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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knapsack.fixtool.model.EditorTarget
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.ReplyShape
import com.knapsack.fixtool.service.TraceLanes
import com.knapsack.fixtool.service.TraceRows
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.ui.terminal.TerminalController
import com.knapsack.fixtool.ui.terminal.TerminalDockSlot
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import com.knapsack.fixtool.viewmodel.TraceRendering
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
        val showSearchResultsPane by viewModel.showSearchResultsPane.collectAsState()
        val pinnedSearchResults by viewModel.pinnedSearchResults.collectAsState()
        val isDictionaryValid by viewModel.isDictionaryValid.collectAsState()
        // Native folder dialogs suspend, and the Open workspace item is a menu click.
        val workspaceScope = rememberCoroutineScope()
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
        val tracePanelOpen by viewModel.tracePanelOpen.collectAsState()
        val expandedTraces by viewModel.expandedTraces.collectAsState()
        val ungroupedTracesExpanded by viewModel.ungroupedTracesExpanded.collectAsState()

        /**
         * The Ledger's rows, rebuilt only when something it draws from changed.
         *
         * The index is republished on the trace ticker and is a new object only when some pane's
         * snapshot actually changed (`TraceFollow` memoises on snapshot identity), so this memo is quiet
         * while the app is — and does no work at all while the panel is shut, because the index is null.
         */
        val traceRows =
            remember(followedTraceIndex, expandedTraces, ungroupedTracesExpanded, followedTrace?.anchorId) {
                val index = followedTraceIndex
                if (index == null) {
                    emptyList()
                } else {
                    TraceRows.build(
                        snapshots = index.snapshots,
                        sessionTitles = index.sessionTitles,
                        grouping = index.grouping,
                        dictionary = viewModel.getDictionaryAdapter(),
                        expanded = expandedTraces,
                        ungroupedExpanded = ungroupedTracesExpanded,
                        followedAnchor = followedTrace?.anchorId,
                    )
                }
            }
        val showLatencyPanel by viewModel.showLatencyPanel.collectAsState()
        val showOrderBookPanel by viewModel.showOrderBookPanel.collectAsState()
        val showScenariosRail by viewModel.showScenariosRail.collectAsState()

        /**
         * Whether ANY pane is grouped by conversation — what the toolbar button lights up on.
         *
         * One collector over the combined flows, not `sessions.map { it.flow.collectAsState() }`.
         * That called a composable inside a loop over a mutable list, so the number and order of
         * composition slots depended on how many sessions were open and every add or remove shifted
         * them, re-creating each collector. Keyed on the session count so the combination is rebuilt
         * exactly when the set of flows to combine changes.
         */
        val sessionCount = viewModel.sessions.size
        val anySessionGrouped by remember(sessionCount) {
            val flows = viewModel.sessions.map { session -> session.groupByConversation }
            if (flows.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(false)
            } else {
                kotlinx.coroutines.flow.combine(flows) { flags -> flags.any { on -> on } }
            }
        }.collectAsState(initial = viewModel.anySessionGroupedByConversation())
        // Documents live in the scenario dock now (see ScenarioDock), not in the session centre, so the
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
        var searchResultsPanelHeight by remember { mutableStateOf(savedLayout.searchHeightDp.dp) }
        var latencyPanelSplitRatio by remember { mutableStateOf(savedLayout.latencyRatio) }
        var orderBookSplitRatio by remember { mutableStateOf(savedLayout.orderBookRatio) }

        // Bring the terminal back the way it was left, and persist changes to it thereafter. The height
        // rides through the dock slot below; visible/minimized live on the (global) TerminalController.
        LaunchedEffect(Unit) {
            TerminalController.restore(savedLayout.terminalVisible, savedLayout.terminalMinimized)
            TerminalController.onChange = {
                viewModel.updateLayout {
                    it.copy(terminalVisible = TerminalController.visible, terminalMinimized = TerminalController.minimized)
                }
            }
        }

        // The docked terminal is hosted as *movable* content. TABS and the two SPLIT branches each place
        // it at a different call site, and closing the last side panel switches SPLIT branches. Composed
        // three times, that switch would dispose one instance and create another — tearing down the PTY
        // and killing a running `claude` session. movableContentOf moves the single terminal node between
        // call sites instead, so the widget, its PTY and the session survive; TerminalPanel's onDispose
        // then only fires on a real close (the terminal is hidden, or the app exits).
        val terminalSlot =
            remember {
                movableContentOf {
                    TerminalDockSlot(
                        automationControlPort = viewModel.appSettings.automationControlPort,
                        initialHeightDp = savedLayout.terminalHeightDp,
                        onHeightPersist = { h -> viewModel.updateLayout { it.copy(terminalHeightDp = h) } },
                    )
                }
            }
        // The scenario dock is NOT movable content: unlike the terminal (three centre-column call sites), it
        // lives at one stable site beneath the whole layout, so it is never recomposed at a new site.

        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        // Handle Cmd+F (Mac) or Ctrl+F (Windows/Linux) to open search
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.F &&
                            (event.isMetaPressed || event.isCtrlPressed)
                        ) {
                            viewModel.toggleGlobalSearchDialog()
                            true // Consume the event
                        } else if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Escape &&
                            followedTrace != null &&
                            !showSettingsDialog &&
                            !showHelpDialog &&
                            !showGlobalSearchDialog
                        ) {
                            // Esc stops following. This is the bubble phase and the outermost handler in
                            // the app, so anything nested that wants Esc has already had it and consumed
                            // it — the grid clears its multi-selection here, the saved-messages popup
                            // closes itself. The dialogs above draw over the whole window without a key
                            // handler of their own, so they are named rather than trusted to consume.
                            viewModel.unfollow()
                            true
                        } else {
                            // esc no longer closes the scenario document: the editor is a bottom dock, not a
                            // full-screen pane, so esc-from-anywhere reads as a stray close. The dock tab's ×
                            // is the way to close it.
                            false // Don't consume other events
                        }
                    },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E)),
            ) {
                Toolbar(
                    viewMode = viewMode,
                    onViewModeChange = { mode ->
                        viewMode = mode
                        // Persist through defaultLayout — the field that already seeds the initial layout.
                        viewModel.persistViewMode(
                            when (mode) {
                                ViewMode.TABS -> "tabs"
                                ViewMode.SPLIT_VERTICAL -> "vertical"
                                ViewMode.SPLIT_HORIZONTAL -> "horizontal"
                            },
                        )
                    },
                    showMessageEditor = showMessageEditor,
                    showDetailPanel = showDetailPanel,
                    showConnectionPanel = showConnectionPanel,
                    showLatencyPanel = showLatencyPanel,
                    showOrderBookPanel = showOrderBookPanel,
                    connectionProfiles = viewModel.connectionProfiles,
                    isDictionaryValid = isDictionaryValid,
                    globalSessionViewMode = globalViewMode,
                    globalFilterRegex = globalFilterRegex,
                    globalFilterShowIncoming = globalFilterShowIncoming,
                    globalFilterShowOutgoing = globalFilterShowOutgoing,
                    hideProtocolTags = viewModel.appSettings.hideProtocolTags,
                    groupByConversation = anySessionGrouped,
                    tracePanelOpen = tracePanelOpen,
                    followingLabel = followedTrace?.label,
                    followingSessionCount = followedTrace?.sessionCount ?: 0,
                    followingMessageCount = followedTrace?.messageCount ?: 0,
                    followingTruncatedOn = followedTrace?.truncatedSessionTitles.orEmpty(),
                    onUnfollow = { viewModel.unfollow() },
                    onOpenMessageEditor = { viewModel.toggleMessageEditor() },
                    onToggleDetailPanel = { viewModel.toggleDetailPanel() },
                    onToggleConnectionPanel = { viewModel.toggleConnectionPanel() },
                    onToggleLatencyPanel = { viewModel.toggleLatencyPanel() },
                    onToggleOrderBookPanel = { viewModel.toggleOrderBookPanel() },
                    onToggleGridView = { viewModel.toggleViewMode() },
                    onQuickConnect = { profileId, profile ->
                        viewModel.connectProfile(profileId, profile)
                    },
                    onGetProfileConnectionState = { profileId ->
                        viewModel.getProfileConnectionState(profileId)
                    },
                    workspaceOpen = !viewModel.openWorkspaceIsHome,
                    workspaceName = viewModel.openWorkspace.name.takeUnless { viewModel.openWorkspaceIsHome },
                    onOpenExample = { viewModel.requestOpenExample() },
                    onOpenWorkspace = {
                        workspaceScope.launch {
                            chooseDirectory(title = "Open workspace", startIn = viewModel.defaultWorkspaceLocation())
                                ?.let { folder -> viewModel.openWorkspace(folder) }
                        }
                    },
                    onCloseWorkspace = { viewModel.closeWorkspace() },
                    recentWorkspaces = viewModel.recentWorkspaces,
                    onOpenRecentWorkspace = { viewModel.openWorkspace(it) },
                    onSearchAllSessions = { viewModel.toggleGlobalSearchDialog() },
                    onAddSeparatorToAll = { viewModel.addSeparatorToAllSessions() },
                    onClearAll = { viewModel.clearAllSessions() },
                    onGlobalFilterChange = { regex -> viewModel.setGlobalFilterRegex(regex) },
                    onGlobalFilterIncomingChange = { show -> viewModel.setGlobalFilterShowIncoming(show) },
                    onGlobalFilterOutgoingChange = { show -> viewModel.setGlobalFilterShowOutgoing(show) },
                    onToggleHideProtocolTags = { viewModel.toggleHideProtocolTags() },
                    onToggleGroupByConversation = { viewModel.toggleGroupByConversationAllSessions() },
                    onToggleTracePanel = { viewModel.toggleTracePanel() },
                    onOpenSettings = { viewModel.toggleSettingsDialog() },
                    onOpenHelp = { viewModel.toggleHelpDialog() },
                    onOpenScenarios = { viewModel.toggleScenariosRail() },
                    onCaptureScenario = { viewModel.captureAllSessionsToEditor() },
                    showTerminal = TerminalController.visible,
                    onToggleTerminal = { TerminalController.toggle() },
                )

                // Settings Dialog
                if (showSettingsDialog) {
                    SettingsDialog(
                        currentSettings = viewModel.appSettings,
                        dictionary = viewModel.dictionary,
                        onSave = { settings -> viewModel.saveAppSettings(settings) },
                        onDismiss = { viewModel.toggleSettingsDialog() },
                        workspaceFolder = viewModel.openWorkspace.absolutePath,
                        workspaceIsDefault = viewModel.openWorkspaceIsHome,
                        onOpenWorkspace = {
                            workspaceScope.launch {
                                chooseDirectory(title = "Open workspace", startIn = viewModel.openWorkspace)
                                    ?.let { folder -> viewModel.openWorkspace(folder) }
                            }
                        },
                        onCloseWorkspace = { viewModel.closeWorkspace() },
                    )
                }

                // Open example: copies a bundled example into a workspace of its own.
                viewModel.pendingExample?.let { example ->
                    OpenExampleDialog(
                        example = example,
                        defaultLocation = viewModel.defaultWorkspaceLocation(),
                        onDismiss = { viewModel.dismissExampleDialog() },
                        onOpen = { name, location, fixVersion ->
                            viewModel.openExample(example.id, name, location, fixVersion)
                        },
                    )
                }

                // Help Dialog
                if (showHelpDialog) {
                    HelpDialog(
                        onClose = { viewModel.toggleHelpDialog() },
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

                when (viewMode) {
                    ViewMode.TABS -> {
                        // All panels in same row: scenarios rail, editor, tabs, detail
                        BoxWithConstraints(modifier = Modifier.weight(1f)) {
                            val maxWidthPx = with(density) { maxWidth.toPx() }

                            Row(modifier = Modifier.fillMaxSize()) {
                                ScenariosRailDock(
                                    viewModel = viewModel,
                                    show = showScenariosRail,
                                    ratio = scenariosRailSplitRatio,
                                    maxWidthPx = maxWidthPx,
                                    onDeltaPx = { dx ->
                                        scenariosRailSplitRatio = (scenariosRailSplitRatio + dx / maxWidthPx).coerceIn(0.1f, 0.45f)
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
                                            editorPanelSplitRatio = (editorPanelSplitRatio + dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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
                                        activeIndex = viewModel.activeSessionIndex,
                                        viewMode = globalViewMode,
                                        onTabClick = { index -> viewModel.setActiveSession(index) },
                                        onCloseTab = { index -> viewModel.closeSession(index) },
                                        onToggleWrapText = { index ->
                                            viewModel.sessions.getOrNull(index)?.toggleWrapText()
                                        },
                                        onConnect = { index ->
                                            viewModel.sessions.getOrNull(index)?.reconnect()
                                        },
                                        onDisconnect = { index ->
                                            viewModel.sessions.getOrNull(index)?.disconnect()
                                        },
                                        isAtBottom = isAtBottom,
                                        onScrollToBottom = { scrollToBottomTrigger++ },
                                    )

                                    // The centre is always the sessions now — the scenario editor is a
                                    // bottom dock (see ScenarioDock), not a pane that replaces the grid.
                                    viewModel.activeSession?.let { session ->
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
                                                onFocusClient = { client ->
                                                    viewModel.sessions
                                                        .indexOf(client)
                                                        .takeIf { it >= 0 }
                                                        ?.let { viewModel.setActiveSession(it) }
                                                },
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
                                        workspaceOpen = !viewModel.openWorkspaceIsHome,
                                        onOpenExample = { viewModel.requestOpenExample() },
                                        onOpenConnectionPanel = { if (!showConnectionPanel) viewModel.toggleConnectionPanel() },
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                    )

                                    // The bottom slot: the Trace panel when it is open, otherwise the
                                    // pinned search results. One slot, because they answer the same
                                    // shape of question over the same rows and stacking them would
                                    // leave the grid a strip.
                                    if (tracePanelOpen || showSearchResultsPane) {
                                        HeightResizeHandle(
                                            onDeltaPx = { dy ->
                                                searchResultsPanelHeight =
                                                    (searchResultsPanelHeight - with(density) { dy.toDp() }).coerceIn(100.dp, 600.dp)
                                            },
                                            onDragEnd = { viewModel.updateLayout { it.copy(searchHeightDp = searchResultsPanelHeight.value) } },
                                        )

                                        Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                            if (tracePanelOpen) {
                                                AppTracePanel(
                                                    viewModel = viewModel,
                                                    rows = traceRows,
                                                    sessionTitles = followedTraceIndex?.sessionTitles.orEmpty(),
                                                    followingLabel = followedTrace?.label,
                                                    selectedMessage = selectedMessage,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            } else {
                                                AppSearchResultsPane(
                                                    viewModel = viewModel,
                                                    pinnedSearchResults = pinnedSearchResults,
                                                    selectedMessage = selectedMessage,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }

                                    // Docked terminal — bottom of the centre pane, so it's only as
                                    // wide as the message area (shrinks when side panels open).
                                    terminalSlot()
                                }

                                // Message detail panel (if shown)
                                if (showDetailPanel) {
                                    // Resizable divider for detail panel
                                    WidthResizeHandle(
                                        onDeltaPx = { dx ->
                                            detailPanelSplitRatio = (detailPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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
                                            connectionPanelSplitRatio = (connectionPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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
                                            orderBookSplitRatio = (orderBookSplitRatio - dx / maxWidthPx).coerceIn(0.15f, 0.7f)
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
                                            latencyPanelSplitRatio = (latencyPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.5f)
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

                                Row(modifier = Modifier.fillMaxSize()) {
                                    ScenariosRailDock(
                                        viewModel = viewModel,
                                        show = showScenariosRail,
                                        ratio = scenariosRailSplitRatio,
                                        maxWidthPx = maxWidthPx,
                                        onDeltaPx = { dx ->
                                            scenariosRailSplitRatio = (scenariosRailSplitRatio + dx / maxWidthPx).coerceIn(0.1f, 0.45f)
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
                                                editorPanelSplitRatio = (editorPanelSplitRatio + dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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

                                        // The bottom slot: Trace panel when open, else pinned results.
                                        if (tracePanelOpen || showSearchResultsPane) {
                                            HeightResizeHandle(
                                                onDeltaPx = { dy ->
                                                    searchResultsPanelHeight =
                                                        (searchResultsPanelHeight - with(density) { dy.toDp() }).coerceIn(100.dp, 600.dp)
                                                },
                                                onDragEnd = {
                                                    viewModel.updateLayout {
                                                        it.copy(
                                                            searchHeightDp = searchResultsPanelHeight.value,
                                                        )
                                                    }
                                                },
                                            )

                                            Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                                if (tracePanelOpen) {
                                                    AppTracePanel(
                                                        viewModel = viewModel,
                                                        rows = traceRows,
                                                        sessionTitles = followedTraceIndex?.sessionTitles.orEmpty(),
                                                        followingLabel = followedTrace?.label,
                                                        selectedMessage = selectedMessage,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                } else {
                                                    AppSearchResultsPane(
                                                        viewModel = viewModel,
                                                        pinnedSearchResults = pinnedSearchResults,
                                                        selectedMessage = selectedMessage,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                        }

                                        // Docked terminal — bottom of the centre pane (shrinks with side panels).
                                        terminalSlot()
                                    }

                                    // Message detail panel (if shown)
                                    if (showDetailPanel) {
                                        // Resizable divider for detail panel
                                        WidthResizeHandle(
                                            onDeltaPx = { dx ->
                                                detailPanelSplitRatio = (detailPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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
                                                connectionPanelSplitRatio = (connectionPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.6f)
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
                                                orderBookSplitRatio = (orderBookSplitRatio - dx / maxWidthPx).coerceIn(0.15f, 0.7f)
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
                                                latencyPanelSplitRatio = (latencyPanelSplitRatio - dx / maxWidthPx).coerceIn(0.1f, 0.5f)
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

                                // The bottom slot: Trace panel when open, else pinned results.
                                if (tracePanelOpen || showSearchResultsPane) {
                                    HeightResizeHandle(
                                        onDeltaPx = { dy ->
                                            searchResultsPanelHeight =
                                                (searchResultsPanelHeight - with(density) { dy.toDp() }).coerceIn(100.dp, 600.dp)
                                        },
                                        onDragEnd = { viewModel.updateLayout { it.copy(searchHeightDp = searchResultsPanelHeight.value) } },
                                    )

                                    Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                        if (tracePanelOpen) {
                                            AppTracePanel(
                                                viewModel = viewModel,
                                                rows = traceRows,
                                                sessionTitles = followedTraceIndex?.sessionTitles.orEmpty(),
                                                followingLabel = followedTrace?.label,
                                                selectedMessage = selectedMessage,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        } else {
                                            AppSearchResultsPane(
                                                viewModel = viewModel,
                                                pinnedSearchResults = pinnedSearchResults,
                                                selectedMessage = selectedMessage,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }

                                // Docked terminal — bottom of the centre pane (shrinks with side panels).
                                terminalSlot()
                            }
                        }
                    }
                }

                // The scenario editor dock — full width, beneath everything, so it spans the whole window
                // (unlike the terminal, which stays as wide as the centre pane). It is decoupled from the
                // session view mode: the same dock in TABS and both SPLITs. Absent when no document is open.
                ScenarioDock(viewModel)
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
 * [ScenarioDock]), so the centre is purely the sessions in both TABS and SPLIT.
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
    SplitView(
        sessions = viewModel.sessions,
        dictionary = viewModel.dictionary,
        viewMode = globalViewMode,
        onCloseSession = { index -> viewModel.closeSession(index) },
        onMoveSession = { from, to -> viewModel.moveSession(from, to) },
        onFocusSession = { index -> viewModel.setActiveSession(index) },
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
        workspaceOpen = !viewModel.openWorkspaceIsHome,
        onOpenExample = { viewModel.requestOpenExample() },
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
        Box(
            modifier = modifier.fillMaxSize().background(AppTheme.Colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Not "no orders": an initiator has no book to be empty. A client's own view of the
                // orders it sent is a different feature, deliberately not this one.
                text = "Only an acceptor holds orders.\nSelect a venue session to see its book.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 12.sp,
            )
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

/**
 * Helper composable that renders the SearchResultsPane with all common configuration.
 * This is extracted to avoid duplication between TABS and SPLIT layout modes.
 */
@Composable
private fun AppSearchResultsPane(
    viewModel: FixMessageViewModel,
    pinnedSearchResults: List<FixMessageViewModel.SearchResult>,
    selectedMessage: FixMessage?,
    modifier: Modifier = Modifier,
) {
    SearchResultsPane(
        searchResults = pinnedSearchResults,
        selectedMessage = selectedMessage,
        dictionary = viewModel.dictionary,
        appSettings = viewModel.appSettings,
        onSelectResult = { result -> viewModel.navigateToSearchResult(result) },
        onClose = { viewModel.closeSearchResultsPane() },
        modifier = modifier,
    )
}

/**
 * The Ledger with everything it needs wired to the one app-level follow state, so all three layouts
 * mount the same panel rather than three configurations of it.
 */
@Composable
private fun AppTracePanel(
    viewModel: FixMessageViewModel,
    rows: List<TraceRows.Row>,
    sessionTitles: List<String>,
    followingLabel: String?,
    selectedMessage: FixMessage?,
    modifier: Modifier = Modifier,
) {
    val rendering by viewModel.traceRendering.collectAsState()
    val index by viewModel.traceIndex.collectAsState()
    val followed by viewModel.followedTrace.collectAsState()
    val anchor = followed?.anchorId

    /**
     * The followed trace as lanes, built only while Lanes is the drawing on screen.
     *
     * Keyed on the same index generation `rows` is, so the two renderings can never be one tick apart —
     * and gated on the rendering so a reader on the Ledger pays nothing for the picture they are not
     * looking at.
     */
    val lanes =
        remember(index, anchor, rendering) {
            if (rendering != TraceRendering.LANES) {
                null
            } else {
                val current = index
                current
                    ?.grouping
                    ?.traces
                    ?.firstOrNull { anchor != null && anchor in it.ids }
                    ?.let { TraceLanes.build(it, current.snapshots, current.sessionTitles, current.sessionRoles) }
            }
        }

    TracePanel(
        rows = rows,
        sessionTitles = sessionTitles,
        selectedMessage = selectedMessage,
        dictionary = viewModel.dictionary,
        appSettings = viewModel.appSettings,
        followingLabel = followingLabel,
        rendering = rendering,
        lanes = lanes,
        onSetRendering = { viewModel.setTraceRendering(it) },
        onToggleTrace = { key -> viewModel.toggleTrace(key) },
        onToggleUngrouped = { viewModel.toggleUngroupedTraces() },
        // The keys of what is on screen right now, not of some index the panel is not drawing — see
        // TraceFollow.expandAll.
        onExpandAll = { viewModel.expandAllTraces(rows.filterIsInstance<TraceRows.Row.Header>().map { it.key }) },
        onCollapseAll = { viewModel.collapseAllTraces() },
        onFollow = { id -> viewModel.follow(id) },
        onUnfollow = { viewModel.unfollow() },
        onSelectMember = { located, message -> viewModel.navigateToTraceMember(located.session, message) },
        onClose = { viewModel.closeTracePanel() },
        modifier = modifier,
    )
}
