package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.CaptureStatus
import com.knapsack.fixtool.model.CorrelatedMessagePair
import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.LatencySeverity
import com.knapsack.fixtool.model.LatencyStatistics
import com.knapsack.fixtool.model.LatencyStatsAccumulator
import com.knapsack.fixtool.model.PacketDirection
import com.knapsack.fixtool.model.PacketTimestamp
import com.knapsack.fixtool.model.TimestampSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for tracking and correlating FIX message latencies.
 *
 * This service:
 * - Records packet timestamps for sent and received messages
 * - Correlates request/response pairs by correlation ID (ClOrdID, QuoteReqID, etc.)
 * - Computes latency statistics (min, max, mean, percentiles)
 * - Provides data for UI display
 */
class LatencyTrackingService(
    private val historySize: Int = 10000,
    private val warningThresholdMicros: Long = 100_000L, // 100ms
    private val criticalThresholdMicros: Long = 500_000L, // 500ms
    private val stalePendingTimeoutMicros: Long = 60_000_000L, // 60 seconds - pending sends older than this are removed
) {
    private val logger = LoggerFactory.getLogger(LatencyTrackingService::class.java)

    // Pending sends awaiting response (keyed by correlationType:correlationId)
    private val pendingSends = ConcurrentHashMap<String, PacketTimestamp>()

    // Track last cleanup time to avoid cleaning up on every packet
    @Volatile
    private var lastCleanupTimeMicros: Long = System.currentTimeMillis() * 1000
    private val cleanupIntervalMicros: Long = 10_000_000L // Clean up every 10 seconds

    // Recent correlated pairs (most recent first)
    private val correlatedPairs = mutableListOf<CorrelatedMessagePair>()
    private val pairsLock = Any()

    // Statistics accumulators per correlation type
    private val statisticsAccumulators = ConcurrentHashMap<CorrelationIdType, LatencyStatsAccumulator>()

    // Latency lookup by raw message (for grid display)
    private val latencyByMessage = ConcurrentHashMap<String, Long>()

    // Observable state for UI
    private val _recentPairs = MutableStateFlow<List<CorrelatedMessagePair>>(emptyList())
    val recentPairs: StateFlow<List<CorrelatedMessagePair>> = _recentPairs.asStateFlow()

    private val _statistics = MutableStateFlow<Map<CorrelationIdType, LatencyStatistics>>(emptyMap())
    val statistics: StateFlow<Map<CorrelationIdType, LatencyStatistics>> = _statistics.asStateFlow()

    private val _aggregateStatistics = MutableStateFlow(LatencyStatistics.empty())
    val aggregateStatistics: StateFlow<LatencyStatistics> = _aggregateStatistics.asStateFlow()

    // Track the timestamp source being used
    private val _timestampSource = MutableStateFlow(TimestampSource.APPLICATION)
    val timestampSource: StateFlow<TimestampSource> = _timestampSource.asStateFlow()

    // Aggregate accumulator for all correlation types
    private val aggregateAccumulator = LatencyStatsAccumulator(historySize)

    init {
        // Initialize accumulators for all correlation types
        CorrelationIdType.entries.forEach { type ->
            statisticsAccumulators[type] = LatencyStatsAccumulator(historySize)
        }
    }

    /**
     * Record a packet timestamp from capture or application-level.
     * Handles correlation of send/receive pairs.
     */
    fun recordPacket(packet: PacketTimestamp) {
        val key = "${packet.correlationType.name}:${packet.correlationId}"

        when (packet.direction) {
            PacketDirection.SEND -> {
                // Store send timestamp for later correlation
                pendingSends[key] = packet
                logger.debug(
                    "Recorded SEND: {} {} = {} at {}μs",
                    packet.messageType,
                    packet.correlationType.displayName,
                    packet.correlationId,
                    packet.timestampMicros,
                )
            }

            PacketDirection.RECEIVE -> {
                // Try to correlate with pending send
                val sendPacket = pendingSends.remove(key)
                if (sendPacket != null) {
                    val rtt = packet.timestampMicros - sendPacket.timestampMicros
                    if (rtt >= 0) {
                        val pair = CorrelatedMessagePair(sendPacket, packet, rtt)
                        addCorrelatedPair(pair)

                        logger.debug(
                            "Correlated {} {} = {}: RTT = {}μs",
                            packet.messageType,
                            packet.correlationType.displayName,
                            packet.correlationId,
                            rtt,
                        )
                    } else {
                        logger.warn(
                            "Negative RTT detected for {} = {}: {}μs (clock skew?)",
                            packet.correlationType.displayName,
                            packet.correlationId,
                            rtt,
                        )
                    }
                } else {
                    logger.debug(
                        "Received {} {} = {} with no matching send",
                        packet.messageType,
                        packet.correlationType.displayName,
                        packet.correlationId,
                    )
                }
            }
        }

        // Periodically clean up stale pending sends to prevent memory leaks
        maybeCleanupStalePendingSends(packet.timestampMicros)

        // Update timestamp source indicator
        _timestampSource.value = packet.source
    }

    /**
     * Clean up stale pending sends periodically to prevent memory leaks.
     * Only runs if enough time has passed since the last cleanup.
     */
    private fun maybeCleanupStalePendingSends(currentTimeMicros: Long) {
        // Check if enough time has passed since last cleanup
        if (currentTimeMicros - lastCleanupTimeMicros < cleanupIntervalMicros) {
            return
        }

        lastCleanupTimeMicros = currentTimeMicros

        // Find and remove stale entries
        val staleKeys =
            pendingSends.entries
                .filter { (_, timestamp) -> currentTimeMicros - timestamp.timestampMicros > stalePendingTimeoutMicros }
                .map { it.key }

        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { key ->
                pendingSends.remove(key)
            }
            logger.debug("Cleaned up {} stale pending sends (no response received within {}s)", staleKeys.size, stalePendingTimeoutMicros / 1_000_000)
        }
    }

    /**
     * Add a correlated message pair and update statistics
     */
    private fun addCorrelatedPair(pair: CorrelatedMessagePair) {
        synchronized(pairsLock) {
            // Add to front (most recent first)
            correlatedPairs.add(0, pair)

            // Trim to history size
            while (correlatedPairs.size > historySize) {
                correlatedPairs.removeLast()
            }

            // Update state flows
            _recentPairs.value = correlatedPairs.toList()
        }

        // Update statistics
        val accumulator =
            statisticsAccumulators.getOrPut(pair.sendTimestamp.correlationType) {
                LatencyStatsAccumulator(historySize)
            }
        accumulator.addSample(pair.roundTripMicros)
        aggregateAccumulator.addSample(pair.roundTripMicros)

        // Store latency by message for grid lookup (only on outbound/send message)
        latencyByMessage[pair.sendTimestamp.rawFixMessage] = pair.roundTripMicros

        // Update statistics state flow
        updateStatistics()
    }

    /**
     * Update statistics state flows
     */
    private fun updateStatistics() {
        _statistics.value =
            statisticsAccumulators.mapValues { (_, accumulator) ->
                accumulator.getStatistics()
            }
        _aggregateStatistics.value = aggregateAccumulator.getStatistics()
    }

    /**
     * Get latency for a specific raw message (for grid display)
     */
    fun getLatencyForMessage(rawMessage: String): Long? = latencyByMessage[rawMessage]

    /**
     * Get latency severity for display coloring
     */
    fun getLatencySeverity(latencyMicros: Long): LatencySeverity =
        when {
            latencyMicros >= criticalThresholdMicros -> LatencySeverity.CRITICAL
            latencyMicros >= warningThresholdMicros -> LatencySeverity.WARNING
            else -> LatencySeverity.NORMAL
        }

    /**
     * Get statistics for a specific correlation type
     */
    fun getStatistics(type: CorrelationIdType): LatencyStatistics = statisticsAccumulators[type]?.getStatistics() ?: LatencyStatistics.empty()

    /**
     * Get recent correlated pairs with optional limit
     */
    fun getRecentPairs(limit: Int = 100): List<CorrelatedMessagePair> {
        synchronized(pairsLock) {
            return correlatedPairs.take(limit)
        }
    }

    /**
     * Get pairs matching a specific correlation type
     */
    fun getPairsByType(
        type: CorrelationIdType,
        limit: Int = 100,
    ): List<CorrelatedMessagePair> {
        synchronized(pairsLock) {
            return correlatedPairs
                .filter { it.sendTimestamp.correlationType == type }
                .take(limit)
        }
    }

    /**
     * Clear all statistics and history
     */
    fun clearStatistics() {
        synchronized(pairsLock) {
            correlatedPairs.clear()
            _recentPairs.value = emptyList()
        }

        statisticsAccumulators.values.forEach { it.clear() }
        aggregateAccumulator.clear()
        latencyByMessage.clear()
        pendingSends.clear()

        updateStatistics()
        logger.info("Cleared latency statistics")
    }

    /**
     * Get count of pending (uncorrelated) sends
     */
    fun getPendingSendCount(): Int = pendingSends.size

    /**
     * Get total number of correlated samples
     */
    fun getTotalSampleCount(): Long = _aggregateStatistics.value.sampleCount

    /**
     * Get warning threshold in microseconds
     */
    fun getWarningThreshold(): Long = warningThresholdMicros

    /**
     * Get critical threshold in microseconds
     */
    fun getCriticalThreshold(): Long = criticalThresholdMicros

    /**
     * Manually trigger cleanup of stale pending sends.
     * Note: Cleanup also happens automatically during recordPacket() calls.
     */
    fun cleanupStalePendingSends() {
        val now = System.currentTimeMillis() * 1000
        maybeCleanupStalePendingSends(now)
        // Force cleanup by resetting last cleanup time
        lastCleanupTimeMicros = 0
        maybeCleanupStalePendingSends(now)
    }
}

