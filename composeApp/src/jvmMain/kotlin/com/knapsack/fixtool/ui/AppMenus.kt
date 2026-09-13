package com.knapsack.fixtool.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.io.File

/** The Mac's own word for showing a folder, and a plain description everywhere else. */
internal val REVEAL_WORKSPACE_LABEL: String = if (IS_MAC) "Reveal in Finder" else "Open workspace folder"

/** The Help menu's second row, which opens the guide at its automation chapter. */
internal const val AUTOMATION_REFERENCE_LABEL = "Automation reference"

/**
 * **Which tool windows are on screen**, which is what draws a stripe tab pressed and a Window menu row ticked.
 *
 * Read off the three group selections rather than kept as state of its own, because every one of these
 * windows has other doors (a message selection opens the detail panel, a control-surface `/panel` call opens
 * any of them) and anything that tracked its own boolean would disagree with the window it names.
 */
internal fun openToolWindows(
    left: ToolWindow?,
    right: ToolWindow?,
    bottom: BottomTab?,
): Set<ToolWindow> =
    buildSet {
        left?.let(::add)
        right?.let(::add)
        when (bottom) {
            is BottomTab.Terminal -> add(ToolWindow.TERMINAL)
            is BottomTab.Trace -> add(ToolWindow.TRACE)
            is BottomTab.Document -> add(ToolWindow.DOCUMENTS)
            else -> Unit
        }
    }

/**
 * **Everything the window can do, in seven menus**: FixTool, Workspace, Session, Run, View, Window and Help.
 *
 * Each menu is read off what the rest of the window already reads, so nothing here is a second definition:
 * the Window menu is [ToolWindow], as the stripes are; the Session menu's pane rows take the pane header's
 * words; the Run menu is the run widget's own [RunChoice]; the View menu is [ViewMode] and the View ▾ labels;
 * and every toolbar chip is a [WindowAction].
 *
 * Built in a composable of its own and published, so the state it reads recomposes this and not the window.
 */
@Composable
@Suppress("LongParameterList")
internal fun PublishAppMenus(
    state: AppMenuState,
    viewModel: FixMessageViewModel,
    layout: ViewMode,
    onLayoutChange: (ViewMode) -> Unit,
    runDoors: RunDoors,
    workspace: WorkspaceMenuState,
    onQuit: () -> Unit,
) {
    val run = rememberRunChoice(viewModel, runDoors)
    val menus = appMenus(viewModel, layout, onLayoutChange, run, workspace, onQuit)
    SideEffect { state.menus = menus }
}

/** The catalogue itself, returned rather than published so a test can read it. See [PublishAppMenus]. */
@Composable
@Suppress("LongParameterList")
internal fun appMenus(
    viewModel: FixMessageViewModel,
    layout: ViewMode,
    onLayoutChange: (ViewMode) -> Unit,
    run: RunChoice,
    workspace: WorkspaceMenuState,
    onQuit: () -> Unit,
): List<AppMenu> =
    listOf(
        applicationMenu(viewModel, onQuit),
        workspaceMenu(viewModel, workspace),
        sessionMenu(viewModel),
        runMenu(run),
        viewMenu(viewModel, layout, onLayoutChange),
        windowMenu(viewModel),
        helpMenu(viewModel),
    )

private fun applicationMenu(
    viewModel: FixMessageViewModel,
    onQuit: () -> Unit,
): AppMenu =
    AppMenu(
        title = "FixTool",
        application = true,
        rows =
            listOf(
                MenuItem(
                    WindowAction.SETTINGS.label,
                    "menu-settings",
                    { if (!viewModel.showSettingsDialog.value) viewModel.toggleSettingsDialog() },
                    chord = WindowAction.SETTINGS.chord,
                ),
                MenuSeparator("menu-application-rule"),
                MenuItem("Quit FixTool", "menu-quit", onQuit, chord = Shortcuts.QUIT),
            ),
    )

