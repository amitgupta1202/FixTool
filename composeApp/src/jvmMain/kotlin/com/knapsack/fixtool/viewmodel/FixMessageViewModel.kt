package com.knapsack.fixtool.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.knapsack.fixtool.model.*
import com.knapsack.fixtool.service.AppSettingsService
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageValidator
import com.knapsack.fixtool.service.SavedMessagesService
import com.knapsack.fixtool.service.demo.DemoServerManager
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FixMessageViewModel : ViewModel() {
    private val logger =
        NotifyingLogger(
            FixMessageViewModel::class.java,
            onNotify = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
        )

    private val _sessions = mutableStateListOf<FixMessageSession>()
    val sessions: List<FixMessageSession> = _sessions

    private val _activeSessionIndex = mutableStateOf(-1) // -1 means no session selected
    val activeSessionIndex: Int
        get() = _activeSessionIndex.value

    val activeSession: FixMessageSession?
        get() = if (_activeSessionIndex.value >= 0) _sessions.getOrNull(_activeSessionIndex.value) else null

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

    // Connection profiles
    private val profileService =
        ConnectionProfileService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
        )
    private val _connectionProfiles = mutableStateListOf<FixConnectionProfile>()
    val connectionProfiles: List<FixConnectionProfile> = _connectionProfiles

    // App settings
    private val settingsService = AppSettingsService()
    private val _appSettings = mutableStateOf(AppSettings.default())
    val appSettings: AppSettings
        get() = _appSettings.value

    // Saved messages
    private val savedMessagesService =
        SavedMessagesService(
            onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
        )
    private val _savedMessages = mutableStateListOf<SavedFixMessage>()
    val savedMessages: List<SavedFixMessage> = _savedMessages

    // Track currently loaded message for editing
    private val _currentLoadedMessageName = MutableStateFlow<String?>(null)
    val currentLoadedMessageName: StateFlow<String?> = _currentLoadedMessageName

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
                    logger.warn("Data dictionary file not found: {}", dictionaryPath)
                    _isDictionaryValid.value = false
                    _dictionaryErrorMessage.value = "Data dictionary file not found: $dictionaryPath"
                }
            } else {
                logger.info("No data dictionary configured, using default")
                _isDictionaryValid.value = false
                _dictionaryErrorMessage.value =
                    "No data dictionary configured. Please configure a data dictionary in Settings."
            }
        } catch (e: Exception) {
            _isDictionaryValid.value = false
            _dictionaryErrorMessage.value = "Failed to load data dictionary: ${e.message}"
            logger.error("Failed to load data dictionary: ${e.message}", e, notifyUser = true)
            // Keep using default dictionary
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
                onError = { errorMsg -> showNotification(errorMsg, NotificationType.ERROR) },
            )
        _sessions.add(session)
        // Auto-select first session if none is selected
        if (_activeSessionIndex.value == -1) {
            _activeSessionIndex.value = 0
        }
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
            } else if (_activeSessionIndex.value >= _sessions.size) {
                _activeSessionIndex.value = _sessions.size - 1
            } else if (_activeSessionIndex.value > index) {
                _activeSessionIndex.value--
            } else if (_activeSessionIndex.value == index) {
                // If closing the active session, select the first available session
                _activeSessionIndex.value = 0
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
            _activeSessionIndex.value = index
            // Reload messages when session selection changes
            loadSavedMessagesForActiveSession()
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
            }
        }
    }

    fun pasteAndDisplayMessage(rawMessage: String) {
        try {
            // Parse the raw message using the loaded data dictionary
            val dataDictionary = _dictionary.value.getDataDictionary()

            // Parse the message
            val quickfixMessage =
                if (dataDictionary != null) {
                    rawMessage.toQuickFixMessage(dataDictionary)
                } else {
                    // Parse without validation if no data dictionary
                    rawMessage.toQuickFixMessage()
                }

            // Create a FixMessage object for display (not connected to any session)
            val fixMessage =
                FixMessage(
                    timestamp = java.time.LocalDateTime.now(),
                    direction = FixMessage.Direction.INCOMING, // Default to incoming for pasted messages
                    rawMessage = rawMessage,
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

    fun toggleSettingsDialog() {
        _showSettingsDialog.value = !_showSettingsDialog.value
    }

    fun toggleViewModeForAllSessions() {
        // Toggle view mode for all sessions
        _sessions.forEach { session ->
            session.toggleViewMode()
        }
    }

    fun saveAppSettings(settings: AppSettings) {
        _appSettings.value = settings
        settingsService.saveSettings(settings)
        // Reload dictionary when settings change
        loadDictionaryFromSettings()
        // Validate the new dictionary
        validateDataDictionary()
    }

    fun sendMessage(sessionIndex: Int, rawMessage: String) {
        // Use sendFixMessage which handles both demo and real connections
        _sessions.getOrNull(sessionIndex)?.sendFixMessage(rawMessage, _dictionary.value)
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
            existingSession.connect(profile.config, _appSettings.value, _dictionary.value)
        } else {
            // Create new session for this profile
            logger.info("Creating new session for profile: {}", profile.name)
            val session = createNewSession(profile.name)
            val newSessionIndex = _sessions.size - 1
            profileToSessionMap[profileId] = newSessionIndex
            session.connect(profile.config, _appSettings.value, _dictionary.value)
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
        profileService.saveProfile(profile)
        loadConnectionProfiles()
    }

    fun deleteConnectionProfile(profileId: String) {
        // Don't delete demo profiles - they're managed by the demo server
        if (DemoServerManager.isDemoProfile(profileId)) {
            logger.warn("Cannot delete demo profile: {}", profileId)
            return
        }

        profileService.deleteProfile(profileId)
        loadConnectionProfiles()
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
        profileService.saveProfile(clonedProfile)
        loadConnectionProfiles()
        return clonedProfile
    }

    // Message editor field management
    fun updateEditorField(index: Int, field: FixField) {
        if (index in _editorFields.indices) {
            _editorFields[index] = field
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
        } else {
            // Single selection mode
            if (_editorFields.size > 1 && index in _editorFields.indices) {
                _editorFields.removeAt(index)
                val newIndex = if (index >= _editorFields.size) _editorFields.size - 1 else index
                _editorSelectedFieldIndex.value = newIndex
                _editorSelectedIndices.clear()
                _editorSelectedIndices.add(newIndex)
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

    fun clearEditorFields(resetSelection: Boolean = true) {
        _editorFields.clear()
        _editorFields.add(FixField())
        if (resetSelection) {
            _editorSelectedFieldIndex.value = 0
            _editorSelectedIndices.clear()
            _editorSelectedIndices.add(0)
        }
        // Clear loaded message name when fields are cleared
        _currentLoadedMessageName.value = null
    }

    fun validateEditorMessage(fields: List<FixField>): List<String> {
        _editorValidationErrors.clear()

        if (!_dictionary.value.isLoaded()) {
            _editorValidationErrors.add(
                "No data dictionary configured. Please configure a data dictionary in settings.",
            )
            return _editorValidationErrors
        }

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

    // Saved Messages Operations
    fun saveEditorMessage(name: String, fields: List<FixField>, profileId: String) {
        val savedFields = fields.map { SavedFixField(tag = it.tag, value = it.value, excluded = it.excluded) }
        val savedMessage = SavedFixMessage(name = name, profileId = profileId, fields = savedFields)

        val updatedMessages = savedMessagesService.saveMessage(profileId, savedMessage)
        _savedMessages.clear()
        _savedMessages.addAll(updatedMessages)
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

        // Track the loaded message name for potential editing
        _currentLoadedMessageName.value = savedMessage.name

        // Mark message as used
        activeSession?.let { session ->
            val currentProfileId = profileToSessionMap.entries.find { it.value == _activeSessionIndex.value }?.key
            if (currentProfileId != null) {
                savedMessagesService.markMessageAsUsed(currentProfileId, savedMessage.id)
            }
        }
    }

    fun loadSavedMessagesForActiveSession() {
        // Check if there is no active session selected (index is -1) or no sessions exist
        if (_activeSessionIndex.value == -1 || _sessions.isEmpty()) {
            // No session selected or no sessions created yet, show all messages from all profiles
            val allMessages = mutableListOf<SavedFixMessage>()
            connectionProfiles.forEach { profile ->
                allMessages.addAll(savedMessagesService.loadMessagesForProfile(profile.id))
            }
            _savedMessages.clear()
            _savedMessages.addAll(allMessages)
        } else {
            // There is an active session selected, filter by the active session's profile
            val currentProfileId = profileToSessionMap.entries.find { it.value == _activeSessionIndex.value }?.key
            if (currentProfileId != null) {
                val messages = savedMessagesService.loadMessagesForProfile(currentProfileId)
                _savedMessages.clear()
                _savedMessages.addAll(messages)
            } else {
                // Fallback: show all messages if we can't find the profile
                val allMessages = mutableListOf<SavedFixMessage>()
                connectionProfiles.forEach { profile ->
                    allMessages.addAll(savedMessagesService.loadMessagesForProfile(profile.id))
                }
                _savedMessages.clear()
                _savedMessages.addAll(allMessages)
            }
        }
    }

    fun deleteSavedMessage(messageId: String, profileId: String) {
        // Delete the message using the profileId from the message itself
        val updatedMessages = savedMessagesService.deleteMessage(profileId, messageId)

        // Reload all saved messages to reflect the deletion
        loadSavedMessagesForActiveSession()

        // Clear loaded message name if we just deleted the currently loaded template
        if (_currentLoadedMessageName.value != null) {
            val deletedMessage = _savedMessages.find { it.id == messageId }
            if (deletedMessage?.name == _currentLoadedMessageName.value) {
                _currentLoadedMessageName.value = null
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

    override fun onCleared() {
        super.onCleared()
        _sessions.forEach { it.destroy() }
        DemoServerManager.stop()
    }
}
