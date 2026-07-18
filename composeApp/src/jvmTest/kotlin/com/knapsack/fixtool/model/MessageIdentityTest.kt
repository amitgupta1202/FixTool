package com.knapsack.fixtool.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Row identity in the message grid must not depend on a message's position in the session list.
 *
 * The grid used to key rows — and, through the same string, `expandedMessages` and
 * `selectedMessageIds` — on `"$timestamp-$index"`. The session list is a ring buffer, so once it is
 * full every retained message's index shifts down by one on each new arrival. That silently
 * reattached expansion and selection to whichever row inherited the old index, and re-keyed the
 * whole LazyColumn on every poll tick.
 *
 * These tests pin the identity contract that replaced it.
 */
class MessageIdentityTest {
    private fun quickfixMessage(type: String): Message =
        Message().apply { header.setString(MsgType.FIELD, type) }

    private fun fixMessage(
        timestamp: LocalDateTime = LocalDateTime.now(),
        raw: String = "8=FIX.4.4|35=D|",
    ): FixMessage =
        FixMessage(
            timestamp = timestamp,
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            messageType = "D",
            quickfixMessage = quickfixMessage("D"),
        )

    /** The regression itself: eviction must not change a surviving message's identity. */
    @Test
    fun `uid survives ring buffer eviction while an index-derived key does not`() =
        runBlocking {
            val session = FixMessageSession(title = "T", bufferSize = 4)
            repeat(4) { session.addMessage(fixMessage(raw = "msg-$it")) }
            delay(POLL_SETTLE_MS)

            val beforeEviction = session.messages.value
            assertEquals(4, beforeEviction.size, "buffer should be full")

            // The message sitting at index 2 — track it across the eviction by instance.
            val tracked = beforeEviction[2]
            val uidBefore = tracked.uid
            val indexKeyBefore = "${tracked.timestamp}-2"

            // One more arrival evicts the head and shifts everything down.
            session.addMessage(fixMessage(raw = "overflow"))
            delay(POLL_SETTLE_MS)

            val afterEviction = session.messages.value
            assertEquals(4, afterEviction.size, "buffer stays capped")

            val newIndex = afterEviction.indexOfFirst { it === tracked }
            assertEquals(1, newIndex, "the tracked message should have shifted from index 2 to 1")

            // Identity is unchanged...
            assertEquals(uidBefore, tracked.uid, "uid must not change when the list shifts")
            assertEquals(
                uidBefore,
                afterEviction[newIndex].uid,
                "the message found at its new index must carry the same uid",
            )

            // ...whereas the old index-derived key would have. This is the bug, stated directly:
            // the key the grid used to compute now names a *different* row.
            val indexKeyAfter = "${tracked.timestamp}-$newIndex"
            assertNotEquals(
                indexKeyBefore,
                indexKeyAfter,
                "the old index-derived key changes on eviction — that was the defect",
            )
        }

    /**
     * Selection and expansion state is keyed by uid, so messages that are equal *by value* must
     * still be addressable separately. Duplicate sends are routine in a FIX test tool.
     */
    @Test
    fun `messages equal by value still have distinct uids`() {
        val sharedTimestamp = LocalDateTime.now()
        val a = fixMessage(timestamp = sharedTimestamp, raw = "identical")
        val b = fixMessage(timestamp = sharedTimestamp, raw = "identical")

        assertNotEquals(a.uid, b.uid, "two distinct instances must never share a uid")
    }

    /**
     * uid is declared in the class body precisely so it stays out of the generated `equals`.
     * The grid compares `message == selectedMessage` for its selected-row highlight; if uid
     * leaked into equality, that comparison would stop matching.
     */
    @Test
    fun `uid does not participate in data class equality`() {
        val sharedTimestamp = LocalDateTime.now()
        val quickfix = quickfixMessage("D")
        val a =
            FixMessage(
                timestamp = sharedTimestamp,
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "same",
                messageType = "D",
                quickfixMessage = quickfix,
            )
        val b =
            FixMessage(
                timestamp = sharedTimestamp,
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "same",
                messageType = "D",
                quickfixMessage = quickfix,
            )

        assertNotEquals(a.uid, b.uid, "precondition: the uids differ")
        assertEquals(a, b, "value-equal messages must still compare equal")
        assertEquals(a.hashCode(), b.hashCode(), "and must still hash alike")
    }

    /** Separators share the identity scheme and must not collide with each other. */
    @Test
    fun `separators at the same timestamp have distinct uids`() {
        val sharedTimestamp = LocalDateTime.now()
        val first = Separator(timestamp = sharedTimestamp)
        val second = Separator(timestamp = sharedTimestamp)

        assertNotEquals(first.uid, second.uid)
    }

    /** Across a full buffer's worth of churn, every retained row still has a unique key. */
    @Test
    fun `uids stay unique across sustained eviction`() =
        runBlocking {
            val session = FixMessageSession(title = "T", bufferSize = 8)
            repeat(40) { session.addMessage(fixMessage(raw = "msg-$it")) }
            delay(POLL_SETTLE_MS * 3)

            val retained = session.messages.value
            assertTrue(retained.isNotEmpty(), "expected surviving messages")

            val uids = retained.map { it.uid }
            assertEquals(uids.size, uids.toSet().size, "every retained row must have a distinct uid")
        }

    private companion object {
        // The session drains its queue on a 100ms poll; give it room to settle.
        const val POLL_SETTLE_MS = 300L
    }
}
