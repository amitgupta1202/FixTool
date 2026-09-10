package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.WireTags
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Two phases at once**, first over the fakes and then under [LoadSetRunner] itself.
 *
 * The rig comes first: two [LoadRunner]s on their own threads, sharing one clock, one host and one pair of
 * lanes, each on its own rate, each reporting the schedule it actually held. The failure it exists to catch
 * is the one a plain `var` clock gives you. Virtual time that jumps to whichever pacer asked first drags
 * the other pacer past its own marks, and that pacer then issues late or issues everything it has left in
 * one go, while still reporting a rate it never held. Which is worse than a red test, because both runs
 * pass their own verdicts. [FakeClock] releases each pacer at its own mark instead, so the number that says
 * so is the lag: nought for both, or the rig is lying.
 *
 * Then the same two phases under the set runner, which is what step 3a is for. Each phase runs on a thread
 * of its own and a phase that names a trigger starts when that phase starts, so two phases of one set can
 * be issuing down one pair of lanes at the same moment. **Nothing reactive is involved.** Both phases here
 * are paced, and what puts them side by side is the runner's gate, not a trigger: nothing posts to a
 * trigger buffer yet. `LoadSet.problems()` refuses a trigger on a paced phase, so no set a surface will
 * accept overlaps, and a plan handed straight to the runner is the only thing that can.
 *
 * **Two phases at the same rate would prove nothing**, because identical schedules keep each other in step
 * whatever the clock does. These are 200 a second and 500 a second, and the assertion is the lag.
 */
class ConcurrentPhasesTest {
    private val orders = LoadTemplate("Orders", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val quoteRequests = LoadTemplate("Quotes", listOf(35 to "R", 131 to "Q-\${run}-\${messageIndex}", 55 to "EUR/USD"))

    /** A template that reads a name nothing seeds, which is what a phase throwing out of its own thread is. */
    private val unseeded = LoadTemplate("Unseeded", listOf(35 to "R", 131 to "Q-\${nobodySeedsThis}", 55 to "EUR/USD"))

    /** A hit at the price another phase keeps off its quotes, which is a name only that phase puts in scope. */
    private val booksTheOffer =
        LoadTemplate("Books", listOf(35 to "AJ", 11 to "H-\${run}-\${messageIndex}", 44 to "\${offer}", 694 to "1"))

    private fun plan(
        id: String,
        template: LoadTemplate,
        match: LoadMatch,
        perSecond: Int,
        forMs: Long = 3_000,
    ) = LoadPlan(
        id = id,
        label = "$id at $perSecond a second on LOADGEN",
        template = template,
        profileId = "p",
        profileName = "LOADGEN",
        shape = LoadShape.Rate(perSecond = perSecond, forMs = forMs),
        match = match,
        settleMs = 2_000,
        seed = mapOf("run" to "t1"),
        storeAndLog = StoreAndLogOverride.FOR_LOAD,
    )

    /** An ExecutionReport for the ClOrdID an order carried. */
    private fun fill(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 11) ?: return emptyList()
        return listOf("8=FIX.4.435=849=VENUE11=$id39=0")
    }

