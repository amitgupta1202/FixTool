package com.knapsack.fixtool.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Where an order has got to, as far as the client has been told.
 *
 * Three, not the four the trigger vocabulary has: `unknown` is not a state an entry can be in, it is
 * the absence of one. See `docs/acceptor-order-state-proposal.md`, decision 4.
 */
enum class OrderState {
    /** The venue has the order; nothing has been sent back about it yet. */
    PENDING,

    /** Acknowledged and unfinished. */
    WORKING,

    /** Filled, canceled, replaced or rejected — nothing more will happen to it. */
    DONE,
}

/**
 * The one thing a trigger can ask the book — four words, and deliberately not the `OrdStatus` zoo.
 *
 * Decision 4: four words a tester can hold in their head, each one naming a rule somebody actually
 * wants to write. `unknown` is the fourth because it is not an [OrderState] — it is the absence of
 * an entry, which is the whole point of the cancel-for-an-order-that-was-never-placed case.
 *
 * Spelled lowercase on disk so a hand-written profile reads as the sentence it is: *when 35=F and
 * the order is unknown*.
 */
@Serializable
enum class OrderConstraint {
    @SerialName("unknown")
    UNKNOWN,

    @SerialName("pending")
    PENDING,

    @SerialName("working")
    WORKING,

    @SerialName("done")
    DONE,

    ;

    /** The word as it is written and read — the same string in a profile, a rule card and a reason. */
    val word: String get() = name.lowercase()

    /** True when [state] — null meaning the book holds no such entry — is what this asks for. */
    fun matches(state: OrderState?): Boolean =
        when (this) {
            UNKNOWN -> state == null
            PENDING -> state == OrderState.PENDING
            WORKING -> state == OrderState.WORKING
            DONE -> state == OrderState.DONE
        }

    companion object {
        /** The constraint [word] names, or null — used where a word arrives from JSON or a query string. */
        fun byWord(word: String): OrderConstraint? = entries.firstOrNull { it.word == word.trim().lowercase() }

        /** The vocabulary, for saying what an unrecognised word could have been. */
        val words: List<String> get() = entries.map { it.word }
    }
}

/**
 * **What the book said about one message's order, at one moment.**
 *
 * A value rather than a live lookup, and that is the whole of decision 6a: the answer is only true of
 * the instant it was taken. A cancel judged `unknown` at 09:14:22 re-reads as `working` at 09:14:25,
 * and a tool that re-derives the reason after the fact would state the second one confidently about
 * the first one's reply.
 *
 * [state] null means the book has never seen this entry — the `unknown` of [OrderConstraint].
 */
data class BookReading(
    /** The key the book was asked about: the order the message named, or null if it named none. */
    val key: String?,
    val state: OrderState?,
    /** LeavesQty as the book had it, so a recorded reason can say what was still open. */
    val leavesQty: String? = null,
) {
    /** What the book said, in the words a trigger is written in. */
    val word: String get() = state?.word ?: OrderConstraint.UNKNOWN.word

    fun satisfies(constraint: OrderConstraint): Boolean = constraint.matches(state)

    companion object {
        /** A book that has never seen the order — also what a venue with no history says about everything. */
        fun unknown(key: String? = null) = BookReading(key = key, state = null)
    }
}

private val OrderState.word: String get() = name.lowercase()

/**
 * One message that touched an order, as **facts off the message** and nothing else.
 *
 * Deliberately not a snapshot of the order after it. The book's claim is that every number on a row
 * is the fold of that row's own events (decision 6b), and a claim you can only check by trusting the
 * thing that made it is not a claim. Storing what each message *said* and folding on read keeps
 * `OrderBookTest`'s "the row is its trail, folded" assertion honest; storing the running totals here
 * would make it vacuous.
 */
