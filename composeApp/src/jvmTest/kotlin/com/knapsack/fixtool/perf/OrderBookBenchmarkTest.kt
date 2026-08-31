package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.BookSpec
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.TAG_MSG_TYPE
import com.knapsack.fixtool.service.OrderBookService
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What booking one message costs, and why it depended on how much the book already held.**
 *
 * `OrderBookService.record` runs on the QuickFIX/J callback thread for every message on a session that
 * has a book open, under the book's own lock — so anything it does per message is paid before the
 * socket is read again, and paid by every other session sharing that book.
 *
 * It did three things whose cost was proportional to the *whole book* rather than to the one message
 * being recorded:
 *
 * - `OrderBook.route(event, book.orders.keys.toSet(), spec)` built a fresh `HashSet` of every key in
 *   the book, so `route` could ask `key in known` twice.
 * - `publish(book)` copied `orders.values` and `unattributed` into new lists to construct a `BookView`,
 *   on every message, for a panel that redraws at frame rate.
 * - an order's event trail was rebuilt with `existing.events + event`, which copies every prior event.
 *   That last one is quadratic in the trail length, which on a working order is the number of fills.
 *
 * With the shipped `DEFAULT_CAP` of 5,000 orders, the first two were 5,000-element operations per
 * message. The third is the one that gets worse the longer a soak run goes.
 */
class OrderBookBenchmarkTest {
    private val spec = BookSpec.ORDERS
    private val at = LocalDateTime.of(2026, 8, 30, 9, 30)

    private fun newOrder(id: String): Map<Int, String> =
        mapOf(TAG_MSG_TYPE to "D", spec.keyTag to id, 55 to "EUR/USD", 38 to "1000000", 54 to "1")

    /**
     * A fill is the venue's own outgoing ExecutionReport, so it is recorded with `sent = true`.
     *
     * `BookSpec.ORDERS` reads `bornBy` as RECEIVED types and `movedBy` as SENT ones — the book is kept
     * from the acceptor's side, where orders arrive and reports leave. Recording a fill as received
     * makes `route` return `Ignored`, which is silent and looks exactly like a working benchmark whose
     * numbers happen to be flat.
     */
    private fun fill(id: String, n: Int): Map<Int, String> =
        mapOf(
            TAG_MSG_TYPE to "8",
            spec.keyTag to id,
            37 to "EX-$id",
            17 to "F-$n",
            150 to "1",
            39 to "1",
            32 to "100",
            31 to "1.0850",
            14 to (n * 100).toString(),
        )

    private fun bookWith(orders: Int, cap: Int = 5_000): OrderBookService {
        val service = OrderBookService(spec, initialCap = cap)
        repeat(orders) { service.record("s", at, sent = false, fields = newOrder("ORD-$it"), raw = "") }
        return service
    }

    /**
     * **The headline: one message into a book that already holds 5,000 orders.**
     *
     * 5,000 is `OrderBookService.DEFAULT_CAP` — the shipped ceiling, so this is the steady state of any
     * session that has been running a while, not a stress case. Every message recorded into a full book
     * used to allocate two lists the size of the book before doing any of the work the message asked for.
     */
    @Test
    fun `recording one message into a full book`() {
        val service = bookWith(orders = 5_000)
        var n = 0

        val result =
            Bench.measure("record() one fill into a 5,000-order book", ops = 2_000) {
                service.record("s", at, sent = true, fields = fill("ORD-${n++ % 5_000}", n), raw = "")
            }

        println("\n┌─ One message booked, against a full book")
        println("│  " + result.render())
        println("└─\n")

        // The pin: per-message cost must not scale with the book. A book-sized copy at cap 5,000 showed
        // up here as hundreds of kilobytes per message; the work one message actually needs is a lookup
        // and an append.
        assertTrue(
            result.bytesPerOp < 20_000,
            "booking one message must not allocate a copy of the book; got ${result.bytesPerOp} B/op",
        )
    }

