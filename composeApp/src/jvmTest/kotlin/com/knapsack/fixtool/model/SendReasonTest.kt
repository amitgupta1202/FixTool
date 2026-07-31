package com.knapsack.fixtool.model

import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What a reply says about itself**, and the handoff that gets it there.
 *
 * The claim under test is not that the sentence reads well — it is that the sentence is a *record*.
 * Every value in it was taken at the moment of the decision, so the line is still true after the book
 * has moved on, which is the one thing a re-derived reason cannot promise. See decision 6a.
 */
class SendReasonTest {
    private val at = LocalDateTime.of(2026, 7, 31, 9, 14, 22, 418_000_000)

    private fun rule(constraint: OrderConstraint?, reading: BookReading?, index: Int = 2) =
        SendReason(
            source = SendReason.Source.RULE,
            at = at,
            ruleIndex = index,
            whenMsgType = "F",
            constraint = constraint,
            reading = reading,
        )

    // ------------------------------------------------------------------ the line

    @Test
    fun `a rule that asked the book says what the book said`() {
        val line = rule(OrderConstraint.UNKNOWN, BookReading.unknown("ORD-9")).line()

        assertEquals("sent by rule 3 — 35=F matched, and the book said ORD-9 was unknown at 09:14:22.418", line)
    }

    /**
     * A rule that did not ask still reports what the book held, because that is the state nobody can
     * recover later — but as trailing context rather than as "and the book said", which would dress up
     * a fact it did not use as a cause.
     */
    @Test
    fun `a rule that did not ask reports the book without claiming it decided anything`() {
        val line = rule(constraint = null, reading = BookReading("ORD-1", OrderState.WORKING)).line()

        assertEquals("sent by rule 3 — 35=F matched at 09:14:22.418, with ORD-1 working in the book", line)
    }

    @Test
    fun `a step of a sequence says which step it is, and a single reply does not`() {
        val sequence = rule(null, null).copy(step = 2, steps = 3)
        val single = rule(null, null).copy(step = 1, steps = 1)

        assertTrue(sequence.line().startsWith("sent by rule 3 (step 2 of 3) —"))
        assertTrue(single.line().startsWith("sent by rule 3 — "), "a one-message reply has no step worth naming")
    }

    @Test
    fun `a hand-sent reply names the shape and what it was answering`() {
        val reason =
            SendReason(
                source = SendReason.Source.HAND,
                at = at,
                shapeName = "Fill the remainder",
                reading = BookReading("ORD-5000", OrderState.WORKING, leavesQty = "2500"),
            )

        assertEquals(
            "sent by hand — \"Fill the remainder\" against ORD-5000, working, 2500 leaves at 09:14:22.418",
            reason.line(),
        )
    }

    @Test
    fun `a hand-sent reply for an order the book does not hold says so rather than saying nothing`() {
        val reason =
            SendReason(
                source = SendReason.Source.HAND,
                at = at,
                shapeName = "Reject the cancel",
                reading = BookReading.unknown("ORD-9"),
            )

        assertTrue(reason.line().contains("ORD-9, which the book did not hold"))
    }

    /**
     * The claim the whole design rests on: the reason is a value, so nothing that happens to the book
     * afterwards can change what it says. A reason that held a live reference would re-read here.
     */
    @Test
    fun `a recorded reason still names the state that produced it after the order has moved on`() {
        val recorded = rule(OrderConstraint.UNKNOWN, BookReading.unknown("ORD-9"))
        val line = recorded.line()

        // The order arrives a moment later and works its way to filled. The book is a different book;
        // the reason is the same sentence.
        BookReading("ORD-9", OrderState.DONE, leavesQty = "0")

        assertEquals(line, recorded.line())
        assertTrue(line.contains("was unknown"), "the state at 09:14:22 is what this reply was sent for")
    }

    // ------------------------------------------------------------------ the handoff

    @Test
    fun `the reason is taken once, so one reason explains one message`() {
        val reason = rule(null, null)

        PendingSendReason.during(reason) {
            assertEquals(reason, PendingSendReason.take())
            assertNull(PendingSendReason.take(), "a second message on the same send is not this one's")
        }
    }

    @Test
    fun `a reason never outlives its send, even when the send throws`() {
        val reason = rule(null, null)

        runCatching { PendingSendReason.during(reason) { error("the send failed") } }

        assertNull(
            PendingSendReason.take(),
            "a reason left behind would attach itself to whatever this thread sends next, which is the one way to be confidently wrong",
        )
    }

    /**
     * A venue with four clients sends on four threads. A field on the service would hand one client's
     * reason to another's reply, and both would look right.
     */
    @Test
    fun `two threads sending at once do not read each other's reasons`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val seen = java.util.concurrent.ConcurrentHashMap<String, Int?>()

        listOf("ALPHA" to 0, "BETA" to 7).forEach { (name, index) ->
            pool.submit {
                start.await()
                PendingSendReason.during(rule(null, null, index = index)) {
                    Thread.sleep(20)
                    seen[name] = PendingSendReason.take()?.ruleIndex
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(0, seen["ALPHA"])
        assertEquals(7, seen["BETA"])
    }
}
