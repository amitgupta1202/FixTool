package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.jetbrains.compose.ui.tooling.preview.Preview
import java.awt.Cursor

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF3A3A3A),
        secondary = Color(0xFF2B2B2B),
        background = Color(0xFF1E1E1E),
        surface = Color(0xFF2B2B2B),
        onPrimary = Color(0xFFE0E0E0),
        onSecondary = Color(0xFFE0E0E0),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
    )

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App(
    modifier: Modifier = Modifier,
    onViewModelCreated: (FixMessageViewModel) -> Unit = {},
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
    ) {
        val viewModel: FixMessageViewModel = viewModel { FixMessageViewModel() }

        // Expose viewModel reference to parent
        LaunchedEffect(viewModel) {
            onViewModelCreated(viewModel)
        }
        var viewMode by rememberSaveable { mutableStateOf(ViewMode.SPLIT_HORIZONTAL) }

        // Collect global state
        val selectedMessage by viewModel.selectedMessage.collectAsState()
        val showDetailPanel by viewModel.showDetailPanel.collectAsState()
        val showMessageEditor by viewModel.showMessageEditor.collectAsState()
        val showConnectionPanel by viewModel.showConnectionPanel.collectAsState()
        val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
        val showGlobalSearchDialog by viewModel.showGlobalSearchDialog.collectAsState()
        val globalSearchQuery by viewModel.globalSearchQuery.collectAsState()
        val globalSearchResults by viewModel.globalSearchResults.collectAsState()
        val showSearchResultsPane by viewModel.showSearchResultsPane.collectAsState()
        val pinnedSearchResults by viewModel.pinnedSearchResults.collectAsState()
        val demoServerRunning by viewModel.demoServerRunning.collectAsState()
        val isDictionaryValid by viewModel.isDictionaryValid.collectAsState()
        val savedMessages = viewModel.savedMessages
        val currentLoadedMessageName by viewModel.currentLoadedMessageName.collectAsState()
        val currentProfileId = viewModel.getCurrentProfileId()
        val notifications = viewModel.notifications
        val density = LocalDensity.current
        val globalViewMode by viewModel.viewMode.collectAsState()
        val globalFilterRegex by viewModel.globalFilterRegex.collectAsState()
        val globalFilterShowIncoming by viewModel.globalFilterShowIncoming.collectAsState()
        val globalFilterShowOutgoing by viewModel.globalFilterShowOutgoing.collectAsState()

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

        var detailPanelSplitRatio by remember { mutableStateOf(0.2f) }
        var editorPanelSplitRatio by remember {
            mutableStateOf(0.28f)
        } // Message editor panel width (28% when description shown, 20% when hidden) - starts at 28% since description is visible by default
        var connectionPanelSplitRatio by remember { mutableStateOf(0.2f) }
        var searchResultsPanelHeight by remember { mutableStateOf(200.dp) } // Height of search results pane at bottom

        Box(
            modifier = modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    // Handle Cmd+F (Mac) or Ctrl+F (Windows/Linux) to open search
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.F &&
                        (event.isMetaPressed || event.isCtrlPressed)
                    ) {
                        viewModel.toggleGlobalSearchDialog()
                        true // Consume the event
                    } else {
                        false // Don't consume other events
                    }
                }
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
                    demoServerRunning = demoServerRunning,
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
                    onToggleDemoServer = {
                        if (demoServerRunning) {
                            viewModel.stopDemoServer()
                        } else {
                            viewModel.startDemoServer()
                        }
                    },
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
                        // All panels in same row: editor, tabs, detail
                        BoxWithConstraints(modifier = Modifier.weight(1f)) {
                            val maxWidthPx = with(density) { maxWidth.toPx() }

                            Row(modifier = Modifier.fillMaxSize()) {
                                // Leftmost panel - Message editor (if shown)
                                if (showMessageEditor) {
                                    Box(
                                        modifier =
                                            Modifier.width(
                                                with(density) { (maxWidthPx * editorPanelSplitRatio).toDp() },
                                            ),
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
                                            onSend = { fields ->
                                                val rawMessage = fields.toRawMessage()
                                                viewModel.sendMessage(rawMessage)
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
                                                editorPanelSplitRatio = if (showingDescription) 0.28f else 0.20f
                                            },
                                            onSaveMessage = { name, fields, profileId ->
                                                viewModel.saveEditorMessage(
                                                    name,
                                                    fields,
                                                    profileId,
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
                                            connectionProfiles = viewModel.connectionProfiles,
                                            currentProfileId = currentProfileId,
                                            currentLoadedMessageName = currentLoadedMessageName,
                                            onSessionChange = { session -> viewModel.setActiveSessionByObject(session) },
                                            onError = { errorMsg ->
                                                viewModel.showNotification(
                                                    errorMsg,
                                                    NotificationType.ERROR,
                                                )
                                            },
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
                                    )

                                    viewModel.activeSession?.let { session ->
                                        val messages by session.messages.collectAsState()
                                        val wrapText by session.wrapText.collectAsState()
                                        val recentlySentMessageTimestamp by session.recentlySentMessageTimestamp.collectAsState()

                                        FixMessageDisplay(
                                            messages = messages,
                                            viewMode = globalViewMode,
                                            dictionary = viewModel.dictionary,
                                            wrapText = wrapText,
                                            selectedMessage = selectedMessage,
                                            recentlySentMessageTimestamp = recentlySentMessageTimestamp,
                                            onSelectMessage = { message -> viewModel.selectMessage(message) },
                                            showDetailPanel = false,
                                            hideProtocolTags = viewModel.appSettings.hideProtocolTags,
                                            gridViewColumns = viewModel.appSettings.gridViewColumns,
                                            appSettings = viewModel.appSettings,
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
                                            SearchResultsPane(
                                                searchResults = pinnedSearchResults,
                                                selectedMessage = selectedMessage,
                                                dictionary = viewModel.dictionary,
                                                appSettings = viewModel.appSettings,
                                                onSelectResult = { result -> viewModel.navigateToSearchResult(result) },
                                                onClose = { viewModel.closeSearchResultsPane() },
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
                                        MessageDetailPanel(
                                            message = selectedMessage,
                                            dictionary = viewModel.dictionary,
                                            onClose = { viewModel.toggleDetailPanel() },
                                            onPasteMessage = { rawMessage ->
                                                viewModel.pasteAndDisplayMessage(rawMessage)
                                            },
                                            appSettings = viewModel.appSettings,
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
                                            onClose = { viewModel.toggleConnectionPanel() },
                                            modifier = Modifier.fillMaxSize(),
                                        )
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

                        // Wrap content in split pane if detail panel, message editor, or connection panel is shown
                        if (showDetailPanel || showMessageEditor || showConnectionPanel) {
                            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                                val maxWidthPx = with(density) { maxWidth.toPx() }

                                Row(modifier = Modifier.fillMaxSize()) {
                                    // Leftmost panel - Message editor (if shown)
                                    if (showMessageEditor) {
                                        Box(
                                            modifier =
                                                Modifier.width(
                                                    with(density) { (maxWidthPx * editorPanelSplitRatio).toDp() },
                                                ),
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
                                                onSend = { fields ->
                                                    val rawMessage =
                                                        fields.joinToString("|") { "${it.tag}=${it.value}" } + "|"
                                                    viewModel.sendMessage(rawMessage)
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
                                                    editorPanelSplitRatio = if (showingDescription) 0.28f else 0.20f
                                                },
                                                onSaveMessage = { name, fields, profileId ->
                                                    viewModel.saveEditorMessage(
                                                        name,
                                                        fields,
                                                        profileId,
                                                    )
                                                },
                                                savedMessages = savedMessages,
                                                onLoadMessage = { savedMessage ->
                                                    viewModel.loadEditorMessage(
                                                        savedMessage,
                                                    )
                                                },
                                                onDeleteMessage = { messageId, profileId ->
                                                    viewModel.deleteSavedMessage(
                                                        messageId,
                                                        profileId,
                                                    )
                                                },
                                                connectionProfiles = viewModel.connectionProfiles,
                                                currentProfileId = currentProfileId,
                                                currentLoadedMessageName = currentLoadedMessageName,
                                                onSessionChange = { session -> viewModel.setActiveSessionByObject(session) },
                                                onError = { errorMsg ->
                                                    viewModel.showNotification(
                                                        errorMsg,
                                                        NotificationType.ERROR,
                                                    )
                                                },
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

                                    // Center panel - Split view
                                    Column(modifier = Modifier.weight(1f)) {
                                        SplitView(
                                            sessions = viewModel.sessions,
                                            dictionary = viewModel.dictionary,
                                            viewMode = globalViewMode,
                                            onCloseSession = { index -> viewModel.closeSession(index) },
                                            onMoveSession = { from, to -> viewModel.moveSession(from, to) },
                                            selectedMessage = selectedMessage,
                                            onSelectMessage = { message -> viewModel.selectMessage(message) },
                                            onPasteMessage = { rawMessage ->
                                                viewModel.pasteAndDisplayMessage(rawMessage)
                                            },
                                            orientation = splitOrientation,
                                            gridViewColumns = viewModel.appSettings.gridViewColumns,
                                            appSettings = viewModel.appSettings,
                                            modifier = Modifier.weight(1f),
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
                                                SearchResultsPane(
                                                    searchResults = pinnedSearchResults,
                                                    selectedMessage = selectedMessage,
                                                    dictionary = viewModel.dictionary,
                                                    appSettings = viewModel.appSettings,
                                                    onSelectResult = { result -> viewModel.navigateToSearchResult(result) },
                                                    onClose = { viewModel.closeSearchResultsPane() },
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
                                            MessageDetailPanel(
                                                message = selectedMessage,
                                                dictionary = viewModel.dictionary,
                                                onClose = { viewModel.toggleDetailPanel() },
                                                onPasteMessage = { rawMessage ->
                                                    viewModel.pasteAndDisplayMessage(rawMessage)
                                                },
                                                appSettings = viewModel.appSettings,
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
                                                onClose = { viewModel.toggleConnectionPanel() },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                SplitView(
                                    sessions = viewModel.sessions,
                                    dictionary = viewModel.dictionary,
                                    viewMode = globalViewMode,
                                    onCloseSession = { index -> viewModel.closeSession(index) },
                                    onMoveSession = { from, to -> viewModel.moveSession(from, to) },
                                    selectedMessage = selectedMessage,
                                    onSelectMessage = { message -> viewModel.selectMessage(message) },
                                    onPasteMessage = { rawMessage -> viewModel.pasteAndDisplayMessage(rawMessage) },
                                    orientation = splitOrientation,
                                    gridViewColumns = viewModel.appSettings.gridViewColumns,
                                    appSettings = viewModel.appSettings,
                                    modifier = Modifier.weight(1f),
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
                                        SearchResultsPane(
                                            searchResults = pinnedSearchResults,
                                            selectedMessage = selectedMessage,
                                            dictionary = viewModel.dictionary,
                                            appSettings = viewModel.appSettings,
                                            onSelectResult = { result -> viewModel.navigateToSearchResult(result) },
                                            onClose = { viewModel.closeSearchResultsPane() },
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
