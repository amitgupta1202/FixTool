package com.knapsack.fixtool.model

import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The fold, asked the questions a venue's own behaviour asks it.**
 *
 * Everything here is a pure state machine over message facts — no session, no socket, no clock — so
 * a case that would take a client, a venue and two seconds of wall time to arrange is three lines.
 * What this cannot say is that the wire agrees; that is `ReplyWithIntegrationTest`'s sibling in this
 * slice.
 *
 * Two of these tests exist because of the design review, and they are the two that would go quietly
 * wrong: a fold that inherited OrderID across a replace, and a fold that added up fills instead of
 * believing the report.
 */
class OrderBookTest {
    private val spec = BookSpec.ORDERS
    private var clock = LocalDateTime.of(2026, 7, 31, 9, 14, 22)
    private var uid = 0L

    private fun event(sent: Boolean, msgType: String, vararg fields: Pair<Int, String>): OrderEvent {
        clock = clock.plusNanos(1_000_000)
        return OrderEvent(at = clock, sent = sent, msgType = msgType, fields = fields.toMap(), messageUid = uid++)
    }

    private fun received(msgType: String, vararg fields: Pair<Int, String>) = event(false, msgType, *fields)

    private fun sent(msgType: String, vararg fields: Pair<Int, String>) = event(true, msgType, *fields)

    private fun order(vararg events: OrderEvent, key: String = "ORD-1", inherited: OrderSnapshot? = null) =
        BookedOrder(key = key, events = events.toList(), inherited = inherited)

    private val newOrder =
        received("D", TAG_CL_ORD_ID to "ORD-1", TAG_SYMBOL to "VOD.L", TAG_SIDE to "1", TAG_ORDER_QTY to "5000", TAG_PRICE to "185.25")

    private fun ack(orderId: String = "EX-1") =
        sent(
            "8",
            TAG_CL_ORD_ID to "ORD-1",
            TAG_ORDER_ID to orderId,
            TAG_EXEC_TYPE to "0",
            TAG_ORD_STATUS to "0",
            TAG_CUM_QTY to "0",
            TAG_LEAVES_QTY to "5000",
        )

    private fun fill(cum: String, leaves: String, last: String, execType: String = "F", status: String = "1") =
        sent(
            "8",
            TAG_CL_ORD_ID to "ORD-1",
            TAG_ORDER_ID to "EX-1",
            TAG_EXEC_TYPE to execType,
            TAG_ORD_STATUS to status,
            TAG_CUM_QTY to cum,
            TAG_LEAVES_QTY to leaves,
            TAG_LAST_QTY to last,
        )

    // ------------------------------------------------------------------ the ordinary life of an order

    @Test
    fun `an order the venue has not answered yet is pending, and nothing is invented for it`() {
        val current = order(newOrder).current

        assertEquals(OrderState.PENDING, current.state)
        assertNull(current.orderId, "the venue has not named this order yet, and a book that guessed would be answering for it")
        assertNull(current.ordStatus)
        assertEquals("5000", current.orderQty)
        assertEquals("VOD.L", current.symbol)
        assertEquals("5000", current.leavesQty, "nothing has traded, and that is arithmetic on what the client sent")
    }

    @Test
    fun `acknowledged then filled twice ends working, on the quantities the venue reported`() {
        val booked = order(newOrder, ack(), fill(cum = "1500", leaves = "3500", last = "1500"), fill(cum = "2500", leaves = "2500", last = "1000"))

        val current = booked.current
        assertEquals(OrderState.WORKING, current.state)
        assertEquals("2500", current.cumQty)
        assertEquals("2500", current.leavesQty)
        assertEquals("EX-1", current.orderId)
    }

    @Test
    fun `a fill that finishes the order is done`() {
        val booked = order(newOrder, ack(), fill(cum = "5000", leaves = "0", last = "5000", status = "2"))

        assertEquals(OrderState.DONE, booked.current.state)
    }

