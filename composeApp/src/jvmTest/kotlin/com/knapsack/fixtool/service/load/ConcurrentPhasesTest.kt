package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.WireTags
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **The fakes hold two phases at once**, which is what everything reactive has to stand on.
 *
 * A set runs its phases strictly in order today, so nothing in the product drives this yet. What it pins is
 * the rig: two [LoadRunner]s on their own threads, sharing one clock, one host and one pair of lanes, each
 * on its own rate, each reporting the schedule it actually held.
 *
 * The failure it exists to catch is the one a plain `var` clock gives you. Virtual time that jumps to
 * whichever pacer asked first drags the other pacer past its own marks, and that pacer then issues late or
 * issues everything it has left in one go, while still reporting a rate it never held. Which is worse than
 * a red test, because both runs pass their own verdicts. [FakeClock] releases each pacer at its own mark
 * instead, so the number that says so is the lag: nought for both, or the rig is lying.
 */
class ConcurrentPhasesTest {
    private val orders = LoadTemplate("Orders", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val quoteRequests = LoadTemplate("Quotes", listOf(35 to "R", 131 to "Q-\${run}-\${messageIndex}", 55 to "EUR/USD"))

    private fun plan(id: String, template: LoadTemplate, match: LoadMatch, perSecond: Int) =
        LoadPlan(
            id = id,
            label = "$id at $perSecond a second on LOADGEN",
            template = template,
            profileId = "p",
            profileName = "LOADGEN",
            shape = LoadShape.Rate(perSecond = perSecond, forMs = 3_000),
            match = match,
            settleMs = 2_000,
            seed = mapOf("run" to "t1"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
        )

    /** An ExecutionReport for the ClOrdID an order carried. */
    private fun fill(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 11) ?: return emptyList()
        return listOf("8=FIX.4.435=849=VENUE11=$id39=0")
    }

    /** A Quote for the QuoteReqID a request carried, which is a different tag and a different reply type. */
    private fun quote(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 131) ?: return emptyList()
        return listOf("8=FIX.4.435=S49=VENUE131=$id117=QID-$id133=1.09010")
    }

    @Test
    fun `two rate phases run at once down the same lanes, and each holds its own schedule`() {
        val clock = FakeClock()
        val lanes =
            (1..2).map { slot ->
                FakeLane(slot, clock, { emptyList() }).apply {
                    // Per request and not per lane: one lane answers both phases, and neither phase's venue
                    // behaviour is whichever one happened to be installed last.
                    answers("D", ::fill)
                    answers("R", ::quote)
                }
            }
        val host = FakeHost(clock, lanes)

        // Two rates and not one, so the marks the two pacers wait for are genuinely different sequences.
        val reports =
            clock.concurrently(
                { LoadRunner(host, clock = clock).run(plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200)).report },
                { LoadRunner(host, clock = clock).run(plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500)).report },
            )

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), reports.map { it.status })
        assertEquals(listOf(600L, 1_500L), reports.map { it.issue.requested })
        reports.forEach { r ->
            assertEquals(r.issue.requested, r.issue.leftSocket, "${r.id}: everything it asked for left the socket")
            assertEquals(r.issue.requested, r.replies.matched, "${r.id}: and every one of them was answered")
            assertEquals(0L, r.replies.strays, "${r.id}: the other phase's replies are not this one's business")
            val rate = assertNotNull(r.rate, r.id)
            assertEquals(emptyList(), rate.shortfalls, "${r.id}: the schedule was held")
            assertEquals(2_000L, rate.heldForMs, "${r.id}: both full seconds ran at the rate asked for")
            assertEquals(0L, rate.maxLagMs, "${r.id}: and no message went late, so the other phase never dragged this clock past a mark")
            assertEquals(LoadReport.RateVerdict.HELD, r.verdict.rate, r.id)
            assertEquals(0, r.verdict.exitCode, r.id)
        }

        // And the two streams really did share the lanes, rather than one finishing before the other began.
        assertEquals(600, lanes.sumOf { lane -> lane.sent.count { it.contains("35=D") } })
        assertEquals(1_500, lanes.sumOf { lane -> lane.sent.count { it.contains("35=R") } })
    }
}