data class OrderEvent(
    val at: LocalDateTime,
    /** True for a message the venue sent, false for one it received. */
    val sent: Boolean,
    val msgType: String,
    /** The fields this message carried, of the ones [BookSpec.readTags] names. */
    val fields: Map<Int, String>,
    /** Links the event back to the message in the grid, so the trail is clickable. */
    val messageUid: Long = 0,
) {
    fun field(tag: Int): String? = fields[tag]?.takeIf { it.isNotBlank() }

    /** ExecType (150), the field that says what a report *is* rather than where the order got to. */
    val execType: String? get() = field(TAG_EXEC_TYPE)

    /** What this event did, in the words a trail is read in: "ack", "fill 1500", "bust 1000". */
    val label: String
        get() {
            val qty = field(TAG_LAST_QTY)
            return when {
                !sent -> if (msgType == "D") "order received" else "$msgType received"
                msgType == "9" -> "cancel rejected"
                execType == "0" -> "ack"
                execType == "F" || execType == "1" || execType == "2" -> listOfNotNull("fill", qty).joinToString(" ")
                execType == "H" -> listOfNotNull("bust", qty).joinToString(" ")
                execType == "G" -> listOfNotNull("correct", qty).joinToString(" ")
                execType == "4" -> "canceled"
                execType == "6" -> "pending cancel"
                execType == "E" -> "pending replace"
                execType == "5" -> "replaced"
                execType == "8" -> "rejected"
                execType == "I" -> "status"
                execType == "C" -> "expired"
                else -> "35=$msgType" + (execType?.let { " 150=$it" } ?: "")
            }
        }
}

/**
 * The order as it stands after some prefix of its events — what a row shows, and what
 * `${order.…}` will read in slice C.
 *
 * Every field is nullable because every one of them is something a venue may simply not have said
 * yet. A book that invented `CumQty 0` for an order nobody has reported on would be answering for
 * the venue, which is the one thing decision 2 forbids.
 */
data class OrderSnapshot(
    val state: OrderState,
    val orderId: String? = null,
    val ordStatus: String? = null,
    val orderQty: String? = null,
    val cumQty: String? = null,
    val leavesQty: String? = null,
    val avgPx: String? = null,
    val price: String? = null,
    val symbol: String? = null,
    val side: String? = null,
)

/**
 * One order, as the list of messages that touched it plus the chain it belongs to.
 *
 * [supersedes] and [supersededBy] are the replace chain, recorded from tag 41. **They carry no
 * identity** — see [OrderBook.fold] and decision 3a: whether a replacement keeps the OrderID is the
 * venue's business, expressed by whatever the preset writes into tag 37, and the book records what
 * was sent rather than deciding what should have been.
 */
data class BookedOrder(
    val key: String,
    val events: List<OrderEvent>,
    val supersedes: String? = null,
    val supersededBy: String? = null,
    /** The snapshot the chain starts from — a replacement's inherited quantities, not its identity. */
    val inherited: OrderSnapshot? = null,
) {
    /** The state after each event in turn: what the trail draws, and what proves the row. */
    fun snapshots(): List<OrderSnapshot> = OrderBook.fold(this)

    val current: OrderSnapshot get() = snapshots().lastOrNull() ?: OrderSnapshot(state = OrderState.PENDING)

    val state: OrderState get() = current.state

    /** When something last happened to this order. */
    val lastAt: LocalDateTime? get() = events.lastOrNull()?.at

    val firstAt: LocalDateTime? get() = events.firstOrNull()?.at

    /**
     * This order with one more event on its trail.
     *
     * The single place a trail grows, so the structure that makes growing it cheap cannot be bypassed
     * by a caller writing `copy(events = events + event)` — which is exactly what every call site used
     * to say, and what made assembling a 5,000-fill trail allocate 509MB.
     */
    fun recording(event: OrderEvent): BookedOrder = copy(events = events.appended(event))
}

/**
 * [event] appended, sharing the prefix when the receiver can.
 *
 * A [BookedOrder] built anywhere else — a test, a fixture — holds an ordinary `listOf`, and the first
 * append converts it. After that the trail grows in O(1). See [AppendOnlyList].
 */
internal fun List<OrderEvent>.appended(event: OrderEvent): List<OrderEvent> =
    if (this is AppendOnlyList<OrderEvent>) append(event) else AppendOnlyList.from(this).append(event)

/**
 * What starts a chain, what moves it, what it is keyed by — **as data**.
 *
 * `orders` is one configuration of the fold, not the fold itself (decision 3b). Quote state is the
 * obvious sibling — keyed by QuoteID, born on `35=S`, moved by `35=AJ`/`35=AG`, and *"an order
 * referencing a quote we never sent"* is the RFQ desk's version of the unknown cancel. It is
 * deliberately **not built** (see the proposal's *What this is not*); the point of keeping this as
 * data is only that building it later is a configuration rather than a second design.
 */
