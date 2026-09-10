package com.knapsack.fixtool.service.load

import java.util.BitSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **A reactive phase's messages, rendered as its trigger releases them.**
 *
 * [RenderAhead] renders a lane's stream before anybody asks for it, which it can because a paced phase's
 * indices are a fixed arithmetic sequence known before the first send. A reactive phase has no such
 * sequence: its next index is whichever of its trigger's requests the venue answered next, so nothing can
 * be rendered until a reply says which. What survives from render-ahead is the shape of the thing, one
 * renderer per lane so a lane's message is not queued behind every other lane's rendering, and the bound
 * on how much may be finished and waiting.
 *
 * **Three kinds of thread and one merged queue.** A dispatcher takes indices off the [TriggerBuffer] and
 * drops each into the queue of the lane that owns it. One renderer per lane turns its own indices into
 * messages and puts them into a single output queue of `(lane, message)`. The issuing thread takes from
 * that one queue and does nothing else. It is one queue and not one per lane on purpose: polling N lane
 * queues in turn costs a poll interval per empty lane, and at fifty lanes that is the whole chain-latency
 * figure this feature exists to produce.
 *
 * **The lane is the one a paced phase would have used**, `(i - indexFrom) % lanes`, which is what
 * [Pacer] and [RenderAhead] both mean by a lane. `(i - 1) % lanes` agrees with it only when
 * `(indexFrom - 1) % lanes` is nought, and a reactive phase's `indexFrom` is its trigger's rather than
 * one, so the two part company on the first set that addresses the half another phase left.
 *
 * **What was never fired is a message the phase never sent.** When the buffer closes, every index in the
 * phase's range that no trigger released goes out as [CompiledTemplate.Rendered.Unaddressable], after
 * everything that was released, so the phase reports it as a hole rather than quietly asking for less.
 * They carry no time of their own: see [Pacer.Ready.fired].
 */
