package com.knapsack.fixtool.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * **What a tag's value MEANS for a replay** — the one thing a FIX dictionary cannot tell us.
 *
 * A dictionary gives a tag a name and a type. Neither says *who mints the value*, and that is the only
 * question a replay actually needs answered: `ClOrdID(11)` and `OrderID(37)` are both STRING fields whose
 * names end in `ID`, and treating one as the other costs either a duplicate rejection at the venue or a
 * scenario asserting that a field equals a freshly-minted uuid.
 *
 * FixTool knows the answer for standard FIX by hardcoded set ([ScenarioCapture.ID_TAGS],
 * [ExpectationSeeder.PRESENCE_TAGS]). It cannot know it for the 20000-range a venue invented — and those
 * tags must not go in the source, because FixTool is one tool across a dozen venues and a proprietary tag
 * hardcoded for one of them is a claim about all of them. So the venue's own answers travel with the
 * venue's own dictionary. See [TagRoleOverlay].
 */
enum class TagRole {
    /**
     * **The client of the relationship mints it; the venue echoes it.** `ClOrdID(11)` is the type case.
     *
     * The name says *which party*, not *which of us* — see [MintingSide]. Whoever mints it parameterizes
     * it on send so every run is fresh (venues enforce uniqueness on these), and the echo coming back
     * becomes a `Reference` check plus a bind constraint, so the step binds to *this run's* reply rather
     * than the first message of that type. The party that merely receives it reads it instead: presence,
     * plus a `bindAs` for whatever quotes it back.
     */
    CLIENT_MINTED_ID,

    /**
     * **The venue mints it.** `OrderID(37)`, `ExecID(17)`. Party-relative exactly as [CLIENT_MINTED_ID] is:
     * the venue-side of the conversation invents it, and which end of that FixTool occupies is
     * [MintingSide]'s question, not this enum's.
     */
    VENUE_MINTED_ID,

    /**
     * **A lifetime stamp.** Replayed verbatim it is already expired; stamped `now` it expires on arrival.
     * Sent as a short future offset, and asserted for presence rather than a moment.
     */
    LIFETIME,
    ;

    companion object {
        fun parse(text: String): TagRole? = entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
    }
}

/**
 * **Which end of the venue relationship FixTool occupies on a session.**
 *
 * [TagRole] says which *party* mints a value. It does not say whether that party is us — and capture
 * used to assume it always was, i.e. that FixTool is forever the client dialling out. On an acceptor
 * session FixTool *is* the venue, and every consequence of a role inverts: the `ClOrdID(11)` we used to
 * mint fresh on send now arrives from the counterparty and must be **echoed**, while the `OrderID(37)`
 * and `ExecID(17)` we used to assert for presence are now ours to mint fresh — and a replayed `ExecID`
 * is the duplicate the parameterization exists to prevent.
 *
 * Seeded under the wrong side the result is not an error but a plausible-looking scenario that asserts
 * the wrong things, and on the send half it never goes red at all: nothing asserts our own Send, so a
 * green run can be emitting a reply whose ClOrdID answers no order. Hence a side per **session** rather
 * than per capture — a both-sides RFQ scenario legitimately captures one of each at once.
 */
enum class MintingSide {
    /** FixTool dials out to the venue. The historical assumption, and still the default. */
    CLIENT,

    /** FixTool is an acceptor simulating the venue. Every [TagRole] consequence inverts. */
    VENUE,
}

