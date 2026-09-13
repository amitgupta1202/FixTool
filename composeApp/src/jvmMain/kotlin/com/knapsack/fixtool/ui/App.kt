package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knapsack.fixtool.model.EditorTarget
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.ReplyShape
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.ui.settings.WorkspaceSettings
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.knapsack.fixtool.ui.App")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App(
    modifier: Modifier = Modifier,
    onViewModelCreated: (FixMessageViewModel) -> Unit = {},
    /** The window's catalogue of actions, which the menu bar draws and whose shortcuts the window answers. */
    menus: AppMenuState = remember { AppMenuState() },
    /** Draws [menus] as the window's menu bar. Null where there is no window to hang one on, as in a test. */
    menuBar: (@Composable (AppMenuState) -> Unit)? = null,
    /** What the menu's Quit does: the window's own close, which logs every session out first. */
    onQuit: () -> Unit = {},
) {
    val arming = rememberWindowArming()
    CompositionLocalProvider(LocalWindowArming provides arming) {
        AppContent(modifier, onViewModelCreated, menus, menuBar, onQuit)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun AppContent(
    modifier: Modifier,
    onViewModelCreated: (FixMessageViewModel) -> Unit,
    menus: AppMenuState,
    menuBar: (@Composable (AppMenuState) -> Unit)?,
    onQuit: () -> Unit,
) {
    FixToolWindowChrome {
        val viewModel: FixMessageViewModel = viewModel { FixMessageViewModel() }

        // Expose viewModel reference to parent
        LaunchedEffect(viewModel) {
            onViewModelCreated(viewModel)
        }

        // Bring the Kotlin script engine up now, on a background thread, rather than leaving the
        // first template expression to pay for it. That first eval costs ~1.4s and would
        // otherwise freeze the window mid-use. Dispatchers.Default keeps it off the EDT, so it
        // overlaps the rest of startup instead of blocking the first frame.
        LaunchedEffect(Unit) {
            withContext(Dispatchers.Default) { FixMessageTemplate.warmUp() }
        }

        // Initialize layout from settings
        val initialLayout =
            when (viewModel.appSettings.defaultLayout) {
                "tabs" -> ViewMode.TABS
                "vertical" -> ViewMode.SPLIT_VERTICAL
                else -> ViewMode.SPLIT_HORIZONTAL
            }
        var viewMode by rememberSaveable { mutableStateOf(initialLayout) }

        // Collect global state
        val selectedMessage by viewModel.selectedMessage.collectAsState()
        val showDetailPanel by viewModel.showDetailPanel.collectAsState()
        val showMessageEditor by viewModel.showMessageEditor.collectAsState()
        val showConnectionPanel by viewModel.showConnectionPanel.collectAsState()
        val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
        val showHelpDialog by viewModel.showHelpDialog.collectAsState()
        val showGlobalSearchDialog by viewModel.showGlobalSearchDialog.collectAsState()
        val globalSearchQuery by viewModel.globalSearchQuery.collectAsState()
        val globalSearchResults by viewModel.globalSearchResults.collectAsState()
        val isDictionaryValid by viewModel.isDictionaryValid.collectAsState()
        // Native folder dialogs suspend, and the Open workspace item is a menu click.
        val workspaceScope = rememberCoroutineScope()
        val browseForWorkspace = {
            workspaceScope.launch {
                chooseDirectory(title = "Open workspace", startIn = viewModel.defaultWorkspaceLocation())
                    ?.let { folder -> viewModel.openWorkspace(folder) }
            }
            Unit
        }
        // One object for the switcher, so the empty state below offers exactly the same things.
        val workspaceMenu =
            WorkspaceMenuState(
                name = viewModel.openWorkspaceName,
                isDefault = viewModel.openWorkspaceIsHome,
                recents = viewModel.recentWorkspaces,
                onNew = { viewModel.requestNewWorkspace() },
                onBrowse = browseForWorkspace,
                onOpenRecent = { viewModel.openWorkspace(it) },
                onClose = { viewModel.closeWorkspace() },
            )
        val savedMessages = viewModel.savedMessages
        val editorState by viewModel.editorState.collectAsState()
        val currentProfileId = viewModel.getCurrentProfileId()
        val notifications = viewModel.notifications
        val density = LocalDensity.current
        val globalViewMode by viewModel.viewMode.collectAsState()
        val globalFilterRegex by viewModel.globalFilterRegex.collectAsState()
        val globalFilterShowIncoming by viewModel.globalFilterShowIncoming.collectAsState()
        val globalFilterShowOutgoing by viewModel.globalFilterShowOutgoing.collectAsState()

        /**
         * The toolbar's filter as one value, so both layouts hand [MessageFilters] the same thing.
         * It is ANDed into every pane and written into none of them — see [MessageFilters].
         */
        val globalFilter =
            remember(globalFilterRegex, globalFilterShowIncoming, globalFilterShowOutgoing) {
                MessageFilters.Global(globalFilterRegex, globalFilterShowIncoming, globalFilterShowOutgoing)
            }
        val followedTrace by viewModel.followedTrace.collectAsState()
        val followedTraceIndex by viewModel.traceIndex.collectAsState()

        /**
         * The whole question the filter asks of every pane, as the toolbar draws it.
         *
         * One value rather than three, because the regex, the two directions and the followed trace are
         * one query. It is on the toolbar at all times, which is what keeps it from going silent. See
         * [ToolbarFilter].
         */
        val filterQuery =
            remember(globalFilter, followedTrace) {
                FilterQuery(
                    global = globalFilter,
                    followingLabel = followedTrace?.label,
                    followingSessionCount = followedTrace?.sessionCount ?: 0,
                    followingMessageCount = followedTrace?.messageCount ?: 0,
                    followingTruncatedOn = followedTrace?.truncatedSessionTitles.orEmpty(),
                )
            }
        // Null, not empty, when nothing is followed: an empty set would narrow every pane to nothing,
        // and "following an id that has not arrived yet" is a state this app deliberately holds.
        val followedUids = followedTrace?.uids
        val followedTraceIds =
            remember(followedTrace, followedTraceIndex) {
                val anchor = followedTrace?.anchorId
                followedTraceIndex
                    ?.grouping
                    ?.traces
                    ?.firstOrNull { anchor != null && anchor in it.ids }
                    ?.ids
                    .orEmpty()
            }

        val showLatencyPanel by viewModel.showLatencyPanel.collectAsState()
        val showOrderBookPanel by viewModel.showOrderBookPanel.collectAsState()
        val showScenariosRail by viewModel.showScenariosRail.collectAsState()

        // Whether any pane is grouped by conversation — what View ▾'s tick shows. See [anySessionGrouped].
        val anySessionGrouped = anySessionGrouped(viewModel)
        // Documents live in the bottom dock now (see BottomDock), not in the session centre, so the
        // layout no longer tracks the active document or its tabs at this level.

        // Load saved messages when active session changes
        LaunchedEffect(viewModel.activeSessionIndex) {
            viewModel.loadSavedMessagesForActiveSession()
        }

        // Add shutdown hook to disconnect all sessions on app close/crash
        DisposableEffect(Unit) {
            val shutdownHook =
                Thread {
                    viewModel.disconnectAllSessions()
                }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            onDispose {
                // Clean up sessions when app window closes
                viewModel.disconnectAllSessions()

                // Remove shutdown hook to avoid duplicate cleanup
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook)
                } catch (e: IllegalStateException) {
                    // Shutdown in progress, hook already executing
                }
            }
        }

        // Panel sizes are seeded from the persisted layout (defaults on first run) and written back on drag
        // end via viewModel.updateLayout — so a resize survives a restart. Held locally so the drag itself
        // is smooth; only the release touches settings. See ResizeHandle and LayoutState.
        val savedLayout = remember { viewModel.layoutState.value }
        var scenariosRailSplitRatio by remember { mutableStateOf(savedLayout.railRatio) }
        var detailPanelSplitRatio by remember { mutableStateOf(savedLayout.detailRatio) }
        var editorPanelSplitRatio by remember { mutableStateOf(savedLayout.editorRatio) }
        var connectionPanelSplitRatio by remember { mutableStateOf(savedLayout.connectionRatio) }
        var latencyPanelSplitRatio by remember { mutableStateOf(savedLayout.latencyRatio) }
        var orderBookSplitRatio by remember { mutableStateOf(savedLayout.orderBookRatio) }

        val leftWindow by viewModel.leftWindow.collectAsState()
        val rightWindow by viewModel.rightWindow.collectAsState()
        val bottomTab by viewModel.bottomTab.collectAsState()
        val openDocuments by viewModel.openDocuments.collectAsState()

        // What draws a stripe tab pressed, and what the Window menu ticks. See [openToolWindows].
        val openToolWindows = openToolWindows(leftWindow, rightWindow, bottomTab)
        // One handler for the tab and for its ⌘ digit, so the two doors to a window cannot drift apart.
        val onToggleToolWindow: (ToolWindow) -> Unit = { window -> viewModel.toggle(window) }

        val onViewModeChange: (ViewMode) -> Unit = { mode ->
            viewMode = mode
            // Persist through defaultLayout — the field that already seeds the initial layout.
            viewModel.persistViewMode(
                when (mode) {
                    ViewMode.TABS -> "tabs"
                    ViewMode.SPLIT_VERTICAL -> "vertical"
                    ViewMode.SPLIT_HORIZONTAL -> "horizontal"
                },
            )
        }

        // The run widget's dialogs, one set for the window: the toolbar's chip and the menu bar's Run menu
        // both open them. See [RunDoors].
        val runDoors = remember { RunDoors() }

        // **The menu bar's catalogue**, built from what the toolbar, the stripes and the pane header read, in a
        // composable of its own so what it reads recomposes it and not this window. See [PublishAppMenus].
        PublishAppMenus(
            state = menus,
            viewModel = viewModel,
            layout = viewMode,
            onLayoutChange = onViewModeChange,
            runDoors = runDoors,
            workspace = workspaceMenu,
            onQuit = onQuit,
        )
        menuBar?.invoke(menus)

        // **Esc stops following**, and it is the one key the window answers that is no menu row's. It is asked
        // after everything that has focus, so anything that wants Esc has already had it and consumed it — the
        // grid clears its multi-selection, the saved-messages popup closes itself. The dialogs are named rather
        // than trusted to consume, because they draw over the whole window. See [AppMenuState.answer].
        SideEffect {
            menus.unclaimed = { event ->
                val stopsFollowing =
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Escape &&
                        followedTrace != null &&
                        !(showSettingsDialog || showHelpDialog || showGlobalSearchDialog)
                if (stopsFollowing) viewModel.unfollow()
                stopsFollowing
            }
        }

        // A slot on the toolbar rather than a dozen more parameters, and `folded` comes from the toolbar,
        // which is the only thing that knows how much room the row has left. See [PaneViewControls].
        val paneViewControls: @Composable (Boolean) -> Unit = { folded ->
            PaneViewControls(
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
                sessionViewMode = globalViewMode,
                onToggleGridView = { viewModel.toggleViewMode() },
                hideProtocolTags = viewModel.appSettings.hideProtocolTags,
                onToggleHideProtocolTags = { viewModel.toggleHideProtocolTags() },
                groupByConversation = anySessionGrouped,
                onToggleGroupByConversation = { viewModel.toggleGroupByConversationAllSessions() },
                folded = folded,
            )
        }

        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E)),
            ) {
                Toolbar(
                    connectionProfiles = viewModel.connectionProfiles,
                    isDictionaryValid = isDictionaryValid,
                    onQuickConnect = { profileId, profile ->
                        viewModel.connectProfile(profileId, profile)
                    },
                    onGetProfileConnectionState = { profileId ->
                        viewModel.getProfileConnectionState(profileId)
                    },
                    workspace = workspaceMenu,
                    environments = viewModel.environments,
                    onConnectProfileIn = { profile, environment -> viewModel.connectProfileIn(profile, environment) },
                    filter = filterQuery,
                    onFilterRegexChange = { regex -> viewModel.setGlobalFilterRegex(regex) },
                    onFilterIncomingChange = { show -> viewModel.setGlobalFilterShowIncoming(show) },
                    onFilterOutgoingChange = { show -> viewModel.setGlobalFilterShowOutgoing(show) },
                    onUnfollow = { viewModel.unfollow() },
                    onSearchAllSessions = { viewModel.toggleGlobalSearchDialog() },
                    onAddSeparatorToAll = { viewModel.addSeparatorToAllSessions() },
                    onClearAll = { viewModel.clearAllSessions() },
                    onOpenSettings = { viewModel.toggleSettingsDialog() },
                    onOpenHelp = { viewModel.toggleHelpDialog() },
                    onCaptureScenario = { viewModel.captureAllSessionsToEditor() },
                    sessionControls = { words -> ToolbarSessionControls(viewModel, words = words) },
                    runConfiguration = { fold ->
                        ToolbarRunConfiguration(viewModel, rememberRunChoice(viewModel, runDoors), fold)
                    },
                    viewControls = paneViewControls,
                )

                // The load run dialog, opened from the editor's Load button with the editor's fields as the template.
                val loadTemplate by viewModel.loadDialogTemplate.collectAsState()
                loadTemplate?.let { template ->
                    LoadRunDialog(
                        viewModel = viewModel,
                        fixedTemplate = template,
                        onDismiss = { viewModel.dismissLoadDialog() },
                        onRun = { plan ->
                            viewModel.dismissLoadDialog()
                            viewModel.startLoadRun(plan)
                        },
                    )
                }

                // Settings Dialog
                if (showSettingsDialog) {
                    SettingsDialog(
                        currentSettings = viewModel.appSettings,
                        dictionary = viewModel.dictionary,
                        onSave = { settings -> viewModel.saveAppSettings(settings) },
                        onDismiss = { viewModel.toggleSettingsDialog() },
                        workspace =
                            WorkspaceSettings(
                                folder = viewModel.openWorkspace.absolutePath,
                                name = viewModel.openWorkspaceName,
                                isDefault = viewModel.openWorkspaceIsHome,
                                onOpen = {
                                    workspaceScope.launch {
                                        chooseDirectory(title = "Open workspace", startIn = viewModel.openWorkspace)
                                            ?.let { folder -> viewModel.openWorkspace(folder) }
                                    }
                                },
                                onClose = { viewModel.closeWorkspace() },
                                environments = viewModel.environments,
                                environmentProposal = viewModel.proposeEnvironments().takeIf { it.isWorthDoing },
                                onExtractEnvironments = {
                                    viewModel.adoptEnvironments(viewModel.proposeEnvironments())
                                },
                                exampleName = viewModel.openWorkspaceExample()?.displayName.orEmpty(),
                                onResetExample = { viewModel.resetOpenExample() },
                            ),
                    )
                }

                // New workspace: an empty folder of its own, named and placed.
                if (viewModel.creatingWorkspace) {
                    NewWorkspaceDialog(
                        defaultLocation = viewModel.defaultWorkspaceLocation(),
                        wireVersionNote = viewModel.wireVersionNote(),
                        onDismiss = { viewModel.dismissNewWorkspace() },
                        onCreate = { name, location -> viewModel.createWorkspace(name, location) },
                    )
                }

                // Help Dialog
                if (showHelpDialog) {
                    HelpDialog(
                        onClose = { viewModel.toggleHelpDialog() },
                        anchor = viewModel.helpAnchor.collectAsState().value,
                    )
                }

                // Global Search Dialog
                if (showGlobalSearchDialog) {
                    SearchAllSessionsDialog(
                        searchQuery = globalSearchQuery,
                        searchResults = globalSearchResults,
                        onQueryChange = { query -> viewModel.setGlobalSearchQuery(query) },
                        onResultClick = { result -> viewModel.navigateToSearchResult(result) },
                        onPinResults = { viewModel.pinSearchResults() },
                        onDismiss = { viewModel.toggleGlobalSearchDialog() },
                    )
                }

                // **The window's main row: a stripe, the content, a stripe.** Both stripes run the full
                // content height, and the centre column is the session area over the one bottom dock, so
                // the dock spans the whole width between the stripes and the side windows end above it,
                // which is IntelliJ's default arrangement.
                Row(modifier = Modifier.weight(1f)) {
                    ToolWindowStripe(
                        side = StripeSide.LEFT,
                        open = openToolWindows,
                        onToggle = onToggleToolWindow,
                        documentsOpen = openDocuments.isNotEmpty(),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        when (viewMode) {
                            ViewMode.TABS -> {
                                // All panels in same row: scenarios rail, editor, tabs, detail
                                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                                    val maxWidthPx = with(density) { maxWidth.toPx() }
                                    // A dock is never dragged narrower than its own folded header, so no width exists at
                                    // which one of its controls is half drawn. The ratio floors are still what decides on a
                                    // wide window, where a tenth of the width is far more than a header needs; the folded
                                    // header takes over on a window narrow enough that a tenth of it is not a header at all.
                                    val dockFloor = dockFloorRatio(0.1f, maxWidthPx, density)
                                    val bookFloor = dockFloorRatio(0.15f, maxWidthPx, density)

                                    Row(modifier = Modifier.fillMaxSize()) {
                                        ScenariosRailDock(
                                            viewModel = viewModel,
                                            show = showScenariosRail,
                                            ratio = scenariosRailSplitRatio,
                                            maxWidthPx = maxWidthPx,
                                            onDeltaPx = { dx ->
                                                scenariosRailSplitRatio = (scenariosRailSplitRatio + dx / maxWidthPx).coerceIn(dockFloor, 0.45f)
                                            },
                                            onDragEnd = { viewModel.updateLayout { it.copy(railRatio = scenariosRailSplitRatio) } },
                                        )

                                        // Leftmost panel - Message editor (if shown)
                                        if (showMessageEditor) {
                                            Box(
                                                modifier =
                                                    Modifier.width(
                                                        with(density) { (maxWidthPx * editorPanelSplitRatio).toDp() },
                                                    ),
                                            ) {
                                                AppMessageEditorPanel(
                                                    viewModel = viewModel,
                                                    savedMessages = savedMessages,
                                                    currentProfileId = currentProfileId,
                                                    editorState = editorState,
                                                    editorPanelSplitRatio = editorPanelSplitRatio,
                                                    onEditorPanelSplitRatioChange = { editorPanelSplitRatio = it },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }

                                            // Resizable divider for editor panel
                                            WidthResizeHandle(
                                                onDeltaPx = { dx ->
                                                    editorPanelSplitRatio = (editorPanelSplitRatio + dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(editorRatio = editorPanelSplitRatio) } },
                                            )
                                        }

                                        // Center panel - Tabs and Message display
                                        Column(modifier = Modifier.weight(1f)) {
                                            var isAtBottom by remember { mutableStateOf(true) }
                                            var scrollToBottomTrigger by remember { mutableStateOf(0) }

                                            TabBar(
                                                sessions = viewModel.sessions,
                                                activeSession = viewModel.activeSession,
                                                viewMode = globalViewMode,
                                                onTabClick = { session -> viewModel.setActiveSessionByObject(session) },
                                                onCloseTab = { session -> viewModel.closeSession(session) },
                                                onToggleWrapText = { session -> session.toggleWrapText() },
                                                onConnect = { session -> session.reconnect() },
                                                onDisconnect = { session -> session.disconnect() },
                                                onMinimize = { session, on -> viewModel.setSessionMinimized(session, on) },
                                                onEditVenueRules = { session -> viewModel.openVenueRules(session) },
                                                isAtBottom = isAtBottom,
                                                onScrollToBottom = { scrollToBottomTrigger++ },
                                            )

                                            // The centre is always the sessions now — the scenario editor is a
                                            // bottom dock (see BottomDock), not a pane that replaces the grid.
                                            viewModel.activeSession?.let { session ->
                                                // Minimizing does not move the editor's target — silently
                                                // pointing a loaded order at a different counterparty is how a
                                                // tester sends to the wrong venue. So the active session can be
                                                // one that is in the strip, and the centre says so instead of
                                                // drawing a pane that is not there.
                                                val activeMinimized by session.minimized.collectAsState()
                                                if (activeMinimized) {
                                                    Box(
                                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = "${session.title} is minimized. Click its chip above to bring it back.",
                                                            color = AppTheme.Colors.textDisabled,
                                                            fontSize = 12.sp,
                                                        )
                                                    }
                                                    return@let
                                                }

                                                val messages by session.messages.collectAsState()
                                                val wrapText by session.wrapText.collectAsState()
                                                val recentlySentMessageTimestamp by session.recentlySentMessageTimestamp.collectAsState()
                                                val latencyTrackingEnabled by session.latencyTrackingEnabled.collectAsState()

                                                if (session.isVenue) {
                                                    // Nothing to grid: a venue's traffic all belongs to its
                                                    // clients, and each of them has a tab.
                                                    AcceptorOverviewPane(
                                                        venue = session,
                                                        clients = viewModel.sessions.filter { it.isClientOf(session) },
                                                        onFocusClient = { client -> viewModel.setActiveSessionByObject(client) },
                                                        onEditRules = { viewModel.openVenueRules(session) },
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    return@let
                                                }

                                                // The TABS layout filters now. Its filter button toggled this
                                                // panel and the grid below never applied it, so a pane filtered
                                                // in split view and not in tabs — one function decides both now
                                                // (see MessageFilters and SessionFilterBar).
                                                val filterVisible by session.filterVisible.collectAsState()
                                                if (filterVisible) SessionFilterBar(session)
                                                val paneFilters =
                                                    MessageFilters.Pane(
                                                        regex = session.filterRegex.collectAsState().value,
                                                        showIncoming = session.filterShowIncoming.collectAsState().value,
                                                        showOutgoing = session.filterShowOutgoing.collectAsState().value,
                                                        showSeparator = session.filterShowSeparator.collectAsState().value,
                                                        messageTypes = session.filterMessageTypes.collectAsState().value,
                                                    )
                                                val filteredMessages =
                                                    remember(messages, paneFilters, globalFilter, followedUids) {
                                                        MessageFilters.apply(messages, paneFilters, globalFilter, followedUids)
                                                    }

                                                FixMessageDisplay(
                                                    messages = filteredMessages,
                                                    viewMode = globalViewMode,
                                                    dictionary = viewModel.dictionary,
                                                    wrapText = wrapText,
                                                    selectedMessage = selectedMessage,
                                                    recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                                                    assertionResults = viewModel.assertionResults,
                                                    onSelectMessage = { m -> viewModel.selectMessageFromGrid(m) },
                                                    onDiffSelected = { a, b -> viewModel.openDiffSelected(a, b) },
                                                    showDetailPanel = false,
                                                    // The tab strip's Search in pane toggles this, and the tabs
                                                    // layout used to be the one place nothing read it.
                                                    searchVisible = session.searchVisible.collectAsState().value,
                                                    onToggleSearch = { session.toggleSearch() },
                                                    hideProtocolTags = viewModel.appSettings.hideProtocolTags,
                                                    gridViewColumns = viewModel.appSettings.gridViewColumns,
                                                    appSettings = viewModel.appSettings,
                                                    showLatencyColumn = latencyTrackingEnabled && viewModel.appSettings.showLatencyColumn,
                                                    getLatencyForMessage =
                                                        if (latencyTrackingEnabled) {
                                                            { rawMessage ->
                                                                session.getLatencyForMessage(rawMessage)
                                                            }
                                                        } else {
                                                            null
                                                        },
                                                    latencyWarningThresholdMicros = viewModel.appSettings.latencyWarningThresholdMicros,
                                                    latencyCriticalThresholdMicros = viewModel.appSettings.latencyCriticalThresholdMicros,
                                                    onAtBottomChanged = { isAtBottom = it },
                                                    scrollToBottomTrigger = scrollToBottomTrigger,
                                                    groupByConversation = session.groupByConversation.collectAsState().value,
                                                    collapsedConversations = session.collapsedConversations.collectAsState().value,
                                                    onToggleConversation = { key -> session.toggleConversationCollapsed(key) },
                                                    followedTraceIds = followedTraceIds,
                                                    onFollowTrace = { id -> viewModel.follow(id) },
                                                    onUnfollowTrace = { viewModel.unfollow() },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            } ?: NoSessionsPlaceholder(
                                                hasProfiles = viewModel.connectionProfiles.isNotEmpty(),
                                                onOpenWorkspace = browseForWorkspace,
                                                onOpenConnectionPanel = { if (!showConnectionPanel) viewModel.toggleConnectionPanel() },
                                                modifier = Modifier.weight(1f).fillMaxSize(),
                                            )
                                        }

                                        // Message detail panel (if shown)
                                        if (showDetailPanel) {
                                            // Resizable divider for detail panel
                                            WidthResizeHandle(
                                                onDeltaPx = { dx ->
                                                    detailPanelSplitRatio = (detailPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(detailRatio = detailPanelSplitRatio) } },
                                            )

                                            Box(
                                                modifier =
                                                    Modifier.width(
                                                        with(density) { (maxWidthPx * detailPanelSplitRatio).toDp() },
                                                    ),
                                            ) {
                                                AppMessageDetailPanel(
                                                    viewModel = viewModel,
                                                    selectedMessage = selectedMessage,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }

                                        // Rightmost panel - Connection panel (if shown)
                                        if (showConnectionPanel) {
                                            // Resizable divider for connection panel
                                            WidthResizeHandle(
                                                onDeltaPx = { dx ->
                                                    connectionPanelSplitRatio =
                                                        (connectionPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(connectionRatio = connectionPanelSplitRatio) } },
                                            )

                                            Box(
                                                modifier =
                                                    Modifier.width(
                                                        with(density) { (maxWidthPx * connectionPanelSplitRatio).toDp() },
                                                    ),
                                            ) {
                                                ConnectionPanel(
                                                    profiles = viewModel.connectionProfiles,
                                                    sessions = viewModel.sessions,
                                                    onConnect = { profileId, profile ->
                                                        viewModel.connectProfile(
                                                            profileId,
                                                            profile,
                                                        )
                                                    },
                                                    onDisconnect = { profileId -> viewModel.disconnectProfile(profileId) },
                                                    onSaveProfile = { profile -> viewModel.saveConnectionProfile(profile) },
                                                    onDeleteProfile = { profileId ->
                                                        viewModel.deleteConnectionProfile(profileId)
                                                    },
                                                    onCloneProfile = { profile -> viewModel.cloneConnectionProfile(profile) },
                                                    onGetProfileSession = { profileId ->
                                                        viewModel.getProfileSession(profileId)
                                                    },
                                                    onGetProfileSessions = { profileId ->
                                                        viewModel.getProfileSessions(profileId)
                                                    },
                                                    onClose = { viewModel.toggleConnectionPanel() },
                                                    selectionRequest = viewModel.connectionPanelSelection.collectAsState().value,
                                                    rulesExpandRequest = viewModel.rulesExpandRequest.collectAsState().value,
                                                    onRulesExpandConsumed = { viewModel.consumeRulesExpandRequest() },
                                                    dictionary = viewModel.dictionary,
                                                    onOpenReplyStepInEditor = { profileId, ruleIndex, stepIndex, template ->
                                                        viewModel.openReplyStep(profileId, ruleIndex, stepIndex, template)
                                                    },
                                                    replyStepApply = viewModel.pendingReplyStepApply,
                                                    onReplyStepConsumed = { viewModel.consumeReplyStepApply() },
                                                    editingReplyStep = viewModel.editorTarget as? EditorTarget.ReplyStep,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }

                                        // The venue's own memory, beside the counterparty's messages.
                                        if (showOrderBookPanel) {
                                            WidthResizeHandle(
                                                onDeltaPx = { dx ->
                                                    orderBookSplitRatio = (orderBookSplitRatio - dx / maxWidthPx).coerceIn(bookFloor, 0.7f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(orderBookRatio = orderBookSplitRatio) } },
                                            )

                                            Box(modifier = Modifier.width(with(density) { (maxWidthPx * orderBookSplitRatio).toDp() })) {
                                                AppOrderBookPanel(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                            }
                                        }

                                        // Latency panel (if shown)
                                        if (showLatencyPanel) {
                                            // Resizable divider for latency panel
                                            WidthResizeHandle(
                                                onDeltaPx = { dx ->
                                                    latencyPanelSplitRatio = (latencyPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.5f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(latencyRatio = latencyPanelSplitRatio) } },
                                            )

                                            Box(
                                                modifier =
                                                    Modifier.width(
                                                        with(density) { (maxWidthPx * latencyPanelSplitRatio).toDp() },
                                                    ),
                                            ) {
                                                viewModel.activeSession?.let { session ->
                                                    val latencyTrackingService = session.getLatencyTrackingService()

                                                    if (latencyTrackingService != null) {
                                                        val statistics by latencyTrackingService.statistics.collectAsState()
                                                        val aggregateStatistics by latencyTrackingService.aggregateStatistics.collectAsState()
                                                        val recentPairs by latencyTrackingService.recentPairs.collectAsState()

                                                        LatencyPanel(
                                                            statistics = statistics,
                                                            aggregateStatistics = aggregateStatistics,
                                                            recentPairs = recentPairs,
                                                            warningThresholdMicros = viewModel.appSettings.latencyWarningThresholdMicros,
                                                            criticalThresholdMicros = viewModel.appSettings.latencyCriticalThresholdMicros,
                                                            onClear = { session.clearLatencyStatistics() },
                                                            onClose = { viewModel.toggleLatencyPanel() },
                                                            modifier = Modifier.fillMaxSize(),
                                                        )
                                                    } else {
                                                        // Latency tracking not enabled for this session
                                                        Box(
                                                            modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Text(
                                                                text = "Latency tracking not enabled.\nEnable it in Settings and reconnect.",
                                                                color = AppTheme.Colors.textDisabled,
                                                                fontSize = 12.sp,
                                                            )
                                                        }
                                                    }
                                                } ?: Box(
                                                    modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = "No active session",
                                                        color = AppTheme.Colors.textDisabled,
                                                        fontSize = 12.sp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ViewMode.SPLIT_HORIZONTAL, ViewMode.SPLIT_VERTICAL -> {
                                val splitOrientation =
                                    if (viewMode == ViewMode.SPLIT_HORIZONTAL) {
                                        SplitOrientation.HORIZONTAL
                                    } else {
                                        SplitOrientation.VERTICAL
                                    }

                                // Wrap content in split pane if the rail, detail panel, message editor, connection panel, or latency panel is shown
                                if (showScenariosRail ||
                                    showDetailPanel ||
                                    showMessageEditor ||
                                    showConnectionPanel ||
                                    showLatencyPanel ||
                                    showOrderBookPanel
                                ) {
                                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                                        val maxWidthPx = with(density) { maxWidth.toPx() }
                                        // A dock is never dragged narrower than its own folded header, so no width exists at
                                        // which one of its controls is half drawn. The ratio floors are still what decides on a
                                        // wide window, where a tenth of the width is far more than a header needs; the folded
                                        // header takes over on a window narrow enough that a tenth of it is not a header at all.
                                        val dockFloor = dockFloorRatio(0.1f, maxWidthPx, density)
                                        val bookFloor = dockFloorRatio(0.15f, maxWidthPx, density)

                                        Row(modifier = Modifier.fillMaxSize()) {
                                            ScenariosRailDock(
                                                viewModel = viewModel,
                                                show = showScenariosRail,
                                                ratio = scenariosRailSplitRatio,
                                                maxWidthPx = maxWidthPx,
                                                onDeltaPx = { dx ->
                                                    scenariosRailSplitRatio = (scenariosRailSplitRatio + dx / maxWidthPx).coerceIn(dockFloor, 0.45f)
                                                },
                                                onDragEnd = { viewModel.updateLayout { it.copy(railRatio = scenariosRailSplitRatio) } },
                                            )

                                            // Leftmost panel - Message editor (if shown)
                                            if (showMessageEditor) {
                                                Box(
                                                    modifier =
                                                        Modifier.width(
                                                            with(density) { (maxWidthPx * editorPanelSplitRatio).toDp() },
                                                        ),
                                                ) {
                                                    AppMessageEditorPanel(
                                                        viewModel = viewModel,
                                                        savedMessages = savedMessages,
                                                        currentProfileId = currentProfileId,
                                                        editorState = editorState,
                                                        editorPanelSplitRatio = editorPanelSplitRatio,
                                                        onEditorPanelSplitRatioChange = { editorPanelSplitRatio = it },
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }

                                                // Resizable divider for editor panel
                                                WidthResizeHandle(
                                                    onDeltaPx = { dx ->
                                                        editorPanelSplitRatio = (editorPanelSplitRatio + dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                    },
                                                    onDragEnd = { viewModel.updateLayout { it.copy(editorRatio = editorPanelSplitRatio) } },
                                                )
                                            }

                                            // Center panel - the sessions (the scenario editor is the bottom dock now)
                                            Column(modifier = Modifier.weight(1f)) {
                                                SplitCentre(
                                                    viewModel = viewModel,
                                                    orientation = splitOrientation,
                                                    globalViewMode = globalViewMode,
                                                    selectedMessage = selectedMessage,
                                                    globalFilter = globalFilter,
                                                    followedUids = followedUids,
                                                    followedTraceIds = followedTraceIds,
                                                )
                                            }

                                            // Message detail panel (if shown)
                                            if (showDetailPanel) {
                                                // Resizable divider for detail panel
                                                WidthResizeHandle(
                                                    onDeltaPx = { dx ->
                                                        detailPanelSplitRatio = (detailPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                    },
                                                    onDragEnd = { viewModel.updateLayout { it.copy(detailRatio = detailPanelSplitRatio) } },
                                                )

                                                Box(
                                                    modifier =
                                                        Modifier.width(
                                                            with(density) { (maxWidthPx * detailPanelSplitRatio).toDp() },
                                                        ),
                                                ) {
                                                    AppMessageDetailPanel(
                                                        viewModel = viewModel,
                                                        selectedMessage = selectedMessage,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }

                                            // Rightmost panel - Connection panel (if shown)
                                            if (showConnectionPanel) {
                                                // Resizable divider for connection panel
                                                WidthResizeHandle(
                                                    onDeltaPx = { dx ->
                                                        connectionPanelSplitRatio =
                                                            (connectionPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.6f)
                                                    },
                                                    onDragEnd = { viewModel.updateLayout { it.copy(connectionRatio = connectionPanelSplitRatio) } },
                                                )

                                                Box(
                                                    modifier =
                                                        Modifier.width(
                                                            with(density) { (maxWidthPx * connectionPanelSplitRatio).toDp() },
                                                        ),
                                                ) {
                                                    ConnectionPanel(
                                                        profiles = viewModel.connectionProfiles,
                                                        sessions = viewModel.sessions,
                                                        onConnect = { profileId, profile ->
                                                            viewModel.connectProfile(
                                                                profileId,
                                                                profile,
                                                            )
                                                        },
                                                        onDisconnect = { profileId -> viewModel.disconnectProfile(profileId) },
                                                        onSaveProfile = { profile -> viewModel.saveConnectionProfile(profile) },
                                                        onDeleteProfile = { profileId ->
                                                            viewModel.deleteConnectionProfile(
                                                                profileId,
                                                            )
                                                        },
                                                        onCloneProfile = { profile ->
                                                            viewModel.cloneConnectionProfile(profile)
                                                        },
                                                        onGetProfileSession = { profileId ->
                                                            viewModel.getProfileSession(
                                                                profileId,
                                                            )
                                                        },
                                                        onGetProfileSessions = { profileId ->
                                                            viewModel.getProfileSessions(
                                                                profileId,
                                                            )
                                                        },
                                                        onClose = { viewModel.toggleConnectionPanel() },
                                                        selectionRequest = viewModel.connectionPanelSelection.collectAsState().value,
                                                        rulesExpandRequest = viewModel.rulesExpandRequest.collectAsState().value,
                                                        onRulesExpandConsumed = { viewModel.consumeRulesExpandRequest() },
                                                        dictionary = viewModel.dictionary,
                                                        onOpenReplyStepInEditor = { profileId, ruleIndex, stepIndex, template ->
                                                            viewModel.openReplyStep(profileId, ruleIndex, stepIndex, template)
                                                        },
                                                        replyStepApply = viewModel.pendingReplyStepApply,
                                                        onReplyStepConsumed = { viewModel.consumeReplyStepApply() },
                                                        editingReplyStep = viewModel.editorTarget as? EditorTarget.ReplyStep,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }

                                            // The venue's own memory, beside the counterparty's messages.
                                            if (showOrderBookPanel) {
                                                WidthResizeHandle(
                                                    onDeltaPx = { dx ->
                                                        orderBookSplitRatio = (orderBookSplitRatio - dx / maxWidthPx).coerceIn(bookFloor, 0.7f)
                                                    },
                                                    onDragEnd = { viewModel.updateLayout { it.copy(orderBookRatio = orderBookSplitRatio) } },
                                                )

                                                Box(modifier = Modifier.width(with(density) { (maxWidthPx * orderBookSplitRatio).toDp() })) {
                                                    AppOrderBookPanel(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                                                }
                                            }

                                            // Latency panel (if shown)
                                            if (showLatencyPanel) {
                                                // Resizable divider for latency panel
                                                WidthResizeHandle(
                                                    onDeltaPx = { dx ->
                                                        latencyPanelSplitRatio = (latencyPanelSplitRatio - dx / maxWidthPx).coerceIn(dockFloor, 0.5f)
                                                    },
                                                    onDragEnd = { viewModel.updateLayout { it.copy(latencyRatio = latencyPanelSplitRatio) } },
                                                )

                                                Box(
                                                    modifier =
                                                        Modifier.width(
                                                            with(density) { (maxWidthPx * latencyPanelSplitRatio).toDp() },
                                                        ),
                                                ) {
                                                    viewModel.activeSession?.let { session ->
                                                        val latencyTrackingService = session.getLatencyTrackingService()

                                                        if (latencyTrackingService != null) {
                                                            val statistics by latencyTrackingService.statistics.collectAsState()
                                                            val aggregateStatistics by latencyTrackingService.aggregateStatistics.collectAsState()
                                                            val recentPairs by latencyTrackingService.recentPairs.collectAsState()

                                                            LatencyPanel(
                                                                statistics = statistics,
                                                                aggregateStatistics = aggregateStatistics,
                                                                recentPairs = recentPairs,
                                                                warningThresholdMicros = viewModel.appSettings.latencyWarningThresholdMicros,
                                                                criticalThresholdMicros = viewModel.appSettings.latencyCriticalThresholdMicros,
                                                                onClear = { session.clearLatencyStatistics() },
                                                                onClose = { viewModel.toggleLatencyPanel() },
                                                                modifier = Modifier.fillMaxSize(),
                                                            )
                                                        } else {
                                                            // Latency tracking not enabled for this session
                                                            Box(
                                                                modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Text(
                                                                    text = "Latency tracking not enabled.\nEnable it in Settings and reconnect.",
                                                                    color = AppTheme.Colors.textDisabled,
                                                                    fontSize = 12.sp,
                                                                )
                                                            }
                                                        }
                                                    } ?: Box(
                                                        modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = "No active session",
                                                            color = AppTheme.Colors.textDisabled,
                                                            fontSize = 12.sp,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Row(modifier = Modifier.weight(1f)) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            SplitCentre(
                                                viewModel = viewModel,
                                                orientation = splitOrientation,
                                                globalViewMode = globalViewMode,
                                                selectedMessage = selectedMessage,
                                                globalFilter = globalFilter,
                                                followedUids = followedUids,
                                                followedTraceIds = followedTraceIds,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Everything that opens across the foot of the window: the terminal, the Ledger,
                        // pinned search results and the open documents, as tabs of one dock. See [BottomDock].
                        BottomDock(viewModel)
                    }

                    ToolWindowStripe(side = StripeSide.RIGHT, open = openToolWindows, onToggle = onToggleToolWindow)
                }
            }

            // Notification popup overlay in bottom-right corner
            NotificationPopupContainer(
                notifications = notifications,
                onDismiss = { notificationId -> viewModel.dismissNotification(notificationId) },
            )
        }
    }
}

/**
 * The Scenarios rail, docked left — the message editor's pane idiom, applied to the workbench that used to be
 * a window. Absent from the layout entirely when hidden, so nothing about the app changes for someone who
 * never opens it.
 */
@Composable
private fun ScenariosRailDock(
    viewModel: FixMessageViewModel,
    show: Boolean,
    ratio: Float,
    maxWidthPx: Float,
    onDeltaPx: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    if (!show) return
    val density = LocalDensity.current
    Box(modifier = Modifier.width(with(density) { (maxWidthPx * ratio).toDp() })) {
        ScenariosRail(viewModel, modifier = Modifier.fillMaxSize())
    }
    WidthResizeHandle(onDeltaPx = onDeltaPx, onDragEnd = onDragEnd)
}

/**
 * The SPLIT layouts' centre: the session grid, and nothing else. The scenario editor used to share this
 * split, which coupled where it appeared to the *session* view mode; it is a bottom dock now (see
 * [BottomDock]), so the centre is purely the sessions in both TABS and SPLIT.
 *
 * Nothing sits above it. The split layouts had a bar of their own for one build, carrying the view
 * controls and the filter row under it, which cost two lines of pane height to hold six controls that
 * are on the toolbar in both layouts now. See [Toolbar].
 */
@Composable
private fun ColumnScope.SplitCentre(
    viewModel: FixMessageViewModel,
    orientation: SplitOrientation,
    globalViewMode: com.knapsack.fixtool.model.FixMessageSession.ViewMode,
    selectedMessage: FixMessage?,
    globalFilter: MessageFilters.Global = MessageFilters.Global.NONE,
    followedUids: Set<Long>? = null,
    followedTraceIds: Set<String> = emptySet(),
) {
    val connectionPanelOpen by viewModel.showConnectionPanel.collectAsState()
    val splitScope = rememberCoroutineScope()
    SplitView(
        sessions = viewModel.sessions,
        dictionary = viewModel.dictionary,
        viewMode = globalViewMode,
        onCloseSession = { session -> viewModel.closeSession(session) },
        onMoveSession = { session, target -> viewModel.moveSessionTo(session, target) },
        onFocusSession = { session -> viewModel.setActiveSessionByObject(session) },
        activeSession = viewModel.activeSession,
        onEditVenueRules = { session -> viewModel.openVenueRules(session) },
        onMinimize = { session, on -> viewModel.setSessionMinimized(session, on) },
        selectedMessage = selectedMessage,
        onSelectMessage = { m -> viewModel.selectMessageFromGrid(m) },
        onDiffSelected = { a, b -> viewModel.openDiffSelected(a, b) },
        onPasteMessage = { rawMessage -> viewModel.pasteAndDisplayMessage(rawMessage) },
        orientation = orientation,
        gridViewColumns = viewModel.appSettings.gridViewColumns,
        assertionResults = viewModel.assertionResults,
        appSettings = viewModel.appSettings,
        globalFilter = globalFilter,
        followedUids = followedUids,
        followedTraceIds = followedTraceIds,
        onFollowTrace = { id -> viewModel.follow(id) },
        onUnfollowTrace = { viewModel.unfollow() },
        hasProfiles = viewModel.connectionProfiles.isNotEmpty(),
        onOpenWorkspace = {
            splitScope.launch {
                chooseDirectory(title = "Open workspace", startIn = viewModel.defaultWorkspaceLocation())
                    ?.let { folder -> viewModel.openWorkspace(folder) }
            }
        },
        onOpenConnectionPanel = { if (!connectionPanelOpen) viewModel.toggleConnectionPanel() },
        modifier = Modifier.weight(1f),
    )
}

/**
 * Helper composable that renders the MessageEditorPanel with all common configuration.
 * This is extracted to avoid duplication between TABS and SPLIT layout modes.
 */
@Composable
private fun AppMessageEditorPanel(
    viewModel: FixMessageViewModel,
    savedMessages: List<SavedFixMessage>,
    currentProfileId: String?,
    editorState: com.knapsack.fixtool.model.MessageEditorState,
    editorPanelSplitRatio: Float,
    onEditorPanelSplitRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSession by viewModel.activeSessionState
    val replyStepTarget = viewModel.editorTarget as? EditorTarget.ReplyStep

    MessageEditorPanel(
        replyStep =
            replyStepTarget?.let { target ->
                ReplyStepEditing(
                    profileName =
                        viewModel.connectionProfiles
                            .firstOrNull { it.id == target.profileId }
                            ?.name
                            .orEmpty(),
                    ruleIndex = target.ruleIndex,
                    stepIndex = target.stepIndex,
                    onApply = { viewModel.applyReplyStep() },
                    onCancel = { viewModel.cancelReplyStep() },
                )
            },
        sessions = viewModel.sessions,
        selectedSession = activeSession,
        dictionary = viewModel.dictionary,
        fields = viewModel.editorFields,
        selectedFieldIndex = viewModel.editorSelectedFieldIndex,
        selectedFieldIndices = viewModel.editorSelectedIndices,
        onFieldUpdate = { index, field ->
            viewModel.updateEditorField(
                index,
                field,
            )
        },
        onFieldAdd = { viewModel.addEditorField() },
        onFieldDelete = { index -> viewModel.deleteEditorField(index) },
        onFieldMoveUp = { index -> viewModel.moveEditorFieldUp(index) },
        onFieldMoveDown = { index -> viewModel.moveEditorFieldDown(index) },
        onFieldSelect = { index, isCtrl, isShift ->
            viewModel.selectEditorField(
                index,
                isCtrl,
                isShift,
            )
        },
        onClearFields = { viewModel.clearEditorFields() },
        onClose = { viewModel.toggleMessageEditor() },
        onSend = onSend@{ fields ->
            // Update message maps before resolving templates
            viewModel.updateMessageMaps()

            // Debug logging
            logger.debug(
                "Message maps - Incoming: {}, Outgoing: {}",
                viewModel.incomingMessagesByType.keys.joinToString(","),
                viewModel.outgoingMessagesByType.keys.joinToString(","),
            )

            // Per-session variables (sessionIndex, sessionQualifier, sessionSenderCompID, ...)
            // are available to template expressions for the active session
            val sessionVariables =
                viewModel.activeSession
                    ?.let { session ->
                        viewModel.sessionTemplateVariables(session, viewModel.activeSessionIndex + 1)
                    }.orEmpty()

            // FIRST: Validate template expressions before sending
            val templateErrors =
                viewModel.validateTemplateExpressions(
                    fields,
                    viewModel.incomingMessagesByType,
                    viewModel.outgoingMessagesByType,
                    seedVariables = sessionVariables,
                )

            if (templateErrors.isNotEmpty()) {
                // Block send if template validation fails
                viewModel.setEditorValidationErrors(
                    listOf("❌ Cannot send message - Fix template expression errors:") + templateErrors,
                )
                logger.warn("Send blocked due to template expression errors: {}", templateErrors.joinToString(", "))
                return@onSend
            }

            // Clear any previous validation errors
            viewModel.clearEditorValidationErrors()

            // Resolve template expressions with access to previous messages
            val resolvedFields =
                fields.resolveTemplates(
                    incomingMessages = viewModel.incomingMessagesByType,
                    outgoingMessages = viewModel.outgoingMessagesByType,
                    dictionary = viewModel.getDictionaryAdapter(),
                    seedVariables = sessionVariables,
                )

            // Debug logging to see if templates were resolved
            fields.forEachIndexed { index, field ->
                if (field.value != resolvedFields[index].value) {
                    logger.debug("Field {} resolved: {} -> {}", field.tag, field.value, resolvedFields[index].value)
                }
            }

            val rawMessage = resolvedFields.toRawMessage()
            val result = viewModel.sendMessage(rawMessage)

            // Display validation warnings in the message editor validation section
            when (result) {
                is com.knapsack.fixtool.service.SendResult.SuccessWithWarning -> {
                    // Say what is actually wrong with the message — it was sent regardless.
                    viewModel.setEditorValidationErrors(listOf("WARNING: sent, but ${result.warning}"))
                }
                is com.knapsack.fixtool.service.SendResult.Failed -> {
                    // Error already logged and notified via NotifyingLogger
                }
                is com.knapsack.fixtool.service.SendResult.Success, null -> {
                    // Success or no result - no action needed
                }
            }
        },
        onSendToAll = onSendToAll@{ fields ->
            // Update message maps before resolving templates
            viewModel.updateMessageMaps()

            // Validate with the first logged-on session's variables seeded; the actual
            // per-session values are applied at send time inside the ViewModel
            val firstTarget =
                viewModel.sessions.firstOrNull {
                    it.connectionState.value == com.knapsack.fixtool.model.FixConnectionState.LOGGED_ON
                }
            val sessionVariables = firstTarget?.let { viewModel.sessionTemplateVariables(it, 1) }.orEmpty()
            val templateErrors =
                viewModel.validateTemplateExpressions(
                    fields,
                    viewModel.incomingMessagesByType,
                    viewModel.outgoingMessagesByType,
                    seedVariables = sessionVariables,
                )

            if (templateErrors.isNotEmpty()) {
                viewModel.setEditorValidationErrors(
                    listOf("❌ Cannot send message - Fix template expression errors:") + templateErrors,
                )
                logger.warn("Send-to-all blocked due to template expression errors: {}", templateErrors.joinToString(", "))
                return@onSendToAll
            }

            viewModel.clearEditorValidationErrors()

            val outcomes = viewModel.sendMessageToAllConnectedSessions(fields)

            val warned =
                outcomes.mapNotNull { it.result as? com.knapsack.fixtool.service.SendResult.SuccessWithWarning }
            if (warned.isNotEmpty()) {
                viewModel.setEditorValidationErrors(
                    listOf("WARNING: sent, but ${warned.first().warning}"),
                )
            }
        },
        onLoad = { fields -> viewModel.requestLoadRun(fields) },
        onValidate = { fields ->
            viewModel.validateEditorMessage(fields)
        },
        validationErrors = viewModel.editorValidationErrors,
        onClearValidationErrors = { viewModel.clearEditorValidationErrors() },
        onSetValidationErrors = { errors ->
            viewModel.setEditorValidationErrors(
                errors,
            )
        },
        onDescriptionVisibilityChanged = { showingDescription ->
            // Adjust panel width: 28% when showing description, 20% when hidden
            onEditorPanelSplitRatioChange(if (showingDescription) 0.28f else 0.20f)
        },
        onSaveMessage = { name, fields, profileId, userTags ->
            viewModel.saveEditorMessage(
                name,
                fields,
                profileId,
                userTags,
            )
        },
        onSaveMessageAs = { name, fields, profileId, userTags ->
            viewModel.saveEditorMessageAs(
                name,
                fields,
                profileId,
                userTags,
            )
        },
        savedMessages = savedMessages,
        onLoadMessage = { savedMessage ->
            viewModel.loadEditorMessage(savedMessage)
        },
        onDeleteMessage = { messageId, profileId ->
            viewModel.deleteSavedMessage(
                messageId,
                profileId,
            )
        },
        onToggleFavorite = { messageId ->
            viewModel.toggleMessageFavorite(messageId)
        },
        connectionProfiles = viewModel.connectionProfiles,
        currentProfileId = currentProfileId,
        editorState = editorState,
        onSessionChange = { session -> viewModel.setActiveSessionByObject(session) },
        onGetProfileConnectionState = { profileId -> viewModel.getProfileConnectionState(profileId) },
        selectedEditorProfile = viewModel.selectedEditorProfile.value,
        onEditorProfileChange = { profile -> viewModel.setSelectedEditorProfile(profile) },
        onError = { errorMsg ->
            viewModel.showNotification(
                errorMsg,
                NotificationType.ERROR,
            )
        },
        modifier = modifier,
    )
}

/**
 * Helper composable that renders the MessageDetailPanel with all common configuration.
 * This is extracted to avoid duplication between TABS and SPLIT layout modes.
 */
@Composable
private fun AppMessageDetailPanel(
    viewModel: FixMessageViewModel,
    selectedMessage: FixMessage?,
    modifier: Modifier = Modifier,
) {
    // Hoist the detail search state into the ViewModel so the in-panel search box and the
    // automation control surface (/detail, fixtool_detail_search) drive the same state.
    val detailSearchQuery by viewModel.detailSearchQuery.collectAsState()
    val detailMatchContextMode by viewModel.detailMatchContextMode.collectAsState()
    // Follow reaches the field rows through a composition local rather than four more parameters
    // threaded through the panel's private row builders. It is an optional host capability consumed
    // deep in a tree of private functions — the case a local is for — and it means a field row can
    // offer Follow without every helper between here and it learning what Follow is.
    CompositionLocalProvider(LocalFollowTrace provides { id: String -> viewModel.follow(id) }) {
        MessageDetailPanel(
            message = selectedMessage,
            dictionary = viewModel.dictionary,
            onClose = { viewModel.toggleDetailPanel() },
            onPasteMessage = { rawMessage ->
                viewModel.pasteAndDisplayMessage(rawMessage)
            },
            appSettings = viewModel.appSettings,
            modifier = modifier,
            externalSearchQuery = detailSearchQuery,
            onSearchQueryChange = { viewModel.setDetailSearch(query = it) },
            externalMatchContextMode = detailMatchContextMode,
            onMatchContextModeChange = { viewModel.setDetailSearch(mode = it) },
            tagResults = selectedMessage?.let { viewModel.assertionResults[it]?.tags } ?: emptyList(),
            // No step asserted on this message; the run's post-mortem held it up against an expectation the
            // run never reached. The banner has to say that, or it claims a verdict nobody reached.
            tagResultsAreDiagnostic = selectedMessage?.let { viewModel.assertionResults[it]?.kind } == "diagnosis",
            onEditAssertion = selectedMessage?.let { msg -> ({ tag: Int? -> viewModel.openScenarioEditorForFailure(msg, tag) }) },
            onDiffAgainst = { msg -> viewModel.openDiffAgainst(msg) },
            // Empty for everything that is not an order sitting on a venue's session, which is what keeps
            // "Reply With…" off the panel entirely for the initiator half of the app.
            replyOffers = selectedMessage?.let { viewModel.replyOffersFor(it) } ?: emptyList(),
            onReplyWith =
                selectedMessage?.let { msg ->
                    (
                        { shape: ReplyShape ->
                            viewModel.replyWith(msg, shape)
                            Unit
                        }
                    )
                },
        )
    }
}

/**
 * The order-book panel, wherever the layout puts it.
 *
 * Extracted like [AppMessageDetailPanel] because TABS and SPLIT place it at two call sites, and two
 * copies of a panel's wiring is two things to keep in step.
 *
 * Shown against the **active session**, which for a venue is the client pane a tester is reading —
 * the book belongs to a counterparty, and "which counterparty" is exactly what the pane already says.
 */
@Composable
private fun AppOrderBookPanel(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    val session by viewModel.activeSessionState
    // Collected, not called: a panel reading a plain snapshot has nothing to recompose it, and a book
    // frozen at the moment the panel opened is a book that lies with a straight face.
    val flow = session?.orderBookFlow()
    val book = flow?.collectAsState()?.value
    if (session == null || book == null) {
        // **The header comes first, even here.** This branch drew the sentence alone, so the one state a
        // fresh window opens in was a dock with no title, no name and nothing to put it away with — found
        // by opening the panel with no venue connected, which is what a first run looks like.
        Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.surface)) {
            DockHeader(window = ToolWindow.ORDER_BOOK, onHide = { viewModel.toggleOrderBookPanel() })
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    // Not "no orders": an initiator has no book to be empty. A client's own view of the
                    // orders it sent is a different feature, deliberately not this one.
                    text = "Only an acceptor holds orders.\nSelect a venue session to see its book.",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    OrderBookPanel(
        book = book,
        title = session!!.title,
        onClear = { session!!.clearOrderBook() },
        onClose = { viewModel.toggleOrderBookPanel() },
        onOpenMessage = { uid -> viewModel.selectMessageByUid(uid) },
        modifier = modifier,
    )
}