    @Test
    fun `a report with no OrdStatus falls back to what is left`() {
        val booked =
            order(
                newOrder,
                sent("8", TAG_CL_ORD_ID to "ORD-1", TAG_EXEC_TYPE to "F", TAG_CUM_QTY to "5000", TAG_LEAVES_QTY to "0"),
            )

        assertEquals(OrderState.DONE, booked.current.state, "nothing is left, so nothing more will happen to it")
    }

    /** A cancel reject is a report *about* an order that does not move it. */
    @Test
    fun `a cancel reject leaves the order where it was`() {
        val booked =
            order(
                newOrder,
                ack(),
                fill(cum = "1500", leaves = "3500", last = "1500"),
                sent("9", TAG_CL_ORD_ID to "CXL-1", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_ORD_STATUS to "8"),
            )

        val current = booked.current
        assertEquals(OrderState.WORKING, current.state, "the venue refused the cancel; the order is still live")
        assertEquals("1500", current.cumQty, "and nothing about it has changed")
    }

    // ------------------------------------------------------------------ decision 2a — the report wins

    /**
     * **A bust takes back a fill the client has already been told about.** A fold that only ever
     * added would sit at 2500 while the client sits at 1500, and every later `${order.leavesQty}`
     * would fill quantity that is not there — the book silently disagreeing with the client's view,
     * which is the one direction decision 2 says not to be wrong in.
     */
    @Test
    fun `a bust moves CumQty down`() {
        val booked =
            order(
                newOrder,
                ack(),
                fill(cum = "1500", leaves = "3500", last = "1500"),
                fill(cum = "2500", leaves = "2500", last = "1000"),
                fill(cum = "1500", leaves = "3500", last = "1000", execType = "H"),
            )

        val current = booked.current
        assertEquals("1500", current.cumQty, "the second fill never happened, and the book has to agree")
        assertEquals("3500", current.leavesQty)
        assertEquals(OrderState.WORKING, current.state)
        assertEquals("bust 1000", booked.events.last().label)
    }

    @Test
    fun `a correct restates a fill rather than adding one`() {
        val booked =
            order(
                newOrder,
                ack(),
                fill(cum = "1500", leaves = "3500", last = "1500"),
                fill(cum = "1200", leaves = "3800", last = "1200", execType = "G"),
            )

        assertEquals("1200", booked.current.cumQty)
        assertEquals("correct 1200", booked.events.last().label)
    }

    @Test
    fun `a reported LeavesQty is booked even when it disagrees with the arithmetic`() {
        // A venue that reserves quantity, or one that is simply wrong. Either way the client was told
        // this, so this is what the book holds — the alternative is the tool arguing with the wire.
        val booked =
            order(
                newOrder,
                sent("8", TAG_CL_ORD_ID to "ORD-1", TAG_EXEC_TYPE to "0", TAG_ORD_STATUS to "0", TAG_CUM_QTY to "0", TAG_LEAVES_QTY to "4000"),
            )

        assertEquals("4000", booked.current.leavesQty)
    }

    // ------------------------------------------------------------------ decision 3a — identity is stated

    /**
     * **The test that fails the moment anyone hard-codes chain inheritance.**
     *
     * The same replace, sent by two venues that disagree about replacement identity — many keep one
     * OrderID for the life of a chain, several crypto exchanges and some futures venues mint a new
     * one. A preset writing `37=${order.orderId}` and one writing `37=${uuid}` must produce different
     * books, and the fold must not be able to tell them apart on any other basis than what tag 37
     * said.
     */
    @Test
    fun `a replacement books the OrderID the venue sent, whichever convention that is`() {
        val original = order(newOrder, ack(orderId = "EX-100006"))

        fun replacement(orderId: String) =
            BookedOrder(
                key = "ORD-7",
                supersedes = "ORD-1",
                inherited = original.current,
                events =
                    listOf(
                        sent(
                            "8",
                            TAG_CL_ORD_ID to "ORD-7",
                            TAG_ORIG_CL_ORD_ID to "ORD-1",
                            TAG_ORDER_ID to orderId,
                            TAG_EXEC_TYPE to "5",
                            TAG_ORD_STATUS to "0",
                            TAG_ORDER_QTY to "1500",
                        ),
                    ),
            )

        assertEquals("EX-100006", replacement("EX-100006").current.orderId, "a venue that keeps the chain's id")
        assertEquals("EX-200042", replacement("EX-200042").current.orderId, "a venue that mints a new one")
    }

