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
    private val onWarning: ((String) -> Unit)? = null,
) {
    companion object {
        private val BUFFER_MSG_SIZE = System.getProperty("noOfMsgToBuffer", "1000").toInt()
        private val POLL_PERIOD_MS = System.getProperty("pollInMs", "100").toLong()
        private const val DRAIN_BATCH_SIZE = 100
        private const val QUEUE_MULTIPLIER = 2
    }

    private val logger = NotifyingLogger(FixMessageSession::class.java, onError)

    private val _messages = MutableStateFlow<List<AppMessage>>(emptyList())
    val messages: StateFlow<List<AppMessage>> = _messages.asStateFlow()

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

    // Recently sent message timestamp (for highlighting)
    private val _recentlySentMessageTimestamp = MutableStateFlow<LocalDateTime?>(null)
    val recentlySentMessageTimestamp: StateFlow<LocalDateTime?> = _recentlySentMessageTimestamp.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow(FixConnectionState.DISCONNECTED)
    val connectionState: StateFlow<FixConnectionState> = _connectionState.asStateFlow()

    private val _connectionConfig = MutableStateFlow<FixConnectionConfig?>(null)
    private var _appSettings: AppSettings? = null
    private var _dictionary: FixDictionary? = null

    private val messageQueue = LinkedBlockingQueue<AppMessage>(BUFFER_MSG_SIZE * QUEUE_MULTIPLIER)
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

    private fun startMessagePolling() {
        scope.launch {
            while (isActive) {
                val batch = mutableListOf<AppMessage>()
                // Drain a bounded batch to keep up during bursts
                messageQueue.drainTo(batch, DRAIN_BATCH_SIZE)
                // drainTo may return 0 even when one item remains, so poll once more
                messageQueue.poll()?.let { batch.add(it) }

                if (batch.isNotEmpty()) {
                    // Use mutable list for efficient batch processing, then convert once
                    val current = _messages.value.toMutableList()
                    batch.forEach { message ->
                        if (current.size >= BUFFER_MSG_SIZE) {
                            current.removeFirst()
                        }
                        current.add(message)
                    }
                    _messages.value = current.toList()
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
        // Drop oldest enqueued item if we're at capacity to avoid unbounded growth
        if (!messageQueue.offer(message)) {
            messageQueue.poll()
            messageQueue.offer(message)
        }
    }

    fun addSeparator() {
        messageQueue.offer(Separator(timestamp = LocalDateTime.now()))
    }

    /**
     * Flushes the message queue synchronously, immediately processing all queued messages.
     * This is primarily for testing to ensure messages are processed before assertions.
     */
    fun flushMessageQueue() {
        val batch = mutableListOf<AppMessage>()
        messageQueue.drainTo(batch)
        if (batch.isNotEmpty()) {
            val current = _messages.value.toMutableList()
            batch.forEach { message ->
                if (current.size >= BUFFER_MSG_SIZE) {
                    current.removeFirst()
                }
                current.add(message)
            }
            _messages.value = current.toList()
        }
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
                    onStateChanged = { state -> _connectionState.value = state },
                    onError = onError,
                    onWarning = onWarning,
                )

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
        scope.launch {
            try {
                // Send logout message if currently logged on
                if (_connectionState.value == FixConnectionState.LOGGED_ON) {
                    logger.info("Sending logout before disconnect for session: {}", title)
                    quickFixService?.logout()

                    // Wait for QuickFIX/J to complete the logout sequence via onLogout callback
                    // The onLogout callback will set state to DISCONNECTED
                    val logoutTimeout = 2000L // 2 seconds max wait
                    val startTime = System.currentTimeMillis()

                    while (_connectionState.value == FixConnectionState.LOGGED_ON &&
                        System.currentTimeMillis() - startTime < logoutTimeout
                    ) {
                        delay(50)
                    }

                    if (_connectionState.value == FixConnectionState.LOGGED_ON) {
                        logger.warn("Logout timeout - forcing disconnect for session: {}", title)
                    } else {
                        logger.info("Logout completed successfully for session: {}", title)
                    }
                }

                // Now safely stop the connection manager after logout is complete
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
    }

    fun sendFixMessage(rawMessage: String, dictionary: FixDictionary) {
        // Send via QuickFIX - outgoing message will be captured by toApp/toAdmin callbacks
        val success = quickFixService?.sendMessage(rawMessage, dictionary) ?: false
        if (!success) {
            logger.error("Failed to send message") // TODO: handle errors
        } else {
            // Mark current time as recently sent - the outgoing message will appear shortly
            val sentTime = LocalDateTime.now()
            _recentlySentMessageTimestamp.value = sentTime

            // Clear the highlight after 3 seconds
            scope.launch {
                delay(3000)
                // Only clear if it's still the same timestamp (in case another message was sent)
                if (_recentlySentMessageTimestamp.value == sentTime) {
                    _recentlySentMessageTimestamp.value = null
                }
            }
        }
    }

    fun destroy() {
        disconnect()
        isActive = false
        messageQueue.clear()
    }
}
