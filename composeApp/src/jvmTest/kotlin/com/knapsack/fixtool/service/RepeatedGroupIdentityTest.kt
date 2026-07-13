package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A group entry is located by identity — the value of its delimiter — and that only works while the
 * identity is unique. A market-data snapshot sends several MDEntries of the same MDEntryType; two
 * legs can share a LegSymbol. Nothing in the path tells those entries apart.
 *
 * FixTool does not guess. The seeder refuses to assert such a group and names it, and the evaluator
 * fails an assertion it cannot resolve to exactly one entry. The failure mode being avoided is the
 * one that matters most for a testing tool: a scenario that asserts the wrong entry, passes, and is
 * believed.
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
            269 to "0", // NoMDEntries entry 1: delimiter
            270 to "1.1010",
            271 to "1000000",
            269 to "0", // NoMDEntries entry 2: same identity — indistinguishable
            270 to "1.1005",
            271 to "5000000",
        )

    /** The same book, but each entry is identifiable: bid and offer. */
    private val distinguishableBook =
        listOf(
            35 to "W",
            262 to "MDREQ-1",
            55 to "EUR/USD",
            268 to "2",
            269 to "0", // bid
            270 to "1.1010",
            271 to "1000000",
            269 to "1", // offer
            270 to "1.1015",
            271 to "2000000",
        )

    private fun raw(fields: List<Pair<Int, String>>) = fields.joinToString("|") { "${it.first}=${it.second}" } + "|"

    @Test
    fun `a group whose entries share an identity is reported, not asserted`() {
        val seed = ExpectationSeeder.seedAll(twoLevelBook, dictionary)

        assertTrue(
            seed.fields.none { it.field.path?.groupTag == 268 },
            "no entry of an ambiguous group may be asserted: ${seed.fields.filter { it.field.path != null }}",
        )
        val refused = seed.unassertable.single()
        assertEquals(268, refused.groupTag)
        assertEquals(269, refused.identityTag)
        assertTrue(refused.reason.contains("MDEntryType"), "the reason should name the identity: ${refused.reason}")

        // The rest of the message is still captured as normal.
        assertTrue(seed.fields.any { it.field.tag == 55 })
    }

    @Test
    fun `a group with distinguishable entries is asserted, entry by entry`() {
        val seed = ExpectationSeeder.seedAll(distinguishableBook, dictionary)

        assertTrue(seed.unassertable.isEmpty())
        val prices = seed.fields.filter { it.field.tag == 270 }
        assertEquals(2, prices.size)
        assertEquals(
            setOf(GroupPath(268, 269, "0"), GroupPath(268, 269, "1")),
            prices.mapNotNull { it.field.path }.toSet(),
        )
    }

    @Test
    fun `a regression in one entry of a distinguishable group is caught`() {
        val expectation = ExpectationSeeder.seed(distinguishableBook, dictionary)

        val regressed = distinguishableBook.map { if (it.first == 271 && it.second == "2000000") 271 to "1" else it }
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(regressed), dictionary), expectation)

        val offerSize = results.single { it.tag == 271 && it.path?.identityValue == "1" }
        assertFalse(offerSize.passed, "the offer's wrong size must fail")
        assertTrue(results.single { it.tag == 271 && it.path?.identityValue == "0" }.passed, "the bid is untouched")
    }

    @Test
    fun `an unchanged book still passes`() {
        val expectation = ExpectationSeeder.seed(distinguishableBook, dictionary)
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(distinguishableBook), dictionary), expectation)

        assertTrue(results.all { it.passed }, "replaying the captured book must be green: ${results.filter { !it.passed }}")
    }

    /**
     * A message that was unambiguous when captured can arrive with repeated identities later. The
     * assertion must say it cannot resolve the entry, not silently bind to the first one and report
     * that entry's value as though it were the other's.
     */
    @Test
    fun `an assertion that resolves to several entries fails as ambiguous`() {
        val expectation =
            Expectation(
                fields = listOf(FieldExpectation(270, Matcher.Numeric(1.1010, 0.0), GroupPath(268, 269, "0"))),
                messageType = "W",
            )

        val result = ExpectationEvaluator.evaluate(RawMessageView(raw(twoLevelBook), dictionary), expectation).single()

        assertFalse(result.passed, "an entry we cannot name is not an entry we can assert")
        assertTrue(result.actual!!.contains("ambiguous"), "say why: ${result.actual}")
        assertTrue(result.actual!!.contains("2 entries"), "say how many: ${result.actual}")
    }

    /**
     * The spec puts the delimiter first in an entry; venues do not always oblige. A field sent ahead
     * of the delimiter belongs to the entry that delimiter opens — including for the second and later
     * entries, where folding it into the previous entry would duplicate a tag there and leave the new
     * entry short of one.
     */
    @Test
    fun `fields sent before each entry's delimiter belong to that entry`() {
        val priceFirst =
            listOf(
                35 to "W",
                262 to "MDREQ-1",
                55 to "EUR/USD",
                268 to "2",
                270 to "1.1010", // MDEntryPx ahead of entry 1's delimiter
                269 to "0", // bid
                271 to "1000000",
                270 to "1.1015", // MDEntryPx ahead of entry 2's delimiter
                269 to "1", // offer
                271 to "2000000",
            )

        val walked = FixStructure.walk(priceFirst, dictionary).filter { it.tag == 270 }

        assertEquals(2, walked.size)
        assertEquals(GroupPath(268, 269, "0"), walked[0].path, "the first price belongs to the bid")
        assertEquals(GroupPath(268, 269, "1"), walked[1].path, "the second price belongs to the offer")

        // And it round-trips: the captured expectation passes against the message it was seeded from.
        val expectation = ExpectationSeeder.seed(priceFirst, dictionary)
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(priceFirst), dictionary), expectation)
        assertTrue(results.all { it.passed }, "failed: ${results.filter { !it.passed }}")
    }

    @Test
    fun `STRICT passes on a message with repeating groups`() {
        val strict = ExpectationSeeder.seed(distinguishableBook, dictionary).copy(mode = MatchMode.STRICT)

        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(distinguishableBook), dictionary), strict)

        assertTrue(results.none { it.matcher == "strict: unexpected" }, "group members are not extras")
        assertTrue(results.all { it.passed })
    }

    /**
     * A venue's new field lands wherever its dictionary puts it. MDEntryDate(272) is a member of
     * NoMDEntries, so it is absorbed into an entry rather than appearing at top level — checking only
     * the top level would let exactly the change STRICT exists to catch go by unnoticed.
     */
    @Test
    fun `STRICT catches a tag the venue added inside a group entry`() {
        val strict = ExpectationSeeder.seed(distinguishableBook, dictionary).copy(mode = MatchMode.STRICT)

        val venueAddedAField =
            distinguishableBook.toMutableList().apply { add(7, 272 to "20260713") } // MDEntryDate, inside the bid
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(venueAddedAField), dictionary), strict)

        val extra = results.single { it.matcher.startsWith("strict: unexpected") }
        assertEquals(272, extra.tag)
        assertEquals(GroupPath(268, 269, "0"), extra.path, "and say which entry it turned up in")
        assertFalse(extra.passed)
    }

    @Test
    fun `STRICT still catches an unexpected top-level tag`() {
        val strict = ExpectationSeeder.seed(distinguishableBook, dictionary).copy(mode = MatchMode.STRICT)

        val withExtra = distinguishableBook + (58 to "surprise") // Text(58), never asserted
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw(withExtra), dictionary), strict)

        val extra = results.single { it.matcher == "strict: unexpected" }
        assertEquals(58, extra.tag)
        assertFalse(extra.passed)
    }
}
