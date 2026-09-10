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
    /**
     * **How many messages the plan asks for**, which a reactive shape cannot answer on its own.
     *
     * A burst counts its own and a rate multiplies two numbers it is holding, so for those the default
     * is the whole answer. A reactive phase issues one message per message its trigger issued, and only
     * `LoadPlan.requested` has that, so the plan hands it over rather than the shape being asked.
     */
    private val requested: Long = shape.ownCount ?: 0L,
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
        /**
         * Messages the plan asked for that were never built, because a capture they needed was not there.
         *
         * Its own counter and not folded into [issueFailures], because the engine never saw them: counting
         * them as refusals would put a hole a set's earlier phase left into the tool's own block, and the
         * tool block is the one number a load report exists to keep honest.
         */
        val unaddressable: Long = 0,
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
     * **What became of one message the pacer asked for.**
     *
     * Three answers and not two, because "the engine refused it" and "it could never be built" are
     * different facts about different things, and only the first is the tool's own doing.
     */
    enum class Issued {
        HANDED,
        REFUSED,
        UNADDRESSABLE,
    }

    /**
     * **One message a reactive phase is handed**, on the lane it belongs to, already rendered.
     *
     * A reactive pacer chooses neither the index nor the moment, because its trigger's replies do. So it
     * is handed finished messages rather than asked to fetch one by index, and the lane comes with the
     * message because the index alone no longer says which lane it is: see [Reactive].
     */
    data class Ready(
        val laneIndex: Int,
        val rendered: CompiledTemplate.Rendered,
        /**
         * **False for an index no trigger ever released**, which is counted and never dated.
         *
         * Those all arrive together, when the trigger says nothing more is coming, and for a reactive
         * phase that is after its trigger's whole settle window. Dating them would stretch the
         * per-second histogram from the last real send to that close, and every idle second in between
         * would be reported as a second the cap was starved in. They are messages that never happened,
         * so they move no clock. See [Tally.count].
         */
        val fired: Boolean = true,
    )

    /**
     * **What a reactive phase issues from and to.**
     *
     * [run]'s `issue` cannot serve. It is handed the index the pacer chose, and a reactive pacer chooses
     * none: its messages arrive already rendered, in the order their triggers landed and their lanes
     * finished with them. [RenderOnTrigger] is what fills this from the other side.
     */
    interface Reactive {
        /** The next message, or null once nothing more is coming. Blocks until one or the other. */
        fun next(): Ready?

        /** Hands one to the engine, as `issue` does for a paced phase, and says what became of it. */
        fun hand(ready: Ready): Issued
    }

    /**
     * Issues the whole plan. [issue] is handed the lane index and the 1-based message index, renders and
     * sends, and answers whether the engine accepted the message.
     *
     * [reactive] is the other half of that job, for a triggered shape, which is released by its trigger's
     * replies rather than by a schedule and so is handed messages instead of asked for them.
     */
    fun run(
        issue: (laneIndex: Int, messageIndex: Int) -> Issued,
        cancelled: () -> Boolean,
        reactive: Reactive? = null,
    ): IssueStats =
        when (shape) {
            is LoadShape.Burst -> burst(shape.count, issue, cancelled)
            is LoadShape.Rate -> rate(shape, issue, cancelled)
            is LoadShape.Triggered ->
                triggered(
                    shape,
                    requireNotNull(reactive) {
                        "a reactive phase is released by its trigger's replies, and nothing was handed over " +
                            "to read them from"
                    },
                    cancelled,
                )
        }

    private fun burst(count: Int, issue: (Int, Int) -> Issued, cancelled: () -> Boolean): IssueStats {
        val tally = Tally(count.toLong())
        for (i in 1..count) {
            if (cancelled()) return tally.finish(stopped = true, perSecond = 0)
            tally.record(issue((i - 1) % lanes, i), clock.nanoTime(), lagNanos = 0)
        }
        return tally.finish(stopped = false, perSecond = 0)
    }

    private fun rate(shape: LoadShape.Rate, issue: (Int, Int) -> Issued, cancelled: () -> Boolean): IssueStats {
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

    /**
     * **A reactive phase: one message per trigger, in the order the triggers landed.**
     *
     * There is no schedule, so there is no lag and there is no shortfall, and [Tally.finish] is asked for
     * no rate at all, which is what keeps `shortfalls` empty for a shape that could never be behind one.
     *
     * [LoadShape.Triggered.cap] is a ceiling and never a target. A token falls due every `1/cap` seconds
     * and a message is released at the later of now and that token, so a phase whose triggers arrive
     * more slowly than the cap allows simply sits under it. One series of tokens covers every lane,
     * which one issuing thread gives free: the cap is a number about the phase and not about a lane.
     *
     * The wait is [Clock.awaitUntil], the same one a rate holds its schedule with, so a test can stall
     * it and read back exactly what a cap released and when.
     */
    private fun triggered(shape: LoadShape.Triggered, reactive: Reactive, cancelled: () -> Boolean): IssueStats {
        val tally = Tally(requested)
        val interval = shape.cap?.takeIf { it > 0 }?.let { NANOS_PER_SECOND / it }
        var nextToken = Long.MIN_VALUE
        while (true) {
            if (cancelled()) return tally.finish(stopped = true, perSecond = 0)
            val ready = reactive.next() ?: break
            if (interval != null && ready.fired) {
                val at = maxOf(clock.nanoTime(), nextToken)
                clock.awaitUntil(at)
                nextToken = at + interval
            }
            val issued = reactive.hand(ready)
            // An index nothing ever fired is counted where it belongs and dated nowhere: see Ready.fired.
            if (ready.fired) tally.record(issued, clock.nanoTime(), lagNanos = 0) else tally.count(issued)
        }
        return tally.finish(stopped = cancelled(), perSecond = 0)
    }

    /** The running counts, and the per-second histogram, kept off the hot path's allocations. */
    private class Tally(
        private val requested: Long,
    ) {
        private var handed = 0L
        private var failed = 0L
        private var unaddressable = 0L
        private var first: Long? = null
        private var last: Long? = null
        private var maxLag = 0L
        private var perSecond = IntArray(INITIAL_SECONDS)

        /**
         * **A message the plan asked for, counted and nothing else.**
         *
         * What a reactive phase does with an index no trigger ever released. [record] would date it as
         * well, and those all arrive at once when the trigger's window closes, so the histogram would be
         * sized through that close and every idle second since the last real send would come back as a
         * second the cap was starved in. See [Ready.fired].
         */
        fun count(issued: Issued) {
            when (issued) {
                Issued.HANDED -> handed++
                Issued.REFUSED -> failed++
                Issued.UNADDRESSABLE -> unaddressable++
            }
        }

        fun record(issued: Issued, nowNanos: Long, lagNanos: Long) {
            count(issued)
            if (first == null) first = nowNanos
            last = nowNanos
            if (lagNanos > maxLag) maxLag = lagNanos
            val second = ((nowNanos - (first ?: nowNanos)) / NANOS_PER_SECOND).toInt()
            if (second >= perSecond.size) perSecond = perSecond.copyOf(maxOf(second + 1, perSecond.size * 2))
            // A slot nothing could be built for is a second the rate genuinely fell short in, so it is
            // left out of the histogram the shortfalls are read from rather than counted as on schedule.
            if (issued != Issued.UNADDRESSABLE) perSecond[second]++
        }

        fun finish(stopped: Boolean, perSecond: Int): IssueStats {
            val seconds = last?.let { l -> ((l - (first ?: l)) / NANOS_PER_SECOND).toInt() + 1 } ?: 0
            val histogram = this.perSecond.copyOf(seconds)
            return IssueStats(
                requested = requested,
                handedToEngine = handed,
                issueFailures = failed,
                unaddressable = unaddressable,
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
