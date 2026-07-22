package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole

/**
 * **Which tags in this dictionary might need a role — asked once, from the dictionary alone.**
 *
 * The overlay ([com.knapsack.fixtool.model.TagRoleOverlay]) can express a venue's correlation ids, but a
 * file nobody knows to write is not a feature: you must already know the mechanism exists, know your
 * venue's tag numbers, and know that saying nothing silently degrades every capture. This scan is the
 * other half — it reads the dictionary the author has **already configured** and produces the list to
 * decide, so the question arrives without the author having to think to ask it.
 *
 * Deliberately not a prompt when a dictionary loads: FixTool's existing users configured theirs long ago
 * and that moment never comes again, and a venue that ships a new dictionary version adds tags to an
 * already-answered file. It has to be somewhere you can *go*.
 *
 * **The scan proposes candidates; it never assigns a role.** Nothing here can tell who *mints* a value —
 * that is exactly what a FIX dictionary does not record, and a wrong guess costs either duplicate
 * rejections at the venue or a scenario asserting that a field equals a uuid. The author decides; this
 * only makes sure they are shown the right dozen tags first, and that no tag is out of reach.
 *
 * **What changed, and why it mattered.** This used to offer only the tags a dictionary added *beyond*
 * standard FIX, on the reading that standard FIX is FixTool's own business. It is not: FixTool's built-in
 * sets answer for about forty tags, and standard FIX defines hundreds. `TradeLinkID(820)` is standard,
 * is minted by the venue on plenty of them, and was in no built-in set — so it seeded `Exact`, replayed a
 * dead link id every run, and the one surface that could have fixed it refused to show it. Being standard
 * meant "not the venue's invention"; it never meant "already decided". The two are separated now: what a
 * tag *is* orders the list ([Tier]), and nothing filters it.
 */
object VenueTagScan {
    /**
     * Where a tag sits in the list. An ordering, never a filter — [scan] returns every tag the dictionary
     * defines, because a venue correlation id whose name does not happen to end in `ID` must still be
     * reachable, and so must a standard one FixTool has no opinion about.
     */
    enum class Tier {
        /** The author has already answered for this tag. Shown first so a wrong call can be corrected. */
        DECLARED,

        /** Named like an identifier, and nothing else has answered for it. The likely questions. */
        IDENTIFIER,

        /** FixTool already answers for this one. Shown *with* its answer, so a venue can add to it. */
        BUILT_IN,

        /** Everything else this dictionary defines. Present, and below the fold. */
        OTHER,
    }

    data class Candidate(
        val tag: Int,
        /** The venue's own name, or null where its dictionary does not name the tag either. */
        val name: String?,
        val roles: Set<TagRole>,
        val tier: Tier,
        /** True when this dictionary defines the tag beyond standard FIX for its own version. */
        val custom: Boolean = false,
        /** What FixTool already decides for this tag — empty unless [tier] is [Tier.BUILT_IN]. */
        val builtIn: Set<TagRole> = emptySet(),
        /** Why FixTool decides it, where that is not a role. Null unless [tier] is [Tier.BUILT_IN]. */
        val builtInReason: String? = null,
    )

    /** A name that says "identifier". Mirrors the echo detector's gate, so the two surfaces agree. */
    private val IDENTIFIER_NAME = Regex("(ID|Id|Ref|Reference|Handle|Key)$")

    /**
     * **Standard tags whose value is reference data, not correlation** — instrument and account identity.
     *
     * `SecurityID(48)` echoes back on every flow and its name ends in `ID`, so it would sit at the top of
     * the identifier tier while being the highest-cost false positive there is: minting a fresh value for
     * it would not merely weaken a scenario, it would send the venue an instrument that does not exist.
     * So it is answered for, not offered — [Tier.BUILT_IN] with the reason on the row rather than hidden,
     * because a venue that genuinely mints one has to be able to see FixTool's answer to disagree with it.
     */
    internal val REFERENCE_DATA =
        setOf(
            1, // Account
            15, // Currency
            22, // SecurityIDSource
            48, // SecurityID
            55, // Symbol
            65, // SymbolSfx
            167, // SecurityType
            200, // MaturityMonthYear
            207, // SecurityExchange
            460, // Product
            541, // MaturityDate
        )

    /** Tags FixTool already answers for itself, with no dictionary and no overlay. */
    private val DECIDED_BY_BUILT_IN: Set<Int>
        get() =
            ScenarioCapture.ID_TAGS +
                ScenarioCapture.LIFETIME_TAGS +
                ScenarioCapture.TRANSPORT_TAGS +
                ExpectationSeeder.PRESENCE_TAGS +
                setOf(35, 60)

