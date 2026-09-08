package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.QuoteConstraint
import com.knapsack.fixtool.model.QuoteEntry
import com.knapsack.fixtool.model.QuoteReading
import com.knapsack.fixtool.model.QuoteState
import com.knapsack.fixtool.model.TAG_MSG_TYPE
import org.slf4j.LoggerFactory
import quickfix.Message
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * **One quote book per counterparty session, fed the same messages the order book is.**
 *
 * Beside [OrderBookService] and shaped like it, with one thing reversed: a quote is **born by a sent
 * `35=S`** and **moved by a received `35=AJ`**, because the venue mints the quote and the client answers
 * it. That is the whole reason this is not a `BookSpec`, whose `route()` decides by the other direction.
 *
 * The one indirection a quote flow forces: a client's QuoteResponse names the quote (117) and carries its
 * own id (693 QuoteRespID), and the venue's answer to it — an ExecutionReport for a booked hit, a
 * QuoteStatusReport for a refusal — echoes **693 and not 117**, because a FIX 4.4 ExecutionReport has no
 * QuoteID field. So a received AJ is remembered as `693 → 117`, and the sent reply is resolved through
 * that map. The AJ itself moves nothing: whether the quote is done depends on what the venue decided,
 * which is in the reply and not in the request.
 *
 * Thread safety is the ordinary kind: `fromApp` arrives on QuickFIX's read thread while `toApp` can be on
 * the dispatch thread or the UI's send path, and a venue with four clients has four of each.
 */
