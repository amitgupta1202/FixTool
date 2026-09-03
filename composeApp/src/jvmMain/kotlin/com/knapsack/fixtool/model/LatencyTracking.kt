package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * Correlation ID types for matching request/response FIX messages
 */
enum class CorrelationIdType(
    val tag: Int,
    val displayName: String,
) {
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

/** Which way a message crossed FixTool's socket. */
enum class WireDirection {
    SEND,
    RECEIVE,
}

/**
 * One message at the socket boundary, and when it crossed.
 *
 * Stamped by `SocketStampFilter`: a SEND when the kernel has accepted the last byte of the message,
 * a RECEIVE when the message has been framed out of the (decrypted) stream and before the FIX engine
 * queues, parses or validates it. The FIX engine's own work is on neither side of the number.
 *
 * [rawFixMessage] is the pipe-delimited form, the same form the grid keys its rows by, so a round trip
 * can be looked up from the outbound row that started it.
 */
data class MessageStamp(
    val timestampMicros: Long,
    val direction: WireDirection,
    val correlationId: String,
    val correlationType: CorrelationIdType,
    val messageType: String,
    val rawFixMessage: String,
)

/**
 * A correlated pair of send/receive messages with calculated round-trip time
 */
data class CorrelatedMessagePair(
    val sendTimestamp: MessageStamp,
    val receiveTimestamp: MessageStamp,
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
    /**
     * An ArrayDeque, not an ArrayList: eviction is `removeFirst`, which shifts every remaining element
     * on an ArrayList and is O(1) here. At the default 10,000-sample ceiling that shift was paid on
     * every correlated round trip.
     */
    private val samples = ArrayDeque<Long>()
    private var sum = 0.0
    private var sumSquares = 0.0

    /**
     * The last computed statistics, or null when a sample has arrived since.
     *
     * [getStatistics] has to sort the samples to get percentiles, which is O(n log n) and allocates a
     * full copy — and it was being called for every accumulator on every single sample, because the
     * publish path recomputed everything per round trip. Caching does not make the sort cheaper; it
     * makes it happen once per *read* rather than once per write, which is the right axis: nobody can
     * read percentiles ten thousand times a second and the panel does not try.
     */
    private var cached: LatencyStatistics? = null

    @Synchronized
    fun addSample(latencyMicros: Long) {
        // Remove oldest sample if at capacity
        if (samples.size >= maxSamples) {
            val removed = samples.removeFirst()
            sum -= removed
            sumSquares -= removed.toDouble() * removed.toDouble()
        }

        samples.addLast(latencyMicros)
        sum += latencyMicros
        sumSquares += latencyMicros.toDouble() * latencyMicros.toDouble()
        cached = null
    }

    @Synchronized
    fun getStatistics(): LatencyStatistics {
        cached?.let { return it }
        if (samples.isEmpty()) {
            return LatencyStatistics.empty()
        }

        val sorted = samples.sorted()
        val count = sorted.size.toLong()
        val mean = sum / count
        val variance = if (count > 1) (sumSquares / count) - (mean * mean) else 0.0
        val stdDev = kotlin.math.sqrt(variance.coerceAtLeast(0.0))

        return statisticsOf(sorted, count, mean, stdDev).also { cached = it }
    }

    private fun statisticsOf(
        sorted: List<Long>,
        count: Long,
        mean: Double,
        stdDev: Double,
    ): LatencyStatistics =
        LatencyStatistics(
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

    @Synchronized
    fun clear() {
        samples.clear()
        sum = 0.0
        sumSquares = 0.0
        cached = null
    }

    @Synchronized
    fun getSampleCount(): Int = samples.size
}
