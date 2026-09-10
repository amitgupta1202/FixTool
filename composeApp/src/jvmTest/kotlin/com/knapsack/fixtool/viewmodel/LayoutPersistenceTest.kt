package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.LayoutState
import com.knapsack.fixtool.service.LayoutStateService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The workbench layout is persisted to app settings and restored on the next launch, so the app comes back the
 * way it was left — panel sizes, which panels are open, dock heights. The store is isolated by `testSettingsDir`
 * so it never touches the shared `~/.fixtool` state.
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

    @Test
    fun `a saved layout is restored by the next launch`() {
        // Write layout.json directly, deterministically (the VM's own save is debounced).
        LayoutStateService(customPath = File(testDir, "layout.json").absolutePath).save(
            LayoutState(
                railRatio = 0.33f,
                detailRatio = 0.42f,
                terminalHeightDp = 500f,
                scenarioDockHeightDp = 420f,
                showScenariosRail = true,
                showDetailPanel = true,
                terminalVisible = true,
            ),
        )

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        // Sizes come back verbatim…
        assertEquals(0.33f, next.layoutState.value.railRatio)
        assertEquals(0.42f, next.layoutState.value.detailRatio)
        assertEquals(500f, next.layoutState.value.terminalHeightDp)
        assertEquals(420f, next.layoutState.value.scenarioDockHeightDp)
        // …and the panels that were open reopen.
        assertTrue(next.showScenariosRail.value, "the rail was open, so it reopens")
        assertTrue(next.showDetailPanel.value, "the detail panel was open, so it reopens")
        assertFalse(next.showConnectionPanel.value, "the connection panel was closed, so it stays closed")
    }

    /**
     * The Trace panel is a tool window with a stripe tab like the other seven, so it comes back where it
     * was left. It was the one panel whose open state lived only in memory, which showed as a tab that
     * was pressed when the app closed and clear when it opened.
     */
    @Test
    fun `a Trace panel left open reopens`() {
        val store = LayoutStateService(customPath = File(testDir, "layout.json").absolutePath)
        store.save(LayoutState(showTracePanel = true))

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertTrue(next.tracePanelOpen.value, "the Ledger was open, so it reopens")
        assertTrue(next.layoutState.value.showTracePanel, "and the layout still says so")

        // And the field survives the file, in both states: a shut panel is written shut rather than absent.
        store.save(LayoutState(showTracePanel = false))
        assertFalse(store.load().showTracePanel, "a shut Ledger reads back shut")
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
