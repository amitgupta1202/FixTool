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
