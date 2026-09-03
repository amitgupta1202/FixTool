package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.WireDirection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What a socket stamp becomes**: a reply paired with the request that carried the same id, the gap
 * between their two stamps, and an entry the grid can find from the row that started it.
 *
 * These are the rules the tracker was shipped with and never had a test for — the only coverage was
 * the history benchmark, which drives `recordStamp` directly and so never exercised the wire parsing.
 */
class LatencyTrackingServiceTest {
    private val soh = Char(1)

    private fun order(id: String) = "8=FIX.4.4${soh}9=60${soh}35=D${soh}49=CLI${soh}56=VEN${soh}11=$id${soh}55=EUR/USD${soh}10=123$soh"

    private fun report(id: String) =
        "8=FIX.4.4${soh}9=60${soh}35=8${soh}49=VEN${soh}56=CLI${soh}37=OID-1${soh}17=E-1${soh}11=$id${soh}39=0${soh}10=123$soh"

    private fun logon(from: String) = "8=FIX.4.4${soh}9=60${soh}35=A${soh}49=$from${soh}98=0${soh}108=30${soh}10=123$soh"

    private fun heartbeat() = "8=FIX.4.4${soh}9=60${soh}35=0${soh}49=CLI${soh}56=VEN${soh}10=123$soh"

    @Test
    fun `a reply is paired with its request by ClOrdID, and the round trip is the gap between the stamps`() {
        val tracker = LatencyTrackingService()

        tracker.record(WireDirection.SEND, order("ORD-1"), 1_000_000)
        tracker.record(WireDirection.RECEIVE, report("ORD-1"), 1_000_450)
        tracker.flushStatistics()

        val pair = tracker.getRecentPairs().single()
        assertEquals(450, pair.roundTripMicros)
        assertEquals(
            CorrelationIdType.CL_ORD_ID,
            pair.sendTimestamp.correlationType,
            "ClOrdID is tried before OrderID and ExecID, which the report also carries",
        )
        assertEquals("ORD-1", pair.sendTimestamp.correlationId)
        assertEquals(
            1,
            tracker.statistics.value
                .getValue(CorrelationIdType.CL_ORD_ID)
                .sampleCount,
        )
        assertEquals(1, tracker.aggregateStatistics.value.sampleCount)
        assertEquals(0, tracker.getPendingSendCount())
    }

    @Test
    fun `the grid finds the round trip by the row it shows, which is the pipe form of the wire`() {
        val tracker = LatencyTrackingService()

        tracker.record(WireDirection.SEND, order("ORD-1"), 1_000_000)
        tracker.record(WireDirection.RECEIVE, report("ORD-1"), 1_000_450)

        assertEquals(450, tracker.getLatencyForMessage(order("ORD-1").replace(soh, '|')))
        assertNull(tracker.getLatencyForMessage(order("ORD-1")), "the SOH form is not a key the grid ever asks with")
    }

    @Test
    fun `logon out and logon back are the session's first round trip`() {
        val tracker = LatencyTrackingService()

        tracker.record(WireDirection.SEND, logon("CLI"), 5_000)
        tracker.record(WireDirection.RECEIVE, logon("VEN"), 7_500)
        tracker.flushStatistics()

        val pair = tracker.getPairsByType(CorrelationIdType.LOGON).single()
        assertEquals(2_500, pair.roundTripMicros)
        assertEquals(
            1,
            tracker.statistics.value
                .getValue(CorrelationIdType.LOGON)
                .sampleCount,
        )
    }

    @Test
    fun `a message with no correlation tag is neither a sample nor a pending send`() {
        val tracker = LatencyTrackingService()

        tracker.record(WireDirection.SEND, heartbeat(), 1_000)
        tracker.record(WireDirection.RECEIVE, heartbeat(), 2_000)
        tracker.flushStatistics()

        assertEquals(0, tracker.getPendingSendCount())
        assertEquals(0, tracker.aggregateStatistics.value.sampleCount)
    }

    @Test
    fun `a tag is only read at the start of a field - 211 is not 11`() {
        val tracker = LatencyTrackingService()
        val lookalike = "8=FIX.4.4${soh}9=60${soh}35=D${soh}211=NOT-AN-ID${soh}55=EUR/USD${soh}10=123$soh"

        tracker.record(WireDirection.SEND, lookalike, 1_000)

        assertEquals(0, tracker.getPendingSendCount())
    }

