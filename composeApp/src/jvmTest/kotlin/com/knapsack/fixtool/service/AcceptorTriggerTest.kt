package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A rule's trigger speaks the scenario matcher vocabulary — the same one, not a second one.
 *
 * The point of reusing [Matcher] rather than growing a conditional DSL is that `>`, `>=`, `<`, `<=`,
 * `between`, `in a set`, `matches`, `is present` and `is absent` all arrive at once and are evaluated
 * by the code that already evaluates them for expectations. These tests are about the trigger's own
 * decisions: what ANDs with what, what is refused, and what happens to a rule nobody can read.
 */
class AcceptorTriggerTest {
    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private fun rule(vararg conditions: FieldCondition, whenFields: Map<String, String> = emptyMap()) =
        AcceptorResponseRule(
            whenMsgType = "D",
            whenFields = whenFields,
            conditions = conditions.toList(),
            steps = listOf(ResponseStep(template = "35=8|39=0|11=\${req.11}|")),
        )

    private fun order(qty: String = "1000", symbol: String = "ACME", extra: String = "") =
        AcceptorResponder.buildMessage("35=D|11=ORD-1|55=$symbol|54=1|38=$qty|$extra")

    private fun matches(rule: AcceptorResponseRule, message: quickfix.Message) =
        AcceptorResponder.firstMatch(AcceptorResponder.compile(listOf(rule)), message) != null

    // ------------------------------------------------------- the vocabulary, applied to a trigger

    @Test
    fun `a bound on a number is a trigger, which exact never could express`() {
        val tooBig = rule(condition(38, Matcher.Range(min = 10_000.0, minInclusive = false)))

        assertTrue(matches(tooBig, order(qty = "10001")), "10001 is above 10000")
        assertTrue(!matches(tooBig, order(qty = "10000")), "an exclusive bound must exclude its own value")
        assertTrue(!matches(tooBig, order(qty = "500")))
    }

    @Test
    fun `presence and absence both trigger`() {
        val hasPrice = rule(condition(44, Matcher.Presence))
        val noPrice = rule(condition(44, Matcher.Absent))

        assertTrue(matches(hasPrice, order(extra = "44=1.25|")))
        assertTrue(!matches(hasPrice, order()))
        assertTrue(matches(noPrice, order()), "a market order has no price, and that is a thing to trigger on")
        assertTrue(!matches(noPrice, order(extra = "44=1.25|")))
    }

    @Test
    fun `oneOf and regex trigger`() {
        assertTrue(matches(rule(condition(55, Matcher.OneOf(listOf("ACME", "WIDGET")))), order(symbol = "WIDGET")))
        assertTrue(!matches(rule(condition(55, Matcher.OneOf(listOf("ACME")))), order(symbol = "WIDGET")))
        assertTrue(matches(rule(condition(55, Matcher.Regex("^TEST.*"))), order(symbol = "TESTCO")))
        assertTrue(!matches(rule(condition(55, Matcher.Regex("^TEST.*"))), order(symbol = "ACME")))
    }

    @Test
    fun `conditions are ANDed, so a rule narrows as they are added`() {
        val both = rule(condition(55, Matcher.Exact("ACME")), condition(38, Matcher.Range(min = 500.0)))

        assertTrue(matches(both, order(symbol = "ACME", qty = "1000")))
        assertTrue(!matches(both, order(symbol = "ACME", qty = "100")), "the quantity bound must still apply")
        assertTrue(!matches(both, order(symbol = "OTHER", qty = "1000")), "the symbol must still apply")
    }

    @Test
    fun `a rule matching nothing lets the next rule have the message`() {
        val specific = rule(condition(55, Matcher.Exact("NOPE"))).copy(steps = listOf(ResponseStep("35=8|39=8|")))
        val fallback = rule().copy(steps = listOf(ResponseStep("35=8|39=0|")))

        val matched = AcceptorResponder.firstMatch(AcceptorResponder.compile(listOf(specific, fallback)), order())

        assertSame(fallback, matched, "first match wins means the first rule that MATCHES, not the first rule")
    }

    // ------------------------------------------------------- the two spellings of a trigger

    @Test
    fun `the older whenFields spelling still triggers, as exact conditions`() {
        val legacy =
            AcceptorResponseRule(
                whenMsgType = "D",
                whenFields = mapOf("55" to "ACME"),
                steps = listOf(ResponseStep(template = "35=8|39=0|")),
            )

        assertTrue(matches(legacy, order(symbol = "ACME")))
        assertTrue(!matches(legacy, order(symbol = "OTHER")))
        assertNull(legacy.validationError())
    }

    @Test
    fun `both spellings on one rule are ANDed, never chosen between`() {
        // The asymmetry with a reply's two spellings, pinned: dropping a constraint would make the
        // rule fire on messages the author excluded, which is the dangerous direction to be wrong in.
        val both = rule(condition(38, Matcher.Range(min = 500.0)), whenFields = mapOf("55" to "ACME"))

        assertEquals(2, both.trigger().size, "neither spelling may be dropped")
        assertTrue(matches(both, order(symbol = "ACME", qty = "1000")))
        assertTrue(!matches(both, order(symbol = "OTHER", qty = "1000")), "the whenFields half must still apply")
        assertTrue(!matches(both, order(symbol = "ACME", qty = "100")), "the conditions half must still apply")
    }

    // ------------------------------------------------------- triggers nobody can read

    @Test
    fun `a reference is refused, because a trigger has no scope to resolve it against`() {
        val bad = rule(condition(11, Matcher.Reference("\${out.D.11}")))

        val problem = bad.validationError()
        assertNotNull(problem)
        assertTrue(problem.contains("no scenario scope"), "the refusal should say why, not merely refuse: $problem")
    }

    @Test
    fun `a rule whose trigger cannot be read is left out rather than left loose`() {
        val unusable = rule(condition(55, Matcher.Regex("[")))
        val fallback = rule().copy(steps = listOf(ResponseStep("35=8|39=0|")))

        assertNotNull(unusable.validationError())
        assertEquals(
            listOf(fallback),
            AcceptorResponder.compile(listOf(unusable, fallback)).map { it.rule },
            "a trigger missing one of its constraints is looser than written — drop the rule, do not run it",
        )
    }

    @Test
    fun `a whenFields key that is not a tag number is named, not silently unmatchable`() {
        val bad =
            AcceptorResponseRule(
                whenMsgType = "D",
                whenFields = mapOf("Symbol" to "ACME"),
                steps = listOf(ResponseStep(template = "35=8|39=0|")),
            )

        val problem = bad.validationError()
        assertNotNull(problem)
        assertTrue(problem.contains("not a tag number"), "got: $problem")
    }

    @Test
    fun `an unbounded range is refused rather than accepting every number`() {
        val problem = rule(condition(38, Matcher.Range())).validationError()

        assertNotNull(problem)
        assertTrue(problem.contains("no bound"), "got: $problem")
    }

    @Test
    fun `a usable matcher trigger has nothing to report`() {
        assertNull(rule(condition(38, Matcher.Range(min = 10_000.0)), whenFields = mapOf("55" to "ACME")).validationError())
    }
}
