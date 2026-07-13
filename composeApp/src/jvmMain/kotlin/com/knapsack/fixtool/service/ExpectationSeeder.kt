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

    fun seed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): Expectation {
        val messageType = fields.firstOrNull { it.first == 35 }?.second
        return Expectation(
            fields = seedDetailed(fields, dictionary).map { it.field },
            messageType = messageType,
            mode = MatchMode.OPEN,
        )
    }

    /**
     * Walk the captured fields in wire order, drop the envelope, seed a matcher per field. There is no
     * structure walk, because there is nothing structural left to decide.
     *
     * **Every occurrence gets its own row**, and that is the change. The old seeder de-duplicated by
     * `(tag, path)`, so a message with four party entries produced *one* PartyRole assertion: three of
     * the four entries were never checked, and the scenario looked complete. Now the *k*-th `452` row
     * asserts the *k*-th `452` in the reply, so all four are covered — and a group whose entries are
     * indistinguishable by any identity, which the old model refused to assert at all, is asserted like
     * anything else. Position is the identity, and every message has one.
     */
    fun seedDetailed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<SeededField> =
        fields
            .filterNot { it.first in OMITTED_TAGS }
            .map { (tag, value) ->
                SeededField(
                    field = FieldExpectation(tag = tag, matcher = seedMatcher(tag, value, dictionary)),
                    capturedValue = value,
                )
            }

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
