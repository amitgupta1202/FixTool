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
 * **Open the fixed-income example, and it works** — the claim every bundled example makes.
 *
 * The scenarios are run **twice** for the reason the FX RFQ desk's are: a quote is spent once it has been
 * lifted, so a second run that reached for the first run's level would be refused as already answered.
 * They pass because each run asks for a quote of its own, which is what a client does.
 */
class FiRfqExampleWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var location: File
    private var venuePort = 0

    private val venueCompId = "FIRFQ_SERVER"
    private val clientCompIds = listOf("FIRFQ_CLIENT1", "FIRFQ_CLIENT2")
    private val laneCompIds = (1..5).map { "FIRFQLG$it" }

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-fi-rfq-example-home", "").apply {
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
                .open(ExampleWorkspaces.FI_RFQ_VENUE, "Fixed Income RFQ", location)
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
                .open(ExampleWorkspaces.FI_RFQ_VENUE, "Fixed Income RFQ", location)
                .getOrThrow()
        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(
            listOf("FI RFQ Client 1", "FI RFQ Client 2", "FI RFQ Demo Desk", "FI RFQ Load Client"),
            viewModel.connectionProfiles.map { it.name }.sorted(),
        )
        assertNotNull(viewModel.scenarioService.load("fi-rfq-scenario-two-way-lift"))
        assertNotNull(viewModel.scenarioService.load("fi-rfq-scenario-one-way-done-away"))
        assertEquals(workspace, viewModel.openWorkspace)
    }

    @Test
    fun `the venue comes up, both clients and all five load lanes reach it, and the lanes leave no store`() {
        val workspace = openAndConnect()

        assertTrue(
            awaitCondition(30_000) {
                loggedOn("FI RFQ Client") == clientCompIds.size && loggedOn("FI RFQ Load Client") == laneCompIds.size
            },
            "both clients and five lanes should log on; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        (clientCompIds + laneCompIds).forEach { compId ->
            assertTrue(
                awaitCondition(10_000) { viewModel.sessions.any { it.title == "FI RFQ Demo Desk ← $compId" } },
                "the venue should have opened a pane for $compId",
            )
        }

        val laneFiles =
            File(workspace, "store").listFiles().orEmpty().map { it.name }.filter { it.startsWith("FIX.4.4-FIRFQLG") }
        assertEquals(emptyList(), laneFiles, "a lane on a memory store wrote a store file")
    }

    /** **All three bundled scenarios are green, twice.** The second run is the one that matters here. */
    @Test
    fun `the bundled scenarios run green twice`() {
        openAndConnect()
        assertTrue(
            awaitCondition(30_000) { loggedOn("FI RFQ Client") == clientCompIds.size },
            "the clients never logged on, so the scenarios cannot be judged",
        )

        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.any { it.title == "FI RFQ Demo Desk ← ${clientCompIds.first()}" }
            },
            "the venue pane the scenarios clear must exist",
        )

        listOf("fi-rfq-scenario-two-way-lift", "fi-rfq-scenario-one-way-done-away").forEach { id ->
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
