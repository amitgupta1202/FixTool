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
 * Seeding rules (see the design doc's auto-seed table):
 * - `UTCTIMESTAMP` / time-ish     → `Temporal(NOW_WITHIN_TOLERANCE)`
 * - `UTCDATEONLY` / date-ish      → `Temporal(TODAY)`
 * - `PRICE`/`QTY`/`AMT`/`FLOAT`   → `Numeric(captured)` (tolerance 0 = format-robust exact)
 * - header volatiles 9,10,34,52   → omitted
 * - well-known IDs (OrderID 37, ExecID 17) → `Presence`
 * - everything else               → `Exact(captured)`
 */
object ExpectationSeeder {
    /** Header volatiles that should never be asserted; omitted from the seed entirely. */
    private val OMITTED_TAGS = setOf(9, 10, 34, 52)

    /** Well-known venue-assigned identifiers whose presence matters but whose value is random. */
    private val PRESENCE_TAGS = setOf(37, 17) // OrderID, ExecID

    /** Default tolerances per numeric family. 0 still ignores formatting (parsed as numbers). */
    private const val DEFAULT_NUMERIC_TOLERANCE = 0.0

    /** Default tolerance (seconds) for timestamp fields seeded as "now ± N". */
    private const val DEFAULT_TIME_TOLERANCE_SECONDS = 60L

    fun seed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): Expectation {
        val messageType = fields.firstOrNull { it.first == 35 }?.second
        val seeded =
            fields
                .filterNot { it.first in OMITTED_TAGS }
                .distinctBy { it.first }
                .map { (tag, value) -> FieldExpectation(tag = tag, matcher = seedMatcher(tag, value, dictionary)) }
        return Expectation(fields = seeded, messageType = messageType, mode = MatchMode.OPEN)
    }

    private fun seedMatcher(tag: Int, value: String, dictionary: FixDictionaryAdapter?): Matcher {
        val fieldType = fieldTypeName(tag, dictionary)
        return when {
            fieldType in TIMESTAMP_TYPES ->
                Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, DEFAULT_TIME_TOLERANCE_SECONDS)
            fieldType in DATE_TYPES -> Matcher.Temporal(TemporalKind.TODAY)
            fieldType in NUMERIC_TYPES && value.toDoubleOrNull() != null ->
                Matcher.Numeric(value.toDouble(), DEFAULT_NUMERIC_TOLERANCE)
            tag in PRESENCE_TAGS -> Matcher.Presence
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
