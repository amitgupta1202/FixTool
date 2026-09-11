package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The two stripes: what each side carries, in what order, and what a tab says about its window.**
 *
 * The tabs are the only door to most of the tool windows now, so these are the tests that stop a window
 * becoming unreachable. Each stripe is asked for its own windows and for the absence of the other side's,
 * because a stripe that drew all nine would look right in a screenshot and be wrong about the one thing a
 * stripe is for.
 */
class ToolWindowStripeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun `the left stripe carries the editor and the rail at the top, and the dock's tabs at the foot`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.LEFT, open = emptySet(), onToggle = {}, documentsOpen = true)
        }

        composeTestRule.onNodeWithTag(ToolWindow.EDITOR.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.SCENARIOS.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.TERMINAL.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.TRACE.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.DOCUMENTS.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.DETAIL.testTag).assertDoesNotExist()
    }

    /**
     * The gap is the whole point of two groups on one stripe: without it the bottom dock's tabs would sit
     * under Scenarios as a third pair of side windows, which is what a bottom stripe used to say they were.
     */
    @Test
    fun `the left stripe's two groups are separated by a gap, with the dock's tabs at the bottom`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.LEFT, open = emptySet(), onToggle = {}, documentsOpen = true)
        }

        val scenarios = boundsOf(ToolWindow.SCENARIOS)
        val terminal = boundsOf(ToolWindow.TERMINAL)
        val documents = boundsOf(ToolWindow.DOCUMENTS)
        assertTrue(
            terminal.first > scenarios.second + GROUP_GAP,
            "the bottom group should be pushed well clear of the top one, but Scenarios ended at " +
                "${scenarios.second} and Terminal began at ${terminal.first}",
        )
        assertTrue(terminal.first < documents.first, "Terminal sits above Documents in the bottom group")
    }

    @Test
    fun `the Documents tab is there only while a document is open`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.LEFT, open = emptySet(), onToggle = {}, documentsOpen = false)
        }

        composeTestRule.onNodeWithTag(ToolWindow.DOCUMENTS.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(ToolWindow.TERMINAL.testTag).assertExists()
    }

    @Test
    fun `the right stripe carries the four side windows, top to bottom in stripe order`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.RIGHT, open = emptySet(), onToggle = {})
        }

        val expected = listOf(ToolWindow.DETAIL, ToolWindow.CONNECTION, ToolWindow.ORDER_BOOK, ToolWindow.LATENCY)
        assertEquals(expected, ToolWindow.inGroup(StripeGroup.RIGHT), "the model's own order is the stripe's order")

        val tops = expected.map { window -> boundsOf(window).first }
        assertEquals(tops.sorted(), tops, "the tabs are drawn down the stripe in declaration order, and got $tops")
        composeTestRule.onNodeWithTag(ToolWindow.EDITOR.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(ToolWindow.TERMINAL.testTag).assertDoesNotExist()
    }

    @Test
    fun `clicking a tab asks for that window and no other`() {
        val toggled = mutableListOf<ToolWindow>()
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.RIGHT, open = emptySet(), onToggle = { toggled += it })
        }

        composeTestRule.onNodeWithTag(ToolWindow.ORDER_BOOK.testTag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ToolWindow.ORDER_BOOK), toggled)
    }

    @Test
    fun `an open window's tab reads as pressed, and a shut one does not`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.RIGHT, open = setOf(ToolWindow.LATENCY), onToggle = {})
        }

        composeTestRule.onNodeWithTag(ToolWindow.LATENCY.testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(ToolWindow.DETAIL.testTag).assertIsNotSelected()
    }

    /** The dock's tabs are pressed the same way: whichever one the dock is showing, and only that one. */
    @Test
    fun `the dock's stripe tabs are pressed one at a time`() {
        composeTestRule.setContent {
            ToolWindowStripe(
                side = StripeSide.LEFT,
                open = setOf(ToolWindow.DOCUMENTS),
                onToggle = {},
                documentsOpen = true,
            )
        }

        composeTestRule.onNodeWithTag(ToolWindow.DOCUMENTS.testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(ToolWindow.TERMINAL.testTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(ToolWindow.TRACE.testTag).assertIsNotSelected()
    }

    /**
     * The shortcut is taught by hovering, so every tab that has one has to carry it. Written out here rather
     * than read off [ToolWindow.tooltip], because a test that asks the code for the answer proves only that
     * the code agrees with itself.
     */
    @Test
    fun `every tab names its window and the digit that opens it`() {
        composeTestRule.setContent {
            ToolWindowStripe(side = StripeSide.LEFT, open = emptySet(), onToggle = {}, documentsOpen = true)
            ToolWindowStripe(side = StripeSide.RIGHT, open = emptySet(), onToggle = {})
        }

        val digits =
            mapOf(
                ToolWindow.EDITOR to 1,
                ToolWindow.SCENARIOS to 2,
                ToolWindow.DETAIL to 3,
                ToolWindow.CONNECTION to 4,
                ToolWindow.ORDER_BOOK to 5,
                ToolWindow.LATENCY to 6,
                ToolWindow.TERMINAL to 7,
                ToolWindow.TRACE to 8,
            )
        digits.forEach { (window, digit) ->
            val expected = if (isMac) "${window.title} · ⌘$digit" else "${window.title} · Ctrl+$digit"
            composeTestRule.onNodeWithTag(window.testTag).assertContentDescriptionEquals(expected)
        }
        // Documents has no digit yet, so its tooltip is the noun and nothing else.
        composeTestRule.onNodeWithTag(ToolWindow.DOCUMENTS.testTag).assertContentDescriptionEquals("Documents")
    }

    /** One noun per window, and the numbering a reader of the guide is promised. */
    @Test
    fun `the windows are named and numbered in stripe order`() {
        assertEquals(
            listOf("Editor", "Scenarios", "Detail", "Connection", "Order book", "Latency", "Terminal", "Trace", "Documents"),
            ToolWindow.entries.map { it.title },
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, null),
            ToolWindow.entries.map { it.shortcut },
        )
    }

    /** How far down the stripe a tab was drawn: its top and its bottom. */
    private fun boundsOf(window: ToolWindow): Pair<Float, Float> {
        val bounds = composeTestRule.onNodeWithTag(window.testTag).getUnclippedBoundsInRoot()
        return bounds.top.value to bounds.bottom.value
    }

    private companion object {
        /** More than the 2dp that separates two tabs of one group, so the gap is unmistakably the gap. */
        const val GROUP_GAP = 40f
    }
}