/**
 * The workspace switcher's rows, and the two it has no room for: showing the folder, and resetting an example.
 *
 * **Close workspace is greyed on Default**, where the switcher leaves it out. The switcher's reasoning is
 * right for a dropdown that is about where you are — Close is what returns you to Default, so on Default it
 * has already happened — but a menu bar is where a reader looks for what the app can do, and a row that
 * comes and goes is a row they conclude does not exist.
 */
@Composable
private fun workspaceMenu(
    viewModel: FixMessageViewModel,
    workspace: WorkspaceMenuState,
): AppMenu {
    val folder = viewModel.openWorkspace
    // Keyed on the folder: whether it is an example is a file inside it, and the window recomposes far more
    // often than a workspace is opened.
    val example = remember(folder) { viewModel.openWorkspaceExample() }
    val recent =
        workspace.recents.map { recent ->
            MenuItem(
                "${recent.name}  ${shortPath(recent)}",
                "menu-workspace-recent-${recent.absolutePath}",
                { workspace.onOpenRecent?.invoke(recent) },
            )
        }
    return AppMenu(
        title = "Workspace",
        rows =
            listOf(
                MenuItem(NEW_WORKSPACE_LABEL, "menu-workspace-new", { workspace.onNew?.invoke() }),
                MenuItem(OPEN_WORKSPACE_LABEL, "menu-workspace-open", { workspace.onBrowse?.invoke() }),
                Submenu(RECENT_WORKSPACES_LABEL, "menu-workspace-recent", recent),
                MenuSeparator("menu-workspace-rule-1"),
                MenuItem(REVEAL_WORKSPACE_LABEL, "menu-workspace-reveal", { revealFolder(folder) }),
                MenuItem(
                    // The example's own name, as Settings' Storage page prints it, and a plain word on a
                    // workspace that is not one, so the row reads the same whether or not it is greyed.
                    example?.let { "Reset ${it.displayName}" } ?: "Reset example",
                    "menu-workspace-reset",
                    { viewModel.resetOpenExample() },
                    enabled = example != null,
                ),
                MenuSeparator("menu-workspace-rule-2"),
                MenuItem(
                    CLOSE_WORKSPACE_LABEL,
                    "menu-workspace-close",
                    { workspace.onClose?.invoke() },
                    enabled = !workspace.isDefault,
                ),
            ),
    )
}

/**
 * **What is connected, what the active pane can do, and what every pane can do at once** — three groups.
 *
 * The middle group is the pane header's own vocabulary, and it acts on the **active** session: the one the
 * editor sends to, and the one ⌘F already searched. A pane action and its all-panes twin read the same verb
 * and differ only in the object, so Add blank line sits above Add blank line to all panes and their shortcuts
 * differ only by ⇧.
 *
 * Three of the header's actions are not here. Move left and Move right are positions in the split grid, which
 * the tabs layout does not have and a menu cannot show. Scroll to bottom is the drawn grid's own scroll state,
 * which lives in the pane and not the session. Group by conversation is in View, for every pane, which is what
 * the toolbar's View ▾ has always offered.
 */
@Composable
private fun sessionMenu(viewModel: FixMessageViewModel): AppMenu {
    val arming = windowArming()
    val activeLoad by viewModel.activeLoadRun.collectAsState()
    val runningIds by viewModel.runningSetIds.collectAsState()
    // Every session's connection, so Disconnect all's greying and the profiles' states follow them. Keyed by
    // session, so a pane opening or closing moves a key rather than shifting every collector after it.
    viewModel.sessions.forEach { session -> key(session.id) { session.connectionState.collectAsState().value } }

    val panes = viewModel.sessions.size
    val closingAll = ClosingAllPanes(panes)
    val closeAll = viewModel.closeAllOffer(activeLoad, runningIds)
    val sessions =
        listOf(
            Submenu(WindowAction.CONNECT.label, "menu-connect", connectRows(viewModel)),
            MenuItem(
                WindowAction.DISCONNECT_ALL.label,
                "menu-disconnect-all",
                { viewModel.disconnectAllSessions() },
                chord = WindowAction.DISCONNECT_ALL.chord,
                enabled = viewModel.disconnectAllOffer(activeLoad, runningIds).enabled,
            ),
            MenuItem(
                if (arming.isArmed(closingAll)) "Close $panes pane${plural(panes)}?" else WindowAction.CLOSE_ALL.label,
                "menu-close-all",
                { if (arming.confirm(closingAll)) viewModel.closeAllSessions() },
                chord = WindowAction.CLOSE_ALL.chord,
                enabled = closeAll.enabled,
            ),
        )
    return AppMenu(
        title = "Session",
        rows =
            sessions + MenuSeparator("menu-session-rule-1") + paneRows(viewModel, arming) +
                MenuSeparator("menu-session-rule-2") + everyPaneRows(viewModel),
    )
}

