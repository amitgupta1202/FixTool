package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole

/**
 * **Which of this venue's own tags might need a role — asked once, from the dictionary alone.**
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
 * only makes sure they are shown the right dozen tags instead of the wrong thousand.
 */
object VenueTagScan {
    /**
     * Where a tag sits in the list. An ordering, never a filter — [scan] returns every custom tag, because
     * a venue correlation id whose name does not happen to end in `ID` must still be reachable.
     */
    enum class Tier {
        /** The author has already answered for this tag. Shown first so a wrong call can be corrected. */
        DECLARED,

        /** Named like an identifier. The likely answers, surfaced without hiding anything else. */
        IDENTIFIER,

        /** Everything else this venue added. Present, and below the fold. */
        OTHER,
    }

    data class Candidate(
        val tag: Int,
        /** The venue's own name, or null where its dictionary does not name the tag either. */
        val name: String?,
        val roles: Set<TagRole>,
        val tier: Tier,
    )

    /** A name that says "identifier". Mirrors the echo detector's gate, so the two surfaces agree. */
    private val IDENTIFIER_NAME = Regex("(ID|Id|Ref|Reference|Handle|Key)$")

    /**
     * **Standard tags whose value is reference data, not correlation** — instrument and account identity.
     *
     * They are excluded from the candidate list because they are the highest-cost false positive there is:
     * `SecurityID(48)` echoes back on every flow and its name ends in `ID`, and minting a fresh value for
     * it would not merely weaken a scenario, it would send garbage to the venue. Hardcoding these is
     * consistent with the no-proprietary-tags rule — every one of them is standard FIX.
     */
    private val REFERENCE_DATA =
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
     * The tags this dictionary adds beyond the standard one for its own FIX version, minus everything
     * already decided — plus whatever the overlay already speaks for, so the list is an editor rather than
     * a one-way funnel.
     *
     * "Custom" is measured against the **bundled dictionary for the dictionary's own version**, which is
     * why that dictionary has to be a whole one: measured against a subset, most of standard FIX looks
     * like a venue invention and the list becomes noise. (It was a 93-field subset until 2026-07-21.)
     */
    fun scan(dictionary: FixDictionaryAdapter?): List<Candidate> {
        if (dictionary == null) return emptyList()
        val standard =
            runCatching { FixDictionaryAdapter.forVersion(dictionary.fixVersion).getAllFields() }
                .getOrDefault(emptyList())
                .mapTo(mutableSetOf()) { it.first }
        val declared = dictionary.tagRoles.declaredTags
        val custom =
            dictionary
                .getAllFields()
                .map { it.first }
                .filter { it !in standard }
                .toMutableSet()
        // A declared tag belongs in the list whatever it is — the author answered for it, and an answer
        // they cannot see is an answer they cannot revise.
        custom += declared
        return custom
            .asSequence()
            .filter { it in declared || (it !in DECIDED_BY_BUILT_IN && it !in REFERENCE_DATA) }
            .map { tag ->
                val name = dictionary.getFieldName(tag)
                val roles = dictionary.tagRoles.rolesOf(tag)
                Candidate(
                    tag = tag,
                    name = name,
                    roles = roles,
                    tier =
                        when {
                            roles.isNotEmpty() -> Tier.DECLARED
                            name == null || IDENTIFIER_NAME.containsMatchIn(name) -> Tier.IDENTIFIER
                            else -> Tier.OTHER
                        },
                )
            }
            .sortedWith(compareBy({ it.tier.ordinal }, { it.tag }))
            .toList()
    }

    /** The one-line summary the settings row shows without opening anything. */
    fun summary(dictionary: FixDictionaryAdapter?): String {
        val all = scan(dictionary)
        if (all.isEmpty()) return "no venue-specific tags"
        val declared = all.count { it.tier == Tier.DECLARED }
        val identifiers = all.count { it.tier == Tier.IDENTIFIER }
        return "${all.size} venue tag${if (all.size == 1) "" else "s"} · $declared declared · " +
            "$identifiers look${if (identifiers == 1) "s" else ""} like an identifier"
    }
}
