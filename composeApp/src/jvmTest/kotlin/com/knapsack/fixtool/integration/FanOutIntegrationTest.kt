package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.VariableSource
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Many clients, one flow, at once — over real sockets.**
 *
 * The identity half of this has existed since multi-session connect: one profile opens N sessions and
 * `LOADGEN01`…`LOADGEN03` log on as their own counterparties. What fan-out adds is *assignment* — which
 * lane gets which session, what a lane calls itself, and the rule that lets three runs share a process
 * without sharing each other's replies.
 *
 * That last one is the claim worth a socket: each lane binds **its own** ExecutionReport. Three runs whose
 * cursors crossed would still go green — every lane would find *an* answer — which is exactly the failure a
 * unit test cannot tell from success.
 */
class FanOutIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var venuePort = 0
    private val runId = System.nanoTime().toString().takeLast(6)
    private val venueCompId get() = "FANVENUE$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-fanout", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
        connectVenue()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    @Test
    fun `three lanes run at once, each on its own session, each binding its own reply`() {
        val client = connectLanes(count = 3)
        val scenario = save(laneScenario())

        val done = assertNotNull(viewModel.startFanOut(scenario, client.id), "the fan-out should have started")
        assertTrue(awaitCondition(30_000) { viewModel.runRecordStore.readSet(done.id)?.status != RunSetStatus.RUNNING })
        val finished = assertNotNull(viewModel.runRecordStore.readSet(done.id))

        val why =
            finished.entries.indices.joinToString("\n") { i ->
                val r = viewModel.runRecordStore.readEntry(finished.id, i + 1)
                "entry ${i + 1} ${finished.entries[i].state} lane=${r?.lane?.slot}: " +
                    r?.result?.steps?.filterNot { it.passed }?.joinToString {
                        "${it.kind} " + it.tags.joinToString { t -> "tag${t.tag} want=${t.expected} got=${t.actual}" }
                    }
            }
        assertEquals(RunSetStatus.PASSED, finished.status, why)
        assertEquals(3, finished.total)
        assertEquals(listOf(1, 2, 3), finished.entries.mapNotNull { it.lane?.slot })
        assertEquals(3, finished.policy.concurrency, "the lanes ran together, not one after another")

        // **Every lane bound its own reply**, and that all three passed is the proof: each asserted the
        // echo of `${'$'}{out.D.11}` — its *own* last order — so a lane that had read another lane's cursor
        // would have gone red on the reference rather than green on somebody else's ExecutionReport.
        (1..3).forEach { n ->
            val record = assertNotNull(viewModel.runRecordStore.readEntry(finished.id, n))
            val slot = assertNotNull(record.lane).slot
            assertTrue(record.result.passed, "lane $slot: ${record.result.steps.map { it.detail }}")
            // The four names a lane seeds survive into the record, with the provenance that says where
            // they came from — and they survive trimming, because they are the report, not the bytes.
            val index = record.result.variables.single { it.name == "sessionIndex" }
            assertEquals(slot.toString(), index.value)
            assertEquals(VariableSource.LANE, index.source)
            assertEquals(
                "LOAD0$slot$runId",
                record.result.variables
                    .single { it.name == "sessionSenderCompID" }
                    .value,
            )
        }

        // **Fifty lanes of order flow is fifty copies of the same three messages**, so the set keeps one
        // passing lane whole as a reference specimen and trims the rest to their counts.
        val specimen = assertNotNull(viewModel.runRecordStore.readEntry(finished.id, 1))
        assertTrue(
            specimen.messages.any { !it.incoming && it.raw.contains("11=ORD-1-") },
            "the reference lane keeps its own order: ${specimen.messages.map { it.raw.take(46) }}",
        )
        assertTrue(
            specimen.messages.none { it.raw.contains("11=ORD-2-") || it.raw.contains("11=ORD-3-") },
            "and saw nobody else's — its record is its own session's traffic",
        )
        val trimmed = assertNotNull(viewModel.runRecordStore.readEntry(finished.id, 3))
        assertTrue(trimmed.messages.isEmpty(), "a passing lane past the first is trimmed to counts")
        assertTrue(trimmed.dropped > 0, "and says how many it dropped rather than looking like a lane that saw nothing")
    }

    /**
     * The pinned leg, over a wire: a second session no profile can spread is shared by every lane, so the
     * fan-out is refused rather than run into a race whose report would look like a venue bug.
     */
    @Test
    fun `a scenario with a second, unspreadable leg is refused`() {
        val client = connectLanes(count = 2)
        val twoLegged =
            save(
                laneScenario().copy(
                    id = "sc-two-legs-$runId",
                    name = "two-legged",
                    teardown = listOf(ScenarioStep.ClearMessages("VENUE")),
                ),
            )

        val refused = viewModel.startFanOut(twoLegged, client.id, over = "LoadGen")

        assertEquals(null, refused, "a shared back-office leg under concurrency is refused, not raced")
    }

    /** A profile that opens one session cannot supply lanes, and the reason names the field that would. */
    @Test
    fun `a single-session profile says what to change`() {
        val single =
            FixConnectionProfile(
                name = "OneShot",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "ONE$runId",
                        targetCompID = venueCompId,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "onestore").absolutePath,
                        fileLogPath = File(testDir, "onelog").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(single)

        val lanes = viewModel.fanOutLanes(single.id)

        val why = assertNotNull(lanes as? FixMessageViewModel.FanOutLanes.Unavailable).why
        assertTrue("opens 1" in why, why)
        assertTrue("Sessions" in why && "{nn}" in why, "it names the field and the pattern that fix it: $why")
    }

    // ----------------------------------------------------------------- fixtures

    /** Each lane sends an order carrying its own slot and asserts the venue echoed that order back. */
    private fun laneScenario() =
        Scenario(
            id = "sc-fanout-$runId",
            name = "lane-order",
            steps =
                listOf(
                    ScenarioStep.Send(
                        "35=D|11=ORD-\${sessionIndex}-\${uuid}|55=EUR/USD|54=1|38=100|40=1|",
                        session = "LoadGen",
                    ),
                    ScenarioStep.Expect(
                        session = "LoadGen",
                        match = MatchPredicate(messageType = "8"),
                        timeoutMs = 20_000,
                        expectation =
                            Expectation(
                                messageType = "8",
                                fields = listOf(FieldExpectation(11, Matcher.Reference("\${out.D.11}"))),
                            ),
                    ),
                ),
        )

    private fun save(scenario: Scenario): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario))
        viewModel.refreshScenarios()
        return scenario
    }

    private fun connectLanes(count: Int): FixConnectionProfile {
        val profile =
            FixConnectionProfile(
                name = "LoadGen",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        // {nn} is what makes each lane its own counterparty — the venue sees three clients.
                        senderCompID = "LOAD{nn}$runId",
                        targetCompID = venueCompId,
                        sessionCount = count,
                        host = "localhost",
                        port = venuePort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clistore").absolutePath,
                        fileLogPath = File(testDir, "clilog").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(25_000) {
                viewModel.getProfileSessions(profile.id).count { it.connectionState.value == FixConnectionState.LOGGED_ON } == count
            },
            "all $count lanes should log on: " +
                viewModel.sessions.joinToString { "${it.title}=${it.connectionState.value}" },
        )
        return profile
    }

    private fun connectVenue() {
        val profile =
            FixConnectionProfile(
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = venueCompId,
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
                        port = venuePort.toString(),
                        socketAcceptPort = venuePort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "venuestore").absolutePath,
                        fileLogPath = File(testDir, "venuelog").absolutePath,
                        acceptorResponseRules =
                            listOf(
                                AcceptorResponseRule(
                                    whenMsgType = "D",
                                    responseTemplate = "35=8|150=0|39=0|37=\${uuid}|11=\${req.11}|55=\${req.55}|",
                                ),
                            ),
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "VENUE" && it.isVenue } },
            "the venue should be listening on $venuePort",
        )
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
        }
        return predicate()
    }
}
