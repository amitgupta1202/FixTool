package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadShape
import java.util.concurrent.locks.LockSupport

/**
 * **Decides when each message goes, and records how late it went.**
 *
 * A burst hands message `i` to lane `i mod lanes` the moment the previous one returned. A rate schedules
 * message `i` at `t0 + i / R` and waits for the mark: parked until a few hundred microseconds before it,
 * then spinning to it, because `parkNanos` alone wakes late by more than a whole interval at a thousand a
 * second.
 *
 * **It never skips.** A pacer that held the rate by issuing fewer would put a second unknown into every
 * downstream count. So a message that could not go on time goes as soon as it can, the run catches up, and
 * the report says where it fell behind and by how much: lag is the tool's number and the round trip is the
 * venue's, and they are reported beside each other and never summed.
 */
class Pacer(
    private val shape: LoadShape,
    private val lanes: Int,
    private val clock: Clock = Clock.SYSTEM,
) {
    /** Time, and the wait for a mark. Replaceable so a test can stall it and read the shortfall back. */
    interface Clock {
        fun nanoTime(): Long

        /** Returns at or after [deadlineNanos]. */
        fun awaitUntil(deadlineNanos: Long)

        object SYSTEM : Clock {
            override fun nanoTime(): Long = System.nanoTime()

            override fun awaitUntil(deadlineNanos: Long) {
                while (true) {
                    val remaining = deadlineNanos - System.nanoTime()
                    if (remaining <= 0) return
                    if (remaining > SPIN_NANOS) LockSupport.parkNanos(remaining - SPIN_NANOS) else Thread.onSpinWait()
                }
            }
        }
    }

    /** Consecutive seconds in which the achieved rate fell below the requested one by more than [TOLERANCE]. */
    data class Shortfall(
        val fromSecond: Int,
        val toSecond: Int,
        val minPerSecond: Int,
        /** How many messages short of the schedule the span was, summed over its seconds. */
        val behind: Long,
    )

    data class IssueStats(
        val requested: Long,
        val handedToEngine: Long,
        val issueFailures: Long,
        val firstIssueNanos: Long?,
        val lastIssueNanos: Long?,
        /** Messages handed to the engine in each second from the first issue. */
        val perSecondIssued: IntArray,
        /** The worst distance behind schedule any message went. Zero for a burst, which has no schedule. */
        val maxLagNanos: Long,
        val shortfalls: List<Shortfall>,
        /** True when [run] returned because it was cancelled rather than because it was done. */
        val stopped: Boolean,
    ) {
        val issued: Long get() = handedToEngine + issueFailures
    }

    /**
     * Issues the whole plan. [issue] is handed the lane index and the 1-based message index, renders and
     * sends, and answers whether the engine accepted the message.
     */
    fun run(issue: (laneIndex: Int, messageIndex: Int) -> Boolean, cancelled: () -> Boolean): IssueStats =
        when (shape) {
            is LoadShape.Burst -> burst(shape.count, issue, cancelled)
            is LoadShape.Rate -> rate(shape, issue, cancelled)
        }

    private fun burst(count: Int, issue: (Int, Int) -> Boolean, cancelled: () -> Boolean): IssueStats {
        val tally = Tally(count.toLong())
        for (i in 1..count) {
            if (cancelled()) return tally.finish(stopped = true, perSecond = 0)
            tally.record(issue((i - 1) % lanes, i), clock.nanoTime(), lagNanos = 0)
        }
        return tally.finish(stopped = false, perSecond = 0)
    }

    private fun rate(shape: LoadShape.Rate, issue: (Int, Int) -> Boolean, cancelled: () -> Boolean): IssueStats {
        val total = shape.requested
        val tally = Tally(total)
        val t0 = clock.nanoTime()
        var i = 0L
        while (i < total) {
            if (cancelled()) return tally.finish(stopped = true, perSecond = shape.perSecond)
            val scheduled = t0 + i * NANOS_PER_SECOND / shape.perSecond
            clock.awaitUntil(scheduled)
            val ok = issue((i % lanes).toInt(), (i + 1).toInt())
            val now = clock.nanoTime()
            tally.record(ok, now, lagNanos = (now - scheduled).coerceAtLeast(0))
            i++
        }
        return tally.finish(stopped = false, perSecond = shape.perSecond)
    }

    /** The running counts, and the per-second histogram, kept off the hot path's allocations. */
    private class Tally(
        private val requested: Long,
    ) {
        private var handed = 0L
        private var failed = 0L
        private var first: Long? = null
        private var last: Long? = null
        private var maxLag = 0L
        private var perSecond = IntArray(INITIAL_SECONDS)

        fun record(ok: Boolean, nowNanos: Long, lagNanos: Long) {
            if (ok) handed++ else failed++
            if (first == null) first = nowNanos
            last = nowNanos
            if (lagNanos > maxLag) maxLag = lagNanos
            val second = ((nowNanos - (first ?: nowNanos)) / NANOS_PER_SECOND).toInt()
            if (second >= perSecond.size) perSecond = perSecond.copyOf(maxOf(second + 1, perSecond.size * 2))
            perSecond[second]++
        }

        fun finish(stopped: Boolean, perSecond: Int): IssueStats {
            val seconds = last?.let { l -> ((l - (first ?: l)) / NANOS_PER_SECOND).toInt() + 1 } ?: 0
            val histogram = this.perSecond.copyOf(seconds)
            return IssueStats(
                requested = requested,
                handedToEngine = handed,
                issueFailures = failed,
                firstIssueNanos = first,
                lastIssueNanos = last,
                perSecondIssued = histogram,
                maxLagNanos = maxLag,
                shortfalls = if (perSecond > 0) shortfalls(histogram, perSecond) else emptyList(),
                stopped = stopped,
            )
        }
    }

    companion object {
        /** Two percent under the requested rate is noise, more is a shortfall. Written into the report. */
        const val TOLERANCE = 0.02
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val SPIN_NANOS = 200_000L
        private const val INITIAL_SECONDS = 64

        /**
         * The spans where the achieved rate fell short, over every second but the last, which is partial by
         * construction. Consecutive short seconds are one span, so a nineteen-second stall reads as one line.
         */
        fun shortfalls(perSecondIssued: IntArray, perSecond: Int): List<Shortfall> {
            val floor = Math.floor(perSecond * (1 - TOLERANCE)).toInt()
            val full = perSecondIssued.size - 1
            val spans = mutableListOf<Shortfall>()
            var start = -1
            var min = Int.MAX_VALUE
            var behind = 0L
            for (s in 0 until full) {
                val issued = perSecondIssued[s]
                if (issued < floor) {
                    if (start < 0) start = s
                    min = minOf(min, issued)
                    behind += perSecond - issued
                } else if (start >= 0) {
                    spans += Shortfall(start, s - 1, min, behind)
                    start = -1
                    min = Int.MAX_VALUE
                    behind = 0
                }
            }
            if (start >= 0) spans += Shortfall(start, full - 1, min, behind)
            return spans
        }
    }
}
