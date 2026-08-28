package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.scenario.BindScope
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The other run boundary, over a real wire: the venue's memory.**
 *
 * `ClearMessages` empties what a session displays. Nothing emptied what the venue *remembers* — the
 * order book its rules read — so a scenario run twice reached iteration 2 with iteration 1's order
 * still live, and a venue configured to reject a duplicate ClOrdID rejected the very order it had
 * acknowledged a second earlier. The scenario had not changed, the venue had not changed, and the run
 * was red: the worst shape a failure can take, because everything it points at is innocent.
 *
 * Both halves are here, and the second means nothing without the first: the repeat genuinely goes red
 * on its own history, and a `ClearOrderBook` step in setup — the step capture now authors for a venue
 * pane — makes the same scenario green twice.
 */
class ScenarioOrderBookIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    // QuickFIX/J's session registry is static per JVM: CompIDs reused across tests collide with a
    // session an earlier test has not finished tearing down.
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-scenario-book", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        connectVenueAndClient()
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        testDir.deleteRecursively()
    }

    /**
     * The defect, demonstrated rather than asserted about. The venue acks a new order and rejects a
     * ClOrdID it already holds live — one rule list, and the book decides which fires.
     */
    @Test
    fun `without a book reset the second run is rejected as a duplicate of the first`() {
        val scenario = repeatable(resetsBook = false)

        assertTrue(run(scenario).passed, "the first run is the easy one")

        val second = run(scenario)
        assertFalse(second.passed, "the venue still holds iteration 1's order, and says so")
        val expect = second.steps.single { it.kind == "expect" }
        assertEquals(
            "8",
            expect.tags.single { it.tag == 39 }.actual,
            "39=8 is Rejected: the venue answered iteration 2 out of iteration 1's memory",
        )
    }

    /** The step that makes a repeat repeatable — the venue forgets, so the second run is the first again. */
    @Test
    fun `a clear-order-book step in setup makes the same scenario green twice`() {
        val scenario = repeatable(resetsBook = true)

        val first = run(scenario)
        assertTrue(first.passed, "${first.steps}")

        val second = run(scenario)
        assertTrue(second.passed, "the book was emptied, so this order is new again: ${second.steps}")
        assertEquals(
            "0",
            second.steps
                .single { it.kind == "expect" }
                .tags
                .single { it.tag == 39 }
                .actual,
            "39=0 is New: the ack a fresh venue gives a fresh order",
        )
        // And the step said so in the report, rather than passing silently.
        assertEquals("order book cleared", second.steps.single { it.kind == "clearBook" }.detail)
    }

    // ----------------------------------------------------------------- the scenario under test

    /**
     * One order, one acknowledgement, **a fixed ClOrdID** — the shape a repeat has when the author is
     * asking "is this flow flaky", and the shape that makes the venue's memory decide the answer.
     *
     * `THIS_RUN` so the expect cannot bind the previous run's reply; the clear keeps the grid honest.
     * Neither of those resets the book, which is the whole point of the step being tested.
     */
    private fun repeatable(resetsBook: Boolean) =
        Scenario(
            id = "sc-book-$runId",
            name = "book-a-trade",
            binding = BindScope.THIS_RUN,
            setup =
                listOfNotNull(
                    ScenarioStep.ClearMessages("CLI"),
                    ScenarioStep.ClearOrderBook("ACC").takeIf { resetsBook },
                ),
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=ORD-$runId|55=EUR/USD|54=1|38=100|40=2|44=1.10|", session = "CLI"),
                    ScenarioStep.Expect(
                        session = "CLI",
                        match = MatchPredicate(messageType = "8"),
                        timeoutMs = 10_000,
                        expectation =
                            Expectation(
                                messageType = "8",
                                fields = listOf(FieldExpectation(39, Matcher.Exact("0"))),
                            ),
                    ),
                ),
        )

    private fun run(scenario: Scenario): ScenarioResult =
        requireNotNull(viewModel.runScenarioBlocking(scenario)) { "the run slot was busy — no other run should hold it" }

    // ----------------------------------------------------------------- wiring

    /**
     * A venue that acks a new order and rejects a live ClOrdID, and one client connected to it. The
     * two rules are inserted through [AcceptorPresets.insert] rather than hand-ordered, because the
     * conditioned rule has to sit above the unconditioned one and that placement is the preset's job.
     */
    private fun connectVenueAndClient() {
        val fixPort = TestPorts.free()
        val acked = AcceptorPresets.insert(emptyList(), AcceptorPresets.byId("order-ack")!!).rules
        val rules = AcceptorPresets.insert(acked, AcceptorPresets.byId("duplicate-clordid")!!).rules
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
                        acceptorResponseRules = rules,
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