/**
 * **A venue's answers about its own tags, beside its own dictionary.**
 *
 * Read from `<dictionary-file>.roles.json` — so `fix-dictionary-4-4.xml` is accompanied by
 * `fix-dictionary-4-4.xml.roles.json`:
 *
 * ```json
 * { "20001": "CLIENT_MINTED_ID", "1701": "CLIENT_MINTED_ID", "20002": "VENUE_MINTED_ID" }
 * ```
 *
 * A tag may carry more than one role, as an array — `QuoteID(117)` is the standard case and it is real:
 * whoever is quoting mints it, so on a dealer-side RFQ it is ours and on a buy-side one it is theirs.
 * Capture resolves that per capture, by whether this scenario's own send minted the value, and a model
 * that stored one role per tag would quietly break the dealer side.
 *
 * **A sidecar, not an edit to the dictionary XML**: the venue ships you that file and their next release
 * overwrites whatever you added to it. This one is yours, it is a dozen lines, and it belongs in the repo
 * beside the dictionary so a team shares one answer rather than each author re-deciding per capture.
 *
 * Unreadable or malformed content is **not** an error that stops anything — it is the absence of an
 * answer, which is exactly where FixTool was before the file existed. Bad entries are skipped
 * individually, so one typo does not discard a venue's whole declaration.
 */
class TagRoleOverlay private constructor(
    private val byTag: Map<Int, Set<TagRole>>,
) {
    fun rolesOf(tag: Int): Set<TagRole> = byTag[tag].orEmpty()

    fun has(tag: Int, role: TagRole): Boolean = role in rolesOf(tag)

    /** Every tag this overlay speaks for — the set capture may treat as classified. */
    val declaredTags: Set<Int> get() = byTag.keys

    val isEmpty: Boolean get() = byTag.isEmpty()

    val size: Int get() = byTag.size

    companion object {
        val EMPTY = TagRoleOverlay(emptyMap())

        /** `<dictionary>.roles.json`, the file we look for beside a loaded dictionary. */
        fun sidecarFor(dictionaryPath: String): File = File("$dictionaryPath.roles.json")

        /** The overlay beside [dictionaryPath], or [EMPTY] — a missing sidecar is the normal case. */
        fun beside(dictionaryPath: String?): TagRoleOverlay {
            val file = dictionaryPath?.let { sidecarFor(it) } ?: return EMPTY
            return if (file.isFile) read(file.readText()) else EMPTY
        }

        /**
         * Writes [roles] to the sidecar beside [dictionaryPath], and returns the file written.
         *
         * Tags with **no** roles are dropped rather than written as an empty list: "I considered 20002 and
         * decided it is nothing" and "I have never heard of 20002" lead to identical behaviour, and a file
         * that records the difference invites a reader to believe the tool acts on it.
         *
         * Sorted by tag and pretty-printed, because this file belongs in the venue's repo beside the
         * dictionary — a diff of it should read as a decision, not as a serializer's output order.
         */
        fun writeBeside(dictionaryPath: String, roles: Map<Int, Set<TagRole>>): File {
            val kept = roles.filterValues { it.isNotEmpty() }.toSortedMap()
            val body =
                kept.entries.joinToString(",\n") { (tag, set) ->
                    val values = set.sortedBy { it.name }.joinToString(", ") { "\"${it.name}\"" }
                    val rendered = if (set.size == 1) values else "[$values]"
                    """  "$tag": $rendered"""
                }
            val file = sidecarFor(dictionaryPath)
            file.writeText(if (kept.isEmpty()) "{}\n" else "{\n$body\n}\n")
            return file
        }

        /**
         * Parses the sidecar's text. Tolerant on purpose: an unparseable tag or an unknown role name is
         * skipped rather than thrown, because the alternative is a venue's whole declaration vanishing
         * over one typo — silently, at capture time, which is where it costs the most.
         */
        fun read(text: String): TagRoleOverlay {
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return EMPTY
            val byTag = mutableMapOf<Int, Set<TagRole>>()
            for ((key, value) in root) {
                val tag = key.trim().toIntOrNull() ?: continue
                val roles =
                    runCatching { value.jsonArray.mapNotNull { TagRole.parse(it.jsonPrimitive.content) } }
                        .getOrElse { listOfNotNull(runCatching { TagRole.parse(value.jsonPrimitive.content) }.getOrNull()) }
                if (roles.isNotEmpty()) byTag[tag] = roles.toSet()
            }
            return if (byTag.isEmpty()) EMPTY else TagRoleOverlay(byTag)
        }
    }
}