/** What every pane can do at once: the toolbar's Capture, Search, filter, Blank line and Clear all. */
private fun everyPaneRows(viewModel: FixMessageViewModel): List<MenuRow> =
    listOf(
        MenuItem(WindowAction.CAPTURE.label, "menu-capture", { viewModel.captureAllSessionsToEditor() }),
        MenuItem(
            WindowAction.SEARCH_ALL.label,
            "menu-search-all",
            { viewModel.toggleGlobalSearchDialog() },
            chord = WindowAction.SEARCH_ALL.chord,
        ),
        MenuItem(
            WindowAction.FILTER_ALL.label,
            "menu-filter-all",
            { viewModel.focusGlobalFilter() },
            chord = WindowAction.FILTER_ALL.chord,
        ),
        MenuItem(
            WindowAction.BLANK_LINE_ALL.label,
            "menu-blank-line-all",
            { viewModel.addSeparatorToAllSessions() },
            chord = WindowAction.BLANK_LINE_ALL.chord,
        ),
        // One press and one click, as the chip is: a cleared pane refills the moment traffic flows, which is
        // the line LosingSomething.kt draws between asking and not.
        MenuItem(
            WindowAction.CLEAR_ALL.label,
            "menu-clear-all",
            { viewModel.clearAllSessions() },
            chord = WindowAction.CLEAR_ALL.chord,
        ),
    )

/**
 * Connect's profiles: pick one and it connects, as the toolbar's Connect ▾ does.
 *
 * A workspace with environments asks where, the way the chip does: each profile opens its environments and
 * the endpoint the profile already names. A profile that is already up says so after its name, because a
 * native menu has no coloured dot to say it with.
 */
private fun connectRows(viewModel: FixMessageViewModel): List<MenuRow> {
    val environments = viewModel.environments
    return viewModel.connectionProfiles.map { profile ->
        val state = viewModel.getProfileConnectionState(profile.id)
        val name =
            if (state == FixConnectionState.DISCONNECTED) {
                profile.name
            } else {
                "${profile.name}  (${state.getDisplayText().lowercase()})"
            }
        val tag = "menu-connect-${profile.id}"
        if (environments.isEmpty()) {
            MenuItem(name, tag, { viewModel.connectProfile(profile.id, profile) })
        } else {
            Submenu(
                name,
                tag,
                environments.map { environment ->
                    MenuItem(
                        environment.name,
                        "$tag-${environment.name}",
                        { viewModel.connectProfileIn(profile, environment) },
                    )
                } +
                    MenuSeparator("$tag-rule") +
                    MenuItem(
                        "As saved (${profile.config.host})",
                        "$tag-as-saved",
                        { viewModel.connectProfile(profile.id, profile) },
                    ),
            )
        }
    }
}

/**
 * The active pane's rows, in the pane header's words.
 *
 * The toggles need a pane that is **drawn**: a minimized pane has no search bar to show and no filter row to
 * open. What acts on the log — a blank line, clearing it, closing it — needs only a session, minimized or not.
 * A venue has no grid at all, so it gets Minimize and Close and nothing else, as its header does.
 */