class QuoteBookService(
    /** Epoch millis. Injected so a test can quote one second and step past it without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
    initialCap: Int = DEFAULT_CAP,
) {
    private val logger = LoggerFactory.getLogger(QuoteBookService::class.java)

    @Volatile
    private var cap: Int = initialCap.coerceAtLeast(1)

    private class Book {
        /** Quote id to entry, in arrival order so eviction can drop the oldest finished one. */
        val quotes = LinkedHashMap<String, QuoteEntry>()

        /** QuoteRespID to the quote it answered, so the venue's own reply can find the quote. */
        val answering = LinkedHashMap<String, String>()
        var evicted = 0L
    }

    private val books = ConcurrentHashMap<String, Book>()

    /** Changes the cap and brings every open book within it, as the order book's setting does. */
    fun setCap(newCap: Int) {
        val wanted = newCap.coerceAtLeast(1)
        if (wanted == cap) return
        cap = wanted
        books.values.forEach { book -> synchronized(book) { evictIfNeeded(book) } }
    }

    /**
     * **The reading a rule is judged against**, taken before the incoming message is recorded.
     *
     * Before, exactly as the order book's `heldBefore` is: a hit has to be judged against the quote as it
     * was when the hit arrived, not against the quote its own reply is about to close.
     */
    fun reading(sessionKey: String, fields: Map<Int, String>): QuoteReading {
        val quoteId = fields[TAG_QUOTE_ID]?.takeIf { it.isNotBlank() } ?: return QuoteReading.unknown()
        val book = books[sessionKey] ?: return QuoteReading.unknown(quoteId)
        val entry = synchronized(book) { book.quotes[quoteId] }
        return QuoteReading.of(quoteId, entry, clock())
    }

    /**
     * Records one application message against this counterparty's quote book.
     *
     * Returns **true when the quote book claimed it**, which today is only a sent `35=8` that answers a
     * QuoteResponse. That message is a booked quote and not an order event, and offering it to the order
     * book as well is what produced the unattributed noise `docs/rfq-venue-proposal.md` records.
     */
    @Suppress("ReturnCount")
    fun record(sessionKey: String, sent: Boolean, fields: Map<Int, String>): Boolean {
        val msgType = fields[TAG_MSG_TYPE] ?: return false
        val book = books.computeIfAbsent(sessionKey) { Book() }
        return synchronized(book) {
            when {
                sent && msgType == MSG_QUOTE -> {
                    born(book, fields)
                    false
                }
                !sent && msgType == MSG_QUOTE_RESPONSE -> {
                    answering(book, fields)
                    false
                }
                sent && (msgType == MSG_EXECUTION_REPORT || msgType == MSG_QUOTE_STATUS_REPORT) ->
                    answered(book, msgType, fields)
                else -> false
            }
        }
    }

    /** A quote the venue has just sent. Callers hold the book's monitor. */
    private fun born(book: Book, fields: Map<Int, String>) {
        val quoteId = fields[TAG_QUOTE_ID]?.takeIf { it.isNotBlank() } ?: return
        book.quotes[quoteId] =
            QuoteEntry(
                quoteId = quoteId,
                quoteReqId = fields[TAG_QUOTE_REQ_ID],
                symbol = fields[TAG_SYMBOL],
                bid = fields[TAG_BID_PX],
                offer = fields[TAG_OFFER_PX],
                bidSize = fields[TAG_BID_SIZE],
                offerSize = fields[TAG_OFFER_SIZE],
                validUntil = fields[TAG_VALID_UNTIL]?.let(::epochMillisOf),
                state = QuoteState.OPEN,
            )
        evictIfNeeded(book)
    }

    /** A client's QuoteResponse, remembered by its own id so the venue's reply can find the quote. */
    private fun answering(book: Book, fields: Map<Int, String>) {
        val respId = fields[TAG_QUOTE_RESP_ID]?.takeIf { it.isNotBlank() } ?: return
        val quoteId = fields[TAG_QUOTE_ID]?.takeIf { it.isNotBlank() } ?: return
        book.answering[respId] = quoteId
        while (book.answering.size > cap) book.answering.remove(book.answering.keys.first())
    }

    /**
     * The venue's own answer, which is what decides whether the quote is done.
     *
     * An ExecutionReport books it. A QuoteStatusReport closes it only when it says `297=11`, a pass:
     * every other status is a refusal, and a quote the venue refused to trade is still the quote it sent.
     */
    private fun answered(book: Book, msgType: String, fields: Map<Int, String>): Boolean {
        val respId = fields[TAG_QUOTE_RESP_ID]?.takeIf { it.isNotBlank() } ?: return false
        val quoteId = book.answering[respId] ?: return false
        val closes = msgType == MSG_EXECUTION_REPORT || fields[TAG_QUOTE_STATUS] == QUOTE_STATUS_PASS
        if (closes) {
            book.quotes[quoteId]?.let { book.quotes[quoteId] = it.copy(state = QuoteState.DONE, doneBy = msgType) }
        }
        // Only a booked hit is withheld from the order book. A QuoteStatusReport carries no ClOrdID and
        // was never an order event to begin with.
        return msgType == MSG_EXECUTION_REPORT
    }

    /** Drops finished quotes first, then the oldest, as the order book does. Callers hold the monitor. */
    private fun evictIfNeeded(book: Book) {
        while (book.quotes.size > cap) {
            val key =
                book.quotes.entries.firstOrNull { it.value.state == QuoteState.DONE }?.key
                    ?: book.quotes.keys.first()
            book.quotes.remove(key)
            book.evicted++
        }
    }

    /** Forgets everything for one counterparty. Cleared with the order book, because they are one venue. */
    fun clear(sessionKey: String) {
        books[sessionKey]?.let {
            synchronized(it) {
                it.quotes.clear()
                it.answering.clear()
                it.evicted = 0
            }
        }
    }

    /** How many quotes this counterparty's book holds, and how many it has dropped. For a test and a panel. */
    fun size(sessionKey: String): Int = books[sessionKey]?.let { synchronized(it) { it.quotes.size } } ?: 0

    fun evicted(sessionKey: String): Long = books[sessionKey]?.let { synchronized(it) { it.evicted } } ?: 0

    /** Tag 62 on the UTC clock, as epoch millis, or null when it is not a UTCTimestamp. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun epochMillisOf(value: String): Long? {
        for (pattern in VALID_UNTIL_PATTERNS) {
            try {
                return LocalDateTime.parse(value.trim(), pattern).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: Exception) {
                continue
            }
        }
        logger.debug("Quote validity '{}' is not a UTCTimestamp, so the quote never expires", value)
        return null
    }

    companion object {
        const val DEFAULT_CAP = 5_000

        /** QuoteID, the tag that names a quote. Public because callers with no book still have to find it. */
        const val QUOTE_ID_TAG = TAG_QUOTE_ID

        /** The tags this book reads. Everything else in the message is somebody else's business. */
        val READ_TAGS =
            setOf(
                TAG_MSG_TYPE,
                TAG_QUOTE_ID,
                TAG_QUOTE_REQ_ID,
                TAG_SYMBOL,
                TAG_VALID_UNTIL,
                TAG_BID_PX,
                TAG_OFFER_PX,
                TAG_BID_SIZE,
                TAG_OFFER_SIZE,
                TAG_QUOTE_RESP_ID,
                TAG_QUOTE_STATUS,
            )

        /** The message reduced to [READ_TAGS], the same shape `OrderBookService.fieldsOf` produces. */
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        fun fieldsOf(message: Message): Map<Int, String> =
            READ_TAGS
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

        /** The word for a reading of an entry that is not there, for callers with no book to ask. */
        val UNKNOWN_WORD: String get() = QuoteConstraint.UNKNOWN.word

        private val VALID_UNTIL_PATTERNS =
            listOf(
                DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss"),
            )
    }
}

private const val TAG_SYMBOL = 55
private const val TAG_VALID_UNTIL = 62
private const val TAG_QUOTE_ID = 117
private const val TAG_QUOTE_REQ_ID = 131
private const val TAG_BID_PX = 132
private const val TAG_OFFER_PX = 133
private const val TAG_BID_SIZE = 134
private const val TAG_OFFER_SIZE = 135
private const val TAG_QUOTE_RESP_ID = 693
private const val TAG_QUOTE_STATUS = 297

private const val MSG_QUOTE = "S"
private const val MSG_QUOTE_RESPONSE = "AJ"
private const val MSG_EXECUTION_REPORT = "8"
private const val MSG_QUOTE_STATUS_REPORT = "AI"

/** QuoteStatus 11: the client passed. Every other status on an AI is a refusal, which closes nothing. */
private const val QUOTE_STATUS_PASS = "11"
