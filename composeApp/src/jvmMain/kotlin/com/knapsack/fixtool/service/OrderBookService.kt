package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.BookReading
import com.knapsack.fixtool.model.AppendOnlyList
import com.knapsack.fixtool.model.BookSpec
import com.knapsack.fixtool.model.BookedOrder
import com.knapsack.fixtool.model.OrderBook
import com.knapsack.fixtool.model.OrderEvent
import com.knapsack.fixtool.model.OrderState
import com.knapsack.fixtool.model.TAG_MSG_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import quickfix.Message
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A message the book could not tie to an order, kept rather than counted away. See decision 7. */
data class Unattributed(
    val at: LocalDateTime,
    val msgType: String,
    val raw: String,
    val why: String,
    val messageUid: Long = 0,
)

/**
 * One counterparty's book, and everything about it that a reader needs in order to distrust it
 * correctly.
 *
 * [clearedAt] is not decoration. An empty book reads identically as "no orders yet" and "somebody
 * pressed clear", and those send a tester in opposite directions.
 */
data class BookView(
    val orders: List<BookedOrder>,
    val unattributed: List<Unattributed>,
    val unattributedCount: Long,
    val evicted: Long,
    val cap: Int,
    val clearedAt: LocalDateTime? = null,
    val clearedBy: String? = null,
) {
    val working: Int get() = orders.count { it.state == OrderState.WORKING }
}

/**
 * **What the venue thinks it is holding, per counterparty.**
 *
 * The state is here and the decisions are in [OrderBook], which is a boundary worth keeping: this
 * class owns concurrency, bounds and eviction, and knows nothing about what a fill does to a
 * quantity.
 *
 * Fed from the wire — `fromApp` and `toApp` — never from the rules engine, so a reply typed by hand
 * into the editor moves the book exactly as a rule's does (decision 2). That is also why the feed
 * takes a raw message rather than a rule's plan: by the time these bytes exist, whoever composed them
 * is no longer a distinction the client could make.
 *
 * Thread safety is the ordinary kind and it matters: `fromApp` arrives on QuickFIX's read thread
 * while `toApp` can be on the dispatch thread or on the UI's send path, and a venue with four clients
 * has four of each.
 */
