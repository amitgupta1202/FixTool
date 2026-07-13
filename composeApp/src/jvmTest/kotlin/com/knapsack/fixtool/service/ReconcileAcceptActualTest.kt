package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Accept actual" must re-seed the row, not flatten it to `exact`.
 *
 * This is open question 4 — *"per-row re-seed, or does Accept-actual already cover it?"* — and the answer
 * turned out to be that Accept-actual did not merely fail to cover it, it was actively destructive: it
 * replaced **whatever matcher the row had** with `Exact(actual)`. The kind was the author's (or the
 * seeder's) statement of what *sort* of thing the field is, and one click threw it away.
 *
 * The reconcile view is where a tired engineer clicks buttons to turn a red build green, which makes it
 * the likeliest place in the tool to manufacture a bad assertion. A button that quietly narrows an
 * assertion every time it is pressed belongs nowhere near it.
 */
class ReconcileAcceptActualTest {
    private fun expectation(vararg fields: FieldExpectation) = Expectation(fields.toList())

    /**
     * A numeric row keeps its tolerance — and, just as importantly, keeps being *numeric*.
     *
     * `Numeric(x, tolerance = 0)` is not the same as `Exact(x)`: it parses both sides as numbers, so it is
     * robust to a venue that starts sending `500000.00` where it used to send `500000`. Flattening it to
     * `Exact("500000")` throws that away silently, and the scenario goes red on a formatting change that is
     * not a behaviour change at all — the exact thing the numeric matcher exists to absorb.
     */
    @Test
    fun `accepting a numeric value keeps it numeric, with its tolerance`() {
        val draft = expectation(FieldExpectation(151, Matcher.Numeric(1_000_000.0, tolerance = 0.5)))

        val after = ScenarioReconcile.acceptActual(draft, index = 0, actual = "500000")

        assertEquals(Matcher.Numeric(500_000.0, tolerance = 0.5), after.fields[0].matcher)

        // And the point of keeping it numeric: the venue may reformat without breaking the scenario.
        val results = ExpectationEvaluator.evaluate(FixMessageView.ofFields(listOf(151 to "500000.00")), after)
        assertTrue(results.single().passed, "a numeric row must survive a formatting change: $results")
    }

    /** An `exact` row is the ordinary case, and it is unchanged. */
    @Test
    fun `accepting an exact value rewrites it to the new value`() {
        val draft = expectation(FieldExpectation(39, Matcher.Exact("2")))

        val after = ScenarioReconcile.acceptActual(draft, index = 0, actual = "8")

        assertEquals(Matcher.Exact("8"), after.fields[0].matcher)
    }

    /**
     * A temporal row cannot be "accepted", and the view must not offer it.
     *
     * `~now ±60s` failing does not mean the venue changed its behaviour — it means the timestamp it sent is
     * outside the window, which is a statement about a *moment*, not a value. "Accepting" it pins the
     * assertion to `20260713-11:02:44`, a timestamp that will never occur again, and the step is red on
     * every subsequent run forever. The author then does the only thing left — loosens it to `presence` or
     * drops it — and the scenario quietly stops checking the timestamp at all.
     *
     * Same reasoning as the reference matcher, which the view already refuses to offer this on.
     */
    @Test
    fun `a temporal row is never offered accept-actual, because there is nothing to accept`() {
        val temporal = Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, toleranceSeconds = 60)

        assertTrue(
            !ScenarioReconcile.canAcceptActual(temporal),
            "accepting a timestamp pins the scenario to a moment that will never come again",
        )
        assertTrue(ScenarioReconcile.canAcceptActual(Matcher.Exact("2")))
        assertTrue(ScenarioReconcile.canAcceptActual(Matcher.Numeric(1.0, 0.0)))
        assertTrue(
            !ScenarioReconcile.canAcceptActual(Matcher.Reference("\${out.D.11}")),
            "accepting an echoed id pins the scenario to this run's id and deletes the correlation check",
        )
    }

    /**
     * And if it is somehow reached anyway, it must not silently write the dead value: a temporal row keeps
     * its matcher. Belt to the braces above, because the cost of getting this wrong is a scenario that
     * cannot pass and an author who deletes the assertion to make it stop.
     */
    @Test
    fun `accepting a temporal row leaves the matcher alone rather than pinning a dead timestamp`() {
        val temporal = Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, toleranceSeconds = 60)
        val draft = expectation(FieldExpectation(60, temporal))

        val after = ScenarioReconcile.acceptActual(draft, index = 0, actual = "20260713-11:02:44")

        assertEquals(temporal, after.fields[0].matcher)
    }
}
