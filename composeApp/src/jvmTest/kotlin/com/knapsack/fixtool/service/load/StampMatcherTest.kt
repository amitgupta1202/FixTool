package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
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
