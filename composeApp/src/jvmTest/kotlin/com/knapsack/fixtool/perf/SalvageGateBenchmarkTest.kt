package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixFields
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.compare.Salvaged
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What the salvage gate is worth.**
 *
 * [IngestParseBenchmarkTest] costed the re-parse `QuickFixService.fromApp` does on every incoming message
 * and found it the dominant per-message cost in the application. This is the fix for the largest part of
 * it, measured.
 *
 * Where the parse's time went before the change, attributed in one run whose whole parse of a 14-field
 * ExecutionReport measured 30.0µs:
 *
 * ```
 * FixFields.parse                         2.7µs
 * GroupOverlay.build + filter             7.0µs   ← "any undefined groups?", and the answer is always no
 * the rest (assembly, header split, …)      ~20µs
 * ```
 *
 * The overlay was a whole walk of the message (the dictionary traversal, the period-detection guess and
 * a label per entry with a dictionary description lookup per row), built so that everything except one
 * yes-or-no could be thrown away. `GroupOverlay.salvageable` answers the yes-or-no directly and makes the
 * walk only when it could come back yes.
 *
 * **The measurement pinned here is the lookup**, because it is the change and because its allocation
 * figure is exact. The end-to-end effect is not reconstructible inside one JVM, because the old parse no longer
 * exists to call, so it was measured by running [IngestParseBenchmarkTest] on both sides of the commit
 * (`git stash push -- composeApp/src/jvmMain`, run, pop, run), which anyone can repeat:
 *
 * ```
 *                            before                    after
 * ExecutionReport      5,552 B   38,467 ns      3,824 B   23,893 ns    1.45x less, 1.61x faster
 * MD snapshot,  25   107,940 B  172,870 ns     60,408 B   89,480 ns    1.79x less, 1.93x faster
 * MD snapshot, 100   373,391 B  333,595 ns    231,043 B  202,020 ns    1.62x less, 1.65x faster
 * MD snapshot, 250   884,408 B  772,282 ns    562,400 B  353,859 ns    1.57x less, 2.18x faster
 * ```
 *
 * That takes an ordinary message's ingest ceiling from about 26,000 to about 42,000 a second on this
 * path. The numbers above were taken in one sitting, because time on a laptop moves 15 to 25 percent run
 * to run. The allocation column is the one to trust.
 *
 * **One thing measured here did not earn its keep and is not in the code.** The gate asks
 * [FixDictionaryAdapter.isGroupTag] once per body field, which reads a field name and a declared type at
 * about 138ns, so memoising it in a `ConcurrentHashMap<Int, Boolean>` looked free. It was a regression:
 * boxing the tag and allocating `computeIfAbsent`'s lambda per call made the gate **3.2x more allocation
 * and 1.6x slower** than simply calling the function (416 B / 2,639 ns against 128 B / 1,700 ns on the
 * ExecutionReport). Left here as a warning, because the next person to look at this will have the same
 * idea.
 */
class SalvageGateBenchmarkTest {
    private val dictionary = FixDictionaryAdapter.fromResource()
    private val dataDictionary = dictionary.getDataDictionary()!!

    /** What `FixMessageHelper.salvageableGroups` did before the gate. Kept alive so the claim stays a comparison. */
    private fun throughTheWholeOverlay(
        fields: List<Pair<Int, String?>>,
        messageType: String?,
    ): List<Salvaged> =
        GroupOverlay
            .build(fields, messageType, dictionary)
            .groups
            .filter { it.source == EntrySource.HEURISTIC && it.countRow != null }
            .map { group -> Salvaged(group.groupTag, group.countRow!!, group.entries.map { it.rows }) }

    private data class Case(
        val name: String,
        val body: List<Pair<Int, String?>>,
        val msgType: String,
    )

    /**
     * The body as `toQuickFixMessageManual` builds it, header and trailer already taken out, because that
     * is the list the lookup is handed in production. Measuring the whole wire instead makes the overlay
     * look four times more expensive than it is, which flatters the fix.
     */
    private fun case(name: String, raw: String): Case {
        val fields = FixFields.parse(raw, FixFields.SOH)
        val msgType = fields.first { it.first == 35 }.second
        val headerTags = FixVersion.getHeaderTags(FixVersion.DEFAULT)
        val trailerTags = FixVersion.getTrailerTags(FixVersion.DEFAULT)

        fun isHeader(tag: Int) = tag in headerTags || dataDictionary.isHeaderField(tag)

        fun isTrailer(tag: Int) = tag in trailerTags || dataDictionary.isTrailerField(tag)

        val body = fields.filter { (tag, _) -> !isHeader(tag) && !isTrailer(tag) }
        return Case(name, body.map { it.first to it.second as String? }, msgType)
    }

    private val cases by lazy {
        listOf(
            case("ExecutionReport (~14 fields)", Corpus.rfqFlow(5).first { it.messageType == "8" }.wireRaw!!),
            case("MD snapshot,  25 entries", Corpus.marketDataSnapshot(25).wireRaw!!),
            case("MD snapshot, 100 entries", Corpus.marketDataSnapshot(100).wireRaw!!),
            case("MD snapshot, 250 entries", Corpus.marketDataSnapshot(250).wireRaw!!),
        )
    }

    @Test
    fun `deciding what to rescue, before and after the gate`() {
        val comparisons =
            cases.map { c ->
                Bench.compare(
                    "Which groups must a parse rescue: ${c.name}",
                    ops = 50,
                    before = "whole overlay, then filtered" to { throughTheWholeOverlay(c.body, c.msgType) },
                    after = "GroupOverlay.salvageable" to { GroupOverlay.salvageable(c.body, c.msgType, dictionary) },
                )
            }

        // Same answer, or the speed is worth nothing. SalvageGateEquivalenceTest is the thorough proof
        // across message shapes, and this is the guard on the very corpus being timed.
        cases.forEach { c ->
            assertEquals(
                throughTheWholeOverlay(c.body, c.msgType),
                GroupOverlay.salvageable(c.body, c.msgType, dictionary),
                "the gate must return exactly what the overlay did for ${c.name}",
            )
        }

        if (!Bench.allocationMeasurable) return
        comparisons.forEach { comparison ->
            // Measured at 13x on the ExecutionReport and 27x on the snapshots. Five is the floor that
            // still fails if anyone puts the walk back on this path.
            assertTrue(
                comparison.allocationFactor > 5.0,
                "the gate allocated only ${comparison.allocationFactor}x less than the overlay: ${comparison.title}",
            )
        }
    }
}
