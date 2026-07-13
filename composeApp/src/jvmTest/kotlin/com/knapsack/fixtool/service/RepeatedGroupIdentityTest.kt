package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group entries are located by identity, but an identity is not always unique: a market-data
 * snapshot sends several NoMDEntries of the same MDEntryType, two legs can share a LegSymbol.
 *
 * Identity alone cannot tell those entries apart — they collapsed onto one [GroupPath], the seeder
 * deduped the second entry away, and every assertion bound to the first entry. A scenario over a
 * two-level book asserted one level and stayed green while the other was wrong, which is the worst
 * way for a testing tool to fail. [GroupPath.occurrence] keeps them distinct.
 */
class RepeatedGroupIdentityTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    /** MarketDataSnapshotFullRefresh(W): two bids — same MDEntryType(269), different price/size. */
    private val twoLevelBook =
        listOf(
            35 to "W",
            262 to "MDREQ-1",
            55 to "EUR/USD",
            268 to "2",
            269 to "0", // NoMDEntries entry 1: delimiter — bid
            270 to "1.1010",
            271 to "1000000",
            269 to "0", // NoMDEntries entry 2: delimiter — same identity, deeper bid
            270 to "1.1005",
            271 to "5000000",
        )

    private fun raw(fields: List<Pair<Int, String>>) = fields.joinToString("|") { "${it.first}=${it.second}" } + "|"

    @Test
    fun `entries sharing an identity get distinct paths`() {
        val walked = FixStructure.walk(twoLevelBook, dictionary).filter { it.path != null }

        val prices = walked.filter { it.tag == 270 }
        assertEquals(listOf("1.1010", "1.1005"), prices.map { it.value }, "both price levels must survive the walk")
        assertEquals(GroupPath(268, 269, "0", occurrence = 0), prices[0].path)
        assertEquals(GroupPath(268, 269, "0", occurrence = 1), prices[1].path)
    }

    @Test
    fun `seeding a two-level book asserts both levels`() {
        val seeded = ExpectationSeeder.seed(twoLevelBook, dictionary)

        val prices = seeded.fields.filter { it.tag == 270 }
        assertEquals(2, prices.size, "the second price level must be asserted, not deduped away: $prices")
        assertEquals(setOf(0, 1), prices.mapNotNull { it.path?.occurrence }.toSet())
    }

    @Test
    fun `a regression in the second level is caught`() {
        val expectation = ExpectationSeeder.seed(twoLevelBook, dictionary)

        // The venue now sends a wrong size on the *second* bid; the first is untouched.
        val regressed = twoLevelBook.map { if (it.first == 271 && it.second == "5000000") 271 to "1" else it }
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(regressed), dictionary), expectation)

        val sizeResults = results.filter { it.tag == 271 }
        assertEquals(2, sizeResults.size)
        assertTrue(sizeResults.first { it.path?.occurrence == 0 }.passed, "the untouched level should still pass")
        assertFalse(
            sizeResults.first { it.path?.occurrence == 1 }.passed,
            "the wrong size on the second level must fail: $sizeResults",
        )
    }

    @Test
    fun `an unchanged book still passes`() {
        val expectation = ExpectationSeeder.seed(twoLevelBook, dictionary)
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(twoLevelBook), dictionary), expectation)

        assertTrue(results.all { it.passed }, "replaying the captured book must be green: ${results.filter { !it.passed }}")
    }

    @Test
    fun `a unique identity still carries occurrence zero, so scenario JSON is unchanged`() {
        val singleEntry = twoLevelBook.take(7) // one entry only
        val seeded = ExpectationSeeder.seed(singleEntry, dictionary)

        assertTrue(seeded.fields.filter { it.path != null }.all { it.path?.occurrence == 0 })
        val json = MatcherCodec.fieldExpectationToJson(seeded.fields.first { it.tag == 270 })
        assertFalse(
            json["path"]!!.toString().contains("occurrence"),
            "a unique identity must not add occurrence to the JSON: $json",
        )
    }

    /**
     * The spec puts the delimiter first in each entry; venues do not always oblige. A field sent
     * ahead of it used to be consumed and emitted nowhere — no assertion was ever seeded for it and
     * the dictionary lint never saw it, so a scenario replayed green while that tag went unchecked.
     */
    @Test
    fun `a field sent before its entry's delimiter still belongs to that entry`() {
        val priceBeforeType =
            listOf(
                35 to "W",
                262 to "MDREQ-1",
                55 to "EUR/USD",
                268 to "1",
                270 to "1.1010", // MDEntryPx ahead of the delimiter
                269 to "0", // NoMDEntries delimiter
                271 to "1000000",
            )

        val walked = FixStructure.walk(priceBeforeType, dictionary)
        val price = walked.single { it.tag == 270 }

        assertEquals(GroupPath(268, 269, "0", occurrence = 0), price.path, "the price belongs to the entry")

        // And it must reach the author as an assertion, which is the point.
        val seeded = ExpectationSeeder.seed(priceBeforeType, dictionary)
        assertEquals(Matcher.Numeric(1.1010, 0.0), seeded.fields.single { it.tag == 270 }.matcher)
        assertTrue(
            ExpectationEvaluator.evaluate(RawMessageView(raw(priceBeforeType), dictionary), seeded).all { it.passed },
        )
    }

    /**
     * STRICT means "no unexpected *top-level* tag". It used to compare the expectation's top-level
     * tags against every tag on the wire, so each grouped field the expectation did assert came back
     * as "strict: unexpected" — no message carrying a repeating group could ever pass, which is most
     * business messages.
     */
    @Test
    fun `STRICT passes on a message with repeating groups`() {
        val strict = ExpectationSeeder.seed(twoLevelBook, dictionary).copy(mode = MatchMode.STRICT)

        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(twoLevelBook), dictionary), strict)

        assertTrue(
            results.none { it.matcher == "strict: unexpected" },
            "group members are not extras: ${results.filter { !it.passed }}",
        )
        assertTrue(results.all { it.passed })
    }

    @Test
    fun `STRICT still catches a genuinely unexpected top-level tag`() {
        val strict = ExpectationSeeder.seed(twoLevelBook, dictionary).copy(mode = MatchMode.STRICT)

        val withExtra = twoLevelBook + (58 to "surprise") // Text(58), never asserted
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(withExtra), dictionary), strict)

        val extra = results.single { it.matcher == "strict: unexpected" }
        assertEquals(58, extra.tag)
        assertFalse(extra.passed)
    }

    @Test
    fun `matchers on the same tag under different entries are not confused`() {
        val expectation = ExpectationSeeder.seed(twoLevelBook, dictionary)

        val second = expectation.fields.single { it.tag == 270 && it.path?.occurrence == 1 }
        assertEquals(Matcher.Numeric(1.1005, 0.0), second.matcher)
    }
}
