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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **A load run's world as fakes**: lanes whose socket is a list of listeners, and a clock that moves when
 * something happens rather than when the wall does.
 *
 * Shared by [LoadRunnerTest] and [LoadSetRunnerTest], because a set is the same lanes run twice and the
 * second test would otherwise be the first one's fakes copied.
 */
internal class FakeLane(
    slot: Int,
    private val clock: FakeClock,
    /**
     * What comes back for each request wire. A `var` because a set changes it between phases: phase 1 asks
     * for quotes and phase 2 hits them, and one lane answers both.
     */
    var answer: (String) -> List<String>,
    private val accept: Boolean = true,
    private val deferStamps: Boolean = false,
) : LoadLane {
    override val lane = Lane(slot, "LOADGEN [$slot]", "LOADGEN%02d".format(slot), "")
    override val sessionId: SessionID = SessionID("FIX.4.4", lane.senderCompID, "VENUE")
    val sent = CopyOnWriteArrayList<String>()
    var discardedCount = 0L

    /** Replies the venue is sitting on, handed over by [takeWithheld]. What makes a reply late. */
    private val withheld = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(SocketStamp) -> Unit>()
    private val unwritten = CopyOnWriteArrayList<String>()

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
     * **The engine's writer thread getting its turn.** A lane built with `deferStamps` accepts a message
     * and stamps nothing, which is every lane on a machine too busy to schedule the write before the
     * pacer returns. Calling this is that thread finally running.
     */
    fun flush() {
        val queued = unwritten.toList()
        unwritten.clear()
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

    private fun writeToSocket(wire: String) {
        clock.nanos += 1_000
        emit(SocketStamp(sessionId, WireDirection.SEND, wire, clock.micros()))
        answer(wire).forEach { reply ->
            clock.nanos += 1_000
            emit(SocketStamp(sessionId, WireDirection.RECEIVE, reply, clock.micros()))
        }
    }

    fun emit(stamp: SocketStamp) = listeners.forEach { it(stamp) }

    override fun discarded(): Long = discardedCount

    override fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

/** Time that moves when sends happen, when the host sleeps, and when the pacer waits. */
internal class FakeClock(
    var nanos: Long = 1_700_000_000_000_000_000L,
) : Pacer.Clock {
    override fun nanoTime(): Long = nanos

    override fun awaitUntil(deadlineNanos: Long) {
        if (deadlineNanos > nanos) nanos = deadlineNanos
    }

    fun micros(): Long = nanos / 1_000

    fun millis(): Long = nanos / 1_000_000
}

internal class FakeHost(
    private val clock: FakeClock,
    private val lanes: List<FakeLane>,
    private val listeners: List<FakeLane> = emptyList(),
    /** Called on every sleep, which is where a settling run spends its time. */
    private val onSleep: () -> Unit = {},
) : LoadHost {
    var released = false
    var releases = 0
    var openedWith: StoreAndLogOverride? = null
    var laneOpens = 0
    val onceCalls = mutableListOf<String>()

    override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> {
        openedWith = override
        laneOpens++
        return lanes
    }

    override fun openListeners(profileIds: List<String>, override: StoreAndLogOverride?): List<LoadLane> = listeners

    override fun resolveOnce(template: String, scope: Map<String, String>, lane: LoadLane): String {
        onceCalls += template
        return "once-${lane.lane.slot}"
    }

    override fun dictionary(): FixDictionaryAdapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    override fun release() {
        released = true
        releases++
    }

    override fun now(): Long = clock.millis()

    override fun sleep(ms: Long) {
        clock.nanos += ms * 1_000_000
        onSleep()
    }
}

/** An ExecutionReport for whatever ClOrdID the request carried. The venue that answers everything. */
internal fun echo(wire: String): List<String> {
    val id = WireTags.tagValue(wire, 11) ?: return emptyList()
    return listOf("8=FIX.4.435=849=VENUE11=$id37=O-$id39=0")
}