data class BookSpec(
    val name: String,
    /** The tag that names an entry: ClOrdID for orders. */
    val keyTag: Int,
    /** The tag that names the entry a message supersedes: OrigClOrdID for orders. */
    val chainTag: Int,
    /** The identity the venue assigns: OrderID for orders. Recorded, never inherited. */
    val idTag: Int,
    /** Received message types that open a chain. */
    val bornBy: Set<String>,
    /** Sent message types that move one. */
    val movedBy: Set<String>,
    /** Everything the fold or the panel reads off a message. */
    val readTags: Set<Int>,
) {
    companion object {
        val ORDERS =
            BookSpec(
                name = "orders",
                keyTag = TAG_CL_ORD_ID,
                chainTag = TAG_ORIG_CL_ORD_ID,
                idTag = TAG_ORDER_ID,
                bornBy = setOf("D"),
                movedBy = setOf("8", "9"),
                readTags =
                    setOf(
                        TAG_CL_ORD_ID,
                        TAG_ORIG_CL_ORD_ID,
                        TAG_ORDER_ID,
                        TAG_EXEC_TYPE,
                        TAG_ORD_STATUS,
                        TAG_ORDER_QTY,
                        TAG_CUM_QTY,
                        TAG_LEAVES_QTY,
                        TAG_AVG_PX,
                        TAG_PRICE,
                        TAG_LAST_QTY,
                        TAG_SYMBOL,
                        TAG_SIDE,
                        TAG_TEXT,
                    ),
            )
    }
}

const val TAG_MSG_TYPE = 35
const val TAG_AVG_PX = 6
const val TAG_CL_ORD_ID = 11
const val TAG_CUM_QTY = 14
const val TAG_ORDER_ID = 37
const val TAG_ORDER_QTY = 38
const val TAG_ORD_STATUS = 39
const val TAG_ORIG_CL_ORD_ID = 41
const val TAG_PRICE = 44
const val TAG_SIDE = 54
const val TAG_SYMBOL = 55
const val TAG_TEXT = 58
const val TAG_LAST_QTY = 32
const val TAG_LEAVES_QTY = 151
const val TAG_EXEC_TYPE = 150

/**
 * The fold, and the rules for what one message does to one entry.
 *
 * Pure: no sessions, no quickfix, no clock. Everything stateful about the book lives in
 * `OrderBookService`, and everything *decidable* about it lives here, so the state machine can be
 * asked questions in a unit test rather than over a socket.
 */
object OrderBook {
    /**
     * The state after each of [order]'s events, oldest first.
     *
     * Two rules do all the work, and both are review decisions rather than conveniences:
     *
     * **The report wins** (decision 2a). Where a sent report carries `CumQty` or `LeavesQty`, those
     * are the values booked — the fold never prefers its own running total to what the venue told the
     * client. This is what makes a bust (`150=H`) or a correct (`150=G`) work: they restate
     * quantities *downward*, and a fold that only ever added would sit above the client's own view
     * and then fill quantity that is not there.
     *
     * **Identity is stated, never inherited** (decision 3a). [OrderSnapshot.orderId] comes from tag
     * 37 on a message, or from this same order's previous events — never from the order it
     * supersedes. Venues disagree about replacement identity: many keep one OrderID for a chain,
     * several crypto exchanges and some futures venues mint a new one, and both are venues someone
     * needs to simulate. So `37=${order.orderId}` and `37=${uuid}` in a preset must produce different
     * books, and nothing here may collapse them.
     *
     * Quantities are the deliberate asymmetry: they *do* carry across a replace when the report omits
     * them ([BookedOrder.inherited]), because how much of a chain has traded is a fact about the
     * chain, while which id it wears is a fact the venue states.
     */
    fun fold(order: BookedOrder): List<OrderSnapshot> {
        var snapshot =
            order.inherited?.copy(
                state = OrderState.PENDING,
                // The successor's identity is the successor's own business — see above. Cleared here
                // rather than never copied, so the inherited *quantities* can travel in one object.
                orderId = null,
                ordStatus = null,
            ) ?: OrderSnapshot(state = OrderState.PENDING)
        val out = mutableListOf<OrderSnapshot>()
        order.events.forEach { event ->
            snapshot = advance(snapshot, event)
            out += snapshot
        }
        return out
    }

