package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.RoundTripHistogram
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.SocketStamp
import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun matcher(
        replyType: String? = null,
        captures: List<Pair<String, Int>> = emptyList(),
        table: StampMatcher.CaptureTable? = null,
    ) = StampMatcher(
        match = LoadMatch(requestTag = 11, replyTag = 11, replyType = replyType),
        requestType = "D",
        issuing = setOf(laneA, laneB),
        laneOf = { if (it == laneA) 1 else 2 },
        captures = captures,
        table = table,
    )

    /** A reply carrying a QuoteID and a price, which is what a capture is read off. */
    private fun quote(id: String, at: Long, quoteId: String? = "QID-$id", offer: String? = "1.09010") =
        SocketStamp(
            laneA,
            WireDirection.RECEIVE,
            "8=FIX.4.4|35=8|49=VENUE|11=$id|" + (quoteId?.let { "117=$it|" } ?: "") + (offer?.let { "133=$it|" } ?: ""),
            at,
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
        assertEquals(1, lanes.getValue(1).matched)
        assertEquals(1, lanes.getValue(1).duplicates)
        assertEquals(0, lanes.getValue(1).unanswered)
        assertEquals(1, lanes.getValue(2).matched)
        assertEquals(1, lanes.getValue(2).unanswered)
        assertEquals(0, lanes.getValue(2).duplicates)
    }

    /**
     * **Per-lane latency, which is only worth collecting now each lane renders ahead of its own sends.**
     *
     * From the lane's own log histogram, reported as the bucket's lower edge: an interpolated per-lane
     * percentile is a number nothing measured, and the question a lane table answers is which lane is out
     * of line. The aggregate percentiles stay exact, off the sorted samples.
     */
    @Test
    fun `each lane carries its own round-trip percentiles, from its own buckets`() {
        val m = matcher()

        // Lane A answers in about 1ms every time; lane B in about 100ms.
        repeat(20) { i ->
            m.onStamp(send(laneA, "A-$i", at = 1_000_000L + i))
            m.onStamp(receive(laneA, "A-$i", at = 1_000_000L + i + 1_000))
            m.onStamp(send(laneB, "B-$i", at = 1_000_000L + i))
            m.onStamp(receive(laneB, "B-$i", at = 1_000_000L + i + 100_000))
        }

        val lanes = m.finish().perLane.associateBy { it.slot }
        assertEquals(RoundTripHistogram.lowerMicros(RoundTripHistogram.indexOf(1_000)), lanes.getValue(1).p95Micros)
        assertEquals(RoundTripHistogram.lowerMicros(RoundTripHistogram.indexOf(100_000)), lanes.getValue(2).p95Micros)
        assertEquals(lanes.getValue(1).p50Micros, lanes.getValue(1).p95Micros, "one bucket, so both percentiles land in it")
        assertTrue(lanes.getValue(2).p95Micros!! > lanes.getValue(1).p95Micros!!, "the slow lane reads as the slow lane")
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

    /**
     * **The three answers the set's reply router asks for**, and the fact that `offer` counts no stray of
     * its own.
     *
     * A reply of the shape this phase waits for carrying an id it never issued is UNKNOWN, not nothing:
     * that is the difference between "try the phase before this one" and "this is somebody's stray", and
     * the router cannot route without it.
     */
    @Test
    fun `offer separates a reply this phase never issued from one that is nothing at all`() {
        val m = matcher(replyType = "8")

        m.onStamp(send(laneA, "ORD-1", at = 1_000))

        assertEquals(StampMatcher.Claim.MINE, m.offer(receive(laneA, "ORD-1", at = 2_000)), "the answer to its own send")
        assertEquals(StampMatcher.Claim.MINE, m.offer(receive(laneA, "ORD-1", at = 3_000)), "and a second copy of it")
        val unknown = m.offer(receive(laneA, "ORD-9", at = 4_000))
        assertEquals(StampMatcher.Claim.UNKNOWN, unknown, "the shape it waits for, an id it never issued")
        val wrongType = m.offer(receive(laneA, "ORD-9", at = 5_000, type = "S"))
        assertEquals(StampMatcher.Claim.NOT_A_REPLY, wrongType, "the wrong type is evidence of nothing")
        val heartbeat = SocketStamp(laneA, WireDirection.RECEIVE, "8=FIX.4.4|35=0|49=VENUE|", 5_100)
        assertEquals(StampMatcher.Claim.NOT_A_REPLY, m.offer(heartbeat))
        val noId = SocketStamp(laneA, WireDirection.RECEIVE, "8=FIX.4.4|35=8|49=VENUE|39=0|", 5_200)
        assertEquals(StampMatcher.Claim.NOT_A_REPLY, m.offer(noId), "no id to match on")

        assertEquals(0, m.snapshot().strays, "offer counts nothing: whose stray it is, is the router's to decide")
        m.countStray()
        assertEquals(1, m.snapshot().strays)
        assertEquals(1, m.snapshot().duplicates, "the duplicate was still this phase's own")
    }

    /**
     * **A phase names its own stamps whether or not it captures anything.**
     *
     * The message index used to reach the matcher only when the phase had somewhere to put a captured
     * value, so a capture-less phase of a set answered "not mine" to the sends it was making. Every phase
     * needs the answer now, because routing a send by its issuer is the only thing stopping two phases
     * issuing the same MsgType from taking each other's counts.
     */
    @Test
    fun `a matcher that captures nothing owns the sends it issued, and the replies to them`() {
        val m = matcher(replyType = "8")
        val ours = send(laneA, "ORD-1", at = 1_000)

        assertFalse(m.owns(ours), "nothing has been handed over yet")
        m.issued("ORD-1", 1)
        assertTrue(m.owns(ours), "handed over on the way to the socket, so the stamp behind it is this phase's")
        assertFalse(m.owns(send(laneA, "ORD-2", at = 1_100)), "and a send this phase never made is not")
        assertEquals(StampMatcher.Claim.MINE, m.offer(ours))

        val reply = receive(laneA, "ORD-1", at = 2_000)
        assertTrue(m.owns(reply), "the id is outstanding")
        assertEquals(StampMatcher.Claim.MINE, m.offer(reply))
        assertTrue(m.owns(reply), "and a second copy is this phase's duplicate rather than anybody's stray")
        assertFalse(m.owns(receive(laneA, "ORD-9", at = 2_100)), "an id nothing here issued")
        assertFalse(m.owns(receive(laneA, "ORD-1", at = 2_200, type = "S")), "and the wrong reply type never reads as its own")
    }

    /**
     * **A phase that has ended still owns what it never got an answer to.**
     *
     * [StampMatcher.closeSettle] freezes the unmatched set and leaves `pending` where it is, which is what
     * lets a reply arriving two phases later be counted as this phase's late one rather than as the
     * running phase's stray.
     */
    @Test
    fun `a request left unanswered is still owned after the settle window closes`() {
        val m = matcher(replyType = "8")
        m.issued("ORD-1", 1)
        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.closeSettle()

        val late = receive(laneA, "ORD-1", at = 60 * 60 * 1_000_000L)
        assertTrue(m.owns(late))
        assertEquals(StampMatcher.Claim.MINE, m.offer(late))
        assertEquals(1L, m.snapshot().late)
    }

    /**
     * **Why ownership takes the stamp and not an id.**
     *
     * Two phases of one set can wait on different tags of different message types. Nothing above these two
     * could read "the id" off a stamp and hold it up to both: the quote phase's is tag 131 of a `35=S`,
     * the order phase's is tag 11 of a `35=8`. Each matcher is asked with its own tags and its own
     * filters, and answers only for its own.
     */
    @Test
    fun `two phases matched on different tags each own only their own stamps`() {
        val orders = matcher(replyType = "8")
        val quotes =
            StampMatcher(
                match = LoadMatch(requestTag = 131, replyTag = 131, replyType = "S"),
                requestType = "R",
                issuing = setOf(laneA, laneB),
            )
        orders.issued("ORD-1", 1)
        quotes.issued("Q-1", 1)
        val orderSend = send(laneA, "ORD-1", at = 1_000)
        val quoteSend = SocketStamp(laneA, WireDirection.SEND, "8=FIX.4.4|35=R|49=LOADGEN01|131=Q-1|55=EUR/USD|", 1_100)

        assertTrue(orders.owns(orderSend))
        assertFalse(quotes.owns(orderSend), "the wrong request type, and no 131 to read anyway")
        assertTrue(quotes.owns(quoteSend))
        assertFalse(orders.owns(quoteSend), "the quote request is not an order however it is read")
        orders.offer(orderSend)
        quotes.offer(quoteSend)

        val fill = receive(laneA, "ORD-1", at = 2_000)
        val quote = SocketStamp(laneA, WireDirection.RECEIVE, "8=FIX.4.4|35=S|49=VENUE|131=Q-1|117=QID-1|133=1.09010|", 2_100)
        assertTrue(orders.owns(fill))
        assertFalse(quotes.owns(fill))
        assertTrue(quotes.owns(quote))
        assertFalse(orders.owns(quote))
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

    /**
     * **A captured value lands at the index its request was issued for**, which is the whole mechanism: a
     * later phase looks its captures up by the same `${'$'}{messageIndex}` its own ids are built from.
     */
    @Test
    fun `a capture is kept at the index its request was issued for`() {
        val table = StampMatcher.CaptureTable(listOf("quoteId", "offer"), size = 5)
        val m = matcher(captures = listOf("quoteId" to 117, "offer" to 133), table = table)

        (1..3).forEach { i ->
            m.issued("ORD-$i", i)
            m.onStamp(send(laneA, "ORD-$i", at = i * 1_000L))
            m.onStamp(quote("ORD-$i", at = i * 1_000L + 500, offer = "1.0901$i"))
        }

        assertEquals("QID-ORD-1", table["quoteId", 1])
        assertEquals("QID-ORD-3", table["quoteId", 3])
        assertEquals("1.09013", table["offer", 3])
        assertNull(table["quoteId", 4], "nothing was issued for 4")
        assertEquals(mapOf("quoteId" to 3, "offer" to 3), table.counts())
    }

    @Test
    fun `a reply without the tag keeps a null, and a duplicate does not overwrite the first`() {
        val table = StampMatcher.CaptureTable(listOf("quoteId"), size = 4)
        val m = matcher(captures = listOf("quoteId" to 117), table = table)

        m.issued("ORD-1", 1)
        m.onStamp(send(laneA, "ORD-1", at = 1_000))
        m.onStamp(quote("ORD-1", at = 1_500, quoteId = null))
        m.issued("ORD-2", 2)
        m.onStamp(send(laneA, "ORD-2", at = 2_000))
        m.onStamp(quote("ORD-2", at = 2_500, quoteId = "QID-first"))
        m.onStamp(quote("ORD-2", at = 2_900, quoteId = "QID-second"))

        assertNull(table["quoteId", 1], "the venue answered without the tag")
        assertEquals("QID-first", table["quoteId", 2], "the match keeps the value, the duplicate does not")
        assertEquals(1L, m.snapshot().duplicates)
        assertEquals(mapOf("quoteId" to 1), m.finish().captured)
    }

    /** The evidence file's shape: one line per index that carries anything, in index order. */
    @Test
    fun `the table's rows are the indices that carry a value, in order`() {
        val table = StampMatcher.CaptureTable(listOf("quoteId", "offer"), size = 6)
        table.put(0, 3, "QID-3")
        table.put(1, 3, "1.09010")
        table.put(0, 1, "QID-1")

        assertEquals(
            listOf(1 to listOf("quoteId" to "QID-1"), 3 to listOf("quoteId" to "QID-3", "offer" to "1.09010")),
            table.rows(),
        )
    }
}
