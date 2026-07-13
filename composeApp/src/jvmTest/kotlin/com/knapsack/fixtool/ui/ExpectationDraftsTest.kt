package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The builder's half of the sequence model, where a false green is authored rather than evaluated.
 *
 * A row's position among its same-tag siblings **is** its occurrence: the second `452` row asserts the
 * second `452`. So the builder can silently re-aim an assertion in two ways the engine cannot, and both
 * are pinned here — dropping an earlier occurrence promotes the survivor, and reordering the rows on
 * save rewrites what they refer to.
 */
class ExpectationDraftsTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    /** Two party entries — the same firm, two roles. Both carry 448=FIRMA; only position tells them apart. */
    private val golden =
        "35=8|11=ORD-1|453=2|448=FIRMA|447=D|452=1|448=FIRMA|447=D|452=4|"

    private fun draftsFor(vararg fields: FieldExpectation) =
        ExpectationDrafts.fromExpectation(
            Expectation(fields.toList(), messageType = "8", golden = golden),
            dictionary,
        )

    @Test
    fun `every occurrence of a repeated tag gets its own row, numbered`() {
        val drafts = draftsFor()

        val roles = drafts.filter { it.tag == 452 }
        assertEquals(2, roles.size, "two party entries, two PartyRole rows")
        assertEquals(listOf(0, 1), roles.map { it.occurrence })
        assertEquals(listOf("1", "4"), roles.map { it.value })
    }

    /**
     * The false green, from the authoring side.
     *
     * An expectation carrying a single `452` row means "the FIRST 452" — that is the whole model. So the
     * drafts must tick the **first** PartyRole row, and show the author that this is what their
     * expectation says. Ticking the second (because its captured value happens to match the matcher)
     * would show them a row labelled `#2` that in fact asserts entry #1: a lie on screen, and a scenario
     * that checks a party entry nobody chose.
     */
    @Test
    fun `a lone row for a repeated tag ticks the FIRST occurrence, whatever its value`() {
        val drafts = draftsFor(FieldExpectation(452, Matcher.Exact("4")))

        val roles = drafts.filter { it.tag == 452 }
        assertTrue(roles[0].included, "a single 452 row asserts the first 452 — the tick belongs there")
        assertTrue(!roles[1].included)
        // And it is honestly red: the saved matcher says 4, the first entry's role is 1.
        assertEquals(Matcher.Exact("4"), roles[0].matcher)
        assertEquals("1", roles[0].value, "the row shows the value it actually checks, not the one it hoped for")
    }

    /**
     * Reopening a step must not rewrite the assertion.
     *
     * Rows the golden cannot account for — hand-authored ones, and every `absent` row — used to be
     * appended at the end of the draft list, and the builder rebuilds `fields` in draft order. So merely
     * opening a scenario and flipping a checkbox silently moved those rows to the bottom, and under this
     * model the order is the assertion.
     */
    @Test
    fun `a row the golden cannot account for keeps its place in the order`() {
        val drafts =
            draftsFor(
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(58, Matcher.Absent), // no golden field can pair with this
                FieldExpectation(453, Matcher.Exact("2")),
            )

        val asserted = drafts.filter { it.included }.map { it.tag }
        assertEquals(
            listOf(11, 58, 453),
            asserted,
            "the absent row must stay between 11 and 453, where the author put it — not slide to the end",
        )
    }
}
