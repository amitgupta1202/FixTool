package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.TAG_CL_ORD_ID
import com.knapsack.fixtool.model.TAG_CUM_QTY
import com.knapsack.fixtool.model.TAG_EXEC_TYPE
import com.knapsack.fixtool.model.TAG_LEAVES_QTY
import com.knapsack.fixtool.model.TAG_MSG_TYPE
import com.knapsack.fixtool.model.TAG_ORDER_ID
import com.knapsack.fixtool.model.TAG_ORDER_QTY
import com.knapsack.fixtool.model.TAG_ORD_STATUS
import com.knapsack.fixtool.model.TAG_ORIG_CL_ORD_ID
import com.knapsack.fixtool.model.TAG_SYMBOL
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The book as a *thing that holds state*: whose order is whose, what it does when it is full, and
 * what it says about its own gaps. What a fill does to a quantity is `OrderBookTest`'s question.
 */
class OrderBookServiceTest {
    private var clock = LocalDateTime.of(2026, 7, 31, 9, 14, 22)

    private fun tick(): LocalDateTime {
        clock = clock.plusNanos(1_000_000)
        return clock
    }

    private fun OrderBookService.receive(session: String, vararg fields: Pair<Int, String>) =
        record(session, tick(), sent = false, fields = fields.toMap(), raw = fields.render())

    private fun OrderBookService.send(session: String, vararg fields: Pair<Int, String>) =
        record(session, tick(), sent = true, fields = fields.toMap(), raw = fields.render())

    private fun Array<out Pair<Int, String>>.render() = joinToString("|") { "${it.first}=${it.second}" }

    private fun order(clOrdId: String, qty: String = "1000") =
        arrayOf(TAG_MSG_TYPE to "D", TAG_CL_ORD_ID to clOrdId, TAG_SYMBOL to "VOD.L", TAG_ORDER_QTY to qty)

    private fun ack(clOrdId: String, orderId: String = "EX-1", qty: String = "1000") =
        arrayOf(
            TAG_MSG_TYPE to "8",
            TAG_CL_ORD_ID to clOrdId,
            TAG_ORDER_ID to orderId,
            TAG_EXEC_TYPE to "0",
            TAG_ORD_STATUS to "0",
            TAG_CUM_QTY to "0",
            TAG_LEAVES_QTY to qty,
        )

    // ------------------------------------------------------------------ whose order is whose

    @Test
    fun `an order arrives pending and the venue's own reply moves it`() {
        val service = OrderBookService()

        service.receive("ALPHA", *order("ORD-1"))
        assertEquals(
            OrderState.PENDING,
            service
                .view("ALPHA")
                .orders
                .single()
                .state,
        )

        service.send("ALPHA", *ack("ORD-1"))
        val booked = service.view("ALPHA").orders.single()
        assertEquals(OrderState.WORKING, booked.state)
        assertEquals("EX-1", booked.current.orderId)
        assertEquals(2, booked.events.size, "the trail is both messages, or it cannot explain the row")
    }

    /**
     * ClOrdID is unique per client and not per venue, which is the whole reason a book belongs to a
     * session. Two clients sending `ORD-1` are two orders, and answering one with the other's state
     * is the failure this separation exists to prevent.
     */
    @Test
    fun `two counterparties sending the same ClOrdID are two orders`() {
        val service = OrderBookService()

        service.receive("ALPHA", *order("ORD-1", qty = "1000"))
        service.receive("BETA", *order("ORD-1", qty = "9999"))
        service.send("ALPHA", *ack("ORD-1"))

        assertEquals(OrderState.WORKING, service.order("ALPHA", "ORD-1")!!.state)
        assertEquals(OrderState.PENDING, service.order("BETA", "ORD-1")!!.state, "beta's order was never answered")
        assertEquals("9999", service.order("BETA", "ORD-1")!!.current.orderQty)
    }

