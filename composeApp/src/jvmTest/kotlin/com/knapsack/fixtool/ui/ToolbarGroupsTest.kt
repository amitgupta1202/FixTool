package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The toolbar is five kinds of thing in seven groups, and it never wraps.**
 *
 * The row it replaces had twenty four buttons in five kinds arranged in none of them: Terminal inside the
 * connect group because it was added next to Connect, Settings and Help splitting the panel toggles
 * into two runs, and Clear all, the only control that destroys anything, between Add blank line and
 * Layout with no divider on either side. Every button was an unlabelled glyph.
 *
 * So the tests here are about *arrangement*, not about what any one button calls: that the words are
 * printed while there is room for them, that a rule separates each group from the next, that the filter
 * is in the middle where the empty space used to be, and that a narrow window drops the words rather than
 * the controls or the line.
 */
class ToolbarGroupsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val profile =
        FixConnectionProfile(
            id = "p1",
            name = "BuySide",
            config = FixConnectionConfig(senderCompID = "BUY", targetCompID = "VENUE", host = "saved.host"),
        )

    @Test
    fun `every action chip prints its word while there is room for it`() {
        render(width = 1700.dp)

        composeTestRule.onNodeWithTag("connect").assertTextContains("Connect")
        composeTestRule.onNodeWithTag("toolbar-capture").assertTextContains("Capture")
        composeTestRule.onNodeWithTag("toolbar-search").assertTextContains("Search")
        composeTestRule.onNodeWithTag("toolbar-blank-line").assertTextContains("Blank line")
        composeTestRule.onNodeWithTag("toolbar-clear-all").assertTextContains("Clear all")
    }

    @Test
    fun `a rule separates each group from the next`() {
        render(width = 1700.dp)

        (1..7).forEach { n ->
            composeTestRule.onNodeWithTag("toolbar-divider-$n").assertExists()
        }
    }

    /**
     * The filter is the row's middle, which is where the space was. Asserted by where it lands rather than
     * by a weight, because a weight can be right and the order still wrong.
     */
    @Test
    fun `the filter sits between the workspace and the connect group`() {
        render(width = 1700.dp)

        val afterWorkspace = composeTestRule.onNodeWithTag("toolbar-divider-1").getUnclippedBoundsInRoot()
        val filter = composeTestRule.onNodeWithTag("toolbar-filter").getUnclippedBoundsInRoot()
        val beforeConnect = composeTestRule.onNodeWithTag("toolbar-divider-2").getUnclippedBoundsInRoot()
        val connect = composeTestRule.onNodeWithTag("connect").getUnclippedBoundsInRoot()

        assertLeftToRight(afterWorkspace.left, filter.left)
        assertLeftToRight(filter.right, beforeConnect.left)
        assertLeftToRight(beforeConnect.left, connect.left)
    }

    /** The dangerous one is alone between two rules, which is the only reason those two rules are there. */
    @Test
    fun `Clear all stands on its own between two rules`() {
        render(width = 1700.dp)

        val before = composeTestRule.onNodeWithTag("toolbar-divider-4").getUnclippedBoundsInRoot()
        val clearAll = composeTestRule.onNodeWithTag("toolbar-clear-all").getUnclippedBoundsInRoot()
        val after = composeTestRule.onNodeWithTag("toolbar-divider-5").getUnclippedBoundsInRoot()

        assertLeftToRight(before.left, clearAll.left)
        assertLeftToRight(clearAll.right, after.left)
    }

    /**
     * The first thing a narrow window takes is the words, and the chips say them on hover instead. The
     * control stays, the tooltip stays, and the row stays one line: nothing here is ever unreachable.
     */
    @Test
    fun `a narrow toolbar drops the words and keeps the chips and their tooltips`() {
        assertEquals(
            ToolbarFold.SEGMENTS,
            ToolbarFold.forWidth(900.dp),
            "this test means nothing at a width the words still fit",
        )

        render(width = 900.dp)

        composeTestRule.onNodeWithText("Blank line").assertDoesNotExist()
        composeTestRule
            .onNodeWithTag("toolbar-blank-line")
            .assertExists()
            // Its whole name, and the shortcut the menu bar gave it.
            .assertContentDescriptionContains("Add blank line to all panes · ", substring = true)
    }

    /**
     * The run widget is its own group, between the dangerous one and the view controls, because "what runs
     * when I press ▶" is a question of its own and not a fourth way to connect something.
     */
    @Test
    fun `the run widget sits between Clear all and the view controls`() {
        render(width = 1700.dp)

        val before = composeTestRule.onNodeWithTag("toolbar-divider-5").getUnclippedBoundsInRoot()
        val widget = composeTestRule.onNodeWithTag("run-config").getUnclippedBoundsInRoot()
        val after = composeTestRule.onNodeWithTag("toolbar-divider-6").getUnclippedBoundsInRoot()

        assertLeftToRight(before.left, widget.left)
        assertLeftToRight(widget.right, after.left)
    }

    /**
     * And the words come back in the order the note folds them: the actions first, the run chip's kind,
     * then every remaining word, then the run chip itself, and only then the segments.
     */
    @Test
    fun `the fold order runs from the action words to the layout segments`() {
        assertEquals(true, ToolbarFold.NONE.actionWords)
        assertEquals(true, ToolbarFold.NONE.runKind)
        assertEquals(true, ToolbarFold.NONE.commandWords)
        assertEquals(true, ToolbarFold.NONE.runName)
        assertEquals(true, ToolbarFold.NONE.layoutSegments)

        assertEquals(false, ToolbarFold.ACTION_WORDS.actionWords, "Capture, Search and Blank line go first")
        assertEquals(true, ToolbarFold.ACTION_WORDS.runKind)

        assertEquals(false, ToolbarFold.RUN_KIND.runKind, "then the run chip drops '· load set'")
        assertEquals(true, ToolbarFold.RUN_KIND.commandWords)

        assertEquals(false, ToolbarFold.ALL_WORDS.commandWords, "then Connect, Disconnect all and the rest")
        assertEquals(true, ToolbarFold.ALL_WORDS.runName, "the name is still worth a narrower chip")

        assertEquals(false, ToolbarFold.RUN_NAME.runName, "then the chip goes, leaving the ▶ and a tooltip")
        assertEquals(null, ToolbarFold.RUN_NAME.runWidget.chipMax)
        assertEquals(true, ToolbarFold.RUN_NAME.layoutSegments)

        assertEquals(false, ToolbarFold.SEGMENTS.layoutSegments, "and only then the layout segments")

        // The two widths the tests either side of this one turn on, asserted where the arithmetic is.
        assertEquals(ToolbarFold.NONE, ToolbarFold.forWidth(1700.dp), "everything fits at 1700dp")
        assertEquals(ToolbarFold.SEGMENTS, ToolbarFold.forWidth(900.dp), "and nothing but the filter at 900dp")
    }

    private fun assertLeftToRight(
        left: Dp,
        right: Dp,
    ) {
        assertTrue(left <= right, "$left should be at or left of $right")
    }

    private fun render(width: Dp) {
        composeTestRule.setContent {
            Box(modifier = Modifier.requiredWidth(width)) {
                Toolbar(
                    connectionProfiles = listOf(profile),
                    onQuickConnect = { _, _ -> },
                    onCaptureScenario = { },
                    onSearchAllSessions = { },
                    onAddSeparatorToAll = { },
                    onClearAll = { },
                    onOpenSettings = { },
                    onOpenHelp = { },
                    // A stub, not the real widget: this is a test about arrangement, and the real one reads
                    // a ViewModel and a workspace on disk to decide what it draws. See RunConfigurationWidgetTest.
                    runConfiguration = { Box(Modifier.size(120.dp, 28.dp).testTag("run-config")) },
                )
            }
        }
    }
}