@Composable
private fun paneRows(
    viewModel: FixMessageViewModel,
    arming: WindowArming,
): List<MenuRow> {
    val active = viewModel.activeSession
    val minimized = active?.minimized.collectedOr(false)
    // **Which pane these act on**, as a heading. The active session is the one the editor sends to, and in the
    // split layout nothing about a pane's own frame says which one that is — so a menu that acted on it without
    // naming it would be asking the reader to know something the window does not show.
    val heading =
        MenuItem(
            when {
                active == null -> "No session"
                minimized -> "${active.title} (minimized)"
                else -> active.title
            },
            "menu-pane-heading",
            {},
            enabled = false,
        )
    return listOf(heading) +
        paneToggleRows(viewModel, active?.takeIf { !it.isVenue && !minimized }) +
        paneLogRows(viewModel, arming, active, minimized)
}

/** The three toggles, which act on a drawn grid: the pane's search bar, its filter row, and wrapping. */
@Composable
private fun paneToggleRows(
    viewModel: FixMessageViewModel,
    drawn: FixMessageSession?,
): List<MenuRow> {
    val rawRows = viewModel.viewMode.collectAsState().value == FixMessageSession.ViewMode.RAW
    val searching = drawn?.searchVisible.collectedOr(false)
    val filtering = drawn?.filterVisible.collectedOr(false)
    val wrapped = drawn?.wrapText.collectedOr(false)
    return listOf(
        MenuItem(
            SEARCH_LABEL,
            "menu-pane-search",
            // The fall-through ⌘F has always had: with no pane on screen to search, it searches them all.
            { drawn?.toggleSearch() ?: viewModel.toggleGlobalSearchDialog() },
            chord = Shortcuts.SEARCH_IN_PANE,
            checked = searching,
        ),
        MenuItem(
            FILTER_LABEL,
            "menu-pane-filter",
            { drawn?.toggleFilter() },
            enabled = drawn != null,
            checked = filtering,
        ),
        MenuItem(
            WRAP_LABEL,
            "menu-pane-wrap",
            { drawn?.toggleWrapText() },
            // Genuinely RAW-only, as in the header: a grid of parsed fields has no lines to wrap.
            enabled = drawn != null && rawRows,
            checked = rawRows && wrapped,
        ),
    )
}

/** What acts on the session itself, minimized or not: its log, its place in the layout, and closing it. */
private fun paneLogRows(
    viewModel: FixMessageViewModel,
    arming: WindowArming,
    active: FixMessageSession?,
    minimized: Boolean,
): List<MenuRow> {
    val log = active?.takeIf { !it.isVenue }
    val closing = active?.let { ClosingSession(it.id) }
    val armed = closing != null && arming.isArmed(closing)
    return listOf(
        MenuItem(
            BLANK_LINE_LABEL,
            "menu-pane-blank-line",
            { log?.addSeparator() },
            chord = Shortcuts.BLANK_LINE,
            enabled = log != null,
        ),
        MenuItem(CLEAR_LABEL, "menu-pane-clear", { log?.clearMessages() }, enabled = log != null),
        MenuItem(
            MINIMIZE_LABEL,
            "menu-pane-minimize",
            { active?.let { viewModel.setSessionMinimized(it, true) } },
            enabled = active != null && !minimized,
        ),
        MenuItem(
            if (armed) "Close ${active?.title}?" else CLOSE_SESSION_LABEL,
            "menu-pane-close",
            { if (active != null && closing != null && arming.confirm(closing)) viewModel.closeSession(active) },
            enabled = active != null,
        ),
    )
}

/**
 * A flow's value where there is a flow, and [default] where there is not.
 *
 * A plain `if` around the collector rather than a safe call, so whether the collector exists is one branch of
 * one conditional — the active pane comes and goes, and its collectors with it.
 */
@Composable
private fun <T> StateFlow<T>?.collectedOr(default: T): T =
    if (this != null) {
        collectAsState().value
    } else {
        default
    }

