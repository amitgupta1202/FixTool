package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Load-for-edit data path (R3): opening a saved scenario rebuilds editable drafts that preserve the
 * user's relaxed matchers and restore each tag's captured value (from the stored golden) for preview.
 */
class ScenarioEditTest {
    @Test
    fun `fromExpectation preserves relaxed matchers and restores golden values`() {
        val golden = "8=FIX.4.4|35=8|11=ORD-1|31=1.2345|39=2|10=000|"
        val expectation =
            Expectation(
                fields = listOf(
                    FieldExpectation(11, Matcher.Reference("\${id0}")),
                    FieldExpectation(31, Matcher.Presence), // user relaxed LastPx from numeric -> presence
                    FieldExpectation(39, Matcher.Exact("2")),
                ),
                messageType = "8",
                golden = golden,
            )

        val drafts = ExpectationDrafts.fromExpectation(expectation, dictionary = null).associateBy { it.tag }

        // Relaxed matcher is preserved, not re-seeded.
        assertEquals(Matcher.Presence, drafts[31]!!.matcher)
        // The captured value is restored from the golden so the editor can show a live preview.
        assertEquals("1.2345", drafts[31]!!.value)
        // Reference correlation survives the round-trip.
        assertEquals(Matcher.Reference("\${id0}"), drafts[11]!!.matcher)
        assertEquals("ORD-1", drafts[11]!!.value)
    }

    @Test
    fun `an unticked tag reappears unticked instead of vanishing (no one-way door)`() {
        val golden = "8=FIX.4.4|35=8|11=ORD-1|31=1.2345|39=2|10=000|"
        // The author previously unticked 31 (LastPx): it is absent from the saved fields.
        val expectation = Expectation(
            fields = listOf(FieldExpectation(11, Matcher.Exact("ORD-1")), FieldExpectation(39, Matcher.Exact("2"))),
            messageType = "8",
            golden = golden,
        )

        val drafts = ExpectationDrafts.fromExpectation(expectation, dictionary = null).associateBy { it.tag }

        val lastPx = drafts[31]
        kotlin.test.assertTrue(lastPx != null, "the unticked golden tag must still be a row")
        kotlin.test.assertFalse(lastPx!!.included, "…shown unticked")
        assertEquals("1.2345", lastPx.value, "…with its captured value, ready to re-include")
        kotlin.test.assertTrue(drafts[11]!!.included)
        kotlin.test.assertTrue(drafts[39]!!.included)
    }
}
