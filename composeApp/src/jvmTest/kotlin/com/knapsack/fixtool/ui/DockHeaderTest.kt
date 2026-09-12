package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The one dock header, by the four promises it makes.**
 *
 * Its title is the stripe noun, its Hide names the digit that brings the dock back, its status goes before
 * the title shortens, and its actions fold into one ⋯ rather than wrapping or being clipped. Each of those
 * was a different answer in a different panel before: ten header patterns, title sizes from 10sp mono bold
 * to 14sp medium, and a close that sat last in one, second of two in another and nowhere at all in a third.
 */
class DockHeaderTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val clear = BarAction("Clear book", Icons.Default.DeleteSweep, {}, "clear-book")
    private val expand = BarAction("Expand all", Icons.Default.UnfoldMore, {}, "expand-all")
    private val collapse = BarAction("Collapse all", Icons.Default.UnfoldLess, {}, "collapse-all")

    /**
     * **The title is the stripe noun and the Hide names the way back.**
     *
     * Not "Order Book", not "Orders", not "the order book" — the word the tab says, so a reader who clicked
     * ⌘5 finds the same noun on the header they just opened. And Hide rather than Close, because nothing is
     * lost: the tooltip names the digit, which is what somebody who has just hidden a dock wants to read.
     */
    @Test
    fun `a dock names itself as its stripe tab does, and its Hide names the digit`() {
        var hidden = false
        composeTestRule.setContent {
            DockHeader(window = ToolWindow.ORDER_BOOK, onHide = { hidden = true }, status = "2 orders · 1 working")
        }

        composeTestRule.onNodeWithTag("tool-window-order_book-header-title").assertTextContains("Order book")
        composeTestRule
            .onNodeWithTag("tool-window-order_book-hide")
            .assertContentDescriptionContains("Hide Order book", substring = true)
        composeTestRule.onNodeWithTag("tool-window-order_book-hide").performClick()
        composeTestRule.waitForIdle()

        assertTrue(hidden, "the Hide hides")
    }

    /** The status is the one place a dock speaks in its own words, and a wide header has room for it. */
    @Test
    fun `a wide header shows its status`() {
        composeTestRule.setContent {
            Box(Modifier.width(400.dp)) {
                DockHeader(window = ToolWindow.ORDER_BOOK, onHide = {}, status = "2 orders · 1 working")
            }
        }

        composeTestRule.onNodeWithTag("tool-window-order_book-status").assertTextContains("2 orders", substring = true)
    }

    /**
     * **The status goes before the title shortens**, because the status is the optional half and the title
     * is not. A header that dropped characters off its name while still printing a count would have spent
     * the room on the thing a reader did not come to the header for.
     */
    @Test
    fun `a narrow header drops the status and keeps the title`() {
        composeTestRule.setContent {
            Box(Modifier.width(110.dp)) {
                DockHeader(window = ToolWindow.ORDER_BOOK, onHide = {}, status = "2 orders · 1 working")
            }
        }

        composeTestRule.onNodeWithTag("tool-window-order_book-status").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tool-window-order_book-header-title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool-window-order_book-hide").assertIsDisplayed()
    }

    /**
     * **A long status never costs the title its name.**
     *
     * A Row measures its unweighted children first, so whichever of the title and the status has no weight
     * is the one that gets what it asks for. The weight was on the title, so a long status took the whole
     * bar at its intrinsic width and the name was middle-ellipsised down to "..": the Order book header
     * came up reading ".." beside "Equity Demo Venue ← EQTY_CLIENT1 · 1 order · 0 working".
     *
     * Found by opening the panel against a real book, not by a test — every status a test had written until
     * now was short enough to leave room. So this one is as long as a real one.
     */
    @Test
    fun `a status longer than the bar ellipsises itself rather than the title`() {
        composeTestRule.setContent {
            Box(Modifier.width(300.dp)) {
                DockHeader(
                    window = ToolWindow.ORDER_BOOK,
                    onHide = {},
                    status = "Equity Demo Venue ← EQTY_CLIENT1 · 14 orders · 9 working · cleared 21:22:03",
                    actions = listOf(clear),
                )
            }
        }

        val title =
            composeTestRule
                .onNodeWithTag("tool-window-order_book-header-title")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
        assertEquals("Order book", title, "the dock lost its own name to its status")
    }

    /**
     * **A narrow header folds its actions into one ⋯ rather than clipping them**, and what folded is still
     * reachable: the menu carries the word the button would have shown. Nothing is reachable at one width
     * and gone at another.
     */
    @Test
    fun `a narrow header folds its actions into the overflow, and the menu still names them`() {
        composeTestRule.setContent {
            Box(Modifier.width(100.dp)) {
                DockHeader(window = ToolWindow.ORDER_BOOK, onHide = {}, actions = listOf(clear, expand, collapse))
            }
        }

        composeTestRule.onNodeWithTag("bar-overflow").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bar-overflow").performClick()
        composeTestRule.waitForIdle()

        // At least the two that folded are menu rows, each under the tag its button carried.
        val rows =
            listOf("clear-book", "expand-all", "collapse-all").count { tag ->
                composeTestRule.onAllNodesWithTag("$tag-menu").fetchSemanticsNodes().isNotEmpty()
            }
        assertTrue(rows >= 2, "a header this narrow folds at least two of its three actions, found $rows")
    }

    /** A wide header folds nothing, so there is no ⋯ at all rather than one that opens an empty menu. */
    @Test
    fun `a wide header draws every action and no overflow`() {
        composeTestRule.setContent {
            Box(Modifier.width(500.dp)) {
                DockHeader(window = ToolWindow.ORDER_BOOK, onHide = {}, actions = listOf(clear, expand, collapse))
            }
        }

        composeTestRule.onNodeWithTag("clear-book").assertIsDisplayed()
        composeTestRule.onNodeWithTag("expand-all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collapse-all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bar-overflow").assertDoesNotExist()
    }

    /**
     * **The height never moves.** Folding is what keeps it constant, which is the whole reason a reader can
     * find the same control at the same height at every width. A bar that wrapped to a second line would
     * move everything below it and read as two bars.
     */
    @Test
    fun `the header is the same height with three actions as with none, at any width`() {
        composeTestRule.setContent {
            Box(Modifier.width(100.dp)) {
                DockHeader(
                    window = ToolWindow.ORDER_BOOK,
                    onHide = {},
                    status = "a status long enough to have wrapped a bar that could wrap",
                    actions = listOf(clear, expand, collapse),
                )
            }
        }

        composeTestRule.onNodeWithTag("tool-window-order_book-header").assertHeightIsEqualTo(DockHeaders.HEIGHT)
    }

    /**
     * **A Close where Close is the honest word.** Search results hold content, and unpinning them loses
     * them, so that one dock's trailing control stays a Close — the grammar reserves Hide for the docks a
     * stripe tab brings straight back.
     */
    @Test
    fun `a dock that holds content closes rather than hides`() {
        composeTestRule.setContent {
            DockHeader(
                title = "Search results · 41",
                onHide = {},
                hideTooltip = "Close",
                hideTag = "search-results-close",
                tag = "search-results-header",
            )
        }

        composeTestRule.onNodeWithTag("search-results-header-title").assertTextContains("Search results · 41")
        composeTestRule.onNodeWithTag("search-results-close").assertContentDescriptionContains("Close")
    }

    /**
     * **The resize handle stops where the folded header would clip.**
     *
     * The ratio floors the docks already had decide on a wide window — a tenth of 1600px is far more than
     * any header needs, so nothing about today's behaviour changes there. They stop meaning anything on a
     * narrow one, and that is where the folded header's own width takes over.
     */
    @Test
    fun `a dock is never dragged narrower than its own folded header`() {
        val density = Density(1f)
        val headerPx = with(density) { DockHeaders.MIN_WIDTH.toPx() }

        assertEquals(
            0.1f,
            dockFloorRatio(0.1f, containerPx = 1600f, density = density),
            "on a wide window the declared floor is the stricter of the two and wins",
        )

        val narrow = dockFloorRatio(0.1f, containerPx = 400f, density = density)
        assertTrue(narrow > 0.1f, "on a 400px window a tenth is not a header, so the header's width takes over")
        assertEquals(headerPx / 400f, narrow, 0.0001f, "and the floor is exactly the folded header")

        assertEquals(
            0.1f,
            dockFloorRatio(0.1f, containerPx = 0f, density = density),
            "an unmeasured row has nothing to clamp against yet",
        )
    }
}