/**
 * **The run configuration widget, as a menu**: what ▶ runs, the ways to choose it, and the two dialogs.
 *
 * Every row is the widget's own — the same [RunChoice], the same counts, the same handlers — so a row here and
 * a row in the chip's dropdown cannot disagree about what will run. The saved sets are submenus rather than
 * the dropdown's `Load set ▸` prefixes, because here a submenu is available and a prefix pretending to be one
 * is not needed.
 */
private fun runMenu(run: RunChoice): AppMenu {
    val loadSets =
        run.loadSets.map { set ->
            val configuration = RunConfiguration(RunConfiguration.Kind.LOAD_SET, set.name)
            MenuItem(
                "${set.label.ifBlank { set.name }}  ${phaseCount(set)}",
                "menu-run-load-set-${set.name}",
                { run.select(configuration) },
                enabled = !run.busy,
                checked = run.selected == configuration,
                choice = true,
            )
        }
    val runSets =
        run.runSets.map { set ->
            val configuration = RunConfiguration(RunConfiguration.Kind.RUN_SET, set.name)
            MenuItem(
                "${set.name}  ${scenarioCount(set)}",
                "menu-run-run-set-${set.name}",
                { run.select(configuration) },
                enabled = !run.busy,
                checked = run.selected == configuration,
                choice = true,
            )
        }
    return AppMenu(
        title = "Run",
        rows =
            listOf(
                MenuItem(run.actLabel, "menu-run", run::act, chord = Shortcuts.RUN, enabled = run.canAct),
                MenuSeparator("menu-run-rule-1"),
                MenuItem(
                    loadRunRow(run.lanes),
                    "menu-run-load",
                    run.doors::openLoadRun,
                    enabled = !run.busy && run.issuers > 0,
                ),
                Submenu("Load set", "menu-run-load-set", loadSets),
                Submenu("Run set", "menu-run-run-set", runSets),
                MenuItem(
                    loadSetsRow(run.loadSets.size),
                    "menu-run-load-sets",
                    run.doors::openLoadSets,
                    enabled = !run.busy,
                ),
                MenuSeparator("menu-run-rule-2"),
                Submenu(
                    "Recent",
                    "menu-run-recent",
                    run.recent.map { record ->
                        MenuItem(record.line, "menu-run-recent-${record.id}", { run.openRecent(record) })
                    },
                ),
            ),
    )
}

/** How every pane draws: the toolbar's layout segments and its View ▾, row for row. */
@Composable
private fun viewMenu(
    viewModel: FixMessageViewModel,
    layout: ViewMode,
    onLayoutChange: (ViewMode) -> Unit,
): AppMenu {
    val rows by viewModel.viewMode.collectAsState()
    val grouped = anySessionGrouped(viewModel)
    // A flip of the all-sessions row mode, so each choice flips only when it is not already in force.
    val showRows: (FixMessageSession.ViewMode) -> Unit = { mode ->
        if (viewModel.viewMode.value != mode) viewModel.toggleViewMode()
    }
    return AppMenu(
        title = "View",
        rows =
            listOf(
                Submenu(
                    "Layout",
                    "menu-view-layout",
                    ViewMode.entries.map { mode ->
                        MenuItem(
                            mode.tooltip,
                            "menu-view-${mode.testTag}",
                            { onLayoutChange(mode) },
                            checked = mode == layout,
                            choice = true,
                        )
                    },
                ),
                MenuSeparator("menu-view-rule-1"),
                MenuItem(
                    PARSED_ROWS_LABEL,
                    "menu-view-parsed",
                    { showRows(FixMessageSession.ViewMode.PARSED) },
                    checked = rows == FixMessageSession.ViewMode.PARSED,
                    choice = true,
                ),
                MenuItem(
                    RAW_ROWS_LABEL,
                    "menu-view-raw",
                    { showRows(FixMessageSession.ViewMode.RAW) },
                    checked = rows == FixMessageSession.ViewMode.RAW,
                    choice = true,
                ),
                MenuSeparator("menu-view-rule-2"),
                MenuItem(
                    HIDE_PROTOCOL_TAGS_LABEL,
                    "menu-view-hide-tags",
                    { viewModel.toggleHideProtocolTags() },
                    chord = Shortcuts.HIDE_PROTOCOL_TAGS,
                    checked = viewModel.appSettings.hideProtocolTags,
                ),
                MenuItem(
                    GROUP_LABEL,
                    "menu-view-group",
                    { viewModel.toggleGroupByConversationAllSessions() },
                    checked = grouped,
                ),
            ),
    )
}

