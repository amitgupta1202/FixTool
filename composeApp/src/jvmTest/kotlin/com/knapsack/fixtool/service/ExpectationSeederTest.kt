package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the dictionary-driven auto-seeding: each field type maps to the right default matcher,
 * header volatiles are dropped, and well-known IDs become presence checks.
 */
class ExpectationSeederTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private val fields =
        listOf(
            35 to "8", // MsgType (STRING)  -> Exact
            52 to "20260630-09:15:02.000", // SendingTime (header volatile) -> omitted
            10 to "077", // CheckSum (header volatile) -> omitted
            60 to "20260630-09:15:02.000", // TransactTime (UTCTIMESTAMP) -> Temporal
            31 to "1.2345", // LastPx (PRICE) -> Numeric
            32 to "100", // LastQty (QTY) -> Numeric
            37 to "OID-1", // OrderID -> Presence
            11 to "ORD-1", // ClOrdID (STRING) -> Exact
        )

    @Test
    fun `seeds matchers from field types and drops volatiles`() {
        val expectation = ExpectationSeeder.seed(fields, dictionary)
        assertEquals("8", expectation.messageType)

        val byTag = expectation.fields.associateBy { it.tag }
        // Header volatiles are not seeded at all.
        assertNull(byTag[52], "SendingTime should be omitted")
        assertNull(byTag[10], "CheckSum should be omitted")

        assertTrue(byTag[35]!!.matcher is Matcher.Exact)
        assertEquals("8", (byTag[35]!!.matcher as Matcher.Exact).value)

        val temporal = byTag[60]!!.matcher
        assertTrue(temporal is Matcher.Temporal, "TransactTime should seed Temporal, got $temporal")
        assertEquals(TemporalKind.NOW_WITHIN_TOLERANCE, (temporal as Matcher.Temporal).kind)

        val price = byTag[31]!!.matcher
        assertTrue(price is Matcher.Numeric, "LastPx should seed Numeric, got $price")
        assertEquals(1.2345, (price as Matcher.Numeric).expected, 1e-9)

        assertTrue(byTag[32]!!.matcher is Matcher.Numeric, "LastQty should seed Numeric")
        assertTrue(byTag[37]!!.matcher is Matcher.Presence, "OrderID should seed Presence")
        assertTrue(byTag[11]!!.matcher is Matcher.Exact, "ClOrdID should seed Exact")
    }

    // A FIX44 QuoteRequest with a two-entry NoRelatedSym(146) repeating group.
    private val groupedQuoteRequest =
        listOf(
            35 to "R",
            131 to "QR-1",
            146 to "2",
            55 to "EUR/USD", // entry 1 (delimiter Symbol 55)
            54 to "1",
            38 to "1000000",
            55 to "GBP/USD", // entry 2
            54 to "2",
            38 to "2000000",
        )

    @Test
    fun `the seed is the captured fields in wire order, one row per occurrence`() {
        val seeded = ExpectationSeeder.seedDetailed(groupedQuoteRequest, dictionary)

        // No structure walk, no de-duplication: the rows *are* the message, envelope aside.
        assertEquals(
            groupedQuoteRequest.map { it.first },
            seeded.map { it.field.tag },
            "the seeded rows must mirror the wire, in order — their order is what addresses them",
        )

        // Both entries seeded. The old seeder de-duplicated by (tag, path) and produced ONE Side
        // assertion for a two-entry group: the second entry went unchecked, and the scenario looked
        // complete while covering half of it.
        val sides = seeded.filter { it.field.tag == 54 }
        assertEquals(2, sides.size, "one Side assertion per entry")
        assertEquals(listOf("1", "2"), sides.map { it.capturedValue })
    }

    @Test
    fun `a grouped golden evaluates green against itself (capture-replay consistency)`() {
        val raw = groupedQuoteRequest.joinToString("|", postfix = "|") { "${it.first}=${it.second}" }
        val expectation = ExpectationSeeder.seed(groupedQuoteRequest, dictionary)

        val results = ExpectationEvaluator.evaluate(RawMessageView(raw), expectation)

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.passed }, "self-evaluation must be all green: ${results.filterNot { it.passed }}")
    }

    @Test
    fun `a group whose entries share a delimiter value is asserted like any other`() {
        // Two entries, same Symbol, opposite sides — the shape that made the old model give up:
        // the identity "the entry whose Symbol is EUR/USD" names both of them. Position names one.
        val twoSided =
            listOf(
                35 to "R", 131 to "QR-2", 146 to "2",
                55 to "EUR/USD", 54 to "1", 38 to "1000000",
                55 to "EUR/USD", 54 to "2", 38 to "2000000",
            )
        val raw = twoSided.joinToString("|", postfix = "|") { "${it.first}=${it.second}" }

        val expectation = ExpectationSeeder.seed(twoSided, dictionary)
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw), expectation)

        assertEquals(listOf("1", "2"), results.filter { it.tag == 54 }.map { it.actual })
        assertTrue(results.all { it.passed }, "an ambiguous-identity group is no longer unassertable")
    }

    @Test
    fun `a LOCALMKTDATE business date seeds Exact and stays green against its own capture on a later day`() {
        // SettlDate(64) is LOCALMKTDATE — a fixed T+n settlement date, not "today". Seeded Temporal(TODAY)
        // it was red on its own captured value every day but the capture day, and the only temporal repairs
        // are Loosen/Drop, so coverage silently eroded. It must seed Exact(value) — the value the venue echoes.
        val settlDate = "20260101"
        val captured = listOf(35 to "8", 64 to settlDate)
        val seeded = ExpectationSeeder.seedDetailed(captured, dictionary)

        val matcher = seeded.first { it.field.tag == 64 }.field.matcher
        assertTrue(matcher is Matcher.Exact, "SettlDate(64) LOCALMKTDATE must seed Exact, got $matcher")
        assertEquals(settlDate, (matcher as Matcher.Exact).value)

        // The invariant the bug broke: a freshly-captured golden must evaluate green against itself — even
        // when 'now' is a different day than the settlement date. Inject 'now' so the check is deterministic.
        val raw = captured.joinToString("|", postfix = "|") { "${it.first}=${it.second}" }
        val expectation = ExpectationSeeder.seed(captured, dictionary)
        val aDifferentDay = Instant.parse("2026-07-15T09:15:02Z")
        val results = ExpectationEvaluator.evaluate(RawMessageView(raw), expectation, now = { aDifferentDay })

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.passed }, "must be green against itself: ${results.filterNot { it.passed }}")
    }

    @Test
    fun `a UTCDATEONLY field still seeds Temporal TODAY (regression guard)`() {
        // MDEntryDate(272) is UTCDATEONLY — it genuinely means the current UTC date on a live snapshot, so
        // Exact would wrongly break it on daily replay. The narrowing of DATE_TYPES must leave this intact.
        val seeded = ExpectationSeeder.seedDetailed(listOf(272 to "20260630"), dictionary)

        val matcher = seeded.first { it.field.tag == 272 }.field.matcher
        assertTrue(matcher is Matcher.Temporal, "MDEntryDate(272) UTCDATEONLY should still seed Temporal, got $matcher")
        assertEquals(TemporalKind.TODAY, (matcher as Matcher.Temporal).kind)
    }

    /**
     * FixTool is generic: RFQ, market data and post-trade venues mint their own ids exactly the way an
     * order venue mints OrderID/ExecID. Seeded Exact, every captured scenario in those flows failed its
     * own first replay — deterministically, because a fresh QuoteID per Quote is what a quoting venue is.
     */
    @Test
    fun `venue-assigned ids across RFQ, MD and post-trade seed Presence, not Exact`() {
        val seeded =
            ExpectationSeeder.seedDetailed(
                listOf(
                    117 to "Q-778201", // QuoteID — fresh per Quote
                    278 to "MDE-1", // MDEntryID — fresh per snapshot
                    19 to "EXEC-PREV", // ExecRefID — the venue's own prior ExecID
                    198 to "SO-4", // SecondaryOrderID
                    527 to "SE-9", // SecondaryExecID
                    880 to "MATCH-1", // TrdMatchID
                    1003 to "TRD-55", // TradeID
                ),
                dictionary,
            )

        seeded.forEach { sf ->
            assertTrue(
                sf.field.matcher is Matcher.Presence,
                "tag ${sf.field.tag} is venue-assigned and must seed Presence, got ${sf.field.matcher}",
            )
        }
    }

    /**
     * The trade capture family, which used to be classified from one side only.
     *
     * `TradeReportID(571)` belongs to whoever *submits* the report, so a report the venue publishes
     * carries the venue's — and listed as client-minted alone it fell through to `Exact`, asserting an id
     * that is new every run. `SecondaryTradeReportID(818)` needs no such resolution: `Secondary<X>ID` is
     * assigned by the party that accepts, which is why `SecondaryOrderID(198)` and `SecondaryExecID(527)`
     * were already venue-assigned and 818 was the family's missing member.
     */
    @Test
    fun `trade capture report ids seed Presence, not the id the venue minted this time`() {
        val seeded =
            ExpectationSeeder.seedDetailed(
                listOf(
                    571 to "TCR-88213", // TradeReportID on a venue-published report
                    818 to "VEN-TCR-4", // SecondaryTradeReportID — assigned by the accepting party
                ),
                dictionary,
            )

        seeded.forEach { sf ->
            assertTrue(
                sf.field.matcher is Matcher.Presence,
                "tag ${sf.field.tag} is minted by the reporting venue and must seed Presence, got ${sf.field.matcher}",
            )
        }
    }

    /**
     * A quote/order lifetime is deliberately NOT ~now — Temporal reds any quote living longer than the
     * tolerance, Exact reds everything for ever. Presence: that the venue says how long it is good for
     * is the behaviour; the stamp itself belongs to the venue's clock and this moment.
     */
    @Test
    fun `ValidUntilTime and ExpireTime seed Presence, not Temporal`() {
        val seeded =
            ExpectationSeeder.seedDetailed(
                listOf(
                    62 to "20260716-10:10:00.000", // ValidUntilTime
                    126 to "20260716-16:30:00.000", // ExpireTime
                ),
                dictionary,
            )

        seeded.forEach { sf ->
            assertTrue(
                sf.field.matcher is Matcher.Presence,
                "tag ${sf.field.tag} is a lifetime stamp and must seed Presence, got ${sf.field.matcher}",
            )
        }
    }
}
