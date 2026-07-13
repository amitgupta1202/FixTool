package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind

/**
 * Builds a first-draft [Expectation] from a captured message by **pre-seeding** each tag's
 * matcher from its dictionary field type. This is the usability lever: without it an author
 * would have to manually downgrade every timestamp / seqnum / ID from `Exact`, and the first
 * replay would fail on a dozen forgotten volatile tags. The author then only corrects the seed.
 *
 * Seeding rules, in the order they are applied:
 * - the envelope ([SessionTags.NEVER_ASSERTED])        → omitted entirely
 * - IDs and addressing ([SessionTags.VALUE_NOT_PORTABLE], OrderID 37, ExecID 17) → `Presence`
 * - `UTCTIMESTAMP` / time-ish     → `Temporal(NOW_WITHIN_TOLERANCE)`
 * - `UTCDATEONLY` / date-ish      → `Temporal(TODAY)`
 * - `PRICE`/`QTY`/`AMT`/`FLOAT`   → `Numeric(captured)` (tolerance 0 = format-robust exact)
 * - everything else               → `Exact(captured)`
 *
 * Presence is checked before the type rules, and the order carries a defect with it — see [seedMatcher].
 */
object ExpectationSeeder {
    /**
     * Never asserted: they identify the connection and the moment, not the behaviour. Seeding
     * SenderCompID(49) or BeginString(8) as Exact made a captured scenario fail on every other
     * environment. Narrower than what a Send step strips — see [SessionTags].
     */
    private val OMITTED_TAGS = SessionTags.NEVER_ASSERTED

    /**
     * Tags asserted for their presence, never their value: the venue-assigned identifiers whose value
     * is random (OrderID, ExecID) and the addressing/origin tags whose value belongs to the
     * environment rather than the behaviour (see [SessionTags.VALUE_NOT_PORTABLE]).
     */
    private val PRESENCE_TAGS = setOf(37, 17) + SessionTags.VALUE_NOT_PORTABLE // OrderID, ExecID

    /** Default tolerances per numeric family. 0 still ignores formatting (parsed as numbers). */
    private const val DEFAULT_NUMERIC_TOLERANCE = 0.0

    /** Default tolerance (seconds) for timestamp fields seeded as "now ± N". */
    private const val DEFAULT_TIME_TOLERANCE_SECONDS = 60L

    /** One seeded assertion plus the captured value it was seeded from (for editor/preview rows). */
    data class SeededField(val field: FieldExpectation, val capturedValue: String)

    /**
     * A group whose fields were **not** asserted, and why. Two entries carry the same identity, so a
     * [com.knapsack.fixtool.model.scenario.GroupPath] cannot name one of them; asserting anyway would
     * bind to whichever came first and report it as the other's. Said out loud so the author knows
     * this part of the message is not covered, instead of trusting a green run that never checked it.
     */
    data class UnassertableGroup(val groupTag: Int, val identityTag: Int, val reason: String)

    /** What a capture produced: the assertions, and the groups it declined to assert. */
    data class Seed(val fields: List<SeededField>, val unassertable: List<UnassertableGroup>)

    fun seed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): Expectation {
        val messageType = fields.firstOrNull { it.first == 35 }?.second
        return Expectation(
            fields = seedDetailed(fields, dictionary).map { it.field },
            messageType = messageType,
            mode = MatchMode.OPEN,
        )
    }

    fun seedDetailed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<SeededField> =
        seedAll(fields, dictionary).fields

    /**
     * Structure-aware seeding: repeating-group fields get a [com.knapsack.fixtool.model.scenario.GroupPath]
     * locating their entry by identity — a top-level lookup on a group-internal tag would always be
     * "absent", so seeding it flat would guarantee a false failure on replay.
     *
     * A group whose entries are not distinguishable by that identity is skipped entirely and returned
     * in [Seed.unassertable]. FixTool asserts what it can locate and names what it cannot.
     */
    fun seedAll(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): Seed {
        val structure = FixStructure.structure(fields, dictionary)
        val seen = mutableSetOf<Pair<Int, Any?>>()
        val seeded = structure.fields
            .filterNot { it.tag in OMITTED_TAGS }
            .filterNot { it.path != null && it.path.groupTag in structure.ambiguousGroups }
            .filter { seen.add(it.tag to it.path) }
            .map { sf ->
                SeededField(
                    field = FieldExpectation(tag = sf.tag, matcher = seedMatcher(sf.tag, sf.value, dictionary), path = sf.path),
                    capturedValue = sf.value,
                )
            }
        val unassertable = structure.ambiguousGroups.sorted().map { groupTag ->
            val identityTag = structure.fields.firstOrNull { it.path?.groupTag == groupTag }?.path?.identityTag ?: 0
            UnassertableGroup(
                groupTag = groupTag,
                identityTag = identityTag,
                reason = groupLabel(groupTag, dictionary) + " has entries that share " +
                    tagLabel(identityTag, dictionary) + ", so an assertion cannot say which entry it means",
            )
        }
        return Seed(seeded, unassertable)
    }

    private fun groupLabel(tag: Int, dictionary: FixDictionaryAdapter?): String = tagLabel(tag, dictionary)

    private fun tagLabel(tag: Int, dictionary: FixDictionaryAdapter?): String =
        dictionary?.getFieldName(tag)?.let { "$it($tag)" } ?: "tag $tag"

    private fun seedMatcher(tag: Int, value: String, dictionary: FixDictionaryAdapter?): Matcher {
        val fieldType = fieldTypeName(tag, dictionary)
        return when {
            // Ahead of the type rules, not after them: OrigSendingTime(122) is a UTCTIMESTAMP, so a
            // type-first walk seeded it "~now ±60s" — and a resend's OrigSendingTime is *by definition*
            // the old message's, minutes or hours in the past. Every resend scenario went red on every
            // run, on the environment it was captured on.
            tag in PRESENCE_TAGS -> Matcher.Presence
            fieldType in TIMESTAMP_TYPES ->
                Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, DEFAULT_TIME_TOLERANCE_SECONDS)
            fieldType in DATE_TYPES -> Matcher.Temporal(TemporalKind.TODAY)
            fieldType in NUMERIC_TYPES && value.toDoubleOrNull() != null ->
                Matcher.Numeric(value.toDouble(), DEFAULT_NUMERIC_TOLERANCE)
            else -> Matcher.Exact(value)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fieldTypeName(tag: Int, dictionary: FixDictionaryAdapter?): String? =
        try {
            dictionary?.getDataDictionary()?.getFieldType(tag)?.name
        } catch (e: Exception) {
            null
        }

    // QuickFIX FieldType enum names, matched as strings so we don't bind to a specific QF/J version.
    private val TIMESTAMP_TYPES = setOf("UTCTIMESTAMP", "UTCTIMEONLY", "TZTIMESTAMP", "TZTIMEONLY", "TIME")
    private val DATE_TYPES = setOf("UTCDATEONLY", "UTCDATE", "LOCALMKTDATE", "MONTHYEAR")
    private val NUMERIC_TYPES = setOf("PRICE", "QTY", "AMT", "FLOAT", "PRICEOFFSET", "PERCENTAGE")
}
