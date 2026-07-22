package com.knapsack.fixtool.service

import org.junit.Test
import quickfix.Message
import quickfix.SessionID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The acceptor's reply leaves on a thread of its own, at a time of its own.
 *
 * The behaviour under test is a *negative* one — the reply must NOT have been sent by the time the
 * inbound callback returns — which is why the caller's thread identity is asserted directly rather
 * than inferred from timing. A test that merely waits for the reply to arrive passes just as well
 * against the inline send this replaced.
 */
class AcceptorDispatchTest {
    private fun sessionId(target: String = "CLIENT") = SessionID("FIX.4.4", "ACCEPTOR", target)

    private fun message(clOrdId: String): Message =
        AcceptorResponder.buildMessage("35=8|150=0|39=0|11=$clOrdId|")

    @Test
    fun `the reply is not sent on the calling thread`() {
        val sentOn = ConcurrentLinkedQueue<String>()
        val arrived = CountDownLatch(1)
        val dispatch =
            AcceptorDispatch(
                send = { _, _ ->
                    sentOn.add(Thread.currentThread().name)
                    arrived.countDown()
                },
            )
        dispatch.use {
            it.schedule(sessionId(), delayMillis = 0) { message("A") }
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "the zero-delay reply never went out")
        }

        assertNotEquals(
            Thread.currentThread().name,
            sentOn.single(),
            "a zero delay must still mean 'as soon as the dispatch thread is free', never 'now, on the caller's'",
        )
    }

    @Test
    fun `nothing has gone out while the delay is still running`() {
        val sent = ConcurrentLinkedQueue<String>()
        val arrived = CountDownLatch(1)
        val dispatch =
            AcceptorDispatch(
                send = { message, _ ->
                    sent.add(message.getString(11))
                    arrived.countDown()
                },
            )
        dispatch.use {
            val session = sessionId()
            it.schedule(session, delayMillis = 400) { message("DELAYED") }
            assertTrue(sent.isEmpty(), "a delayed reply went out immediately")
            assertEquals(1, it.pendingCount(session), "the delayed reply should be queued")
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "the delayed reply never went out")
            assertEquals(listOf("DELAYED"), sent.toList())
        }
    }

    @Test
    fun `steps of one sequence keep the order they were scheduled in`() {
        val sent = ConcurrentLinkedQueue<String>()
        val allThree = CountDownLatch(3)
        val dispatch =
            AcceptorDispatch(
                send = { message, _ ->
                    sent.add(message.getString(11))
                    allThree.countDown()
                },
            )
        dispatch.use {
            val session = sessionId()
            it.schedule(session, delayMillis = 0) { message("ack") }
            it.schedule(session, delayMillis = 60) { message("partial") }
            it.schedule(session, delayMillis = 120) { message("fill") }
            assertTrue(allThree.await(5, TimeUnit.SECONDS), "not every step went out")
        }
        assertEquals(listOf("ack", "partial", "fill"), sent.toList())
    }

    @Test
    fun `a session that logs out is not replied to`() {
        val sent = ConcurrentLinkedQueue<String>()
        val dispatch = AcceptorDispatch(send = { message, _ -> sent.add(message.getString(11)) })
        dispatch.use {
            val goneAway = sessionId("GONE")
            val stillHere = sessionId("HERE")
            it.schedule(goneAway, delayMillis = 300) { message("dropped") }
            it.schedule(stillHere, delayMillis = 300) { message("kept") }

            it.cancelAll(goneAway)
            assertEquals(0, it.pendingCount(goneAway), "logout should drop the queued reply, not merely ignore it")

            Thread.sleep(700)
            assertEquals(
                listOf("kept"),
                sent.toList(),
                "cancelling one session's pending replies must not touch another's",
            )
        }
    }

    @Test
    fun `a send that throws does not take the dispatch thread down with it`() {
        val sent = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val both = CountDownLatch(2)
        val dispatch =
            AcceptorDispatch(
                send = { message, _ ->
                    val clOrdId = message.getString(11)
                    both.countDown()
                    if (clOrdId == "boom") throw IllegalStateException("session is gone")
                    sent.add(clOrdId)
                },
                onError = { text, _ -> errors.add(text) },
            )
        dispatch.use {
            val session = sessionId()
            it.schedule(session, delayMillis = 0) { message("boom") }
            it.schedule(session, delayMillis = 60) { message("after") }
            assertTrue(both.await(5, TimeUnit.SECONDS), "the reply after the failing one never ran")
        }

        assertEquals(listOf("after"), sent.toList(), "one failed send must not cancel the rest of the sequence")
        assertEquals(1, errors.size, "the failure should be reported, not swallowed")
        assertTrue(errors.single().contains("session is gone"), "the report should quote the cause: ${errors.single()}")
    }
}
