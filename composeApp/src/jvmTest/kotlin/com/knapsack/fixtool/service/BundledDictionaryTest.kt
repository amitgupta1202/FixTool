package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The bundled dictionaries are the real ones.**
 *
 * FIX 4.4 is the product default (`FixVersion.DEFAULT`, `AppSettings.defaultFixVersion`), and for a
 * while what shipped under that name was a 93-field demo asset written for the FX demo server. Nothing
 * failed loudly. What happened instead: `isTimestamp` asks the dictionary about every tag but 60, so 18
 * standard UTCTIMESTAMP fields replayed the captured moment; the seeder's type rule could not see them
 * either, so they seeded `Exact` and were red for ever; and `identifierFamily` admits "tags the
 * dictionary does not know at all", so the fix plan then offered to demote those red rows to `Presence`
 * — turning a blind dictionary into permanently lost coverage by way of a repair that reads reasonably.
 *
 * These assertions are the guard. They are deliberately about *counts and types*, not about a file, so
 * any future substitution — a trimmed copy, a venue's own — is caught by what it costs rather than by
 * its name.
 */
class BundledDictionaryTest {
    /**
     * Standard FIX 4.4 UTCTIMESTAMP fields beyond TransactTime(60), which capture hardcodes. Each one is
     * a send that replays stale and an expectation that is red for ever when the dictionary cannot type it.
     */
    private val timestampTags =
        listOf(
            42, // OrigTime
            122, // OrigSendingTime
            126, // ExpireTime
            168, // EffectiveTime
            341, // TradSesStartTime
            342, // TradSesOpenTime
            343, // TradSesPreCloseTime
            344, // TradSesCloseTime
            345, // TradSesEndTime
            367, // QuoteSetValidUntilTime
            438, // ContraTradeTime
            443, // StrikeTime
            483, // TransBkdTime
            515, // ExecValuationPoint
            586, // OrigOrdModTime
            629, // HopSendingTime
            769, // TrdRegTimestamp
            779, // LastUpdateTime
        )

    /** The correlation ids capture mints scenario variables for; the name is the variable's name. */
    private val idTagNames =
        mapOf(
            11 to "ClOrdID",
            41 to "OrigClOrdID",
            66 to "ListID",
            70 to "AllocID",
            117 to "QuoteID",
            131 to "QuoteReqID",
            262 to "MDReqID",
            320 to "SecurityReqID",
            335 to "TradSesReqID",
            526 to "SecondaryClOrdID",
            571 to "TradeReportID",
            583 to "ClOrdLinkID",
            693 to "QuoteRespID",
        )

    private val fix44 = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    @Test
    fun `the default dictionary is a whole FIX 4_4, not a subset`() {
        // Real FIX 4.4 is 916 fields. The demo stub that used to ship here was 93 — any floor between the
        // two catches the substitution, and this one leaves room for a legitimately revised dictionary.
        assertTrue(
            fix44.getAllFields().size > 900,
            "bundled FIX 4.4 has ${fix44.getAllFields().size} fields — a trimmed dictionary makes capture " +
                "classify standard fields blind",
        )
    }

    @Test
    fun `every standard UTCTIMESTAMP is typed, so it is parameterized rather than replayed stale`() {
        val untyped = timestampTags.filter { fix44.getDataDictionary()?.getFieldType(it)?.name != "UTCTIMESTAMP" }
        assertEquals(emptyList(), untyped, "untyped timestamp tags replay the captured moment and seed Exact")
    }

    @Test
    fun `every correlation id is named, so its scenario variable is not tag$number`() {
        val unnamed = idTagNames.filter { (tag, name) -> fix44.getFieldName(tag) != name }
        assertEquals(emptyMap(), unnamed, "an unnamed ID tag mints \${tagNNN} into the saved scenario, for ever")
    }

    /**
     * The classifier the fix plan consults. Its "the dictionary does not know this tag" clause is meant for
     * a venue's custom fields; a blind dictionary turns it on for standard FIX, and the plan starts offering
     * to stop asserting them. Pinned on a timestamp because that is the case that was actually mis-offered.
     */
    @Test
    fun `a standard timestamp is not mistaken for a per-run identifier`() {
        assertTrue(
            !ExpectationSeeder.identifierFamily(769, fix44),
            "TrdRegTimestamp(769) reads as an identifier — the fix plan will offer to demote it to Presence",
        )
    }
}
