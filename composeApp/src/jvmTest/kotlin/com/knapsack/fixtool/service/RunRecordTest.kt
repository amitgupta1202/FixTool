package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The record is the artifact**, so what it keeps, what it drops, and whether it survives a restart are
 * the claims worth pinning. A suite's evidence cannot live in the tab: entry 2's setup clears the grid,
 * the buffer evicts, and the app may have been closed by the time anybody reads the report.
 */
class RunRecordTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir =
            File.createTempFile("fixtool-runs", "").apply {
                delete()
                mkdirs()
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ----------------------------------------------------------------- the recorder

    /**
     * The flow conflates and the collector sees whole snapshots, so the same message arrives many times.
     * Keyed by uid rather than by the message: `FixMessage` equality behaves like identity only because
     * `quickfix.Message` never overrode `equals`, and a re-parse would silently make two of one message.
     */
    @Test
    fun `repeated snapshots of the same session record each message once`() {
        val recorder = RunRecorder()
        val one = message("D", at = 10, incoming = false)
        val two = message("8", at = 20, incoming = true)

        // An empty session at the start, then the entry's own two messages, seen three times over.
        recorder.observe("CLI", emptyList())
        recorder.observe("CLI", listOf(one))
        recorder.observe("CLI", listOf(one, two))
        recorder.observe("CLI", listOf(one, two))

        assertEquals(2, recorder.size)
        assertEquals(listOf(0, 1), recorder.build(emptyMap(), cap = 0).messages.map { it.index })
    }

    /**
     * **A record is one entry's traffic, not the session's history.**
     *
     * The first snapshot of a session is what was already there. A suite that does not clear a session —
     * a venue pane, typically, since a capture clears the client leg and the book but not the venue's log
     * — would otherwise give entry 12 a record holding all eleven earlier entries, under a header that
     * says "entry 12". Found by reading a real record, not by a test.
     */
    @Test
    fun `history the entry inherited is not part of its record`() {
        val recorder = RunRecorder()
        val history = message("8", at = 1, incoming = true)
        val ours = message("8", at = 20, incoming = true)

        recorder.observe("CLI", listOf(history))
        recorder.observe("CLI", listOf(history, ours))

        val built = recorder.build(emptyMap(), cap = 0)

        assertEquals(listOf(20L), built.messages.map { it.atMicros }, "only what arrived during the entry")
        assertEquals(0, built.dropped, "history is not a message the cap dropped — it was never the entry's")
    }

    /**
     * Except where a verdict pointed at it: under the permissive binding scope an expect may bind a
     * message older than the run, and a record whose `bound` index addressed a message it had discarded
     * would be evidence with the evidence taken out.
     */
    @Test
    fun `a stale message the run actually bound is kept anyway`() {
        val recorder = RunRecorder()
        val stale = message("8", at = 1, incoming = true)
        recorder.observe("CLI", listOf(stale))
        recorder.observe("CLI", listOf(stale, message("W", at = 5, incoming = true)))

        val built = recorder.build(mapOf(stale to StepResult(0, "expect", "steps", passed = true, stepId = "step-1")), cap = 0)

        assertEquals(2, built.messages.size)
        assertEquals(mapOf("step-1" to 0), built.bound, "and the step still points at the message it judged")
    }

    /** One clock for the process, so two sessions' messages go into one arrival order. */
    @Test
    fun `messages from two sessions are recorded in arrival order`() {
        val recorder = RunRecorder()
        recorder.observe("TRADE", emptyList())
        recorder.observe("QUOTE", emptyList())
        recorder.observe("TRADE", listOf(message("D", at = 30, incoming = false)))
        recorder.observe("QUOTE", listOf(message("R", at = 10, incoming = false), message("S", at = 20, incoming = true)))

        val built = recorder.build(emptyMap(), cap = 0)

        assertEquals(listOf("QUOTE", "QUOTE", "TRADE"), built.messages.map { it.session })
    }

    /**
     * **The cap never drops what the report points at.** A repeat over a quote stream is what it exists
     * for, and a record that dropped the bound reply would be a report pointing at nothing.
     */
    @Test
    fun `the cap keeps every judged message and drops the oldest unbound, and says how many`() {
        val recorder = RunRecorder()
        val noise = (1..10).map { message("W", at = it.toLong(), incoming = true) }
        val bound = message("8", at = 11, incoming = true)
        recorder.observe("CLI", emptyList())
        recorder.observe("CLI", noise + bound)

        val judged = mapOf(bound to StepResult(0, "expect", "steps", passed = true, stepId = "step-1"))
        val built = recorder.build(judged, cap = 4)

        assertEquals(4, built.messages.size)
        assertEquals(7, built.dropped, "and the count is reported, never silent")
        assertTrue(
            built.messages
                .last()
                .raw
                .contains("35=8"),
            "the bound reply survives the cap",
        )
        assertEquals(
            listOf(8L, 9L, 10L, 11L),
            built.messages.map { it.atMicros },
            "the cap falls on the unbound remainder, oldest first",
        )
        assertEquals(mapOf("step-1" to 3), built.bound, "and the step still points at its message")
    }

    /** A run-level verdict — the strict-traffic stray — has no step to name, so it binds nothing. */
    @Test
    fun `a stray named by the traffic verdict is kept but binds no step`() {
        val recorder = RunRecorder()
        val stray = message("8", at = 5, incoming = true)
        recorder.observe("CLI", emptyList())
        recorder.observe("CLI", listOf(stray))

        val built = recorder.build(mapOf(stray to StepResult(-1, "traffic", "steps", passed = false)), cap = 1)

        assertEquals(1, built.messages.size)
        assertTrue(built.bound.isEmpty())
    }

    // ----------------------------------------------------------------- the store

    @Test
    fun `a set and its entries survive being written and read back`() {
        val store = RunRecordStore(customDir = dir.absolutePath)
        val set = sampleSet()

        assertTrue(store.begin(set))
        val name = store.write(sampleRecord(set.id))
        assertEquals("01-book-a-trade.json", name)
        store.writeSet(set.withEntry(0) { it.copy(state = RunState.PASSED, record = name, durationMs = 42) })

        val back = assertNotNull(store.readSet(set.id))
        assertEquals(set.label, back.label)
        assertEquals(RunState.PASSED, back.entries[0].state)
        assertEquals(42L, back.entries[0].durationMs)
        assertEquals(RunSource.Saved("nightly"), back.source)
        assertEquals(RunPolicy(stopOnFirstFailure = true, pauseBetweenMs = 500), back.policy)

        val record = assertNotNull(store.readEntry(set.id, 1))
        assertEquals("book-a-trade", record.scenarioName)
        assertEquals(2, record.messages.size)
        assertTrue(record.result.passed)
        assertEquals(
            "2",
            record.result.steps
                .single { it.kind == "expect" }
                .tags
                .single()
                .actual,
        )
        assertEquals(mapOf("step-1" to 1), record.bound)
        assertEquals(3, record.dropped)
        assertTrue(record.messages[0].raw.contains("35=D"), "the bytes are the evidence")
    }

    /**
     * **After a fan-out, keep what a reader will actually open.** Fifty lanes of order flow is fifty copies
     * of the same three messages: the failures are what the run is for, one passing lane answers "what does
     * a good one look like", and the rest keep their verdict and their counts while their messages go —
     * with `dropped` set, so a trimmed record says so rather than looking like a lane that saw nothing.
     */
    @Test
    fun `trimming keeps every failure, one passing specimen, and the counts of the rest`() {
        val store = RunRecordStore(customDir = dir.absolutePath)
        val set =
            sampleSet().copy(
                id = "fan",
                source = RunSource.FanOut("sc-1", "prof-1"),
                entries =
                    listOf(
                        laneEntry(1, RunState.PASSED),
                        laneEntry(2, RunState.FAILED),
                        laneEntry(3, RunState.PASSED),
                        laneEntry(4, RunState.PASSED),
                    ),
            )
        store.begin(set)
        (1..4).forEach { n -> store.write(sampleRecord("fan").copy(entry = n, scenarioName = "book-a-trade")) }
        store.writeSet(set)

        val trimmed = store.trimToSpecimens(set)

        assertEquals(2, trimmed, "lanes 3 and 4 — lane 1 is the specimen and lane 2 is a failure")
        assertEquals(2, assertNotNull(store.readEntry("fan", 1)).messages.size, "the reference lane is whole")
        assertEquals(2, assertNotNull(store.readEntry("fan", 2)).messages.size, "and so is the failure")
        val cut = assertNotNull(store.readEntry("fan", 4))
        assertTrue(cut.messages.isEmpty())
        assertEquals(5, cut.dropped, "3 the cap had already taken, plus the 2 this took")
        assertTrue(cut.result.passed, "the verdict and the timing stay — only the bytes go")
        assertEquals(3_104L, cut.durationMs)
    }

    private fun laneEntry(slot: Int, state: RunState) =
        RunEntry(
            scenarioId = "sc-1",
            scenarioName = "book-a-trade",
            lane = Lane(slot, "L [$slot]", "L$slot", "q$slot"),
            state = state,
            record = "%02d-book-a-trade.json".format(slot),
        )

    /** Twenty sets of twelve entries is real disk, so the directory is the retention, not the tab. */
    @Test
    fun `pruning keeps the most recent sets and drops the rest`() {
        val store = RunRecordStore(customDir = dir.absolutePath)
        (1..5).forEach { n -> store.begin(sampleSet().copy(id = "set-$n", startedAt = n.toLong())) }

        store.prune(keep = 2)

        assertEquals(listOf("set-5", "set-4"), store.listSets().map { it.id }, "newest first, and only what was kept")
        assertNull(store.readSet("set-1"))
    }

    /** The record's own shape, through JSON and back — Phase 2's viewer reads exactly what CI was handed. */
    @Test
    fun `a record round-trips through json`() {
        val record = sampleRecord("nightly")
        val back = RunRecordCodec.fromJson(Json.parseToJsonElement(RunRecordCodec.toJson(record).toString()).jsonObject)

        assertEquals(record.scenarioId, back.scenarioId)
        assertEquals(record.messages, back.messages)
        assertEquals(record.bound, back.bound)
        assertEquals(record.dropped, back.dropped)
        assertEquals(record.durationMs, back.durationMs)
        assertEquals(record.result.passed, back.result.passed)
        assertEquals(record.result.steps.map { it.kind }, back.result.steps.map { it.kind })
        assertEquals(record.result.steps.map { it.latencyMs }, back.result.steps.map { it.latencyMs })
        // What was asserted, beside what came back — the half of the evidence a report alone does not carry.
        assertEquals(record.scenario?.name, back.scenario?.name)
        assertEquals(record.scenario?.steps?.size, back.scenario?.steps?.size)
        assertEquals(
            (record.scenario?.steps?.last() as ScenarioStep.Expect)
                .expectation.fields
                .single()
                .tag,
            (back.scenario?.steps?.last() as ScenarioStep.Expect)
                .expectation.fields
                .single()
                .tag,
        )
    }

    // ----------------------------------------------------------------- the record, read back as messages

    /**
     * **A record's bytes, back as messages.** The grid takes a list and a tint map and knows nothing about
     * where either came from, which is the only reason an entry that ran an hour ago can be read on the
     * same surface as the traffic arriving now.
     */
    @Test
    fun `a record parses back into messages the grid can show, tinted by its own bound map`() {
        val record = sampleRecord("nightly")

        val parsed = RunRecordMessages.of(record, FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4))

        assertEquals(2, parsed.messages.size)
        assertEquals("D", parsed.messages[0].messageType)
        assertEquals(FixMessage.Direction.INCOMING, parsed.messages[1].direction)
        assertTrue(parsed.messages[0].wireRaw!!.contains("11=ORD"), "the bytes are the ones the record kept")
        // The tint is the record's own: stepId -> index, resolved to the object the grid will be handed.
        assertEquals(1, parsed.judged.size)
        val (message, step) = parsed.judged.entries.single()
        assertEquals(parsed.messages[1], message, "the expect judged the reply, not the order")
        assertEquals("step-1", step.stepId)
    }

    /**
     * A message whose wire order was never known keeps its display form and gets **no** `wireRaw` — the
     * same answer a live message with no bytes gives, so the surfaces that need a byte order refuse it by
     * name instead of asserting against an order nobody observed.
     */
    @Test
    fun `a message with no known wire order is shown without pretending to have bytes`() {
        val record =
            sampleRecord("nightly").copy(
                messages = listOf(RecordedMessage(0, "CLI", incoming = true, atMicros = 1, raw = "8=FIX.4.4|35=8|39=2|", wireOrderKnown = false)),
                bound = emptyMap(),
            )

        val parsed = RunRecordMessages.of(record, FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4))

        assertEquals(1, parsed.messages.size)
        assertNull(parsed.messages.single().wireRaw, "no order was observed, so none is handed on")
    }

    /**
     * **A pipe inside a value, on the branch that substitutes pipes.** A record that fell back to the
     * display form has no SOH, so `toWireFixMessage` turns every `|` into a delimiter — and a Text field
     * carrying one would be read as a short message plus a segment that is silently dropped. Counted as
     * unreadable rather than shown as a message that says something the venue never said.
     */
    @Test
    fun `a display-form message whose reading is disproved is not shown as a shorter one`() {
        val record =
            sampleRecord("nightly").copy(
                messages =
                    listOf(
                        RecordedMessage(
                            index = 0,
                            session = "CLI",
                            incoming = true,
                            atMicros = 1,
                            raw = "8=FIX.4.4|35=8|39=8|58=Rejected|insufficient margin|",
                            wireOrderKnown = false,
                        ),
                    ),
                bound = emptyMap(),
            )

        val parsed = RunRecordMessages.of(record, FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4))

        assertEquals(0, parsed.messages.size, "the reading was disproved, so nothing is asserted about it")
    }

    /** The same bytes in wire order are unambiguous by construction, and still read. */
    @Test
    fun `the same message with real SOH bytes is read, pipe and all`() {
        val soh = "\u0001"
        val record =
            sampleRecord("nightly").copy(
                messages =
                    listOf(
                        RecordedMessage(
                            index = 0,
                            session = "CLI",
                            incoming = true,
                            atMicros = 1,
                            raw = "8=FIX.4.4$soh" + "35=8$soh" + "39=8$soh" + "58=Rejected|insufficient margin$soh",
                            wireOrderKnown = true,
                        ),
                    ),
                bound = emptyMap(),
            )

        val parsed = RunRecordMessages.of(record, FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4))

        assertEquals(1, parsed.messages.size)
        assertEquals("Rejected|insufficient margin", parsed.messages.single().valueOfTag(58))
    }

    // ----------------------------------------------------------------- fixtures

    /**
     * **Every source survives the round trip.** `FanOut` did not: the writer put `"fanOut"` and the reader
     * matched `"fanout"`, so a fan-out set came back as `Selected(emptyList())` and the rail — which gates
     * its whole p50/p95/max report on `source is RunSource.FanOut` — showed nothing for a set reopened
     * from Recent runs. One variant was wrong and nothing failed, so this covers the whole sealed set
     * rather than the one that broke.
     */
    @Test
    fun `every run source survives a write and a read`() {
        val store = RunRecordStore(customDir = dir.absolutePath)
        val sources =
            listOf(
                RunSource.Saved("nightly"),
                RunSource.Favourites,
                RunSource.Filtered("eur"),
                RunSource.Selected(listOf("sc-1", "sc-2")),
                RunSource.Repeat("sc-1", 20),
                RunSource.Examples("sc-1"),
                RunSource.FanOut("sc-1", "prof-1"),
            )

        sources.forEachIndexed { i, source ->
            val set = sampleSet().copy(id = "set-$i", source = source)
            assertTrue(store.begin(set))
            assertEquals(source, assertNotNull(store.readSet(set.id)).source, "$source did not survive")
        }
    }

    /** A record written before the tag was spelled consistently is recovered, not abandoned. */
    @Test
    fun `a set written with the old camelCase fanOut tag still reads as a fan-out`() {
        val store = RunRecordStore(customDir = dir.absolutePath)
        val set = sampleSet().copy(source = RunSource.FanOut("sc-1", "prof-1"))
        assertTrue(store.begin(set))
        val file = File(store.directoryFor(set.id), "set.json")
        file.writeText(file.readText().replace("\"fanout\"", "\"fanOut\""))

        assertEquals(RunSource.FanOut("sc-1", "prof-1"), assertNotNull(store.readSet(set.id)).source)
    }

    private fun sampleSet() =
        RunSet(
            id = "2026-08-28T09-36-02-nightly",
            label = "nightly — 1 scenario",
            source = RunSource.Saved("nightly"),
            entries = listOf(RunEntry("sc-1", "book-a-trade")),
            policy = RunPolicy(stopOnFirstFailure = true, pauseBetweenMs = 500),
            startedAt = 1_000,
            status = RunSetStatus.RUNNING,
        )

    private fun sampleRecord(setId: String) =
        RunRecord(
            setId = setId,
            entry = 1,
            iteration = 1,
            scenarioId = "sc-1",
            scenarioName = "book-a-trade",
            scenario =
                Scenario(
                    id = "sc-1",
                    name = "book-a-trade",
                    binding = BindScope.THIS_RUN,
                    steps =
                        listOf(
                            ScenarioStep.Send("35=D|11=ORD|", session = "CLI"),
                            ScenarioStep.Expect(
                                session = "CLI",
                                expectation =
                                    Expectation(
                                        messageType = "8",
                                        fields = listOf(FieldExpectation(39, Matcher.Exact("2"))),
                                    ),
                            ),
                        ),
                ),
            startedAt = 1_724_838_075_201,
            durationMs = 3_104,
            result =
                ScenarioResult(
                    scenario = "book-a-trade",
                    passed = true,
                    steps =
                        listOf(
                            StepResult(0, "send", "steps", passed = true, detail = "35=D|", latencyMs = 1),
                            StepResult(
                                1,
                                "expect",
                                "steps",
                                passed = true,
                                detail = "messageType=8",
                                tags = listOf(TagResult(39, "exact 2", "2", "2", passed = true)),
                                stepId = "step-1",
                                latencyMs = 214,
                            ),
                        ),
                    durationMs = 3_104,
                ),
            messages =
                listOf(
                    RecordedMessage(0, "CLI", incoming = false, atMicros = 10, raw = "8=FIX.4.4|35=D|11=ORD|"),
                    RecordedMessage(1, "CLI", incoming = true, atMicros = 224, raw = "8=FIX.4.4|35=8|39=2|"),
                ),
            bound = mapOf("step-1" to 1),
            dropped = 3,
        )

    private fun message(type: String, at: Long, incoming: Boolean) =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = if (incoming) FixMessage.Direction.INCOMING else FixMessage.Direction.OUTGOING,
            rawMessage = "35=$type|",
            messageType = type,
            quickfixMessage = Message(),
            captureTimeMicros = at,
            wireRaw = "35=$type|",
        )
}
