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
    /**
     * Session/transport tags are never asserted: they identify the connection, not the behaviour.
     * Seeding SenderCompID(49) or BeginString(8) as Exact made a captured scenario fail on every
     * other environment — the same list capture already strips from Send steps ([SessionTags]).
     */
    private val OMITTED_TAGS = SessionTags.TRANSPORT

    /** Well-known venue-assigned identifiers whose presence matters but whose value is random. */
    private val PRESENCE_TAGS = setOf(37, 17) // OrderID, ExecID

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
     * Structure-aware seeding: repeating-group fields get a [com.knapsack.fixtool.model.scenario.GroupPath]
     * locating their entry by identity — a top-level lookup on a group-internal tag would always be
     * "absent", so seeding it flat would guarantee a false failure on replay.
     */
    fun seedDetailed(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<SeededField> {
        val seen = mutableSetOf<Pair<Int, Any?>>()
        return FixStructure.walk(fields, dictionary)
            .filterNot { it.tag in OMITTED_TAGS }
            .filter { seen.add(it.tag to it.path) }
            .map { sf ->
                SeededField(
                    field = FieldExpectation(tag = sf.tag, matcher = seedMatcher(sf.tag, sf.value, dictionary), path = sf.path),
                    capturedValue = sf.value,
                )
            }
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
