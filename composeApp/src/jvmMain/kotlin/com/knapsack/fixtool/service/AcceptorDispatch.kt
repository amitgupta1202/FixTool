package com.knapsack.fixtool.service

import quickfix.Message
import quickfix.Session
import quickfix.SessionID
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Puts an acceptor rule's reply on the wire **after the inbound callback has returned**.
 *
 * [AcceptorResponder] decides *what* to reply with and stays pure; this decides *when*, and owns the
 * one thread that does it.
 *
 * The reply used to go out with a direct `Session.sendToTarget` inside `fromApp` — on QuickFIX's own
 * read thread, before the callback returned. That is not merely early, it is *impossibly* early: the
 * reply can reach the counterparty before their `send()` call has returned to them. A client whose
 * ordering assumptions are wrong then passes against this acceptor and fails against a real venue,
 * which is the tool masking the class of bug it exists to find. So every reply goes through here,
 * **including a zero-delay one** — there is one answer to "when does the acceptor reply", not a fast
 * path and a slow path that can drift apart.
 *
 * **One thread**, so the steps of a sequence keep the order they were written in without any further
 * ceremony. Sequences triggered by different inbound messages interleave with each other, which is
 * exactly what a real venue does with two orders in flight.
 *
 * Pending work is tracked per [SessionID] and dropped on logout: a counterparty that has gone away
 * should not be replied to, and the sends would fail one by one and fill the log with the noise of a
 * conversation nobody is having.
 */
class AcceptorDispatch(
    /** Seam for tests; production sends through the QuickFIX session. */
    private val send: (Message, SessionID) -> Unit = { message, sessionId -> Session.sendToTarget(message, sessionId) },
    private val onSent: (Message) -> Unit = {},
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : Closeable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fixtool-acceptor-response").apply { isDaemon = true }
        }

    private val pending = ConcurrentHashMap<SessionID, MutableSet<ScheduledFuture<*>>>()

    /**
     * Schedules [message] to be sent to [sessionId] after [delayMillis].
     *
     * Returns immediately — a zero delay means "as soon as this thread is free", never "now, on
     * yours". A delay in the past is treated as zero rather than refused; the caller's arithmetic
     * over a sequence's offsets is allowed to be sloppy at the edges.
     */
    fun schedule(message: Message, sessionId: SessionID, delayMillis: Long) {
        val futures = pending.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        // The future has to be reachable from inside its own body to un-track itself, and it does not
        // exist until schedule() returns. The holder closes that gap; it is only ever touched from
        // the single dispatch thread plus this one write, both after the array is allocated.
        val holder = arrayOfNulls<ScheduledFuture<*>>(1)
        val future =
            executor.schedule(
                {
                    holder[0]?.let { futures.remove(it) }
                    dispatch(message, sessionId)
                },
                delayMillis.coerceAtLeast(0),
                TimeUnit.MILLISECONDS,
            )
        holder[0] = future
        futures.add(future)
        // Lost the race with cancelAll: the session went away between computeIfAbsent and here, so
        // this future is in a set nobody will cancel. Cancel it ourselves rather than let it fire at
        // a session that has already logged out.
        if (!pending.containsKey(sessionId)) future.cancel(false)
    }

    /** Drops everything still queued for [sessionId] — called when the session logs out. */
    fun cancelAll(sessionId: SessionID) {
        pending.remove(sessionId)?.forEach { it.cancel(false) }
    }

    /** How many sends are still queued for [sessionId]. For tests and diagnostics. */
    fun pendingCount(sessionId: SessionID): Int = pending[sessionId]?.size ?: 0

    override fun close() {
        pending.keys.toList().forEach { cancelAll(it) }
        executor.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(message: Message, sessionId: SessionID) {
        try {
            send(message, sessionId)
            onSent(message)
        } catch (e: Exception) {
            // The dispatch thread is shared by every pending reply on every session. An exception
            // escaping here would be swallowed into the future nobody reads, and — worse — a throw
            // from a scheduled task is invisible. Report it and keep the thread.
            onError("Acceptor auto-response failed: ${e.message}", e)
        }
    }
}
