package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.ScenarioResult
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
        dir = File.createTempFile("fixtool-runs", "").apply { delete(); mkdirs() }
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

        recorder.observe("CLI", listOf(one))
        recorder.observe("CLI", listOf(one, two))
        recorder.observe("CLI", listOf(one, two))

        assertEquals(2, recorder.size)
        assertEquals(listOf(0, 1), recorder.build(emptyMap(), cap = 0).messages.map { it.index })
    }

    /** One clock for the process, so two sessions' messages go into one arrival order. */
    @Test
    fun `messages from two sessions are recorded in arrival order`() {
        val recorder = RunRecorder()
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
        recorder.observe("CLI", noise + bound)

        val judged = mapOf(bound to StepResult(0, "expect", "steps", passed = true, stepId = "step-1"))
        val built = recorder.build(judged, cap = 4)

        assertEquals(4, built.messages.size)
        assertEquals(7, built.dropped, "and the count is reported, never silent")
        assertTrue(built.messages.last().raw.contains("35=8"), "the bound reply survives the cap")
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
        assertEquals("2", record.result.steps.single { it.kind == "expect" }.tags.single().actual)
        assertEquals(mapOf("step-1" to 1), record.bound)
        assertEquals(3, record.dropped)
        assertTrue(record.messages[0].raw.contains("35=D"), "the bytes are the evidence")
    }

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
    }

    // ----------------------------------------------------------------- fixtures

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
