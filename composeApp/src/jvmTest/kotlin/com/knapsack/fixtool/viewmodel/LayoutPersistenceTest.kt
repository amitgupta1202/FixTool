@file:Suppress("DEPRECATION")

package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.LayoutState
import com.knapsack.fixtool.service.LayoutStateService
import com.knapsack.fixtool.ui.BottomTab
import com.knapsack.fixtool.ui.ToolWindow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workbench layout is persisted to its own store and restored on the next launch, so the app comes back
 * the way it was left: panel sizes, which window each stripe group was showing, the dock's height. The
 * store is isolated by `testSettingsDir` so it never touches the shared `~/.fixtool` state.
 */
class LayoutPersistenceTest {
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "fixtool-layout-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun store() = LayoutStateService(customPath = File(testDir, "layout.json").absolutePath)

    @Test
    fun `a saved layout is restored by the next launch`() {
        // Write layout.json directly, deterministically (the VM's own save is debounced).
        store().save(
            LayoutState(
                railRatio = 0.33f,
                detailRatio = 0.42f,
                bottomHeightDp = 500f,
                leftWindow = "SCENARIOS",
                rightWindow = "DETAIL",
                bottomTab = "TERMINAL",
            ),
        )

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        // Sizes come back verbatim…
        assertEquals(0.33f, next.layoutState.value.railRatio)
        assertEquals(0.42f, next.layoutState.value.detailRatio)
        assertEquals(500f, next.layoutState.value.resolvedBottomHeightDp)
        // …and each group reopens on the window it was left showing.
        assertEquals(ToolWindow.SCENARIOS, next.leftWindow.value, "the rail was showing, so it reopens")
        assertEquals(ToolWindow.DETAIL, next.rightWindow.value, "the detail panel was showing, so it reopens")
        assertEquals(BottomTab.Terminal, next.bottomTab.value, "and the dock comes back on the terminal")
        assertTrue(next.showScenariosRail.value, "the boolean the rest of the app reads agrees")
        assertFalse(next.showConnectionPanel.value, "the connection panel was not showing, so it stays shut")
    }

    /**
     * The Trace panel is a tool window with a stripe tab like the others, so it comes back where it was
     * left. It was the one panel whose open state lived only in memory, which showed as a tab that was
     * pressed when the app closed and clear when it opened.
     */
    @Test
    fun `a Trace panel left open reopens`() {
        val store = store()
        store.save(LayoutState(bottomTab = "TRACE"))

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertTrue(next.tracePanelOpen.value, "the Ledger was open, so it reopens")
        assertEquals(BottomTab.Trace, next.bottomTab.value, "and the layout still says so")

        // And the field survives the file, in both states: a hidden dock is written hidden, not absent.
        store.save(LayoutState(bottomTab = null))
        assertNull(store.load().bottomTab, "a hidden dock reads back hidden")
    }

    /**
     * **A document tab is not restorable.** The id names a document this launch has not opened, so the dock
     * comes back hidden rather than on a tab with nothing behind it.
     */
    @Test
    fun `a document tab in the store does not reopen the dock`() {
        store().save(LayoutState(bottomTab = "DOC:editor-abc"))

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertNull(next.bottomTab.value, "the document is not open this launch, so the dock stays hidden")
    }

    /**
     * **The one-time migration from the eight booleans.**
     *
     * A file that says four right-hand panels were open is exactly the state the groups exist to make
     * unreachable, so the first true one in stripe order wins and the rest are dropped. Read once: the new
     * shape is written back with the old fields cleared, so a second launch has nothing left to migrate.
     */
    @Test
    fun `the old booleans migrate to one window per group`() {
        val old =
            LayoutState(
                showMessageEditor = true,
                showDetailPanel = true,
                showLatencyPanel = true,
                terminalVisible = true,
                showTracePanel = true,
                terminalHeightDp = 320f,
                scenarioDockHeightDp = 340f,
                searchHeightDp = 200f,
            )

        val migrated = old.migrated()
        assertEquals("EDITOR", migrated.leftWindow)
        assertEquals("DETAIL", migrated.rightWindow, "first true in stripe order wins, so Latency is dropped")
        assertEquals("TERMINAL", migrated.bottomTab, "first true in stripe order wins, so Trace is dropped")
        assertEquals(340f, migrated.bottomHeightDp, "the taller of the two docks that became one")
        assertNull(migrated.showDetailPanel, "the old fields are cleared, so nothing migrates twice")
        assertNull(migrated.terminalHeightDp)
        assertNull(migrated.searchHeightDp)

        // Migrating again changes nothing, and closing a group afterwards does not resurrect a panel.
        assertEquals(migrated, migrated.migrated())
        assertNull(migrated.copy(rightWindow = null).migrated().rightWindow)
    }

    @Test
    fun `a launch on an old layout file comes back in the new shape`() {
        store().save(LayoutState(showOrderBookPanel = true, showScenariosRail = true, terminalHeightDp = 420f))

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertEquals(ToolWindow.SCENARIOS, next.leftWindow.value)
        assertEquals(ToolWindow.ORDER_BOOK, next.rightWindow.value)
        assertEquals(420f, next.layoutState.value.resolvedBottomHeightDp, "the terminal's height became the dock's")
        assertNull(next.layoutState.value.showOrderBookPanel, "and the old field is gone from the in-memory layout")
    }

    @Test
    fun `updateLayout reflects in the layout flow immediately`() {
        val vm = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        vm.updateLayout { it.copy(railRatio = 0.4f) }
        // The in-memory layout updates at once (a composable reading it recomposes); the disk write is debounced.
        assertEquals(0.4f, vm.layoutState.value.railRatio)
    }

    @Test
    fun `the session view mode persists through defaultLayout`() {
        val first = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        first.persistViewMode("vertical")
        // persistViewMode is debounced, but it also updates the in-memory settings synchronously.
        assertEquals("vertical", first.appSettings.defaultLayout)
    }
}
