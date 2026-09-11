@file:Suppress("DEPRECATION")

package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.LayoutState
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.service.LayoutStateService
import com.knapsack.fixtool.service.SavedRunEntry
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.service.load.LoadSetStore
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

    /**
     * **What the ▶ is pointed at survives a restart**, which is the whole reason it is written down: the
     * run somebody made before lunch is almost always the run they want after it, and an answer held only
     * in memory would put every relaunch back on whatever the default rule picked.
     */
    @Test
    fun `the selected run configuration comes back on the next launch`() {
        val first = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        saveLoadSet(first, "rfq-round-trip")
        first.selectRunConfiguration("LOADSET:rfq-round-trip")
        // The VM's own save is debounced, so the file is written here rather than waited for.
        store().save(first.layoutState.value)

        val next = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertEquals("LOADSET:rfq-round-trip", next.layoutState.value.selectedRunConfiguration)
        assertEquals("LOADSET:rfq-round-trip", next.resolvedRunConfiguration())
    }

    /**
     * **The default rule falls back rather than emptying.**
     *
     * A window whose ▶ said "nothing" on a workspace with three saved sets in it would be a control asking
     * to be configured before it can be used, so the rule has three more answers after the pick: the last
     * thing that was run, then the first thing that was saved, then nothing at all. And a pick naming a set
     * that is no longer on disk falls through the same way rather than being cleared, because a set comes
     * back with the branch that defined it.
     */
    @Test
    fun `the default run configuration is the pick, then the last run, then the first saved`() {
        val vm = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        assertNull(vm.resolvedRunConfiguration(), "nothing saved and nothing run is the one honest null")

        saveLoadSet(vm, "aaa-first")
        assertEquals("LOADSET:aaa-first", vm.resolvedRunConfiguration(), "one saved set is the default")

        saveLoadSet(vm, "zulu-last")
        vm.selectRunConfiguration("LOADSET:zulu-last")
        assertEquals("LOADSET:zulu-last", vm.resolvedRunConfiguration(), "and a pick beats the default")

        vm.selectRunConfiguration("LOADSET:deleted-on-another-branch")
        assertEquals(
            "LOADSET:aaa-first",
            vm.resolvedRunConfiguration(),
            "a pick naming nothing on disk falls through to the rule under it",
        )

        // A record naming a saved set beats the first one saved, because "what I ran last" is the better
        // guess at "what I want to run next" on any workspace with more than one set in it.
        vm.selectRunConfiguration(null)
        val report = LoadFixtures.burstReport(unmatched = 0)
        vm.loadRecordStore.write(
            LoadRecord.of(report).copy(set = LoadRecord.SetInfo("zulu-last", OnFailure.CONTINUE)),
        )
        // Written straight to the store, behind the ViewModel's back, so the lists it holds are told here.
        // Every door in the app goes through a function that says so for itself.
        vm.refreshRunConfigurations()
        assertEquals("LOADSET:zulu-last", vm.resolvedRunConfiguration())

        // And a record naming a set that is no longer saved is skipped rather than offered, the same way a
        // stale pick is: a Recent row can name a file that went with the branch it was written on.
        vm.deleteLoadSet("zulu-last")
        assertEquals("LOADSET:aaa-first", vm.resolvedRunConfiguration())

        // A scenario set is in the running for the same slot when nothing else is: the rule does not care
        // which kind it was, only which file is still there.
        vm.deleteLoadSet("aaa-first")
        vm.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade"))))
        vm.refreshRunConfigurations()
        assertEquals("RUNSET:nightly", vm.resolvedRunConfiguration())
    }

    /**
     * **The lists the run widget draws from are state, and every door that writes one refreshes them.**
     *
     * They used to be read by the widget itself, inside `remember` blocks keyed on what the widget could
     * see: its menu opening, a run starting, the load set editor closing. A workspace being opened moves
     * none of those, so a workspace with sets in it came up under the previous one's chip, reported from a
     * desk as a chip still reading `Load run…` with three saved sets behind it. The claim is therefore
     * about the four writes that matter: a save, a delete, and the re-pointing of the stores.
     */
    @Test
    fun `runConfigurations follows a save, a delete and a workspace open`() {
        val previousPaths = WorkspacePaths.current
        WorkspacePaths.use(testDir.absolutePath)
        try {
            val vm = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
            assertTrue(vm.savedLoadSetNames().isEmpty(), "a fresh workspace has nothing to run")
            assertTrue(vm.savedRunSetNames().isEmpty())

            saveLoadSet(vm, "aaa-first")
            assertEquals(listOf("aaa-first"), vm.savedLoadSetNames(), "a save shows up")

            vm.saveRunSet("nightly", emptyList())
            assertEquals(listOf("nightly"), vm.savedRunSetNames(), "and so does a run set")

            vm.deleteLoadSet("aaa-first")
            assertTrue(vm.savedLoadSetNames().isEmpty(), "a delete takes the name back out")

            // A second workspace with a set of its own: the stores are re-pointed at it, and the lists have
            // to follow or the chip goes on naming a set that is no longer anywhere near the open workspace.
            val other = File(testDir, "workspaces/other").apply { mkdirs() }
            LoadSetStore(customDir = File(other, "load-sets").absolutePath).save(
                LoadSet(
                    name = "zulu-last",
                    label = "Zulu last",
                    phases = listOf(LoadPhaseSpec("Phase 1", "Nothing", "LoadGen", shape = LoadShape.Burst(10))),
                ),
            )
            vm.openWorkspace(other).getOrThrow()

            assertEquals(listOf("zulu-last"), vm.savedLoadSetNames())
            assertTrue(vm.savedRunSetNames().isEmpty(), "and the previous workspace's set stays behind")
            assertEquals("LOADSET:zulu-last", vm.resolvedRunConfiguration(), "which is what the chip then names")

            vm.closeWorkspace()
            assertTrue(vm.savedLoadSetNames().isEmpty(), "closing takes it back out again")
            assertEquals(listOf("nightly"), vm.savedRunSetNames(), "and Default's own is back")
        } finally {
            WorkspacePaths.use(previousPaths)
        }
    }

    /** The saved load sets the ViewModel is holding for the run widget, by name. */
    private fun FixMessageViewModel.savedLoadSetNames(): List<String> {
        val held = runConfigurations.value
        return held.loadSets.map { it.name }
    }

    /** The saved scenario sets the ViewModel is holding for the run widget, by name. */
    private fun FixMessageViewModel.savedRunSetNames(): List<String> {
        val held = runConfigurations.value
        return held.runSets.map { it.name }
    }

    private fun saveLoadSet(vm: FixMessageViewModel, name: String) {
        vm.saveLoadSet(
            LoadSet(
                name = name,
                label = name,
                phases = listOf(LoadPhaseSpec("Phase 1", "Nothing", "LoadGen", shape = LoadShape.Burst(10))),
            ),
        )
    }

    @Test
    fun `the session view mode persists through defaultLayout`() {
        val first = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        first.persistViewMode("vertical")
        // persistViewMode is debounced, but it also updates the in-memory settings synchronously.
        assertEquals("vertical", first.appSettings.defaultLayout)
    }
}
