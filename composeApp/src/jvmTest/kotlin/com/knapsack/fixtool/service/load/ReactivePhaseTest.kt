package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.WireDirection
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A phase that reacts to another one, running.**
 *
 * The worked example is the design note's: a client asks for 200 quotes, a dealer answers each one as the
 * request reaches it, and the client hits each quote as the answer reaches it. Four of the 200 requests are
 * never answered, so phase 1's settle window runs its full sixty seconds either way. What reactive changes
 * is that the 196 chains that worked finish inside the first second instead of a minute later, and that is
 * the assertion this file exists for.
 *
 * **The fake clock and the barrier.** Virtual time moves when a socket is written or a phase sleeps, and
 * phase 1's sixty-second settle window is sixty seconds of virtual time and microseconds of real time. Left
 * alone it would run to its end before the threads answering its replies had been scheduled at all, and the
 * chains would then be dated inside a window they in fact finished before. So the host holds its first
 * settle poll until the chains that can complete have, which is the same job [FakeClock.concurrently] does
 * for two pacers and the only thing a virtual clock cannot do for itself.
 */
class ReactivePhaseTest {
    private val ask = LoadTemplate("Ask", listOf(35 to "R", 131 to "RFQ-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val quote = LoadTemplate("Quote", listOf(35 to "AJ", 117 to "\${quoteId}", 133 to "1.09010"))
    private val hit = LoadTemplate("Hit", listOf(35 to "AJ", 11 to "HIT-\${run}-\${messageIndex}", 117 to "\${quoteId}"))

    // -------------------------------------------------------------------------------------------------
    // The exit criterion: the RFQ set from the design note, end to end
    // -------------------------------------------------------------------------------------------------

    /**
     * **Three phases, two of them reactive, and 196 chains that do not wait for the four that failed.**
     *
     * Phase 1 bursts 200 QuoteRequests and keeps the QuoteID off each answer. Phase 2 is the dealer,
     * reacting to phase 1: one Quote per answered request, addressed by the QuoteID phase 1 kept, which is
     * the capture path a reactive phase reads through. Phase 3 is the client again, reacting to phase 2.
     *
     * Four requests go unanswered. They cost phase 1 its whole settle window, exactly as they did before,
     * and they cost the two phases behind it four messages each, reported as messages that were never sent
     * rather than hidden by asking for fewer. Everything else is finished inside the first second.
     */
    @Test
    fun `two reactive phases chain 196 of 200 messages through, and none waits for the four that failed`() {
        val clock = FakeClock()
        val silent = setOf(3, 42, 111, 200)
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        // The client's leg: a Quote back for every QuoteRequest but the four the venue never answers, and
        // an ExecutionReport for every hit it sends later.
        client.forEach { lane ->
            lane.answers("R") { wire -> if (indexOf(wire, 131) in silent) emptyList() else quoteFor(wire) }
            lane.answers("AJ", ::fill)
        }
        // The dealer's leg: the client accepting the quote it was sent.
        dealer.forEach { lane -> lane.answers("AJ", ::accepted) }

        val chained = AtomicBoolean()
        val hits = sendsOf(client, "AJ")
        val host =
            FakeHost(
                clock,
                client,
                lanesByProfile = mapOf(CLIENT to client, DEALER to dealer),
                onSleep = {
                    if (chained.compareAndSet(false, true)) awaitCount("the 196 chains to reach phase 3", 196) { hits.get() }
                },
            )

        val record = LoadSetRunner(host, clock = clock).run(rfq(200))

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE, LoadStatus.DONE), record.phases.map { it.status })
        val (asked, quoted, hitBack) = record.phases

        assertEquals(200L, asked.issue.leftSocket, "phase 1 asked for all two hundred")
        assertEquals(196L, asked.replies.matched, "and four of them were never answered")
        assertEquals(4L, asked.replies.unmatched)

        assertEquals(200L, quoted.issue.requested, "a reactive phase asks for what its trigger asked for")
        assertEquals(196L, quoted.issue.leftSocket, "one quote per request the venue answered")
        assertEquals(4L, quoted.issue.unaddressable, "and four the venue never gave it anything to answer")
        assertEquals(196L, quoted.replies.matched)
        assertEquals(200L, hitBack.issue.requested)
        assertEquals(196L, hitBack.issue.leftSocket)
        assertEquals(4L, hitBack.issue.unaddressable)
        assertEquals(196L, hitBack.replies.matched)

        // What was not sent, and why, in the words the report prints. A reactive phase that captures
        // nothing has no capture name to blame, so the phase that never answered is the name it gives.
        assertEquals(
            listOf("reply to phase 1 · Ask for a quote"),
            quoted.unaddressable.map { it.missing }.distinct(),
        )
        assertEquals(silent, quoted.unaddressable.map { it.index }.toSet())
        assertEquals(listOf("reply to phase 2 · Quote it"), hitBack.unaddressable.map { it.missing }.distinct())

        // Every quote went out addressed by the QuoteID phase 1 kept for that very message, which is the
        // whole of the capture path: the buffer carries an index and the table carries the value.
        assertEquals(
            (1..200).filterNot { it in silent }.map { "QID-RFQ-t1-$it" }.toSet(),
            dealer.flatMap { it.sent }.mapNotNull { WireTags.tagValue(it, 117) }.toSet(),
        )

        // The point of the whole feature. Phase 1 spends its sixty seconds on four requests that never came
        // back; the chains behind it are finished in the first seconds. The floor is the last phase's own
        // ceiling and not the waiting: 196 messages under a 200/s cap is nine hundred and eighty
        // milliseconds however fast its triggers arrive.
        val started = assertNotNull(asked.issue.firstSendAt)
        assertTrue(
            assertNotNull(hitBack.issue.lastSendAt) - started < CHAINS_WITHIN_MS,
            "the chains took ${assertNotNull(hitBack.issue.lastSendAt) - started}ms, so they waited on the four that failed",
        )
        assertTrue(
            assertNotNull(asked.finishedAt) - started >= SETTLE_MS,
            "phase 1's settle window closed early, so this proves nothing about waiting for it",
        )
    }