    /**
     * The other half of the same decision: with no 37 on the report there is no OrderID, rather than
     * the predecessor's. Identity is something a venue states.
     */
    @Test
    fun `a replacement whose report names no OrderID has none, not its predecessor's`() {
        val original = order(newOrder, ack(orderId = "EX-100006"))
        val replacement =
            BookedOrder(
                key = "ORD-7",
                supersedes = "ORD-1",
                inherited = original.current,
                events =
                    listOf(
                        sent(
                            "8",
                            TAG_CL_ORD_ID to "ORD-7",
                            TAG_ORIG_CL_ORD_ID to "ORD-1",
                            TAG_EXEC_TYPE to "5",
                            TAG_ORD_STATUS to "0",
                        ),
                    ),
            )

        assertNull(replacement.current.orderId, "inheriting here would put an id on the wire the venue never sent")
        assertNotEquals("EX-100006", replacement.current.orderId)
    }

    /**
     * The deliberate asymmetry. How much of a chain has traded is a fact about the chain; which id it
     * wears is a fact the venue states. So quantities carry when the report omits them.
     */
    @Test
    fun `a replacement carries the quantities already done when its report omits them`() {
        val original = order(newOrder, ack(), fill(cum = "1500", leaves = "3500", last = "1500"))
        val replacement =
            BookedOrder(
                key = "ORD-7",
                supersedes = "ORD-1",
                inherited = original.current,
                events =
                    listOf(
                        sent(
                            "8",
                            TAG_CL_ORD_ID to "ORD-7",
                            TAG_ORIG_CL_ORD_ID to "ORD-1",
                            TAG_ORDER_ID to "EX-1",
                            TAG_EXEC_TYPE to "5",
                            TAG_ORD_STATUS to "0",
                        ),
                    ),
            )

        assertEquals("1500", replacement.current.cumQty, "1500 has traded on this chain and still has")
        assertEquals("VOD.L", replacement.current.symbol, "a report that omits the symbol does not un-symbol the order")
    }

    // ------------------------------------------------------------------ decision 6b — the row is its trail

    @Test
    fun `every snapshot is the fold of the events up to it`() {
        val booked =
            order(
                newOrder,
                ack(),
                fill(cum = "1500", leaves = "3500", last = "1500"),
                fill(cum = "2500", leaves = "2500", last = "1000"),
            )

        val snapshots = booked.snapshots()

        assertEquals(booked.events.size, snapshots.size, "one snapshot per event, or the trail cannot line up beside it")
        assertEquals(listOf(null, "0", "1500", "2500"), snapshots.map { it.cumQty })
        assertEquals(listOf("5000", "5000", "3500", "2500"), snapshots.map { it.leavesQty })
        assertEquals(booked.current, snapshots.last(), "the row is the last snapshot, or the panel and its evidence have drifted")
    }

