package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AcceptorResponder.explain] answers the same question [AcceptorResponder.firstMatch] does, and
 * shows its working — so the thing under test is above all that **the two never disagree**.
 *
 * They are separate implementations on purpose: `firstMatch` runs on the callback thread of a loaded
 * acceptor and short-circuits, `explain` evaluates everything so it can say why. That is a licence to
 * drift, and a dry run that says "this fires" where the wire says nothing is worse than having no dry
 * run at all — it moves the bug from the rule to the tool and costs an afternoon. Hence
 * [selection agrees with the live matcher on every case here], which re-asks each scenario below
 * through both paths.
 */
class AcceptorResponderExplainTest {
    private fun condition(tag: Int, matcher: Matcher) = FieldCondition(tag, MatcherCodec.matcherToJson(matcher))

    private fun rule(
        msgType: String = "D",
        vararg conditions: FieldCondition,
        enabled: Boolean = true,
        template: String = "35=8|39=0|11=\${req.11}|",
    ) = AcceptorResponseRule(
        whenMsgType = msgType,
        conditions = conditions.toList(),
        enabled = enabled,
        steps = listOf(ResponseStep(template = template)),
    )

    private fun order(qty: String = "1000", symbol: String = "ACME", extra: String = "") =
        AcceptorResponder.buildMessage("35=D|11=ORD-1|55=$symbol|54=1|38=$qty|$extra")

    // ------------------------------------------------------------------ the verdict

    @Test
    fun `the first matching rule is selected and the ones after it are not`() {
        val rules = listOf(rule(), rule())
        val outcomes = AcceptorResponder.explain(rules, order())

        assertTrue(outcomes[0].selected, "the first rule matches, so it wins")
        assertTrue(outcomes[1].matched, "the second rule's own trigger matches just as well")
        assertFalse(outcomes[1].selected, "but first match wins, so it never fires")
    }

    @Test
    fun `a condition reports the value it actually read`() {
        val outcomes = AcceptorResponder.explain(listOf(rule(conditions = arrayOf(condition(38, Matcher.Exact("500"))))), order(qty = "1000"))

        val condition = outcomes.single().conditions.single()
        assertEquals(38, condition.tag)
        assertEquals("1000", condition.actual, "the reader must report what the message says, not what the rule wanted")
        assertFalse(condition.satisfied)
        assertFalse(outcomes.single().matched)
    }

    /**
     * The commonest cause of a rule that never fires, and the one the outcome alone cannot explain:
     * a condition on a tag the message does not carry fails identically to one whose value is wrong.
     */
    @Test
    fun `a condition on an absent tag is reported as absent, not as an empty value`() {
        val outcomes = AcceptorResponder.explain(listOf(rule(conditions = arrayOf(condition(44, Matcher.Exact("1.25"))))), order())

        val condition = outcomes.single().conditions.single()
        assertNull(condition.actual, "tag 44 is not on the message at all — that is not the same as blank")
        assertFalse(condition.satisfied)
    }

    @Test
    fun `a disabled rule is reported as skipped rather than as unmatched`() {
        val outcomes = AcceptorResponder.explain(listOf(rule(enabled = false)), order())

        val outcome = outcomes.single()
        assertNotNull(outcome.skipped, "'disabled' and 'did not match' need different fixes, so they must read differently")
        assertTrue(outcome.skipped!!.contains("disabled"))
        assertFalse(outcome.matched)
        assertTrue(outcome.conditions.isEmpty(), "a rule that never reached the matcher has no condition verdicts to give")
    }

    @Test
    fun `a rule whose trigger cannot be parsed is named, not silently dropped`() {
        val unusable =
            AcceptorResponseRule(
                whenMsgType = "D",
                conditions = listOf(FieldCondition(38, MatcherCodec.matcherToJson(Matcher.Regex("[")))),
                steps = listOf(ResponseStep(template = "35=8|")),
            )
        val outcomes = AcceptorResponder.explain(listOf(unusable), order())

        // compile() drops this rule entirely; absent from the result it would be indistinguishable
        // from a rule that simply did not match, which is the state this whole surface exists to
        // tell apart.
        assertEquals(1, outcomes.size, "the rule must still be reported, at its own index")
        assertNotNull(outcomes.single().skipped)
        assertFalse(outcomes.single().matched)
    }

    @Test
    fun `indices address the rules as written, including skipped ones`() {
        val rules = listOf(rule(enabled = false), rule(msgType = "F"), rule())
        val outcomes = AcceptorResponder.explain(rules, order())

        assertEquals(listOf(0, 1, 2), outcomes.map { it.index })
        assertEquals(2, outcomes.single { it.selected }.index, "the third rule is the only one that can fire")
    }

    // ------------------------------------------------------------------ the invariant

    /**
     * Every case above, re-asked through the path that actually runs on the wire. If these two ever
     * disagree the dry run is lying, and the point of the dry run is that it does not.
     */
    @Test
    fun `selection agrees with the live matcher on every case here`() {
        val message = order(qty = "1000", extra = "44=1.25|")
        val cases: List<List<AcceptorResponseRule>> =
            listOf(
                listOf(rule(), rule()),
                listOf(rule(conditions = arrayOf(condition(38, Matcher.Exact("500"))))),
                listOf(rule(conditions = arrayOf(condition(38, Matcher.Exact("1000"))))),
                listOf(rule(conditions = arrayOf(condition(99, Matcher.Presence)))),
                listOf(rule(enabled = false)),
                listOf(rule(enabled = false), rule()),
                listOf(rule(msgType = "F"), rule()),
                listOf(rule(msgType = "F")),
                emptyList(),
            )

        cases.forEachIndexed { index, rules ->
            val explained = AcceptorResponder.explain(rules, message).firstOrNull { it.selected }?.rule
            val live = AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), message)
            assertEquals(live, explained, "case $index: the dry run and the live matcher must pick the same rule")
        }
    }
}
