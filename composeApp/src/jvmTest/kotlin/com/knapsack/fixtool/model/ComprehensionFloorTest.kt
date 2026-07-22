package com.knapsack.fixtool.model

import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.ScenarioCapture
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A venue dictionary says WHICH fields exist. It does not say what `AvgPx` means.**
 *
 * A dictionary does two jobs. *Conformance* — what will this counterparty accept — only the loaded
 * file can answer, and validation keeps asking it. *Comprehension* — is this field a price, a
 * timestamp, an enum — standard FIX has answered for twenty years.
 *
 * Both used to go to the loaded file, so a venue subset (which is correct: it lists only what that
 * venue speaks) silently un-defined standard fields. Capture read "no type" as "untyped string" and
 * seeded `Exact` on a price; the row went permanently red; and the fix plan offered *loosen to
 * presence* while refusing *widen the tolerance*, because `numericFamily` needs a type it no longer
 * had. Coverage removed, wearing the costume of a repair. This test is what keeps that fixed.
 */
class ComprehensionFloorTest {
    private val subset: FixDictionaryAdapter =
        FixDictionaryAdapter.fromFile(
            File(
                (
                    this::class.java.classLoader.getResource("test-venue-subset.xml")
                        ?: error("test-venue-subset.xml not found")
                ).toURI(),
            ),
        )

    /** The field the venue dictionary omits — and the one right beside it that it declares. */
    @Test
    fun `a standard field the venue omits keeps its standard type`() {
        assertTrue(subset.declaresField(31), "LastPx is in the venue file")
        assertTrue(!subset.declaresField(6), "AvgPx is not — that is the case under test")

        assertEquals("PRICE", subset.fieldType(31))
        assertEquals("PRICE", subset.fieldType(6), "standard FIX types it, so FixTool must too")
        assertEquals("AvgPx", subset.getFieldName(6))
    }

    /** The consequence that mattered: the seeder now bounds the price instead of pinning its text. */
    @Test
    fun `the omitted price seeds numeric, not exact`() {
        val seeded = ExpectationSeeder.seedDetailed(listOf(6 to "1.08965"), subset).single().field
        assertTrue(seeded.matcher is Matcher.Numeric, "was ${seeded.matcher}")
        assertTrue(ExpectationSeeder.numericFamily(6, subset), "and the fix plan may offer a tolerance")
    }

    /** Enum values too — a picker over a field the venue omits is not an empty control. */
    @Test
    fun `enum values fall through to standard`() {
        assertTrue(subset.getFieldEnumValues(39).isNotEmpty(), "OrdStatus values come from standard")
        assertTrue(subset.declaredEnumValues(39).isEmpty(), "but the venue declared none — lint can tell")
    }

    /** Conformance is untouched: the loaded file still decides what is acceptable. */
    @Test
    fun `the conformance dictionary is not widened`() {
        assertTrue(subset.getDataDictionary()!!.isMsgField("8", 31), "declared by the venue")
        assertTrue(!subset.getDataDictionary()!!.isMsgField("8", 6), "still not acceptable to this venue")
    }

    /**
     * **The capture warning got narrower for free, and that is the point.**
     *
     * It warned about every tag the loaded file could not name — which, against a venue subset, meant
     * standard fields. That warning was *true* before the floor (the type really was unknown) and is
     * false after it, so leaving it firing would train an author to ignore the one notification that
     * still matters: a genuinely proprietary tag nobody has declared.
     */
    @Test
    fun `capture no longer calls a standard field unclassifiable`() {
        val candidates = ScenarioCapture.fromPaste("35=8|31=1.08|6=1.08|20777=X|", "S").candidates
        val unclassified = ScenarioCapture.unclassifiedTags(candidates, subset)

        assertTrue(6 !in unclassified, "AvgPx is standard — the floor classifies it")
        assertTrue(31 !in unclassified, "and LastPx the venue declares itself")
        assertTrue(20777 in unclassified, "a proprietary tag nobody declared is still the real warning")
    }

    /** No dictionary at all stays a distinct state — it must not silently answer as FIX 4.4. */
    @Test
    fun `an unloaded dictionary gains no floor`() {
        val none = FixDictionaryAdapter.createDefault()
        assertTrue(!none.isLoaded())
        assertNull(none.fieldType(6))
        assertNull(none.getFieldName(6))
    }
}
