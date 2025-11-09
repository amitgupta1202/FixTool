package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.FixConnectionManager
import com.knapsack.fixtool.service.QuickFixService
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class FixMessageSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    private val onError: ((String) -> Unit)? = null,
) {
    companion object {
        private val BUFFER_MSG_SIZE = System.getProperty("noOfMsgToBuffer", "1000").toInt()
        private val POLL_PERIOD_MS = System.getProperty("pollInMs", "100").toLong()
    }

    private val logger = NotifyingLogger(FixMessageSession::class.java, onError)

    private val _messages = MutableStateFlow<List<AppMessage>>(emptyList())
    val messages: StateFlow<List<AppMessage>> = _messages.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.RAW)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _wrapText = MutableStateFlow(true)
    val wrapText: StateFlow<Boolean> = _wrapText.asStateFlow()

    private val _searchVisible = MutableStateFlow(false)
    val searchVisible: StateFlow<Boolean> = _searchVisible.asStateFlow()

    private val _filterVisible = MutableStateFlow(false)
    val filterVisible: StateFlow<Boolean> = _filterVisible.asStateFlow()

    private val _filterRegex = MutableStateFlow("")
    val filterRegex: StateFlow<String> = _filterRegex.asStateFlow()

    // Direction filter states
    private val _filterShowIncoming = MutableStateFlow(true)
    val filterShowIncoming: StateFlow<Boolean> = _filterShowIncoming.asStateFlow()

    private val _filterShowOutgoing = MutableStateFlow(true)
    val filterShowOutgoing: StateFlow<Boolean> = _filterShowOutgoing.asStateFlow()

    private val _filterShowSeparator = MutableStateFlow(true)
    val filterShowSeparator: StateFlow<Boolean> = _filterShowSeparator.asStateFlow()

    // Message type filter (comma-separated list like "R,D,0,5")
    private val _filterMessageTypes = MutableStateFlow("")
    val filterMessageTypes: StateFlow<String> = _filterMessageTypes.asStateFlow()

    // Hide protocol tags state (for PARSED view)
    private val _hideProtocolTags = MutableStateFlow(true)
    val hideProtocolTags: StateFlow<Boolean> = _hideProtocolTags.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow(FixConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FixConnectionState> = _connectionState.asStateFlow()

    private val _connectionConfig = MutableStateFlow<FixConnectionConfig?>(null)
    private var _appSettings: AppSettings? = null
    private var _dictionary: FixDictionary? = null

    private val messageQueue = LinkedBlockingQueue<AppMessage>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var isActive = true
    private var quickFixService: QuickFixService? = null
    private var connectionManager: FixConnectionManager? = null

    enum class ViewMode {
        RAW,
        PARSED,
    }

    init {
        startMessagePolling()
    }

    fun toggleViewMode() {
        _viewMode.value =
            when (_viewMode.value) {
                ViewMode.RAW -> ViewMode.PARSED
                ViewMode.PARSED -> ViewMode.RAW
            }
    }

    fun toggleWrapText() {
        _wrapText.value = !_wrapText.value
    }

    fun toggleSearch() {
        _searchVisible.value = !_searchVisible.value
    }

    fun toggleFilter() {
        _filterVisible.value = !_filterVisible.value
    }

    fun setFilterRegex(regex: String) {
        _filterRegex.value = regex
    }

    fun setFilterShowIncoming(show: Boolean) {
        _filterShowIncoming.value = show
    }

    fun setFilterShowOutgoing(show: Boolean) {
        _filterShowOutgoing.value = show
    }

    fun setFilterShowSeparator(show: Boolean) {
        _filterShowSeparator.value = show
    }

    fun setFilterMessageTypes(types: String) {
        _filterMessageTypes.value = types
    }

    fun toggleHideProtocolTags() {
        _hideProtocolTags.value = !_hideProtocolTags.value
    }

    private fun startMessagePolling() {
        scope.launch {
            while (isActive) {
                val message = messageQueue.poll()
                if (message != null) {
                    val currentMessages = _messages.value
                    _messages.value =
                        if (currentMessages.size >= BUFFER_MSG_SIZE) {
                            // Drop the oldest message and add the new one
                            currentMessages.drop(1) + message
                        } else {
                            currentMessages + message
                        }
                }
                delay(POLL_PERIOD_MS) // Poll every 100ms
            }
        }
    }

    fun addMessage(message: FixMessage) {
        // Filter heartbeat messages if showHeartbeat is false
        val config = _connectionConfig.value
        if (config != null && !config.showHeartbeat && message.messageType == "0") {
            // Skip heartbeat messages when showHeartbeat is disabled
            return
        }
        messageQueue.offer(message)
    }

    fun addSeparator() {
        messageQueue.offer(Separator(timestamp = LocalDateTime.now()))
    }

    fun clearMessages() {
        _messages.value = emptyList()
        messageQueue.clear()
        logger.info("Cleared messages for session: {}", title)
    }

    fun reconnect() {
        val config = _connectionConfig.value
        val settings = _appSettings
        val dictionary = _dictionary
        if (config != null && settings != null) {
            connect(config, settings, dictionary)
        } else {
            logger.info("Cannot reconnect: No connection config or app settings available")
        }
    }

    fun connect(config: FixConnectionConfig, appSettings: AppSettings, dictionary: FixDictionary? = null) {
        try {
            // Use provided dictionary or create a default empty one
            val effectiveDictionary = dictionary ?: FixDictionaryAdapter.createDefault()

            _connectionState.value = FixConnectionState.CONNECTING
            _connectionConfig.value = config
            _appSettings = appSettings
            _dictionary = effectiveDictionary

            // Create QuickFIX service
            quickFixService =
                QuickFixService(
                    config = config,
                    onMessageReceived = { message -> addMessage(message) },
                ) { state -> _connectionState.value = state }

            // Create connection manager
            connectionManager = FixConnectionManager(config, quickFixService!!, appSettings, effectiveDictionary)

            // Start the connection
            connectionManager?.start()

            logger.info("Connection initiated for session: {}", title)
        } catch (e: Exception) {
            _connectionState.value = FixConnectionState.ERROR
            val errorMsg = "Failed to connect: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
        }
    }

    fun disconnect() {
        try {
            // Send logout message if currently logged on
            if (_connectionState.value == FixConnectionState.LOGGED_ON) {
                logger.info("Sending logout before disconnect for session: {}", title)
                quickFixService?.logout()

                // Give the logout message time to be sent before closing socket
                Thread.sleep(500)
            }

            connectionManager?.stop()
            connectionManager = null
            quickFixService = null
            _connectionState.value = FixConnectionState.DISCONNECTED

            logger.info("Disconnected session: {}", title)
        } catch (e: Exception) {
            _connectionState.value = FixConnectionState.ERROR
            val errorMsg = "Error disconnecting: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
        }
    }

    fun sendFixMessage(rawMessage: String, dictionary: FixDictionary) {
        // Send via QuickFIX - outgoing message will be captured by toApp/toAdmin callbacks
        val success = quickFixService?.sendMessage(rawMessage, dictionary) ?: false
        if (!success) {
            logger.error("Failed to send message") // TODO: handle errors
        }
    }

    fun destroy() {
        disconnect()
        isActive = false
        messageQueue.clear()
    }
}
