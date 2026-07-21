package com.knapsack.fixtool.model

// Latency tracking model imports are in this package (CaptureStatus, PacketDirection, TimestampSource)
import com.knapsack.fixtool.service.FixConnectionManager
import com.knapsack.fixtool.service.LatencyTrackingManager
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

@Suppress("TooManyFunctions") // a session aggregates message I/O, filtering, latency and admin controls
class FixMessageSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sessionQualifier: String = "",
    // 1-based slot within a multi-session profile group; 0 for standalone sessions.
    // Reconnects re-resolve the slot's identity from the profile, so profile edits take effect.
    val profileSlot: Int = 0,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    /**
     * **How big a burst this session can absorb before it starts throwing traffic away.**
     *
     * Deliberately not `bufferSize * 2`, which is what it used to be and what made a display preference into
     * a throughput limit: a user who shrank the grid window to keep it readable also shrank the burst buffer
     * by the same factor, and started losing messages at a rate that had nothing to do with the choice they
     * thought they were making. Retention answers "how much history do I want to scroll"; this answers "how
     * far ahead of the drain may the wire get". They are unrelated, and only one of them is the user's
     * business.
     *
     * The floor covers one drain cycle at a rate no FIX session realistically sustains, so what fills this is
     * not throughput any more but a stall — a GC pause, a starved dispatcher, a debugger breakpoint. It never
     * *reduces* anyone's depth: a session deliberately configured with a huge window keeps its proportional
     * queue. Injectable so a test can force the overflow path deterministically instead of by volume.
     */
    private val queueDepth: Int = maxOf(bufferSize * QUEUE_MULTIPLIER, MIN_QUEUE_DEPTH),
    private val onError: ((String) -> Unit)? = null,
    private val onWarning: ((String) -> Unit)? = null,
) {
    companion object {
        const val DEFAULT_BUFFER_SIZE = 1000
        private val POLL_PERIOD_MS = System.getProperty("pollInMs", "100").toLong()

        // DRAIN_BATCH_SIZE is gone rather than raised. It existed to bound an O(batch × bufferSize) drain,
        // and with O(1) eviction there is nothing left for it to bound — keeping it at any value would only
        // re-impose the ingest ceiling it used to be the cause of. See [retained].
        private const val QUEUE_MULTIPLIER = 2

        /**
         * The floor under [queueDepth], in messages.
         *
         * Sized as one drain cycle at 200,000 messages/second — an order of magnitude above what any FIX
         * session realistically sustains, chosen so that reaching it means something has *stalled* rather
         * than something is merely fast. Bounded on purpose: this queue is backpressure, and the only
         * alternative to dropping the head when it fills is to keep allocating until the heap ends the
         * process. A drop is recoverable and now counted; an OOM takes the evidence with it.
         */
        private const val MIN_QUEUE_DEPTH = 20_000
    }

    private val logger = NotifyingLogger(FixMessageSession::class.java, onError)

    private val _messages = MutableStateFlow<List<AppMessage>>(emptyList())
    val messages: StateFlow<List<AppMessage>> = _messages.asStateFlow()

    /**
     * **Messages this session received and threw away, because it could not ingest them fast enough.**
     *
     * Not the ring buffer's eviction of old messages — that is the retention policy working, and on a busy
     * session it happens constantly and means nothing. This counts the other thing: [addMessage] finding the
     * queue full and discarding its head to make room, so a message the venue sent never became visible to
     * the grid, to a scenario, or to anyone. That used to happen in total silence.
     *
     * It is a claim about *this tool*, not the counterparty, and it is the single most important number on a
     * session that carries market data: every downstream mystery — a step that timed out on a reply that was
     * definitely sent, a strict run that reports traffic it never showed — reduces to this when it is not
     * zero. See the drain loop in [startMessagePolling] for why the ceiling exists.
     */
    private val _discarded = MutableStateFlow(0L)
    val discarded: StateFlow<Long> = _discarded.asStateFlow()

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

    /** The config this session last connected with (per-session identity already resolved). */
    val currentConfig: FixConnectionConfig?
        get() = _connectionConfig.value
    private var _appSettings: AppSettings? = null
    private var _dictionary: FixDictionary? = null

    private val messageQueue = LinkedBlockingQueue<AppMessage>(queueDepth)
    private val scope = CoroutineScope(Dispatchers.Default)

    private var isActive = true
    private var quickFixService: QuickFixService? = null
    private var connectionManager: FixConnectionManager? = null
    private val latestIncomingByType = ConcurrentHashMap<String, FixMessage>()
    private val latestOutgoingByType = ConcurrentHashMap<String, FixMessage>()

    // Latency tracking
    private var latencyTrackingManager: LatencyTrackingManager? = null
    private val _latencyTrackingEnabled = MutableStateFlow(false)
    val latencyTrackingEnabled: StateFlow<Boolean> = _latencyTrackingEnabled.asStateFlow()

    private val _captureStatus = MutableStateFlow<CaptureStatus>(CaptureStatus.Stopped)
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus.asStateFlow()

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

    /**
     * **The retained window, as a deque.** Eviction is the whole reason this is not an ArrayList.
     *
     * The drain used to copy `_messages.value` into an ArrayList and call `removeFirst()` once per message
     * over capacity — an O(n) shift each time, so the loop cost O(batch × bufferSize). That quadratic is what
     * forced the batch cap, and the batch cap is what capped ingestion at roughly a thousand messages a
     * second per session; past it, [addMessage] discarded traffic that had already arrived. An ArrayDeque
     * evicts in O(1), so the batch no longer has to be small, and the drain is linear in what it drains
     * rather than in what the session is holding.
     *
     * Guarded by [retained] itself: it is touched only from the single drain coroutine and [clearMessages].
     * Readers never see it — they get the immutable snapshot published to [_messages].
     */
    private val retained = ArrayDeque<AppMessage>()

    private fun startMessagePolling() {
        scope.launch {
            while (isActive) {
                val batch = mutableListOf<AppMessage>()
                // The whole queue, not a fixed slice. What bounds this is the queue's own capacity, which is
                // already bufferSize-proportional — draining less than that per cycle only guaranteed the
                // backlog would grow until addMessage started throwing messages away.
                messageQueue.drainTo(batch)
                // drainTo may return 0 even when one item remains, so poll once more
                messageQueue.poll()?.let { batch.add(it) }

                if (batch.isNotEmpty()) {
                    synchronized(retained) {
                        batch.forEach { message ->
                            if (retained.size >= bufferSize) retained.removeFirst()
                            retained.addLast(message)
                        }
                        // One immutable snapshot per cycle, which is what StateFlow's readers require and
                        // what keeps the UI's recomposition batched. It copies references, not elements —
                        // the part that was moving whole arrays per message is gone.
                        _messages.value = retained.toList()
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
        // Track the most recent message per type so callers don't have to rescan history
        when (message.direction) {
            FixMessage.Direction.INCOMING -> {
                latestIncomingByType[message.messageType] = message
                // Record for latency tracking (app-level fallback)
                recordIncomingForLatency(message)
            }
            FixMessage.Direction.OUTGOING -> {
                latestOutgoingByType[message.messageType] = message
                // Record for latency tracking (app-level fallback)
                recordOutgoingForLatency(message)
            }
        }
        // Drop oldest enqueued item if we're at capacity to avoid unbounded growth. Counted, because a
        // message discarded here was received and then lost, and every silent one of them turns into a
        // question about the venue somewhere downstream. See [discarded].
        if (!messageQueue.offer(message)) {
            if (messageQueue.poll() != null) _discarded.value += 1
            messageQueue.offer(message)
        }
    }

    fun addSeparator() {
        messageQueue.offer(Separator(timestamp = LocalDateTime.now()))
    }

    /**
     * Flushes the message queue synchronously, immediately processing all queued messages.
     * This is primarily for testing to ensure messages are processed before assertions.
     *
     * Through [retained], like the drain, and not by rebuilding from [_messages]: the deque is the retained
     * window and the flow is a published snapshot of it. A second path that reconstructed the window from
     * its own snapshot would be a second decider for what the session is holding, and the two would drift
     * the first time they interleaved.
     */
    fun flushMessageQueue() {
        val batch = mutableListOf<AppMessage>()
        messageQueue.drainTo(batch)
        if (batch.isEmpty()) return
        synchronized(retained) {
            batch.forEach { message ->
                if (retained.size >= bufferSize) retained.removeFirst()
                retained.addLast(message)
            }
            _messages.value = retained.toList()
        }
    }

    fun clearMessages() {
        // The deque first, and under the same lock. Emptying only the published snapshot would leave the
        // window itself full, and the very next drain cycle would republish every message the user just
        // cleared — a clear that undoes itself a tenth of a second later.
        synchronized(retained) {
            retained.clear()
            _messages.value = emptyList()
        }
        messageQueue.clear()
        latestIncomingByType.clear()
        latestOutgoingByType.clear()
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
                    dictionary = effectiveDictionary,
                    onMessageReceived = { message -> addMessage(message) },
                    onStateChanged = { state ->
                        _connectionState.value = state
                        // Start latency tracking when logged on
                        if (state == FixConnectionState.LOGGED_ON && latencyTrackingManager != null) {
                            startLatencyTracking(config, appSettings.captureNetworkInterface.ifBlank { null })
                        }
                    },
                    onError = onError,
                    onWarning = onWarning,
                    onConnectionFailed = {
                        // Stop the connection manager when auto-reconnect is disabled
                        scope.launch {
                            logger.info("Stopping connection (auto-reconnect disabled)")
                            connectionManager?.stop()
                            connectionManager = null
                            quickFixService = null
                            _connectionState.value = FixConnectionState.ERROR
                        }
                    },
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
                // Stop latency tracking
                stopLatencyTracking()

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

    fun sendFixMessage(rawMessage: String, dictionary: FixDictionary): com.knapsack.fixtool.service.SendResult {
        // Send via QuickFIX - outgoing message will be captured by toApp/toAdmin callbacks
        val result =
            quickFixService?.sendMessage(rawMessage, dictionary)
                ?: return com.knapsack.fixtool.service.SendResult
                    .Failed("No QuickFIX service available")

        when (result) {
            is com.knapsack.fixtool.service.SendResult.Success,
            is com.knapsack.fixtool.service.SendResult.SuccessWithWarning,
            -> {
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
            is com.knapsack.fixtool.service.SendResult.Failed -> {
                logger.error("Failed to send message: ${result.error}")
            }
        }

        return result
    }

    fun snapshotLatestIncomingByType(): Map<String, FixMessage> = latestIncomingByType.toMap()

    fun snapshotLatestOutgoingByType(): Map<String, FixMessage> = latestOutgoingByType.toMap()

    // Admin / session-level controls (delegate to the underlying QuickFIX session).
    fun resetSequenceNumbers(sender: Int?, target: Int?): Boolean =
        quickFixService?.resetSequenceNumbers(sender, target) ?: false

    fun sequenceNumbers(): Pair<Int, Int>? = quickFixService?.sequenceNumbers()

    fun sendTestRequest(testReqId: String): Boolean = quickFixService?.sendTestRequest(testReqId) ?: false

    fun sendResendRequest(beginSeqNo: Int, endSeqNo: Int): Boolean =
        quickFixService?.sendResendRequest(beginSeqNo, endSeqNo) ?: false

    fun sendSequenceReset(newSeqNo: Int, gapFill: Boolean): Boolean =
        quickFixService?.sendSequenceReset(newSeqNo, gapFill) ?: false

    fun forceLogout(reason: String?): Boolean = quickFixService?.forceLogout(reason) ?: false

    fun forceDisconnect(reason: String): Boolean = quickFixService?.forceDisconnect(reason) ?: false

    // ========================================
    // Latency Tracking Methods
    // ========================================

    /**
     * Enable latency tracking for this session.
     * Should be called before or after connect() based on settings.
     */
    fun enableLatencyTracking(
        correlationTags: List<Int> = listOf(11, 131, 117, 262, 37, 17),
        historySize: Int = 10000,
        warningThresholdMicros: Long = 100_000L,
        criticalThresholdMicros: Long = 500_000L,
        networkInterface: String? = null,
        onFallbackNotification: ((String) -> Unit)? = null,
    ) {
        if (latencyTrackingManager != null) {
            logger.info("Latency tracking already enabled for session: {}", title)
            return
        }

        latencyTrackingManager =
            LatencyTrackingManager(
                correlationTags = correlationTags,
                historySize = historySize,
                warningThresholdMicros = warningThresholdMicros,
                criticalThresholdMicros = criticalThresholdMicros,
                onError = onError,
                onFallbackNotification = onFallbackNotification,
            )

        _latencyTrackingEnabled.value = true

        // If already connected, start tracking
        val config = _connectionConfig.value
        if (config != null && _connectionState.value == FixConnectionState.LOGGED_ON) {
            startLatencyTracking(config, networkInterface)
        }

        logger.info("Latency tracking enabled for session: {}", title)
    }

    /**
     * Disable latency tracking for this session
     */
    fun disableLatencyTracking() {
        latencyTrackingManager?.stopTracking()
        latencyTrackingManager = null
        _latencyTrackingEnabled.value = false
        _captureStatus.value = CaptureStatus.Stopped
        logger.info("Latency tracking disabled for session: {}", title)
    }

    /**
     * Start latency tracking (called when session connects)
     */
    private fun startLatencyTracking(
        config: FixConnectionConfig,
        networkInterface: String? = null,
    ) {
        val manager = latencyTrackingManager ?: return

        val port =
            config.port.toIntOrNull()
                ?: config.socketConnectPort.toIntOrNull()
                ?: config.socketAcceptPort.toIntOrNull()

        if (port == null) {
            logger.warn("Cannot start latency tracking: no valid port configured")
            return
        }

        // For TLS connections, skip packet capture entirely - encrypted payloads can't be parsed
        // Use application-level timestamps instead
        if (config.useSSL) {
            _captureStatus.value = CaptureStatus.Fallback("TLS connection - using app-level timestamps")
            logger.info(
                "Latency tracking started for session {} on port {}: app-level (TLS encrypted)",
                title,
                port,
            )
            return
        }

        // Determine the effective network interface
        // Use loopback (lo0) for localhost connections, otherwise use provided or auto-detect
        val host = config.socketConnectHost.lowercase()
        val effectiveInterface =
            networkInterface ?: if (isLocalhostAddress(host)) {
                "lo0" // Use loopback for localhost connections
            } else {
                null // Let PacketCaptureService auto-detect
            }

        val success = manager.startTracking(effectiveInterface, port)
        _captureStatus.value = manager.captureStatus.value

        logger.info(
            "Latency tracking started for session {} on port {} (interface: {}): {}",
            title,
            port,
            effectiveInterface ?: "auto",
            if (success) "packet capture" else "app-level fallback",
        )
    }

    /**
     * Check if the host is a localhost address
     */
    private fun isLocalhostAddress(host: String): Boolean =
        host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host.startsWith("127.")

    /**
     * Stop latency tracking (called when session disconnects)
     */
    private fun stopLatencyTracking() {
        latencyTrackingManager?.stopTracking()
        _captureStatus.value = CaptureStatus.Stopped
    }

    /**
     * Get the latency tracking service (for UI access)
     */
    fun getLatencyTrackingService() = latencyTrackingManager?.trackingService

    /**
     * Get latency for a specific message (for grid display)
     */
    fun getLatencyForMessage(rawMessage: String): Long? = latencyTrackingManager?.trackingService?.getLatencyForMessage(rawMessage)

    /**
     * Record an outgoing message for latency tracking
     */
    private fun recordOutgoingForLatency(message: FixMessage) {
        latencyTrackingManager?.recordApplicationTimestamp(
            direction = PacketDirection.SEND,
            rawMessage = message.rawMessage,
            captureTimeMicros = message.captureTimeMicros,
        )
    }

    /**
     * Record an incoming message for latency tracking
     */
    private fun recordIncomingForLatency(message: FixMessage) {
        latencyTrackingManager?.recordApplicationTimestamp(
            direction = PacketDirection.RECEIVE,
            rawMessage = message.rawMessage,
            captureTimeMicros = message.captureTimeMicros,
        )
    }

    /**
     * Clear latency statistics
     */
    fun clearLatencyStatistics() {
        latencyTrackingManager?.trackingService?.clearStatistics()
        logger.info("Cleared latency statistics for session: {}", title)
    }

    /**
     * Get current timestamp source being used
     */
    fun getLatencyTimestampSource(): TimestampSource = latencyTrackingManager?.getCurrentTimestampSource() ?: TimestampSource.APPLICATION

    fun destroy() {
        disconnect()
        disableLatencyTracking()
        isActive = false
        messageQueue.clear()
    }
}
