package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.PacketDirection
import com.knapsack.fixtool.model.PacketTimestamp
import com.knapsack.fixtool.service.LatencyTrackingService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What one correlated message costs the latency tracker, and what it leaves behind.**
 *
 * `addCorrelatedPair` runs once per round trip on a session with latency tracking on, and did three
 * things whose cost was the size of the whole history rather than of the one pair being added:
 *
 * ```
 * correlatedPairs.add(0, pair)                    // O(n) shift on an ArrayList
 * while (size > historySize) removeLast()
 * _recentPairs.value = correlatedPairs.toList()   // O(n) copy, per pair
 * ```
 *
 * `historySize` defaults to **10,000**, so every correlated message shifted ten thousand elements and
 * then copied ten thousand more — to publish a list the panel renders `.take(50)` of. It is the same
 * shape as the ArrayList eviction that used to cap session ingest, fixed there by moving to a deque
 * and not yet here.
 *
 * And it leaked: `latencyByMessage` is keyed by the **full raw FIX message** and was only ever cleared
 * by an explicit statistics reset, while `correlatedPairs` beside it was properly capped. A long
 * latency-tracked soak run accumulated one whole message string per round trip, indefinitely.
 */
class LatencyHistoryBenchmarkTest {
    private fun send(id: Int): PacketTimestamp =
        PacketTimestamp(
            timestampMicros = id * 1_000L,
            direction = PacketDirection.SEND,
            correlationType = CorrelationIdType.CL_ORD_ID,
            correlationId = "ORD-$id",
            messageType = "D",
            rawFixMessage = "8=FIX.4.4|35=D|11=ORD-$id|55=EUR/USD|38=1000000|",
        )

    private fun receive(id: Int): PacketTimestamp =
        PacketTimestamp(
            timestampMicros = id * 1_000L + 250,
            direction = PacketDirection.RECEIVE,
            correlationType = CorrelationIdType.CL_ORD_ID,
            correlationId = "ORD-$id",
            messageType = "8",
            rawFixMessage = "8=FIX.4.4|35=8|11=ORD-$id|39=0|14=0|",
        )

    private fun round(service: LatencyTrackingService, id: Int) {
        service.recordPacket(send(id))
        service.recordPacket(receive(id))
    }

    /**
     * **The headline: one round trip, against a history that is already full.**
     *
     * The service is filled to its 10,000-pair ceiling first, because that is the steady state of any
     * session left running — and it is exactly the state in which the old shift-and-copy was at its
     * most expensive.
     */
    @Test
    fun `recording one correlated pair into a full history`() {
        val service = LatencyTrackingService(historySize = 10_000)
        repeat(10_000) { round(service, it) }

        var next = 100_000
        val result =
            Bench.measure("one round trip into a 10,000-pair history", ops = 2_000) {
                round(service, next++)
            }

        println("\n┌─ One correlated round trip, history already at its 10,000 ceiling")
        println("│  " + result.render())
        println("└─\n")

        assertTrue(
            result.bytesPerOp < 20_000,
            "recording one pair must not copy the whole history; got ${result.bytesPerOp} B/op",
        )
    }

    /**
     * The same shape as the order book's: if per-message cost tracks the history size, the numbers climb
     * across the row and the tracker gets slower the longer you leave it on. Flatness is the property.
     */
    @Test
    fun `per-pair cost does not track how much history is kept`() {
        println("\n┌─ Cost of one round trip, by history ceiling")
        val measured =
            listOf(100, 1_000, 10_000).map { size ->
                val service = LatencyTrackingService(historySize = size)
                repeat(size) { round(service, it) }
                var next = 500_000
                val r =
                    Bench.measure("history of %,d pairs".format(size), ops = 1_000) {
                        round(service, next++)
                    }
                println("│  " + r.render())
                size to r
            }
        println("└─\n")

        val smallest = measured.first().second.bytesPerOp
        val largest = measured.last().second.bytesPerOp
        assertTrue(
            largest < smallest * 4 + 4_000,
            "a 100x deeper history must not mean a proportionally dearer round trip: " +
                "$smallest B/op at 100 against $largest B/op at 10,000",
        )
    }

    /**
     * **The leak, stated as a count.**
     *
     * `latencyByMessage` exists so the grid can show a latency beside a row. A message that has rolled
     * out of the history has also rolled out of the grid, so its entry can never be read again — and
     * every one of those entries holds a full raw FIX message as its key.
     */
    @Test
    fun `the by-message index does not outgrow the history it serves`() {
        val service = LatencyTrackingService(historySize = 100)
        repeat(5_000) { round(service, it) }

        assertEquals(
            100,
            service.latencyIndexSize(),
            "the lookup index must be bounded by the history, not by everything that ever happened",
        )
        // And it must still answer for what IS in the history: the newest pair.
        assertTrue(
            service.getLatencyForMessage(send(4_999).rawFixMessage) != null,
            "the most recent message must still resolve to its latency",
        )
        assertTrue(
            service.getLatencyForMessage(send(0).rawFixMessage) == null,
            "and one that rolled out long ago must not still be held",
        )
    }

    /**
     * The published list is what a panel collects, and the panel draws `.take(50)` of it. Publishing all
     * 10,000 per pair was ten thousand references copied to render fifty — and it is the copy, not the
     * list, that made it expensive.
     */
    @Test
    fun `the published window is bounded, and holds the newest pairs first`() {
        val service = LatencyTrackingService(historySize = 10_000)
        repeat(3_000) { round(service, it) }

        val published = service.recentPairs.value
        assertTrue(
            published.size <= 256,
            "the published window must be bounded; got ${published.size}",
        )
        assertTrue(published.isNotEmpty(), "and must not be empty while there is history")
        assertEquals(
            "ORD-2999",
            published.first().sendTimestamp.correlationId,
            "newest first — the panel shows the most recent round trips",
        )
        // The full history stays available to anything that wants it.
        assertEquals(3_000, service.getRecentPairs(limit = 100_000).size, "the history itself is untouched by the publish window")
    }
}