class OrderBookService(
    private val spec: BookSpec = BookSpec.ORDERS,
    initialCap: Int = DEFAULT_CAP,
) {
    /**
     * How many orders one counterparty's book keeps.
     *
     * Its own number rather than one derived from the session's message buffer: how much scrollback a
     * tester wants and how much order state a venue keeps are unrelated questions, and deriving one
     * from the other is the mistake the ingest path already made once, when a display preference
     * silently became a throughput limit.
     *
     * **Settable while books are open**, because the moment a tester discovers the cap is too small is
     * the middle of the soak run that proved it — and a setting you can only apply by reconnecting is
     * one that costs the very state it was raised to preserve. Volatile: read on the callback thread
     * of every session, written on the UI thread that saved Settings.
     */
    @Volatile
    private var cap: Int = initialCap.coerceAtLeast(1)

    companion object {
        const val DEFAULT_CAP = 5_000

        /**
         * How often a book's published view is rebuilt while it is being written to.
         *
         * The same cadence `FixMessageSession` drains on, chosen the same way: it is the fastest rate a
         * person can perceive as live, and it decouples what the panel costs from what the wire does.
         */
        private const val PUBLISH_INTERVAL_NANOS = 100_000_000L

        /**
         * One daemon thread for every book in the process, and it only ever runs the trailing flush —
         * the write that would otherwise be lost when traffic stops inside the pacing window.
         *
         * Daemon because it must never hold the process open, and shared because a thread per
         * connection to do a hundred-millisecond timer would be a thread per connection doing nothing.
         */
        private val FLUSHER: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "order-book-publish").apply { isDaemon = true }
            }

        /**
         * [message] reduced to the tags [spec] reads — **the one place a `quickfix.Message` meets the
         * book**, and the reason [OrderBook] never has to see one.
         *
         * Here rather than at each caller because there are now three (the wire capture, the trigger's
         * reading, the dry run's), and a second reduction that read one tag differently would put two
         * books in the same app.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun fieldsOf(message: Message, spec: BookSpec = BookSpec.ORDERS): Map<Int, String> =
            (spec.readTags + TAG_MSG_TYPE)
                .mapNotNull { tag ->
                    val value =
                        try {
                            when {
                                message.header.isSetField(tag) -> message.header.getString(tag)
                                message.isSetField(tag) -> message.getString(tag)
                                else -> null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    value?.let { tag to it }
                }.toMap()
    }

    private class Book(
        cap: Int,
    ) {
        val orders = LinkedHashMap<String, BookedOrder>()
        val unattributed = ArrayDeque<Unattributed>()
        val unattributedCount = AtomicLong()
        val evicted = AtomicLong()
        var clearedAt: LocalDateTime? = null
        var clearedBy: String? = null

        /**
         * **The book as something a panel can watch.**
         *
         * Published on every change rather than read on demand, because a panel that calls a plain
         * function has nothing to recompose it: the numbers freeze at whatever they were when the
         * panel first drew, and a stale book is worse than no book — it is a book that lies with a
         * straight face. Caught in live verification, where the panel showed CumQty 0 against a wire
         * that had already traded 2500.
         */
        val published = MutableStateFlow(BookView(emptyList(), emptyList(), 0, 0, cap))

        /** Something changed that [published] has not been told about yet. Guarded by the book's monitor. */
        var dirty = false

        /** A trailing flush is already booked, so [publish] must not book a second one. */
        var flushScheduled = false

        /** When [published] was last actually rebuilt, for pacing. */
        var lastPublishedAt = 0L
    }

    private val books = ConcurrentHashMap<String, Book>()

    /**
     * Changes the cap, and brings every book already open **within it**.
     *
     * Lowering evicts on the spot rather than waiting for the next order, because a book reporting
     * `cap 100` while holding 5,000 is a book lying about the one number this setting is. Finished
     * orders go first, as always, and the evictions are counted where every other eviction is counted
     * — a setting that quietly shrank a venue's memory would be exactly the silence decision 8a was
     * written against.
     *
     * Raising evicts nothing: the cap is a ceiling, and there is no work to do when it moves up.
     */
    fun setCap(newCap: Int) {
        val wanted = newCap.coerceAtLeast(1)
        if (wanted == cap) return
        cap = wanted
        books.values.forEach { book ->
            synchronized(book) {
                if (book.orders.size > wanted) {
                    evictIfNeeded(book)
                }
                // Published either way: `cap` is on the view, so a reader is owed the new number even
                // when nothing was dropped to reach it. And published AT ONCE rather than paced —
                // pacing exists to stop per-message traffic rebuilding the view a thousand times a
                // second, and a person changing a setting is not traffic. They changed it and they are
                // looking at it.
                publishNow(book)
            }
        }
    }

    /** [sessionKey]'s book, as a flow — created empty for a counterparty nothing has happened on yet. */
    fun views(sessionKey: String): StateFlow<BookView> =
        books.computeIfAbsent(sessionKey) { Book(cap) }.also(::flush).published.asStateFlow()

    /** Every counterparty this service has seen, in the order they first appeared. */
    fun sessions(): List<String> = books.keys().toList().sorted()

    fun view(sessionKey: String): BookView =
        books[sessionKey]?.also(::flush)?.published?.value
            ?: BookView(emptyList(), emptyList(), 0, 0, cap)

    /**
     * **Marks the book changed, and rebuilds the view at most ten times a second.**
     *
     * Call inside the book's lock, after every change.
     *
     * Rebuilding is O(orders): it copies every order in the book into a fresh list to make an immutable
     * `BookView`. Doing that per message made the cost of booking one message proportional to how much
     * the book already held — at the shipped cap of 5,000 orders, ~40KB and ~25us per message, on the
     * QuickFIX callback thread, for a panel that cannot draw faster than the screen refreshes.
     *
     * So it is paced, on the same 100ms cadence and for the same reason as `FixMessageSession`'s drain:
     * a viewer wants the current state ten times a second, not a thousand. What it is NOT is dropped —
     * a change that arrives inside the window books a trailing flush, so the last message before the
     * traffic stops still reaches the panel. That distinction is the whole design: a stale book is
     * worse than no book, and pacing without a trailing flush is how you get one.
     *
     * [view] flushes synchronously before reading, so a caller asking directly never sees the window.
     */
    private fun publish(book: Book) {
        book.dirty = true
        val now = System.nanoTime()
        if (now - book.lastPublishedAt >= PUBLISH_INTERVAL_NANOS) {
            materialize(book, now)
            return
        }
        if (!book.flushScheduled) {
            book.flushScheduled = true
            val dueInNanos = PUBLISH_INTERVAL_NANOS - (now - book.lastPublishedAt)
            FLUSHER.schedule(
                {
                    synchronized(book) {
                        book.flushScheduled = false
                        if (book.dirty) materialize(book, System.nanoTime())
                    }
                },
                dueInNanos / 1_000_000 + 1,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /**
     * Publishes immediately, whatever the pacing window says. Call inside the book's lock.
     *
     * For changes a person made and is watching for — the cap, a clear. Traffic goes through [publish].
     */
    private fun publishNow(book: Book) {
        book.dirty = true
        materialize(book, System.nanoTime())
    }

    /** Rebuilds [Book.published] from the book as it stands. Call inside the book's lock. */
    private fun materialize(book: Book, now: Long) {
        book.dirty = false
        book.lastPublishedAt = now
        book.published.value =
            BookView(
                orders = book.orders.values.toList(),
                unattributed = book.unattributed.toList(),
                unattributedCount = book.unattributedCount.get(),
                evicted = book.evicted.get(),
                cap = cap,
                clearedAt = book.clearedAt,
                clearedBy = book.clearedBy,
            )
    }

    /** Brings [book]'s published view up to date now, if anything is outstanding. */
    private fun flush(book: Book) {
        synchronized(book) { if (book.dirty) materialize(book, System.nanoTime()) }
    }

    fun order(sessionKey: String, key: String): BookedOrder? {
        val book = books[sessionKey] ?: return null
        synchronized(book) { return book.orders[key] }
    }

    /**
     * What this book says about the order [fields] names — the value a `whenOrder` constraint reads
     * and a recorded reason quotes.
     *
     * Taken as one atomic read under the book's own lock, so every rule in a list is judged against
     * the same answer. A reading assembled from two lookups could straddle a message arriving on
     * another thread, and the rule that fires would then be the one no state ever justified.
     */
    fun reading(sessionKey: String, fields: Map<Int, String>): BookReading {
        val book = books[sessionKey] ?: return OrderBook.reading(fields, spec) { null }
        synchronized(book) { return OrderBook.reading(fields, spec) { key -> book.orders[key] } }
    }

    /**
     * The order [fields] names, or null if this book has never seen it — the entry `${order.…}` reads.
     *
     * Distinct from [reading], which answers *what state* for a trigger and is deliberately small
     * enough to be stored in every recorded reason. This is the whole order, and it is looked up
     * again at each send rather than captured, because within one reply the earlier steps move it.
     */
    fun booked(sessionKey: String, fields: Map<Int, String>): BookedOrder? {
        val book = books[sessionKey] ?: return null
        synchronized(book) {
            OrderBook.namedKeys(fields, spec).forEach { key -> book.orders[key]?.let { return it } }
            return null
        }
    }

    /** Wipes one counterparty's book, and records that it was wiped rather than never filled. */
    fun clear(sessionKey: String, by: String = "manually", at: LocalDateTime = LocalDateTime.now()) {
        val book = books.computeIfAbsent(sessionKey) { Book(cap) }
        synchronized(book) {
            book.orders.clear()
            book.unattributed.clear()
            book.unattributedCount.set(0)
            book.evicted.set(0)
            book.clearedAt = at
            book.clearedBy = by
            // At once, like setCap and for the same reason: a person cleared this book and is watching
            // the panel to see it empty.
            publishNow(book)
        }
    }

    /**
     * Records one message against [sessionKey]'s book.
     *
     * [fields] is the message reduced to the tags [BookSpec.readTags] names — the reduction happens at
     * the caller because that is where a `quickfix.Message` is, and the whole point of [OrderBook] is
     * that it never sees one.
     */
    @Suppress("ReturnCount")
    fun record(
        sessionKey: String,
        at: LocalDateTime,
        sent: Boolean,
        fields: Map<Int, String>,
        raw: String,
        messageUid: Long = 0,
    ): OrderBook.Outcome {
        val msgType = fields[TAG_MSG_TYPE] ?: return OrderBook.Outcome.Ignored
        val event =
            OrderEvent(
                at = at,
                sent = sent,
                msgType = msgType,
                fields = fields.filterKeys { it in spec.readTags },
                messageUid = messageUid,
            )
        val book = books.computeIfAbsent(sessionKey) { Book(cap) }
        synchronized(book) {
            // The live key set, not a copy of it. `route` only asks `key in known` twice, and this
            // runs under the book's own monitor so nothing can mutate the map while it looks — but
            // `.toSet()` built a fresh HashSet of every key in the book on EVERY message. At the
            // shipped cap of 5,000 orders that was ~270KB and ~100us per message, on the QuickFIX
            // callback thread, to answer two membership questions. See OrderBookBenchmarkTest.
            val outcome = OrderBook.route(event, book.orders.keys, spec)
            when (outcome) {
                is OrderBook.Outcome.Ignored -> Unit
                is OrderBook.Outcome.Born -> {
                    book.orders[outcome.key] = BookedOrder(key = outcome.key, events = AppendOnlyList.of(event))
                    evictIfNeeded(book)
                }
                is OrderBook.Outcome.Moved -> {
                    val existing = book.orders.getValue(outcome.key)
                    book.orders[outcome.key] = existing.recording(event)
                }
                is OrderBook.Outcome.Chained -> {
                    val from = book.orders.getValue(outcome.from)
                    // The predecessor ends here, and says what ended it. Recording the report on both
                    // is not double counting: it is one message that is true of two orders, and a
                    // trail that omitted it on the superseded one would end mid-sentence.
                    book.orders[outcome.from] = from.recording(event).copy(supersededBy = outcome.key)
                    book.orders[outcome.key] =
                        BookedOrder(
                            key = outcome.key,
                            events = AppendOnlyList.of(event),
                            supersedes = outcome.from,
                            // Quantities travel; identity does not — see OrderBook.fold, decision 3a.
                            inherited = from.current,
                        )
                    evictIfNeeded(book)
                }
                is OrderBook.Outcome.Unattributed -> {
                    book.unattributedCount.incrementAndGet()
                    book.unattributed.addLast(
                        Unattributed(
                            at = at,
                            msgType = msgType,
                            raw = raw,
                            why =
                                event.field(spec.keyTag)?.let { "names ${spec.keyTag}=$it, which this book has never seen" }
                                    ?: "carries no ${spec.keyTag}, so there is nothing to attribute it to",
                            messageUid = messageUid,
                        ),
                    )
                    while (book.unattributed.size > UNATTRIBUTED_KEPT) book.unattributed.removeFirst()
                }
            }
            // Paced: this is the per-message path, and it is the only one that is.
            if (outcome !is OrderBook.Outcome.Ignored) publish(book)
            return outcome
        }
    }

    /**
     * Drops the oldest **finished** order first, and only reaches for a working one when there is
     * nothing finished left.
     *
     * A working order is the one a cancel is most likely to arrive for, and an evicted order answers
     * that cancel "unknown" — a venue behaviour the tester did not configure. Evicting the done ones
     * first is what makes the cap cost history rather than behaviour.
     */
    private fun evictIfNeeded(book: Book) {
        if (book.orders.size <= cap) return
        val victims = book.orders.entries.sortedBy { if (it.value.state == OrderState.DONE) 0 else 1 }
        var over = book.orders.size - cap
        for (entry in victims) {
            if (over <= 0) break
            book.orders.remove(entry.key)
            book.evicted.incrementAndGet()
            over--
        }
    }
}

/** How many unattributed messages one book keeps for reading; the *count* is never truncated. */
private const val UNATTRIBUTED_KEPT = 50
