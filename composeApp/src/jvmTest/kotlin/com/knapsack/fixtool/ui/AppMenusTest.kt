package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The menu bar, as the catalogue: every action named once and found in one place.**
 *
 * The bar itself is Swing and needs a window, so what is held here is the catalogue it draws — which is also
 * what the window's key handler answers shortcuts from, so a claim about a row is a claim about its shortcut
 * too. The claims are the note's: the Window menu is the stripes, the Session menu speaks the pane header's
 * words, the Run menu is the run widget, every toolbar chip has a row, no shortcut is claimed twice, and a
 * row that destroys something asks the same question the button beside the thing asks.
 *
 * Expected words are written out rather than read off the constants, because a test that asks the code for
 * the answer proves only that the code agrees with itself.
 */
class AppMenusTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var previousPaths: WorkspacePaths

    private var menus: List<AppMenu> = emptyList()
    private var layout by mutableStateOf(ViewMode.SPLIT_HORIZONTAL)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-app-menus", "").apply {
                delete()
                mkdirs()
            }
        previousPaths = WorkspacePaths.current
        WorkspacePaths.use(testDir.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        viewModel.closeAllSessions()
        WorkspacePaths.use(previousPaths)
        testDir.deleteRecursively()
    }

    /** The catalogue, composed the way the window composes it, with [beside] drawn in the same window. */
    private fun compose(beside: @Composable () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalWindowArming provides rememberWindowArming()) {
                menus =
                    appMenus(
                        viewModel = viewModel,
                        layout = layout,
                        onLayoutChange = { layout = it },
                        run = rememberRunChoice(viewModel),
                        workspace = WorkspaceMenuState(),
                        onQuit = {},
                    )
                beside()
            }
        }
        rule.waitForIdle()
    }

    private fun menu(title: String): AppMenu = menus.first { it.title == title }

    private fun item(tag: String): MenuItem = menus.items().first { it.tag == tag }

    /** The labels of one menu's top-level rows, separators as "—", so a group boundary is part of the claim. */
    private fun labels(title: String): List<String> =
        menu(title).rows.map {
            when (it) {
                is MenuItem -> it.label
                is Submenu -> "${it.label} ▸"
                is MenuSeparator -> "—"
            }
        }

    @OptIn(InternalComposeUiApi::class)
    private fun press(
        key: Key,
        shift: Boolean = false,
        ctrl: Boolean = false,
    ): KeyEvent =
        KeyEvent(key, KeyEventType.KeyDown, isMetaPressed = !ctrl, isCtrlPressed = ctrl, isShiftPressed = shift)

    @Test
    fun `the bar is seven menus, in the order the note gives them`() {
        compose()

        assertEquals(listOf("FixTool", "Workspace", "Session", "Run", "View", "Window", "Help"), menus.map { it.title })
        assertTrue(menu("FixTool").application, "the FixTool menu is the one a Mac draws itself")
    }

    /**
     * **The Window menu is the stripes.** Every tool window in stripe order, with the digit its tab prints, and
     * a tick on the one that is open, which is what the stripe draws pressed.
     */
    @Test
    fun `the Window menu is every tool window, its digit, and a tick on what is open`() {
        viewModel.show(ToolWindow.DETAIL)
        compose()

        assertEquals(
            listOf(
                "Editor",
                "Scenarios",
                "Detail",
                "Connection",
                "Order book",
                "Latency",
                "Terminal",
                "Trace",
                "Documents",
                "—",
                "Hide all tool windows",
            ),
            labels("Window"),
        )
        val windows = menu("Window").rows.filterIsInstance<MenuItem>().take(ToolWindow.entries.size)
        assertEquals(
            listOf("⌘1", "⌘2", "⌘3", "⌘4", "⌘5", "⌘6", "⌘7", "⌘8", "⌘9"),
            windows.map { it.chord?.label(mac = true) },
        )
        assertEquals(listOf("Detail"), windows.filter { it.checked == true }.map { it.label })
        assertFalse(item("menu-window-documents").enabled, "Documents is greyed while no document is open")
        assertFalse(menus.dispatch(press(Key.Nine)), "so ⌘9 answers nothing then, rather than opening an empty dock")
    }

    /** IntelliJ's ⇧⌘F12: the second press is the reason it is worth a shortcut. */
    @Test
    fun `Hide all tool windows puts back exactly what it hid`() {
        viewModel.show(ToolWindow.EDITOR)
        viewModel.show(ToolWindow.ORDER_BOOK)
        compose()

        item("menu-window-hide-all").onClick()
        rule.waitForIdle()
        assertNull(viewModel.leftWindow.value)
        assertNull(viewModel.rightWindow.value)
        assertTrue(item("menu-window-hide-all").enabled, "with everything hidden it is live, because it restores")

        item("menu-window-hide-all").onClick()
        rule.waitForIdle()
        assertEquals(ToolWindow.EDITOR, viewModel.leftWindow.value)
        assertEquals(ToolWindow.ORDER_BOOK, viewModel.rightWindow.value)
    }

    /**
     * **The Session menu speaks the pane header's words**, in three groups: what is connected, what the active
     * pane can do, and what every pane can do at once. A pane action and its all-panes twin share the verb and
     * a letter, and differ only by the object and ⇧.
     */
    @Test
    fun `the Session menu is the pane header's words, and each pane action sits over its all-panes twin`() {
        compose()

        assertEquals(
            listOf(
                "Connect ▸",
                "Disconnect all",
                "Close all",
                "—",
                "No session",
                "Search in pane",
                "Filter pane",
                "Wrap lines",
                "Add blank line",
                "Clear messages",
                "Minimize",
                "Close session",
                "—",
                "Capture scenario from all sessions",
                "Search all sessions",
                "Filter all panes",
                "Add blank line to all panes",
                "Clear all panes",
            ),
            labels("Session"),
        )
        assertEquals("⌘F", item("menu-pane-search").chord?.label(mac = true))
        assertEquals("⌘⇧F", item("menu-search-all").chord?.label(mac = true))
        assertEquals("⌘B", item("menu-pane-blank-line").chord?.label(mac = true))
        assertEquals("⌘⇧B", item("menu-blank-line-all").chord?.label(mac = true))
        assertEquals("Ctrl+Shift+B", item("menu-blank-line-all").chord?.label(mac = false))
        // The toolbar's whole-window actions, each a letter with ⇧ because each acts on every pane or session —
        // except the filter, whose ⇧F is already Search all sessions, so it takes ⌥.
        assertEquals("⌘⌥F", item("menu-filter-all").chord?.label(mac = true))
        assertEquals("Ctrl+Alt+F", item("menu-filter-all").chord?.label(mac = false))
        assertEquals("⌘⇧K", item("menu-clear-all").chord?.label(mac = true))
        assertEquals("⌘⇧D", item("menu-disconnect-all").chord?.label(mac = true))
        assertEquals("⌘⇧W", item("menu-close-all").chord?.label(mac = true))
    }

    /** The toolbar is the favourites and the menu bar is the catalogue, so every favourite is in it. */
    @Test
    fun `every toolbar action is a row in the catalogue`() {
        compose()

        fun names(rows: List<MenuRow>): List<String> =
            rows.flatMap {
                when (it) {
                    is MenuItem -> listOf(it.label)
                    is Submenu -> listOf(it.label) + names(it.rows)
                    is MenuSeparator -> emptyList()
                }
            }
        val everyName = menus.flatMap { names(it.rows) }
        val missing = WindowAction.entries.filterNot { it.label in everyName }

        assertTrue(missing.isEmpty(), "toolbar actions with no row: $missing")
    }

    /**
     * **No shortcut is claimed twice.** Control and Command are one modifier to the key handler — ⌘R runs as
     * well as ⌃R, as it always has — so they count as the same shortcut here too.
     */
    @Test
    fun `no two rows claim one shortcut, and no two rows share a tag`() {
        compose()

        val claimed =
            menus
                .items()
                .filter { it.chord != null }
                .groupBy { Triple(it.chord!!.key, it.chord!!.shift, it.chord!!.alt) }
                .filterValues { it.size > 1 }
        assertTrue(claimed.isEmpty(), "shortcuts claimed twice: ${claimed.values.map { rows -> rows.map { it.label } }}")

        val tags = menus.flatMap { menu -> menu.rows.items().map { it.tag } }
        assertEquals(tags.size, tags.toSet().size, "a tag is how a row is kept and how a test names it")
    }

    /** A shortcut is a property of a row, so pressing it does what the row does and nothing else. */
    @Test
    fun `a shortcut does what its row does`() {
        compose()

        assertTrue(menus.dispatch(press(Key.One)))
        assertEquals(ToolWindow.EDITOR, viewModel.leftWindow.value)

        val hidden = viewModel.appSettings.hideProtocolTags
        assertTrue(menus.dispatch(press(Key.H, shift = true)))
        assertEquals(!hidden, viewModel.appSettings.hideProtocolTags)

        // ⌘⌥F is not ⌘F: Option must match, so it asks for the toolbar's filter and opens no search.
        @OptIn(InternalComposeUiApi::class)
        val optionF = KeyEvent(Key.F, KeyEventType.KeyDown, isMetaPressed = true, isAltPressed = true)
        assertTrue(menus.dispatch(optionF))
        assertEquals(1, viewModel.globalFilterFocusRequests.value)
        assertFalse(viewModel.showGlobalSearchDialog.value)

        // ⌘F with no pane on screen searches every session, as it always has.
        assertTrue(menus.dispatch(press(Key.F)))
        assertTrue(viewModel.showGlobalSearchDialog.value)
    }

    /**
     * **Close all by key asks the toolbar's question, and the second press answers it**, because a key is a
     * door to the same loss the chip guards. Clear all panes does not ask, by key or by click: a cleared pane
     * refills as soon as traffic flows, which is the line LosingSomething.kt draws.
     */
    @Test
    fun `Close all by key arms and a second press closes, while Clear all panes clears on one press`() {
        connectedPane("KEYSALL")
        compose { Column { ToolbarSessionControls(viewModel) } }

        assertTrue(menus.dispatch(press(Key.K, shift = true)), "Clear all panes answers its key")

        assertTrue(menus.dispatch(press(Key.W, shift = true)))
        rule.waitForIdle()
        assertEquals(1, viewModel.sessions.size, "the first press asks, it does not close")
        rule.onNodeWithTag("toolbar-close-all").assertContentDescriptionContains("Close 1 pane? Click again.")

        assertTrue(menus.dispatch(press(Key.W, shift = true)))
        rule.waitUntil(25_000) { viewModel.sessions.isEmpty() }
    }

    /** Nothing connected, so Disconnect all is greyed, and a greyed row lets its key through. */
    @Test
    fun `Disconnect all by key with nothing connected answers nothing`() {
        compose()

        assertFalse(item("menu-disconnect-all").enabled)
        assertFalse(menus.dispatch(press(Key.D, shift = true)))
    }

    /**
     * **A refused row lets its key through.** ⌃R with nothing saved is not swallowed, and on a Mac ⌘, and ⌘Q are
     * the system's own application menu's to answer, so answering them here as well would answer them twice.
     */
    @Test
    fun `a refused row does not answer its shortcut, and a Mac leaves the application menu's to the system`() {
        compose()

        assertEquals("Run", item("menu-run").label)
        assertFalse(item("menu-run").enabled)
        assertFalse(menus.dispatch(press(Key.R, ctrl = true)))

        assertFalse(menus.dispatch(press(Key.Comma), nativeApplicationMenu = true))
        assertFalse(viewModel.showSettingsDialog.value)
        assertTrue(menus.dispatch(press(Key.Comma), nativeApplicationMenu = false))
        assertTrue(viewModel.showSettingsDialog.value)
    }

    /**
     * **The Run menu is the run widget.** Choosing a set in the menu aims ▶, the chip names it, and the menu's
     * first row runs the same thing ⌃R and ▶ do — because both read one [RunChoice].
     */
    @Test
    fun `choosing a set in the Run menu aims the widget's run button`() {
        listOf("rfq-round-trip" to "RFQ round trip", "nos-cancel" to "NOS then cancel").forEach { (name, label) ->
            viewModel.saveLoadSet(
                LoadSet(
                    name = name,
                    label = label,
                    phases = listOf(LoadPhaseSpec("Phase 1", "Quote request", "LoadGen", shape = LoadShape.Burst(10))),
                ),
            )
        }
        compose { ToolbarRunConfiguration(viewModel) }

        val sets = (menu("Run").rows.first { it.tag == "menu-run-load-set" } as Submenu).rows
        // In the store's order, which is by file name — the same order the chip's dropdown lists them in.
        assertEquals(listOf("NOS then cancel  1 phase", "RFQ round trip  1 phase"), sets.map { (it as MenuItem).label })

        item("menu-run-load-set-nos-cancel").onClick()
        rule.waitForIdle()

        assertEquals("LOADSET:nos-cancel", viewModel.layoutState.value.selectedRunConfiguration)
        assertEquals("Run NOS then cancel", item("menu-run").label)
        assertEquals(true, item("menu-run-load-set-nos-cancel").checked)
        rule.onNodeWithTag("run-config").assertContentDescriptionContains("NOS then cancel · load set")
    }

    /**
     * **One question, whichever door asks it.** A session closed from the menu is asked about the way its pane
     * header asks, and it is the *same* question: the menu arms it, the header shows it armed, and the header's
     * second click is the answer. Three clocks would have been three questions about one pane, and the header
     * would have closed it on a single click after the menu had asked.
     */
    @Test
    fun `Close session in the menu arms the pane header's Close, and either answers`() {
        val session = connectedPane("MENUCLOSE")
        compose {
            Box(Modifier.width(700.dp)) {
                PaneHeader(
                    session = session,
                    viewMode = FixMessageSession.ViewMode.PARSED,
                    messageCount = 0,
                    isAtBottom = true,
                    onScrollToBottom = {},
                    onMinimize = {},
                    onClose = { viewModel.closeSession(session) },
                )
            }
        }
        assertEquals(session.title, item("menu-pane-heading").label, "the rows say which pane they act on")

        item("menu-pane-close").onClick()
        rule.waitForIdle()
        assertEquals(1, viewModel.sessions.size, "the first ask arms, it does not close")
        assertEquals("Close ${session.title}?", item("menu-pane-close").label)
        rule.onNodeWithTag("pane-close").assertContentDescriptionContains("Close ${session.title}?", substring = true)

        rule.onNodeWithTag("pane-close").performClick()
        rule.waitUntil(25_000) { viewModel.sessions.isEmpty() }
    }

    /** Close all in the menu and Close all on the toolbar are one question too, with the pane count in it. */
    @Test
    fun `Close all in the menu arms the toolbar's Close all`() {
        connectedPane("MENUALL")
        compose { Column { ToolbarSessionControls(viewModel) } }

        item("menu-close-all").onClick()
        rule.waitForIdle()
        assertEquals(1, viewModel.sessions.size)
        assertEquals("Close 1 pane?", item("menu-close-all").label)
        rule.onNodeWithTag("toolbar-close-all").assertContentDescriptionContains("Close 1 pane? Click again.")

        item("menu-close-all").onClick()
        rule.waitUntil(25_000) { viewModel.sessions.isEmpty() }
    }

    /** A pane of its own, against a port nobody listens on: the pane is what these are about, not the wire. */
    private fun connectedPane(sender: String): FixMessageSession {
        val profile =
            FixConnectionProfile(
                name = sender,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "$sender${System.nanoTime().toString().takeLast(6)}",
                        targetCompID = "VENUE",
                        host = "localhost",
                        port = "1",
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        fileStorePath = File(testDir, "store").absolutePath,
                        fileLogPath = File(testDir, "log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        // Polled rather than `waitUntil`, because nothing is composed yet for the rule to wait on.
        val deadline = System.currentTimeMillis() + 25_000
        while (viewModel.sessions.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val session = viewModel.sessions.single()
        viewModel.setActiveSessionByObject(session)
        return session
    }
}
