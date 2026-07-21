package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.TagRole

/**
 * **Correlation ids found by watching a value come back, rather than by being told.**
 *
 * [VenueTagScan] reads a dictionary and asks the author about tags that *look* like identifiers. That
 * covers a venue's named ids in one pass, and it cannot cover two things, both of which need a **flow**:
 *
 * - an id whose name says nothing — on a real venue dictionary the un-id-shaped tail is over a hundred
 *   tags deep, and no one is reading it;
 * - an id the **venue** minted that a later send quotes back, which needs to know *which reply minted it*.
 *
 * Capture already holds the evidence: we sent this value, and it came back. That is an observed echo
 * whatever the tag number is. The only reason the standard echo rewrite is gated on a tag list at all is
 * the false positive at [ScenarioCapture.captureFrom]'s correlation step — an ordinary `11=1` matching
 * `Side(54)=1` — and that is a **value-entropy** problem, not a tag-identity one. `1` coming back is
 * coincidence; `A7F3C201881B` coming back is not.
 *
 * **It proposes and never applies.** Every proposal is the author's to accept, for the same reason the
 * fix plan's presence demotion is default-unchecked: the gate is deliberately wider than certainty.
 */
object EchoDetector {
    /**
     * Which way the value travelled first, which is what decides the role. Not a confidence level — these
     * are different facts about the world and they want opposite treatments.
     */
    enum class Kind {
        /** We said it first, they echoed it: ours to mint fresh every run. */
        MINT,

        /** They said it first, we quoted it back: ours to *read* from this run's reply. */
        CAPTURE,
    }

    data class Proposal(
        val kind: Kind,
        /** Every tag this value travelled in — declaring the proposal declares all of them. */
        val tags: List<Int>,
        val role: TagRole,
        /** The observed value, shown as evidence. Never used as a matcher. */
        val value: String,
        /** The variable the author would get, named from the venue's own vocabulary. */
        val suggestedName: String,
        /** One sentence naming both messages, so the author can check the claim rather than trust it. */
        val evidence: String,
    )

    /** Minimum length before an echoed value is worth a second look. */
    private const val MIN_LENGTH = 8

    /** Below this, a run of pure digits is a sequence number or a quantity, not an id. */
    private const val MIN_NUMERIC_LENGTH = 12

    /** A value under this many characters must mix character classes to be believable as an id. */
    private const val MIN_SINGLE_CLASS_LENGTH = 12

    /** Appear under this many distinct tags in one message and you are a constant, not a correlation. */
    private const val CONSTANT_TAG_COUNT = 3

    /** Types whose values are never identifiers, whatever they echo. */
    private val NON_IDENTIFIER_TYPES =
        setOf(
            "UTCTIMESTAMP", "UTCTIMEONLY", "TZTIMESTAMP", "TZTIMEONLY", "TIME",
            "UTCDATE", "UTCDATEONLY", "LOCALMKTDATE", "MONTHYEAR",
            "PRICE", "PRICEOFFSET", "QTY", "AMT", "FLOAT", "PERCENTAGE",
            "INT", "SEQNUM", "NUMINGROUP", "LENGTH", "BOOLEAN",
        )

    /** A name that says identifier. Mirrors [VenueTagScan]'s gate so the two surfaces agree. */
    private val IDENTIFIER_NAME = Regex("(ID|Id|Ref|Reference|Handle|Key)$")

    /**
     * Proposals for [selection], most credible first. Empty when there is nothing to say — a single-message
     * capture can produce nothing here by construction, since an echo needs two messages.
     */
    fun detect(selection: List<ScenarioCapture.Candidate>, dictionary: FixDictionaryAdapter?): List<Proposal> {
        data class Sighting(val index: Int, val outgoing: Boolean, val tag: Int)

        val sightings = mutableMapOf<String, MutableList<Sighting>>()
        val tagsPerValuePerMessage = mutableMapOf<Pair<Int, String>, MutableSet<Int>>()

        selection.forEachIndexed { index, candidate ->
            if (candidate.direction == null) return@forEachIndexed
            for ((tag, value) in candidate.fields) {
                tagsPerValuePerMessage.getOrPut(index to value) { mutableSetOf() } += tag
                if (!eligible(tag, value, dictionary)) continue
                sightings.getOrPut(value) { mutableListOf() } += Sighting(index, candidate.outgoing, tag)
            }
        }

        val taken = mutableSetOf<String>()
        val proposals = mutableListOf<Proposal>()
        for ((value, seen) in sightings) {
            val out = seen.filter { it.outgoing }
            val inn = seen.filterNot { it.outgoing }
            if (out.isEmpty() || inn.isEmpty()) continue // no echo, no evidence
            // A value repeated across many tags of one message is an account or a desk, not a correlation.
            if (seen.any { tagsPerValuePerMessage[it.index to value].orEmpty().size >= CONSTANT_TAG_COUNT }) continue

            val first = seen.minByOrNull { it.index } ?: continue
            val kind = if (first.outgoing) Kind.MINT else Kind.CAPTURE
            val later = (if (first.outgoing) inn else out).filter { it.index > first.index }
            if (later.isEmpty()) continue // seen both ways inside one message: not a round trip

            val tags = (listOf(first.tag) + later.map { it.tag }).distinct().sorted()
            val name = mintName(first.tag, dictionary?.getFieldName(first.tag), taken)
            taken += name
            val other = later.minByOrNull { it.index } ?: continue
            proposals +=
                Proposal(
                    kind = kind,
                    tags = tags,
                    role = if (kind == Kind.MINT) TagRole.CLIENT_MINTED_ID else TagRole.VENUE_MINTED_ID,
                    value = value,
                    suggestedName = name,
                    evidence = evidence(kind, selection, first.index, first.tag, other.index, other.tag, value),
                )
        }
        // Named-and-id-shaped first: the ones an author confirms at a glance, before the ones they must
        // think about. Then by tag, so the list does not reshuffle between runs of the same capture.
        return proposals.sortedWith(
            compareBy(
                { if (idShaped(it.tags.first(), dictionary)) 0 else 1 },
                { it.tags.first() },
            ),
        )
    }