    /**
     * **The same message, into books of four different sizes.**
     *
     * This is the shape of the defect stated directly: if per-message cost tracks book size, the numbers
     * climb across the row, and the tool gets slower the longer you leave it running. If it does not,
     * they are flat. Flatness is the property, not any particular figure.
     */
    @Test
    fun `per-message cost does not track how much the book already holds`() {
        println("\n┌─ Cost of booking one fill, by how many orders the book holds")
        val measured =
            listOf(10, 100, 1_000, 5_000).map { size ->
                val service = bookWith(orders = size)
                var n = 0
                val r =
                    Bench.measure("book of %,d orders".format(size), ops = 1_000) {
                        service.record("s", at, sent = true, fields = fill("ORD-${n++ % size}", n), raw = "")
                    }
                println("│  " + r.render())
                size to r
            }
        println("└─\n")

        val smallest = measured.first().second.bytesPerOp
        val largest = measured.last().second.bytesPerOp
        assertTrue(
            largest < smallest * 4 + 4_000,
            "a 500x bigger book must not mean a proportionally bigger per-message cost: " +
                "$smallest B/op at 10 orders against $largest B/op at 5,000",
        )
    }

    /**
     * **The quadratic**: one working order taking many fills.
     *
     * `existing.copy(events = existing.events + event)` allocates a new list and copies every prior
     * event, so building a trail of n events costs 1+2+...+n. An algo order or a market-making session
     * puts thousands of reports on a single order, and this is the cost of assembling that trail —
     * paid under the book's lock, so every other session on the book waits for it.
     *
     * The measurement is the total cost of n appends, at two values of n an order of magnitude apart. A
     * linear append doubles the work for ten times the events... no: it multiplies by ten. A quadratic
     * one multiplies by a hundred. That ratio is the assertion, because it is a property of the data
     * structure rather than of the machine.
     */
    @Test
    fun `appending to one order's trail is linear in the trail, not quadratic`() {
        fun costOf(fills: Int): Long {
            val service = OrderBookService(spec, initialCap = 5_000)
            service.record("s", at, sent = false, fields = newOrder("ORD-1"), raw = "")
            val r =
                Bench.measure("%,d fills on one order".format(fills), ops = 1, warmupRounds = 1, rounds = 3) {
                    repeat(fills) { i -> service.record("s", at, sent = true, fields = fill("ORD-1", i), raw = "") }
                }
            println("│  " + r.render())
            return r.nanosPerOp
        }

        println("\n┌─ Building one order's event trail")
        val small = costOf(500)
        val large = costOf(5_000)
        println("└─ 10x the fills cost %.1fx the time\n".format(large.toDouble() / small))

        // Ten times the events must cost roughly ten times the work, not a hundred. The bound is loose
        // because a laptop is noisy and because the events themselves are real work — but a genuine
        // quadratic lands around 100x and cannot hide under 30x.
        assertTrue(
            large < small * 30,
            "trail append looks quadratic: 500 fills took ${small}ns, 5,000 took ${large}ns " +
                "(${large.toDouble() / small}x for 10x the events)",
        )
    }

    /**
     * The correctness half, and the one that matters most: the book must say exactly the same things
     * after the change. An order's trail, its state, and what the view reports are the whole product
     * here — a faster book that lost an event would be strictly worse than a slow one.
     */
    @Test
    fun `the book still records every event, in order, with the same outcome`() {
        val service = OrderBookService(spec, initialCap = 5_000)
        assertEquals(
            OrderBook.Outcome.Born("ORD-1"),
            service.record("s", at, sent = false, fields = newOrder("ORD-1"), raw = ""),
        )
        repeat(50) { i ->
            val outcome = service.record("s", at, sent = true, fields = fill("ORD-1", i), raw = "")
            assertEquals(OrderBook.Outcome.Moved("ORD-1"), outcome, "fill $i must move the order it names")
        }

        val order = service.order("s", "ORD-1")!!
        assertEquals(51, order.events.size, "the opening order plus 50 fills, none lost")
        assertEquals("D", order.events.first().msgType, "in order: the order that opened the trail is first")
        // CumQty, not ExecID: `record` keeps only the tags BookSpec.readTags names, and ExecID is not
        // one of them. Asserting on a dropped tag would compare null to null and prove nothing.
        order.events.drop(1).forEachIndexed { i, event ->
            assertEquals("${i * 100}", event.field(14), "event $i must be the fill recorded at that position")
        }

        val view = service.view("s")
        assertEquals(1, view.orders.size, "one order in the book")
        assertEquals(
            order.events.size,
            view.orders
                .single()
                .events.size,
            "and the view must see its whole trail",
        )
    }

