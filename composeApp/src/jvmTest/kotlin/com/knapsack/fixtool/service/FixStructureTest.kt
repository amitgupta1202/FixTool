package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural walk over nesting deeper than one level.
 *
 * It answers exactly one question now — *is this field at the top level, or inside a repeating group?*
 * — and it has exactly one caller, [DictionaryLint]. The assertion engine no longer asks it anything:
 * it asserts the k-th occurrence of a tag and needs no structure at all, which is what removed the
 * seam where this walker and QuickFIX/J disagreed about what a group entry contained.
 *
 * Venue dialects nest legs several levels deep — a real quote flow runs
 * NoLegs(555) > NoNestedPartyIDs(539) > NoNestedPartySubIDs(804). A walker that scans a nested group's
 * fields flat stops at the first tag only a *deeper* group's dictionary defines (545, which lives in
 * 804's scope, not 539's), unwinds to top level, and mis-classifies every field after it — which
 * surfaces to the user as a phantom "not defined for QuoteRequest (R)" warning on a message that is in
 * fact structurally valid.
 */
class FixStructureTest {
    private val dictionary: FixDictionaryAdapter = loadTestDictionary()

    private fun loadTestDictionary(): FixDictionaryAdapter {
        val resource =
            this::class.java.classLoader.getResource("test-nested-groups.xml")
                ?: error("Test data dictionary not found")
        return FixDictionaryAdapter.fromFile(File(resource.toURI()))
    }

    /** Two legs; the first carries a party nested two levels below it, then resumes with a leg field. */
    private val threeDeepQuoteRequest =
        listOf(
            35 to "R",
            131 to "QR-1",
            555 to "2",
            600 to "LEG-1", // NoLegs entry 1: delimiter
            624 to "1",
            539 to "1",
            524 to "PARTY-1", // NoNestedPartyIDs entry: delimiter
            525 to "D",
            538 to "11",
            804 to "1",
            545 to "SUB-1", // NoNestedPartySubIDs entry: delimiter — two levels below the leg
            805 to "2",
            556 to "EUR", // back out at the leg level, still LEG-1
            600 to "LEG-2", // NoLegs entry 2: delimiter
            624 to "2",
            556 to "USD",
            15 to "GBP", // back out at top level
        )

    @Test
    fun `fields of groups nested below the top group do not spill to top level`() {
        val walked = FixStructure.walk(threeDeepQuoteRequest, dictionary)
        val topLevel = walked.filter { it.groupTag == null }.map { it.tag }

        listOf(600, 624, 556, 524, 525, 538, 545, 805).forEach { tag ->
            assertTrue(tag !in topLevel, "tag $tag spilled to top level: $topLevel")
        }
        // The group counts stay top-level; 15 proves the walk resumes after the legs.
        assertEquals(listOf(35, 131, 555, 15), topLevel)
    }

    @Test
    fun `a message nesting three levels deep trips no dictionary-mismatch warning`() {
        assertEquals(emptyList(), DictionaryLint.unknownTags(threeDeepQuoteRequest, dictionary))
    }

    @Test
    fun `a field after a nested subtree still belongs to the group it was sent in`() {
        val walked = FixStructure.walk(threeDeepQuoteRequest, dictionary)

        fun groupOf(tag: Int, value: String): Int? = walked.first { it.tag == tag && it.value == value }.groupTag

        // LegCurrency follows the nested party subtree — the walk must not have unwound to top level.
        assertEquals(555, groupOf(556, "EUR"))
        assertEquals(555, groupOf(556, "USD"))
        assertEquals(555, groupOf(545, "SUB-1"))
        assertEquals(null, groupOf(15, "GBP"))
    }

    @Test
    fun `every field is walked exactly once`() {
        val walked = FixStructure.walk(threeDeepQuoteRequest, dictionary)
        assertEquals(
            threeDeepQuoteRequest.map { it.first },
            walked.map { it.tag },
            "the walk must neither drop a field nor emit one twice — the lint reads this list",
        )
    }
}
