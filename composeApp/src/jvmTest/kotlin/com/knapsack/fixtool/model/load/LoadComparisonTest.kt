package com.knapsack.fixtool.model.load

import com.knapsack.fixtool.service.load.LoadFixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The state that makes Compare trustworthy is the one where it refuses.**
 *
 * A tool that will subtract a run matching `131 QuoteReqID` on a `35=S` from one matching `11 ClOrdID`
 * on a `35=8` produces a confident wrong number, so that case is tested first and by name.
 */
class LoadComparisonTest {
    private fun row(c: LoadComparison, label: String) = (c.deltas + c.deciding + c.context).single { it.label == label }

    @Test
    fun `two runs that measured different exchanges are refused, and the deciding rows are named first`() {
        val rfq =
            LoadFixtures
                .burstReport(
                    unmatched = 0,
                ).let { it.copy(match = LoadMatch(131, 131, "S"), template = it.template.copy(msgType = "R")) }
        val order = LoadFixtures.burstReport(unmatched = 0)

        val c = LoadComparison.of(rfq, order)

        assertFalse(c.comparable)
        assertEquals(emptyList(), c.deltas, "nothing is subtracted when the two measured different things")
        assertEquals(listOf("request type", "match tags", "reply type"), c.deciding.map { it.label })
        assertTrue(c.deciding.all { it.direction == LoadComparison.Direction.DIFFERS }, c.deciding.toString())
        assertEquals(1, c.blockers.size, "one sentence, not one per differing row: ${c.blockers}")
        assertTrue(c.blockers.single().contains("One issued 35=R and matched 131 → 131 on a reply of type S"), c.blockers.toString())
        assertTrue(c.blockers.single().contains("looks like an answer and is not one"), c.blockers.toString())
    }

    /** Lanes, shape, settle and store may all differ. They are context, and never a reason to refuse. */
    @Test
    fun `everything but the exchange may differ, and is shown as context rather than as a delta`() {
        val before = LoadFixtures.burstReport(unmatched = 0)
        val after = before.copy(lanes = 12, shape = LoadShape.Rate(500, 600_000), settleMs = 30_000, storeAndLog = null)

        val c = LoadComparison.of(before, after)

        assertTrue(c.comparable, c.blockers.toString())
        assertEquals(LoadComparison.Direction.DIFFERS, row(c, "lanes").direction)
        assertEquals(LoadComparison.Direction.DIFFERS, row(c, "shape").direction)
        assertTrue(c.deltas.none { it.label in setOf("lanes", "shape", "settle", "store and log") })
    }

    /**
     * The 2026-09-06 acceptor fix, which cost a hand-read of two JSON files: 77 unanswered cleared and the
     * percentiles down by an order of magnitude.
     */
    @Test
    fun `completeness up, latency down, and a count that reached zero reads as cleared`() {
        val base = LoadFixtures.burstReport(unmatched = 0)
        val before =
            base.copy(
                replies = base.replies.copy(matched = 299_923, unmatched = 77),
                roundTrip = base.roundTrip!!.copy(p50 = 61_000, p95 = 212_000, p99 = 1_400_000),
            )
        val after =
            base.copy(
                replies = base.replies.copy(matched = 300_000, unmatched = 0),
                roundTrip = base.roundTrip!!.copy(p50 = 3_100, p95 = 7_900, p99 = 96_000),
            )

        val c = LoadComparison.of(before, after)

        assertEquals("cleared", row(c, "unanswered").delta)
        assertEquals(LoadComparison.Direction.BETTER, row(c, "unanswered").direction)
        assertEquals(LoadComparison.Direction.BETTER, row(c, "answered").direction)
        assertEquals("−95%", row(c, "p50 round trip").delta)
        assertEquals("−96%", row(c, "p95 round trip").delta)
        assertEquals("−93%", row(c, "p99 round trip").delta)
        assertEquals("+77", row(c, "answered").delta, "a change that rounds to 0% is named as the count it is")
    }

    /** A percentage of zero is an arithmetic error with a sign on it, not a big improvement. */
    @Test
    fun `a percentage appears only where the earlier value was not zero`() {
        val base = LoadFixtures.burstReport(unmatched = 0)
        val before = base.copy(replies = base.replies.copy(duplicates = 0), tool = base.tool.copy(discarded = 0))
        val after = base.copy(replies = base.replies.copy(duplicates = 5), tool = base.tool.copy(discarded = 12))

        val c = LoadComparison.of(before, after)

        assertEquals("+12", row(c, "discarded by the panes").delta)
        assertEquals(LoadComparison.Direction.WORSE, row(c, "discarded by the panes").direction)
        assertEquals("changed", row(c, "duplicates").delta, "duplicates are reported, not judged")
        assertEquals(LoadComparison.Direction.UNRANKED, row(c, "duplicates").direction)
    }

    /** The threshold is fixed and applied to both, so the row asks the two runs the same question. */
    @Test
    fun `the stall row counts seconds whose own p95 crossed the same threshold in both runs`() {
        val base = LoadFixtures.burstReport(unmatched = 0)
        val noisy = base.copy(perSecond = (0 until 10).map { LoadReport.Second(it, 500, 500, if (it < 4) 210_000 else 4_000) })
        val calm = base.copy(perSecond = (0 until 10).map { LoadReport.Second(it, 500, 500, 4_000) })

        assertEquals(4, LoadComparison.stalls(noisy, LoadComparison.DEFAULT_THRESHOLD_US))
        assertEquals(0, LoadComparison.stalls(calm, LoadComparison.DEFAULT_THRESHOLD_US))

        val c = LoadComparison.of(noisy, calm)
        assertEquals("cleared", row(c, "seconds over 100ms p95").delta)
        assertEquals(LoadComparison.Direction.BETTER, row(c, "seconds over 100ms p95").direction)
    }

    @Test
    fun `a run with no distribution compares its counts and skips its percentiles`() {
        val base = LoadFixtures.burstReport(unmatched = 4_000)
        val nothing = base.copy(roundTrip = null)

        val c = LoadComparison.of(nothing, base)

        assertTrue(c.comparable)
        assertTrue(c.deltas.none { it.label.endsWith("round trip") }, "nothing to subtract without two distributions")
        assertTrue(c.deltas.any { it.label == "answered" })
    }
}
