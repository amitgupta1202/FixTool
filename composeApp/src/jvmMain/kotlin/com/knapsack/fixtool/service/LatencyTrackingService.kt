package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.CorrelatedMessagePair
import com.knapsack.fixtool.model.CorrelationIdType
import com.knapsack.fixtool.model.LatencySeverity
import com.knapsack.fixtool.model.LatencyStatistics
import com.knapsack.fixtool.model.LatencyStatsAccumulator
import com.knapsack.fixtool.model.MessageStamp
import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import com.knapsack.fixtool.util.Coalescer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * **Turns socket stamps into round trips.**
 *
 * Fed one call to [record] per message on the wire, from the MINA I/O thread via [SocketStampFilter].
 * It reads the message type and the first correlation tag it knows, pairs a RECEIVE with the SEND that
 * carried the same id, and keeps the history and statistics the latency panel and the grid's latency
 * column read.
 *
 * The Logon is the one message paired without a tag: an initiator's outgoing `35=A` and the venue's
 * `35=A` back are the session's first round trip, and worth a number.
 *
 * [correlationTags] is tried in order and the first tag a message carries decides its pairing. Any tag
 * pairs, not only the built-ins; [nameOf] names the others for the panel, and is asked once per tag.
 */
class LatencyTrackingService(
    private val correlationTags: List<Int> = CorrelationIdType.allTags(),
    private val nameOf: (Int) -> String? = { null },
    private val historySize: Int = 10000,
    private val warningThresholdMicros: Long = 100_000L, // 100ms
    private val criticalThresholdMicros: Long = 500_000L, // 500ms
    private val stalePendingTimeoutMicros: Long = 60_000_000L, // 60 seconds - pending sends older than this are removed
) {
    private val logger = LoggerFactory.getLogger(LatencyTrackingService::class.java)

    private companion object {
        /**
         * How many of the newest pairs [recentPairs] publishes.
         *
         * Generous next to the fifty the panel draws, so the window is never the reason something is
         * missing, and small enough that publishing it per round trip is not a cost worth measuring.
         */
        const val PUBLISHED_WINDOW = 256

        /**
         * Synthetic correlation ID for logon messages.
         * Since logon doesn't have an explicit correlation tag, we use a fixed ID.
         */
        const val LOGON_CORRELATION_ID = "SESSION_LOGON"

        /** The FIX field delimiter as sent. */
        val SOH: Char = Char(1)
    }

    // Pending sends awaiting response (keyed by tag:correlationId)
    private val pendingSends = ConcurrentHashMap<String, MessageStamp>()

    /**
     * The type for each configured tag, resolved on first use rather than at construction: the tracker is
     * created before the session connects, and the dictionary [nameOf] reads may not be loaded until then.
     */
    private val typesByTag = ConcurrentHashMap<Int, CorrelationIdType>()

    private fun typeOf(tag: Int): CorrelationIdType = typesByTag.computeIfAbsent(tag) { CorrelationIdType.fromTag(it, nameOf) }

    // Track last cleanup time to avoid cleaning up on every message
    @Volatile
    private var lastCleanupTimeMicros: Long = System.currentTimeMillis() * 1000
    private val cleanupIntervalMicros: Long = 10_000_000L // Clean up every 10 seconds

    /**
     * Recent correlated pairs, most recent first.
     *
     * An ArrayDeque, not an ArrayList, and for the same reason `FixMessageSession.retained` is one:
     * inserting at the front of an ArrayList shifts every element behind it, so with the default
     * 10,000-pair ceiling every round trip moved ten thousand references before doing anything else.
     * A deque does it in O(1) at both ends. See `LatencyHistoryBenchmarkTest`.
     */
    private val correlatedPairs = ArrayDeque<CorrelatedMessagePair>()
    private val pairsLock = Any()

    // Statistics accumulators per correlation type
    private val statisticsAccumulators = ConcurrentHashMap<CorrelationIdType, LatencyStatsAccumulator>()

    /**
     * Latency by raw message, for the grid's latency column.
     *
     * Bounded by [correlatedPairs], which it did not used to be: entries were added per round trip and
     * removed only by an explicit `clearStatistics`, so a long soak run accumulated one full raw FIX
     * message — as a map KEY — per correlated message, forever. A message that has rolled out of the
     * history has also rolled out of the grid, so its entry could never be read again either.
     */
    private val latencyByMessage = ConcurrentHashMap<String, Long>()

    // Observable state for UI
    private val _recentPairs = MutableStateFlow<List<CorrelatedMessagePair>>(emptyList())
    val recentPairs: StateFlow<List<CorrelatedMessagePair>> = _recentPairs.asStateFlow()

    private val _statistics = MutableStateFlow<Map<CorrelationIdType, LatencyStatistics>>(emptyMap())
    val statistics: StateFlow<Map<CorrelationIdType, LatencyStatistics>> = _statistics.asStateFlow()

    private val _aggregateStatistics = MutableStateFlow(LatencyStatistics.empty())
    val aggregateStatistics: StateFlow<LatencyStatistics> = _aggregateStatistics.asStateFlow()

    // Aggregate accumulator for all correlation types
    private val aggregateAccumulator = LatencyStatsAccumulator(historySize)

    init {
        // The built-ins and the Logon up front; a configured tag outside them gets its accumulator on its
        // first round trip, in addCorrelatedPair.
        (CorrelationIdType.builtIn + CorrelationIdType.LOGON).forEach { type ->
            statisticsAccumulators[type] = LatencyStatsAccumulator(historySize)
        }
    }

    /**
     * One message at the socket, as [SocketStampFilter] saw it.
     *
     * Runs on the I/O thread, so it does the least it can: a scan for tag 35, a scan for the first
     * correlation tag present, and a hand-off to [recordStamp]. A message carrying none of the tags —
     * a heartbeat, a test request, a reject with no id — costs the scans and nothing more.
     *
     * The raw message is kept in the grid's pipe-delimited form, so the grid can ask for a round trip
     * by the row that started it.
     */
    fun record(direction: WireDirection, wire: String, micros: Long) {
        val messageType = tagValue(wire, 35) ?: return

        if (CorrelationIdType.isLogonMessage(messageType)) {
            recordStamp(
                MessageStamp(
                    timestampMicros = micros,
                    direction = direction,
                    correlationId = LOGON_CORRELATION_ID,
                    correlationType = CorrelationIdType.LOGON,
                    messageType = messageType,
                    rawFixMessage = wire.toRawFixMessage(),
                ),
            )
            return
        }

        for (tag in correlationTags) {
            val correlationId = tagValue(wire, tag) ?: continue
            recordStamp(
                MessageStamp(
                    timestampMicros = micros,
                    direction = direction,
                    correlationId = correlationId,
                    correlationType = typeOf(tag),
                    messageType = messageType,
                    rawFixMessage = wire.toRawFixMessage(),
                ),
            )
            return // Use first found correlation ID
        }
    }

    /**
     * Record one stamped message. Handles correlation of send/receive pairs.
     */
    fun recordStamp(stamp: MessageStamp) {
        val key = "${stamp.correlationType.tag}:${stamp.correlationId}"

        when (stamp.direction) {
            WireDirection.SEND -> {
                // Store send timestamp for later correlation
                pendingSends[key] = stamp
                logger.debug(
                    "Recorded SEND: {} {} = {} at {}μs",
                    stamp.messageType,
                    stamp.correlationType.displayName,
                    stamp.correlationId,
                    stamp.timestampMicros,
                )
            }

            WireDirection.RECEIVE -> {
                // Try to correlate with pending send
                val sendStamp = pendingSends.remove(key)
                if (sendStamp != null) {
                    val rtt = stamp.timestampMicros - sendStamp.timestampMicros
                    if (rtt >= 0) {
                        val pair = CorrelatedMessagePair(sendStamp, stamp, rtt)
                        addCorrelatedPair(pair)

                        logger.debug(
                            "Correlated {} {} = {}: RTT = {}μs",
                            stamp.messageType,
                            stamp.correlationType.displayName,
                            stamp.correlationId,
                            rtt,
                        )
                    } else {
                        logger.warn(
                            "Negative RTT detected for {} = {}: {}μs (clock skew?)",
                            stamp.correlationType.displayName,
                            stamp.correlationId,
                            rtt,
                        )
                    }
                } else {
                    logger.debug(
                        "Received {} {} = {} with no matching send",
                        stamp.messageType,
                        stamp.correlationType.displayName,
                        stamp.correlationId,
                    )
                }
            }
        }

        // Periodically clean up stale pending sends to prevent memory leaks
        maybeCleanupStalePendingSends(stamp.timestampMicros)
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
            correlatedPairs.addFirst(pair)

            // Trim to history size, and take the evicted pair's lookup entry with it — the index exists
            // to answer for messages the grid can still show, and this one is past the end of the history.
            while (correlatedPairs.size > historySize) {
                val dropped = correlatedPairs.removeLast()
                latencyByMessage.remove(dropped.sendTimestamp.rawFixMessage)
            }

            // Only the newest window, not the whole history. The panel draws `.take(50)` of this, and
            // copying ten thousand references per round trip to render fifty is the cost this publish
            // used to be. Anything wanting more asks getRecentPairs, which reads the history itself.
            _recentPairs.value =
                ArrayList<CorrelatedMessagePair>(minOf(PUBLISHED_WINDOW, correlatedPairs.size)).apply {
                    for (existing in correlatedPairs) {
                        if (size == PUBLISHED_WINDOW) break
                        add(existing)
                    }
                }
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
     * Publishes the statistics, at most ten times a second.
     *
     * Recomputing them means sorting every accumulator's samples for the percentiles — O(n log n) with
     * a full copy, at a default ceiling of 10,000 samples — and it was being done on every correlated
     * round trip, for a panel showing a handful of numbers a person reads a few times a second. The
     * [Coalescer] paces it and guarantees the trailing run, so the last round trip before traffic stops
     * still reaches the panel rather than leaving it a few samples behind forever.
     */
    private fun updateStatistics() {
        statisticsPublisher.request()
    }

    private val statisticsPublisher =
        Coalescer {
            _statistics.value =
                statisticsAccumulators.mapValues { (_, accumulator) ->
                    accumulator.getStatistics()
                }
            _aggregateStatistics.value = aggregateAccumulator.getStatistics()
        }

    /** Publishes anything outstanding at once — for a synchronous reader, and for [clearStatistics]. */
    fun flushStatistics() {
        statisticsPublisher.flush()
    }

    /**
     * Get latency for a specific raw message (for grid display)
     */
    fun getLatencyForMessage(rawMessage: String): Long? = latencyByMessage[rawMessage]

    /** How many messages the lookup index holds. Bounded by the history — see [latencyByMessage]. */
    fun latencyIndexSize(): Int = latencyByMessage.size

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
    fun getStatistics(type: CorrelationIdType): LatencyStatistics =
        statisticsAccumulators[type]?.getStatistics() ?: LatencyStatistics.empty()

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
     * Note: Cleanup also happens automatically during recordStamp() calls.
     */
    fun cleanupStalePendingSends() {
        val now = System.currentTimeMillis() * 1000
        maybeCleanupStalePendingSends(now)
        // Force cleanup by resetting last cleanup time
        lastCleanupTimeMicros = 0
        maybeCleanupStalePendingSends(now)
    }

    /**
     * The value of [tag] in [message], or null when absent or empty.
     *
     * A scan, not a regex: this runs on the I/O thread for every message on the wire, and the regex it
     * replaces was compiled afresh on each call. Accepts SOH or pipe as the delimiter so the same code
     * reads the bytes as sent and the form the tool shows.
     */
    private fun tagValue(message: String, tag: Int): String? = WireTags.tagValue(message, tag)
}