    @Test
    fun `a prefix of the events folds to what the row said at the time`() {
        val events = listOf(newOrder, ack(), fill(cum = "1500", leaves = "3500", last = "1500"))

        val halfway = BookedOrder(key = "ORD-1", events = events.take(2)).current

        assertEquals("0", halfway.cumQty, "at the ack, nothing had traded — which is what a recorded reason has to be able to say")
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun `an order is born from the client, a report moves it, and neither is confused for the other`() {
        assertEquals(OrderBook.Outcome.Born("ORD-1"), OrderBook.route(newOrder, emptySet(), spec))
        assertEquals(OrderBook.Outcome.Moved("ORD-1"), OrderBook.route(ack(), setOf("ORD-1"), spec))
    }

    @Test
    fun `a report about an order nobody has seen is unattributed, not invented`() {
        val strayFill = sent("8", TAG_CL_ORD_ID to "GHOST-1", TAG_EXEC_TYPE to "F", TAG_CUM_QTY to "10")

        assertEquals(OrderBook.Outcome.Unattributed, OrderBook.route(strayFill, setOf("ORD-1"), spec))
    }

    @Test
    fun `a report with no ClOrdID at all is unattributed`() {
        val nameless = sent("8", TAG_ORDER_ID to "EX-9", TAG_EXEC_TYPE to "F")

        assertEquals(OrderBook.Outcome.Unattributed, OrderBook.route(nameless, setOf("ORD-1"), spec))
    }

    /**
     * A cancel reply carries the *cancel request's* ClOrdID in 11 and the order's in 41, which looks
     * exactly like a replacement to anything that only checks "new key, known 41". Read that way, the
     * venue's own cancel opens a book entry for the cancel request and leaves the order it canceled
     * reading `working` — the book disagreeing with the client's view of the very message it just
     * sent them. Found by the cancel presets; the fold already had [OrderBook.supersedes] and the
     * router was not asking it.
     */
    @Test
    fun `a cancel reports on the order it names, and does not open a chain for the cancel request`() {
        val pendingCancel =
            sent("8", TAG_CL_ORD_ID to "CXL-2", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_EXEC_TYPE to "6", TAG_ORD_STATUS to "6")
        val canceled =
            sent("8", TAG_CL_ORD_ID to "CXL-2", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_EXEC_TYPE to "4", TAG_ORD_STATUS to "4")

        assertEquals(OrderBook.Outcome.Moved("ORD-1"), OrderBook.route(pendingCancel, setOf("ORD-1"), spec))
        assertEquals(OrderBook.Outcome.Moved("ORD-1"), OrderBook.route(canceled, setOf("ORD-1"), spec))
        assertEquals(
            OrderState.DONE,
            order(newOrder, ack(), pendingCancel, canceled).state,
            "the client was told the order is canceled, so the book must say so too",
        )
    }

    @Test
    fun `a cancel reject reports on the order it names, when the book has one`() {
        val reject = sent("9", TAG_CL_ORD_ID to "CXL-2", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_ORD_STATUS to "8")

        assertEquals(OrderBook.Outcome.Moved("ORD-1"), OrderBook.route(reject, setOf("ORD-1"), spec))
        assertEquals(
            OrderState.WORKING,
            order(newOrder, ack(), reject).state,
            "refusing a cancel leaves the order exactly where it was",
        )
    }

    @Test
    fun `a replacement's report opens the chain link, and only the venue can open it`() {
        val replaced =
            sent("8", TAG_CL_ORD_ID to "ORD-7", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_EXEC_TYPE to "5", TAG_ORD_STATUS to "0")
        val clientAsking = received("G", TAG_CL_ORD_ID to "ORD-7", TAG_ORIG_CL_ORD_ID to "ORD-1", TAG_ORDER_QTY to "1500")

        assertEquals(OrderBook.Outcome.Chained("ORD-7", "ORD-1"), OrderBook.route(replaced, setOf("ORD-1"), spec))
        assertEquals(
            OrderBook.Outcome.Ignored,
            OrderBook.route(clientAsking, setOf("ORD-1"), spec),
            "a client asking for a replace is not the venue agreeing to one",
        )
    }

    // ------------------------------------------------------------------ what a trigger reads

    /**
     * A cancel names two ids and only one of them is the order. Getting this backwards is invisible in
     * every number on the panel — the book would simply answer a question nobody asked, and answer it
     * `unknown` for an order it is holding.
     */
    @Test
    fun `a cancel is a question about the order it supersedes, not about what it calls itself`() {
        val held = order(newOrder, ack())
        val cancel = mapOf(TAG_MSG_TYPE to "F", TAG_CL_ORD_ID to "CXL-4", TAG_ORIG_CL_ORD_ID to "ORD-1")

        assertEquals(listOf("ORD-1", "CXL-4"), OrderBook.namedKeys(cancel, spec), "41 before 11")

        val reading = OrderBook.reading(cancel, spec) { key -> held.takeIf { key == "ORD-1" } }
        assertEquals("ORD-1", reading.key)
        assertEquals(OrderState.WORKING, reading.state)
    }

    @Test
    fun `an order names itself, so a message with no 41 is read by its own ClOrdID`() {
        val fields = mapOf(TAG_MSG_TYPE to "D", TAG_CL_ORD_ID to "ORD-1")

        val reading = OrderBook.reading(fields, spec) { key -> order(newOrder).takeIf { key == "ORD-1" } }

        assertEquals("ORD-1", reading.key)
        assertEquals(OrderState.PENDING, reading.state)
    }

    /**
     * A reading the book cannot fill still says **which** order was asked after. "unknown" on its own
     * would leave a recorded reason unable to name what it did not find, and a reason that cannot say
     * what it was about is the kind that gets scrolled past.
     */
    @Test
    fun `an unknown order is still keyed by the name the message gave it`() {
        val cancel = mapOf(TAG_MSG_TYPE to "F", TAG_CL_ORD_ID to "CXL-4", TAG_ORIG_CL_ORD_ID to "ORD-9")

        val reading = OrderBook.reading(cancel, spec) { null }

        assertEquals("ORD-9", reading.key, "the order it asked about, not the cancel's own id")
        assertNull(reading.state)
        assertEquals("unknown", reading.word)
        assertTrue(reading.satisfies(OrderConstraint.UNKNOWN))
    }

    @Test
    fun `a message naming no order at all reads unknown, keyed by nothing`() {
        val reading = OrderBook.reading(mapOf(TAG_MSG_TYPE to "H"), spec) { order(newOrder) }

        assertNull(reading.key, "nothing was named, so nothing may be claimed to have been")
        assertNull(reading.state)
    }

    @Test
    fun `the reading carries what was left, so a hand-sent reply can say what it was answering`() {
        val partial =
            sent(
                "8",
                TAG_CL_ORD_ID to "ORD-1",
                TAG_EXEC_TYPE to "F",
                TAG_ORD_STATUS to "1",
                TAG_CUM_QTY to "2500",
                TAG_LEAVES_QTY to "2500",
            )
        val held = order(newOrder, ack(), partial)

        val reading = OrderBook.reading(mapOf(TAG_MSG_TYPE to "D", TAG_CL_ORD_ID to "ORD-1"), spec) { held }

        assertEquals("2500", reading.leavesQty)
    }

    /** Four words, three states and the absence of one — the whole vocabulary, pinned. */
    @Test
    fun `each word matches its own state and no other`() {
        val states = listOf(null, OrderState.PENDING, OrderState.WORKING, OrderState.DONE)
        val expected =
            mapOf(
                OrderConstraint.UNKNOWN to null,
                OrderConstraint.PENDING to OrderState.PENDING,
                OrderConstraint.WORKING to OrderState.WORKING,
                OrderConstraint.DONE to OrderState.DONE,
            )

        OrderConstraint.entries.forEach { constraint ->
            states.forEach { state ->
                assertEquals(
                    expected[constraint] == state,
                    constraint.matches(state),
                    "${constraint.word} against ${state ?: "no entry"}",
                )
            }
        }
    }

    @Test
    fun `a word survives the round trip it makes through a profile and a query string`() {
        OrderConstraint.entries.forEach { constraint ->
            assertEquals(constraint, OrderConstraint.byWord(constraint.word))
        }
        assertNull(OrderConstraint.byWord("filled"), "a word outside the vocabulary is refused, not guessed at")
    }

    @Test
    fun `the spec is data, so a book of something else is a configuration and not a rewrite`() {
        // Not built — quotes are named in the proposal's *What this is not*. This asserts only that
        // the fold is not welded to 35=D, which is the whole of decision 3b.
        val quotes =
            BookSpec(
                name = "quotes",
                keyTag = 117,
                chainTag = 131,
                idTag = 117,
                bornBy = setOf("S"),
                movedBy = setOf("AJ"),
                readTags = setOf(117, 131),
            )
        val quoteSent = OrderEvent(at = clock, sent = false, msgType = "S", fields = mapOf(117 to "Q-1"))

        assertEquals(OrderBook.Outcome.Born("Q-1"), OrderBook.route(quoteSent, emptySet(), quotes))
    }
}
