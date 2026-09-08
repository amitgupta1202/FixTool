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
import java.io.File
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

    /**
     * Two phases that a *single* matcher could not tell apart: both wait for a `35=8` on tag 11.
     *
     * The quote-then-hit pair above is answered by the type check alone, so a reply landing in the wrong
     * phase never reaches the router's "an id nothing here issued" branch. Orders and cancels do reach it,
     * and that is the shape the router was built for.
     */
    private val order = LoadTemplate("Orders", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))
    private val cancel =
        LoadTemplate(
            "Cancels",
            listOf(35 to "F", 11 to "CXL-\${run}-\${messageIndex}", 41 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"),
        )

    private fun orderPhases() =
        listOf(
            spec("Send the orders", "Orders", LoadMatch(11, 11, "8")),
            spec("Cancel them", "Cancels", LoadMatch(11, 11, "8")),
        )

    private fun fill(id: String) = "8=FIX.4.4\u000135=8\u000149=VENUE\u000111=$id\u000139=2\u0001"

    /**
     * A venue that answers every order and every cancel with a `35=8`.
     *
     * [swallow] is the ClOrdID whose fill is kept back and handed over beside the first cancel, so it
     * lands on the socket while phase 2 is the live phase. [ghost] is a fill for an id nobody ever sent,
     * released the same way, which is a real stray and belongs to whichever phase was running.
     */
    private fun ordersThenCancels(
        lane: FakeLane? = null,
        swallow: String? = null,
        ghost: String? = null,
    ): (String) -> List<String> =
        { wire ->
            val id = WireTags.tagValue(wire, 11) ?: "?"
            when (WireTags.msgType(wire)) {
                "D" ->
                    if (id == swallow) {
                        lane?.withhold(fill(id))
                        emptyList()
                    } else {
                        listOf(fill(id))
                    }
                "F" ->
                    listOf(fill(id)) +
                        lane?.takeWithheld().orEmpty() +
                        (ghost?.takeIf { id.endsWith("-1") }?.let { listOf(fill(it)) } ?: emptyList())
                else -> emptyList()
            }
        }

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
                "Orders" -> order
                "Cancels" -> cancel
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

    /**
     * **The router's motivating case**: two phases waiting for the same reply type on the same tag.
     *
     * Phase 2's matcher answers UNKNOWN here rather than "not a reply", because the fill *is* the shape it
     * waits for and carries an id it never issued. Only after phase 1 has claimed it may anything be
     * counted as a stray, and phase 1 claims it however long after phase 1 ended it arrives.
     */
    @Test
    fun `with both phases matched on the same tag and type, a late fill is still the first phase's`() {
        val clock = FakeClock()
        val lanes =
            (1..2).map { slot ->
                val lane = FakeLane(slot, clock, { emptyList() })
                lane.answer = ordersThenCancels(lane, swallow = "ORD-t1-3")
                lane
            }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned(OnFailure.CONTINUE, orderPhases()))

        assertEquals(1L, record.phases[0].replies.late, "the fill phase 1 asked for, whenever it turned up")
        assertEquals(0L, record.phases[1].replies.strays, "and not a phase 2 stray, which is the number a diagnosis reads")
        assertEquals(0L, record.phases[0].replies.strays)
        assertEquals(8L, record.phases[1].replies.matched, "every cancel answered")
        assertEquals(7L, record.phases[0].replies.matched, "the withheld one was not matched inside phase 1's window")
    }

    /**
     * And a fill for an id nobody ever sent **is** a stray, counted against the phase that was running
     * when it came, because that is the number the "nothing matched" diagnosis reads.
     */
    @Test
    fun `a fill for an id nobody issued is a stray of the phase that was running`() {
        val clock = FakeClock()
        val lanes =
            (1..2).map { slot ->
                val lane = FakeLane(slot, clock, { emptyList() })
                lane.answer = ordersThenCancels(lane, ghost = "GHOST-1")
                lane
            }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned(OnFailure.CONTINUE, orderPhases()))

        assertEquals(1L, record.phases[1].replies.strays, "the ghost fill, released while phase 2 was live")
        assertEquals(0L, record.phases[0].replies.strays, "a phase that had ended is charged nothing")
        assertEquals(8L, record.phases[0].replies.matched)
        assertEquals(8L, record.phases[1].replies.matched, "a stray leaves the phase's own matches alone")
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

    /**
     * **Stopped in the moment between the 202 and phase 1's first send**: every phase is skipped, nothing
     * is stopped and nothing failed, and the verdict must still not read PASSED.
     *
     * This is the reachable one: `POST /load` answers 202 and `POST /loads/<id>/stop` arrives before the
     * runner has looked at phase 1. On the count of failures alone a set that never sent a message came
     * back "PASSED, 0 passed, 3 skipped".
     */
    @Test
    fun `a set stopped before phase one dialled is stopped, and never passed`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, venue()) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(planned(), cancelled = { true })

        assertEquals(listOf(LoadStatus.SKIPPED, LoadStatus.SKIPPED), record.phases.map { it.status })
        assertEquals(LoadRecord.STOPPED_NOTE, record.phases[0].note)
        assertEquals(SetOutcome.STOPPED, record.verdict.outcome)
        assertEquals(1, record.verdict.phase, "the phase the stop landed on")
        assertEquals("2 skipped", record.verdict.counts())
        assertEquals(1, record.exitCode, "a build cannot pass on a set that never sent a message")
        assertEquals(0, lanes.sumOf { it.sent.size }, "and nothing dialled")
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

    // -------------------------------------------------------------------------------------------------
    // Captured values: the venue mints its own ids, and phase 2 has to read them off phase 1's replies
    // -------------------------------------------------------------------------------------------------

    /** A hit that names the quote by the id the venue minted, rather than by one derived from the seed. */
    private val hitByCapture =
        LoadTemplate(
            "Hits",
            listOf(35 to "AJ", 11 to "H-\${run}-\${messageIndex}", 117 to "\${quoteId}", 44 to "\${offer}", 694 to "1"),
        )

    private inner class ResolveOpaque : LoadSet.Resolver {
        override fun profile(key: String): LoadSet.Profile? =
            if (key == "LOADGEN") LoadSet.Profile("p", "LOADGEN", FixConnectionConfig()) else null

        override fun template(key: String, profileId: String?): LoadTemplate? =
            when (key) {
                "Quotes" -> quoteRequest
                "Hits" -> hitByCapture
                else -> null
            }
    }

    private fun opaqueSet(count: Int = 8, hits: Int = 8, indexFrom: Int = 1, capture: Map<String, Int> = mapOf("quoteId" to 117, "offer" to 133)) =
        LoadSet(
            name = "round-trip",
            label = "Round trip",
            seed = mapOf("run" to "t1"),
            storeAndLog = StoreAndLogOverride.FOR_LOAD,
            onFailure = OnFailure.CONTINUE,
            phases =
                listOf(
                    spec("Ask for a quote", "Quotes", LoadMatch(131, 131, "S"), count = count).copy(capture = capture),
                    spec("Hit them", "Hits", LoadMatch(11, 11, "8"), count = hits, indexFrom = indexFrom),
                ),
        ).plan(ResolveOpaque(), seedOverride = emptyMap(), id = "set-1")

    /**
     * The venue mints an id the client cannot derive, which is what every real counterparty does. Phase 2
     * addresses it anyway, because phase 1 kept it at the index phase 2 counts by.
     */
    private fun opaqueVenue(withoutQuoteIdFor: String? = null): (String) -> List<String> =
        { wire ->
            when (WireTags.msgType(wire)) {
                "R" -> {
                    val reqId = WireTags.tagValue(wire, 131)
                    val quoteId = "OPAQUE-" + opaqueId()
                    val fields =
                        listOfNotNull(
                            "8=FIX.4.4",
                            "35=S",
                            "49=VENUE",
                            "131=$reqId",
                            if (reqId == withoutQuoteIdFor) null else "117=$quoteId",
                            "133=1.09010",
                        )
                    listOf(fields.joinToString(SOH) + SOH)
                }
                "AJ" ->
                    listOf(
                        listOf(
                            "8=FIX.4.4",
                            "35=8",
                            "49=VENUE",
                            "11=${WireTags.tagValue(wire, 11)}",
                            "117=${WireTags.tagValue(wire, 117)}",
                            "44=${WireTags.tagValue(wire, 44)}",
                            "39=2",
                        ).joinToString(SOH) + SOH,
                    )
                else -> emptyList()
            }
        }

    @Test
    fun `phase two addresses the ids phase one was given, and books at the price it was quoted`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, opaqueVenue()) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(opaqueSet())

        assertEquals(SetOutcome.PASSED, record.verdict.outcome)
        assertEquals(0, record.exitCode)
        assertEquals(listOf(8L, 8L), record.phases.map { it.replies.matched })
        assertEquals(mapOf("quoteId" to 117, "offer" to 133), record.phases[0].capture?.names)
        assertEquals(mapOf("quoteId" to 8, "offer" to 8), record.phases[0].capture?.captured)
        assertNull(record.phases[1].capture, "a phase that only reads captures keeps none")

        val hits = lanes.flatMap { lane -> lane.sent.filter { WireTags.msgType(it) == "AJ" } }
        assertEquals(8, hits.size)
        assertTrue(hits.all { WireTags.tagValue(it, 117)!!.startsWith("OPAQUE-") }, "the id came off the reply, not the seed")
        assertEquals(8, hits.map { WireTags.tagValue(it, 117) }.toSet().size, "every hit its own quote")
        assertTrue(hits.all { WireTags.tagValue(it, 44) == "1.09010" }, "and at the price the quote carried")
        assertEquals(0L, record.phases[1].issue.unaddressable)
    }

    /**
     * A requested message that was not sent fails the phase. The bar is "every requested message
     * answered", so a hole the tool could not fill is not a smaller proof.
     */
    @Test
    fun `one missing capture is one unaddressable message, named, and the set exits one`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, opaqueVenue(withoutQuoteIdFor = "Q-t1-3")) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(opaqueSet())

        assertEquals(SetOutcome.FAILED, record.verdict.outcome)
        assertEquals(2, record.verdict.phase, "phase 1 answered everything, phase 2 could not ask everything")
        assertEquals(1, record.exitCode)
        assertEquals(mapOf("quoteId" to 7, "offer" to 8), record.phases[0].capture?.captured, "one reply carried no 117")
        assertEquals(LoadReport.Completeness.INCOMPLETE, record.phases[1].verdict.completeness)
        assertEquals(1L, record.phases[1].issue.unaddressable)
        assertEquals(listOf(LoadReport.Unaddressable(3, "quoteId")), record.phases[1].unaddressable)
        assertEquals(7L, record.phases[1].issue.leftSocket, "the other seven went")
        assertEquals(7L, record.phases[1].replies.matched)
    }

    /** What `indexFrom` is for: phase 2 reads the table phase 1 filled at 5 to 8, not at 1 to 4. */
    @Test
    fun `a phase counting from an offset reads the captures kept at those indices`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, opaqueVenue()) }
        val host = FakeHost(clock, lanes)

        val record = LoadSetRunner(host, clock = clock).run(opaqueSet(count = 8, hits = 4, indexFrom = 5))

        assertEquals(SetOutcome.PASSED, record.verdict.outcome)
        assertEquals(0L, record.phases[1].issue.unaddressable)
        val quotes = lanes.flatMap { lane -> lane.sent.filter { WireTags.msgType(it) == "S" } }
        assertEquals(emptyList(), quotes, "the client sends requests, the venue sends quotes")
        val hits = lanes.flatMap { lane -> lane.sent.filter { WireTags.msgType(it) == "AJ" } }
        assertEquals(setOf("H-t1-5", "H-t1-6", "H-t1-7", "H-t1-8"), hits.map { WireTags.tagValue(it, 11) }.toSet())
        assertEquals(4, hits.mapNotNull { WireTags.tagValue(it, 117) }.toSet().size, "four different quotes, all addressable")
    }

    @Test
    fun `the captured values are written beside the phase's other evidence`() {
        val clock = FakeClock()
        val lanes = (1..2).map { FakeLane(it, clock, opaqueVenue()) }
        val host = FakeHost(clock, lanes)
        val dir = File.createTempFile("fixtool-set-capture", "").also { it.delete() }
        try {
            val store = LoadRecordStore(dir.absolutePath)
            val record = LoadSetRunner(host, store, clock = clock).run(opaqueSet())

            val names = assertNotNull(record.phases[0].evidence)
            assertEquals("01-captured.tsv", names.captured)
            assertNull(record.phases[1].evidence?.captured, "a phase that captures nothing writes no file")
            val lines = File(store.directoryFor(record.id), names.captured!!).readLines().filter { it.isNotBlank() }
            assertEquals(8, lines.size)
            assertEquals("1", lines.first().substringBefore('\t'))
            assertTrue(lines.first().contains("\tquoteId=OPAQUE-"), lines.first())
            assertTrue(lines.first().endsWith("\toffer=1.09010"), lines.first())
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The field separator, written as its escape so no control character has to survive a copy. */
private const val SOH = "\u0001"

/** Eight characters no client could have derived, which is what a real venue's QuoteID is. */
private fun opaqueId(): String =
    java.util.UUID
        .randomUUID()
        .toString()
        .take(8)