/**
 * **Whether any pane is grouped by conversation** — what View's tick and the toolbar's View ▾ both show.
 *
 * One collector over the combined flows, not `sessions.map { it.flow.collectAsState() }`: that calls a
 * composable inside a loop over a mutable list, so every add or remove shifted the slots. Keyed on the
 * sessions themselves, so the combination is rebuilt exactly when the set of flows to combine changes.
 */
@Composable
internal fun anySessionGrouped(viewModel: FixMessageViewModel): Boolean {
    val identities = viewModel.sessions.joinToString(",") { it.id }
    val grouped by remember(identities) {
        val flows = viewModel.sessions.map { session -> session.groupByConversation }
        if (flows.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(false)
        } else {
            kotlinx.coroutines.flow.combine(flows) { flags -> flags.any { on -> on } }
        }
    }.collectAsState(initial = viewModel.anySessionGroupedByConversation())
    return grouped
}

/**
 * **The stripes, as a list**: every tool window with the digit that opens it, ticked while it is open.
 *
 * Documents is greyed while no document is open, which is when its stripe tab is not drawn at all.
 */
@Composable
private fun windowMenu(viewModel: FixMessageViewModel): AppMenu {
    val left by viewModel.leftWindow.collectAsState()
    val right by viewModel.rightWindow.collectAsState()
    val bottom by viewModel.bottomTab.collectAsState()
    val documents by viewModel.openDocuments.collectAsState()
    val stowed by viewModel.stowedToolWindows.collectAsState()
    val open = openToolWindows(left, right, bottom)

    val windows =
        ToolWindow.entries.map { window ->
            MenuItem(
                window.title,
                window.testTag.replace("tool-window", "menu-window"),
                { viewModel.toggle(window) },
                chord = window.chord,
                enabled = window != ToolWindow.DOCUMENTS || documents.isNotEmpty(),
                checked = window in open,
            )
        }
    return AppMenu(
        title = "Window",
        rows =
            windows + MenuSeparator("menu-window-rule") +
                MenuItem(
                    "Hide all tool windows",
                    "menu-window-hide-all",
                    { viewModel.toggleAllToolWindows() },
                    chord = Shortcuts.HIDE_ALL_TOOL_WINDOWS,
                    // Pressed again with everything hidden, it puts back what it hid — so it is live then too.
                    enabled = open.isNotEmpty() || stowed != null,
                ),
    )
}

private fun helpMenu(viewModel: FixMessageViewModel): AppMenu =
    AppMenu(
        title = "Help",
        rows =
            listOf(
                MenuItem(WindowAction.HELP.label, "menu-help", { viewModel.openHelp() }),
                MenuItem(AUTOMATION_REFERENCE_LABEL, "menu-help-automation", { viewModel.openHelp("automation") }),
            ),
    )

private fun plural(count: Int): String = if (count == 1) "" else "s"

/**
 * Show [folder] the way this platform shows a folder: selected in its parent in Finder, opened elsewhere.
 *
 * Quietly nothing where the desktop cannot do either, which is a headless box — and a headless box has no menu
 * bar to have chosen this from.
 */
private fun revealFolder(folder: File) {
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    runCatching {
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            desktop.browseFileDirectory(folder)
        } else {
            desktop.open(folder)
        }
    }
}