    /**
     * Every tag this dictionary defines, tiered — plus whatever the overlay already speaks for, so the
     * list is an editor rather than a one-way funnel.
     *
     * Two things are left out, and only two. A **bundled standard dictionary** returns nothing: it is
     * extracted to a temp file, so a sidecar written beside it would not survive a restart, and the tags
     * an author would be declaring are the ones FixTool ships answers for. And the **transport envelope**
     * ([SessionTags.REWRITTEN_ON_SEND]) is absent because the engine rewrites those fields on every send
     * whatever anyone declares — offering a control that cannot take effect is worse than not offering it.
     * A declaration already in the sidecar is listed regardless: an answer the author cannot see is an
     * answer they cannot revise.
     */
    fun scan(dictionary: FixDictionaryAdapter?): List<Candidate> {
        if (dictionary == null || dictionary.isStandard) return emptyList()
        if (dictionary.getFilePath() == null) return emptyList()
        val standard =
            runCatching { FixDictionaryAdapter.forVersion(dictionary.fixVersion).getAllFields() }
                .getOrDefault(emptyList())
                .mapTo(mutableSetOf()) { it.first }
        val declared = dictionary.tagRoles.declaredTags
        val decided = DECIDED_BY_BUILT_IN
        val tags = dictionary.getAllFields().mapTo(mutableSetOf()) { it.first }
        tags += declared
        return tags
            .asSequence()
            .filter { it in declared || it !in SessionTags.REWRITTEN_ON_SEND }
            .map { tag ->
                val name = dictionary.getFieldName(tag)
                val builtIn = builtInRolesOf(tag)
                Candidate(
                    tag = tag,
                    name = name,
                    roles = dictionary.tagRoles.rolesOf(tag),
                    tier =
                        when {
                            tag in declared -> Tier.DECLARED
                            tag in decided || tag in REFERENCE_DATA -> Tier.BUILT_IN
                            name == null || IDENTIFIER_NAME.containsMatchIn(name) -> Tier.IDENTIFIER
                            else -> Tier.OTHER
                        },
                    custom = tag !in standard,
                    builtIn = builtIn,
                    builtInReason = if (builtIn.isEmpty()) builtInReasonOf(tag) else null,
                )
            }
            // Numeric order, whatever the tier. Tier used to be the first sort key, which meant the list
            // was two interleaved runs of ascending tags — and with every tag now present it would be
            // four. A reader looking for 820 knows its number and nothing else about which tier it landed
            // in, so the number is what the order has to follow; the tier travels on the row instead.
            .sortedBy { it.tag }
            .toList()
    }

    /** FixTool's own role opinion for a tag, as the built-in sets state it. */
    private fun builtInRolesOf(tag: Int): Set<TagRole> =
        buildSet {
            if (tag in ScenarioCapture.ID_TAGS) add(TagRole.CLIENT_MINTED_ID)
            if (tag in ExpectationSeeder.VENUE_MINTED_IDS) add(TagRole.VENUE_MINTED_ID)
            if (tag in ScenarioCapture.LIFETIME_TAGS) add(TagRole.LIFETIME)
        }

    /** Why a tag is answered for where the answer is not a role — said plainly, on the row. */
    private fun builtInReasonOf(tag: Int): String? =
        when (tag) {
            in REFERENCE_DATA -> "reference data — asserted as captured, never minted"
            in SessionTags.VALUE_NOT_PORTABLE -> "environment addressing — asserted for presence"
            35 -> "the message type — matched, not asserted"
            60 -> "TransactTime — seeded from its type as a timestamp"
            else -> null
        }

    /**
     * The one-line summary the settings row shows without opening anything.
     *
     * Counts what is **open** — the tags nobody has answered for that look like identifiers — rather than
     * the size of the dictionary, because the number that should move an author to open the dialog is the
     * number of unanswered questions.
     */
    fun summary(dictionary: FixDictionaryAdapter?): String {
        val all = scan(dictionary)
        if (all.isEmpty()) return "no tags to declare"
        val declared = all.count { it.tier == Tier.DECLARED }
        val identifiers = all.count { it.tier == Tier.IDENTIFIER }
        return "${all.size} tag${if (all.size == 1) "" else "s"} · $declared declared · " +
            "$identifiers look${if (identifiers == 1) "s" else ""} like an identifier"
    }
}
