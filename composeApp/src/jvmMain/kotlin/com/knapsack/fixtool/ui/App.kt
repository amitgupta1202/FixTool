package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.slf4j.LoggerFactory
import java.awt.Cursor

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
            val demoServerRunning by viewModel.demoServerRunning.collectAsState()
            val demoServerFixVersion by viewModel.demoServerFixVersion.collectAsState()
            val isDictionaryValid by viewModel.isDictionaryValid.collectAsState()
            val savedMessages = viewModel.savedMessages
            val editorState by viewModel.editorState.collectAsState()
            val currentProfileId = viewModel.getCurrentProfileId()
            val notifications = viewModel.notifications
            val density = LocalDensity.current
            val globalViewMode by viewModel.viewMode.collectAsState()
            val globalFilterRegex by viewModel.globalFilterRegex.collectAsState()
            val globalFilterShowIncoming by viewModel.globalFilterShowIncoming.collectAsState()
            val globalFilterShowOutgoing by viewModel.globalFilterShowOutgoing.collectAsState()
            val showLatencyPanel by viewModel.showLatencyPanel.collectAsState()
            val showScenariosRail by viewModel.showScenariosRail.collectAsState()
            val documents by viewModel.openDocuments.collectAsState()
            val workspace by viewModel.openScenarios.collectAsState()
            val activeDocumentId by viewModel.activeDocumentId.collectAsState()
            val activeDocument = documents.firstOrNull { it.id == activeDocumentId }
            // The tab strip is derived from both: a document of a scenario does not know the scenario's name
            // or whether it is dirty, because the draft is the scenario's. See documentTabsOf.
            val documentTabs = documentTabsOf(documents, workspace)

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

            var scenariosRailSplitRatio by remember { mutableStateOf(0.18f) }
            var documentSplitRatio by remember { mutableStateOf(0.5f) } // SPLIT only: the document's share of the centre
            var detailPanelSplitRatio by remember { mutableStateOf(0.2f) }
            var editorPanelSplitRatio by remember {
                mutableStateOf(0.28f)
            } // Message editor panel width (28% when description shown, 20% when hidden) - starts at 28% since description is visible by default
            var connectionPanelSplitRatio by remember { mutableStateOf(0.2f) }
            var searchResultsPanelHeight by remember { mutableStateOf(200.dp) } // Height of search results pane at bottom
            var latencyPanelSplitRatio by remember { mutableStateOf(0.25f) } // Latency panel width (25%)

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
                                activeDocumentId != null
                            ) {
                                // esc closes the focused document — and a dirty one asks first, in the tab
                                // strip, because the ViewModel is the only thing that knows it is dirty.
                                viewModel.requestCloseDocument(activeDocumentId!!)
                                true
                            } else {
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
                        onViewModeChange = { viewMode = it },
                        showMessageEditor = showMessageEditor,
                        showDetailPanel = showDetailPanel,
                        showConnectionPanel = showConnectionPanel,
                        showLatencyPanel = showLatencyPanel,
                        connectionProfiles = viewModel.connectionProfiles,
                        isDictionaryValid = isDictionaryValid,
                        globalSessionViewMode = globalViewMode,
                        globalFilterRegex = globalFilterRegex,
                        globalFilterShowIncoming = globalFilterShowIncoming,
                        globalFilterShowOutgoing = globalFilterShowOutgoing,
                        hideProtocolTags = viewModel.appSettings.hideProtocolTags,
                        onOpenMessageEditor = { viewModel.toggleMessageEditor() },
                        onToggleDetailPanel = { viewModel.toggleDetailPanel() },
                        onToggleConnectionPanel = { viewModel.toggleConnectionPanel() },
                        onToggleLatencyPanel = { viewModel.toggleLatencyPanel() },
                        onToggleGridView = { viewModel.toggleViewMode() },
                        onQuickConnect = { profileId, profile ->
                            viewModel.connectProfile(profileId, profile)
                        },
                        onGetProfileConnectionState = { profileId ->
                            viewModel.getProfileConnectionState(profileId)
                        },
                        onSearchAllSessions = { viewModel.toggleGlobalSearchDialog() },
                        onAddSeparatorToAll = { viewModel.addSeparatorToAllSessions() },
                        onClearAll = { viewModel.clearAllSessions() },
                        onGlobalFilterChange = { regex -> viewModel.setGlobalFilterRegex(regex) },
                        onGlobalFilterIncomingChange = { show -> viewModel.setGlobalFilterShowIncoming(show) },
                        onGlobalFilterOutgoingChange = { show -> viewModel.setGlobalFilterShowOutgoing(show) },
                        onToggleHideProtocolTags = { viewModel.toggleHideProtocolTags() },
                        onOpenSettings = { viewModel.toggleSettingsDialog() },
                        onOpenHelp = { viewModel.toggleHelpDialog() },
                        onOpenScenarios = { viewModel.toggleScenariosRail() },
                    )

                    // Settings Dialog
                    if (showSettingsDialog) {
                        SettingsDialog(
                            currentSettings = viewModel.appSettings,
                            dictionary = viewModel.dictionary,
                            onSave = { settings -> viewModel.saveAppSettings(settings) },
                            onDismiss = { viewModel.toggleSettingsDialog() },
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
                                        onRatioChange = { scenariosRailSplitRatio = it },
                                        maxWidthPx = maxWidthPx,
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
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(AppTheme.Separators.panelSeparatorWidth)
                                                    .fillMaxHeight()
                                                    .background(AppTheme.Separators.color)
                                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                    .pointerInput(maxWidthPx) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val newOffset =
                                                                editorPanelSplitRatio + (dragAmount.x / maxWidthPx)
                                                            editorPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                        }
                                                    },
                                        )
                                    }

                                    // Center panel - Tabs and Message display
                                    Column(modifier = Modifier.weight(1f)) {
                                        var isAtBottom by remember { mutableStateOf(true) }
                                        var scrollToBottomTrigger by remember { mutableStateOf(0) }

                                        val confirmingCloseId by viewModel.confirmingCloseId.collectAsState()
                                        TabBar(
                                            sessions = viewModel.sessions,
                                            activeIndex = viewModel.activeSessionIndex,
                                            viewMode = globalViewMode,
                                            // A session tab is the way back to the sessions — it is what
                                            // clears the centre's document selection, and the only thing that
                                            // does. It never moves because a document was focused.
                                            onTabClick = { index ->
                                                viewModel.setActiveSession(index)
                                                viewModel.showSessions()
                                            },
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
                                            documents = documentTabs,
                                            activeDocumentId = activeDocumentId,
                                            confirmingCloseId = confirmingCloseId,
                                            onFocusDocument = { viewModel.focusDocument(it) },
                                            onRequestCloseDocument = { viewModel.requestCloseDocument(it) },
                                            onConfirmCloseDocument = { viewModel.closeDocument(it) },
                                            onCancelCloseDocument = { viewModel.cancelCloseDocument() },
                                        )

                                        if (activeDocument != null) {
                                            ScenarioDocumentPane(viewModel, activeDocument, modifier = Modifier.weight(1f))
                                        } else {
                                        viewModel.activeSession?.let { session ->
                                            val messages by session.messages.collectAsState()
                                            val wrapText by session.wrapText.collectAsState()
                                            val recentlySentMessageTimestamp by session.recentlySentMessageTimestamp.collectAsState()
                                            val latencyTrackingEnabled by session.latencyTrackingEnabled.collectAsState()

                                            FixMessageDisplay(
                                                messages = messages,
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
                                                modifier = Modifier.weight(1f),
                                            )
                                        } ?: Box(
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "No active sessions. Click the connection button to connect to a FIX server.",
                                                color = Color(0xFF6A6A6A),
                                                fontSize = 14.sp,
                                            )
                                        }
                                        }

                                        // Search results pane at bottom (if visible)
                                        if (showSearchResultsPane) {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(AppTheme.Separators.panelSeparatorWidth)
                                                        .background(AppTheme.Separators.color)
                                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                                                        .pointerInput(Unit) {
                                                            detectDragGestures { change, dragAmount ->
                                                                change.consume()
                                                                val newHeight = searchResultsPanelHeight - dragAmount.y.dp
                                                                searchResultsPanelHeight = newHeight.coerceIn(100.dp, 600.dp)
                                                            }
                                                        },
                                            )

                                            Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                                AppSearchResultsPane(
                                                    viewModel = viewModel,
                                                    pinnedSearchResults = pinnedSearchResults,
                                                    selectedMessage = selectedMessage,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }

                                    // Message detail panel (if shown)
                                    if (showDetailPanel) {
                                        // Resizable divider for detail panel
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(AppTheme.Separators.panelSeparatorWidth)
                                                    .fillMaxHeight()
                                                    .background(AppTheme.Separators.color)
                                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                    .pointerInput(maxWidthPx) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val newOffset =
                                                                detailPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                            detailPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                        }
                                                    },
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
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(AppTheme.Separators.panelSeparatorWidth)
                                                    .fillMaxHeight()
                                                    .background(AppTheme.Separators.color)
                                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                    .pointerInput(maxWidthPx) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val newOffset =
                                                                connectionPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                            connectionPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                        }
                                                    },
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
                                                demoServerRunning = demoServerRunning,
                                                demoServerFixVersion = demoServerFixVersion,
                                                onStartDemoServer = { viewModel.startDemoServer(it) },
                                                onStopDemoServer = { viewModel.stopDemoServer() },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }

                                    // Latency panel (if shown)
                                    if (showLatencyPanel) {
                                        // Resizable divider for latency panel
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(AppTheme.Separators.panelSeparatorWidth)
                                                    .fillMaxHeight()
                                                    .background(AppTheme.Separators.color)
                                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                    .pointerInput(maxWidthPx) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val newOffset =
                                                                latencyPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                            latencyPanelSplitRatio = newOffset.coerceIn(0.1f, 0.5f)
                                                        }
                                                    },
                                        )

                                        Box(
                                            modifier =
                                                Modifier.width(
                                                    with(density) { (maxWidthPx * latencyPanelSplitRatio).toDp() },
                                                ),
                                        ) {
                                            viewModel.activeSession?.let { session ->
                                                val latencyTrackingService = session.getLatencyTrackingService()
                                                val captureStatus by session.captureStatus.collectAsState()

                                                if (latencyTrackingService != null) {
                                                    val statistics by latencyTrackingService.statistics.collectAsState()
                                                    val aggregateStatistics by latencyTrackingService.aggregateStatistics.collectAsState()
                                                    val recentPairs by latencyTrackingService.recentPairs.collectAsState()
                                                    val timestampSource by latencyTrackingService.timestampSource.collectAsState()

                                                    LatencyPanel(
                                                        statistics = statistics,
                                                        aggregateStatistics = aggregateStatistics,
                                                        recentPairs = recentPairs,
                                                        captureStatus = captureStatus,
                                                        timestampSource = timestampSource,
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
                            if (showScenariosRail || showDetailPanel || showMessageEditor || showConnectionPanel || showLatencyPanel) {
                                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                                    val maxWidthPx = with(density) { maxWidth.toPx() }

                                    Row(modifier = Modifier.fillMaxSize()) {
                                        ScenariosRailDock(
                                            viewModel = viewModel,
                                            show = showScenariosRail,
                                            ratio = scenariosRailSplitRatio,
                                            onRatioChange = { scenariosRailSplitRatio = it },
                                            maxWidthPx = maxWidthPx,
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
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .width(1.dp)
                                                        .fillMaxHeight()
                                                        .background(Color(0xFF3A3A3A))
                                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                        .pointerInput(maxWidthPx) {
                                                            detectDragGestures { change, dragAmount ->
                                                                change.consume()
                                                                val newOffset =
                                                                    editorPanelSplitRatio + (dragAmount.x / maxWidthPx)
                                                                editorPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                            }
                                                        },
                                            )
                                        }

                                        // Center panel - Split view (with the document area beside it, if any)
                                        Column(modifier = Modifier.weight(1f)) {
                                            SplitCentre(
                                                viewModel = viewModel,
                                                orientation = splitOrientation,
                                                globalViewMode = globalViewMode,
                                                selectedMessage = selectedMessage,
                                                hasDocuments = documents.isNotEmpty(),
                                                ratio = documentSplitRatio,
                                                onRatioChange = { documentSplitRatio = it },
                                            )

                                            // Search results pane at bottom (if visible)
                                            if (showSearchResultsPane) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(AppTheme.Separators.panelSeparatorWidth)
                                                            .background(AppTheme.Separators.color)
                                                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                                                            .pointerInput(Unit) {
                                                                detectDragGestures { change, dragAmount ->
                                                                    change.consume()
                                                                    val newHeight = searchResultsPanelHeight - dragAmount.y.dp
                                                                    searchResultsPanelHeight = newHeight.coerceIn(100.dp, 600.dp)
                                                                }
                                                            },
                                                )

                                                Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                                    AppSearchResultsPane(
                                                        viewModel = viewModel,
                                                        pinnedSearchResults = pinnedSearchResults,
                                                        selectedMessage = selectedMessage,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                        }

                                        // Message detail panel (if shown)
                                        if (showDetailPanel) {
                                            // Resizable divider for detail panel
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .width(1.dp)
                                                        .fillMaxHeight()
                                                        .background(Color(0xFF3A3A3A))
                                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                        .pointerInput(maxWidthPx) {
                                                            detectDragGestures { change, dragAmount ->
                                                                change.consume()
                                                                val newOffset =
                                                                    detailPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                                detailPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                            }
                                                        },
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
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .width(1.dp)
                                                        .fillMaxHeight()
                                                        .background(Color(0xFF3A3A3A))
                                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                        .pointerInput(maxWidthPx) {
                                                            detectDragGestures { change, dragAmount ->
                                                                change.consume()
                                                                val newOffset =
                                                                    connectionPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                                connectionPanelSplitRatio = newOffset.coerceIn(0.1f, 0.6f)
                                                            }
                                                        },
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
                                                    demoServerRunning = demoServerRunning,
                                                    demoServerFixVersion = demoServerFixVersion,
                                                    onStartDemoServer = { viewModel.startDemoServer(it) },
                                                    onStopDemoServer = { viewModel.stopDemoServer() },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }

                                        // Latency panel (if shown)
                                        if (showLatencyPanel) {
                                            // Resizable divider for latency panel
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .width(AppTheme.Separators.panelSeparatorWidth)
                                                        .fillMaxHeight()
                                                        .background(AppTheme.Separators.color)
                                                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                                                        .pointerInput(maxWidthPx) {
                                                            detectDragGestures { change, dragAmount ->
                                                                change.consume()
                                                                val newOffset =
                                                                    latencyPanelSplitRatio - (dragAmount.x / maxWidthPx)
                                                                latencyPanelSplitRatio = newOffset.coerceIn(0.1f, 0.5f)
                                                            }
                                                        },
                                            )

                                            Box(
                                                modifier =
                                                    Modifier.width(
                                                        with(density) { (maxWidthPx * latencyPanelSplitRatio).toDp() },
                                                    ),
                                            ) {
                                                viewModel.activeSession?.let { session ->
                                                    val latencyTrackingService = session.getLatencyTrackingService()
                                                    val captureStatus by session.captureStatus.collectAsState()

                                                    if (latencyTrackingService != null) {
                                                        val statistics by latencyTrackingService.statistics.collectAsState()
                                                        val aggregateStatistics by latencyTrackingService.aggregateStatistics.collectAsState()
                                                        val recentPairs by latencyTrackingService.recentPairs.collectAsState()
                                                        val timestampSource by latencyTrackingService.timestampSource.collectAsState()

                                                        LatencyPanel(
                                                            statistics = statistics,
                                                            aggregateStatistics = aggregateStatistics,
                                                            recentPairs = recentPairs,
                                                            captureStatus = captureStatus,
                                                            timestampSource = timestampSource,
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
                                        hasDocuments = documents.isNotEmpty(),
                                        ratio = documentSplitRatio,
                                        onRatioChange = { documentSplitRatio = it },
                                    )

                                    // Search results pane at bottom (if visible)
                                    if (showSearchResultsPane) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(AppTheme.Separators.panelSeparatorWidth)
                                                    .background(AppTheme.Separators.color)
                                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                                                    .pointerInput(Unit) {
                                                        detectDragGestures { change, dragAmount ->
                                                            change.consume()
                                                            val newHeight = searchResultsPanelHeight - dragAmount.y.dp
                                                            searchResultsPanelHeight = newHeight.coerceIn(100.dp, 600.dp)
                                                        }
                                                    },
                                        )

                                        Box(modifier = Modifier.height(searchResultsPanelHeight)) {
                                            AppSearchResultsPane(
                                                viewModel = viewModel,
                                                pinnedSearchResults = pinnedSearchResults,
                                                selectedMessage = selectedMessage,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
    onRatioChange: (Float) -> Unit,
    maxWidthPx: Float,
) {
    if (!show) return
    val density = LocalDensity.current
    Box(modifier = Modifier.width(with(density) { (maxWidthPx * ratio).toDp() })) {
        ScenariosRail(viewModel, modifier = Modifier.fillMaxSize())
    }
    Box(
        modifier =
            Modifier
                .width(AppTheme.Separators.panelSeparatorWidth)
                .fillMaxHeight()
                .background(AppTheme.Separators.color)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(maxWidthPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onRatioChange((ratio + (dragAmount.x / maxWidthPx)).coerceIn(0.1f, 0.45f))
                    }
                },
    )
}

/**
 * The SPLIT layouts' centre: the session grid, and — while any scenario document is open — **the document
 * beside it**, split along the axis the author already chose. That is the promise SPLIT makes and a document
 * tab must not break it: SPLIT exists to keep more than one thing on screen, so a document that took the whole
 * centre would have quietly turned SPLIT back into TABS.
 *
 * With no document open this is exactly the SplitView that was here before, and nothing else is composed.
 */
@Composable
private fun ColumnScope.SplitCentre(
    viewModel: FixMessageViewModel,
    orientation: SplitOrientation,
    globalViewMode: com.knapsack.fixtool.model.FixMessageSession.ViewMode,
    selectedMessage: FixMessage?,
    hasDocuments: Boolean,
    ratio: Float,
    onRatioChange: (Float) -> Unit,
) {
    val grid: @Composable (Modifier) -> Unit = { m ->
        SplitView(
            sessions = viewModel.sessions,
            dictionary = viewModel.dictionary,
            viewMode = globalViewMode,
            onCloseSession = { index -> viewModel.closeSession(index) },
            onMoveSession = { from, to -> viewModel.moveSession(from, to) },
            selectedMessage = selectedMessage,
            onSelectMessage = { m -> viewModel.selectMessageFromGrid(m) },
            onDiffSelected = { a, b -> viewModel.openDiffSelected(a, b) },
            onPasteMessage = { rawMessage -> viewModel.pasteAndDisplayMessage(rawMessage) },
            orientation = orientation,
            gridViewColumns = viewModel.appSettings.gridViewColumns,
            assertionResults = viewModel.assertionResults,
            appSettings = viewModel.appSettings,
            modifier = m,
        )
    }
    if (!hasDocuments) {
        grid(Modifier.weight(1f))
        return
    }
    val documentWeight = ratio.coerceIn(0.2f, 0.85f)
    BoxWithConstraints(modifier = Modifier.weight(1f)) {
        val density = LocalDensity.current
        if (orientation == SplitOrientation.HORIZONTAL) {
            val widthPx = with(density) { maxWidth.toPx() }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f - documentWeight)) { grid(Modifier.fillMaxSize()) }
                Box(
                    modifier =
                        Modifier
                            .width(AppTheme.Separators.panelSeparatorWidth)
                            .fillMaxHeight()
                            .background(AppTheme.Separators.color)
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                            .pointerInput(widthPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onRatioChange((documentWeight - (dragAmount.x / widthPx)).coerceIn(0.2f, 0.85f))
                                }
                            },
                )
                Box(modifier = Modifier.weight(documentWeight)) { ScenarioDocumentArea(viewModel) }
            }
        } else {
            val heightPx = with(density) { maxHeight.toPx() }
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f - documentWeight)) { grid(Modifier.fillMaxSize()) }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(AppTheme.Separators.panelSeparatorWidth)
                            .background(AppTheme.Separators.color)
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                            .pointerInput(heightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onRatioChange((documentWeight - (dragAmount.y / heightPx)).coerceIn(0.2f, 0.85f))
                                }
                            },
                )
                Box(modifier = Modifier.weight(documentWeight)) { ScenarioDocumentArea(viewModel) }
            }
        }
    }
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

    MessageEditorPanel(
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
        onEditAssertion = selectedMessage?.let { msg -> ({ tag: Int? -> viewModel.openScenarioEditorForFailure(msg, tag) }) },
        onDiffAgainst = { msg -> viewModel.openDiffAgainst(msg) },
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