    private fun advance(previous: OrderSnapshot, event: OrderEvent): OrderSnapshot {
        val orderQty = event.field(TAG_ORDER_QTY) ?: previous.orderQty
        val cumQty = event.field(TAG_CUM_QTY) ?: previous.cumQty
        val leavesQty = event.field(TAG_LEAVES_QTY) ?: derivedLeaves(orderQty, cumQty, previous.leavesQty)
        val ordStatus = event.field(TAG_ORD_STATUS) ?: previous.ordStatus
        return OrderSnapshot(
            state = stateOf(event, ordStatus, leavesQty, previous.state),
            orderId = event.field(TAG_ORDER_ID) ?: previous.orderId,
            ordStatus = ordStatus,
            orderQty = orderQty,
            cumQty = cumQty,
            leavesQty = leavesQty,
            avgPx = event.field(TAG_AVG_PX) ?: previous.avgPx,
            price = event.field(TAG_PRICE) ?: previous.price,
            symbol = event.field(TAG_SYMBOL) ?: previous.symbol,
            side = event.field(TAG_SIDE) ?: previous.side,
        )
    }

    /**
     * Only when nothing said it. A derived LeavesQty is arithmetic on two values the venue *did*
     * send; a reported one is what the client was told, and those are not the same claim.
     */
    private fun derivedLeaves(orderQty: String?, cumQty: String?, fallback: String?): String? {
        val total = orderQty?.toBigDecimalOrNull() ?: return fallback
        val done = cumQty?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return (total - done).stripTrailingZeros().toPlainString()
    }

    /**
     * OrdStatus decides when the venue sent one, because that is the field whose whole job is to say
     * where the order is. LeavesQty is the fallback for a report that omits it, and the previous
     * state is the fallback for a message that says nothing about either — a cancel reject (`35=9`)
     * is a report *about* an order that leaves it exactly where it was.
     */
    private fun stateOf(event: OrderEvent, ordStatus: String?, leavesQty: String?, previous: OrderState): OrderState {
        if (!event.sent) return if (previous == OrderState.PENDING) OrderState.PENDING else previous
        if (event.msgType == "9") return previous
        return when (ordStatus) {
            "0", "1", "6", "E", "A" -> OrderState.WORKING
            "2", "4", "5", "8", "C" -> OrderState.DONE
            null -> if (leavesQty?.toBigDecimalOrNull()?.signum() == 0) OrderState.DONE else OrderState.WORKING
            else -> if (leavesQty?.toBigDecimalOrNull()?.signum() == 0) OrderState.DONE else OrderState.WORKING
        }
    }

    /** What a message did to the book — see `OrderBookService`, which is what acts on it. */
    sealed interface Outcome {
        /** Opens a chain: a received order. */
        data class Born(
            val key: String,
        ) : Outcome

        /** Moves one that is already booked. */
        data class Moved(
            val key: String,
        ) : Outcome

        /** Opens a chain from an existing one: a replacement the venue has just reported. */
        data class Chained(
            val key: String,
            val from: String,
        ) : Outcome

        /** Names no entry this book holds. Counted and listed, never silently dropped (decision 7). */
        data object Unattributed : Outcome

        /** Nothing to do with this book at all — an admin message, a heartbeat, another MsgType. */
        data object Ignored : Outcome
    }

    /**
     * What [event] does to a book that currently holds [known] keys.
     *
     * Kept separate from [fold] because they answer different questions: this one is about *which*
     * entry a message belongs to, that one is about what the entry then looks like. The routing is
     * where "unattributed" is decided, and it is the half a test can get wrong without any number
     * looking odd.
     */
    fun route(event: OrderEvent, known: Set<String>, spec: BookSpec): Outcome {
        val key = event.field(spec.keyTag)
        val chain = event.field(spec.chainTag)
        return when {
            !event.sent && event.msgType in spec.bornBy && key != null ->
                if (key in known) Outcome.Moved(key) else Outcome.Born(key)
            !event.sent -> Outcome.Ignored
            event.msgType !in spec.movedBy -> Outcome.Ignored
            key != null && key in known -> Outcome.Moved(key)
            // A report naming an order this book holds, under a ClOrdID of its own. **What it is
            // depends on whether it replaces that order or merely reports on it**, and the difference
            // is [supersedes]:
            //
            //   150=5  11=ORD-7  41=ORD-6   a replacement — ORD-6 ends, ORD-7 begins
            //   150=4  11=CXL-2  41=ORD-1   ORD-1 is canceled; CXL-2 is the *request's* id, not an order
            //
            // Chaining both meant a cancel reply opened a book entry for the cancel request and left
            // the order it canceled reading `working` — the book disagreeing with the client's own
            // view of the message it had just sent them, which is the one direction decision 2 says
            // not to be wrong in. Only the venue can open a chain link either way: a book never
            // invents one from an inbound 35=G, because the client asking is not the venue agreeing.
            chain != null && chain in known ->
                if (key != null && supersedes(event)) Outcome.Chained(key, chain) else Outcome.Moved(chain)
            // A report about an order this book has never seen. Includes the cancel reject naming an
            // unknown OrigClOrdID, which is exactly the message a venue sends when it agrees.
            else -> Outcome.Unattributed
        }
    }