    // -------------------------------------------------------------------------------------------------
    // The lane rule
    // -------------------------------------------------------------------------------------------------

    /**
     * **Index `i` goes to lane `(i - indexFrom) % lanes`, which is the lane a paced phase would use.**
     *
     * `(i - 1) % lanes` agrees with that only when `(indexFrom - 1) % lanes` is nought, and a reactive
     * phase inherits its trigger's `indexFrom`, so the two part company on the first set that addresses
     * the half another phase left. Here the phases count from 2 over two lanes, which inverts them: under
     * the wrong rule every message of this test goes down the other lane.
     */
    @Test
    fun `a reactive phase puts each index on the lane a paced phase would have put it on`() {
        val clock = FakeClock()
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        client.forEach { it.answers("R", ::quoteFor) }
        dealer.forEach { it.answers("AJ", ::accepted) }
        val host = FakeHost(clock, client, lanesByProfile = mapOf(CLIENT to client, DEALER to dealer))

        LoadSetRunner(host, clock = clock).run(
            planned(
                phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(4))
                    .copy(requested = 4, indexFrom = 2, capture = mapOf("quoteId" to 117)),
                phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered())
                    .copy(requested = 4, indexFrom = 2, after = 1),
            ),
        )

        assertEquals(listOf("RFQ-t1-2", "RFQ-t1-4"), client[0].sent.mapNotNull { WireTags.tagValue(it, 131) })
        assertEquals(listOf("RFQ-t1-3", "RFQ-t1-5"), client[1].sent.mapNotNull { WireTags.tagValue(it, 131) })
        assertEquals(
            listOf("QID-RFQ-t1-2", "QID-RFQ-t1-4"),
            dealer[0].sent.mapNotNull { WireTags.tagValue(it, 117) }.sorted(),
            "the reactive phase's lane 0 is the paced phase's lane 0, which is what indexFrom decides",
        )
        assertEquals(listOf("QID-RFQ-t1-3", "QID-RFQ-t1-5"), dealer[1].sent.mapNotNull { WireTags.tagValue(it, 117) }.sorted())
    }

    // -------------------------------------------------------------------------------------------------
    // The cap: a ceiling, and the seconds it held at and the seconds it waited in
    // -------------------------------------------------------------------------------------------------

    /**
     * **A cap releases at the ceiling and is never judged against it.**
     *
     * Two hundred triggers are all waiting by the time the phase starts issuing, so the only thing
     * deciding when each message goes is the cap: one every ten milliseconds, aggregated over both lanes
     * because the tokens belong to the phase and not to a lane. Every full second reaches the ceiling, so
     * none was starved, and the verdict declines to score any of it: sitting under a ceiling is normal
     * and would read as having missed a schedule nobody asked for.
     */
    @Test
    fun `a capped reactive phase releases at its ceiling, and holds there`() {
        val clock = FakeClock()
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        client.forEach { it.answers("R", ::quoteFor) }
        dealer.forEach { it.answers("AJ", ::accepted) }
        val host = FakeHost(clock, client, lanesByProfile = mapOf(CLIENT to client, DEALER to dealer))

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(200))
                        .copy(requested = 200, capture = mapOf("quoteId" to 117)),
                    phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered(cap = 100))
                        .copy(requested = 200, after = 1),
                ),
            )

        val quoted = record.phases[1]
        val rate = assertNotNull(quoted.rate)
        assertEquals(200L, quoted.issue.leftSocket)
        assertTrue(rate.ceiling, "a cap is a ceiling, and the report has to say so or the verdict scores it")
        assertEquals(100, rate.requestedPerSecond)
        assertEquals(emptyList(), rate.shortfalls, "nothing under a ceiling is behind anything")
        assertEquals(ONE_SECOND, rate.heldForMs, "the one full second it had was spent at the cap")
        assertEquals(0L, rate.starvedForMs, "and none of it waiting for a trigger")
        assertEquals(LoadReport.RateVerdict.NOT_APPLICABLE, quoted.verdict.rate)
        assertEquals("n/a, reactive", quoted.rateWord)
        assertEquals(0, record.exitCode)

        // Two hundred at a hundred a second is a shade under two seconds, which is the cap doing the work
        // rather than the triggers, all of which were waiting before the first one went.
        val span = assertNotNull(quoted.issue.spanMs)
        assertTrue(span in 1_900..2_100, "two hundred messages under a 100/s ceiling spanned ${span}ms")
    }

    /**
     * **The `Tally.last` trap: an index nothing fired must not stretch the histogram.**
     *
     * Two of the ten requests go unanswered, so the two messages behind them are never released. The
     * phase learns that only when its trigger's settle window closes, which here is five seconds after
     * the last message it did send. Recorded through the ordinary path those two would date the run's
     * last issue at the close, the per-second histogram would be sized through it, and every idle second
     * in between would come back as a second the cap was starved in: held 2s and starved 3s, in a phase
     * that was never starved for a moment.
     *
     * So they are counted and never dated, and `heldForMs + starvedForMs` is the whole of it: one full
     * second, spent at the cap.
     */
    @Test
    fun `an index no trigger fired is counted and never dated, so an idle wait is not a starved one`() {
        val clock = FakeClock()
        val silent = setOf(3, 7)
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        client.forEach { lane ->
            lane.answers("R") { wire -> if (indexOf(wire, 131) in silent) emptyList() else quoteFor(wire) }
        }
        dealer.forEach { it.answers("AJ", ::accepted) }
        val paced = AtomicBoolean()
        val quotes = sendsOf(dealer, "AJ")
        val host =
            FakeHost(
                clock,
                client,
                lanesByProfile = mapOf(CLIENT to client, DEALER to dealer),
                onSleep = {
                    if (paced.compareAndSet(false, true)) awaitCount("the eight quotes the cap lets through", 8) { quotes.get() }
                },
            )

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(10))
                        .copy(requested = 10, capture = mapOf("quoteId" to 117), settleMs = 5_000),
                    phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered(cap = 4))
                        .copy(requested = 10, after = 1),
                ),
            )

        val quoted = record.phases[1]
        val rate = assertNotNull(quoted.rate)
        assertEquals(2L, quoted.issue.unaddressable)
        assertEquals(8L, quoted.issue.leftSocket)
        assertEquals(0L, rate.starvedForMs, "the wait for the trigger's window to close was counted as starvation")
        assertEquals(ONE_SECOND, rate.heldForMs)
        assertEquals(
            ONE_SECOND,
            rate.heldForMs + rate.starvedForMs,
            "the histogram was sized through the close rather than through the last message that went",
        )
    }

    /**
     * **The other half of the same number: a phase below its ceiling is starved, and says so.**
     *
     * The venue takes a second to answer each request, so the phase reacting to it releases one message a
     * second against a cap of four. Not one full second reached the ceiling, and every one of them is
     * counted as a second spent waiting rather than as a second behind a schedule, because a phase under
     * its ceiling is waiting and never failing. Nothing here asserts on the last second, which is partial
     * by construction and left out of both numbers.
     */
    @Test
    fun `a reactive phase whose triggers arrive slowly is starved, not short`() {
        val clock = FakeClock()
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val answered = AtomicInteger()
        val quotes = sendsOf(dealer, "AJ")
        client.forEach { lane ->
            lane.answers("R") { wire ->
                // The venue takes a second, and the phase reacting to us has caught up before it does, so
                // a message it released is dated in the second it was released in rather than in whichever
                // second the clock had run on to while its thread waited to be scheduled.
                awaitCount("the dealer to answer trigger ${answered.get()}", answered.get()) { quotes.get() }
                answered.incrementAndGet()
                clock.advance(NANOS_PER_SECOND)
                quoteFor(wire)
            }
        }
        dealer.forEach { it.answers("AJ", ::accepted) }
        val host = FakeHost(clock, client, lanesByProfile = mapOf(CLIENT to client, DEALER to dealer))

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(6))
                        .copy(requested = 6, capture = mapOf("quoteId" to 117)),
                    phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered(cap = 4))
                        .copy(requested = 6, after = 1),
                ),
            )

        val rate = assertNotNull(record.phases[1].rate)
        assertEquals(6L, record.phases[1].issue.leftSocket)
        assertEquals(0L, rate.heldForMs, "one a second against a cap of four never reached the ceiling")
        assertTrue(rate.starvedForMs >= 4 * ONE_SECOND, "it waited on its trigger for ${rate.starvedForMs}ms of full seconds")
        assertEquals(emptyList(), rate.shortfalls, "waiting on a trigger is not falling behind a schedule")
        assertEquals(0, record.exitCode, "and it is not a failure either")
    }

    // -------------------------------------------------------------------------------------------------
    // What happens when the trigger never runs, and when there is no set at all
    // -------------------------------------------------------------------------------------------------

    /**
     * **A reactive phase whose trigger was skipped ends rather than waiting for ever.**
     *
     * This is the deadlock the trigger buffer exists to remove, now with a phase that really does read
     * one. Phase 1 fails because nothing answers it, so phase 2 is skipped before its matcher is ever
     * built and nothing on the reply path could ever close phase 3's buffer. The close belongs to phase
     * 2's exit instead, and phase 3 is skipped for the same reason and says so in its own voice.
     */
    @Test
    fun `a reactive phase whose trigger never ran is skipped rather than left waiting`() {
        val clock = FakeClock()
        val client = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val dealer = (1..2).map { FakeLane(it, clock, { emptyList() }) }
        val host = FakeHost(clock, client, lanesByProfile = mapOf(CLIENT to client, DEALER to dealer))

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(4))
                        .copy(requested = 4, capture = mapOf("quoteId" to 117), settleMs = 1_000),
                    phase(CLIENT, "Ask again", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(4)).copy(requested = 4),
                    phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered())
                        .copy(requested = 4, after = 2),
                ),
            )

        assertEquals(listOf(LoadStatus.DONE, LoadStatus.SKIPPED, LoadStatus.SKIPPED), record.phases.map { it.status })
        assertEquals("phase 2 did not run, so nothing would have fired this one", record.phases[2].note)
        assertTrue(dealer.all { it.sent.isEmpty() }, "nothing was quoted for a request nobody made")
    }

    /** A reactive phase is released by an earlier phase, and only a set has one. Said before a lane opens. */
    @Test
    fun `a reactive plan handed to a single run is refused`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, { emptyList() }) }

        val refused =
            assertFailsWith<LoadRefused> {
                LoadRunner(FakeHost(clock, lanes), clock = clock).run(
                    phase(CLIENT, "Quote it", ask, LoadMatch(131, 131, "S"), LoadShape.Triggered()).copy(requested = 4),
                )
            }

        assertEquals("this phase reacts to an earlier one, and only a phase of a set has one to react to", refused.message)
        assertTrue(lanes.all { it.sent.isEmpty() })
    }

    // -------------------------------------------------------------------------------------------------
    // The rig
    // -------------------------------------------------------------------------------------------------

    /** The design note's set: ask, quote every answer, hit every quote. */
    private fun rfq(count: Int) =
        planned(
            phase(CLIENT, "Ask for a quote", ask, LoadMatch(131, 131, "S"), LoadShape.Burst(count))
                .copy(requested = count.toLong(), capture = mapOf("quoteId" to 117), settleMs = SETTLE_MS),
            phase(DEALER, "Quote it", quote, LoadMatch(117, 117, "AI"), LoadShape.Triggered())
                .copy(requested = count.toLong(), after = 1, settleMs = SETTLE_MS),
            phase(CLIENT, "Respond", hit, LoadMatch(11, 11, "8"), LoadShape.Triggered(cap = 200))
                .copy(requested = count.toLong(), after = 2, settleMs = 30_000),
        )

    /**
     * A phase's plan as [LoadSet.plan] would fold it, which is what the runner is handed. Built by hand
     * rather than through a resolver, because what is under test is the runner and not the fold: a
     * reactive phase's `requested` and `indexFrom` are its trigger's, and every caller sets them so.
     */
    private fun phase(
        profile: String,
        label: String,
        template: LoadTemplate,
        match: LoadMatch,
        shape: LoadShape,
    ) = LoadPlan(
        id = "rfq",
        label = label,
        template = template,
        profileId = profile,
        profileName = profile,
        shape = shape,
        match = match,
        settleMs = 2_000,
        seed = mapOf("run" to "t1"),
        storeAndLog = StoreAndLogOverride.FOR_LOAD,
    )

    private fun planned(vararg phases: LoadPlan) =
        LoadSet.Planned(
            id = "set-rfq",
            label = "RFQ reactive",
            name = "rfq-reactive",
            onFailure = OnFailure.STOP,
            seed = mapOf("run" to "t1"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            phases = phases.toList(),
        )

    /** A Quote for a QuoteReqID, carrying the QuoteID the next phase addresses it by. */
    private fun quoteFor(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 131) ?: return emptyList()
        return listOf("8=FIX.4.4|35=S|49=VENUE|131=$id|117=QID-$id|133=1.09010|")
    }

    /** The client accepting a Quote, which is what a dealer's message draws back. */
    private fun accepted(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 117) ?: return emptyList()
        return listOf("8=FIX.4.4|35=AI|49=CLIENT|117=$id|297=5|")
    }

    /** An ExecutionReport for a hit, which is what the last leg of the chain draws back. */
    private fun fill(wire: String): List<String> {
        val id = WireTags.tagValue(wire, 11) ?: return emptyList()
        return listOf("8=FIX.4.4|35=8|49=VENUE|11=$id|39=0|")
    }

    /** The message index out of an id the templates build as `PREFIX-run-index`. */
    private fun indexOf(wire: String, tag: Int): Int = WireTags.tagValue(wire, tag)?.substringAfterLast('-')?.toIntOrNull() ?: -1

    /**
     * **Messages of one type counted as they are dated**, which is what a barrier has to wait for.
     *
     * A lane records a message in `sent` before it stamps it, so a barrier on that count releases the
     * clock while the last stamp is still to be taken, and that message is then dated wherever the clock
     * has run on to. The stamp carries the time it was made, so counting stamps is counting moments that
     * have already happened.
     */
    private fun sendsOf(lanes: List<FakeLane>, msgType: String): AtomicInteger {
        val seen = AtomicInteger()
        lanes.forEach { lane ->
            lane.addStampListener { stamp ->
                if (stamp.direction == WireDirection.SEND && WireTags.msgType(stamp.wire) == msgType) seen.incrementAndGet()
            }
        }
        return seen
    }

    /**
     * **Real time waiting for the threads virtual time cannot see.**
     *
     * The clock moves when a socket is written or a phase sleeps, and neither of those is what a phase
     * reacting to another spends its time on: it is waiting on a queue. So a test that wants a virtual
     * second to mean what it says has to hold the thing moving the clock until the threads that are not
     * have caught up. Says what it was waiting for when they do not, because a hung test says nothing.
     */
    private fun awaitCount(what: String, want: Int, have: () -> Int) {
        val deadline = System.nanoTime() + STALL_NANOS
        while (have() < want) {
            check(System.nanoTime() < deadline) { "waited ${STALL_NANOS / NANOS_PER_SECOND}s for $what: ${have()} of $want" }
            Thread.sleep(1)
        }
    }

    private companion object {
        const val CLIENT = "client"
        const val DEALER = "dealer"
        const val ONE_SECOND = 1_000L
        const val SETTLE_MS = 60_000L

        /**
         * How long the 196 chains may take against phase 1's sixty-second window. Twice what the last
         * phase's 200/s ceiling costs it, because the point is the order of magnitude and not the
         * millisecond: a minute of waiting would fail this by a factor of thirty.
         */
        const val CHAINS_WITHIN_MS = 2_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val STALL_NANOS = 30 * NANOS_PER_SECOND
    }
}
