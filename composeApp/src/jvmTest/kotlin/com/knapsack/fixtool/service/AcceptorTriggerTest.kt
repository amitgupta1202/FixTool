package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ------------------------------------------------------- not-equals, and switching a rule off

    @Test
    fun `notEqual triggers on anything but the value`() {
        val notRejectSymbol = rule(condition(55, Matcher.NotEqual("REJECT")))

        assertTrue(matches(notRejectSymbol, order(symbol = "ACME")))
        assertTrue(!matches(notRejectSymbol, order(symbol = "REJECT")))
    }

    @Test
    fun `notEqual is not satisfied by a tag that never arrived`() {
        // "not X" about a field the message does not carry is a question with no answer. Passing it
        // would let a whole missing field through as a successful negative.
        assertTrue(!matches(rule(condition(44, Matcher.NotEqual("1.25"))), order()))
        assertTrue(matches(rule(condition(44, Matcher.NotEqual("1.25"))), order(extra = "44=9.99|")))
    }

    @Test
    fun `a disabled rule is skipped, and the message falls to the next rule`() {
        val off = rule(condition(55, Matcher.Exact("ACME"))).copy(enabled = false, steps = listOf(ResponseStep("35=8|39=8|")))
        val on = rule().copy(steps = listOf(ResponseStep("35=8|39=0|")))

        assertSame(on, AcceptorResponder.firstMatch(AcceptorResponder.compile(listOf(off, on)), order()))
        assertEquals(
            listOf(on),
            AcceptorResponder.compile(listOf(off, on)).map { it.rule },
            "a rule switched off must not reach the matcher at all",
        )
    }

    @Test
    fun `a disabled rule is kept, not deleted, and says nothing is wrong with it`() {
        val off = rule().copy(enabled = false)

        assertNull(off.validationError(), "switching a rule off is not a fault to report")
        assertEquals(1, off.sequence().size, "its reply is still there, waiting to be switched back on")
    }

    // ------------------------------------------------------- the constraint no tag can express

    @Test
    fun `a rule written before the book existed asks it nothing and is not faulted for it`() {
        val old = rule()

        assertNull(old.whenOrder, "the field defaults to asking nothing, which is what every profile on disk says")
        assertNull(old.validationError())
        assertTrue(old.isUnconditional(), "and it still answers every message of its type")
    }

    @Test
    fun `a rule carrying only a book constraint is a conditioned rule`() {
        val conditioned = rule().copy(whenOrder = OrderConstraint.WORKING)

        assertTrue(conditioned.trigger().isEmpty(), "it places no condition on any tag")
        assertFalse(
            conditioned.isUnconditional(),
            "and is still narrowed — read off trigger() alone it would be appended below the rule it must precede",
        )
    }

    /**
     * The structural refusal (settled open question 1). A reply that reads `${order.…}` cannot be sent
     * for an order the venue will not have: every reference would substitute empty and the venue would
     * put `37=` on the wire as a real field with no value. Refused on the rule, the same way the fill
     * presets are conditioned on `40 = 2` rather than testing for a price at send time.
     *
     * Asked of `35=F` because a cancel does not bring an order with it — see the test below for the
     * message type that does.
     */
    @Test
    fun `a reply that reads the book must require an order to read`() {
        val reads =
            rule().copy(whenMsgType = "F", steps = listOf(ResponseStep("35=8|37=\${order.orderId}|11=\${req.11}|")))

        val problem = assertNotNull(reads.validationError(), "an unconditioned rule reading the book is a broken rule")
        assertTrue(problem.contains("\${order."), "the problem names what it found: $problem")

        assertNotNull(
            reads.copy(whenOrder = OrderConstraint.UNKNOWN).validationError(),
            "'unknown' is precisely the state in which there is nothing to read",
        )
        assertNull(reads.copy(whenOrder = OrderConstraint.PENDING).validationError())
        assertNull(reads.copy(whenOrder = OrderConstraint.WORKING).validationError())
        assertNull(reads.copy(whenOrder = OrderConstraint.DONE).validationError())
    }

    /**
     * **The message that creates the order is its own guarantee.**
     *
     * A `35=D` opens the book entry itself — the wire feeds the book before the rules are asked — so
     * an order exists by the time any step of the reply is rendered, even though `whenOrder` a moment
     * earlier read `unknown` (decision 4a). Without this, an accumulating fill sequence could not be
     * written as a rule at all: it answers the very message that brings the order, so no constraint it
     * could carry would ever hold.
     */
    @Test
    fun `a reply to the message that creates the order may read the book with no constraint at all`() {
        val fill =
            rule().copy(
                whenMsgType = "D",
                steps =
                    listOf(
                        ResponseStep("35=8|150=0|39=0|11=\${req.11}|151=\${req.38}|"),
                        ResponseStep("35=8|150=F|14=\${order.leavesQty / 2}|", delayMillis = 250),
                    ),
            )

        assertNull(fill.validationError(), "the 35=D that triggers this is what puts the order in the book")
        assertTrue(fill.willHaveAnOrder())
        assertFalse(
            fill.copy(whenMsgType = "F").willHaveAnOrder(),
            "a cancel brings no order with it, so the same reply on 35=F is unsafe",
        )
    }

    /**
     * The word on disk is the word an author writes and reads. A rule saved as `WORKING` and shown as
     * `working` would be two vocabularies for one field, and hand-editing a profile is a supported way
     * to reach this feature.
     */
    @Test
    fun `the constraint is spelled the same in a profile as it is on the card`() {
        val json = Json { encodeDefaults = false }
        val rule = rule().copy(whenOrder = OrderConstraint.WORKING)

        val encoded = json.encodeToString(AcceptorResponseRule.serializer(), rule)

        assertTrue(encoded.contains("\"whenOrder\":\"working\""), "encoded as: $encoded")
        assertEquals(rule, json.decodeFromString(AcceptorResponseRule.serializer(), encoded))
    }

    @Test
    fun `a profile written before this field existed still loads`() {
        val old = """{"whenMsgType":"D","steps":[{"template":"35=8|39=0|"}]}"""

        val parsed = Json.decodeFromString(AcceptorResponseRule.serializer(), old)

        assertNull(parsed.whenOrder)
        assertNull(parsed.validationError())
    }
}
