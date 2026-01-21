package com.knapsack.fixtool.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.knapsack.fixtool.model.*
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.service.demo.DemoServerManager
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

class FixMessageViewModel(
    testSettingsDir: String? = null,
) : ViewModel() {
    private val logger =
        NotifyingLogger(
            FixMessageViewModel::class.java,
            onNotify = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
        )

    // Coroutine scope for this ViewModel
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessions = mutableStateListOf<FixMessageSession>()
    val sessions: List<FixMessageSession> = _sessions

    private val _activeSessionIndex = mutableStateOf(-1) // -1 means no session selected
    val activeSessionIndex: Int
        get() = _activeSessionIndex.value
    val activeSessionIndexState: State<Int> = _activeSessionIndex

    val activeSession: FixMessageSession?
        get() = if (_activeSessionIndex.value >= 0) _sessions.getOrNull(_activeSessionIndex.value) else null

    private val _activeSessionState = mutableStateOf<FixMessageSession?>(null)
    val activeSessionState: State<FixMessageSession?> = _activeSessionState

    // Selected profile for message editor (can be set even if profile is disconnected)
    private val _selectedEditorProfile = mutableStateOf<FixConnectionProfile?>(null)
    val selectedEditorProfile: State<FixConnectionProfile?> = _selectedEditorProfile

    private val _dictionary = mutableStateOf(FixDictionaryAdapter.createDefault())
    val dictionary: FixDictionary
        get() = _dictionary.value

    // Global message selection state (shared across all panes/sessions)
    private val _selectedMessage = MutableStateFlow<FixMessage?>(null)
    val selectedMessage: StateFlow<FixMessage?> = _selectedMessage.asStateFlow()

    // Global detail panel visibility (shared across all panes/sessions)
    private val _showDetailPanel = MutableStateFlow(false)
    val showDetailPanel: StateFlow<Boolean> = _showDetailPanel.asStateFlow()

    // Message editor dialog visibility
    private val _showMessageEditor = MutableStateFlow(false)
    val showMessageEditor: StateFlow<Boolean> = _showMessageEditor.asStateFlow()

    // Connection panel visibility
    private val _showConnectionPanel = MutableStateFlow(false)
    val showConnectionPanel: StateFlow<Boolean> = _showConnectionPanel.asStateFlow()

    // Settings dialog visibility
    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    // Help dialog visibility
    private val _showHelpDialog = MutableStateFlow(false)
    val showHelpDialog: StateFlow<Boolean> = _showHelpDialog.asStateFlow()

    // Latency panel visibility
    private val _showLatencyPanel = MutableStateFlow(false)
    val showLatencyPanel: StateFlow<Boolean> = _showLatencyPanel.asStateFlow()

    // Global search across all sessions
    private val _showGlobalSearchDialog = MutableStateFlow(false)
    val showGlobalSearchDialog: StateFlow<Boolean> = _showGlobalSearchDialog.asStateFlow()

    private val _globalSearchQuery = MutableStateFlow("")
    val globalSearchQuery: StateFlow<String> = _globalSearchQuery.asStateFlow()

    data class SearchResult(
        val session: FixMessageSession,
        val message: FixMessage,
        val matchedText: String,
        val messageTypeDescription: String,
        val msgSeqNum: Int?,
        val senderCompId: String?,
        val sessionUsername: String,
    )

    private val _globalSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val globalSearchResults: StateFlow<List<SearchResult>> = _globalSearchResults.asStateFlow()

    // Search results pane (persistent search results at bottom of screen)
    private val _showSearchResultsPane = MutableStateFlow(false)
    val showSearchResultsPane: StateFlow<Boolean> = _showSearchResultsPane.asStateFlow()

    private val _pinnedSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val pinnedSearchResults: StateFlow<List<SearchResult>> = _pinnedSearchResults.asStateFlow()

    // Global filter across all sessions
    private val _globalFilterRegex = MutableStateFlow("")
    val globalFilterRegex: StateFlow<String> = _globalFilterRegex.asStateFlow()

    private val _globalFilterShowIncoming = MutableStateFlow(true)
    val globalFilterShowIncoming: StateFlow<Boolean> = _globalFilterShowIncoming.asStateFlow()

    private val _globalFilterShowOutgoing = MutableStateFlow(true)
    val globalFilterShowOutgoing: StateFlow<Boolean> = _globalFilterShowOutgoing.asStateFlow()

    // Global view mode (applies to all sessions)
    private val _viewMode = MutableStateFlow(FixMessageSession.ViewMode.PARSED) // Will be initialized from settings

    // Message maps for template expressions - stores latest message of each type
    // These can be referenced in template expressions like: ${incoming["D"].valueOfTag(11)}
    private val _incomingMessagesByType = mutableMapOf<String, FixMessage>()
    val incomingMessagesByType: Map<String, FixMessage>
        get() = _incomingMessagesByType.toMap()

    private val _outgoingMessagesByType = mutableMapOf<String, FixMessage>()
    val outgoingMessagesByType: Map<String, FixMessage>
        get() = _outgoingMessagesByType.toMap()
    val viewMode: StateFlow<FixMessageSession.ViewMode> = _viewMode.asStateFlow()

    // App settings (loaded first before other services)
    private val settingsService =
        AppSettingsService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customSettingsDir = testSettingsDir,
        )
    private val _appSettings = mutableStateOf(AppSettings.default())
    val appSettings: AppSettings
        get() = _appSettings.value

    // Connection profiles (lazy-initialized to use appSettings paths)
    private val profileService by lazy {
        ConnectionProfileService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customPath = _appSettings.value.connectionProfilesPath,
        )
    }
    private val _connectionProfiles = mutableStateListOf<FixConnectionProfile>()
    val connectionProfiles: List<FixConnectionProfile> = _connectionProfiles

    // Saved messages (lazy-initialized to use appSettings paths)
    private val savedMessagesService by lazy {
        SavedMessagesService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customPath = _appSettings.value.savedMessagesPath,
        )
    }
    private val _savedMessages = mutableStateListOf<SavedFixMessage>()
    val savedMessages: List<SavedFixMessage> = _savedMessages

    // Track message editor state (new, clean, dirty)
    private val _editorState =
        MutableStateFlow<com.knapsack.fixtool.model.MessageEditorState>(
            com.knapsack.fixtool.model.MessageEditorState.New,
        )
    val editorState: StateFlow<com.knapsack.fixtool.model.MessageEditorState> = _editorState

    // Backwards compatibility: expose message name from editor state
    val currentLoadedMessageName: StateFlow<String?> =
        _editorState
            .map { it.messageNameOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Track which session belongs to which profile
    private val profileToSessionMap = mutableMapOf<String, Int>()

    // Demo server state
    val demoServerRunning: StateFlow<Boolean> = DemoServerManager.isRunning

    // Message editor state (persisted when opening/closing)
    private val _editorFields = mutableStateListOf<FixField>()
    val editorFields: List<FixField> = _editorFields

    private val _editorSelectedFieldIndex = mutableStateOf(0)
    val editorSelectedFieldIndex: Int
        get() = _editorSelectedFieldIndex.value

    // Multi-selection support - stores selected indices as a sorted set
    private val _editorSelectedIndices = mutableStateListOf<Int>()
    val editorSelectedIndices: List<Int> = _editorSelectedIndices

    // Message validation state
    private val _editorValidationErrors = mutableStateListOf<String>()
    val editorValidationErrors: List<String> = _editorValidationErrors

    // Notification state
    private val _notifications = mutableStateListOf<com.knapsack.fixtool.model.Notification>()
    val notifications: List<com.knapsack.fixtool.model.Notification> = _notifications

    // Data dictionary validation state
    private val _isDictionaryValid = MutableStateFlow(true)
    val isDictionaryValid: StateFlow<Boolean> = _isDictionaryValid.asStateFlow()

    private val _dictionaryErrorMessage = MutableStateFlow<String?>(null)
    val dictionaryErrorMessage: StateFlow<String?> = _dictionaryErrorMessage.asStateFlow()

    init {
        // Load app settings first (this also loads the data dictionary)
        loadAppSettings()

        // Initialize global view mode from settings
        _viewMode.value =
            if (appSettings.defaultViewMode.lowercase() == "grid") {
                FixMessageSession.ViewMode.PARSED
            } else {
                FixMessageSession.ViewMode.RAW
            }

        // Validate dictionary on startup
        validateDataDictionary()

        // Load saved connection profiles
        loadConnectionProfiles()

        // Initialize editor with one blank field
        if (_editorFields.isEmpty()) {
            _editorFields.add(FixField())
            _editorSelectedIndices.add(0)
        }

        // Set up demo profile management
        DemoServerManager.onDemoProfilesChanged = { demoProfiles ->
            handleDemoProfilesChanged(demoProfiles)
        }

        // Set up demo template management
        DemoServerManager.onDemoTemplatesChanged = { _ ->
            // Reload saved messages to reflect template changes
            loadSavedMessagesForActiveSession()
        }
    }

    private fun loadAppSettings() {
        _appSettings.value = settingsService.loadSettings()
        // Load data dictionary from app settings after loading settings
        loadDictionaryFromSettings()
    }

    private fun loadDictionaryFromSettings() {
        try {
            val dictionaryPath = _appSettings.value.defaultDataDictionary
            if (dictionaryPath.isNotBlank()) {
                val dictionaryFile = File(dictionaryPath)
                if (dictionaryFile.exists()) {
                    _dictionary.value = FixDictionaryAdapter.fromFile(dictionaryFile)
                    logger.info("Loaded data dictionary for UI from: {}", dictionaryPath)
                    _isDictionaryValid.value = true
                    _dictionaryErrorMessage.value = null
                } else {
                    logger.warn("Data dictionary file not found: {}, falling back to bundled FIX 4.4", dictionaryPath)
                    loadBundledDictionary()
                }
            } else {
                // No custom dictionary configured - use bundled FIX 4.4 dictionary
                logger.info("No custom data dictionary configured, using bundled FIX 4.4")
                loadBundledDictionary()
            }
        } catch (e: Exception) {
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load data dictionary: ${e.message}"
            logger.error("Failed to load data dictionary: ${e.message}", e, notifyUser = true)
            // Try bundled dictionary as last resort
            loadBundledDictionary()
        }
    }

    /**
     * Loads the bundled FIX 4.4 dictionary from classpath resources.
     * This is used as the default when no custom dictionary is configured.
     */
    private fun loadBundledDictionary() {
        try {
            _dictionary.value = FixDictionaryAdapter.fromResource()
            if (_dictionary.value.isLoaded()) {
                logger.info("Loaded bundled FIX 4.4 dictionary")
                _isDictionaryValid.value = true
                _dictionaryErrorMessage.value = null
            } else {
                logger.error("Failed to load bundled dictionary")
                _isDictionaryValid.value = false
                _dictionaryErrorMessage.value = "Failed to load bundled FIX 4.4 dictionary"
            }
        } catch (e: Exception) {
            logger.error("Failed to load bundled dictionary: ${e.message}", e)
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load bundled dictionary: ${e.message}"
        }
    }

    /**
     * Validates the data dictionary configuration and shows an error notification if invalid
     */
    private fun validateDataDictionary() {
        if (!_isDictionaryValid.value) {
            val errorMsg = _dictionaryErrorMessage.value ?: "Data dictionary is not configured"
            showNotification(errorMsg, com.knapsack.fixtool.model.NotificationType.ERROR)
        }
    }

    private fun createNewSession(title: String = "Session"): FixMessageSession {
        val session =
            FixMessageSession(
                title = title,
                bufferSize = _appSettings.value.sessionBufferSize,
                onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            )
        _sessions.add(session)
        // Do NOT auto-select session on connect - let user or template loading do it
        return session
    }

    fun closeSession(index: Int) {
        if (index in _sessions.indices) {
            // Remove profile mapping for this session
            profileToSessionMap.entries.removeIf { it.value == index }

            _sessions[index].destroy()
            _sessions.removeAt(index)

            // Adjust active index if needed
            if (_sessions.isEmpty()) {
                _activeSessionIndex.value = -1 // No sessions, so no selection
                _activeSessionState.value = null
            } else if (_activeSessionIndex.value >= _sessions.size) {
                _activeSessionIndex.value = _sessions.size - 1
                _activeSessionState.value = _sessions.getOrNull(_activeSessionIndex.value)
            } else if (_activeSessionIndex.value > index) {
                _activeSessionIndex.value--
                _activeSessionState.value = _sessions.getOrNull(_activeSessionIndex.value)
            } else if (_activeSessionIndex.value == index) {
                // If closing the active session, select the first available session
                _activeSessionIndex.value = 0
                _activeSessionState.value = _sessions.getOrNull(0)
            }

            // Adjust all session indices in the map that are greater than the closed index
            val updatedMap =
                profileToSessionMap.mapValues { (_, sessionIndex) ->
                    if (sessionIndex > index) sessionIndex - 1 else sessionIndex
                }
            profileToSessionMap.clear()
            profileToSessionMap.putAll(updatedMap)
        }
    }

    fun setActiveSession(index: Int) {
        if (index == -1 || index in _sessions.indices) {
            val session = if (index >= 0) _sessions.getOrNull(index) else null
            logger.info("setActiveSession(index=$index): Switching to session: ${session?.title} (ID: ${session?.id})")
            _activeSessionIndex.value = index
            _activeSessionState.value = session

            // Sync selectedEditorProfile to match the selected session
            val profileId = profileToSessionMap.entries.find { it.value == index }?.key
            val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
            _selectedEditorProfile.value = profile
            logger.info("setActiveSession: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")

            // Reload messages when session selection changes
            loadSavedMessagesForActiveSession()
        }
    }

    fun setActiveSessionByObject(session: FixMessageSession?) {
        logger.info("setActiveSessionByObject: Switching to session: ${session?.title} (ID: ${session?.id})")
        if (session == null) {
            _activeSessionIndex.value = -1
            _activeSessionState.value = null
            _selectedEditorProfile.value = null
            logger.info("setActiveSessionByObject: Cleared selectedEditorProfile")
        } else {
            val index = _sessions.indexOf(session)
            if (index >= 0) {
                logger.info("setActiveSessionByObject: Found session at index $index")
                _activeSessionIndex.value = index
                _activeSessionState.value = session

                // Sync selectedEditorProfile to match the selected session
                val profileId = profileToSessionMap.entries.find { it.value == index }?.key
                val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                _selectedEditorProfile.value = profile
                logger.info("setActiveSessionByObject: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")
            } else {
                logger.warn("setActiveSessionByObject: Session not found in sessions list!")
            }
        }
        loadSavedMessagesForActiveSession()
    }

    /**
     * Sets the selected profile for the message editor.
     * This can be a connected or disconnected profile.
     * If the profile has a session, that session will also be made active.
     */
    fun setSelectedEditorProfile(profile: FixConnectionProfile?) {
        logger.info("setSelectedEditorProfile: ${profile?.name} (ID: ${profile?.id})")
        _selectedEditorProfile.value = profile

        // If profile has a session, make it active
        if (profile != null) {
            val session = getProfileSession(profile.id)
            setActiveSessionByObject(session)
        } else {
            // No profile selected, clear active session
            setActiveSessionByObject(null)
        }
    }

    fun selectMessage(message: FixMessage?) {
        _selectedMessage.value = message
        // Auto-show detail panel when a message is selected
        if (message != null && !_showDetailPanel.value) {
            _showDetailPanel.value = true
        }
        // Auto-select the tab/session that contains this message
        if (message != null) {
            val sessionIndex =
                _sessions.indexOfFirst { session ->
                    session.messages.value.contains(message)
                }
            if (sessionIndex >= 0 && sessionIndex != _activeSessionIndex.value) {
                _activeSessionIndex.value = sessionIndex
                _activeSessionState.value = _sessions.getOrNull(sessionIndex)

                // Sync selectedEditorProfile to match the selected session
                val profileId = profileToSessionMap.entries.find { it.value == sessionIndex }?.key
                val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                _selectedEditorProfile.value = profile
            }
        }
    }

    fun pasteAndDisplayMessage(rawMessage: String) {
        try {
            // Normalize the message format (supports both traditional and line-based formats)
            val normalizedMessage = rawMessage.normalizeFixMessage()

            // Parse the raw message using the loaded data dictionary
            val dataDictionary = _dictionary.value.getDataDictionary()

            // Parse the message
            val quickfixMessage =
                if (dataDictionary != null) {
                    normalizedMessage.toQuickFixMessage(dataDictionary)
                } else {
                    // Parse without validation if no data dictionary
                    normalizedMessage.toQuickFixMessage()
                }

            // Create a FixMessage object for display (not connected to any session)
            val fixMessage =
                FixMessage(
                    timestamp = java.time.LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING, // Default to incoming for pasted messages
                    rawMessage = normalizedMessage,
                    quickfixMessage = quickfixMessage,
                )

            // Select and display the pasted message
            selectMessage(fixMessage)
        } catch (e: Exception) {
            logger.error("Failed to parse message: ${e.message}", e, notifyUser = true)
        }
    }

    fun toggleDetailPanel() {
        _showDetailPanel.value = !_showDetailPanel.value
    }

    fun toggleMessageEditor() {
        _showMessageEditor.value = !_showMessageEditor.value
    }

    fun toggleConnectionPanel() {
        _showConnectionPanel.value = !_showConnectionPanel.value
    }

    fun toggleHideProtocolTags() {
        val updatedSettings = appSettings.copy(hideProtocolTags = !appSettings.hideProtocolTags)
        saveAppSettings(updatedSettings)
    }

    fun toggleSettingsDialog() {
        _showSettingsDialog.value = !_showSettingsDialog.value
    }

    fun toggleHelpDialog() {
        _showHelpDialog.value = !_showHelpDialog.value
    }

    fun toggleLatencyPanel() {
        _showLatencyPanel.value = !_showLatencyPanel.value
    }

    fun toggleGlobalSearchDialog() {
        _showGlobalSearchDialog.value = !_showGlobalSearchDialog.value
        // Clear results when closing
        if (!_showGlobalSearchDialog.value) {
            _globalSearchResults.value = emptyList()
            _globalSearchQuery.value = ""
        }
    }

    fun pinSearchResults() {
        // Pin current search results to the pane and show it
        _pinnedSearchResults.value = _globalSearchResults.value
        _showSearchResultsPane.value = true
    }

    fun closeSearchResultsPane() {
        _showSearchResultsPane.value = false
        _pinnedSearchResults.value = emptyList()
    }

    fun setGlobalSearchQuery(query: String) {
        _globalSearchQuery.value = query
        performGlobalSearch(query)
    }

    private fun performGlobalSearch(query: String) {
        if (query.isBlank()) {
            _globalSearchResults.value = emptyList()
            return
        }

        val results = mutableListOf<SearchResult>()
        val regex =
            try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                // Invalid regex, use literal string matching
                null
            }

        _sessions.forEach { session ->
            session.messages.value.forEach { appMessage ->
                if (appMessage is FixMessage) {
                    val displayText = appMessage.toDisplayString()
                    val matchedText =
                        if (regex != null) {
                            regex.find(displayText)?.value
                        } else {
                            if (displayText.contains(query, ignoreCase = true)) query else null
                        }

                    if (matchedText != null) {
                        // Extract message type description
                        val messageTypeDescription =
                            _dictionary.value.getFieldValueDescription(35, appMessage.messageType)
                                ?: appMessage.messageType

                        // Extract MsgSeqNum (tag 34) from header
                        val msgSeqNum =
                            try {
                                if (appMessage.quickfixMessage.header.isSetField(34)) {
                                    appMessage.quickfixMessage.header.getInt(34)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }

                        // Extract SenderCompID (tag 49) from header
                        val senderCompId =
                            try {
                                if (appMessage.quickfixMessage.header.isSetField(49)) {
                                    appMessage.quickfixMessage.header.getString(49)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }

                        results.add(
                            SearchResult(
                                session = session,
                                message = appMessage,
                                matchedText = matchedText,
                                messageTypeDescription = messageTypeDescription,
                                msgSeqNum = msgSeqNum,
                                senderCompId = senderCompId,
                                sessionUsername = session.title,
                            ),
                        )
                    }
                }
            }
        }

        // Sort by timestamp, then MsgSeqNum, then SenderCompID
        val sortedResults =
            results.sortedWith(
                compareBy<SearchResult> { it.message.timestamp }
                    .thenBy(nullsLast()) { it.msgSeqNum }
                    .thenBy(nullsLast()) { it.senderCompId },
            )

        _globalSearchResults.value = sortedResults
    }

    fun navigateToSearchResult(result: SearchResult) {
        // Switch to the session containing the result
        val sessionIndex = _sessions.indexOf(result.session)
        if (sessionIndex >= 0) {
            setActiveSession(sessionIndex)
            // Select the message
            selectMessage(result.message)
        }
    }

    fun toggleViewMode() {
        // Toggle global view mode (applies to all sessions)
        _viewMode.value =
            when (_viewMode.value) {
                FixMessageSession.ViewMode.RAW -> FixMessageSession.ViewMode.PARSED
                FixMessageSession.ViewMode.PARSED -> FixMessageSession.ViewMode.RAW
            }
    }

    fun saveAppSettings(settings: AppSettings) {
        _appSettings.value = settings
        if (!settingsService.saveSettings(settings)) {
            logger.error("Failed to save application settings")
        }
        // Reload dictionary when settings change
        loadDictionaryFromSettings()
        // Validate the new dictionary
        validateDataDictionary()
    }

    /**
     * Updates the message maps with the latest messages from all sessions.
     * This is called before template evaluation to ensure templates can reference recent messages.
     */
    fun updateMessageMaps() {
        _incomingMessagesByType.clear()
        _outgoingMessagesByType.clear()

        // Use per-session caches to avoid rescanning full message histories
        _sessions.forEach { session ->
            _incomingMessagesByType.putAll(session.snapshotLatestIncomingByType())
            _outgoingMessagesByType.putAll(session.snapshotLatestOutgoingByType())
        }
    }

    fun sendMessage(rawMessage: String): com.knapsack.fixtool.service.SendResult? {
        // Use the currently active session to send message
        logger.info("sendMessage called. Active session index: ${_activeSessionIndex.value}")
        logger.info("sendMessage: _activeSessionState.value = ${_activeSessionState.value?.title} (ID: ${_activeSessionState.value?.id})")
        logger.info("sendMessage: activeSession computed = ${activeSession?.title} (ID: ${activeSession?.id})")

        // Use _activeSessionState directly instead of computed activeSession
        val session = _activeSessionState.value
        if (session == null) {
            logger.error(
                "sendMessage: No active session found! activeSessionIndex=${_activeSessionIndex.value}, sessions.size=${_sessions.size}",
                notifyUser = true,
            )
            return null
        } else {
            logger.info("sendMessage: Sending to session: '${session.title}' (ID: ${session.id})")
            val result = session.sendFixMessage(rawMessage, _dictionary.value)
            logger.info("sendMessage: Message sent to ${session.title}, result: $result")
            return result
        }
    }

    // Connection management methods
    fun connectProfile(profileId: String, profile: FixConnectionProfile) {
        // Check if profile already has a session
        val existingSessionIndex = profileToSessionMap[profileId]
        if (existingSessionIndex != null && existingSessionIndex in _sessions.indices) {
            val existingSession = _sessions[existingSessionIndex]
            val currentState = existingSession.connectionState.value

            // If already connecting or connected, don't switch session automatically
            if (currentState == FixConnectionState.CONNECTING ||
                currentState == FixConnectionState.CONNECTED ||
                currentState == FixConnectionState.LOGGED_ON
            ) {
                logger.info("Session already connecting/connected: {}", profile.name)
                return
            }

            // If disconnected or error, reconnect without switching session
            logger.info("Reconnecting session: {}", profile.name)

            // Enable latency tracking if configured
            if (_appSettings.value.enableLatencyTracking) {
                existingSession.enableLatencyTracking(
                    correlationTags = _appSettings.value.latencyCorrelationTags,
                    historySize = _appSettings.value.latencyHistorySize,
                    warningThresholdMicros = _appSettings.value.latencyWarningThresholdMicros,
                    criticalThresholdMicros = _appSettings.value.latencyCriticalThresholdMicros,
                    networkInterface = _appSettings.value.captureNetworkInterface.ifBlank { null },
                )
            }

            existingSession.connect(profile.config, _appSettings.value, _dictionary.value)

            // Auto-select profile in message editor if none is currently selected
            // (but don't change active session/tab - that's controlled separately)
            if (_selectedEditorProfile.value == null) {
                logger.info("Auto-selecting profile '{}' in message editor", profile.name)
                _selectedEditorProfile.value = profile
            }
        } else {
            // Create new session for this profile
            logger.info("Creating new session for profile: {}", profile.name)
            val session = createNewSession(profile.name)
            val newSessionIndex = _sessions.size - 1
            profileToSessionMap[profileId] = newSessionIndex

            // Enable latency tracking if configured
            if (_appSettings.value.enableLatencyTracking) {
                session.enableLatencyTracking(
                    correlationTags = _appSettings.value.latencyCorrelationTags,
                    historySize = _appSettings.value.latencyHistorySize,
                    warningThresholdMicros = _appSettings.value.latencyWarningThresholdMicros,
                    criticalThresholdMicros = _appSettings.value.latencyCriticalThresholdMicros,
                    networkInterface = _appSettings.value.captureNetworkInterface.ifBlank { null },
                )
            }

            session.connect(profile.config, _appSettings.value, _dictionary.value)

            // Auto-select profile in message editor if none is currently selected
            // (but don't change active session/tab - that's controlled separately)
            if (_selectedEditorProfile.value == null) {
                logger.info("Auto-selecting profile '{}' in message editor", profile.name)
                _selectedEditorProfile.value = profile
            }
        }
    }

    fun disconnectProfile(profileId: String) {
        val sessionIndex = profileToSessionMap[profileId]
        if (sessionIndex != null) {
            _sessions.getOrNull(sessionIndex)?.disconnect()
        }
    }

    /**
     * Disconnects all active sessions
     * Called during app shutdown to gracefully logout from all servers
     */
    fun disconnectAllSessions() {
        logger.info("Disconnecting all sessions (${_sessions.size})")
        _sessions.forEach { session ->
            try {
                session.disconnect()
            } catch (e: Exception) {
                logger.error("Error disconnecting session ${session.title}: ${e.message}", e, notifyUser = false)
            }
        }
    }

    fun getProfileConnectionState(profileId: String): FixConnectionState {
        val sessionIndex = profileToSessionMap[profileId]
        return if (sessionIndex != null && sessionIndex in _sessions.indices) {
            _sessions[sessionIndex].connectionState.value
        } else {
            FixConnectionState.DISCONNECTED
        }
    }

    /**
     * Returns priority for connection state sorting.
     * Lower value = higher priority.
     */
    private fun getConnectionPriority(state: FixConnectionState): Int =
        when (state) {
            FixConnectionState.CONNECTED, FixConnectionState.LOGGED_ON -> 0 // Highest priority
            FixConnectionState.CONNECTING -> 1 // Medium priority
            else -> 2 // Lowest priority (DISCONNECTED, ERROR)
        }

    fun getProfileSession(profileId: String): FixMessageSession? {
        val sessionIndex = profileToSessionMap[profileId]
        return if (sessionIndex != null && sessionIndex in _sessions.indices) {
            _sessions[sessionIndex]
        } else {
            null
        }
    }

    // Profile management methods
    private fun loadConnectionProfiles() {
        _connectionProfiles.clear()
        _connectionProfiles.addAll(profileService.loadProfiles().sortedBy { it.name.lowercase() })
    }

    fun saveConnectionProfile(profile: FixConnectionProfile) {
        profileService
            .saveProfile(profile)
            .onSuccess {
                loadConnectionProfiles()
            }.onFailure { error ->
                logger.error("Failed to save connection profile: ${error.message}", error)
            }
    }

    fun deleteConnectionProfile(profileId: String) {
        // Don't delete demo profiles - they're managed by the demo server
        if (DemoServerManager.isDemoProfile(profileId)) {
            logger.warn("Cannot delete demo profile: {}", profileId)
            return
        }

        profileService
            .deleteProfile(profileId)
            .onSuccess {
                loadConnectionProfiles()
            }.onFailure { error ->
                logger.error("Failed to delete connection profile: ${error.message}", error)
            }
    }

    fun cloneConnectionProfile(profile: FixConnectionProfile): FixConnectionProfile {
        val clonedProfile =
            profile.copy(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                name = "${profile.name} (Copy)",
                createdAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
            )
        profileService
            .saveProfile(clonedProfile)
            .onSuccess {
                loadConnectionProfiles()
            }.onFailure { error ->
                logger.error("Failed to clone connection profile: ${error.message}", error)
            }
        return clonedProfile
    }

    // Message editor field management
    fun updateEditorField(index: Int, field: FixField) {
        if (index in _editorFields.indices) {
            _editorFields[index] = field
            markEditorDirty()
        }
    }

    fun addEditorField() {
        // If there's a selected field, insert after it; otherwise add at the end
        val insertIndex =
            if (_editorSelectedFieldIndex.value in _editorFields.indices) {
                _editorSelectedFieldIndex.value + 1
            } else {
                _editorFields.size
            }
        _editorFields.add(insertIndex, FixField())
        _editorSelectedFieldIndex.value = insertIndex
        markEditorDirty()
    }

    fun deleteEditorField(index: Int) {
        // If multi-selection exists, delete all selected fields (even if not contiguous)
        if (_editorSelectedIndices.size > 1 && _editorFields.size > _editorSelectedIndices.size) {
            val sorted = _editorSelectedIndices.sorted()
            // Remove from bottom to top to maintain indices
            sorted.reversed().forEach { _editorFields.removeAt(it) }
            // Update selection to the position where deleted items were
            val newIndex = (sorted.first()).coerceIn(0, _editorFields.size - 1)
            _editorSelectedIndices.clear()
            _editorSelectedIndices.add(newIndex)
            _editorSelectedFieldIndex.value = newIndex
            markEditorDirty()
        } else {
            // Single selection mode
            if (_editorFields.size > 1 && index in _editorFields.indices) {
                _editorFields.removeAt(index)
                val newIndex = if (index >= _editorFields.size) _editorFields.size - 1 else index
                _editorSelectedFieldIndex.value = newIndex
                _editorSelectedIndices.clear()
                _editorSelectedIndices.add(newIndex)
                markEditorDirty()
            }
        }
    }

    fun moveEditorFieldUp(index: Int) {
        // If multi-selection exists, move all selected fields up
        if (_editorSelectedIndices.size > 1) {
            val sorted = _editorSelectedIndices.sorted()
            val minIndex = sorted.first()

            if (minIndex > 0) {
                if (isSelectionContiguous()) {
                    // Contiguous: move as a group
                    val fieldsToMove = sorted.map { _editorFields[it] }
                    sorted.reversed().forEach { _editorFields.removeAt(it) }
                    fieldsToMove.forEachIndexed { offset, field ->
                        _editorFields.add(minIndex - 1 + offset, field)
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(sorted.map { it - 1 })
                    _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value - 1
                } else {
                    // Non-contiguous: swap each selected item with the one above it
                    val newIndices = mutableListOf<Int>()
                    sorted.forEach { currentIndex ->
                        val targetIndex = currentIndex - 1
                        if (currentIndex > 0 && targetIndex !in sorted) {
                            // Swap with the item above (which is not selected)
                            val temp = _editorFields[currentIndex]
                            _editorFields[currentIndex] = _editorFields[targetIndex]
                            _editorFields[targetIndex] = temp
                            newIndices.add(targetIndex)
                        } else {
                            // Can't move up, keep at current position
                            newIndices.add(currentIndex)
                        }
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(newIndices.sorted())
                    if (_editorSelectedFieldIndex.value in sorted &&
                        _editorSelectedFieldIndex.value - 1 !in sorted &&
                        _editorSelectedFieldIndex.value > 0
                    ) {
                        _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value - 1
                    }
                }
                markEditorDirty()
            }
        } else {
            // Single selection mode
            if (index > 0 && index in _editorFields.indices) {
                val field = _editorFields.removeAt(index)
                _editorFields.add(index - 1, field)
                _editorSelectedFieldIndex.value = index - 1
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    _editorSelectedIndices.add(index - 1)
                }
                markEditorDirty()
            }
        }
    }

    fun moveEditorFieldDown(index: Int) {
        // If multi-selection exists, move all selected fields down
        if (_editorSelectedIndices.size > 1) {
            val sorted = _editorSelectedIndices.sorted()
            val maxIndex = sorted.last()

            if (maxIndex < _editorFields.size - 1) {
                if (isSelectionContiguous()) {
                    // Contiguous: move as a group
                    val fieldsToMove = sorted.map { _editorFields[it] }
                    sorted.reversed().forEach { _editorFields.removeAt(it) }
                    fieldsToMove.forEachIndexed { offset, field ->
                        _editorFields.add(sorted.first() + 1 + offset, field)
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(sorted.map { it + 1 })
                    _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value + 1
                } else {
                    // Non-contiguous: swap each selected item with the one below it (process from bottom to top)
                    val newIndices = mutableListOf<Int>()
                    sorted.reversed().forEach { currentIndex ->
                        val targetIndex = currentIndex + 1
                        if (currentIndex < _editorFields.size - 1 && targetIndex !in sorted) {
                            // Swap with the item below (which is not selected)
                            val temp = _editorFields[currentIndex]
                            _editorFields[currentIndex] = _editorFields[targetIndex]
                            _editorFields[targetIndex] = temp
                            newIndices.add(targetIndex)
                        } else {
                            // Can't move down, keep at current position
                            newIndices.add(currentIndex)
                        }
                    }
                    _editorSelectedIndices.clear()
                    _editorSelectedIndices.addAll(newIndices.sorted())
                    if (_editorSelectedFieldIndex.value in sorted &&
                        _editorSelectedFieldIndex.value + 1 !in sorted &&
                        _editorSelectedFieldIndex.value < _editorFields.size - 1
                    ) {
                        _editorSelectedFieldIndex.value = _editorSelectedFieldIndex.value + 1
                    }
                }
                markEditorDirty()
            }
        } else {
            // Single selection mode
            if (index < _editorFields.size - 1 && index in _editorFields.indices) {
                val field = _editorFields.removeAt(index)
                _editorFields.add(index + 1, field)
                _editorSelectedFieldIndex.value = index + 1
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    _editorSelectedIndices.add(index + 1)
                }
                markEditorDirty()
            }
        }
    }

    fun selectEditorField(index: Int, isCtrlPressed: Boolean = false, isShiftPressed: Boolean = false) {
        if (index !in _editorFields.indices) return

        when {
            isShiftPressed && _editorSelectedIndices.isNotEmpty() -> {
                // Range selection: select all fields between anchor and current index
                val anchor = _editorSelectedIndices.minOrNull() ?: index
                val range = if (index >= anchor) anchor..index else index..anchor
                _editorSelectedIndices.clear()
                _editorSelectedIndices.addAll(range.toList())
                _editorSelectedFieldIndex.value = index
            }

            isCtrlPressed -> {
                // Toggle selection: add/remove from multi-selection
                if (index in _editorSelectedIndices) {
                    _editorSelectedIndices.remove(index)
                    if (_editorSelectedIndices.isNotEmpty()) {
                        _editorSelectedFieldIndex.value = _editorSelectedIndices.last()
                    } else {
                        _editorSelectedFieldIndex.value = index
                    }
                } else {
                    _editorSelectedIndices.add(index)
                    _editorSelectedFieldIndex.value = index
                }
            }

            else -> {
                // Single selection: clear previous and select only this one
                _editorSelectedIndices.clear()
                _editorSelectedIndices.add(index)
                _editorSelectedFieldIndex.value = index
            }
        }
    }

    fun clearEditorSelection() {
        _editorSelectedIndices.clear()
        if (_editorFields.isNotEmpty()) {
            _editorSelectedIndices.add(0)
            _editorSelectedFieldIndex.value = 0
        }
    }

    /**
     * Returns true if the selected indices form a contiguous range
     */
    private fun isSelectionContiguous(): Boolean {
        if (_editorSelectedIndices.size <= 1) return true
        val sorted = _editorSelectedIndices.sorted()
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1] - sorted[i] != 1) return false
        }
        return true
    }

    /**
     * Marks the editor as dirty (modified) if it's currently in Clean state
     * Call this whenever the editor content is modified by the user
     */
    fun markEditorDirty() {
        val currentState = _editorState.value
        if (currentState is com.knapsack.fixtool.model.MessageEditorState.Clean) {
            _editorState.value =
                com.knapsack.fixtool.model.MessageEditorState.Dirty(
                    messageId = currentState.messageId,
                    messageName = currentState.messageName,
                    userTags = currentState.userTags,
                )
        }
        // If already Dirty or New, no change needed
    }

    fun clearEditorFields(resetSelection: Boolean = true) {
        _editorFields.clear()
        _editorFields.add(FixField())
        if (resetSelection) {
            _editorSelectedFieldIndex.value = 0
            _editorSelectedIndices.clear()
            _editorSelectedIndices.add(0)
        }
        // Reset editor state to New when fields are cleared
        _editorState.value = com.knapsack.fixtool.model.MessageEditorState.New
    }

    /**
     * Validates template expressions in fields with incoming/outgoing message context
     * Used before sending to ensure all expressions can be resolved
     */
    fun validateTemplateExpressions(
        fields: List<FixField>,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
    ): List<String> {
        val errors = mutableListOf<String>()

        fields.forEach { field ->
            if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                val templateErrors =
                    FixMessageTemplate.validateExpressions(
                        field.value,
                        incomingMessages = incomingMessages,
                        outgoingMessages = outgoingMessages,
                        dictionary = _dictionary.value,
                    )
                templateErrors.forEach { error ->
                    errors.add("Field ${field.tag}: $error")
                }
            }
        }

        return errors
    }

    /**
     * Returns the current data dictionary adapter for template expression evaluation.
     */
    fun getDictionaryAdapter(): com.knapsack.fixtool.model.FixDictionaryAdapter = _dictionary.value

    fun validateEditorMessage(fields: List<FixField>): List<String> {
        _editorValidationErrors.clear()

        if (!_dictionary.value.isLoaded()) {
            _editorValidationErrors.add(
                "No data dictionary configured. Please configure a data dictionary in settings.",
            )
            return _editorValidationErrors
        }

        // Validate template expressions in field values (without incoming/outgoing context for Validate button)
        // Use shared variables map so variables defined in earlier fields are available to later fields
        val sharedVariables = mutableMapOf<String, String>()
        fields.forEach { field ->
            if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                val templateErrors =
                    FixMessageTemplate.validateExpressions(
                        field.value,
                        // No incoming/outgoing context available in editor, but can still validate syntax
                        incomingMessages = emptyMap(),
                        outgoingMessages = emptyMap(),
                        variables = sharedVariables,
                    )
                templateErrors.forEach { error ->
                    _editorValidationErrors.add("Field ${field.tag}: $error")
                }
            }
        }

        // Also validate FIX message structure
        val result =
            FixMessageValidator.validate(
                fields.toRawMessage(),
                _dictionary.value,
            )
        _editorValidationErrors.addAll(result.errors)

        return _editorValidationErrors
    }

    fun clearEditorValidationErrors() {
        _editorValidationErrors.clear()
    }

    fun setEditorValidationErrors(errors: List<String>) {
        _editorValidationErrors.clear()
        _editorValidationErrors.addAll(errors)
    }

    fun moveSession(fromIndex: Int, toIndex: Int) {
        if (fromIndex in _sessions.indices && toIndex in _sessions.indices) {
            val session = _sessions.removeAt(fromIndex)
            _sessions.add(toIndex, session)

            // Adjust active index if needed
            when {
                _activeSessionIndex.value == fromIndex -> _activeSessionIndex.value = toIndex
                fromIndex < _activeSessionIndex.value && toIndex >= _activeSessionIndex.value -> _activeSessionIndex.value--
                fromIndex > _activeSessionIndex.value && toIndex <= _activeSessionIndex.value -> _activeSessionIndex.value++
            }

            // Adjust profileToSessionMap indices to reflect the move
            val updatedMap = profileToSessionMap.mapValues { (_, sessionIndex) ->
                when {
                    sessionIndex == fromIndex -> toIndex
                    fromIndex < toIndex && sessionIndex > fromIndex && sessionIndex <= toIndex -> sessionIndex - 1
                    fromIndex > toIndex && sessionIndex >= toIndex && sessionIndex < fromIndex -> sessionIndex + 1
                    else -> sessionIndex
                }
            }
            profileToSessionMap.clear()
            profileToSessionMap.putAll(updatedMap)
        }
    }

    // Demo Server Management
    fun startDemoServer() {
        try {
            DemoServerManager.start()
        } catch (e: Exception) {
            // Error already logged by manager
        }
    }

    fun stopDemoServer() {
        DemoServerManager.stop()
    }

    /**
     * Handles demo profile creation/deletion when demo server starts/stops
     */
    private fun handleDemoProfilesChanged(demoProfiles: List<FixConnectionProfile>) {
        // Remove existing demo profiles
        val nonDemoProfiles = _connectionProfiles.filter { !DemoServerManager.isDemoProfile(it.id) }
        _connectionProfiles.clear()
        _connectionProfiles.addAll(nonDemoProfiles)

        // Add new demo profiles
        if (demoProfiles.isNotEmpty()) {
            _connectionProfiles.addAll(demoProfiles)
            // Re-sort profiles
            val sortedProfiles = _connectionProfiles.sortedBy { it.name.lowercase() }
            _connectionProfiles.clear()
            _connectionProfiles.addAll(sortedProfiles)
        }
    }

    // Global session operations
    fun addSeparatorToAllSessions() {
        _sessions.forEach { it.addSeparator() }
    }

    fun clearAllSessions() {
        _sessions.forEach { it.clearMessages() }
    }

    fun setGlobalFilterRegex(regex: String) {
        _globalFilterRegex.value = regex
        // Apply to all sessions
        _sessions.forEach { it.setFilterRegex(regex) }
    }

    fun setGlobalFilterShowIncoming(show: Boolean) {
        _globalFilterShowIncoming.value = show
        // Apply to all sessions
        _sessions.forEach { it.setFilterShowIncoming(show) }
    }

    fun setGlobalFilterShowOutgoing(show: Boolean) {
        _globalFilterShowOutgoing.value = show
        // Apply to all sessions
        _sessions.forEach { it.setFilterShowOutgoing(show) }
    }

    // Saved Messages Operations
    fun saveEditorMessage(
        name: String,
        fields: List<FixField>,
        profileId: String,
        userTags: Set<String> = setOf(profileId),
    ) {
        val savedFields = fields.map { SavedFixField(tag = it.tag, value = it.value, excluded = it.excluded) }

        // Determine if this is an update to existing message or a new save
        val currentState = _editorState.value
        val savedMessage =
            when (currentState) {
                is com.knapsack.fixtool.model.MessageEditorState.Clean,
                is com.knapsack.fixtool.model.MessageEditorState.Dirty,
                -> {
                    // Update existing message - preserve ID and createdAt
                    val existingId = currentState.messageIdOrNull()!!
                    val existing = _savedMessages.find { it.id == existingId }
                    SavedFixMessage(
                        id = existingId,
                        name = name,
                        userTags = userTags,
                        fields = savedFields,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        modifiedAt = System.currentTimeMillis(),
                        version = (existing?.version ?: 0) + 1,
                    )
                }
                is com.knapsack.fixtool.model.MessageEditorState.New -> {
                    // Create new message with new ID
                    SavedFixMessage(
                        name = name,
                        userTags = userTags,
                        fields = savedFields,
                    )
                }
            }

        savedMessagesService
            .saveMessage(profileId, savedMessage)
            .onSuccess { updatedMessages ->
                _savedMessages.clear()
                _savedMessages.addAll(updatedMessages)

                // Update editor state to Clean after successful save
                _editorState.value =
                    com.knapsack.fixtool.model.MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    /**
     * Save As: Always creates a new message with a new ID
     * Useful for creating copies or variants of existing messages
     */
    fun saveEditorMessageAs(
        name: String,
        fields: List<FixField>,
        profileId: String,
        userTags: Set<String> = setOf(profileId),
    ) {
        val savedFields = fields.map { SavedFixField(tag = it.tag, value = it.value, excluded = it.excluded) }

        // Always create a new message (never update existing)
        val savedMessage =
            SavedFixMessage(
                // id will be generated automatically
                name = name,
                userTags = userTags,
                fields = savedFields,
            )

        savedMessagesService
            .saveMessage(profileId, savedMessage)
            .onSuccess { updatedMessages ->
                _savedMessages.clear()
                _savedMessages.addAll(updatedMessages)

                // Update editor state to Clean with the new message ID
                _editorState.value =
                    com.knapsack.fixtool.model.MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    fun getCurrentProfileId(): String? =
        activeSession?.let { session ->
            profileToSessionMap.entries.find { it.value == _activeSessionIndex.value }?.key
        }

    fun loadEditorMessage(savedMessage: SavedFixMessage) {
        // Clear current fields
        _editorFields.clear()

        // Load fields from saved message
        val fieldsToLoad = savedMessage.fields.map { FixField(tag = it.tag, value = it.value, excluded = it.excluded) }
        if (fieldsToLoad.isEmpty()) {
            _editorFields.add(FixField())
        } else {
            _editorFields.addAll(fieldsToLoad)
        }

        // Reset selection to first field
        _editorSelectedIndices.clear()
        _editorSelectedIndices.add(0)
        _editorSelectedFieldIndex.value = 0

        // Set editor state to Clean (message loaded, unmodified)
        _editorState.value =
            com.knapsack.fixtool.model.MessageEditorState.Clean(
                messageId = savedMessage.id,
                messageName = savedMessage.name,
                userTags = savedMessage.getAllUserTags(),
            )

        // Auto-select appropriate profile/session based on template's associated profiles
        autoSelectProfileForMessage(savedMessage)

        // Mark message as used
        activeSession?.let { session ->
            val currentProfileId = profileToSessionMap.entries.find { it.value == _activeSessionIndex.value }?.key
            if (currentProfileId != null) {
                savedMessagesService
                    .markMessageAsUsed(currentProfileId, savedMessage.id)
                    .onFailure { error ->
                        logger.error("Failed to mark message as used: ${error.message}", error)
                    }
            }
        }
    }

    /**
     * Auto-selects the appropriate profile/session when a template is loaded.
     * Algorithm:
     * 1. Get associated profile IDs from template's userTags
     * 2. Filter to only associated profiles
     * 3. Sort by connection status (CONNECTED/LOGGED_ON first, CONNECTING second, DISCONNECTED/ERROR third), then alphabetically
     * 4. Select the first profile's session from the sorted list (if any)
     */
    private fun autoSelectProfileForMessage(savedMessage: SavedFixMessage) {
        val associatedProfileIds = savedMessage.getAllUserTags()

        if (associatedProfileIds.isEmpty()) {
            // No associated profiles - keep current session selection
            logger.info("loadEditorMessage: No associated profiles for message '${savedMessage.name}', keeping current session")
            return
        }

        // Filter connection profiles to only those associated with the message
        val associatedProfiles = connectionProfiles.filter { it.id in associatedProfileIds }

        if (associatedProfiles.isEmpty()) {
            // Associated profiles not found - keep current session selection
            logger.info("loadEditorMessage: Associated profiles not found for message '${savedMessage.name}', keeping current session")
            return
        }

        // Sort profiles by connection state priority (connected first) then alphabetically
        val sortedProfiles =
            associatedProfiles.sortedWith(
                compareBy(
                    { profile -> getConnectionPriority(getProfileConnectionState(profile.id)) },
                    { profile -> profile.name.lowercase() },
                ),
            )

        // Select the first profile
        val selectedProfile = sortedProfiles.first()

        logger.info(
            "loadEditorMessage: Auto-selecting profile '${selectedProfile.name}' for message '${savedMessage.name}' (${associatedProfiles.size} associated profiles)",
        )

        // Set the selected editor profile (this will also set active session if connected)
        setSelectedEditorProfile(selectedProfile)
    }

    fun loadSavedMessagesForActiveSession() {
        // Always show all messages from all profiles (no filtering by active session)
        // The user can filter in the popup UI if needed
        val allMessages = mutableListOf<SavedFixMessage>()
        connectionProfiles.forEach { profile ->
            allMessages.addAll(savedMessagesService.loadMessagesForProfile(profile.id))
        }
        // Deduplicate messages by ID (messages can be shared across multiple profiles)
        _savedMessages.clear()
        _savedMessages.addAll(allMessages.distinctBy { it.id })
    }

    fun deleteSavedMessage(messageId: String, profileId: String) {
        // Delete the message using the profileId from the message itself
        savedMessagesService
            .deleteMessage(profileId, messageId)
            .onSuccess {
                // Reload all saved messages to reflect the deletion
                loadSavedMessagesForActiveSession()

                // Clear editor state if we just deleted the currently loaded message
                val currentMessageId = _editorState.value.messageIdOrNull()
                if (currentMessageId == messageId) {
                    _editorState.value = com.knapsack.fixtool.model.MessageEditorState.New
                }
            }.onFailure { error ->
                logger.error("Failed to delete message: ${error.message}", error)
            }
    }

    fun toggleMessageFavorite(messageId: String) {
        // Find the message and toggle its favorite status
        val message = _savedMessages.find { it.id == messageId } ?: return
        val updatedMessage = message.copy(isFavorite = !message.isFavorite)

        // Get the profileId from the message's user tags
        val profileId = message.getAllUserTags().firstOrNull() ?: return

        // Update local state immediately for responsive UI
        val index = _savedMessages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            _savedMessages[index] = updatedMessage
        }

        // Persist to storage
        savedMessagesService
            .saveMessage(profileId, updatedMessage)
            .onFailure { error ->
                logger.error("Failed to toggle favorite: ${error.message}", error)
                // Revert local state on failure
                if (index >= 0) {
                    _savedMessages[index] = message
                }
            }
    }

    // ========================================
    // Notification Management
    // ========================================

    /**
     * Shows a notification to the user
     */
    fun showNotification(
        message: String,
        type: com.knapsack.fixtool.model.NotificationType = com.knapsack.fixtool.model.NotificationType.ERROR,
    ) {
        logger.info("Showing notification: [$type] $message")
        val notification =
            com.knapsack.fixtool.model.Notification(
                message = message,
                type = type,
            )
        _notifications.add(notification)
    }

    /**
     * Dismisses a notification by its ID
     */
    fun dismissNotification(notificationId: String) {
        _notifications.removeAll { it.id == notificationId }
    }

    /**
     * Clears all notifications
     */
    fun clearAllNotifications() {
        _notifications.clear()
    }

    // ========================================
    // Test Helper Methods
    // ========================================

    /**
     * Creates a new session for testing purposes.
     * This is a public wrapper around createNewSession for use in tests.
     */
    fun createSessionForTest(title: String = "Test Session"): FixMessageSession = createNewSession(title)

    /**
     * Creates a session with an associated profile for testing purposes.
     * This properly sets up the profile-to-session mapping that's needed for
     * testing the selectedEditorProfile sync behavior.
     *
     * @param profileName Name for the profile
     * @return Pair of the created profile and session
     */
    fun createSessionWithProfileForTest(profileName: String): Pair<FixConnectionProfile, FixMessageSession> {
        val profile = FixConnectionProfile(
            name = profileName,
            config = FixConnectionConfig(
                host = "localhost",
                port = "9876",
                senderCompID = "TEST_SENDER",
                targetCompID = "TEST_TARGET",
                beginString = "FIX.4.4",
            ),
        )
        _connectionProfiles.add(profile)

        val session = createNewSession(profileName)
        val sessionIndex = _sessions.size - 1
        profileToSessionMap[profile.id] = sessionIndex

        return Pair(profile, session)
    }

    override fun onCleared() {
        super.onCleared()
        _sessions.forEach { it.destroy() }
        DemoServerManager.stop()
    }
}
