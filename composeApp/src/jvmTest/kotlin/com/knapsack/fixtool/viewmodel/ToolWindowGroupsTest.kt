package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.ui.BottomTab
import com.knapsack.fixtool.ui.ScenarioDoc
import com.knapsack.fixtool.ui.ToolWindow
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **One window per stripe group**, which is the rule that turned eight independent flags into three
 * selections.
 *
 * The cases here are the ones that flag-per-window could not express: a second tab in the same group
 * *replacing* the first rather than joining it, and a group emptying when its one window is toggled off.
 * The bottom group is the same rule wearing tabs, and the Documents tab is the same rule again, which is
 * why closing the last document has to leave the dock hidden rather than showing an empty strip.
 */
class ToolWindowGroupsTest {
    private lateinit var testDir: File
    private lateinit var viewModel: FixMessageViewModel

    @Before
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "fixtool-groups-${System.nanoTime()}").apply { mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `a second tab in the right group replaces the first`() {
        viewModel.toggle(ToolWindow.DETAIL)
        assertEquals(ToolWindow.DETAIL, viewModel.rightWindow.value)

        viewModel.toggle(ToolWindow.CONNECTION)
        assertEquals(ToolWindow.CONNECTION, viewModel.rightWindow.value, "Connection replaced Detail")
        assertFalse(viewModel.showDetailPanel.value, "and the detail panel is off screen, not beside it")
        assertTrue(viewModel.showConnectionPanel.value)
    }

    @Test
    fun `toggling the showing window empties its group`() {
        viewModel.toggle(ToolWindow.CONNECTION)
        viewModel.toggle(ToolWindow.CONNECTION)
        assertNull(viewModel.rightWindow.value, "the right stripe shows nothing")
        assertFalse(viewModel.showConnectionPanel.value)
    }

    @Test
    fun `the left group holds the editor or the rail, one at a time`() {
        viewModel.toggleMessageEditor()
        assertEquals(ToolWindow.EDITOR, viewModel.leftWindow.value)

        viewModel.toggleScenariosRail()
        assertEquals(ToolWindow.SCENARIOS, viewModel.leftWindow.value)
        assertFalse(viewModel.showMessageEditor.value, "the rail replaced the editor")
    }

    /** ⌘7 then ⌘8: the dock is one area, so the second digit moves it rather than opening a second dock. */
    @Test
    fun `the terminal then the trace leaves the dock on the trace`() {
        viewModel.toggle(ToolWindow.TERMINAL)
        assertEquals(BottomTab.Terminal, viewModel.bottomTab.value)

        viewModel.toggle(ToolWindow.TRACE)
        assertEquals(BottomTab.Trace, viewModel.bottomTab.value, "Trace replaced the terminal in the one dock")
        assertTrue(viewModel.tracePanelOpen.value)

        viewModel.toggle(ToolWindow.TRACE)
        assertNull(viewModel.bottomTab.value, "and the same tab again hides the dock")
        assertFalse(viewModel.tracePanelOpen.value)
    }

    @Test
    fun `opening a document shows the dock on it, and closing the last one hides the dock`() {
        viewModel.openScenarioEditor(scenario("sc-1", "first"))
        val first = ScenarioDoc.editorId("sc-1")
        assertEquals(BottomTab.Document(first), viewModel.bottomTab.value)
        assertTrue(viewModel.showing(ToolWindow.DOCUMENTS), "the Documents stripe tab reads pressed")

        viewModel.openScenarioEditor(scenario("sc-2", "second"))
        val second = ScenarioDoc.editorId("sc-2")
        assertEquals(BottomTab.Document(second), viewModel.bottomTab.value, "opening a document shows it")

        // Closing the one the dock is on falls back to its neighbour…
        viewModel.closeDocument(second)
        assertEquals(BottomTab.Document(first), viewModel.bottomTab.value)

        // …and closing the last one leaves nothing in the group to fall back to.
        viewModel.closeDocument(first)
        assertNull(viewModel.bottomTab.value, "the dock hides with the last document")
        assertFalse(viewModel.showing(ToolWindow.DOCUMENTS))
    }

    /** The Documents tab is a door to the documents, so with none open it opens nothing. */
    @Test
    fun `the Documents tab does nothing while no document is open`() {
        viewModel.toggle(ToolWindow.DOCUMENTS)
        assertNull(viewModel.bottomTab.value)
    }

    /**
     * Pinned results take the dock and give it back. The alternative, leaving the dock on a tab that has
     * just been unpinned, is a dock showing a pane that no longer has anything in it.
     */
    @Test
    fun `unpinning search results gives the dock back to what it was showing`() {
        viewModel.toggle(ToolWindow.TERMINAL)
        viewModel.pinSearchResults()
        assertEquals(BottomTab.SearchResults, viewModel.bottomTab.value)
        assertTrue(viewModel.showSearchResultsPane.value)

        viewModel.closeSearchResultsPane()
        assertEquals(BottomTab.Terminal, viewModel.bottomTab.value, "back to the terminal")
        assertFalse(viewModel.showSearchResultsPane.value)
    }

    @Test
    fun `results pinned onto a hidden dock hide it again when they are unpinned`() {
        viewModel.pinSearchResults()
        assertEquals(BottomTab.SearchResults, viewModel.bottomTab.value)

        viewModel.closeSearchResultsPane()
        assertNull(viewModel.bottomTab.value)
    }

    /**
     * A message click must not evict a panel its owner chose. The right group shows one window, so the old
     * unconditional "select a message, open the detail panel" would throw the order book off screen on every
     * click in the grid.
     */
    @Test
    fun `selecting a message opens the detail panel only onto an empty right stripe`() {
        // Nothing showing: the click opens the detail panel, as it always did.
        viewModel.selectMessage(message())
        assertEquals(ToolWindow.DETAIL, viewModel.rightWindow.value)

        // The order book showing: the click leaves it alone.
        viewModel.toggle(ToolWindow.ORDER_BOOK)
        viewModel.selectMessage(message())
        assertEquals(ToolWindow.ORDER_BOOK, viewModel.rightWindow.value, "nothing was evicted")
    }

    private fun scenario(id: String, name: String) =
        Scenario(id = id, name = name, steps = listOf(ScenarioStep.Send("35=D|11=ORD-1|")))

    private fun message(): FixMessage {
        val raw = "8=FIX.4.4|35=D|11=ORD-1|55=EUR/USD|"
        return FixMessage(
            timestamp = LocalDateTime.of(2026, 6, 30, 10, 0, 0),
            direction = FixMessage.Direction.OUTGOING,
            rawMessage = raw,
            quickfixMessage = Message(),
            wireRaw = raw.replace('|', ''),
        )
    }
}
