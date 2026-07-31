package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.PendingSendReason
import com.knapsack.fixtool.model.SendReason
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
     * Schedules the message [build] returns to be sent to [sessionId] after [delayMillis].
     *
     * Returns immediately — a zero delay means "as soon as this thread is free", never "now, on
     * yours". A negative delay is treated as zero rather than refused; the caller's arithmetic over a
     * sequence's offsets is allowed to be sloppy at the edges.
     *
     * [build] runs on the dispatch thread, immediately before the send, so a step's `${uuid}` and
     * `${now}` describe that step and not the trigger that queued it. A build that throws is reported
     * exactly like a send that throws — the step is lost, the rest of the sequence is not.
     *
     * [reason] is carried, never decided: this class owns *when* a reply goes out, and the caller who
     * owned *why* wrote it down when the decision was made. It rides as far as the capture of the
     * outgoing message so nothing downstream has to reconstruct it — see [SendReason], decision 6a.
     */
    fun schedule(sessionId: SessionID, delayMillis: Long, reason: SendReason? = null, build: () -> Message) {
        val futures = pending.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        // Finished work is pruned here rather than by each task removing itself. Self-removal needs
        // the future to be reachable from inside its own body, which it is not until schedule()
        // returns — and a zero-delay task can run before that, so the reference it reads is racily
        // null and the entry never leaves. Pruning on the way in has neither problem.
        futures.removeIf { it.isDone }
        val future =
            executor.schedule({ dispatch(build, sessionId, reason) }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        futures.add(future)
        // Lost the race with cancelAll: the session went away while this was being queued, so the set
        // just added to is one nobody will ever cancel. Identity, not mere presence — a same-named
        // session logging straight back on installs a *different* set, and this future belongs to
        // neither it nor anyone.
        if (pending[sessionId] !== futures) future.cancel(false)
    }

    /** Drops everything still queued for [sessionId] — called when the session logs out. */
    fun cancelAll(sessionId: SessionID) {
        pending.remove(sessionId)?.forEach { it.cancel(false) }
    }

    /** How many sends are still waiting for their moment on [sessionId]. For tests and diagnostics. */
    fun pendingCount(sessionId: SessionID): Int = pending[sessionId]?.count { !it.isDone } ?: 0

    override fun close() {
        pending.keys.toList().forEach { cancelAll(it) }
        executor.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(build: () -> Message, sessionId: SessionID, reason: SendReason?) {
        try {
            val message = build()
            // Carried, not decided. This class owns *when* a reply goes out; [reason] is what whoever
            // owned *why* wrote down at the time, and it rides as far as the capture in `toApp` so
            // nothing downstream has to reconstruct it. See SendReason, decision 6a.
            PendingSendReason.during(reason) { send(message, sessionId) }
            onSent(message)
        } catch (e: Exception) {
            // The dispatch thread is shared by every pending reply on every session. An exception
            // escaping here would be swallowed into the future nobody reads, and — worse — a throw
            // from a scheduled task is invisible. Report it and keep the thread.
            onError("Acceptor auto-response failed: ${e.message}", e)
        }
    }
}