@Suppress("LongParameterList")
class RenderOnTrigger(
    private val prototypes: List<CompiledTemplate.LanePrototype>,
    /** Where the indices this phase issues for arrive, and how it learns that no more will. */
    private val buffer: TriggerBuffer,
    /** This phase's first 1-based message index, which for a reactive phase is its trigger's. */
    private val indexFrom: Int,
    /** How many messages the phase is asked for, which for a reactive phase is its trigger's count. */
    private val requested: Long,
    /** What an index nothing fired was missing, in the words the report prints. */
    private val missing: String,
    private val cancelled: () -> Boolean,
    /**
     * **Called on the issuing thread each time [next] waits and nothing came**, which is where a reactive
     * phase spends the whole of its trigger's window.
     *
     * A phase publishes progress from the issue path, and a reactive phase can be minutes between two
     * messages: the live document showed it ISSUING with counts frozen at whatever they were when the
     * last one went, for the length of its trigger's settle window. The wait is here, so the tick is here.
     * On the issuing thread and no other, which is what lets the counters it reads stay unsynchronised.
     */
    private val onIdle: () -> Unit = {},
    /** Which phase of its set this renders for, 1-based, which is only ever in a thread name. */
    phase: Int = 1,
    depth: Int = DEPTH,
) : AutoCloseable {
    private val lanes = prototypes.size

    /**
     * One queue of indices per lane, unbounded because the dispatcher must never wait: it is the only
     * thing draining the trigger buffer, and a dispatcher parked on a full lane queue would stop the
     * lanes that are keeping up as well as the one that is not. Which also makes every offer into one
     * of them succeed, so nothing on the way out of this has to handle a refusal or an interrupt.
     */
    private val laneQueues = List(lanes) { LinkedBlockingQueue<Int>() }

    /** Every lane's finished messages in one place, bounded, which is what the issuing thread reads. */
    private val out = ArrayBlockingQueue<Pacer.Ready>(depth)

    /** Every index a trigger released, so the close can say which were never released at all. */
    private val fired = BitSet()

    @Volatile private var stopped = false

    /**
     * What a renderer threw, re-thrown on the issuing thread.
     *
     * A render error has always surfaced where the phase reports it, which for a paced phase is the
     * inline render the pacer falls back to. A reactive phase has no index to render inline from, so the
     * throw is carried across rather than lost on a thread nobody joins.
     */
    @Volatile private var failure: Throwable? = null

    private val renderers =
        prototypes.mapIndexed { lane, prototype ->
            Thread({ render(lane, prototype) }, "fixtool-render-on-trigger-p$phase-$lane").apply { isDaemon = true }
        }

    private val dispatcher = Thread(::dispatch, "fixtool-trigger-p$phase").apply { isDaemon = true }

    init {
        renderers.forEach { it.start() }
        dispatcher.start()
    }

    /**
     * **The next message to issue, or null once nothing more is coming.** Blocks for one.
     *
     * Polled rather than taken so a stopped set is not waiting on a trigger that will not arrive.
     * Stopping is the polled flag it is everywhere else, and a poll interval is nothing to be late for
     * on a queue with nothing in it: an arriving message wakes this the moment it lands.
     *
     * Every empty poll is also the phase's chance to say where it is. See [onIdle].
     */
    fun next(): Pacer.Ready? {
        while (true) {
            failure?.let { throw it }
            if (stopped || cancelled()) return null
            val ready = out.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (ready == null) {
                onIdle()
                continue
            }
            if (ready === END_READY) return null
            return ready
        }
    }

    /** Takes indices off the buffer and gives each to its lane, then closes the phase's range out. */
    private fun dispatch() {
        try {
            while (!stopped) {
                val index = buffer.next() ?: break
                val offset = index - indexFrom
                if (offset in 0 until tracked) fired.set(offset)
                laneQueues[Math.floorMod(offset, lanes)].offer(index)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // Every renderer is told there is no more and finished with before the indices nothing fired
            // go out, because what a phase never sent belongs after everything it did send. Offered and
            // never put: a lane queue is unbounded so an offer always takes, and a put would throw the
            // interrupt that closing this brings straight back out of the one block that has to run.
            laneQueues.forEach { it.offer(END_INDEX) }
            joinRenderers()
            if (!stopped) neverFired().forEach { put(it) }
            put(END_READY)
        }
    }

    /** One lane's indices, rendered in the order they were released, into the merged queue. */
    @Suppress("TooGenericExceptionCaught")
    private fun render(lane: Int, prototype: CompiledTemplate.LanePrototype) {
        try {
            while (!stopped) {
                val index = laneQueues[lane].take()
                if (index == END_INDEX) return
                put(Pacer.Ready(lane, prototype.renderOrRefuse(index)))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            failure = e
            stopped = true
        }
    }

    /**
     * Every index of this phase's range no trigger released, as the message the phase never sent.
     *
     * On the dispatcher's own thread, after every renderer has ended, so nothing else is reading [fired]
     * and nothing else is still to arrive.
     */
    private fun neverFired(): List<Pacer.Ready> =
        (0 until tracked).mapNotNull { offset ->
            if (fired.get(offset)) {
                null
            } else {
                val index = indexFrom + offset
                Pacer.Ready(
                    laneIndex = Math.floorMod(offset, lanes),
                    rendered = CompiledTemplate.Rendered.Unaddressable(index, missing),
                    fired = false,
                )
            }
        }

    /** Into the merged queue, waiting for room, and giving up the moment this is closed. */
    private fun put(ready: Pacer.Ready) {
        while (!stopped && !out.offer(ready, POLL_MS, TimeUnit.MILLISECONDS)) Unit
    }

    /** Uninterruptibly, because an interrupt here would leave a renderer writing into a closed phase. */
    @Suppress("SwallowedException")
    private fun joinRenderers() {
        var interrupted = false
        renderers.forEach { thread ->
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun close() {
        stopped = true
        dispatcher.interrupt()
        renderers.forEach { it.interrupt() }
    }

    /** The phase's range as a number of bits, which is every index it could ever be fired for. */
    private val tracked: Int get() = requested.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** As deep as a lane's look-ahead, for the same reason: never in the way, never a run in memory. */
        const val DEPTH = 64
        private const val POLL_MS = 20L

        /** The end of one lane's indices. No message index can collide with it: a phase counts from 1. */
        private const val END_INDEX = Int.MIN_VALUE

        /** The end of the merged queue, as a value in it, so the issuing thread is woken rather than left polling. */
        private val END_READY =
            Pacer.Ready(-1, CompiledTemplate.Rendered.Unaddressable(-1, ""), fired = false)
    }
}
