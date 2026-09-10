package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.SocketStamp
import com.knapsack.fixtool.service.WireTags
import quickfix.Message
import quickfix.SessionID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **A load run's world as fakes**: lanes whose socket is a list of listeners, and a clock that moves when
 * something happens rather than when the wall does.
 *
 * Shared by [LoadRunnerTest] and [LoadSetRunnerTest], because a set is the same lanes run twice and the
 * second test would otherwise be the first one's fakes copied.
 *
 * Every piece of it holds for one phase at a time and for two at once. A phase on its own thread reads the
 * same clock, sends down the same lane and is counted by the same host as the phase beside it, so anything
 * kept here as a plain `var` or a plain list would be a fake that answers a concurrency question with its
 * own race. What makes two phases work is [FakeClock.concurrently], which advances virtual time only when
 * every phase still running is waiting for a mark, or [FakeClock.withParties] when the threads are not the
 * test's to make: a set runs its phases on threads of its own, inside the call the test is sitting in.
 */
internal class FakeLane(
    slot: Int,
    private val clock: FakeClock,
    /**
     * What comes back for each request wire, for anything [answers] has no policy for.
     *
     * A `var` because a policy that reaches back into its own lane, to withhold a reply or hand one over
     * late, cannot be handed to the constructor that is building that lane.
     */
    @Volatile var answer: (String) -> List<String>,
    private val accept: Boolean = true,
    private val deferStamps: Boolean = false,
) : LoadLane {
    override val lane = Lane(slot, "LOADGEN [$slot]", "LOADGEN%02d".format(slot), "")
    override val sessionId: SessionID = SessionID("FIX.4.4", lane.senderCompID, "VENUE")
    val sent = CopyOnWriteArrayList<String>()

    @Volatile var discardedCount = 0L

    /** Replies the venue is sitting on, handed over by [takeWithheld]. What makes a reply late. */
    private val withheld = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(SocketStamp) -> Unit>()
    private val unwritten = CopyOnWriteArrayList<String>()
    private val byMsgType = ConcurrentHashMap<String, (String) -> List<String>>()

    override fun send(message: Message): Boolean {
        if (!accept) return false
        val wire = message.toString()
        sent += wire
        if (deferStamps) {
            unwritten += wire
            return true
        }
        writeToSocket(wire)
        return true
    }

    /**
     * **What this lane answers a [msgType] request with**, whatever else it is answering.
     *
     * [answer] is one policy for the whole lane, which is all a run of one phase at a time ever needed. Two
     * phases running at once issue different MsgTypes down the same lane and each brings its own venue
     * behaviour, so a policy registered here is picked by the request rather than by whoever set it last.
     */
    fun answers(msgType: String, policy: (String) -> List<String>) {
        byMsgType[msgType] = policy
    }

    /**
     * **The engine's writer thread getting its turn.** A lane built with `deferStamps` accepts a message
     * and stamps nothing, which is every lane on a machine too busy to schedule the write before the
     * pacer returns. Calling this is that thread finally running.
     */
    fun flush() {
        val queued = synchronized(this) { unwritten.toList().also { unwritten.clear() } }
        queued.forEach { writeToSocket(it) }
    }

    /** The venue keeps [reply] to itself until [takeWithheld]. */
    fun withhold(reply: String) {
        withheld += reply
    }

    /**
     * The venue finally answers. Returned so an [answer] policy can add them to a later phase's replies,
     * which is what makes them late: they land on the socket while a different phase is the live one.
     */
    fun takeWithheld(): List<String> {
        val queued = withheld.toList()
        withheld.clear()
        return queued
    }

    /**
     * One request out and its replies back, as one uninterrupted turn on the socket.
     *
     * `synchronized` because QuickFIX/J serialises sends per session behind `senderMsgSeqNumLock`, so two
     * phases issuing down one lane queue for it rather than interleaving a request with another request's
     * replies. It is also what keeps the clock's steps and the stamps they date in the same order.
     */
    private fun writeToSocket(wire: String) =
        synchronized(this) {
            clock.advance(STAMP_NANOS)
            emit(SocketStamp(sessionId, WireDirection.SEND, wire, clock.micros()))
            answerFor(wire).forEach { reply ->
                clock.advance(STAMP_NANOS)
                emit(SocketStamp(sessionId, WireDirection.RECEIVE, reply, clock.micros()))
            }
        }

    /** The policy for one request: its own MsgType's when one is registered, the lane's otherwise. */
    private fun answerFor(wire: String): List<String> = (WireTags.msgType(wire)?.let { byMsgType[it] } ?: answer)(wire)

    fun emit(stamp: SocketStamp) = listeners.forEach { it(stamp) }

    override fun discarded(): Long = discardedCount

    override fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private companion object {
        /** What one trip through the socket costs, so a round trip is a number rather than zero. */
        const val STAMP_NANOS = 1_000L
    }
}

