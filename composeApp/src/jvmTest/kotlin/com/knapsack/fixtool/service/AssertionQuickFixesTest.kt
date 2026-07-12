package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.GroupPath
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.AssertionQuickFixes.Edit
import com.knapsack.fixtool.service.AssertionQuickFixes.Kind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The three relax-at-point-of-failure verbs, applied to an expectation in one batch. */
class AssertionQuickFixesTest {
    private val path1 = GroupPath(453, 452, "1")
    private val path2 = GroupPath(453, 452, "2")

    private fun expectation() =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(150, Matcher.Exact("F")),
                    FieldExpectation(31, Matcher.Numeric(1.2345, 0.0)),
                    FieldExpectation(448, Matcher.Exact("BROKER-A"), path1),
                    FieldExpectation(448, Matcher.Exact("CLIENT-X"), path2),
                ),
            messageType = "8",
            golden = "35=8|150=F|",
        )

    @Test
    fun `accept actual rebaselines to exact, or absent when the tag was missing`() {
        val updated =
            AssertionQuickFixes.apply(
                expectation(),
                listOf(
                    Edit(150, null, Kind.ACCEPT_ACTUAL, actual = "8"),
                    Edit(31, null, Kind.ACCEPT_ACTUAL, actual = null),
                ),
            )
        assertEquals(Matcher.Exact("8"), updated.fields.single { it.tag == 150 }.matcher)
        assertEquals(Matcher.Absent, updated.fields.single { it.tag == 31 }.matcher)
        // Everything else untouched, mode/messageType/golden preserved.
        assertEquals(4, updated.fields.size)
        assertEquals("8", updated.messageType)
        assertEquals("35=8|150=F|", updated.golden)
    }

    @Test
    fun `loosen and drop`() {
        val updated =
            AssertionQuickFixes.apply(
                expectation(),
                listOf(
                    Edit(31, null, Kind.LOOSEN_TO_PRESENCE, actual = "1.3"),
                    Edit(150, null, Kind.DROP, actual = "8"),
                ),
            )
        assertEquals(Matcher.Presence, updated.fields.single { it.tag == 31 }.matcher)
        assertTrue(updated.fields.none { it.tag == 150 })
        assertEquals(3, updated.fields.size)
    }

    @Test
    fun `edits target the exact group entry, never its same-tag sibling`() {
        val updated =
            AssertionQuickFixes.apply(
                expectation(),
                listOf(Edit(448, path1, Kind.ACCEPT_ACTUAL, actual = "BROKER-B")),
            )
        assertEquals(Matcher.Exact("BROKER-B"), updated.fields.single { it.tag == 448 && it.path == path1 }.matcher)
        assertEquals(Matcher.Exact("CLIENT-X"), updated.fields.single { it.tag == 448 && it.path == path2 }.matcher)
    }

    @Test
    fun `pathed edit falls back to a path-less same-tag field for pre-group-aware scenarios`() {
        val legacy = Expectation(fields = listOf(FieldExpectation(448, Matcher.Exact("OLD"))))
        val updated = AssertionQuickFixes.apply(legacy, listOf(Edit(448, path1, Kind.LOOSEN_TO_PRESENCE, actual = "X")))
        assertEquals(Matcher.Presence, updated.fields.single().matcher)
    }

    @Test
    fun `an edit whose field no longer exists is skipped, not an error`() {
        val updated = AssertionQuickFixes.apply(expectation(), listOf(Edit(999, null, Kind.DROP, actual = null)))
        assertEquals(4, updated.fields.size)
    }
}
