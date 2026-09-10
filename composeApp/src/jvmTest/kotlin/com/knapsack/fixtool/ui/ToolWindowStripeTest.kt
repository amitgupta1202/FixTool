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
 * **The stripes: what each edge carries, in what order, and what a tab says about its window.**
 *
 * The tabs are the only door to five of the eight tool windows now, so these are the tests that stop a
 * window becoming unreachable. Each stripe is asked for its own windows and for the absence of the other
 * edges' windows, because an edge that drew all eight would look right in a screenshot and be wrong about
 * the one thing a stripe is for.
 */
class ToolWindowStripeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun `the left stripe carries the editor and the scenarios rail, and nothing else`() {
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.LEFT, open = emptySet(), onToggle = {})
        }

        composeTestRule.onNodeWithTag(ToolWindow.EDITOR.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.SCENARIOS.testTag).assertExists()
        composeTestRule.onNodeWithTag(ToolWindow.DETAIL.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(ToolWindow.TERMINAL.testTag).assertDoesNotExist()
    }

    @Test
    fun `the right stripe carries the four side windows, top to bottom in stripe order`() {
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.RIGHT, open = emptySet(), onToggle = {})
        }

        val expected = listOf(ToolWindow.DETAIL, ToolWindow.CONNECTION, ToolWindow.ORDER_BOOK, ToolWindow.LATENCY)
        assertEquals(expected, ToolWindow.on(ToolWindowEdge.RIGHT), "the model's own order is the stripe's order")

        val tops = expected.map { window -> topOf(window) }
        assertEquals(tops.sorted(), tops, "the tabs are drawn down the stripe in declaration order, and got $tops")
    }

    @Test
    fun `the bottom stripe carries the terminal and the trace, left to right`() {
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.BOTTOM, open = emptySet(), onToggle = {})
        }

        val terminal = leftOf(ToolWindow.TERMINAL)
        val trace = leftOf(ToolWindow.TRACE)
        assertTrue(terminal < trace, "Terminal sits left of Trace, but they were at $terminal and $trace")
        composeTestRule.onNodeWithTag(ToolWindow.EDITOR.testTag).assertDoesNotExist()
    }

    @Test
    fun `clicking a tab asks for that window and no other`() {
        val toggled = mutableListOf<ToolWindow>()
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.RIGHT, open = emptySet(), onToggle = { toggled += it })
        }

        composeTestRule.onNodeWithTag(ToolWindow.ORDER_BOOK.testTag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ToolWindow.ORDER_BOOK), toggled)
    }

    @Test
    fun `an open window's tab reads as pressed, and a shut one does not`() {
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.RIGHT, open = setOf(ToolWindow.LATENCY), onToggle = {})
        }

        composeTestRule.onNodeWithTag(ToolWindow.LATENCY.testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(ToolWindow.DETAIL.testTag).assertIsNotSelected()
    }

    /**
     * The shortcut is taught by hovering, so every tab has to carry it. Written out here rather than read
     * off [ToolWindow.tooltip], because a test that asks the code for the answer proves only that the code
     * agrees with itself.
     */
    @Test
    fun `every tab names its window and the digit that opens it`() {
        composeTestRule.setContent {
            ToolWindowStripe(edge = ToolWindowEdge.LEFT, open = emptySet(), onToggle = {})
            ToolWindowStripe(edge = ToolWindowEdge.RIGHT, open = emptySet(), onToggle = {})
            ToolWindowStripe(edge = ToolWindowEdge.BOTTOM, open = emptySet(), onToggle = {})
        }

        ToolWindow.entries.forEachIndexed { index, window ->
            val digit = index + 1
            val expected = if (isMac) "${window.title} · ⌘$digit" else "${window.title} · Ctrl+$digit"
            composeTestRule.onNodeWithTag(window.testTag).assertContentDescriptionEquals(expected)
        }
    }

    /** How far down the stripe a tab was drawn. */
    private fun topOf(window: ToolWindow): Float {
        val bounds = composeTestRule.onNodeWithTag(window.testTag).getUnclippedBoundsInRoot()
        return bounds.top.value
    }

    /** How far along the stripe a tab was drawn. */
    private fun leftOf(window: ToolWindow): Float {
        val bounds = composeTestRule.onNodeWithTag(window.testTag).getUnclippedBoundsInRoot()
        return bounds.left.value
    }

    /** One noun per window, and the numbering a reader of the guide is promised. */
    @Test
    fun `the eight windows are named and numbered in stripe order`() {
        assertEquals(
            listOf("Editor", "Scenarios", "Detail", "Connection", "Order book", "Latency", "Terminal", "Trace"),
            ToolWindow.entries.map { it.title },
        )
        assertEquals((1..8).toList(), ToolWindow.entries.map { it.shortcut })
    }
}
