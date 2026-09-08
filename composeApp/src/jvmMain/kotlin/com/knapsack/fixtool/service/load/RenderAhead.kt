package com.knapsack.fixtool.service.load

import quickfix.Message
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **One lane's messages, rendered ahead of its own sends.**
 *
 * [LoadRunner] drove every lane from one pacer loop, rendering *and* sending on that thread, round-robin.
 * So lane N's message left systematically later than lane 1's by roughly (N−1) times the per-message
 * render cost — small against a 3ms p50, and systematic per lane, which is the exact shape of a false
 * finding: lane 50 looks slightly worse forever and somebody goes looking for it in the venue.
 *
 * This is why per-lane *latency* waited. Fix the cause, then measure. Per-lane completeness never had the
 * problem and shipped a step earlier.
 *
 * Each lane renders on its own thread into a bounded queue, so the pacer thread does nothing but take a
 * finished message and hand it to the engine. Bounded because an unbounded look-ahead on a 300,000-message
 * burst is the whole run in memory before the first send.
 *
 * The lane's message indices are a fixed arithmetic sequence — message `m` goes to lane `(m − 1) % lanes`
 * in both shapes — so a producer needs no coordination to know what to render next. [next] checks the
 * index it hands back against the one the pacer asked for anyway, and a lane whose stream ever disagrees
 * gives up look-ahead for the rest of the run rather than sending the wrong message.
 */
class RenderAhead(
    private val prototype: CompiledTemplate.LanePrototype,
    /** This lane's first 1-based message index. */
    firstIndex: Int,
    /** The gap between its indices, which is the lane count. */
    private val stride: Int,
    /** How many messages this lane is asked for. */
    private val count: Long,
    name: String,
    depth: Int = DEPTH,
) : AutoCloseable {
    /** A message and the index it was rendered for, so the consumer can check it got what it asked for. */
    private class Rendered(
        val index: Int,
        val message: Message,
    )

    private val queue = ArrayBlockingQueue<Rendered>(depth)

    @Volatile private var stopped = false

    /** Set when the producer thread died, so [next] stops waiting for a message that will never come. */
    @Volatile private var failed = false

    @Volatile private var abandoned = false

    private val thread =
        Thread({ produce(firstIndex) }, name).apply {
            isDaemon = true
            start()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun produce(firstIndex: Int) {
        try {
            var index = firstIndex
            var made = 0L
            while (!stopped && made < count) {
                val rendered = Rendered(index, prototype.render(index))
                while (!stopped && !queue.offer(rendered, POLL_MS, TimeUnit.MILLISECONDS)) Unit
                index += stride
                made++
            }
        } catch (e: Exception) {
            // Rendering throws on the pacer thread instead, where it always did and where the run reports it.
            failed = true
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The message for [messageIndex], or null when this lane has no look-ahead to offer and the caller
     * should render it inline — the producer failed, the run is stopping, or the stream disagreed.
     */
    fun next(messageIndex: Int): Message? {
        if (abandoned) return null
        while (!stopped && !failed) {
            val rendered = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            if (rendered.index == messageIndex) return rendered.message
            // Never send the wrong message. One disagreement and this lane renders inline from here on,
            // which is exactly what it did before this class existed.
            abandoned = true
            return null
        }
        return null
    }

    override fun close() {
        stopped = true
        thread.interrupt()
    }

    companion object {
        /** Deep enough that a render never blocks a send, shallow enough to be nothing on any run's memory. */
        const val DEPTH = 64
        private const val POLL_MS = 20L

        /**
         * One producer per lane, or none at all when there is nothing to render: a template with no
         * per-message field renders the same message every time, and a thread to hand it over is cost
         * with no benefit.
         */
        fun forLanes(prototypes: List<CompiledTemplate.LanePrototype>, requested: Long): List<RenderAhead> {
            val lanes = prototypes.size
            return prototypes.mapIndexed { index, prototype ->
                RenderAhead(
                    prototype = prototype,
                    firstIndex = index + 1,
                    stride = lanes,
                    count = countFor(requested, lanes, index),
                    name = "fixtool-render-ahead-$index",
                )
            }
        }

        /** How many of `1..requested` fall to lane [index] when message `m` goes to lane `(m − 1) % lanes`. */
        fun countFor(requested: Long, lanes: Int, index: Int): Long =
            if (requested <= index) 0 else (requested - index - 1) / lanes + 1
    }
}
