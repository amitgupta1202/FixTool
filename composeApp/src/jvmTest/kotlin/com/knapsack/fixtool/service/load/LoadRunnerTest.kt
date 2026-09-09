package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.WireTags
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The runner over a fake host**: lanes that echo a reply stamp the moment a request leaves, or swallow
 * one, or refuse. What the runner is tested for is the sequence and the accounting, not the socket.
 */
class LoadRunnerTest {
    private val template = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD", 58 to "\${out.D.11}"))

    @Suppress("LongParameterList")
    private fun plan(
        shape: LoadShape = LoadShape.Burst(40),
        settleMs: Long = 2_000,
        seed: Map<String, String> = mapOf("run" to "t1"),
        strict: Boolean = false,
        indexFrom: Int = 1,
    ) =
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
            indexFrom = indexFrom,
        )

    /**
     * What lets a three-phase RFQ set say "the other 2,000": one integer, added to the pacer's counter
     * before the prototype renders, and visible nowhere else.
     */
    @Test
    fun `indexFrom shifts every message index and the count stays what was asked for`() {
        val clock = FakeClock()
        val lanes = (1..4).map { FakeLane(it, clock, ::echo) }
        val host = FakeHost(clock, lanes)

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(10), indexFrom = 2_001)).report

        val ids = lanes.flatMap { lane -> lane.sent.map { WireTags.tagValue(it, 11) } }
        assertEquals(10, ids.size, "the count is the count, whatever the indices are")
        assertEquals((2_001..2_010).map { "ORD-t1-$it" }.toSet(), ids.toSet())
        assertEquals(2_001, r.indexFrom, "the report says where it counted from, so the record and the row can")
        assertEquals(10L, r.replies.matched)
    }

    @Test
    fun `a clean burst finishes settle early, matches everything, and releases the sessions`() {
        val clock = FakeClock()
        val lanes = (1..4).map { FakeLane(it, clock, ::echo) }
        val host = FakeHost(clock, lanes)
        val stages = mutableListOf<LoadStage>()

        val outcome = LoadRunner(host, clock = clock).run(plan()) { stages += it.stage }
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
        assertEquals(LoadStage.PREPARING, stages.first())
        assertEquals(LoadStage.DONE, stages.last())
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
    fun `a send the engine has not written yet holds the window open, instead of being reported as never sent`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, ::echo, deferStamps = true) }
        var sleeps = 0
        // The writer thread gets its turn on the second poll of the settle window, and not before: at the
        // moment the pacer returns, ten messages are with the engine and none has been stamped out.
        val host = FakeHost(clock, lanes, onSleep = { if (++sleeps == 2) lanes.forEach { it.flush() } })

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(10), settleMs = 5_000)).report

        assertEquals(10L, r.issue.handedToEngine)
        assertEquals(10L, r.issue.leftSocket, "the window waited for the issue path to drain rather than closing on an empty pending set")
        assertEquals(0L, r.tool.neverLeftSocket, "nothing was stranded, so the tool takes no blame")
        assertTrue(!r.tool.limited, "a busy machine is not a limited run")
        assertEquals(10L, r.replies.matched)
        assertEquals(LoadReport.Completeness.COMPLETE, r.verdict.completeness)
        assertEquals(0, r.verdict.exitCode)
    }

    @Test
    fun `a send that truly never leaves is still tool-limited, at the cost of the whole window`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, ::echo, deferStamps = true) }
        val host = FakeHost(clock, lanes) // nothing ever flushes: the engine took them and wrote none

        val r = LoadRunner(host, clock = clock).run(plan(shape = LoadShape.Burst(10), settleMs = 3_000)).report

        assertEquals(10L, r.issue.handedToEngine)
        assertEquals(0L, r.issue.leftSocket)
        assertEquals(10L, r.tool.neverLeftSocket)
        assertTrue(r.tool.limited)
        assertEquals(1, r.verdict.exitCode)
        assertTrue(r.finishedAt!! - r.startedAt >= 3_000, "only the full window proves it never went: ${r.finishedAt!! - r.startedAt}ms")
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

    /**
     * **A refused send takes its message index back.**
     *
     * The index reaches the matcher before the send, because the SEND stamp carries the wire and nothing
     * else, and that stamp is what takes it out again. A send the engine will not take is the one path with
     * no stamp behind it, so every refusal used to leave an entry the run never claimed again.
     */
    @Test
    fun `a send the engine refuses leaves no index behind in the matcher`() {
        val clock = FakeClock()
        val refusing = FakeLane(1, clock, ::echo, accept = false)
        val host = FakeHost(clock, listOf(refusing))
        var matcher: StampMatcher? = null

        val runner =
            LoadRunner(
                host,
                clock = clock,
                listen = { m, ls ->
                    matcher = m
                    LoadRunner.everySession(m, ls)
                },
            )
        val r = runner.run(plan(shape = LoadShape.Burst(10)).copy(capture = mapOf("fillId" to 37))).report

        assertEquals(10L, r.tool.issueFailures, "the engine took none of them")
        assertEquals(0, assertNotNull(matcher).issuedNotStamped(), "and none of the ten is still waiting for a stamp that is not coming")
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
        assertTrue(missing.message!!.contains("--seed run="), missing.message)

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
