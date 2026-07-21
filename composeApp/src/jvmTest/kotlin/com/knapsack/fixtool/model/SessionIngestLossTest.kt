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
