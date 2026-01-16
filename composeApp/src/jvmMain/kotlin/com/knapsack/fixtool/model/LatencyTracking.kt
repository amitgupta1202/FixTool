package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * Correlation ID types for matching request/response FIX messages
 */
enum class CorrelationIdType(val tag: Int, val displayName: String) {
    /**
     * Session-level logon correlation (35=A → 35=A).
     * Uses synthetic correlation ID since logon has no explicit correlation tag.
     * Tag -35 is a special marker (not a real FIX tag).
     */
    LOGON(-35, "Logon"),

    CL_ORD_ID(11, "ClOrdID"),
    QUOTE_REQ_ID(131, "QuoteReqID"),
    QUOTE_ID(117, "QuoteID"),
    MD_REQ_ID(262, "MDReqID"),
    ORDER_ID(37, "OrderID"),
    EXEC_ID(17, "ExecID"),
    ;

    companion object {
        /**
         * Get correlation type from FIX tag number
         */
        fun fromTag(tag: Int): CorrelationIdType? = entries.find { it.tag == tag }

        /**
         * Get all available tag numbers (excludes special types like LOGON)
         */
        fun allTags(): List<Int> = entries.filter { it.tag > 0 }.map { it.tag }

        /**
         * Check if a message type is a logon (35=A)
         */
        fun isLogonMessage(msgType: String): Boolean = msgType == "A"
    }
}

/**
 * Direction of network traffic
 */
enum class PacketDirection {
    SEND,
    RECEIVE,
}

/**
 * Timestamp source indicating the accuracy level
 */
enum class TimestampSource(val displayName: String, val accuracyDescription: String) {
    PACKET("Packet", "~10μs"),
    APPLICATION("App-level", "~100μs"),
}

/**
 * A captured packet timestamp with extracted FIX message data
 */
data class PacketTimestamp(
    val timestampMicros: Long,
    val direction: PacketDirection,
    val correlationId: String,
    val correlationType: CorrelationIdType,
    val messageType: String,
    val rawFixMessage: String,
    val source: TimestampSource = TimestampSource.PACKET,
)

/**
 * A correlated pair of send/receive messages with calculated round-trip time
 */
data class CorrelatedMessagePair(
    val sendTimestamp: PacketTimestamp,
    val receiveTimestamp: PacketTimestamp,
    val roundTripMicros: Long,
) {
    /**
     * Format the round-trip time for display
     */
    fun formatRoundTripTime(): String =
        when {
            roundTripMicros < 1000 -> "${roundTripMicros}μs"
            roundTripMicros < 1_000_000 -> String.format("%.2fms", roundTripMicros / 1000.0)
            else -> String.format("%.2fs", roundTripMicros / 1_000_000.0)
        }

    /**
     * Get latency severity level based on thresholds
     */
    fun getSeverity(
        warningThresholdMicros: Long,
        criticalThresholdMicros: Long,
    ): LatencySeverity =
        when {
            roundTripMicros >= criticalThresholdMicros -> LatencySeverity.CRITICAL
            roundTripMicros >= warningThresholdMicros -> LatencySeverity.WARNING
            else -> LatencySeverity.NORMAL
        }
}

/**
 * Severity level for latency display
 */
enum class LatencySeverity {
    NORMAL,
    WARNING,
    CRITICAL,
}

/**
 * Latency statistics for a set of correlated message pairs
 */
@Serializable
data class LatencyStatistics(
    val sampleCount: Long = 0,
    val minMicros: Long = 0,
    val maxMicros: Long = 0,
    val meanMicros: Double = 0.0,
    val medianMicros: Long = 0,
    val p90Micros: Long = 0,
    val p95Micros: Long = 0,
    val p99Micros: Long = 0,
    val stdDevMicros: Double = 0.0,
) {
    companion object {
        fun empty(): LatencyStatistics = LatencyStatistics()
    }

    /**
     * Format a microsecond value for display
     */
    fun formatMicros(micros: Long): String =
        when {
            micros < 1000 -> "${micros}μs"
            micros < 1_000_000 -> String.format("%.2fms", micros / 1000.0)
            else -> String.format("%.2fs", micros / 1_000_000.0)
        }

    fun formatMicros(micros: Double): String = formatMicros(micros.toLong())
}

/**
 * Accumulator for computing latency statistics incrementally
 */
class LatencyStatsAccumulator(
    private val maxSamples: Int = 10000,
) {
    private val samples = mutableListOf<Long>()
    private var sum = 0.0
    private var sumSquares = 0.0

    @Synchronized
    fun addSample(latencyMicros: Long) {
        // Remove oldest sample if at capacity
        if (samples.size >= maxSamples) {
            val removed = samples.removeFirst()
            sum -= removed
            sumSquares -= removed.toDouble() * removed.toDouble()
        }

        samples.add(latencyMicros)
        sum += latencyMicros
        sumSquares += latencyMicros.toDouble() * latencyMicros.toDouble()
    }

    @Synchronized
    fun getStatistics(): LatencyStatistics {
        if (samples.isEmpty()) {
            return LatencyStatistics.empty()
        }

        val sorted = samples.sorted()
        val count = sorted.size.toLong()
        val mean = sum / count
        val variance = if (count > 1) (sumSquares / count) - (mean * mean) else 0.0
        val stdDev = kotlin.math.sqrt(variance.coerceAtLeast(0.0))

        return LatencyStatistics(
            sampleCount = count,
            minMicros = sorted.first(),
            maxMicros = sorted.last(),
            meanMicros = mean,
            medianMicros = sorted[sorted.size / 2],
            p90Micros = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)],
            p95Micros = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)],
            p99Micros = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)],
            stdDevMicros = stdDev,
        )
    }

    @Synchronized
    fun clear() {
        samples.clear()
        sum = 0.0
        sumSquares = 0.0
    }

    @Synchronized
    fun getSampleCount(): Int = samples.size
}

/**
 * Network interface information for packet capture configuration
 */
data class NetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val addresses: List<String>,
    val isLoopback: Boolean,
) {
    /**
     * User-friendly display string
     */
    fun toDisplayString(): String {
        val addrs = addresses.take(2).joinToString(", ")
        return if (addrs.isNotEmpty()) {
            "$displayName ($addrs)"
        } else {
            displayName
        }
    }
}

/**
 * Capture status for UI display
 */
sealed class CaptureStatus {
    data object Stopped : CaptureStatus()
    data class Running(val source: TimestampSource, val interfaceName: String) : CaptureStatus()
    data class Error(val message: String) : CaptureStatus()
    data class Fallback(val reason: String) : CaptureStatus()
}
