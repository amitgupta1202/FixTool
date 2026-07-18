package com.knapsack.fixtool.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ViewModelScenarioHost` reads a session's message log without hopping to the Swing EDT.
 *
 * That is only legal because [FixMessageSession.messages] is a StateFlow holding an immutable list,
 * not Compose state. The scenario runner polls it roughly every 100ms, so a 30-second `expect` step
 * used to drag ~300 full O(N) `filterIsInstance` copies onto the EDT and freeze the UI for the
 * length of the run.
 *
 * These tests pin the two properties that make the EDT hop unnecessary.
 */
class SessionMessageReadOffEdtTest {
    private fun quickfixMessage(type: String): Message =
        Message().apply { header.setString(MsgType.FIELD, type) }

    private fun fixMessage(raw: String): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw,
            messageType = "D",
            quickfixMessage = quickfixMessage("D"),
        )

    /**
     * The load-bearing one: a read must complete while the EDT is busy. Under the old
     * `invokeAndWait` this call could not return until the EDT drained.
     */
    @Test
    fun `message log is readable while the EDT is blocked`() =
        runBlocking {
            val session = FixMessageSession(title = "T", bufferSize = 64)
            repeat(10) { session.addMessage(fixMessage("msg-$it")) }
            delay(POLL_SETTLE_MS)

            val edtOccupied = CountDownLatch(1)
            val releaseEdt = CountDownLatch(1)

            // Occupy the EDT for the duration of the read.
            SwingUtilities.invokeLater {
                edtOccupied.countDown()
                // Outlasts READ_WATCHDOG_SECONDS deliberately: if the EDT freed itself on the same
                // deadline the assertion uses, a blocked read would unblock just in time and the
                // test would pass against the bug it exists to catch.
                releaseEdt.await(EDT_HOLD_SECONDS, TimeUnit.SECONDS)
            }
            assertTrue(edtOccupied.await(EDT_HOLD_SECONDS, TimeUnit.SECONDS), "EDT never picked up the task")

            val result = AtomicReference<List<FixMessage>>()
            val failure = AtomicReference<Throwable?>()
            val readDone = CountDownLatch(1)

            Thread {
                try {
                    result.set(session.messages.value.filterIsInstance<FixMessage>())
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    readDone.countDown()
                }
            }.start()

            val completed = readDone.await(READ_WATCHDOG_SECONDS, TimeUnit.SECONDS)
            releaseEdt.countDown() // always free the EDT, pass or fail

            assertTrue(completed, "reading the message log blocked on the busy EDT")
            assertNull(failure.get(), "reading off the EDT threw: ${failure.get()}")
            assertEquals(10, result.get().size, "off-EDT read returned the wrong messages")
        }

    /** Reads stay internally consistent while the ingestion loop keeps mutating the log. */
    @Test
    fun `concurrent reads during ingestion never observe a torn list`() =
        runBlocking {
            val session = FixMessageSession(title = "T", bufferSize = 128)
            val failure = AtomicReference<Throwable?>()
            val stop = CountDownLatch(1)

            val reader =
                Thread {
                    try {
                        while (stop.count > 0) {
                            val snapshot = session.messages.value
                            // Iterating a snapshot must never see a concurrent structural change.
                            val counted = snapshot.filterIsInstance<FixMessage>().size
                            assertTrue(counted <= snapshot.size)
                        }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
            reader.start()

            repeat(300) { session.addMessage(fixMessage("msg-$it")) }
            delay(POLL_SETTLE_MS * 3)
            stop.countDown()
            reader.join(READ_WATCHDOG_SECONDS * 1000)

            assertNull(failure.get(), "a concurrent read failed: ${failure.get()}")
        }

    private companion object {
        const val POLL_SETTLE_MS = 300L

        /** How long a read is allowed to take before we call it blocked. */
        const val READ_WATCHDOG_SECONDS = 3L

        /** How long the EDT stays occupied — must comfortably outlast the read watchdog. */
        const val EDT_HOLD_SECONDS = 30L
    }
}
