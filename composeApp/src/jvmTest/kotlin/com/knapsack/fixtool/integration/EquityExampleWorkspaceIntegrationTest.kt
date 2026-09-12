package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.service.ConnectionProfileService
import com.knapsack.fixtool.service.ExampleWorkspaces
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Open the equity example, and it works** — the claim the other two examples make, made again for the
 * one whose book holds an order.
 *
 * The scenarios are run **twice**, as the FX and RFQ ones are, and here the second run is load-bearing in
 * a way it is not for them. This venue's orders do not go away on their own: a Day limit that rested in
 * the first run is still working when the second starts, so a fixed ClOrdID would be answered out of the
 * venue's memory as a duplicate. That is what the `clearOrderBook` step in each scenario's setup is for,
 * and a green first run proves nothing about it.
 */
class EquityExampleWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var location: File
    private var venuePort = 0

    private val venueCompId = "EQTY_SERVER"
    private val clientCompIds = listOf("EQTY_CLIENT1", "EQTY_CLIENT2")
    private val laneCompIds = (1..5).map { "EQLG$it" }

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-equity-example-home", "").apply {
                delete()
                mkdirs()
            }
        location = File(testDir, "workspaces").apply { mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
    }

    /** Waits for QuickFIX/J to let go of the fixed CompIDs, not merely for the panes to disappear. */
    @After
    fun cleanup() {
        runCatching { viewModel.closeWorkspace() }
        viewModel.disconnectAllSessions()
        awaitCondition(10_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        awaitCondition(15_000) {
            portIsFree(venuePort) && sessionIds().none { quickfix.Session.lookupSession(it) != null }
        }
        testDir.deleteRecursively()
    }

    private fun sessionIds(): List<quickfix.SessionID> =
        (clientCompIds + laneCompIds).flatMap { client ->
            listOf(
                quickfix.SessionID("FIX.4.4", client, venueCompId),
                quickfix.SessionID("FIX.4.4", venueCompId, client),
            )
        }

    /** Copies the example, moves it off the bundled port, opens it, and connects everything, venue first. */
    private fun openAndConnect(): File {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.EQUITY_VENUE, "Equity Venue", location)
                .getOrThrow()

        val profiles = ConnectionProfileService(customPath = File(workspace, "connection_profiles.json").absolutePath)
        profiles.saveProfiles(
            profiles.loadProfiles().map { profile ->
                profile.copy(
                    config =
                        profile.config.copy(
                            port = venuePort.toString(),
                            socketAcceptPort =
                                profile.config.socketAcceptPort.takeIf { it.isBlank() } ?: venuePort.toString(),
                        ),
                )
            },
        )

        viewModel.openWorkspace(workspace).getOrThrow()
        val venue = viewModel.connectionProfiles.first { it.config.senderCompID == venueCompId }
        viewModel.connectProfile(venue.id, venue)
        viewModel.connectionProfiles.filter { it.id != venue.id }.forEach { viewModel.connectProfile(it.id, it) }
        return workspace
    }

    private fun loggedOn(prefix: String) =
        viewModel.sessions.count { session ->
            session.title.startsWith(prefix) && session.connectionState.value == FixConnectionState.LOGGED_ON
        }

    @Test
    fun `opening the example gives a workspace holding the venue, its clients, the load client and the scenarios`() {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.EQUITY_VENUE, "Equity Venue", location)
                .getOrThrow()
        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(
            listOf("Equity Client 1", "Equity Client 2", "Equity Demo Venue", "Equity Load Client"),
            viewModel.connectionProfiles.map { it.name }.sorted(),
        )
        assertNotNull(viewModel.scenarioService.load("equity-scenario-rest-and-cancel"))
        assertNotNull(viewModel.scenarioService.load("equity-scenario-market-fill"))
        assertNotNull(viewModel.scenarioService.load("equity-scenario-market-data"))
        assertEquals(workspace, viewModel.openWorkspace)
    }

    @Test
    fun `the venue comes up, both clients and all five load lanes reach it, and the lanes leave no store`() {
        val workspace = openAndConnect()

        assertTrue(
            awaitCondition(30_000) {
                loggedOn("Equity Client") == clientCompIds.size && loggedOn("Equity Load Client") == laneCompIds.size
            },
            "both clients and five lanes should log on; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        (clientCompIds + laneCompIds).forEach { compId ->
            assertTrue(
                awaitCondition(10_000) { viewModel.sessions.any { it.title == "Equity Demo Venue ← $compId" } },
                "the venue should have opened a pane for $compId",
            )
        }

        val laneFiles =
            File(workspace, "store").listFiles().orEmpty().map { it.name }.filter { it.startsWith("FIX.4.4-EQLG") }
        assertEquals(emptyList(), laneFiles, "a lane on a memory store wrote a store file")
    }

    /** **All three bundled scenarios are green, twice.** The second run is the one that matters here. */
    @Test
    fun `the bundled scenarios run green twice`() {
        openAndConnect()
        assertTrue(
            awaitCondition(30_000) { loggedOn("Equity Client") == clientCompIds.size },
            "the clients never logged on, so the scenarios cannot be judged",
        )

        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.any { it.title == "Equity Demo Venue ← ${clientCompIds.first()}" }
            },
            "the venue pane the scenarios clear must exist",
        )

        listOf(
            "equity-scenario-rest-and-cancel",
            "equity-scenario-market-fill",
            "equity-scenario-market-data",
        ).forEach { id ->
            val scenario = assertNotNull(viewModel.scenarioService.load(id), "$id did not come across")
            repeat(2) { attempt ->
                val result = viewModel.runScenarioBlocking(scenario)
                assertNotNull(result, "the run slot was busy on attempt ${attempt + 1} of $id")
                assertTrue(
                    result.passed,
                    "run ${attempt + 1} of $id was red:\n" +
                        result.steps.joinToString("\n") {
                            "  [${if (it.passed) "ok" else "RED"}] ${it.kind}/${it.phase} ${it.detail} " +
                                it.tags.joinToString { t -> "${t.tag}=${t.actual}(${t.status})" }
                        },
                )
            }
        }
    }

    private fun portIsFree(port: Int): Boolean = runCatching { java.net.ServerSocket(port).close() }.isSuccess

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
    }

    /** A torn read is 'not yet', not 'no': the same two-reads-agree rule the other example tests use. */
    private fun settled(predicate: () -> Boolean): Boolean = predicate() && predicate()

    private companion object {
        const val POLL_MS = 100L
    }
}
