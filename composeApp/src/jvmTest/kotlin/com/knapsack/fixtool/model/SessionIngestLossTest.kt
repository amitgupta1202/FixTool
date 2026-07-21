package com.knapsack.fixtool.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A session that cannot keep up throws messages away, and now says how many.**
 *
 * The ingest path is a queue of `bufferSize * 2` drained a fixed batch at a time on a fixed period, so a
 * session has a hard ceiling on how fast it can absorb traffic. Past that, [FixMessageSession.addMessage]
 * discards the head of the queue to make room — the message was received, and then lost. It never reaches
 * the grid, a scenario, or a capture.
 *
 * That used to happen in complete silence, which made it the worst kind of defect: every symptom pointed
 * somewhere else. A step that timed out on a reply the venue provably sent looked like a venue bug, or a
 * wrong bind predicate, or a broken expectation — and an engineer could spend a long time on any of those
 * before suspecting that the tool had simply dropped the message on the floor.
 *
 * These pin the counter, not the ceiling: the rate is an implementation detail that should be free to
 * improve, but "we lost some and did not say" must never come back.
 */
class SessionIngestLossTest {
    private fun message(i: Int): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|35=W|262=REQ|270=$i|",
            messageType = "W",
            quickfixMessage = Message().apply { header.setString(MsgType.FIELD, "W") },
        )

    @Test
    fun `a burst past the ingest ceiling is counted, not silently dropped`() {
        // Queue capacity is bufferSize * 2 = 100. A tight loop outruns the 100ms drain, so the overflow is
        // guaranteed without depending on how fast this machine happens to be.
        val session = FixMessageSession(title = "burst", bufferSize = 50)
        repeat(5_000) { session.addMessage(message(it)) }

        val lost = session.discarded.value
        assertTrue(lost > 0, "5,000 messages into a 100-deep queue must have discarded some, and said so")
        // Everything that did not fit was counted: nothing vanishes unaccounted for.
        val kept =
            runBlocking {
                repeat(20) { delay(100) }
                session.messages.value.size
            }
        assertTrue(
            lost + kept <= 5_000,
            "the counter must not over-report: $lost discarded + $kept kept exceeds the 5,000 offered",
        )
    }

    /**
     * The ceiling was an accident of the data structure, not a policy.
     *
     * Eviction used to be `removeFirst()` on an ArrayList — O(n) per message — so the drain cost
     * O(batch × bufferSize), and the batch had to be capped to contain it. That cap was the ingest ceiling:
     * roughly a thousand messages a second, past which traffic that had already arrived was thrown away. A
     * deque evicts in O(1), so the batch can be the whole queue.
     *
     * This pins the outcome rather than the mechanism: a burst that fits in the queue must arrive intact, in
     * order, however fast it was offered.
     */
    @Test
    fun `a burst that fits the queue is ingested whole, in one cycle`() {
        val session = FixMessageSession(title = "fast", bufferSize = 5_000)
        val burst = 4_000
        repeat(burst) { session.addMessage(message(it)) }

        // One drain, not forty: under the old fixed batch this needed ~4 seconds of cycles to catch up, and
        // anything still queued when the queue filled was discarded outright.
        session.flushMessageQueue()
        assertEquals(0L, session.discarded.value, "the queue was deep enough; nothing should have been lost")
        assertEquals(burst, session.messages.value.size, "every offered message must be retained")
        val order =
            session.messages.value
                .filterIsInstance<FixMessage>()
                .map { it.rawMessage }
        assertEquals(message(0).rawMessage, order.first(), "and in arrival order")
        assertEquals(message(burst - 1).rawMessage, order.last())
    }

    /**
     * The window and the published snapshot are one thing seen two ways, so clearing has to empty both. It
     * would be easy to empty only the snapshot — and the next drain cycle, a tenth of a second later, would
     * republish every message the user had just cleared, from the window that still held them.
     */
    @Test
    fun `clearing empties the retained window, not just the published snapshot`() {
        val session = FixMessageSession(title = "clear", bufferSize = 100)
        repeat(10) { session.addMessage(message(it)) }
        session.flushMessageQueue()
        assertEquals(10, session.messages.value.size)

        session.clearMessages()
        assertTrue(session.messages.value.isEmpty(), "cleared")

        // One more message, one more drain: only the new one may come back.
        session.addMessage(message(99))
        session.flushMessageQueue()
        assertEquals(1, session.messages.value.size, "a clear must not be undone by the next drain cycle")
    }

    @Test
    fun `a session inside its ceiling reports no loss at all`() {
        val session = FixMessageSession(title = "calm", bufferSize = 1_000)
        repeat(50) { session.addMessage(message(it)) }
        assertEquals(0L, session.discarded.value, "nothing was lost, so the count must stay silent at zero")
    }

    /**
     * The ring buffer evicting old messages is the retention policy working as designed — it happens on
     * every busy session and means nothing. Only the ingest discard is a loss, and conflating the two would
     * make the counter cry wolf on every long-running session until nobody read it.
     */
    @Test
    fun `evicting old messages from a full display buffer is not a loss`() {
        val session = FixMessageSession(title = "rolling", bufferSize = 10)
        runBlocking {
            // Slow enough for the drain to keep up: the queue never overflows, but the 10-deep display
            // buffer rolls over many times.
            repeat(10) { round ->
                repeat(5) { session.addMessage(message(round * 5 + it)) }
                delay(120)
            }
        }
        assertEquals(
            0L,
            session.discarded.value,
            "rolling the display buffer is retention, not loss — only the ingest discard counts",
        )
    }
}