    private fun idShaped(tag: Int, dictionary: FixDictionaryAdapter?): Boolean {
        val name = dictionary?.getFieldName(tag) ?: return false
        return IDENTIFIER_NAME.containsMatchIn(name)
    }

    private fun evidence(
        kind: Kind,
        selection: List<ScenarioCapture.Candidate>,
        firstIndex: Int,
        firstTag: Int,
        laterIndex: Int,
        laterTag: Int,
        value: String,
    ): String {
        val firstType = selection.getOrNull(firstIndex)?.messageType ?: "?"
        val laterType = selection.getOrNull(laterIndex)?.messageType ?: "?"
        val sameTag = firstTag == laterTag
        val where = if (sameTag) "" else " (as $laterTag)"
        return when (kind) {
            Kind.MINT ->
                "your 35=$firstType carried $firstTag=$value, and 35=$laterType came back with it$where"
            Kind.CAPTURE ->
                "the venue's 35=$firstType supplied $firstTag=$value, and your 35=$laterType sent it back$where"
        }
    }

    /**
     * The gates, in the order that rejects most cheaply. Every one of them exists because of a specific
     * false positive; see the type and reference-data notes on [VenueTagScan].
     */
    private fun eligible(tag: Int, value: String, dictionary: FixDictionaryAdapter?): Boolean {
        if (value.length < MIN_LENGTH) return false
        if (tag == 35 || tag in ScenarioCapture.TRANSPORT_TAGS) return false
        // Already answered — by FixTool for standard FIX, or by the venue's overlay. Proposing a tag that
        // is already handled is noise that teaches the author to dismiss the list.
        if (tag in ScenarioCapture.ID_TAGS || tag in ExpectationSeeder.PRESENCE_TAGS) return false
        if (tag in ScenarioCapture.LIFETIME_TAGS) return false
        if (dictionary?.tagRoles?.declaredTags?.contains(tag) == true) return false
        if (tag in VenueTagScan.REFERENCE_DATA) return false
        // A vocabulary, not an identity: an echoed enum is a coincidence.
        if (dictionary?.hasFieldValues(tag) == true) return false
        val type = ExpectationSeeder.fieldTypeName(tag, dictionary)
        if (type != null && type in NON_IDENTIFIER_TYPES) return false
        // **The name is not a gate here, deliberately** — it only sorts the list (see [idShaped]).
        // [VenueTagScan] must lean on the name because it has nothing else; this has the echo itself, and
        // an id whose name says nothing is precisely the case the scan buries a hundred tags deep. Gating
        // on the name too would leave the two surfaces blind to exactly the same ids.
        return believableId(value)
    }

    /** Could this value be a coincidence? `1` yes, `A7F3C201881B` no. */
    private fun believableId(value: String): Boolean {
        // Identifiers do not contain whitespace. This is what keeps free text out now that the name is no
        // longer a gate: an echoed `58=Order accepted - ref 12` clears length and character-class checks
        // easily, and is not an id in any venue's dialect.
        if (value.any { it.isWhitespace() }) return false
        if (value.all { it.isDigit() } && value.length < MIN_NUMERIC_LENGTH) return false
        val classes =
            listOf(
                value.any { it.isDigit() },
                value.any { it.isLetter() },
                value.any { !it.isLetterOrDigit() },
            ).count { it }
        return classes >= 2 || value.length >= MIN_SINGLE_CLASS_LENGTH
    }
}
