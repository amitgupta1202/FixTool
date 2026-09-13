package com.knapsack.fixtool.model

/**
 * **The words a venue that relays between two parties is written in.**
 *
 * Every one of these is parsed from a **string** on disk and none of them is ever serialised as an enum.
 * An older FixTool decodes profiles with `ignoreUnknownKeys`, which forgives a new field, but a new *value*
 * in an enum it knows fails the decode, `loadProfiles()` comes back empty, and its next save writes one
 * profile over the file. So the vocabulary lives in strings, and a word nobody recognises is a named
 * refusal on the rule's card rather than a lost file. See `docs/rfq-relay-impl-plan.md`, fact 2.
 */
enum class PartyRole {
    /** Raises the request: a buy side asking for a price. */
    REQUESTER,

    /** Answers it: a dealer quoting one. */
    RESPONDER,

    ;

    val word: String get() = name.lowercase()

    companion object {
        fun byWord(word: String?): PartyRole? = entries.firstOrNull { it.word == word?.trim()?.lowercase() }
    }
}

/**
 * **What a trigger may ask about the sender's part on this venue.**
 *
 * `unlisted` is a word and not a role, for the reason `unknown` is one in [QuoteConstraint]: it is the
 * absence of an entry — a counterparty that logged on and matches nothing the venue declared.
 */
enum class SenderRole {
    REQUESTER,
    RESPONDER,
    UNLISTED,

    ;

    val word: String get() = name.lowercase()

    /** True when a counterparty whose declared role is [role] (null: none) is what this asks for. */
    fun matches(role: PartyRole?): Boolean =
        when (this) {
            REQUESTER -> role == PartyRole.REQUESTER
            RESPONDER -> role == PartyRole.RESPONDER
            UNLISTED -> role == null
        }

    companion object {
        fun byWord(word: String?): SenderRole? = entries.firstOrNull { it.word == word?.trim()?.lowercase() }

        val words: List<String> get() = entries.map { it.word }
    }
}

/**
 * **What a trigger may ask the RFQ book**, in four words, as `whenQuote` asks the quote book in four.
 *
 * The book keeps a finer life than this — requested, open, refused, passed, done, expired — and a trigger is
 * told the part of it that decides what a venue may still do: `open` covers requested and open, because in
 * both a quote can still arrive; `done` covers refused, passed and traded, because in all three the
 * negotiation is over. The book's own view keeps the full word.
 */
enum class RfqConstraint {
    UNKNOWN,
    OPEN,
    DONE,
    EXPIRED,

    ;

    val word: String get() = name.lowercase()

    companion object {
        fun byWord(word: String?): RfqConstraint? = entries.firstOrNull { it.word == word?.trim()?.lowercase() }

        val words: List<String> get() = entries.map { it.word }
    }
}

/** Whether any responder is logged on to be asked. The one question about a venue no tag can carry. */
enum class RespondersOnline {
    NONE,
    SOME,

    ;

    val word: String get() = name.lowercase()

    companion object {
        fun byWord(word: String?): RespondersOnline? = entries.firstOrNull { it.word == word?.trim()?.lowercase() }

        val words: List<String> get() = entries.map { it.word }
    }
}

/**
 * One counterparty a venue expects, and the part it plays.
 *
 * [compId] may end in `*` to cover a family — `FIDLRLG*` is a five-lane dealer load client. [role] is a
 * string for the reason everything in this file is; [PartyRole.byWord] reads it.
 */
@kotlinx.serialization.Serializable
data class Counterparty(
    val compId: String,
    val role: String,
) {
    val isPrefix: Boolean get() = compId.endsWith("*")

    /** True when [candidate] is this counterparty, exactly or by prefix. */
    fun covers(candidate: String): Boolean =
        if (isPrefix) candidate.startsWith(compId.dropLast(1)) else candidate == compId
}

/**
 * The role [compId] plays among [counterparties], or null when none of them covers it.
 *
 * An exact entry beats any prefix, and a longer prefix beats a shorter one, so `FIDLR1` can be carved out of
 * a `FIDLR*` family. An entry whose role is not a word is skipped rather than guessed at.
 */
fun roleOf(
    counterparties: List<Counterparty>,
    compId: String,
): PartyRole? =
    counterparties
        .filter { it.covers(compId) }
        .sortedWith(compareBy<Counterparty> { it.isPrefix }.thenByDescending { it.compId.length })
        .firstNotNullOfOrNull { PartyRole.byWord(it.role) }

