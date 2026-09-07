package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.SocketStamp
import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The matcher is one object over every session, fed stamps, and its counts are exact.**
 *
 * These are the acceptance criteria of #43's comment, one test each: a reply is matched wherever it lands,
 * a repeat is a duplicate, nothing is aged out before settle closes, a reply after that is late, and a
 * hundred thousand exchanges produce a hundred thousand matches with the right percentiles.
 */
class StampMatcherTest {
    private val laneA = SessionID("FIX.4.4", "LOADGEN01", "VENUE")
    private val laneB = SessionID("FIX.4.4", "LOADGEN02", "VENUE")
    private val dropCopy = SessionID("FIX.4.4", "DROPCOPY", "VENUE")

    private fun matcher(replyType: String? = null) =
        StampMatcher(
            match = LoadMatch(requestTag = 11, replyTag = 11, replyType = replyType),
            requestType = "D",
            issuing = setOf(laneA, laneB),
            laneOf = { if (it == laneA) 1 else 2 },
        )

    private fun send(on: SessionID, id: String, at: Long, type: String = "D") =
        SocketStamp(on, WireDirection.SEND, "8=FIX.4.4|35=$type|49=${on.senderCompID}|11=$id|55=EUR/USD|", at)

    private fun receive(on: SessionID, id: String, at: Long, type: String = "8") =
        SocketStamp(on, WireDirection.RECEIVE, "8=FIX.4.4|35=$type|49=VENUE|11=$id|37=O-$id|39=0|", at)

    @Test
    fun `a request on one session answered on another is matched once, with its round trip`() {
        val m = matcher()

        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.onStamp(receive(dropCopy, "ORD-1", at = 15_000))

        val result = m.finish()
        assertEquals(1, result.counts.leftSocket)
        assertEquals(1, result.counts.matched)
        assertEquals(0, result.counts.pendingNow)
        assertEquals(emptyList(), result.unmatched)
        assertEquals(14_000L, result.roundTripsSorted.single())
        assertEquals(1, result.specimens.single().let { if (it.request.contains("35=D") && it.reply.contains("35=8")) 1 else 0 })
    }

    /**
     * **The measurements the charts need, correct during the run and not only after it.**
     *
     * `perSecond` used to be rebuilt in `buckets()` and reached the report only through a `Result`, which
     * exists once, at the end — so the live document had nothing to draw for the whole of a ten-minute run.
     */
    @Test
    fun `the per-second story is readable while the run is still going`() {
        val m = matcher()

        m.onStamp(send(laneA, "ORD-1", at = 1_000_000))
        m.onStamp(receive(laneA, "ORD-1", at = 1_004_000))
        m.onStamp(send(laneA, "ORD-2", at = 2_500_000))
        m.onStamp(receive(laneA, "ORD-2", at = 2_600_000))

        val live = m.bucketsSoFar()
        assertEquals(2, live.size, "two seconds have happened, and both are readable now")
        assertEquals(1, live[0].issued)
        assertEquals(1, live[0].matched)
        assertEquals(4_000L, live[0].p95Micros)
        assertEquals(100_000L, live[1].p95Micros)
        assertEquals(live, m.finish().perSecond, "and the final report says exactly what the live read said")
    }

    /** Thirty counts, whatever the run's size, and correct as replies land rather than at the end. */
    @Test
    fun `the round-trip histogram counts every match into its own log bucket`() {
        val m = matcher()

        // 4ms, 40ms, 400ms: one decade apart, so five buckets apart.
        listOf(4_000L, 40_000L, 400_000L).forEachIndexed { i, rtt ->
            m.onStamp(send(laneA, "ORD-$i", at = 1_000_000L + i))
            m.onStamp(receive(laneA, "ORD-$i", at = 1_000_000L + i + rtt))
        }

        val live = m.histogramSoFar()
        assertEquals(RoundTripHistogram.BUCKETS, live.size)
        assertEquals(3, live.sum(), "every match is in exactly one bucket")
        val at = listOf(4_000L, 40_000L, 400_000L).map { RoundTripHistogram.indexOf(it) }
        assertEquals(listOf(RoundTripHistogram.PER_DECADE, RoundTripHistogram.PER_DECADE), at.zipWithNext { a, b -> b - a })
        at.forEach { assertEquals(1, live[it]) }
        assertEquals(live, m.finish().histogram)
    }

    /**
     * **Per-lane completeness, which is sound.** Per-lane *latency* is not, until each lane renders ahead
     * of its own sends: one pacer loop drives every lane round-robin, so lane N is issued systematically
     * later than lane 1 and would look worse forever. Completeness is unaffected by the ordering.
     */
    @Test
    fun `completeness is counted per lane, duplicates included`() {
        val m = matcher()

        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.onStamp(receive(laneA, "ORD-1", at = 2_000))
        m.onStamp(receive(laneA, "ORD-1", at = 3_000))
        m.onStamp(send(laneB, "ORD-2", at = 1_000))
        m.onStamp(receive(laneB, "ORD-2", at = 2_000))
        m.onStamp(send(laneB, "ORD-3", at = 1_000))

        val lanes = m.finish().perLane.associateBy { it.slot }
        assertEquals(StampMatcher.LaneCounts(1, matched = 1, unanswered = 0, duplicates = 1), lanes.getValue(1))
        assertEquals(StampMatcher.LaneCounts(2, matched = 1, unanswered = 1, duplicates = 0), lanes.getValue(2))
    }

