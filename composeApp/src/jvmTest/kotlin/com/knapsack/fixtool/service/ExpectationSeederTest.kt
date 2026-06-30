package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import org.junit.Test
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
}