    @Test
    fun `a replacement opens a chain and ends the order it supersedes`() {
        val service = OrderBookService()
        service.receive("ALPHA", *order("ORD-1"))
        service.send("ALPHA", *ack("ORD-1"))

        service.send(
            "ALPHA",
            TAG_MSG_TYPE to "8",
            TAG_CL_ORD_ID to "ORD-7",
            TAG_ORIG_CL_ORD_ID to "ORD-1",
            TAG_ORDER_ID to "EX-1",
            TAG_EXEC_TYPE to "5",
            TAG_ORD_STATUS to "0",
            TAG_ORDER_QTY to "1500",
            TAG_LEAVES_QTY to "1500",
        )

        val original = service.order("ALPHA", "ORD-1")!!
        val replacement = service.order("ALPHA", "ORD-7")!!
        assertEquals("ORD-7", original.supersededBy)
        assertEquals("ORD-1", replacement.supersedes)
        assertEquals(OrderState.WORKING, replacement.state)
        assertEquals("1500", replacement.current.orderQty)
        assertEquals("VOD.L", replacement.current.symbol, "the chain's symbol, which the report did not repeat")
    }

    // ------------------------------------------------------------------ what it says about its gaps

    @Test
    fun `a report for an order nobody sent is listed, with the reason, not dropped`() {
        val service = OrderBookService()
        service.receive("ALPHA", *order("ORD-1"))

        service.send("ALPHA", TAG_MSG_TYPE to "8", TAG_CL_ORD_ID to "GHOST-9", TAG_EXEC_TYPE to "F", TAG_CUM_QTY to "10")

        val view = service.view("ALPHA")
        assertEquals(1, view.unattributedCount)
        val entry = view.unattributed.single()
        assertTrue("GHOST-9" in entry.why, "the reason has to name what could not be attributed: ${entry.why}")
        assertEquals(1, view.orders.size, "and nothing was invented to hold it")
    }

    @Test
    fun `a report with no ClOrdID says that is what is wrong with it`() {
        val service = OrderBookService()

        service.send("ALPHA", TAG_MSG_TYPE to "8", TAG_ORDER_ID to "EX-9", TAG_EXEC_TYPE to "F")

        assertTrue(
            "carries no 11" in
                service
                    .view("ALPHA")
                    .unattributed
                    .single()
                    .why,
        )
    }

    @Test
    fun `a cleared book says it was cleared, because an empty one otherwise cannot`() {
        val service = OrderBookService()
        service.receive("ALPHA", *order("ORD-1"))

        service.clear("ALPHA", by = "manually", at = clock)

        val view = service.view("ALPHA")
        assertTrue(view.orders.isEmpty())
        assertEquals(clock, view.clearedAt)
        assertEquals("manually", view.clearedBy)
        assertNull(service.view("BETA").clearedAt, "a book nobody cleared has nothing to report")
    }

    // ------------------------------------------------------------------ the bound

    /**
     * The cap costs history, not behaviour. A working order is the one a cancel is most likely to
     * arrive for, and an evicted order answers that cancel "unknown" — a venue behaviour nobody
     * configured and nobody can see the cause of.
     */
    @Test
    fun `eviction takes finished orders before working ones`() {
        val service = OrderBookService(cap = 3)

        // Three that are done, then four that are still working.
        repeat(3) { i ->
            service.receive("ALPHA", *order("DONE-$i"))
            service.send(
                "ALPHA",
                TAG_MSG_TYPE to "8",
                TAG_CL_ORD_ID to "DONE-$i",
                TAG_EXEC_TYPE to "2",
                TAG_ORD_STATUS to "2",
                TAG_CUM_QTY to "1000",
                TAG_LEAVES_QTY to "0",
            )
        }
        repeat(4) { i ->
            service.receive("ALPHA", *order("LIVE-$i"))
            service.send("ALPHA", *ack("LIVE-$i"))
        }

        val view = service.view("ALPHA")
        assertEquals(3, view.orders.size)
        assertTrue(view.orders.all { it.state == OrderState.WORKING }, "a finished order was kept over a live one")
        assertEquals(4, view.evicted, "and the count says how much history that cost")
    }

    @Test
    fun `the eviction count is visible, so a book that dropped something never looks full and healthy`() {
        val service = OrderBookService(cap = 2)
        repeat(5) { i ->
            service.receive("ALPHA", *order("ORD-$i"))
            service.send(
                "ALPHA",
                TAG_MSG_TYPE to "8",
                TAG_CL_ORD_ID to "ORD-$i",
                TAG_EXEC_TYPE to "2",
                TAG_ORD_STATUS to "2",
                TAG_CUM_QTY to "1000",
                TAG_LEAVES_QTY to "0",
            )
        }

        val view = service.view("ALPHA")
        assertEquals(2, view.orders.size)
        assertEquals(3, view.evicted)
        assertEquals(2, view.cap)
    }

    // ------------------------------------------------------------------ watchability

