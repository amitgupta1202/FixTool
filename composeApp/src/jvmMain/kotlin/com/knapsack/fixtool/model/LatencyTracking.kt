package com.knapsack.fixtool.model

import kotlinx.serialization.Serializable

/**
 * **What a reply is paired with its request by**: one FIX tag, or the Logon handshake.
 *
 * A value keyed by its tag, not an enum. It used to be an enum of six, and the Latency settings page
 * offered every field in the dictionary while the tracker silently ignored any tag that was not one of
 * the six — a venue's own quote reference could be added to the list and would never pair. Now any tag
 * in the list pairs, and one the built-ins do not name is named from the loaded dictionary, or by its
 * number when the dictionary does not know it either.
 *
 * Equality is by [tag] alone, so the panel's per-type statistics have one bucket per tag whatever the
 * name resolved to.
 */
class CorrelationIdType(
    val tag: Int,
    val displayName: String,
) {
    override fun equals(other: Any?): Boolean = other is CorrelationIdType && other.tag == tag

    override fun hashCode(): Int = tag

    override fun toString(): String = "$displayName($tag)"

    companion object {
        /** Not a real FIX tag: the Logon has no correlation field, so it is paired per session under this marker. */
        const val LOGON_TAG = -35

        /** Session-level logon correlation (35=A → 35=A). */
        val LOGON = CorrelationIdType(LOGON_TAG, "Logon")

        val CL_ORD_ID = CorrelationIdType(11, "ClOrdID")
        val QUOTE_REQ_ID = CorrelationIdType(131, "QuoteReqID")
        val QUOTE_ID = CorrelationIdType(117, "QuoteID")
        val MD_REQ_ID = CorrelationIdType(262, "MDReqID")
        val ORDER_ID = CorrelationIdType(37, "OrderID")
        val EXEC_ID = CorrelationIdType(17, "ExecID")

        /**
         * TestRequest → Heartbeat: FIX's own ping. The venue's session engine must echo the id, before any
         * order book or risk check is involved and with nothing placed — a probe of the network and the
         * engine alone, and the floor under every order round trip.
         */
        val TEST_REQ_ID = CorrelationIdType(112, "TestReqID")

        /** The built-in tags, in the order the default list tries them. */
        val builtIn: List<CorrelationIdType> = listOf(CL_ORD_ID, QUOTE_REQ_ID, QUOTE_ID, MD_REQ_ID, ORDER_ID, EXEC_ID, TEST_REQ_ID)

        /**
         * The type for [tag]: a built-in by its standard name, otherwise named by [nameOf] (the dictionary,
         * typically), otherwise by its number.
         */
        fun fromTag(
            tag: Int,
            nameOf: (Int) -> String? = { null },
        ): CorrelationIdType = builtIn.firstOrNull { it.tag == tag } ?: CorrelationIdType(tag, nameOf(tag) ?: "Tag $tag")

        /** The built-in tag numbers, which is what a fresh install correlates on. */
        fun allTags(): List<Int> = builtIn.map { it.tag }

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
