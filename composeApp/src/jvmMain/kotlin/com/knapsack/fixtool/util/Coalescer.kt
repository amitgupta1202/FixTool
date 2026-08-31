package com.knapsack.fixtool.util

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * **Runs an expensive publish at most ten times a second, and never drops the last one.**
 *
 * The same problem turned up twice in this codebase and will turn up again: something on a per-message
 * path rebuilds an immutable snapshot for a viewer, the rebuild is proportional to the whole of the
 * state rather than to what changed, and the viewer cannot use more than a handful of updates a second
 * anyway. The order book copied every order per message; the latency tracker sorted ten thousand
 * samples per round trip to recompute percentiles nobody read that fast.
 *
 * Pacing alone is not the answer, and getting that wrong is worse than not pacing at all. If a change
 * arrives inside the window and nothing arrives after it, a naive rate limiter leaves the viewer on
 * stale numbers **forever** — a panel that is wrong with a straight face, which is precisely the defect
 * the observable state was introduced to fix. So every deferred change books a trailing run.
 *
 * [request] is safe to call from any thread and does not block. [flush] is for a reader that wants the
 * truth right now rather than within the window — a synchronous accessor, or a test.
 *
 * The caller owns its own locking: [run] is invoked with no lock held by this class, so whatever it
 * touches must be guarded by the caller the same way it is on the fast path.
 */
class Coalescer(
    private val intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    private val run: () -> Unit,
) {
    private val lock = Any()
    private var pending = false
    private var scheduled = false
    private var lastRunAt = 0L

    /**
     * Note that something changed, and publish it — now if the window has passed, otherwise on the
     * trailing run this books.
     */
    fun request() {
        val due: Long
        synchronized(lock) {
            pending = true
            val now = System.nanoTime()
            val elapsed = now - lastRunAt
            if (elapsed >= intervalNanos) {
                pending = false
                lastRunAt = now
                // Fall through to run outside the lock.
                due = -1
            } else {
                if (scheduled) return
                scheduled = true
                due = (intervalNanos - elapsed) / 1_000_000 + 1
            }
        }
        if (due < 0) {
            run()
            return
        }
        SCHEDULER.schedule(::fire, due, TimeUnit.MILLISECONDS)
    }

    private fun fire() {
        synchronized(lock) {
            scheduled = false
            if (!pending) return
            pending = false
            lastRunAt = System.nanoTime()
        }
        run()
    }

    /** Publishes anything outstanding immediately. For a synchronous read, or a change a person made. */
    fun flush() {
        synchronized(lock) {
            if (!pending) return
            pending = false
            lastRunAt = System.nanoTime()
        }
        run()
    }

    private companion object {
        /**
         * The cadence everything user-facing in this app already moves at — `FixMessageSession` drains
         * on it, and it is the fastest rate a person reads as live.
         */
        const val DEFAULT_INTERVAL_NANOS = 100_000_000L

        /**
         * One daemon thread for every coalescer in the process, and it only ever runs trailing
         * publishes — the writes that would otherwise be lost when activity stops inside a window.
         *
         * Daemon because it must never hold the process open.
         */
        val SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "coalesced-publish").apply { isDaemon = true }
            }
    }
}
