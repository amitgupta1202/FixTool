package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.load.SetOutcome
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.service.WireTags
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A set over the same fakes the single run uses**: two phases that address each other from one seed, the
 * two policies, a reply that arrives in the wrong phase, and a set stopped by hand.
 */
class LoadSetRunnerTest {
    private val quoteRequest =
        LoadTemplate("Quotes", listOf(35 to "R", 131 to "Q-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val hit =
        LoadTemplate(
            "Hits",
            listOf(35 to "AJ", 11 to "H-\${run}-\${messageIndex}", 117 to "Q-\${run}-\${messageIndex}", 694 to "1"),
        )

    private fun spec(label: String, template: String, match: LoadMatch, count: Int = 8, indexFrom: Int = 1) =
        LoadPhaseSpec(
            label = label,
            template = template,
            profile = "LOADGEN",
            match = match,
            shape = LoadShape.Burst(count),
            indexFrom = indexFrom,
            settleMs = 2_000,
        )

    private fun set(onFailure: OnFailure = OnFailure.STOP, phases: List<LoadPhaseSpec>? = null) =
        LoadSet(
            name = "round-trip",
            label = "Round trip",
            seed = mapOf("run" to "t1"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            onFailure = onFailure,
            phases =
                phases ?: listOf(
                    spec("Ask for a quote", "Quotes", LoadMatch(131, 131, "S")),
                    spec("Hit them", "Hits", LoadMatch(11, 11, "8")),
                ),
        )

    private inner class Resolve : LoadSet.Resolver {
        override fun profile(key: String): LoadSet.Profile? =
            if (key == "LOADGEN") LoadSet.Profile("p", "LOADGEN", FixConnectionConfig()) else null

        override fun template(key: String, profileId: String?): LoadTemplate? =
            when (key) {
                "Quotes" -> quoteRequest
                "Hits" -> hit
                else -> null
            }
    }

    private fun planned(onFailure: OnFailure = OnFailure.STOP, phases: List<LoadPhaseSpec>? = null) =
        set(onFailure, phases).plan(Resolve(), seedOverride = emptyMap(), id = "set-1")

    /**
     * The venue: a Quote for a 35=R, an ExecutionReport for a 35=AJ, nothing for anything else.
     *
     * [lane] is given so a withheld quote can be handed back beside a later phase's reply, which is how a
     * reply lands on the socket while a different phase is the live one.
     */
    private fun venue(
        lane: FakeLane? = null,
        swallowQuote: String? = null,
        withholdIt: Boolean = false,
    ): (String) -> List<String> =
        { wire ->
            when (WireTags.msgType(wire)) {
                "R" -> {
                    val id = WireTags.tagValue(wire, 131)
                    val reply = "8=FIX.4.435=S49=VENUE131=$id117=Q-$id133=1.09010"
                    when {
                        id != swallowQuote -> listOf(reply)
                        withholdIt -> {
                            lane?.withhold(reply)
                            emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "AJ" ->
                    listOf(
                        "8=FIX.4.435=849=VENUE11=${WireTags.tagValue(wire, 11)}39=2",
                    ) + lane?.takeWithheld().orEmpty()
                else -> emptyList()
            }
        }

    @Test
    fun `two phases run in order on lanes opened once, and share the seed`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned())

        assertEquals(SetOutcome.PASSED, record.verdict.outcome)
        assertEquals(0, record.exitCode)
        assertEquals("2 passed", record.verdict.counts())
        assertEquals(listOf("Ask for a quote", "Hit them"), record.phases.map { it.label })
        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), record.phases.map { it.status })
        assertEquals(listOf(8L, 8L), record.phases.map { it.replies.matched })
        assertEquals(mapOf("run" to "t1"), record.seed)
        assertEquals(LoadRecord.SetInfo("round-trip", OnFailure.STOP), record.set)
        assertEquals("set-1", record.id)
        assertEquals(1, host.laneOpens, "the lanes are opened once for the set, not once per phase")
        assertEquals(1, host.releases, "and released once, after the last phase")
        val sentTypes = lanes.flatMap { lane -> lane.sent.map { WireTags.msgType(it) } }
        assertEquals(setOf("R", "AJ"), sentTypes.toSet())
        val hits = lanes.flatMap { lane -> lane.sent.filter { WireTags.msgType(it) == "AJ" } }
        assertTrue(
            hits.all { WireTags.tagValue(it, 117)!!.startsWith("Q-t1-") },
            "phase 2 addressed what phase 1 minted",
        )
    }

    @Test
    fun `each phase writes its own evidence names, and the record says which`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned())

        assertEquals(
            listOf(LoadReport.Evidence.forPhase(1), LoadReport.Evidence.forPhase(2)),
            record.phases.map { it.evidence },
        )
    }

