package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.FieldCondition
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.OrderState
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
        order: OrderConstraint? = null,
        template: String = "35=8|39=0|11=\${req.11}|",
    ) = AcceptorResponseRule(
        whenMsgType = msgType,
        conditions = conditions.toList(),
        whenOrder = order,
        enabled = enabled,
        steps = listOf(ResponseStep(template = template)),
    )

    private fun order(qty: String = "1000", symbol: String = "ACME", extra: String = "") =
        AcceptorResponder.buildMessage("35=D|11=ORD-1|55=$symbol|54=1|38=$qty|$extra")

    /** A cancel naming ORD-1: two ids, and only 41 is the order the question is about. */
    private val cancel = AcceptorResponder.buildMessage("35=F|11=CXL-4|41=ORD-1|55=ACME|54=1|")

    // What the book said, as the four answers it can give. Taken as values rather than looked up,
    // because that is what the engine receives — see BookReading and decision 6a.
    private val unknown = BookReading.unknown("ORD-1")
    private val pending = BookReading("ORD-1", OrderState.PENDING)
    private val working = BookReading("ORD-1", OrderState.WORKING, leavesQty = "1000")
    private val done = BookReading("ORD-1", OrderState.DONE, leavesQty = "0")

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
                // The book constraint joins the same cross-check rather than getting one of its own:
                // it is a condition, and the claim that the two paths agree has to cover every kind
                // of condition or it is a claim about the ones somebody remembered.
                listOf(rule(order = OrderConstraint.UNKNOWN)),
                listOf(rule(order = OrderConstraint.WORKING)),
                listOf(rule(order = OrderConstraint.PENDING), rule()),
                listOf(rule(order = OrderConstraint.WORKING), rule(order = OrderConstraint.UNKNOWN)),
                listOf(rule(order = OrderConstraint.DONE, conditions = arrayOf(condition(38, Matcher.Exact("1000"))))),
                listOf(rule(order = OrderConstraint.UNKNOWN, enabled = false), rule()),
            )
        // Every state the book can be in, including no book at all — which is the case a caller that
        // forgot to take a reading would land in, and the one whose answer must not be "fires anyway".
        val books = listOf(null, unknown, pending, working, done)

        cases.forEachIndexed { index, rules ->
            books.forEach { book ->
                val explained = AcceptorResponder.explain(rules, message, book).firstOrNull { it.selected }?.rule
                val live = AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), message, book)
                assertEquals(
                    live,
                    explained,
                    "case $index against ${book?.word ?: "no book"}: the dry run and the live matcher must pick the same rule",
                )
            }
        }
    }

    // ------------------------------------------------------------------ the constraint no tag can express

    @Test
    fun `a rule conditioned unknown does not fire for an order the book holds`() {
        val rules = listOf(rule(msgType = "F", order = OrderConstraint.UNKNOWN))

        assertTrue(AcceptorResponder.explain(rules, cancel, unknown).single().selected)
        assertFalse(
            AcceptorResponder.explain(rules, cancel, working).single().matched,
            "the venue is holding it, so the rule that answers 'never heard of it' must not be the one that fires",
        )
    }

    /**
     * The pair from decision 1, and the whole point of the slice: **one rule list, two answers**,
     * chosen by what the venue is holding rather than by which rule the tester left switched on.
     */
    @Test
    fun `the same rule list rejects a cancel for an unknown order and accepts one for a working order`() {
        val rules =
            listOf(
                rule(msgType = "F", order = OrderConstraint.UNKNOWN, template = "35=9|102=1|"),
                rule(msgType = "F", order = OrderConstraint.WORKING, template = "35=8|150=4|"),
            )

        assertEquals(0, AcceptorResponder.explain(rules, cancel, unknown).first { it.selected }.index)
        assertEquals(1, AcceptorResponder.explain(rules, cancel, working).first { it.selected }.index)
        assertNull(
            AcceptorResponder.explain(rules, cancel, done).firstOrNull { it.selected },
            "a cancel for a finished order matches neither, and the venue is entitled to say nothing",
        )
    }

    @Test
    fun `the book constraint reports what it asked and what it read, like any other condition`() {
        val outcome =
            AcceptorResponder
                .explain(listOf(rule(msgType = "F", order = OrderConstraint.WORKING)), cancel, unknown)
                .single()

        val order = assertNotNull(outcome.order, "a rule that asked the book has to report what it heard")
        assertEquals(OrderConstraint.WORKING, order.constraint)
        assertEquals("ORD-1", order.key, "the order it asked about — 41, not the cancel's own 11")
        assertEquals("unknown", order.actual, "what the book said, not what the rule wanted")
        assertFalse(order.satisfied)
        assertFalse(outcome.matched)
    }

    @Test
    fun `a rule that does not ask the book reports nothing about it`() {
        val outcome = AcceptorResponder.explain(listOf(rule(msgType = "F")), cancel, working).single()

        assertNull(outcome.order, "an absent report is how 'this rule does not read the book' is said")
        assertTrue(outcome.matched)
    }

    /**
     * A rule that asks a question nobody can answer must not fire. It is the same call [compile] makes
     * for a trigger it cannot parse, and for the same reason: a rule firing on messages its author
     * excluded is the dangerous direction to be wrong in.
     */
    @Test
    fun `with no book to ask, a rule that asks one does not fire and says why`() {
        val rules = listOf(rule(msgType = "F", order = OrderConstraint.UNKNOWN))

        assertNull(AcceptorResponder.firstMatch(AcceptorResponder.compile(rules), cancel, book = null))
        val order = AcceptorResponder.explain(rules, cancel, book = null).single().order
        assertNull(assertNotNull(order).actual, "no book is not the same answer as an empty one")
        assertFalse(order.satisfied)
    }

    @Test
    fun `a rule carrying only a book constraint shadows nothing, because it is a conditioned rule`() {
        val rules = listOf(rule(msgType = "F", order = OrderConstraint.UNKNOWN), rule(msgType = "F"))

        assertNull(
            AcceptorResponder.shadowingRule(rules, 1),
            "the earlier rule answers only the cancels for orders nobody sent, so the later one is still reachable",
        )
    }
}