    @Test
    fun `only the configured tags pair`() {
        val tracker = LatencyTrackingService(correlationTags = listOf(131))

        tracker.record(WireDirection.SEND, order("ORD-1"), 1_000)
        tracker.record(WireDirection.RECEIVE, report("ORD-1"), 2_000)
        tracker.flushStatistics()

        assertEquals(0, tracker.aggregateStatistics.value.sampleCount, "ClOrdID is not in this venue's list")
        assertEquals(0, tracker.getPendingSendCount())
    }

    @Test
    fun `a reply with no request is dropped rather than paired with the next request`() {
        val tracker = LatencyTrackingService()

        tracker.record(WireDirection.RECEIVE, report("ORD-9"), 1_000)
        tracker.record(WireDirection.SEND, order("ORD-9"), 2_000)
        tracker.flushStatistics()

        assertEquals(0, tracker.aggregateStatistics.value.sampleCount)
        assertEquals(1, tracker.getPendingSendCount(), "the send waits for a reply that has not come")
    }

    @Test
    fun `a test request is paired with the heartbeat that echoes its id`() {
        val tracker = LatencyTrackingService()
        val ping = "8=FIX.4.4${soh}9=60${soh}35=1${soh}49=CLI${soh}56=VEN${soh}112=PING-1${soh}10=123$soh"
        val echo = "8=FIX.4.4${soh}9=60${soh}35=0${soh}49=VEN${soh}56=CLI${soh}112=PING-1${soh}10=123$soh"

        tracker.record(WireDirection.SEND, ping, 10_000)
        tracker.record(WireDirection.RECEIVE, echo, 10_320)
        tracker.flushStatistics()

        val pair = tracker.getPairsByType(CorrelationIdType.TEST_REQ_ID).single()
        assertEquals(320, pair.roundTripMicros)
        assertEquals("1", pair.sendTimestamp.messageType)
        assertEquals("0", pair.receiveTimestamp.messageType)
        assertEquals("TestReqID", pair.sendTimestamp.correlationType.displayName)
    }

    @Test
    fun `a tag outside the built-ins pairs when it is in the list, and takes its name from the dictionary`() {
        val tracker = LatencyTrackingService(correlationTags = listOf(11, 5001), nameOf = { tag -> if (tag == 5001) "VenueQuoteRef" else null })
        val request = "8=FIX.4.4${soh}9=60${soh}35=R${soh}49=CLI${soh}56=VEN${soh}5001=Q-7${soh}10=123$soh"
        val reply = "8=FIX.4.4${soh}9=60${soh}35=S${soh}49=VEN${soh}56=CLI${soh}5001=Q-7${soh}10=123$soh"

        tracker.record(WireDirection.SEND, request, 1_000)
        tracker.record(WireDirection.RECEIVE, reply, 1_900)
        tracker.flushStatistics()

        val pair = tracker.getRecentPairs().single()
        assertEquals(900, pair.roundTripMicros)
        assertEquals(5001, pair.sendTimestamp.correlationType.tag)
        assertEquals("VenueQuoteRef", pair.sendTimestamp.correlationType.displayName, "the panel shows the dictionary's name for it")
        val bucket =
            tracker.statistics.value.entries
                .single { it.key.tag == 5001 }
        assertEquals(1, bucket.value.sampleCount, "and it has its own statistics bucket")
        assertEquals("VenueQuoteRef", bucket.key.displayName)
    }

    @Test
    fun `a tag the dictionary does not know is still paired, and named by its number`() {
        val tracker = LatencyTrackingService(correlationTags = listOf(9001))
        val out = "8=FIX.4.4${soh}9=60${soh}35=D${soh}9001=X${soh}10=123$soh"
        val back = "8=FIX.4.4${soh}9=60${soh}35=8${soh}9001=X${soh}10=123$soh"

        tracker.record(WireDirection.SEND, out, 1_000)
        tracker.record(WireDirection.RECEIVE, back, 1_500)

        assertEquals(
            "Tag 9001",
            tracker
                .getRecentPairs()
                .single()
                .sendTimestamp.correlationType.displayName,
        )
    }

    @Test
    fun `two lookups of the same tag share one statistics bucket whatever the name resolved to`() {
        assertEquals(CorrelationIdType(11, "ClOrdID"), CorrelationIdType(11, "Tag 11"))
        assertEquals(CorrelationIdType.CL_ORD_ID.hashCode(), CorrelationIdType(11, "anything").hashCode())
    }

    @Test
    fun `an empty tag value does not count as an id`() {
        val tracker = LatencyTrackingService()
        val blank = "8=FIX.4.4${soh}9=60${soh}35=D${soh}11=${soh}55=EUR/USD${soh}10=123$soh"

        tracker.record(WireDirection.SEND, blank, 1_000)

        assertTrue(tracker.getPendingSendCount() == 0)
    }
}