    @Test
    fun `the same id arriving again is a duplicate, reported beside matched rather than judged`() {
        val m = matcher()

        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.onStamp(receive(laneA, "ORD-1", at = 2_000))
        m.onStamp(receive(laneA, "ORD-1", at = 3_000))
        m.onStamp(receive(laneA, "ORD-1", at = 4_000))

        val counts = m.snapshot()
        assertEquals(1, counts.matched)
        assertEquals(2, counts.duplicates)
        assertEquals(0, counts.strays)
    }

    @Test
    fun `heartbeats, logons and a reply of the wrong type never match anything`() {
        val m = matcher(replyType = "8")

        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.onStamp(SocketStamp(laneA, WireDirection.RECEIVE, "8=FIX.4.4|35=0|49=VENUE|", 1_500))
        m.onStamp(SocketStamp(laneA, WireDirection.RECEIVE, "8=FIX.4.4|35=A|49=VENUE|98=0|108=30|", 1_600))
        m.onStamp(receive(laneA, "ORD-1", at = 2_000, type = "j"))

        assertEquals(1, m.snapshot().pendingNow, "a BusinessMessageReject carrying the id is not the answer when the reply type is 8")
        m.onStamp(receive(laneA, "ORD-1", at = 3_000, type = "8"))
        assertEquals(0, m.snapshot().pendingNow)
        assertEquals(1, m.snapshot().matched)
    }

    @Test
    fun `a send is pending until settle closes, and a reply after that is late, not matched`() {
        val m = matcher()

        m.onStamp(send(laneB, "ORD-1", at = 1_000))
        m.onStamp(send(laneB, "ORD-2", at = 1_001))
        m.onStamp(receive(laneB, "ORD-2", at = 60 * 60 * 1_000_000L))
        assertEquals(1, m.snapshot().pendingNow, "an hour later it is still pending")

        val unmatched = m.closeSettle()
        assertEquals(listOf("ORD-1"), unmatched.map { it.id })
        assertEquals(2, unmatched.single().laneSlot)
        assertTrue(unmatched.single().wire.contains("11=ORD-1"), "the record keeps the bytes of what went unanswered")

        m.onStamp(receive(laneA, "ORD-1", at = 60 * 60 * 1_000_000L + 5))
        val result = m.finish()
        assertEquals(1, result.counts.late)
        assertEquals(1, result.counts.matched)
        assertEquals(listOf("ORD-1"), result.unmatched.map { it.id }, "late does not rewrite the verdict taken at close")
    }

    @Test
    fun `a send from a listen-only session is not issued, and a reply to nothing we sent is a stray`() {
        val m = matcher()

        m.onStamp(send(dropCopy, "OTHER-1", at = 1_000))
        m.onStamp(receive(dropCopy, "OTHER-1", at = 2_000))
        m.onStamp(receive(dropCopy, "SOMEONE-ELSES", at = 2_500))

        val counts = m.snapshot()
        assertEquals(0, counts.leftSocket)
        assertEquals(0, counts.matched)
        assertEquals(2, counts.strays)
    }

    @Test
    fun `a hundred thousand exchanges are a hundred thousand matches with exact percentiles`() {
        val m = matcher()
        val n = 100_000
        val t0 = 1_000_000_000L
        for (i in 1..n) m.onStamp(send(if (i % 2 == 0) laneA else laneB, "ORD-$i", at = t0 + i))
        // Reply i lands i microseconds after its request, so the round trips are exactly 1..n.
        for (i in 1..n) m.onStamp(receive(if (i % 3 == 0) dropCopy else laneA, "ORD-$i", at = t0 + i + i))

        val result = m.finish()
        assertEquals(n.toLong(), result.counts.leftSocket)
        assertEquals(n.toLong(), result.counts.matched)
        assertEquals(0, result.counts.pendingNow)
        assertEquals(0, result.counts.duplicates)
        assertEquals(n, result.roundTripsSorted.size)
        val d = assertNotNull(RunSetStats.of(result.roundTripsSorted))
        assertEquals(1L, d.min)
        assertEquals(50_000L, d.p50)
        assertEquals(95_000L, d.p95)
        assertEquals(99_000L, d.p99)
        assertEquals(100_000L, d.max)
        assertEquals(50_000L, d.mean)
        assertEquals(StampMatcher.DEFAULT_SPECIMENS, result.specimens.size)
        assertEquals(n, result.pendingPeak, "every request was outstanding before the first reply")
    }

    @Test
    fun `per-second buckets count issued and matched from the first send`() {
        val m = matcher()
        val t0 = 5_000_000L
        m.onStamp(send(laneA, "A", at = t0))
        m.onStamp(send(laneA, "B", at = t0 + 400_000))
        m.onStamp(send(laneA, "C", at = t0 + 1_200_000))
        m.onStamp(receive(laneA, "A", at = t0 + 100_000))
        m.onStamp(receive(laneA, "C", at = t0 + 2_500_000))

        val buckets = m.finish().perSecond
        assertEquals(3, buckets.size)
        assertEquals(listOf(2, 1, 0), buckets.map { it.issued })
        assertEquals(listOf(1, 0, 1), buckets.map { it.matched })
        assertEquals(100_000L, buckets[0].p95Micros)
        assertNull(buckets[1].p95Micros)
        assertEquals(1_300_000L, buckets[2].p95Micros)
    }
}
