package com.knapsack.fixtool.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **What a venue is holding for a client, in quotes.**
 *
 * The order book's smaller sibling, and deliberately not a [BookSpec] of it. `OrderBook.route()` decides
 * what a message does by direction: an entry is **born by a received message** and moved by a sent one,
 * which is the shape of every order flow. A quote is the other way round. The venue *sends* the `35=S`
 * that creates it and *receives* the `35=AJ` that answers it, so bending the order book's spec to carry
 * quotes would mean inverting the one rule it is built on.
 *
 * Small on purpose: a quote has one life (open, then answered or expired) and no partial fills, no
 * working state and no leaves quantity. Four words, and the arithmetic is a clock comparison.
 */
enum class QuoteState {
    /** Sent, not yet answered, and its validity has not been reached. */
    OPEN,

    /** Hit, or passed on. Either way the venue will not honour it twice. */
    DONE,
}

/** One quote the venue has sent, as the book holds it. */
data class QuoteEntry(
    val quoteId: String,
    val quoteReqId: String?,
    val symbol: String?,
    val bid: String?,
    val offer: String?,
    val bidSize: String?,
    val offerSize: String?,
    /** Tag 62 as epoch millis, read on the UTC clock. Null when the venue quoted no validity. */
    val validUntil: Long?,
    val state: QuoteState,
    /** `"8"` when a hit booked it, `"AI"` when a pass closed it. Null while it is open. */
    val doneBy: String? = null,
) {
    /** Whether this quote's validity has passed at [now]. A quote with no validity never expires. */
    fun expiredAt(now: Long): Boolean = validUntil != null && validUntil < now

    /**
     * One field by the name a rule and a reply template write, which is the `${quote.*}` vocabulary.
     *
     * Null both for "the venue did not say it" and for a name outside the vocabulary. The refusals that
     * matter are made where the name is authored, not here.
     */
    fun field(name: String): String? =
        when (name) {
            "quoteId" -> quoteId
            "quoteReqId" -> quoteReqId
            "symbol" -> symbol
            "bid" -> bid
            "offer" -> offer
            "bidSize" -> bidSize
            "offerSize" -> offerSize
            "validUntil" -> validUntil?.toString()
            "state" -> state.name.lowercase()
            else -> null
        }

    companion object {
        /** The names a `${quote.*}` may carry, and the only ones [field] answers to. */
        val FIELDS =
            listOf("quoteId", "quoteReqId", "symbol", "bid", "offer", "bidSize", "offerSize", "validUntil", "state")
    }
}

/**
 * **What a rule may ask the quote book**, in the four words a tester can hold in their head.
 *
 * The same shape as [OrderConstraint], and `unknown` is a word here for the same reason it is one there:
 * it is not a [QuoteState] but the absence of an entry, which is the hit-for-a-quote-we-never-sent case.
 * `expired` is not a state either — it is `open` plus a clock — because a quote does not become expired
 * at a moment anybody sends a message, and storing it would mean sweeping the book to keep it true.
 *
 * Spelled lowercase on disk so a hand-written profile reads as the sentence it is: *when 35=AJ and the
 * quote is expired*.
 */
@Serializable
enum class QuoteConstraint {
    @SerialName("unknown")
    UNKNOWN,

    @SerialName("open")
    OPEN,

    @SerialName("expired")
    EXPIRED,

    @SerialName("done")
    DONE,

    ;

    /** The word as it is written and read: the same string in a profile, a rule card and a reason. */
    val word: String get() = name.lowercase()

    /** True when [entry] — null meaning the book holds no such quote — is what this asks for at [now]. */
    fun matches(entry: QuoteEntry?, now: Long): Boolean =
        when (this) {
            UNKNOWN -> entry == null
            DONE -> entry != null && entry.state == QuoteState.DONE
            EXPIRED -> entry != null && entry.state == QuoteState.OPEN && entry.expiredAt(now)
            OPEN -> entry != null && entry.state == QuoteState.OPEN && !entry.expiredAt(now)
        }

    companion object {
        /** The constraint [word] names, or null, for a word arriving from JSON or a query string. */
        fun byWord(word: String): QuoteConstraint? = entries.firstOrNull { it.word == word.trim().lowercase() }

        /** The vocabulary, for saying what an unrecognised word could have been. */
        val words: List<String> get() = entries.map { it.word }
    }
}

/**
 * **What the quote book said about one message's quote, at one moment.**
 *
 * A value and not a live lookup, for the reason [BookReading] is one: the answer is only true of the
 * instant it was taken. A hit judged `open` at 09:14:22 re-reads as `done` the moment its own reply is
 * recorded, and a tool that re-derived the reason afterwards would state the second answer confidently
 * about the first one's reply. So [word] is frozen here too.
 */
data class QuoteReading(
    /** The quote the message named, or null when it named none. */
    val quoteId: String?,
    val entry: QuoteEntry?,
    /** What the book said, in the words a trigger is written in, decided when the reading was taken. */
    val word: String,
) {
    fun satisfies(constraint: QuoteConstraint): Boolean = constraint.word == word

    /** One field of the quote, for a `${quote.*}` and for the `quoteField` matcher. */
    fun field(name: String): String? = entry?.field(name)

    companion object {
        /** A book that has never sent this quote, which is also what a venue with no history says. */
        fun unknown(quoteId: String? = null) = QuoteReading(quoteId, null, QuoteConstraint.UNKNOWN.word)

        /** The reading for [entry] at [now], which is where `expired` is decided. */
        fun of(quoteId: String?, entry: QuoteEntry?, now: Long): QuoteReading {
            val word = QuoteConstraint.entries.first { it.matches(entry, now) }.word
            return QuoteReading(quoteId, entry, word)
        }
    }
}
