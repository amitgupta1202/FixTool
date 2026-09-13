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
 *
 * ### Not delivered is not sent
 *
 * A reply whose counterparty is not logged on **at the moment it would go out** is not built and not
 * sent, and [onNotDelivered] is told. This used to be left to QuickFIX/J, which is the wrong place:
 * `Session.sendRaw` runs `toApp` and persists the message *before* its own logged-on check, so a reply to
 * a departed client was captured in its pane as sent, booked, and counted in `responsesSent`, while
 * `sendToTarget`'s `false` went unread. A step that was never on a wire is not a step the venue sent.
 */
class AcceptorDispatch(
    /** Seam for tests; production sends through the QuickFIX session and reports whether it went. */
    private val send: (Message, SessionID) -> Boolean = Session::sendToTarget,
    private val onSent: (Message) -> Unit = {},
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    /** Whether [SessionID] can be written to right now. A seam, because a test's sessions are never registered. */
    private val isLoggedOn: (SessionID) -> Boolean = { Session.lookupSession(it)?.isLoggedOn == true },
    /** A step that was due and did not go, because its counterparty was not there to receive it. */
    private val onNotDelivered: (SessionID, SendReason?) -> Unit = { _, _ -> },
) : Closeable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fixtool-acceptor-response").apply { isDaemon = true }
        }

    /** One queued step, with the reason it carries, so a logout can say what it dropped and why it was owed. */
    private class Pending(
        val future: ScheduledFuture<*>,
        val reason: SendReason?,
    )

    private val pending = ConcurrentHashMap<SessionID, MutableSet<Pending>>()

    /** One scheduled action, by the key it was armed under. A holder, so a finished timer removes only itself. */
    private class Timer {
        @Volatile
        var future: ScheduledFuture<*>? = null
    }

    private val timers = ConcurrentHashMap<String, Timer>()

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
        val queued = pending.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        // Finished work is pruned here rather than by each task removing itself. Self-removal needs
        // the future to be reachable from inside its own body, which it is not until schedule()
        // returns — and a zero-delay task can run before that, so the reference it reads is racily
        // null and the entry never leaves. Pruning on the way in has neither problem.
        queued.removeIf { it.future.isDone }
        val future =
            executor.schedule({ dispatch(build, sessionId, reason) }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        queued.add(Pending(future, reason))
        // Lost the race with cancelAll: the session went away while this was being queued, so the set
        // just added to is one nobody will ever cancel. Identity, not mere presence — a same-named
        // session logging straight back on installs a *different* set, and this future belongs to
        // neither it nor anyone.
        if (pending[sessionId] !== queued) future.cancel(false)
    }

    /**
     * Drops everything still queued for [sessionId] — called when the session logs out — and returns the
     * reason each dropped step carried.
     *
     * Returned rather than counted here, because only the caller knows whether a drop is a step that was
     * owed and never delivered (a logout) or one a person asked to stop (Stop pending). The two are
     * reported differently and neither may be reported as the other.
     */
    fun cancelAll(sessionId: SessionID): List<SendReason?> =
        pending
            .remove(sessionId)
            ?.filter { it.future.cancel(false) }
            ?.map { it.reason }
            .orEmpty()

    /**
     * **Runs [action] on this dispatch thread after [delayMillis]**, under [key] — the venue's one thing that happens
     * without a message arriving.
     *
     * On this thread and not a timer of its own, so what it does is ordered with every reply: an RFQ's expiry is
     * decided between two sends, never during one. Arming a key that is already armed replaces it. An action that
     * throws is reported like a send that throws, and the thread is kept.
     */
    fun scheduleTimer(key: String, delayMillis: Long, action: () -> Unit) {
        val timer = Timer()
        timers.put(key, timer)?.future?.cancel(false)
        timer.future =
            executor.schedule(
                {
                    timers.remove(key, timer)
                    runTimer(key, action)
                },
                delayMillis.coerceAtLeast(0),
                TimeUnit.MILLISECONDS,
            )
    }

    /** Disarms [key], and says whether there was anything armed to disarm. */
    fun cancelTimer(key: String): Boolean = timers.remove(key)?.future?.cancel(false) == true

    /** How many timers are armed and have not run. For tests and diagnostics. */
    fun timerCount(): Int = timers.values.count { it.future?.isDone != true }

    @Suppress("TooGenericExceptionCaught") // the shared thread outlives any one timer's failure
    private fun runTimer(key: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            onError("Scheduled acceptor action '$key' failed: ${e.message}", e)
        }
    }

    /** How many sends are still waiting for their moment on [sessionId]. For tests and diagnostics. */
    fun pendingCount(sessionId: SessionID): Int = pending[sessionId]?.count { !it.future.isDone } ?: 0

    /** Every session with anything still queued, so a venue can count pending work its panes do not name. */
    fun sessionsWithPending(): Set<SessionID> = pending.filterValues { set -> set.any { !it.future.isDone } }.keys

    override fun close() {
        pending.keys.toList().forEach { cancelAll(it) }
        timers.keys.toList().forEach { cancelTimer(it) }
        executor.shutdownNow()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(build: () -> Message, sessionId: SessionID, reason: SendReason?) {
        try {
            // Asked before building, so a step owed to a departed counterparty draws no ExecID and reads
            // no book: nothing about it happened except that it was due.
            if (!isLoggedOn(sessionId)) {
                onNotDelivered(sessionId, reason)
                return
            }
            val message = build()
            // Carried, not decided. This class owns *when* a reply goes out; [reason] is what whoever
            // owned *why* wrote down at the time, and it rides as far as the capture in `toApp` so
            // nothing downstream has to reconstruct it. See SendReason, decision 6a.
            val sent = PendingSendReason.during(reason) { send(message, sessionId) }
            // The session can go between the check above and the write. QuickFIX/J says so by
            // returning false, and a false is a step that did not reach the wire.
            if (sent) onSent(message) else onNotDelivered(sessionId, reason)
        } catch (e: Exception) {
            // The dispatch thread is shared by every pending reply on every session. An exception
            // escaping here would be swallowed into the future nobody reads, and — worse — a throw
            // from a scheduled task is invisible. Report it and keep the thread.
            onError("Acceptor auto-response failed: ${e.message}", e)
        }
    }
}