    /**
     * **The book has to be watchable, or a panel drawn from it lies.**
     *
     * Found in live verification rather than here: the panel read the book with a plain call, so
     * nothing recomposed it and its numbers froze at whatever they were when it first drew — CumQty 0
     * on screen against a wire that had already traded 2500. A stale book is worse than no book,
     * because it is wrong with a straight face and the reader has no way to tell.
     */
    @Test
    fun `every change publishes, so a watcher sees the wire move`() {
        val service = OrderBookService()
        val watched = service.views("ALPHA")
        val before = watched.value

        service.receive("ALPHA", *order("ORD-1"))
        val afterOrder = watched.value
        service.send("ALPHA", *ack("ORD-1"))
        val afterAck = watched.value

        assertTrue(before.orders.isEmpty(), "a book nothing has happened on starts empty rather than absent")
        assertEquals(1, afterOrder.orders.size, "the order has to reach a watcher, not only the book")
        assertEquals(OrderState.PENDING, afterOrder.orders.single().state)
        assertEquals(OrderState.WORKING, afterAck.orders.single().state, "and so does the answer that moved it")
        assertTrue(afterOrder !== afterAck, "a watcher that is handed the same object cannot know anything changed")
    }

    @Test
    fun `clearing publishes too, or the panel keeps drawing a book that is gone`() {
        val service = OrderBookService()
        val watched = service.views("ALPHA")
        service.receive("ALPHA", *order("ORD-1"))

        service.clear("ALPHA", by = "the test", at = clock)

        assertTrue(watched.value.orders.isEmpty())
        assertEquals("the test", watched.value.clearedBy)
    }

    // ------------------------------------------------------------------ threads

    /**
     * `fromApp` is QuickFIX's read thread, `toApp` can be the dispatch thread or the UI's send path,
     * and a venue with four clients has four of each. This is not a race the tester would ever be
     * able to report usefully — it would read as a book that is occasionally missing an order.
     */
    @Test
    fun `orders arriving from several threads at once all land`() {
        val service = OrderBookService()
        val threads = 8
        val each = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(each) { i ->
                    service.receive("ALPHA", *order("ORD-$t-$i"))
                    service.send("ALPHA", *ack("ORD-$t-$i"))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the feed deadlocked")

        val view = service.view("ALPHA")
        assertEquals(threads * each, view.orders.size)
        assertTrue(view.orders.all { it.events.size == 2 }, "an order lost one of its two messages")
        assertNotNull(service.order("ALPHA", "ORD-7-49"))
    }

    // ------------------------------------------------------------------ what a trigger reads

    @Test
    fun `a reading is per counterparty, so one client's cancel never reads another's order`() {
        val service = OrderBookService()
        service.receive("ALPHA", *order("ORD-1"))
        service.send("ALPHA", *ack("ORD-1"))
        val cancel = mapOf(TAG_MSG_TYPE to "F", TAG_CL_ORD_ID to "CXL-1", TAG_ORIG_CL_ORD_ID to "ORD-1")

        assertEquals(OrderState.WORKING, service.reading("ALPHA", cancel).state)
        assertNull(
            service.reading("BETA", cancel).state,
            "BETA never sent ORD-1, and answering its cancel from ALPHA's book is the bug the per-session key exists to stop",
        )
    }

    @Test
    fun `a book nothing has happened on reads unknown rather than failing`() {
        val service = OrderBookService()

        val reading = service.reading("NOBODY", mapOf(TAG_MSG_TYPE to "F", TAG_ORIG_CL_ORD_ID to "ORD-1"))

        assertEquals("ORD-1", reading.key)
        assertNull(reading.state)
    }

    /**
     * The reduction is the one place a `quickfix.Message` meets the book. A second one that read a tag
     * differently would put two books in the same app, agreeing on everything until they didn't.
     */
    @Test
    fun `a message reduces to the tags the book reads and nothing else`() {
        val message =
            AcceptorResponder.buildMessage("35=F|11=CXL-1|41=ORD-1|55=VOD.L|54=1|60=20260731-09:14:22.000|9999=noise")

        val fields = OrderBookService.fieldsOf(message)

        assertEquals("F", fields[TAG_MSG_TYPE])
        assertEquals("ORD-1", fields[TAG_ORIG_CL_ORD_ID])
        assertEquals("CXL-1", fields[TAG_CL_ORD_ID])
        assertNull(fields[9999], "a tag no spec names has no business in the book")
    }
}
