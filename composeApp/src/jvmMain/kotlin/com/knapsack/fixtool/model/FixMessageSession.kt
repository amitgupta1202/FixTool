package com.knapsack.fixtool.model

// Latency tracking model imports are in this package (CaptureStatus, PacketDirection, TimestampSource)
import com.knapsack.fixtool.service.AcceptorStatus
import com.knapsack.fixtool.service.BookView
import com.knapsack.fixtool.service.FixConnectionManager
import com.knapsack.fixtool.service.LatencyTrackingManager
import com.knapsack.fixtool.service.QuickFixService
import com.knapsack.fixtool.service.SessionEndpoint
import com.knapsack.fixtool.service.VenueEvent
import com.knapsack.fixtool.util.NotifyingLogger
import quickfix.SessionID
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

        /** How many refused logons a venue remembers. Enough to see a pattern, not a leak. */
        private const val MAX_REFUSED_LOGONS = 50
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

    /**
     * **Group this session's grid by business exchange** — per session, like [filterVisible], and for
     * the same reason: it is a way of looking at THIS pane. Held app-globally at first, it had two
     * defects in one flaw: grouping toggled every pane at once, and the collapse set below was keyed by
     * conversation *label* — so in a both-sides test, folding `STREAM-A` in the venue pane folded the
     * client pane's same-labelled conversation with it.
     */
    private val _groupByConversation = MutableStateFlow(false)
    val groupByConversation: StateFlow<Boolean> = _groupByConversation.asStateFlow()

    /** Conversations folded shut in THIS pane, by header key (the conversation's label). */
    private val _collapsedConversations = MutableStateFlow<Set<String>>(emptySet())
    val collapsedConversations: StateFlow<Set<String>> = _collapsedConversations.asStateFlow()

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

    /**
     * **The one FIX session this pane speaks to.**
     *
     * Set when this pane's own connection comes up, or injected by whoever created it when the pane
     * is one client of a venue. Every send and every admin action goes through it, so neither this
     * class nor the UI above it has to know which of the two it is looking at.
     */
    private var endpoint: SessionEndpoint? = null

    /**
     * The venue this pane's counterparty is connected *to*, when this pane is one client of many.
     *
     * Its presence is what makes this pane a guest rather than a host: it owns no transport, so
     * "disconnect" means log this one client out and "close" means stop watching — neither may
     * unbind the port, which belongs to the venue and to every other client on it.
     */
    private var venue: QuickFixService? = null

    /** The counterparty this pane is bound to, when it is a venue client. */
    private var venueSessionId: SessionID? = null

    /** Which counterparty of a venue this pane shows, or null if it is not a venue client. */
    val clientSessionId: SessionID? get() = venueSessionId

    /** Is this pane one of [candidate]'s clients? Identity, not configuration: two venues can share a port history. */
    fun isClientOf(candidate: FixMessageSession): Boolean = venue != null && venue === candidate.venueService()

    /** True when this pane is one client of a venue rather than a connection of its own. */
    val isVenueClient: Boolean get() = venue != null

    /** True when this pane *is* a venue: it holds the port, and its counterparties have panes of their own. */
    val isVenue: Boolean get() = _connectionConfig.value?.acceptsAnyClient() == true && !isVenueClient

    /**
     * **Logons this venue turned away**, most recent last.
     *
     * The only record that they happened. QuickFIX/J answers a logon it does not recognise with
     * silence — no Logout, no Reject — so from the client's side a wrong CompID and a wrong port and
     * a closed firewall all look identical, and from this side nothing appeared at all. Kept even
     * though it is not FIX traffic, because it is the answer to the question that traffic cannot
     * answer: *nobody is connecting, and I do not know why*.
     */
    private val _refusedLogons = MutableStateFlow<List<VenueEvent.LogonRefused>>(emptyList())
    val refusedLogons: StateFlow<List<VenueEvent.LogonRefused>> = _refusedLogons.asStateFlow()

    /** Where this session's venue events go. Set by whoever owns the pane, before it connects. */
    var venueEventListener: ((VenueEvent) -> Unit)? = null

    /** This venue's engine, for binding a client's pane to one of its sessions. Null unless it is a venue. */
    fun venueService(): QuickFixService? = quickFixService

    private fun handleVenueEvent(event: VenueEvent) {
        if (event is VenueEvent.LogonRefused) {
            _refusedLogons.value = (_refusedLogons.value + event).takeLast(MAX_REFUSED_LOGONS)
        }
        venueEventListener?.invoke(event)
    }

    /**
     * Binds this pane to one counterparty of [venueService].
     *
     * Everything the session has already said — its Logon above all — arrives during this call, from
     * the buffer the engine kept while the pane was being created.
     */
    fun bindToVenueClient(venueService: QuickFixService, sessionId: SessionID, config: FixConnectionConfig) {
        venue = venueService
        venueSessionId = sessionId
        endpoint = venueService.endpointFor(sessionId)
        _connectionConfig.value = config
        venueService.attachClient(
            sessionId = sessionId,
            onMessage = { message -> addMessage(message) },
            onState = { state -> _connectionState.value = state },
        )
        logger.info("Pane '{}' bound to venue client {}", title, sessionId)
    }
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

    fun toggleGroupByConversation() {
        _groupByConversation.value = !_groupByConversation.value
    }

    fun setGroupByConversation(on: Boolean) {
        _groupByConversation.value = on
    }

    fun toggleConversationCollapsed(key: String) {
        val current = _collapsedConversations.value
        _collapsedConversations.value = if (key in current) current - key else current + key
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
        if (isVenueClient) {
            // This pane's counterparty connected to us; there is nothing here to dial. Reconnecting is
            // the client's move to make, and if it makes it the venue routes it back to this same pane.
            logger.info("Session '{}' is a venue client — nothing to connect", title)
            return
        }
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
                            endpoint = null
                            _connectionState.value = FixConnectionState.ERROR
                        }
                    },
                    onVenueEvent = { event -> handleVenueEvent(event) },
                )
            endpoint = quickFixService?.ownerEndpoint()

            // Create connection manager
            connectionManager = FixConnectionManager(config, quickFixService!!, appSettings, effectiveDictionary)

            // Start the connection
            connectionManager?.start()

            // A venue has no session of its own to log on, so nothing would ever move it off
            // CONNECTING and its tab would read as perpetually half-open. What it *has* done is bind
            // the port, which is the whole of what an acceptor does before anyone arrives — and it
            // stays that way as clients come and go, since no one client's departure closes it.
            if (config.acceptsAnyClient()) _connectionState.value = FixConnectionState.CONNECTED

            logger.info("Connection initiated for session: {}", title)
        } catch (e: Exception) {
            _connectionState.value = FixConnectionState.ERROR
            val errorMsg = "Failed to connect: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
        }
    }

    fun disconnect() {
        if (isVenueClient) {
            // Logs this one counterparty out. The venue keeps listening and every other client stays
            // up — a pane's disconnect button is about the conversation it shows, not the port.
            logger.info("Logging out venue client: {}", title)
            endpoint?.logout()
            return
        }
        scope.launch {
            try {
                // Stop latency tracking
                stopLatencyTracking()

                // Send logout message if currently logged on
                if (_connectionState.value == FixConnectionState.LOGGED_ON) {
                    logger.info("Sending logout before disconnect for session: {}", title)
                    endpoint?.logout()

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
                endpoint = null
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
            endpoint?.send(rawMessage, dictionary)
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
        endpoint?.resetSequenceNumbers(sender, target) ?: false

    fun sequenceNumbers(): Pair<Int, Int>? = endpoint?.sequenceNumbers()

    fun sendTestRequest(testReqId: String): Boolean = endpoint?.sendTestRequest(testReqId) ?: false

    /** Drops this session's queued acceptor auto-responses; returns how many were still waiting. */
    fun stopPendingResponses(): Int = endpoint?.stopPendingResponses() ?: 0

    /** What this session's acceptor is running and mid-way through, or null if it is not an acceptor. */
    fun acceptorStatus(): AcceptorStatus? = quickFixService?.acceptorStatus()

    /**
     * **What this venue is holding for this counterparty.**
     *
     * Two shapes of acceptor answer it from two places, and the difference is where the engine lives:
     * a *venue client pane* borrows its venue's engine and names itself with [clientSessionId], while
     * a single-client acceptor owns its engine and has exactly one counterparty to be about. Null for
     * anything that is not an acceptor at all — a client's own view of the orders it sent is a
     * different feature for a different user, and is out of scope by design.
     */
    fun orderBook(): BookView? =
        venue?.orderBook(venueSessionId)
            ?: quickFixService?.takeIf { isAcceptor }?.orderBook()

    /** Wipes this counterparty's book, recording that it was wiped rather than never filled. */
    fun clearOrderBook(by: String = "manually") {
        venue?.clearOrderBook(venueSessionId, by) ?: quickFixService?.takeIf { isAcceptor }?.clearOrderBook(by = by)
    }

    private val isAcceptor: Boolean
        get() = _connectionConfig.value?.connectionType == FixConnectionConfig.ConnectionType.ACCEPTOR

    /**
     * Applies a ruleset saved since this session connected. Returns how many rules are live, or null
     * if there is no live session to apply them to.
     *
     * [currentConfig] is moved with them, so the config this session reports is the one it is actually
     * running — a session answering with rules it is no longer using is the same lie in a second place.
     */
    fun reloadAcceptorRules(rules: List<AcceptorResponseRule>, latency: AcceptorLatencyConfig): Int? {
        val live = quickFixService?.reloadAcceptorRules(rules, latency) ?: return null
        _connectionConfig.value =
            _connectionConfig.value?.copy(acceptorResponseRules = rules, acceptorLatency = latency)
        return live
    }

    fun sendResendRequest(beginSeqNo: Int, endSeqNo: Int): Boolean =
        endpoint?.sendResendRequest(beginSeqNo, endSeqNo) ?: false

    fun sendSequenceReset(newSeqNo: Int, gapFill: Boolean): Boolean =
        endpoint?.sendSequenceReset(newSeqNo, gapFill) ?: false

    fun forceLogout(reason: String?): Boolean = endpoint?.forceLogout(reason) ?: false

    fun forceDisconnect(reason: String): Boolean = endpoint?.forceDisconnect(reason) ?: false

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
        if (isVenueClient) {
            // Closing a client's pane stops us watching that counterparty; it does not log them out
            // and it certainly does not touch the venue. If the same client speaks again a fresh pane
            // opens for them — better than a live session with nowhere to show what it is saying.
            venueSessionId?.let { venue?.detachClient(it) }
            venue = null
            endpoint = null
        } else {
            disconnect()
        }
        disableLatencyTracking()
        isActive = false
        messageQueue.clear()
    }
}