/** The names a `${rfq.*}` may carry in a reply template. */
object RfqEntryNames {
    val FIELDS = listOf("requester", "quoter", "asked", "quoted", "state")
}

/**
 * **The whole life of an RFQ as the book keeps it.** A trigger is told the four-word [RfqConstraint] this
 * reduces to; the venue pane shows this.
 */
enum class RfqLife {
    /** A requester asked, and the venue has relayed it to nobody yet. */
    REQUESTED,

    /** Relayed to at least one responder; quotes may arrive. */
    OPEN,

    /** The venue answered the requester with a QuoteRequestReject instead of relaying. */
    REFUSED,

    /** The requester passed. */
    PASSED,

    /** A trade was decided. */
    DONE,

    /** Its time ran out before anything else ended it. */
    EXPIRED,

    ;

    val word: String get() = name.lowercase()

    /** True while a quote can still arrive and be relayed. */
    val live: Boolean get() = this == REQUESTED || this == OPEN
}

/** How one responder's part in an RFQ ended, when it has. */
enum class LegOutcome {
    NOT_DELIVERED,
    PASSED,
    LIFTED,
    COVER,
    DONE_AWAY,

    ;

    val word: String get() = name.lowercase().replace('_', ' ')
}

/** One quote a responder sent on an RFQ, and the id the venue showed the requester for it. */
data class LegQuote(
    val dealerQuoteId: String,
    val venueQuoteId: String? = null,
    val bid: String? = null,
    val offer: String? = null,
    val bidSize: String? = null,
    val offerSize: String? = null,
    /** Tag 62 as epoch millis. Null: the dealer quoted no validity. */
    val validUntil: Long? = null,
    /** The dealer has since quoted again, so this level no longer stands. */
    val superseded: Boolean = false,
) {
    fun liveAt(now: Long): Boolean = !superseded && (validUntil == null || validUntil >= now)
}

/** One responder on one RFQ. */
data class RfqLeg(
    val responderKey: String,
    val compId: String,
    /** The QuoteReqID the venue sent this responder. */
    val venueQuoteReqId: String? = null,
    val quotes: List<LegQuote> = emptyList(),
    val outcome: LegOutcome? = null,
) {
    /** The quote that still stands, if any: the latest one, when it has not lapsed. */
    fun currentQuote(now: Long): LegQuote? = quotes.lastOrNull()?.takeIf { it.liveAt(now) }
}

/** One negotiation, from the requester's QuoteRequest to however it ended. */
data class RfqEntry(
    val rfqId: String,
    val requesterKey: String,
    val requesterCompId: String,
    /** The QuoteReqID the requester sent. */
    val requesterQuoteReqId: String,
    /** The opening QuoteRequest's `uid`, for Trace. */
    val openingUid: Long?,
    /** The opening QuoteRequest's fields, read through its groups, for rules that fire without a message. */
    val opening: Map<Int, String>,
    val openedAt: Long,
    /** Epoch millis after which an RFQ still live reads `expired`. Null: never. */
    val expireAt: Long?,
    val life: RfqLife,
    val legs: List<RfqLeg> = emptyList(),
) {
    val symbol: String? get() = opening[55]
    val securityId: String? get() = opening[48]
    val securityType: String? get() = opening[167]
    val side: String? get() = opening[54]
    val qty: String? get() = opening[38]

    fun leg(responderKey: String): RfqLeg? = legs.firstOrNull { it.responderKey == responderKey }

    /** [life] with the clock applied: a live RFQ past [expireAt] is expired though nobody has said so. */
    fun lifeAt(now: Long): RfqLife = if (life.live && expireAt != null && expireAt < now) RfqLife.EXPIRED else life
}

/**
 * **What the RFQ book said about one message, at one moment** — frozen when taken, like `QuoteReading`,
 * because a lift judged `open` re-reads as `done` a moment later and a reason must quote the first answer.
 *
 * [leg] and [quote] are set when the message named a quote id (117) the venue had relayed: the responder it
 * belongs to and the quote it stands for.
 */
data class RfqReading(
    val rfqId: String?,
    val entry: RfqEntry?,
    val word: String,
    val leg: RfqLeg? = null,
    val quote: LegQuote? = null,
) {
    fun satisfies(constraint: RfqConstraint): Boolean = constraint.word == word

    companion object {
        fun unknown() = RfqReading(null, null, RfqConstraint.UNKNOWN.word)
    }
}

/** Every RFQ a venue holds, for a panel. */
data class RfqBookView(
    val rfqs: List<RfqEntry> = emptyList(),
    val evicted: Long = 0,
)