    /**
     * True when this report **replaces** the order it names in 41, rather than merely reporting on it.
     *
     * `150=5` is ExecType *Replaced* and is what says so; `39=5` is accepted alongside it because a
     * venue that sets one and not the other is common enough, and reading a replace as a cancel would
     * lose the chain entirely. Everything else naming a 41 — a cancel, a pending cancel, a reject — is
     * about the order already in the book and belongs to it.
     *
     * Written when the fold was, and left uncalled: [route] chained on "new key, known 41" alone,
     * which is also the shape of every cancel reply a venue sends.
     */
    fun supersedes(event: OrderEvent): Boolean =
        event.sent && (event.execType == "5" || event.field(TAG_ORD_STATUS) == "5")

    /**
     * **The vocabulary `${order.…}` reads**, and what each name is worth for [order] as it stands.
     *
     * Names, not tag numbers, and that is a claim rather than a style: `${order.14}` would be a lie.
     * Tag 14 is a field of a *message*, and half of what this returns is on no message at all — it is
     * what the fold computed from the ones that were. A reader who sees `${order.cumQty}` knows to
     * ask the book; one who saw `${order.14}` would reasonably go looking at the wire.
     *
     * A name the venue has never stated is **absent, not empty**. That is the whole of how the
     * refusal works one level up: a caller can ask which names a template needs and which of them
     * this order can answer, and say so, instead of substituting nothing and putting `37=` on the
     * wire as a real field with no value.
     */
    fun fields(order: BookedOrder): Map<String, String> {
        val current = order.current
        return buildMap {
            put("clOrdId", order.key)
            order.supersedes?.let { put("origClOrdId", it) }
            current.orderId?.let { put("orderId", it) }
            current.ordStatus?.let { put("ordStatus", it) }
            current.orderQty?.let { put("orderQty", it) }
            current.cumQty?.let { put("cumQty", it) }
            current.leavesQty?.let { put("leavesQty", it) }
            current.avgPx?.let { put("avgPx", it) }
            current.price?.let { put("price", it) }
            current.symbol?.let { put("symbol", it) }
            current.side?.let { put("side", it) }
        }
    }

    /**
     * Every name [fields] can produce, whether or not a given order has one.
     *
     * Kept so a template writing `${order.leaves}` can be told it is not a name at all, rather than
     * told this order has not got one — those two send an author to different places.
     */
    val names: List<String> =
        listOf(
            "orderId",
            "clOrdId",
            "origClOrdId",
            "symbol",
            "side",
            "orderQty",
            "cumQty",
            "leavesQty",
            "avgPx",
            "price",
            "ordStatus",
        )

    /**
     * The entries [fields] names, in the order a lookup should try them.
     *
     * **41 before 11**, because when a message carries both, 41 is the order it is *about* and 11 is
     * what the message calls itself. A cancel request `11=CXL-4 41=ORD-9` is a question about ORD-9;
     * answering it with whatever the book happens to hold under CXL-4 would be answering a different
     * question. 11 is the fallback for everything that names no predecessor — an order, a status
     * request — where the message's own id *is* the order's.
     */
    fun namedKeys(fields: Map<Int, String>, spec: BookSpec): List<String> =
        listOfNotNull(fields[spec.chainTag], fields[spec.keyTag])
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * What the book — reached through [lookup] — says about the order [fields] names.
     *
     * The first key [namedKeys] offers that [lookup] knows wins. When it knows none, the reading is
     * `unknown` *keyed by the first name the message offered*, so a reason can still say which order
     * was asked after and not merely that something was.
     */
    fun reading(fields: Map<Int, String>, spec: BookSpec, lookup: (String) -> BookedOrder?): BookReading {
        val names = namedKeys(fields, spec)
        names.forEach { key ->
            val found = lookup(key) ?: return@forEach
            val current = found.current
            return BookReading(key = key, state = current.state, leavesQty = current.leavesQty)
        }
        return BookReading.unknown(names.firstOrNull())
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? =
    try {
        BigDecimal(trim())
    } catch (e: NumberFormatException) {
        null
    }