/**
 * Time that moves when sends happen, when the host sleeps, and when the pacer waits.
 *
 * **The rule with more than one thread on it**: virtual time moves to the soonest mark anybody is waiting
 * for, and only once every party still running is waiting for one. A plain `var` advanced by whoever asked
 * first would put the other pacer's whole remaining schedule in the past, and that pacer would then issue
 * everything it had left in one go and report a rate it never held. With one party, which is every test
 * that does not ask for more, that rule is exactly the jump forward this always did.
 */
internal class FakeClock(
    start: Long = 1_700_000_000_000_000_000L,
) : Pacer.Clock {
    private val lock = ReentrantLock()
    private val moved = lock.newCondition()
    private var nanos = start

    /** How many threads may still reach a mark. [withParties] and [concurrently] are what change it. */
    private var expected = 1

    /** The threads that have reached one and could reach another, so an ended one stops being counted. */
    private val parties = HashSet<Thread>()

    /** What each waiting thread is waiting for, so the clock can move to the soonest of them. */
    private val marks = HashMap<Thread, Long>()

    override fun nanoTime(): Long = lock.withLock { nanos }

    override fun awaitUntil(deadlineNanos: Long) = lock.withLock { awaitLocked(deadlineNanos) }

    /** Time spent working rather than waiting: a socket write, and whatever else costs a run its clock. */
    fun advance(byNanos: Long) =
        lock.withLock {
            nanos += byNanos
            moved.signalAll()
        }

    /**
     * **A thread sleeping rather than waiting for a mark**, which is what the host's `sleep` is.
     *
     * It waits the way a pacer does, so a phase that has finished issuing and is polling out its settle
     * window does not drag virtual time forward under a phase still on a schedule.
     */
    fun sleep(byNanos: Long) = lock.withLock { awaitLocked(nanos + byNanos) }

    fun micros(): Long = nanoTime() / 1_000

    fun millis(): Long = nanoTime() / 1_000_000

    /**
     * **Runs [body] with [n] threads expected to take part**, whoever it is that makes them.
     *
     * [concurrently] makes its own threads and can count them off as they return. A set makes one thread
     * per phase, inside the very call the test is sitting in, so the only thing the test can say is how
     * many there are going to be. A party whose thread has ended stops being counted, so the tail of the
     * last one left runs at the jump forward this clock always did.
     */
    fun <T> withParties(n: Int, body: () -> T): T {
        lock.withLock {
            expected = n
            moved.signalAll()
        }
        try {
            return body()
        } finally {
            lock.withLock {
                expected = 1
                parties.clear()
                moved.signalAll()
            }
        }
    }

    /**
     * **Runs [bodies] at the same time, one thread each, as the parties of this clock.**
     *
     * A party that returns stops being counted, so the last one left runs on exactly as a single-threaded
     * test does. The first throwable any of them raised is re-thrown here, because a body that failed on
     * its own thread would otherwise be a green test.
     */
    fun <T> concurrently(vararg bodies: () -> T): List<T> {
        val results = arrayOfNulls<Any?>(bodies.size)
        val failures = CopyOnWriteArrayList<Throwable>()
        withParties(bodies.size) {
            val threads =
                bodies.mapIndexed { i, body ->
                    Thread({
                        try {
                            results[i] = body()
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            lock.withLock {
                                parties.remove(Thread.currentThread())
                                expected = (expected - 1).coerceAtLeast(1)
                                moved.signalAll()
                            }
                        }
                    }, "fake-clock-party-$i")
                }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        failures.firstOrNull()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }

    /** Caller holds [lock]. Returns at or after [deadlineNanos], having moved the clock if it was owed to. */
    private fun awaitLocked(deadlineNanos: Long) {
        val me = Thread.currentThread()
        marks[me] = deadlineNanos
        parties += me
        try {
            var stalledMs = 0L
            while (true) {
                val here = nanos
                if (here >= deadlineNanos) return
                val waitingFor = partiesLocked()
                val soonest = if (marks.size >= waitingFor) marks.values.min() else null
                if (soonest != null && soonest > here) {
                    nanos = soonest
                    moved.signalAll()
                    stalledMs = 0
                    continue
                }
                check(stalledMs < STALL_MS) {
                    "the fake clock waited ${STALL_MS}ms for a party to reach a mark: ${marks.size} of " +
                        "$waitingFor waiting, the clock at ${here}ns, this thread wanting ${deadlineNanos}ns"
                }
                moved.await(STEP_MS, TimeUnit.MILLISECONDS)
                stalledMs = if (nanos > here) 0 else stalledMs + STEP_MS
            }
        } finally {
            marks.remove(me)
            moved.signalAll()
        }
    }

    /**
     * **How many threads the clock still waits for**, with the ones that have ended taken off.
     *
     * [concurrently] takes its own off as each body returns, which it can because it made them. A party a
     * test only said would exist, which is every thread a set makes for its own phases, is taken off when
     * its thread dies. Without that the clock would wait for a phase that finished ten seconds ago and
     * stall the phase still running beside it. Caller holds [lock].
     */
    private fun partiesLocked(): Int {
        val ended = parties.filterNot { it.isAlive }
        if (ended.isNotEmpty()) {
            parties.removeAll(ended.toSet())
            expected = (expected - ended.size).coerceAtLeast(1)
        }
        return expected
    }

    private companion object {
        /** How long a waiter sits before looking again, in real milliseconds. */
        const val STEP_MS = 5L

        /**
         * How long the clock will wait for a party that never arrives before saying so.
         *
         * A hung test says nothing. This one names how many parties were waiting and what for, which is
         * the difference between a bug in the rig and a bug in what it is testing.
         */
        const val STALL_MS = 10_000L
    }
}

internal class FakeHost(
    private val clock: FakeClock,
    private val lanes: List<FakeLane>,
    private val listeners: List<FakeLane> = emptyList(),
    /** Called on every sleep, which is where a settling run spends its time. */
    private val onSleep: () -> Unit = {},
    /**
     * Lanes per profile id, for a set whose phases issue from different profiles: the initiator in phase 1
     * and the responder in phase 2. A profile with no entry here gets [lanes], which is what every test
     * that opens a single profile relies on.
     */
    private val lanesByProfile: Map<String, List<FakeLane>> = emptyMap(),
    /** The same, for a profile nothing issues from. Empty means every listener open gets [listeners]. */
    private val listenersByProfile: Map<String, List<FakeLane>> = emptyMap(),
) : LoadHost {
    @Volatile var released = false

    @Volatile var openedWith: StoreAndLogOverride? = null

    private val opens = AtomicInteger()
    private val releaseCount = AtomicInteger()

    val laneOpens: Int get() = opens.get()
    val releases: Int get() = releaseCount.get()

    /**
     * Which profile came through which door, in the order it was asked for, so a test can say that an
     * issuing profile was opened as lanes and a listen-only one as a listener.
     */
    val laneOpensByProfile = CopyOnWriteArrayList<String>()
    val listenerOpensByProfile = CopyOnWriteArrayList<String>()
    val onceCalls = CopyOnWriteArrayList<String>()

    override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> {
        openedWith = override
        opens.incrementAndGet()
        laneOpensByProfile += profileId
        return lanesByProfile[profileId] ?: lanes
    }

    override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> {
        listenerOpensByProfile += profileIds
        if (listenersByProfile.isEmpty()) return listeners
        return profileIds.flatMap { listenersByProfile[it] ?: listeners }
    }

    override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String {
        onceCalls += template
        return "once-${lane.lane.slot}"
    }

    override fun dictionary(): FixDictionaryAdapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    override fun release() {
        released = true
        releaseCount.incrementAndGet()
    }

    override fun now(): Long = clock.millis()

    override fun sleep(ms: Long) {
        clock.sleep(ms * 1_000_000)
        onSleep()
    }
}

/** An ExecutionReport for whatever ClOrdID the request carried. The venue that answers everything. */
internal fun echo(wire: String): List<String> {
    val id = WireTags.tagValue(wire, 11) ?: return emptyList()
    return listOf("8=FIX.4.435=849=VENUE11=$id37=O-$id39=0")
}
