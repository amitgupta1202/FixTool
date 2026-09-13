package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.SendReason
import org.junit.Test
import quickfix.Message
import quickfix.SessionID
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    true
                },
                isLoggedOn = { true },
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
                    true
                },
                isLoggedOn = { true },
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
                    true
                },
                isLoggedOn = { true },
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
        val dispatch = AcceptorDispatch(send = { message, _ -> sent.add(message.getString(11)) }, isLoggedOn = { true })
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
                isLoggedOn = { true },
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

    /**
     * A step owed to a counterparty that is not logged on when it falls due is **not built and not sent**.
     *
     * QuickFIX/J would have taken it anyway: `sendRaw` captures and persists before it checks, so the reply
     * appeared in the pane as sent and was counted, for a client that never received it.
     */
    @Test
    fun `a step for a counterparty that is not logged on is not built, not sent, and is reported`() {
        val built = ConcurrentLinkedQueue<String>()
        val sent = ConcurrentLinkedQueue<String>()
        val notDelivered = ConcurrentLinkedQueue<SessionID>()
        val both = CountDownLatch(2)
        val gone = sessionId("GONE")
        val here = sessionId("HERE")
        val dispatch =
            AcceptorDispatch(
                send = { message, _ -> sent.add(message.getString(11)) },
                isLoggedOn = { it == here },
                onSent = { both.countDown() },
                onNotDelivered = { session, _ ->
                    notDelivered.add(session)
                    both.countDown()
                },
            )
        dispatch.use {
            it.schedule(gone, delayMillis = 0) { message("owed").also { built.add("owed") } }
            it.schedule(here, delayMillis = 0) { message("kept").also { built.add("kept") } }
            assertTrue(both.await(5, TimeUnit.SECONDS), "one step should be sent and one reported")
        }

        assertEquals(listOf("kept"), built.toList(), "a step nobody can receive must not draw ids or read a book")
        assertEquals(listOf("kept"), sent.toList())
        assertEquals(listOf(gone), notDelivered.toList())
    }

    /** The session can leave between the check and the write; QuickFIX/J's false is a step that did not go. */
    @Test
    fun `a send the engine refuses is reported as not delivered, never as sent`() {
        val outcomes = ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(1)
        val dispatch =
            AcceptorDispatch(
                send = { _, _ -> false },
                isLoggedOn = { true },
                onSent = {
                    outcomes.add("sent")
                    done.countDown()
                },
                onNotDelivered = { _, _ ->
                    outcomes.add("not delivered")
                    done.countDown()
                },
            )
        dispatch.use {
            it.schedule(sessionId(), delayMillis = 0) { message("A") }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf("not delivered"), outcomes.toList())
    }

    // ------------------------------------------------------------------ timers

    @Test
    fun `a timer runs its action once, on the dispatch thread, after its delay`() {
        val ranOn = ConcurrentLinkedQueue<String>()
        val ran = CountDownLatch(1)
        AcceptorDispatch(send = { _, _ -> true }, isLoggedOn = { true }).use { dispatch ->
            dispatch.scheduleTimer("rfq-expiry:RFQ-1", 50) {
                ranOn.add(Thread.currentThread().name)
                ran.countDown()
            }
            assertEquals(1, dispatch.timerCount())
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the timer never ran")
            Thread.sleep(100)
            assertEquals(listOf("fixtool-acceptor-response"), ranOn.toList(), "once, and on the thread every reply leaves on")
            assertEquals(0, dispatch.timerCount())
        }
    }

    @Test
    fun `a cancelled timer never runs, and arming a key again replaces it`() {
        val ran = ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(1)
        AcceptorDispatch(send = { _, _ -> true }, isLoggedOn = { true }).use { dispatch ->
            dispatch.scheduleTimer("a", 200) { ran.add("a") }
            assertTrue(dispatch.cancelTimer("a"))
            assertFalse(dispatch.cancelTimer("a"), "nothing left to cancel")

            dispatch.scheduleTimer("b", 200) { ran.add("first b") }
            dispatch.scheduleTimer("b", 50) {
                ran.add("second b")
                done.countDown()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
        }
        assertEquals(listOf("second b"), ran.toList())
    }

    @Test
    fun `a timer that throws is reported and the thread goes on`() {
        val errors = ConcurrentLinkedQueue<String>()
        val after = CountDownLatch(1)
        AcceptorDispatch(send = { _, _ -> true }, isLoggedOn = { true }, onError = { message, _ -> errors.add(message) }).use { dispatch ->
            dispatch.scheduleTimer("boom", 0) { error("no RFQ") }
            dispatch.scheduleTimer("next", 50) { after.countDown() }
            assertTrue(after.await(5, TimeUnit.SECONDS), "the thread died with the first timer")
        }
        assertTrue(errors.single().contains("boom"), errors.toString())
    }

    /** A logout says what it dropped, so each step that was owed can be counted rather than forgotten. */
    @Test
    fun `cancelling a session returns the reason each dropped step carried`() {
        val dispatch = AcceptorDispatch(send = { _, _ -> true }, isLoggedOn = { true })
        dispatch.use {
            val session = sessionId()
            val first = SendReason(source = SendReason.Source.RULE, at = LocalDateTime.now(), ruleIndex = 4)
            val second = SendReason(source = SendReason.Source.RULE, at = LocalDateTime.now(), ruleIndex = 7)
            it.schedule(session, delayMillis = 5_000, reason = first) { message("A") }
            it.schedule(session, delayMillis = 5_000, reason = second) { message("B") }

            val dropped = it.cancelAll(session)

            assertEquals(listOf(4, 7), dropped.map { reason -> reason?.ruleIndex }.sortedBy { index -> index })
            assertTrue(it.cancelAll(session).isEmpty(), "a second cancel has nothing left to report")
        }
    }
}
