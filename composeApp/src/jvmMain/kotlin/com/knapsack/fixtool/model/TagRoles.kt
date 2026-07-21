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
     * **We mint it; the venue echoes it.** Parameterized on send so every run is fresh (venues enforce
     * uniqueness on these), and its echo on a reply becomes a `Reference` check plus a bind constraint,
     * so the step binds to *this run's* reply rather than the first message of that type.
     */
    CLIENT_MINTED_ID,

    /**
     * **The venue mints it.** Seeded `Presence`: that the field is there is the behaviour, its value is
     * new on every reply. Seeded `Exact` instead, the scenario fails its own first replay for ever.
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
 * **A venue's answers about its own tags, beside its own dictionary.**
 *
 * Read from `<dictionary-file>.roles.json` — so `fix-dictionary-4-4.xml` is accompanied by
 * `fix-dictionary-4-4.xml.roles.json`:
 *
 * ```json
 * { "20013": "CLIENT_MINTED_ID", "1751": "CLIENT_MINTED_ID", "20040": "VENUE_MINTED_ID" }
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