/**
 * Manager that coordinates PacketCaptureService and LatencyTrackingService.
 * Handles lifecycle management and graceful fallback.
 */
class LatencyTrackingManager(
    private val correlationTags: List<Int> = CorrelationIdType.allTags(),
    private val historySize: Int = 10000,
    private val warningThresholdMicros: Long = 100_000L,
    private val criticalThresholdMicros: Long = 500_000L,
    private val onError: ((String) -> Unit)? = null,
    private val onFallbackNotification: ((String) -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(LatencyTrackingManager::class.java)

    private var packetCaptureService: PacketCaptureService? = null

    val trackingService =
        LatencyTrackingService(
            historySize = historySize,
            warningThresholdMicros = warningThresholdMicros,
            criticalThresholdMicros = criticalThresholdMicros,
        )

    private val _captureStatus = MutableStateFlow<CaptureStatus>(CaptureStatus.Stopped)
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus.asStateFlow()

    private var fallbackNotificationShown = false

    /**
     * Start latency tracking for a FIX session.
     *
     * @param networkInterface Network interface name (null for auto-detect)
     * @param port FIX server port
     * @return true if using packet-level timestamps, false if using app-level fallback
     */
    fun startTracking(
        networkInterface: String?,
        port: Int,
    ): Boolean {
        // Check packet capture availability first
        val (available, reason) = PacketCaptureService.checkAvailability()

        if (!available) {
            logger.info("Packet capture unavailable: $reason. Using app-level timestamps.")
            _captureStatus.value = CaptureStatus.Fallback(reason ?: "Unknown reason")
            showFallbackNotification(reason)
            return false
        }

        // Try to start packet capture
        packetCaptureService =
            PacketCaptureService(
                correlationTags = correlationTags,
                onError = onError,
            )

        val success =
            packetCaptureService!!.startCapture(
                interfaceName = networkInterface,
                port = port,
                onMessage = { packet -> trackingService.recordPacket(packet) },
            )

        _captureStatus.value = packetCaptureService!!.status.value

        if (!success) {
            val status = packetCaptureService!!.status.value
            val fallbackReason =
                when (status) {
                    is CaptureStatus.Fallback -> status.reason
                    is CaptureStatus.Error -> status.message
                    else -> "Unknown reason"
                }
            showFallbackNotification(fallbackReason)
        }

        return success
    }

    /**
     * Stop latency tracking
     */
    fun stopTracking() {
        packetCaptureService?.stopCapture()
        packetCaptureService = null
        _captureStatus.value = CaptureStatus.Stopped
    }

    /**
     * Record a message using application-level timestamps.
     * Always records as a fallback - useful when:
     * - Packet capture is unavailable (permission issues)
     * - Connection is TLS encrypted (packet payloads unreadable)
     * - Packet capture misses messages for any reason
     *
     * When packet capture is working, it provides more accurate timestamps,
     * but app-level timestamps ensure we always have latency data.
     *
     * @param direction SEND or RECEIVE
     * @param rawMessage The raw FIX message string
     * @param captureTimeMicros Pre-captured timestamp from QuickFIX/J layer (0 = capture now)
     */
    fun recordApplicationTimestamp(
        direction: PacketDirection,
        rawMessage: String,
        captureTimeMicros: Long = 0L,
    ) {
        // Parse message to extract message type
        val messageType = extractTagValue(rawMessage, 35) ?: return

        // Use pre-captured timestamp if provided, otherwise capture now
        val effectiveTimeMicros =
            if (captureTimeMicros > 0) {
                captureTimeMicros
            } else {
                System.currentTimeMillis() * 1000 + (System.nanoTime() % 1_000_000) / 1000
            }

        // Handle Logon (35=A) specially - correlate outgoing with incoming
        if (CorrelationIdType.isLogonMessage(messageType)) {
            val timestamp =
                PacketTimestamp(
                    timestampMicros = effectiveTimeMicros,
                    direction = direction,
                    correlationId = LOGON_CORRELATION_ID,
                    correlationType = CorrelationIdType.LOGON,
                    messageType = messageType,
                    rawFixMessage = rawMessage,
                    source = TimestampSource.APPLICATION,
                )
            trackingService.recordPacket(timestamp)
            return
        }

        // For other messages, use tag-based correlation
        for (tag in correlationTags) {
            val correlationId = extractTagValue(rawMessage, tag)
            if (!correlationId.isNullOrBlank()) {
                val correlationType = CorrelationIdType.fromTag(tag) ?: continue

                val timestamp =
                    PacketTimestamp(
                        timestampMicros = effectiveTimeMicros,
                        direction = direction,
                        correlationId = correlationId,
                        correlationType = correlationType,
                        messageType = messageType,
                        rawFixMessage = rawMessage,
                        source = TimestampSource.APPLICATION,
                    )

                trackingService.recordPacket(timestamp)
                return
            }
        }
    }

    companion object {
        /**
         * Synthetic correlation ID for logon messages.
         * Since logon doesn't have an explicit correlation tag, we use a fixed ID.
         */
        private const val LOGON_CORRELATION_ID = "SESSION_LOGON"
    }

    /**
     * Show fallback notification to user (once per session)
     */
    private fun showFallbackNotification(reason: String?) {
        if (fallbackNotificationShown) {
            return
        }

        fallbackNotificationShown = true

        val message =
            buildString {
                appendLine("Latency Tracking: Using application-level timestamps (~100μs accuracy)")
                appendLine()
                appendLine("For more accurate packet-level timestamps (~10μs), run with elevated privileges:")
                appendLine("• macOS: Add user to 'access_bpf' group or run with sudo")
                appendLine("• Linux: Grant CAP_NET_RAW capability or run as root")
                appendLine("• Windows: Run as Administrator with Npcap installed")
                if (!reason.isNullOrBlank()) {
                    appendLine()
                    appendLine("Reason: $reason")
                }
            }

        onFallbackNotification?.invoke(message)
        logger.info("Fallback notification: $reason")
    }

    /**
     * Get available network interfaces
     */
    fun getAvailableInterfaces() = packetCaptureService?.getAvailableInterfaces() ?: emptyList()

    /**
     * Check if packet capture is currently running
     */
    fun isPacketCaptureRunning(): Boolean = _captureStatus.value is CaptureStatus.Running

    /**
     * Get current timestamp source
     */
    fun getCurrentTimestampSource(): TimestampSource =
        when (_captureStatus.value) {
            is CaptureStatus.Running -> TimestampSource.PACKET
            else -> TimestampSource.APPLICATION
        }

    /**
     * Reset fallback notification flag (for new session)
     */
    fun resetFallbackNotification() {
        fallbackNotificationShown = false
    }

    /**
     * Extract a tag value from a FIX message
     */
    private fun extractTagValue(
        message: String,
        tag: Int,
    ): String? {
        val soh = '\u0001'
        val pattern = "(?:^|[$soh|])$tag=([^$soh|]+)".toRegex()
        return pattern.find(message)?.groupValues?.get(1)
    }
}
