package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.WireDirection
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.SocketStamp
import org.junit.Test
import quickfix.Message
import quickfix.SessionID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The runner over a fake host**: lanes that echo a reply stamp the moment a request leaves, or swallow
 * one, or refuse. What the runner is tested for is the sequence and the accounting, not the socket.
 */
class LoadRunnerTest {
    /** A lane whose socket is a list of listeners. `answer` decides what comes back for each request wire. */
    private class FakeLane(
        slot: Int,
        private val clock: FakeClock,
        private val answer: (String) -> List<String>,
        private val accept: Boolean = true,
    ) : LoadLane {
        override val lane = Lane(slot, "LOADGEN [$slot]", "LOADGEN%02d".format(slot), "")
        override val sessionId: SessionID = SessionID("FIX.4.4", lane.senderCompID, "VENUE")
        val sent = CopyOnWriteArrayList<String>()
        var discardedCount = 0L
        private val listeners = CopyOnWriteArrayList<(SocketStamp) -> Unit>()

        override fun send(message: Message): Boolean {
            if (!accept) return false
            val wire = message.toString()
            sent += wire
            clock.nanos += 1_000
            emit(SocketStamp(sessionId, WireDirection.SEND, wire, clock.micros()))
            answer(wire).forEach { reply ->
                clock.nanos += 1_000
                emit(SocketStamp(sessionId, WireDirection.RECEIVE, reply, clock.micros()))
            }
            return true
        }

        fun emit(stamp: SocketStamp) = listeners.forEach { it(stamp) }

        override fun discarded(): Long = discardedCount

        override fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }
    }

    /** Time that moves when sends happen, when the host sleeps, and when the pacer waits. */
    private class FakeClock(
        var nanos: Long = 1_700_000_000_000_000_000L,
    ) : Pacer.Clock {
        override fun nanoTime(): Long = nanos

        override fun awaitUntil(deadlineNanos: Long) {
            if (deadlineNanos > nanos) nanos = deadlineNanos
        }

        fun micros(): Long = nanos / 1_000

        fun millis(): Long = nanos / 1_000_000
    }

    private class FakeHost(
        private val clock: FakeClock,
        private val lanes: List<FakeLane>,
        private val listeners: List<FakeLane> = emptyList(),
    ) : LoadHost {
        var released = false
        var openedWith: StoreAndLogOverride? = null
        val onceCalls = mutableListOf<String>()

        override fun openLanes(profileId: String, override: StoreAndLogOverride?): List<LoadLane> {
            openedWith = override
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
        }

        override fun now(): Long = clock.millis()

        override fun sleep(ms: Long) {
            clock.nanos += ms * 1_000_000
        }
    }

    private fun echo(wire: String): List<String> {
        val id = com.knapsack.fixtool.service.WireTags.tagValue(wire, 11) ?: return emptyList()
        return listOf("8=FIX.4.435=849=VENUE11=$id37=O-$id39=0")
    }

    private val template = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD", 58 to "\${out.D.11}"))

    private fun plan(shape: LoadShape = LoadShape.Burst(40), settleMs: Long = 2_000, seed: Map<String, String> = mapOf("run" to "t1"), strict: Boolean = false) =
        LoadPlan(
            id = "test-run",
            label = "NOS ${shape.describe()} on LOADGEN",
            template = template,
            profileId = "p",
            profileName = "LOADGEN",
            listenProfileIds = emptyList(),
            shape = shape,
            match = LoadMatch(11),
            settleMs = settleMs,
            seed = seed,
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            strictRate = strict,
        )

    @Test
    fun `a clean burst finishes settle early, matches everything, and releases the sessions`() {
        val clock = FakeClock()
        val lanes = (1..4).map { FakeLane(it, clock, ::echo) }
        val host = FakeHost(clock, lanes)
        val phases = mutableListOf<LoadPhase>()

        val outcome = LoadRunner(host, clock = clock).run(plan()) { phases += it.phase }
        val r = outcome.report

        assertEquals(LoadStatus.DONE, r.status)
        assertEquals(LoadReport.Completeness.COMPLETE, r.verdict.completeness)
        assertEquals(0, r.verdict.exitCode)
        assertEquals(40L, r.issue.requested)
        assertEquals(40L, r.issue.handedToEngine)
        assertEquals(40L, r.issue.leftSocket)
        assertEquals(40L, r.replies.matched)
        assertEquals(0L, r.replies.unmatched)
        assertEquals(listOf(10, 10, 10, 10), lanes.map { it.sent.size }, "round-robin over the lanes")
        assertTrue(lanes[0].sent[0].contains("11=ORD-t1-1"), lanes[0].sent[0])
        assertTrue(lanes[1].sent[0].contains("11=ORD-t1-2"), lanes[1].sent[0])
        assertEquals(listOf(11), r.template.perMessageTags)
        assertEquals(listOf(58), r.template.onceTags)
        assertEquals(4, host.onceCalls.size, "the Kotlin field was evaluated once per lane, never per message")
        assertTrue(lanes[2].sent.all { it.contains("58=once-3") }, "and its frozen value rides every message of that lane")
        assertEquals(StoreAndLogOverride.FOR_LOAD, host.openedWith)
        assertTrue(host.released)
        assertEquals(LoadPhase.PREPARING, phases.first())
        assertEquals(LoadPhase.DONE, phases.last())
        assertTrue(r.finishedAt!! - r.startedAt < 2_000, "settle ended the moment nothing was pending, not after the window: ${r.finishedAt!! - r.startedAt}ms")
        assertNotNull(r.roundTrip).let { assertEquals(40, it.samples) }
        assertNotNull(r.timing)
    }

    @Test
    fun `a swallowed request is unmatched by id, settle runs its full window, and the exit code is 1`() {
        val clock = FakeClock()
        val lanes = (1..2).map { slot -> FakeLane(slot, clock, { wire -> if (wire.contains("11=ORD-t1-7")) emptyList() else echo(wire) }) }
        val host = FakeHost(clock, lanes)

        val outcome = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(10), settleMs = 3_000))
        val r = outcome.report

        assertEquals(LoadReport.Completeness.UNMATCHED, r.verdict.completeness)
        assertEquals(1, r.verdict.exitCode)
        assertEquals(9L, r.replies.matched)
        assertEquals(1L, r.replies.unmatched)
        assertEquals(listOf("ORD-t1-7"), r.unmatched.map { it.id })
        assertEquals(1, r.unmatched.single().lane, "message 7 went to lane (7-1) mod 2 + 1")
        assertEquals(listOf("ORD-t1-7"), outcome.unmatched.map { it.id })
        assertTrue(outcome.unmatched.single().wire.contains("11=ORD-t1-7"))
        assertTrue(r.finishedAt!! - r.startedAt >= 3_000, "with something pending the window runs its full length: ${r.finishedAt!! - r.startedAt}ms")
    }

    @Test
    fun `discarded panes and refused sends make the run tool-limited, whatever the venue did`() {
        val clock = FakeClock()
        val good = FakeLane(1, clock, ::echo)
        val refusing = FakeLane(2, clock, ::echo, accept = false)
        val host = FakeHost(clock, listOf(good, refusing))
        good.discardedCount = 5

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(10))).report

        assertEquals(5L, r.issue.handedToEngine)
        assertEquals(5L, r.tool.issueFailures)
        assertEquals(0L, r.tool.discarded, "discarded is the delta over the run, and this pane's count did not move")
        assertEquals(LoadReport.ToolVerdict.LIMITED, r.verdict.tool)
        assertEquals(LoadReport.Completeness.COMPLETE, r.verdict.completeness, "everything that left was answered")
        assertEquals(1, r.verdict.exitCode)
    }

    @Test
    fun `cancellation mid-issue stops with the counts so far`() {
        val clock = FakeClock()
        val lanes = listOf(FakeLane(1, clock, ::echo))
        val host = FakeHost(clock, lanes)

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(1_000)), cancelled = { lanes[0].sent.size >= 25 }).report

        assertEquals(LoadStatus.STOPPED, r.status)
        assertEquals(25L, r.issue.leftSocket)
        assertEquals(25L, r.replies.matched)
        assertEquals(1, r.verdict.exitCode)
    }

    @Test
    fun `a rate run reports the schedule it held`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, ::echo) }
        val host = FakeHost(clock, lanes)

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Rate(perSecond = 200, forMs = 3_000))).report

        assertEquals(600L, r.issue.requested)
        assertEquals(600L, r.replies.matched)
        val rate = assertNotNull(r.rate)
        assertEquals(200, rate.requestedPerSecond)
        assertEquals(emptyList(), rate.shortfalls)
        assertEquals(LoadReport.RateVerdict.HELD, r.verdict.rate)
        assertEquals(0, r.verdict.exitCode)
    }

    @Test
    fun `a name nothing seeds and a template without a type are refused before any lane opens`() {
        val clock = FakeClock()
        val host = FakeHost(clock, listOf(FakeLane(1, clock, ::echo)))

        val missing = assertFailsWith<LoadRefused> { LoadRunner(host, clock = clock).run(plan(seed = emptyMap())) }
        assertTrue(missing.message!!.contains("\${run}"), missing.message)
        assertTrue(missing.message!!.contains("--set run="), missing.message)

        val typeless = assertFailsWith<LoadRefused> {
            LoadRunner(host, clock = clock).run(plan().copy(template = LoadTemplate("x", listOf(11 to "A"))))
        }
        assertTrue(typeless.message!!.contains("tag 35"), typeless.message)
        assertTrue(!host.released, "nothing was opened, so nothing is released")
    }

    @Test
    fun `no lane logged on is a refusal, not an empty report`() {
        val clock = FakeClock()
        val host = FakeHost(clock, emptyList())

        val refused = assertFailsWith<LoadRefused> { LoadRunner(host, clock = clock).run(plan()) }

        assertTrue(refused.message!!.contains("LOGGED_ON"), refused.message)
    }
}