    @Test
    fun `under STOP a phase that did not pass skips the rest, and says why`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue(swallowQuote = "Q-t1-3")) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned())

        assertEquals(SetOutcome.FAILED, record.verdict.outcome)
        assertEquals(2, record.phases.size)
        assertEquals(1, record.verdict.phase, "the verdict names the phase that failed")
        assertEquals("1 failed, 1 skipped", record.verdict.counts())
        assertEquals(1, record.exitCode)
        assertEquals(LoadReport.Completeness.UNMATCHED, record.phases[0].verdict.completeness)
        assertEquals(LoadStatus.SKIPPED, record.phases[1].status)
        assertEquals("phase 1 did not pass and the set stops on failure", record.phases[1].note)
        assertNull(record.phases[1].verdict.exitCode, "a skipped phase is not judged")
        assertEquals(0L, record.phases[1].issue.leftSocket)
        assertEquals(8L, record.phases[1].issue.requested, "it still carries what would have run")
        assertTrue(lanes.none { lane -> lane.sent.any { WireTags.msgType(it) == "AJ" } }, "phase 2 never dialled")
    }

    @Test
    fun `under CONTINUE every phase runs and the verdict names the first that did not pass`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue(swallowQuote = "Q-t1-3")) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned(OnFailure.CONTINUE))

        assertEquals(SetOutcome.FAILED, record.verdict.outcome)
        assertEquals(1, record.verdict.phase)
        assertEquals("1 passed, 1 failed", record.verdict.counts())
        assertEquals(1, record.exitCode)
        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), record.phases.map { it.status })
        assertEquals(8L, record.phases[1].replies.matched, "phase 2 ran and passed")
    }

    /**
     * The reason the set owns one listener per session rather than one matcher per run: phase 1's replies
     * keep arriving through phase 2, and one matcher per run counted every one of them as a phase 2 stray.
     */
    @Test
    fun `a reply that arrives during a later phase is credited to the phase that asked`() {
        val clock = FakeClock()
        val lanes = lanesAnsweringLate(clock)
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned(OnFailure.CONTINUE))

        assertEquals(1L, record.phases[0].replies.late, "phase 1's late reply, however long after phase 1 ended")
        assertEquals(0L, record.phases[1].replies.strays, "and not a phase 2 stray")
        assertEquals(0L, record.phases[0].replies.strays)
        assertEquals(8L, record.phases[1].replies.matched, "phase 2 is unaffected by a reply that was not its own")
    }

    /** Two lanes that swallow one quote request and answer it beside the first hit of the next phase. */
    private fun lanesAnsweringLate(clock: FakeClock): List<FakeLane> =
        (1..2).map { slot ->
            val lane = FakeLane(slot, clock, { emptyList() })
            lane.answer = venue(lane, swallowQuote = "Q-t1-3", withholdIt = true)
            lane
        }

    @Test
    fun `stopping the set stops the running phase and skips the rest`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phases =
                        listOf(
                            spec("Ask for a quote", "Quotes", LoadMatch(131, 131, "S"), count = 400),
                            spec("Hit them", "Hits", LoadMatch(11, 11, "8")),
                        ),
                ),
                cancelled = { lanes.sumOf { it.sent.size } >= 20 },
            )

        assertEquals(SetOutcome.STOPPED, record.verdict.outcome)
        assertEquals(1, record.verdict.phase)
        assertEquals("1 stopped, 1 skipped", record.verdict.counts())
        assertEquals(1, record.exitCode, "a build cannot pass on a set somebody ended by hand")
        assertEquals(LoadStatus.STOPPED, record.phases[0].status)
        assertEquals(LoadStatus.SKIPPED, record.phases[1].status)
        assertEquals("the set was stopped", record.phases[1].note)
    }

    @Test
    fun `the record is published as each phase lands, so a killed set leaves the phases that finished`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)
        val seen = mutableListOf<List<LoadStatus>>()

        LoadSetRunner(host, clock = clock).run(planned()) { seen += it.phases.map { p -> p.status } }

        assertEquals(
            listOf(LoadStatus.PENDING, LoadStatus.PENDING),
            seen.first(),
            "both phases are drawn before either runs",
        )
        assertEquals(listOf(LoadStatus.DONE, LoadStatus.DONE), seen.last())
        assertTrue(
            seen.any { it == listOf(LoadStatus.DONE, LoadStatus.RUNNING) },
            "phase 1 was complete on disk while phase 2 ran: $seen",
        )
    }

    @Test
    fun `a phase whose profile has no lane is refused before phase one dials`() {
        val clock = FakeClock()
        val host = FakeHost(clock, emptyList())

        val refused = assertFailsWith<LoadRefused> { LoadSetRunner(host, clock = clock).run(planned()) }

        assertTrue(refused.message!!.contains("phase 1"), refused.message)
        assertTrue(refused.message!!.contains("LOGGED_ON"), refused.message)
        assertTrue(host.released, "whatever was opened is put back")
    }

    @Test
    fun `indexFrom lets a phase address the half another left`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)

        val record =
            LoadSetRunner(host, clock = clock).run(
                planned(
                    phases =
                        listOf(
                            spec("Ask for a quote", "Quotes", LoadMatch(131, 131, "S"), count = 8),
                            spec("Hit the second half", "Hits", LoadMatch(11, 11, "8"), count = 4, indexFrom = 5),
                        ),
                ),
            )

        assertEquals(SetOutcome.PASSED, record.verdict.outcome)
        val hits = lanes.flatMap { lane -> lane.sent.filter { WireTags.msgType(it) == "AJ" } }
        assertEquals(
            setOf("Q-t1-5", "Q-t1-6", "Q-t1-7", "Q-t1-8"),
            hits.map { assertNotNull(WireTags.tagValue(it, 117)) }.toSet(),
        )
        assertEquals(5, record.phases[1].indexFrom)
    }
}
