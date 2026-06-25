package com.knapsack.fixtool.viewmodel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MessageEditorState
import com.knapsack.fixtool.model.Notification
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.FixMessageHelper.normalizeFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.service.SessionIdentityResolver
import com.knapsack.fixtool.service.demo.DemoServerManager
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
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
    private val testSettingsDir: String? = null,
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

    // Current FIX version based on loaded dictionary
    val currentFixVersion: FixVersion
        get() = (_dictionary.value as? FixDictionaryAdapter)?.fixVersion ?: FixVersion.DEFAULT

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
            customPath = resolveStoragePath(_appSettings.value.connectionProfilesPath, "connection_profiles.json"),
        )
    }
    private val _connectionProfiles = mutableStateListOf<FixConnectionProfile>()
    val connectionProfiles: List<FixConnectionProfile> = _connectionProfiles

    // Saved messages (lazy-initialized to use appSettings paths)
    private val savedMessagesService by lazy {
        SavedMessagesService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            customPath = resolveStoragePath(_appSettings.value.savedMessagesPath, "saved_messages.json"),
        )
    }
    private val _savedMessages = mutableStateListOf<SavedFixMessage>()
    val savedMessages: List<SavedFixMessage> = _savedMessages

    /**
     * Resolves where a JSON store (connection profiles, saved messages) is kept. An explicit
     * setting always wins. Otherwise, when constructed with a [testSettingsDir] the store is kept
     * beside that dir's app_settings.json, so tests stay isolated and never read or write the real
     * ~/.fixtool files; in normal use this returns blank and the service applies its own default.
     */
    private fun resolveStoragePath(configured: String, fileName: String): String =
        when {
            configured.isNotBlank() -> configured
            testSettingsDir != null -> java.io.File(testSettingsDir, fileName).absolutePath
            else -> ""
        }

    // Track message editor state (new, clean, dirty)
    private val _editorState =
        MutableStateFlow<MessageEditorState>(
            MessageEditorState.New,
        )
    val editorState: StateFlow<MessageEditorState> = _editorState

    // Backwards compatibility: expose message name from editor state
    val currentLoadedMessageName: StateFlow<String?> =
        _editorState
            .map { it.messageNameOrNull() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Track which sessions belong to which profile (a profile owns multiple sessions when sessionCount > 1)
    private val profileToSessionMap = mutableMapOf<String, MutableList<Int>>()

    private fun profileIdForSessionIndex(index: Int): String? = profileToSessionMap.entries.find { index in it.value }?.key

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
    private val _notifications = mutableStateListOf<Notification>()
    val notifications: List<Notification> = _notifications

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

        // Load saved messages once so the in-memory list is populated from startup (the control
        // surface reads it without forcing a per-request disk reload; writes refresh it).
        loadSavedMessagesForActiveSession()

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
            val settings = _appSettings.value
            if (settings.useBundledDictionary) {
                // Use bundled dictionary for the configured FIX version
                loadBundledDictionaryForVersion(settings.defaultFixVersion)
            } else {
                // Use custom dictionary path
                val dictionaryPath = settings.defaultDataDictionary
                val transportDictionaryPath = settings.defaultTransportDictionary
                if (dictionaryPath.isNotBlank()) {
                    val dictionaryFile = File(dictionaryPath)
                    if (dictionaryFile.exists()) {
                        // Check if transport dictionary is configured for FIX 5.0+
                        val transportFile =
                            if (transportDictionaryPath.isNotBlank()) {
                                File(transportDictionaryPath).takeIf { it.exists() }
                            } else {
                                null
                            }

                        _dictionary.value = FixDictionaryAdapter.fromFiles(dictionaryFile, transportFile)
                        val loadedVersion = (_dictionary.value as? FixDictionaryAdapter)?.fixVersion
                        logger.info(
                            "Loaded data dictionary for UI from: {} (detected version: {}, transport: {})",
                            dictionaryPath,
                            loadedVersion?.displayName,
                            transportFile?.absolutePath ?: "none",
                        )

                        // Warn if FIX 5.0+ but no transport dictionary
                        if (loadedVersion?.isFix50Plus == true && transportFile == null) {
                            showNotification(
                                "FIX 5.0+ requires a transport dictionary (FIXT11.xml). Please configure it in Settings.",
                                NotificationType.WARNING,
                            )
                        }

                        _isDictionaryValid.value = true
                        _dictionaryErrorMessage.value = null
                    } else {
                        logger.warn(
                            "Data dictionary file not found: {}, falling back to bundled {}",
                            dictionaryPath,
                            settings.defaultFixVersion.displayName,
                        )
                        showNotification(
                            "Custom dictionary not found at $dictionaryPath, using bundled ${settings.defaultFixVersion.displayName}",
                            NotificationType.WARNING,
                        )
                        loadBundledDictionaryForVersion(settings.defaultFixVersion)
                    }
                } else {
                    // No custom dictionary configured - use bundled dictionary for default version
                    logger.info("No custom data dictionary configured, using bundled {}", settings.defaultFixVersion.displayName)
                    loadBundledDictionaryForVersion(settings.defaultFixVersion)
                }
            }
        } catch (e: Exception) {
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load data dictionary: ${e.message}"
            logger.error("Failed to load data dictionary: ${e.message}", e, notifyUser = true)
            // Try bundled dictionary as last resort
            showNotification(
                "Failed to load custom dictionary, using bundled ${FixVersion.DEFAULT.displayName}",
                NotificationType.WARNING,
            )
            loadBundledDictionaryForVersion(FixVersion.DEFAULT)
        }
    }

    /**
     * Loads the bundled FIX dictionary for the default version.
     * This is used as the default when no custom dictionary is configured.
     */
    private fun loadBundledDictionary() {
        loadBundledDictionaryForVersion(FixVersion.DEFAULT)
    }

    /**
     * Loads the bundled FIX dictionary for a specific version.
     * For FIX 5.0+, this also loads the FIXT.1.1 transport dictionary.
     *
     * @param version The FIX version to load
     */
    fun loadBundledDictionaryForVersion(version: FixVersion) {
        try {
            _dictionary.value = FixDictionaryAdapter.forVersion(version)
            if (_dictionary.value.isLoaded()) {
                logger.info("Loaded bundled ${version.displayName} dictionary")
                _isDictionaryValid.value = true
                _dictionaryErrorMessage.value = null
            } else {
                logger.error("Failed to load bundled ${version.displayName} dictionary")
                _isDictionaryValid.value = false
                _dictionaryErrorMessage.value = "Failed to load bundled ${version.displayName} dictionary"
            }
        } catch (e: Exception) {
            logger.error("Failed to load bundled dictionary for ${version.displayName}: ${e.message}", e)
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load bundled ${version.displayName} dictionary: ${e.message}"
        }
    }

    /**
     * Validates the data dictionary configuration and shows an error notification if invalid
     */
    private fun validateDataDictionary() {
        if (!_isDictionaryValid.value) {
            val errorMsg = _dictionaryErrorMessage.value ?: "Data dictionary is not configured"
            showNotification(errorMsg, NotificationType.ERROR)
        }
    }

    private fun createNewSession(
        title: String = "Session",
        sessionQualifier: String = "",
        profileSlot: Int = 0,
    ): FixMessageSession {
        val session =
            FixMessageSession(
                title = title,
                sessionQualifier = sessionQualifier,
                profileSlot = profileSlot,
                bufferSize = _appSettings.value.sessionBufferSize,
                onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            )
        _sessions.add(session)
        // Do NOT auto-select session on connect - let user or template loading do it
        return session
    }

    fun closeSession(index: Int) {
        if (index in _sessions.indices) {
            // Remove this session from its profile's group mapping
            profileToSessionMap.values.forEach { indices -> indices.removeAll { it == index } }
            profileToSessionMap.entries.removeIf { it.value.isEmpty() }

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
            profileToSessionMap.values.forEach { indices ->
                for (i in indices.indices) {
                    if (indices[i] > index) indices[i] = indices[i] - 1
                }
            }
        }
    }

    fun setActiveSession(index: Int) {
        if (index == -1 || index in _sessions.indices) {
            val session = if (index >= 0) _sessions.getOrNull(index) else null
            logger.info("setActiveSession(index=$index): Switching to session: ${session?.title} (ID: ${session?.id})")
            _activeSessionIndex.value = index
            _activeSessionState.value = session

            // Sync selectedEditorProfile to match the selected session (if enabled)
            if (_appSettings.value.autoSyncSessionToEditor) {
                val profileId = profileIdForSessionIndex(index)
                val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                _selectedEditorProfile.value = profile
                logger.info("setActiveSession: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")
            } else {
                logger.info("setActiveSession: Auto-sync to editor disabled, skipping profile update")
            }

            // Reload messages when session selection changes
            loadSavedMessagesForActiveSession()
        }
    }

    fun setActiveSessionByObject(session: FixMessageSession?) {
        logger.info("setActiveSessionByObject: Switching to session: ${session?.title} (ID: ${session?.id})")
        if (session == null) {
            _activeSessionIndex.value = -1
            _activeSessionState.value = null
            if (_appSettings.value.autoSyncSessionToEditor) {
                _selectedEditorProfile.value = null
                logger.info("setActiveSessionByObject: Cleared selectedEditorProfile")
            }
        } else {
            val index = _sessions.indexOf(session)
            if (index >= 0) {
                logger.info("setActiveSessionByObject: Found session at index $index")
                _activeSessionIndex.value = index
                _activeSessionState.value = session

                // Sync selectedEditorProfile to match the selected session (if enabled)
                if (_appSettings.value.autoSyncSessionToEditor) {
                    val profileId = profileIdForSessionIndex(index)
                    val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                    _selectedEditorProfile.value = profile
                    logger.info("setActiveSessionByObject: Updated selectedEditorProfile to: ${profile?.name} (ID: ${profile?.id})")
                } else {
                    logger.info("setActiveSessionByObject: Auto-sync to editor disabled, skipping profile update")
                }
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

                // Sync selectedEditorProfile to match the selected session (if enabled)
                if (_appSettings.value.autoSyncSessionToEditor) {
                    val profileId = profileIdForSessionIndex(sessionIndex)
                    val profile = if (profileId != null) _connectionProfiles.find { it.id == profileId } else null
                    _selectedEditorProfile.value = profile
                }
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

    data class SessionSendOutcome(
        val session: FixMessageSession,
        val result: com.knapsack.fixtool.service.SendResult,
    )

    /**
     * Template variables describing one session, available to message template expressions
     * at send time - e.g. 262=MD-${sessionIndex} for a unique MDReqID per session.
     */
    fun sessionTemplateVariables(session: FixMessageSession, index: Int): Map<String, String> =
        mapOf(
            "sessionIndex" to index.toString(),
            "sessionQualifier" to session.sessionQualifier,
            "sessionTitle" to session.title,
            "sessionSenderCompID" to (session.currentConfig?.senderCompID ?: ""),
        )

    /**
     * Sends one message to every logged-on session. Template expressions are re-resolved
     * per session, so dynamic values (UUIDs, timestamps) are unique per session and the
     * per-session variables from [sessionTemplateVariables] are available.
     */
    fun sendMessageToAllConnectedSessions(fields: List<FixField>): List<SessionSendOutcome> {
        updateMessageMaps()
        val targets = _sessions.filter { it.connectionState.value == FixConnectionState.LOGGED_ON }
        if (targets.isEmpty()) {
            showNotification("No logged-on session to send to", NotificationType.WARNING)
            return emptyList()
        }

        val outcomes =
            targets.mapIndexed { index, session ->
                val resolvedFields =
                    fields.resolveTemplates(
                        incomingMessages = incomingMessagesByType,
                        outgoingMessages = outgoingMessagesByType,
                        dictionary = getDictionaryAdapter(),
                        seedVariables = sessionTemplateVariables(session, index + 1),
                    )
                val result = session.sendFixMessage(resolvedFields.toRawMessage(), _dictionary.value)
                logger.info("sendMessageToAllConnectedSessions: sent to '${session.title}', result: $result")
                SessionSendOutcome(session, result)
            }

        val failed = outcomes.filter { it.result is com.knapsack.fixtool.service.SendResult.Failed }
        if (failed.isEmpty()) {
            showNotification("Message sent to ${outcomes.size} session(s)", NotificationType.SUCCESS)
        } else {
            showNotification(
                "Sent to ${outcomes.size - failed.size}/${outcomes.size} sessions - " +
                    "failed: ${failed.joinToString { it.session.title }}",
                NotificationType.WARNING,
            )
        }
        return outcomes
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

    /** Parses a raw FIX string into editor fields (so template expressions can be resolved). */
    private fun rawToFields(raw: String): List<FixField> =
        com.knapsack.fixtool.service.FixMessageHelper
            .parseFixMessage(raw)
            .map { (tag, value) -> FixField(tag = tag.toString(), value = value) }

    /**
     * Resolves template expressions in [raw] against the session at [sessionIndex] (per-session
     * variables, latest in/out messages) and sends the resolved message — the same path the editor
     * "Send" button uses, exposed for automation. Returns null if the session index is invalid.
     */
    fun sendResolvedToSession(raw: String, sessionIndex: Int): com.knapsack.fixtool.service.SendResult? {
        val session = _sessions.getOrNull(sessionIndex) ?: return null
        updateMessageMaps()
        val resolved =
            rawToFields(raw).resolveTemplates(
                incomingMessages = incomingMessagesByType,
                outgoingMessages = outgoingMessagesByType,
                dictionary = getDictionaryAdapter(),
                seedVariables = sessionTemplateVariables(session, sessionIndex + 1),
            )
        return session.sendFixMessage(resolved.toRawMessage(), _dictionary.value)
    }

    /** Raw-string overload of [sendMessageToAllConnectedSessions]; resolves per session. */
    fun sendMessageToAllConnectedSessions(raw: String): List<SessionSendOutcome> =
        sendMessageToAllConnectedSessions(rawToFields(raw))

    // Connection management methods
    fun connectProfile(profileId: String, profile: FixConnectionProfile) {
        // Acceptors bind a single listen port, so they always run as one session
        val targetCount =
            if (profile.config.connectionType == FixConnectionConfig.ConnectionType.INITIATOR) {
                profile.config.sessionCount.coerceAtLeast(1)
            } else {
                1
            }

        val identityErrors = SessionIdentityResolver.validate(profile.config, targetCount)
        if (identityErrors.isNotEmpty()) {
            identityErrors.forEach { showNotification(it, NotificationType.ERROR) }
            return
        }

        val existingIndices = profileToSessionMap[profileId]?.filter { it in _sessions.indices }.orEmpty()
        val reconnected = reconnectExistingSessions(existingIndices, profile, targetCount)
        val created = createMissingSessions(profileId, existingIndices, profile, targetCount)

        // Auto-select profile and activate session if none is currently selected
        if ((reconnected || created) && _selectedEditorProfile.value == null) {
            logger.info("Auto-selecting profile '{}' in message editor", profile.name)
            setSelectedEditorProfile(profile)
        }
    }

    /**
     * Reconnects a profile's existing sessions that are down; sessions already connecting or
     * connected are left alone. Each session re-resolves its slot's identity from the profile
     * so config edits take effect on reconnect.
     * @return true if at least one session was reconnected
     */
    private fun reconnectExistingSessions(existingIndices: List<Int>, profile: FixConnectionProfile, targetCount: Int): Boolean {
        var reconnected = false
        existingIndices.forEach { index ->
            val session = _sessions[index]
            val currentState = session.connectionState.value
            if (currentState == FixConnectionState.CONNECTING ||
                currentState == FixConnectionState.CONNECTED ||
                currentState == FixConnectionState.LOGGED_ON
            ) {
                logger.info("Session already connecting/connected: {}", session.title)
            } else {
                logger.info("Reconnecting session: {}", session.title)
                val config =
                    if (session.profileSlot > 0) {
                        SessionIdentityResolver.resolve(profile.config, session.profileSlot, targetCount.coerceAtLeast(session.profileSlot))
                    } else {
                        profile.config
                    }
                enableLatencyTrackingIfConfigured(session)
                session.connect(config, _appSettings.value, _dictionary.value)
                reconnected = true
            }
        }
        return reconnected
    }

    /**
     * Creates sessions for any slots of the profile's group not yet occupied, up to [targetCount].
     * @return true if at least one session was created
     */
    private fun createMissingSessions(
        profileId: String,
        existingIndices: List<Int>,
        profile: FixConnectionProfile,
        targetCount: Int,
    ): Boolean {
        if (existingIndices.size >= targetCount) return false

        val usedSlots = existingIndices.mapTo(mutableSetOf()) { _sessions[it].profileSlot }
        val freeSlots = (1..targetCount).filter { it !in usedSlots }

        freeSlots.take(targetCount - existingIndices.size).forEach { slot ->
            val isMultiSession = targetCount > 1
            val config =
                if (isMultiSession) SessionIdentityResolver.resolve(profile.config, slot, targetCount) else profile.config
            val title = if (isMultiSession) "${profile.name} [$slot]" else profile.name

            logger.info(
                "Creating new session '{}' for profile: {} (SenderCompID: {}, qualifier: '{}')",
                title,
                profile.name,
                config.senderCompID,
                config.sessionQualifier,
            )
            val session = createNewSession(title, config.sessionQualifier, profileSlot = if (isMultiSession) slot else 0)
            profileToSessionMap.getOrPut(profileId) { mutableListOf() }.add(_sessions.size - 1)

            enableLatencyTrackingIfConfigured(session)
            session.connect(config, _appSettings.value, _dictionary.value)
        }
        return true
    }

    private fun enableLatencyTrackingIfConfigured(session: FixMessageSession) {
        if (_appSettings.value.enableLatencyTracking) {
            session.enableLatencyTracking(
                correlationTags = _appSettings.value.latencyCorrelationTags,
                historySize = _appSettings.value.latencyHistorySize,
                warningThresholdMicros = _appSettings.value.latencyWarningThresholdMicros,
                criticalThresholdMicros = _appSettings.value.latencyCriticalThresholdMicros,
                networkInterface = _appSettings.value.captureNetworkInterface.ifBlank { null },
            )
        }
    }

    fun disconnectProfile(profileId: String) {
        getProfileSessions(profileId).forEach { it.disconnect() }
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
        // For multi-session profiles, report the most-connected state across the group
        val states = getProfileSessions(profileId).map { it.connectionState.value }
        return states.minByOrNull { connectionStateRank(it) } ?: FixConnectionState.DISCONNECTED
    }

    private fun connectionStateRank(state: FixConnectionState): Int =
        when (state) {
            FixConnectionState.LOGGED_ON -> 0
            FixConnectionState.CONNECTED -> 1
            FixConnectionState.CONNECTING -> 2
            FixConnectionState.ERROR -> 3
            FixConnectionState.DISCONNECTED -> 4
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

    fun getProfileSession(profileId: String): FixMessageSession? = getProfileSessions(profileId).firstOrNull()

    /**
     * Returns all sessions belonging to a profile, in creation order.
     * A profile owns multiple sessions when its sessionCount is greater than 1.
     */
    fun getProfileSessions(profileId: String): List<FixMessageSession> =
        profileToSessionMap[profileId]
            ?.filter { it in _sessions.indices }
            ?.map { _sessions[it] }
            .orEmpty()

    // Profile management methods
    private fun loadConnectionProfiles() {
        _connectionProfiles.clear()
        _connectionProfiles.addAll(profileService.loadProfiles().sortedBy { it.name.lowercase() })
    }

    /** @return true if the profile was persisted; false if the underlying save failed. */
    fun saveConnectionProfile(profile: FixConnectionProfile): Boolean {
        var persisted = false
        profileService
            .saveProfile(profile)
            .onSuccess {
                loadConnectionProfiles()
                persisted = true
            }.onFailure { error ->
                logger.error("Failed to save connection profile: ${error.message}", error)
            }
        return persisted
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
        if (currentState is MessageEditorState.Clean) {
            _editorState.value =
                MessageEditorState.Dirty(
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
        _editorState.value = MessageEditorState.New
    }

    /**
     * Validates template expressions in fields with incoming/outgoing message context
     * Used before sending to ensure all expressions can be resolved
     */
    fun validateTemplateExpressions(
        fields: List<FixField>,
        incomingMessages: Map<String, FixMessage>,
        outgoingMessages: Map<String, FixMessage>,
        seedVariables: Map<String, String> = emptyMap(),
    ): List<String> {
        val errors = mutableListOf<String>()
        // Use shared variables map so variables defined in earlier fields are available to later fields
        val sharedVariables = seedVariables.toMutableMap()

        fields.forEach { field ->
            if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                val templateErrors =
                    FixMessageTemplate.validateExpressions(
                        field.value,
                        incomingMessages = incomingMessages,
                        outgoingMessages = outgoingMessages,
                        variables = sharedVariables,
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
    fun getDictionaryAdapter(): FixDictionaryAdapter = _dictionary.value

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
            profileToSessionMap.values.forEach { indices ->
                for (i in indices.indices) {
                    val sessionIndex = indices[i]
                    indices[i] =
                        when {
                            sessionIndex == fromIndex -> toIndex
                            fromIndex < toIndex && sessionIndex > fromIndex && sessionIndex <= toIndex -> sessionIndex - 1
                            fromIndex > toIndex && sessionIndex >= toIndex && sessionIndex < fromIndex -> sessionIndex + 1
                            else -> sessionIndex
                        }
                }
            }
        }
    }

    // Demo Server Management
    val demoServerFixVersion: StateFlow<FixVersion?> = DemoServerManager.currentFixVersion

    fun startDemoServer(fixVersion: FixVersion = FixVersion.FIX_4_4) {
        try {
            DemoServerManager.start(fixVersion)
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
                is MessageEditorState.Clean,
                is MessageEditorState.Dirty,
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
                is MessageEditorState.New -> {
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
                    MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    /** Result of [saveTemplateDirect]: the persisted message plus whether it was newly created. */
    data class TemplateSaveResult(val message: SavedFixMessage, val created: Boolean)

    /**
     * Saves a template directly, independent of the message editor's current state. Creates a new
     * template, or updates the existing one when [id] matches a saved message under [profileId].
     * Intended for automation/control callers; refreshes the in-memory list so the UI reflects the
     * change.
     * @return the [TemplateSaveResult], or null if the underlying persistence failed.
     */
    @Suppress("LongParameterList") // distinct template attributes; a DTO would only add ceremony for one caller
    fun saveTemplateDirect(
        profileId: String,
        name: String,
        fields: List<SavedFixField>,
        userTags: Set<String> = setOf(profileId),
        isFavorite: Boolean = false,
        id: String? = null,
    ): TemplateSaveResult? {
        // Look the existing record up from the store (not the in-memory list, which a headless
        // control caller may not have populated) so an update preserves createdAt and version.
        val existing = id?.let { mid -> savedMessagesService.loadMessagesForProfile(profileId).find { it.id == mid } }
        val message =
            SavedFixMessage(
                id = id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                userTags = userTags,
                fields = fields,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                version = (existing?.version ?: 0) + 1,
                isFavorite = isFavorite,
            )
        var persisted = false
        savedMessagesService
            .saveMessage(profileId, message)
            .onSuccess { loadSavedMessagesForActiveSession(); persisted = true }
            .onFailure { error -> logger.error("Failed to save template: ${error.message}", error) }
        return if (persisted) TemplateSaveResult(message, created = existing == null) else null
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
                    MessageEditorState.Clean(
                        messageId = savedMessage.id,
                        messageName = savedMessage.name,
                        userTags = savedMessage.getAllUserTags(),
                    )
            }.onFailure { error ->
                logger.error("Failed to save message: ${error.message}", error)
            }
    }

    fun getCurrentProfileId(): String? =
        activeSession?.let {
            profileIdForSessionIndex(_activeSessionIndex.value)
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
            MessageEditorState.Clean(
                messageId = savedMessage.id,
                messageName = savedMessage.name,
                userTags = savedMessage.getAllUserTags(),
            )

        // Auto-select appropriate profile/session based on template's associated profiles
        autoSelectProfileForMessage(savedMessage)

        // Mark message as used
        activeSession?.let { session ->
            val currentProfileId = profileIdForSessionIndex(_activeSessionIndex.value)
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
                    _editorState.value = MessageEditorState.New
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
        type: NotificationType = NotificationType.ERROR,
    ) {
        logger.info("Showing notification: [$type] $message")
        val notification =
            Notification(
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
        val profile =
            FixConnectionProfile(
                name = profileName,
                config =
                    FixConnectionConfig(
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
        profileToSessionMap[profile.id] = mutableListOf(sessionIndex)

        return Pair(profile, session)
    }

    override fun onCleared() {
        super.onCleared()
        _sessions.forEach { it.destroy() }
        DemoServerManager.stop()
    }
}