    /** A Quote for the QuoteReqID a request carried, which is a different tag and a different reply type. */
    private fun quote(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 131) ?: return emptyList()
        return listOf("8=FIX.4.435=S49=VENUE131=$id117=QID-$id133=1.09010")
    }

    /** Two lanes that answer an order with a fill and a quote request with a quote, per request. */
    private fun lanes(clock: FakeClock): List<FakeLane> =
        (1..2).map { slot ->
            FakeLane(slot, clock, { emptyList() }).apply {
                // Per request and not per lane: one lane answers both phases, and neither phase's venue
                // behaviour is whichever one happened to be installed last.
                answers("D", ::fill)
                answers("R", ::quote)
            }
        }

    @Test
    fun `two rate phases run at once down the same lanes, and each holds its own schedule`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)

        // Two rates and not one, so the marks the two pacers wait for are genuinely different sequences.
        val reports =
            clock.concurrently(
                { LoadRunner(host, clock = clock).run(plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200)).report },
                { LoadRunner(host, clock = clock).run(plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500)).report },
            )

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), reports.map { it.status })
        assertEquals(listOf(600L, 1_500L), reports.map { it.issue.requested })
        reports.forEach { r -> assertHeldItsSchedule(r) }
        assertEquals(2_000L, assertNotNull(reports[0].rate).heldForMs, "both full seconds ran at the rate asked for")
        assertEquals(2_000L, assertNotNull(reports[1].rate).heldForMs)

        // And the two streams really did share the lanes, rather than one finishing before the other began.
        assertEquals(600, lanes.sumOf { lane -> lane.sent.count { it.contains("35=D") } })
        assertEquals(1_500, lanes.sumOf { lane -> lane.sent.count { it.contains("35=R") } })
    }

    // -------------------------------------------------------------------------------------------------
    // The same, under the set runner
    // -------------------------------------------------------------------------------------------------

    /**
     * **The exit criterion of step 3a**: two paced phases of one set, issuing at the same time, each
     * holding its own schedule.
     *
     * The set ran its phases strictly in order, one after the other on the thread that started it, and the
     * only thing that made that true was the loop. Now every phase has a thread and a gate, and phase 2's
     * gate is phase 1's **start** rather than its end. Both phases are paced: the trigger is what says when
     * phase 2 may begin and nothing else, and no message is released by a reply.
     *
     * What the lag proves is that neither phase dragged the other's clock past a mark, which is the failure
     * that would otherwise pass both verdicts. What the record proves is that they were live at once:
     * `[RUNNING, RUNNING]` cannot appear in a set that runs its phases one after another. Nothing here
     * asserts on a tail second, because the moment one phase ends the clock has one party again and the
     * survivor's last second is its own.
     */
    @Test
    fun `two paced phases of one set run at once, each holding its own schedule`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)
        val seen = CopyOnWriteArrayList<List<LoadStatus>>()

        val record =
            clock.withParties(2) {
                LoadSetRunner(host, clock = clock).run(
                    setOf(
                        plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200),
                        plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500).copy(after = 1),
                    ),
                ) { seen += it.phases.map { p -> p.status } }
            }

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), record.phases.map { it.status })
        assertEquals(0, record.exitCode)
        assertEquals(listOf(600L, 1_500L), record.phases.map { it.issue.requested })
        record.phases.forEach { r -> assertHeldItsSchedule(r) }
        assertTrue(
            seen.any { it == listOf(LoadStatus.RUNNING, LoadStatus.RUNNING) },
            "the two phases were never live at the same moment, so the set still runs them in order: $seen",
        )
        assertEquals(600, lanes.sumOf { lane -> lane.sent.count { it.contains("35=D") } })
        assertEquals(1_500, lanes.sumOf { lane -> lane.sent.count { it.contains("35=R") } })
        lanes.forEach { lane ->
            assertTrue(
                lane.sent.indexOfFirst { it.contains("35=R") } < lane.sent.indexOfLast { it.contains("35=D") },
                "lane ${lane.lane.slot} sent one phase's whole stream before the other's began",
            )
        }
        assertEquals(1, host.laneOpens, "the lanes are opened once for the set, not once per phase")
        assertEquals(1, host.releases, "and released once, after the last phase")
    }

    /**
     * **A phase that throws does not take the phase beside it down with it.**
     *
     * The set's teardown closes every stamp listener and releases the host, which in the app begins a
     * disconnect and a reconnect. Re-throwing from the middle of the phases would run that under a phase
     * still issuing: it would lose every stamp it had left and have its sessions cycled underneath it. So
     * every phase is joined first, and only then does anything go anywhere.
     *
     * What escapes a phase is every `Throwable` and not [LoadRefused] in particular. In a set none of
     * `LoadRunner`'s own refusals is reachable, because templates, lanes and unseeded names are all judged
     * before phase 1 dials. This plan is handed straight to the runner, which is the one way to get one.
     */
    @Test
    fun `a phase that throws is joined back after every other phase has ended`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)
        val seen = CopyOnWriteArrayList<List<LoadStatus>>()

        val refused =
            assertFailsWith<LoadRefused> {
                LoadSetRunner(host, clock = clock).run(
                    setOf(
                        plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200),
                        plan("broken", unseeded, LoadMatch(131, 131, "S"), perSecond = 500).copy(after = 1),
                    ),
                ) { seen += it.phases.map { p -> p.status } }
            }

        assertTrue(refused.message!!.contains("nobodySeedsThis"), refused.message)
        assertEquals(
            listOf(LoadStatus.DONE, LoadStatus.PENDING),
            seen.last(),
            "phase 1 ran to its own end while phase 2 was already dead: $seen",
        )
        assertEquals(600, lanes.sumOf { lane -> lane.sent.count { it.contains("35=D") } }, "and issued everything it asked for")
        assertEquals(1, host.releases, "released once, after the join, and not from under the phase still going")
    }

    /**
     * **A phase whose thread will not start does not leave the set holding its own gates.**
     *
     * Fifty lanes over three phases asks a machine for a hundred and fifty-three threads, and one with
     * none left answers `Thread.start()` with an `OutOfMemoryError` rather than with a thread. Thrown
     * from where the phases are started, it goes straight past the joins beneath it into the teardown,
     * which closes every stamp listener and releases the host: in the app that is a disconnect and a
     * reconnect under phase 1, which is still issuing. Nothing counts the unstarted phase's latches down
     * either, so a phase gated on that one waits at its gate for the rest of the process.
     *
     * So the phase that could not start is counted out by hand, as is every phase behind it, and the
     * error reaches the caller only after phase 1 has ended. The venue notes whether the host had been
     * released each time it answered, which is what "after" means here, and takes its time over the
     * first message so that a teardown running early would have a window it could not miss.
     *
     * **The machine runs out of threads once phase 1 is issuing**, rather than at some moment of the
     * scheduler's choosing. A phase still at its gate when a later one fails is skipped by that failure,
     * which is a fine record and not what this is about: what is worth pinning is the phase that had
     * started, and the set holding on to its own error until that phase has ended.
     */
    @Test
    fun `a phase whose thread will not start is counted out, and the phase that did start is joined`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)
        val answeredAfterRelease = AtomicBoolean()
        val firstAnswer = AtomicBoolean(true)
        lanes.forEach { lane ->
            lane.answers("D") { wire ->
                if (host.released) answeredAfterRelease.set(true)
                if (firstAnswer.compareAndSet(true, false)) Thread.sleep(SLOW_ANSWER_MS)
                fill(wire)
            }
        }
        val runner = LoadSetRunner(host, clock = clock)
        val buffers = CopyOnWriteArrayList<TriggerBuffer>()
        runner.onTriggerBuffers = { buffers += it }
        val seen = CopyOnWriteArrayList<List<LoadStatus>>()
        // A machine with a thread for phase 1 and none for anything after it.
        runner.newThread = { name, body ->
            if (name.endsWith("-1")) {
                Thread(body, name)
            } else {
                object : Thread(body, name) {
                    override fun start() {
                        awaitSent("phase 1's first order", lanes)
                        throw OutOfMemoryError("unable to create native thread")
                    }
                }
            }
        }

        val thrown =
            assertFailsWith<OutOfMemoryError> {
                runner.run(
                    setOf(
                        burst("ask", orders, LoadMatch(11, 11, "8")),
                        burst("hit", quoteRequests, LoadMatch(131, 131, "S")),
                        burst("react", quoteRequests, LoadMatch(131, 131, "S")).copy(after = 2),
                    ),
                ) { seen += it.phases.map { p -> p.status } }
            }

        assertEquals("unable to create native thread", thrown.message)
        assertEquals(
            listOf(LoadStatus.DONE, LoadStatus.PENDING, LoadStatus.PENDING),
            seen.last(),
            "phase 1 reached its own verdict before the error went anywhere: $seen",
        )
        assertEquals(4, lanes.sumOf { lane -> lane.sent.count { it.contains("35=D") } }, "and issued everything it asked for")
        assertFalse(answeredAfterRelease.get(), "the host was released while phase 1 was still being answered")
        assertTrue(buffers.all { it.closed }, "a phase that never started still closes what was waiting on it")
        assertEquals(
            "phase 2 did not run, so nothing would have fired this one",
            buffers[2].note,
            "and phase 3 is told, rather than left at a gate nobody will open",
        )
        assertEquals(1, host.releases)
    }

    /**
     * **An interrupt on the thread running the set costs it neither its record nor the interrupt.**
     *
     * Stopping a set is a polled flag and never an interrupt, so an interrupt landing on this thread is
     * somebody else's. The joins that hold the teardown off the live phases swallow it, and what they
     * used to do was hand it straight back on their way out. The next thing the set does is join the
     * record writer, and a join on a thread whose flag is set throws before it waits: the writer was left
     * running, the last record was never written, the last progress line never went out, and the caller
     * was handed an InterruptedException where a record was due.
     *
     * Nothing interrupts this thread in the app today, which is what makes it worth pinning now rather
     * than after something does. The interrupt itself is not swallowed for good: it is handed back on the
     * last line of the set, with nothing left behind it to take away.
     */
    @Test
    fun `an interrupt on the set's own thread does not cost it the last record`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)
        val dir = File.createTempFile("fixtool-set-interrupt", "").also { it.delete() }
        try {
            val store = LoadRecordStore(dir.absolutePath)
            val landed = AtomicBoolean()
            val record =
                LoadSetRunner(host, store, clock = clock).run(
                    setOf(
                        burst("ask", orders, LoadMatch(11, 11, "8")),
                        burst("hit", quoteRequests, LoadMatch(131, 131, "S")),
                    ),
                ) {
                    // Somebody else's interrupt, on the set's own thread, before it has run a phase.
                    if (landed.compareAndSet(false, true)) Thread.currentThread().interrupt()
                }

            // First, because reading it is also what clears it: the rest of this test, and every test
            // after it on this thread, runs without somebody else's interrupt on it.
            assertTrue(Thread.interrupted(), "the interrupt is handed back, once, on the last line of the set")
            assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), record.phases.map { it.status })
            assertEquals(record, store.readRecord(record.id), "the record on disk is the one the set returned")
        } finally {
            Thread.interrupted()
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------------------------------
    // The trigger buffers: closed on every way out, which is what removes the deadlock
    // -------------------------------------------------------------------------------------------------

    /**
     * **The deadlock, in the shape the plan names it in.** Phases 1 and 2 paced, 3 waiting on 2, phase 1
     * failing under STOP. Phase 2 is skipped before its matcher is ever built, so nothing on the reply path
     * could close phase 3's buffer, and a phase 3 that waited on one would wait for ever.
     *
     * The close belongs to phase 2's exit rather than to its replies, and the note says which exit it was.
     * Phase 3 is skipped for the same reason and says so in its own voice, so the record reads as a chain:
     * phase 1 did not pass, phase 2 did not run, and nothing was ever going to fire phase 3.
     */
    @Test
    fun `a skipped phase closes the buffer of everything that was waiting on it`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val host = FakeHost(clock, lanes)
        val runner = LoadSetRunner(host, clock = clock)
        val buffers = CopyOnWriteArrayList<TriggerBuffer>()
        runner.onTriggerBuffers = { buffers += it }

        // Nothing answers, so phase 1 fails on unmatched requests and the set stops on it.
        val record =
            runner.run(
                setOf(
                    burst("ask", orders, LoadMatch(11, 11, "8")),
                    burst("hit", quoteRequests, LoadMatch(131, 131, "S")),
                    burst("react", quoteRequests, LoadMatch(131, 131, "S")).copy(after = 2),
                ),
            )

        assertEquals(
            listOf(LoadStatus.DONE, LoadStatus.SKIPPED, LoadStatus.SKIPPED),
            record.phases.map { it.status },
        )
        assertEquals("phase 1 did not pass and the set stops on failure", record.phases[1].note)
        assertEquals("phase 2 did not run, so nothing would have fired this one", record.phases[2].note)
        assertEquals(3, buffers.size, "one buffer per phase, made before any phase started")
        assertTrue(buffers.all { it.closed }, "every buffer is closed on the way out, whichever way that was")
        assertEquals(
            "phase 2 did not run, so nothing would have fired this one",
            buffers[2].note,
            "the buffer says the same thing the skipped phase does",
        )
        assertEquals("it runs on its own schedule, so nothing fires it", buffers[0].note)
    }

    /** A phase that ran closes its dependants' buffers too, saying it has finished rather than that it never went. */
    @Test
    fun `a phase that ran closes the buffer of everything waiting on it when it ends`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)
        val runner = LoadSetRunner(host, clock = clock)
        val buffers = CopyOnWriteArrayList<TriggerBuffer>()
        runner.onTriggerBuffers = { buffers += it }

        val record =
            clock.withParties(2) {
                runner.run(
                    setOf(
                        plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200),
                        plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500).copy(after = 1),
                    ),
                )
            }

        assertEquals(0, record.exitCode)
        assertEquals("phase 1 has finished, so nothing more will fire this one", buffers[1].note)
        assertTrue(buffers.all { it.closed })
    }

    // -------------------------------------------------------------------------------------------------
    // What overlap costs: one discard, counted by both phases
    // -------------------------------------------------------------------------------------------------

    /**
     * **One message thrown away, reported by both phases, and failing both.**
     *
     * `discarded` is a delta: every participating session's cumulative counter, read when a phase starts
     * and again when it ends. Two phases over the same sessions at the same time take deltas that span the
     * same discard, so one message the panes threw away fails two phases and the set names the earlier of
     * them. The number is not exact in the other direction either, since the counter is incremented
     * without a lock from the receive thread and from every sending thread.
     *
     * This is pinned rather than fixed. Moving the count to the set means a new field on the record, both
     * directions of the codec and a rule for how a set-level discard fails a set once no phase carries
     * one, which belongs with the report's own reshaping. Nothing a surface accepts overlaps yet, so
     * nothing reads a doubled number today. What this test is for is that the day it does, it is a test
     * that changes and not a surprise.
     */
    @Test
    fun `two overlapping phases both count the same discarded message`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        // On phase 1's third order, which is after phase 2 has read its own starting count: the clock
        // cannot reach phase 1's second mark until phase 2 is waiting at one of its own, and phase 2 reads
        // the count before it waits for anything.
        lanes.first().answers("D") { wire ->
            if (WireTags.tagValue(wire, 11) == "ORD-t1-3") lanes.first().discardedCount++
            fill(wire)
        }
        val host = FakeHost(clock, lanes)

        val record =
            clock.withParties(2) {
                LoadSetRunner(host, clock = clock).run(
                    setOf(
                        plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200),
                        plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500).copy(after = 1),
                    ),
                )
            }

        assertEquals(1L, lanes.sumOf { it.discardedCount }, "the panes threw one message away, once")
        assertEquals(listOf(1L, 1L), record.phases.map { it.tool.discarded }, "and both phases charged themselves with it")
        assertEquals(
            listOf(LoadReport.ToolVerdict.LIMITED, LoadReport.ToolVerdict.LIMITED),
            record.phases.map { it.verdict.tool },
        )
        assertEquals(1, record.exitCode, "a discard fails a phase, so one discard fails a set twice over")
        assertEquals(1, record.verdict.phase, "named on the earlier of the two")
    }

    // -------------------------------------------------------------------------------------------------
    // What a phase may read while another is still filling it
    // -------------------------------------------------------------------------------------------------

    /**
     * **A phase that starts beside another may not read that other one's captures.**
     *
     * `LoadSet.problems()` says so as R17, in the phase's own voice, before anything dials. The runner had
     * no such rule of its own: it handed every earlier live phase's captures over by list position, so a
     * set that reached it without being validated would read a table a sibling was still filling. That is
     * a race and not an untidiness. Here phase 3 starts with phase 1 and reads what phase 2 keeps, and
     * phase 2 has not sent a message, let alone had one answered.
     *
     * The refusal is `LoadRunner`'s own, because the name is simply not in scope, which is the same answer
     * a template reading anything nobody seeds gets.
     */
    @Test
    fun `a phase is never handed the captures of a phase it did not wait for`() {
        val clock = FakeClock()
        val lanes = lanes(clock)
        val host = FakeHost(clock, lanes)

        val refused =
            assertFailsWith<LoadRefused> {
                LoadSetRunner(host, clock = clock).run(
                    setOf(
                        burst("ask", orders, LoadMatch(11, 11, "8")),
                        burst("quote", quoteRequests, LoadMatch(131, 131, "S")).copy(capture = mapOf("offer" to 133)),
                        burst("book", booksTheOffer, LoadMatch(11, 11, "8")).copy(after = 1),
                    ),
                )
            }

        assertTrue(refused.message!!.contains("\${offer}"), refused.message)
        assertTrue(
            lanes.none { lane -> lane.sent.any { it.contains("35=AJ") } },
            "and nothing was booked at a price nobody had quoted",
        )
    }

    /**
     * **The set stops on the lowest-numbered phase that failed, not on the first one to get there.**
     *
     * With the phases in a queue those were always the same phase. Running two at once they are not: phase
     * 2 here issues for a second and phase 1 for three, so phase 2 reaches its verdict two seconds before
     * phase 1 reaches its own. A note read off whichever failure landed first would send the reader of the
     * record to phase 2, and `LoadRecord.verdict` would meanwhile name phase 1, which reads as the set
     * disagreeing with itself about what went wrong.
     *
     * Nothing is answered, so both phases fail on requests that went unanswered, and phase 3 waits for
     * both of them because it names no trigger.
     */
    @Test
    fun `the set stops on the lowest-numbered failure, not the first one to land`() {
        val clock = FakeClock()
        // A venue that answers nothing, so both phases fail and neither of them quickly.
        val lanes = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val host = FakeHost(clock, lanes)

        val record =
            clock.withParties(2) {
                LoadSetRunner(host, clock = clock).run(
                    setOf(
                        plan("orders", orders, LoadMatch(11, 11, "8"), perSecond = 200, forMs = 3_000),
                        plan("quotes", quoteRequests, LoadMatch(131, 131, "S"), perSecond = 500, forMs = 1_000).copy(after = 1),
                        burst("later", orders, LoadMatch(11, 11, "8")),
                    ),
                )
            }

        assertTrue(
            assertNotNull(record.phases[1].finishedAt) < assertNotNull(record.phases[0].finishedAt),
            "phase 2 was the first to fail, which is what makes this worth pinning",
        )
        assertEquals(
            "phase 1 did not pass and the set stops on failure",
            record.phases[2].note,
            "the phase the set stops on is the lowest-numbered one that failed",
        )
        assertEquals(1, record.verdict.phase, "and the verdict names the same phase the note does")
        assertEquals(1, record.exitCode)
    }

    private fun burst(
        id: String,
        template: LoadTemplate,
        match: LoadMatch,
    ) = plan(id, template, match, perSecond = 1).copy(shape = LoadShape.Burst(4), requested = 4)

    private fun setOf(vararg phases: LoadPlan) =
        LoadSet.Planned(
            id = "set-1",
            label = "Two at once",
            name = "two-at-once",
            onFailure = OnFailure.STOP,
            seed = mapOf("run" to "t1"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            phases = phases.toList(),
        )

    /** Everything it asked for went, everything that went was answered, and nothing went late. */
    private fun assertHeldItsSchedule(report: LoadReport) {
        assertEquals(report.issue.requested, report.issue.leftSocket, "${report.id}: everything it asked for left the socket")
        assertEquals(report.issue.requested, report.replies.matched, "${report.id}: and every one of them was answered")
        assertEquals(0L, report.replies.strays, "${report.id}: the other phase's replies are not this one's business")
        val rate = assertNotNull(report.rate, report.id)
        assertEquals(emptyList(), rate.shortfalls, "${report.id}: the schedule was held")
        assertEquals(
            0L,
            rate.maxLagMs,
            "${report.id}: a message went late, so the phase beside it dragged this clock past a mark",
        )
        assertEquals(LoadReport.RateVerdict.HELD, report.verdict.rate, report.id)
        assertEquals(0, report.verdict.exitCode, report.id)
    }

    /** Waits, in real time, until something has gone down a lane. A hung test says nothing, so this says it. */
    private fun awaitSent(what: String, lanes: List<FakeLane>) {
        val deadline = System.nanoTime() + STALL_NANOS
        while (lanes.sumOf { it.sent.size } == 0) {
            check(System.nanoTime() < deadline) { "waited ${STALL_NANOS / 1_000_000_000}s for $what" }
            Thread.sleep(1)
        }
    }

    private companion object {
        /**
         * How long the venue takes over one message, in real milliseconds, so that a teardown running
         * from the wrong place would have a window it could not miss. Nothing else waits on it.
         */
        const val SLOW_ANSWER_MS = 50L

        /** How long a real-time wait sits before it says what it was waiting for. */
        const val STALL_NANOS = 10_000_000_000L
    }
}