    /**
     * Publishing is what makes the panel able to redraw, so it has to still happen — a fix that made
     * booking cheap by never telling anyone would freeze the panel at whatever it first drew, which is
     * the exact defect the flow was introduced to solve.
     *
     * [OrderBookService.view] is the synchronous read: it flushes anything outstanding before answering,
     * so a caller asking directly never observes the pacing window at all.
     */
    @Test
    fun `the view reflects every recorded message the moment it is asked`() {
        val service = OrderBookService(spec, initialCap = 5_000)

        service.record("s", at, sent = false, fields = newOrder("ORD-1"), raw = "")
        assertEquals(1, service.view("s").orders.size, "a new order must be visible immediately")

        repeat(10) { i -> service.record("s", at, sent = true, fields = fill("ORD-1", i), raw = "") }
        assertEquals(
            11,
            service
                .view("s")
                .orders
                .single()
                .events.size,
            "and so must every fill on it",
        )

        service.record("s", at, sent = false, fields = newOrder("ORD-2"), raw = "")
        assertEquals(2, service.view("s").orders.size, "and a second order")
    }

    /**
     * **The trailing flush, which is the whole reason pacing is safe.**
     *
     * A change arriving inside the 100ms window does not publish immediately — it books a flush. If that
     * flush were ever dropped, the panel would sit on stale numbers for as long as the traffic stayed
     * stopped, which is precisely the "book that lies with a straight face" the flow was added to
     * prevent. So: write once, stop, and wait for the flow to catch up on its own.
     *
     * Waits far longer than the interval and returns as soon as it converges, so it is slow only when
     * it is about to fail.
     */
    @Test
    fun `a change inside the pacing window still reaches the flow on its own`() {
        val service = OrderBookService(spec, initialCap = 5_000)
        val flow = service.views("s")

        // First write publishes immediately (nothing has been published yet, so the window has passed).
        service.record("s", at, sent = false, fields = newOrder("ORD-1"), raw = "")
        // These land inside the window and must arrive via the trailing flush, with nothing to push them.
        repeat(5) { i -> service.record("s", at, sent = true, fields = fill("ORD-1", i), raw = "") }

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (flow.value.orders
                    .singleOrNull()
                    ?.events
                    ?.size == 6
            ) {
                break
            }
            Thread.sleep(10)
        }
        assertEquals(
            6,
            flow.value.orders
                .single()
                .events.size,
            "the trailing flush must deliver the last write with no further traffic to carry it",
        )
    }

    /**
     * Subscribing must not hand a collector a stale book either — `views()` flushes before returning,
     * so a panel that opens mid-run draws what the book holds rather than what it held a moment ago.
     */
    @Test
    fun `subscribing to a book that changed inside the window sees it current`() {
        val service = OrderBookService(spec, initialCap = 5_000)
        service.record("s", at, sent = false, fields = newOrder("ORD-1"), raw = "")
        repeat(3) { i -> service.record("s", at, sent = true, fields = fill("ORD-1", i), raw = "") }

        assertEquals(
            4,
            service
                .views("s")
                .value.orders
                .single()
                .events.size,
            "a fresh subscriber must see the book as it stands, not as it was last published",
        )
    }
}
