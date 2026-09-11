package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The one dock at the foot of the window.**
 *
 * What these pin is the strip: which tabs are in it, in what order, which of them is conditional, and that
 * selecting one shows its content. Four things used to be four docks or panes with four frames, so a strip
 * that quietly dropped one would be a feature that had become unreachable rather than one that had moved.
 *
 * The terminal tab is deliberately never selected here: selecting it spawns a real PTY, which is not a
 * thing to do in a headless test run. Its own behaviour is covered by `ToolWindowGroupsTest`.
 */
class BottomDockTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var testDir: File
    private lateinit var viewModel: FixMessageViewModel

    @Before
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "fixtool-dock-${System.nanoTime()}").apply { mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun dock() {
        composeTestRule.setContent { BottomDock(viewModel) }
        composeTestRule.waitForIdle()
    }

    private fun leftOf(tag: String): Float {
        val bounds = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return bounds.left.value
    }

    @Test
    fun `a dock showing nothing has no strip at all`() {
        dock()

        composeTestRule.onNodeWithTag("bottom-dock-tabs").assertDoesNotExist()
        composeTestRule.onNodeWithTag("bottom-dock-tab-trace").assertDoesNotExist()
    }

    @Test
    fun `the fixed tabs come first, terminal then trace`() {
        viewModel.openTracePanel()
        dock()

        composeTestRule.onNodeWithTag("bottom-dock-tabs").assertIsDisplayed()
        assertTrue(
            leftOf("bottom-dock-tab-terminal") < leftOf("bottom-dock-tab-trace"),
            "Terminal sits left of Trace",
        )
    }

    /** The results tab exists only while there are pinned results, which is what its × takes away. */
    @Test
    fun `the search results tab is there only while results are pinned`() {
        viewModel.openTracePanel()
        dock()
        composeTestRule.onNodeWithTag("bottom-dock-tab-search").assertDoesNotExist()

        viewModel.pinSearchResults()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("bottom-dock-tab-search").assertIsDisplayed()
        assertTrue(leftOf("bottom-dock-tab-trace") < leftOf("bottom-dock-tab-search"), "and it follows Trace")

        composeTestRule.onNodeWithTag("bottom-dock-close-search").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("bottom-dock-tab-search").assertDoesNotExist()
        assertEquals(BottomTab.Trace, viewModel.bottomTab.value, "and the dock goes back to what it was on")
    }

    /**
     * **Pinned is not showing.** The tab belongs in the strip for as long as there are results behind it,
     * because a strip that dropped it the moment you looked at another tab would make switching tabs the
     * only way to lose a search, with no way back to it.
     */
    @Test
    fun `the search results tab survives a look at another tab`() {
        viewModel.openTracePanel()
        viewModel.pinSearchResults()
        dock()
        composeTestRule.onNodeWithTag("bottom-dock-tab-search").assertIsDisplayed()

        composeTestRule.onNodeWithTag("bottom-dock-tab-trace").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BottomTab.Trace, viewModel.bottomTab.value)
        composeTestRule.onNodeWithTag("bottom-dock-tab-search").assertIsDisplayed()

        composeTestRule.onNodeWithTag("bottom-dock-tab-search").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BottomTab.SearchResults, viewModel.bottomTab.value, "and it is the way back to them")
    }

    @Test
    fun `every open document has a tab, after the fixed ones`() {
        viewModel.openScenarioEditor(scenario("sc-a", "alpha"))
        viewModel.openScenarioEditor(scenario("sc-b", "beta"))
        dock()

        val alpha = "doc-tab-${ScenarioDoc.editorId("sc-a")}"
        val beta = "doc-tab-${ScenarioDoc.editorId("sc-b")}"
        composeTestRule.onNodeWithTag(alpha).assertIsDisplayed()
        composeTestRule.onNodeWithTag(beta).assertIsDisplayed()
        assertTrue(leftOf("bottom-dock-tab-trace") < leftOf(alpha), "the documents follow the fixed tabs")
        assertTrue(leftOf(alpha) < leftOf(beta), "in the order they were opened")
    }

    @Test
    fun `selecting a tab shows its content`() {
        viewModel.openScenarioEditor(scenario("sc-a", "alpha"))
        dock()

        // A document is showing, so its editor is in the dock's body.
        composeTestRule.onNodeWithTag("scenario-name").assertIsDisplayed()

        // Clicking Trace moves the dock to the Ledger, and the editor goes with it.
        composeTestRule.onNodeWithTag("bottom-dock-tab-trace").performClick()
        composeTestRule.waitForIdle()
        assertEquals(BottomTab.Trace, viewModel.bottomTab.value)
        composeTestRule.onNodeWithTag("scenario-name").assertDoesNotExist()

        // And back again, by its own tab.
        composeTestRule.onNodeWithTag("doc-tab-${ScenarioDoc.editorId("sc-a")}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scenario-name").assertIsDisplayed()
    }

    private fun scenario(id: String, name: String) =
        Scenario(id = id, name = name, steps = listOf(ScenarioStep.Send("35=D|11=ORD-1|")))
}
