package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A suite, over a real wire, and the record it leaves.**
 *
 * This is the claim the whole design turns on: by the time a twelve-scenario suite lands, the grid holds
 * the last entry's traffic and nothing else — entry 2's setup clears it, and even without a clear the
 * session is a ring buffer. So eleven of the twelve reports would point at messages that are not there.
 * Here two entries run against a live venue, the second one clears the session on its way in, and entry
 * 1's bytes are **still readable** afterwards, from disk, with the verdict that judged them.
 */
class RunSetIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-run-set", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        connectVenueAndClient()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    @Test
    fun `a two-entry set runs both, and each entry's evidence outlives the next entry's clear`() {
        val first = save(scenario("alpha", "A-$runId"))
        val second = save(scenario("beta", "B-$runId"))
        val set =
            RunSets.suite(
                listOf(first, second),
                RunSource.Selected(listOf(first.id, second.id)),
                label = "suite",
                now = System.currentTimeMillis(),
            )

        val progress = mutableListOf<Int>()
        val done = assertNotNull(viewModel.runSetBlocking(set) { progress += it.done }, "the run slot should be free")

        assertEquals(RunSetStatus.PASSED, done.status, "both entries should pass: ${done.entries.map { it.state to it.note }}")
        assertEquals(listOf(RunState.PASSED, RunState.PASSED), done.entries.map { it.state })
        assertTrue(progress.last() == 2, "the queue drains where a caller can watch it: $progress")

        // The live session shows the LAST entry only — its setup cleared what came before, which is exactly
        // why a report cannot be a reference into the session.
        val live = viewModel.sessions.single { it.title == "CLI" }.messages.value.filterIsInstance<FixMessage>()
        assertTrue(live.none { it.rawMessage.contains("A-$runId") }, "entry 1's traffic is gone from the grid")

        // And it is still on disk, with the verdict that judged it.
        val alpha = assertNotNull(viewModel.runRecordStore.readEntry(done.id, 1))
        assertEquals("alpha", alpha.scenarioName)
        assertTrue(alpha.result.passed)
        assertTrue(
            alpha.messages.any { it.raw.contains("A-$runId") && !it.incoming },
            "the order entry 1 sent: ${alpha.messages.map { it.raw.take(40) }}",
        )
        assertTrue(
            alpha.messages.any { it.raw.contains("A-$runId") && it.incoming },
            "and the reply it was judged against",
        )

        // The reconcile pair: the step's id, and the message it bound, by index into the record.
        val expectStep = alpha.result.steps.single { it.kind == "expect" }
        val boundAt = assertNotNull(alpha.bound[expectStep.stepId], "the expect must say which message it judged")
        assertTrue(alpha.messages[boundAt].incoming, "and it must be the reply, not the order")
        assertEquals(0, alpha.dropped)

        // set.json is beside the entries, and says what the set was and how it went.
        val onDisk = assertNotNull(viewModel.runRecordStore.readSet(done.id))
        assertEquals(RunSetStatus.PASSED, onDisk.status)
        assertEquals(listOf("01-alpha.json", "02-beta.json"), onDisk.entries.map { it.record })
        assertTrue(File(viewModel.runRecordStore.directoryFor(done.id), "set.json").isFile)
    }

    /** The slot is the set's for the whole batch: a bare run during one is refused, not interleaved. */
    @Test
    fun `a set holds the run slot for the whole batch`() {
        val only = save(scenario("solo", "S-$runId"))
        val set = RunSets.repeat(only, times = 2, now = System.currentTimeMillis())

        var refusedDuring: Boolean? = null
        viewModel.runSetBlocking(set) { progress ->
            // Asked while the set is between entries, which is when a competing run would slip in.
            if (progress.done == 1 && refusedDuring == null) {
                refusedDuring = viewModel.runScenarioBlocking(only) == null
            }
        }

        assertEquals(true, refusedDuring, "a scenario run inside a set must be told the slot is taken")
    }

    // ----------------------------------------------------------------- fixtures

    private fun save(scenario: Scenario): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario), "the scenario must reach disk to be run by id")
        return scenario
    }

    /** Clear, send an order, assert the venue's ack. The clear is what makes entry 2 erase entry 1. */
    private fun scenario(name: String, clOrdId: String) =
        Scenario(
            id = "sc-$name-$runId",
            name = name,
            setup = listOf(ScenarioStep.ClearMessages("CLI")),
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=$clOrdId|55=EUR/USD|54=1|38=100|40=1|", session = "CLI"),
                    ScenarioStep.Expect(
                        session = "CLI",
                        match = MatchPredicate(messageType = "8"),
                        timeoutMs = 10_000,
                        expectation =
                            Expectation(
                                messageType = "8",
                                fields = listOf(FieldExpectation(11, Matcher.Exact(clOrdId))),
                            ),
                    ),
                ),
        )

    private fun connectVenueAndClient() {
        val fixPort = TestPorts.free()
        val rule =
            AcceptorResponseRule(
                whenMsgType = "D",
                responseTemplate = "35=8|150=0|39=0|37=\${uuid}|11=\${req.11}|55=\${req.55}|",
            )
        val venue =
            FixConnectionProfile(
                name = "ACC",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "ACC$runId",
                        targetCompID = "CLI$runId",
                        port = fixPort.toString(),
                        socketAcceptPort = fixPort.toString(),
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "accstore").absolutePath,
                        fileLogPath = File(testDir, "acclog").absolutePath,
                        acceptorResponseRules = listOf(rule),
                    ),
            )
        val client =
            FixConnectionProfile(
                name = "CLI",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "CLI$runId",
                        targetCompID = "ACC$runId",
                        host = "localhost",
                        port = fixPort.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "clistore").absolutePath,
                        fileLogPath = File(testDir, "clilog").absolutePath,
                    ),
            )
        listOf(venue, client).forEach {
            viewModel.saveConnectionProfile(it)
            viewModel.connectProfile(it.id, it)
        }
        assertTrue(
            awaitCondition(15_000) {
                viewModel.sessions.any { it.title == "CLI" && it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "the client should log on to the FixTool venue",
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
