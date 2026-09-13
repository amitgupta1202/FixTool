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
 * **Open the fixed-income platform, and it works** — the claim every bundled example makes.
 *
 * Everything is connected, the Dealer Load Client included, because that is what a user demoing it does: five
 * dealer lanes quote every request by rule while Dealer 1 and Dealer 2 are played by the scenarios. The scenarios
 * pass anyway, which is the point of choosing a quote by who sent it and by its price rather than by arriving last.
 *
 * They are run **twice**: a quote is spent once it has been lifted, so a second run that reached for the first
 * run's quote would be refused. They pass because each run asks for an RFQ of its own, which is what a buy side does.
 */
class FiRfqExampleWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var location: File
    private var venuePort = 0

    private val venueCompId = "FIRFQ_VENUE"
    private val venueName = "FI RFQ Platform"
    private val clientCompIds = listOf("FIBUY1", "FIBUY2", "FIDLR1", "FIDLR2")
    private val laneCompIds = (1..5).flatMap { listOf("FIBUYLG$it", "FIDLRLG$it") }

    private val scenarioIds =
        listOf(
            "fi-rfq-scenario-lift-dealer-offer",
            "fi-rfq-scenario-better-offer-lifted",
            "fi-rfq-scenario-dealer-cannot-ask",
            "fi-rfq-scenario-not-listed",
        )

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

    private fun loggedOn(compIds: List<String>) =
        viewModel.sessions.count { session ->
            session.currentConfig?.senderCompID in compIds && session.connectionState.value == FixConnectionState.LOGGED_ON
        }

    private fun everybodyOn() = loggedOn(clientCompIds + laneCompIds) == clientCompIds.size + laneCompIds.size

    @Test
    fun `opening the example gives a workspace holding the platform, both sides, both load clients and the scenarios`() {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.FI_RFQ_VENUE, "Fixed Income RFQ", location)
                .getOrThrow()
        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(
            listOf("Buy Side 1", "Buy Side 2", "Buy Side Load Client", "Dealer 1", "Dealer 2", "Dealer Load Client", venueName),
            viewModel.connectionProfiles.map { it.name }.sorted(),
        )
        scenarioIds.forEach { assertNotNull(viewModel.scenarioService.load(it), "$it did not come across") }
        assertEquals(workspace, viewModel.openWorkspace)
    }

    @Test
    fun `the platform comes up, all four clients and every load lane reach it, and the lanes leave no store`() {
        val workspace = openAndConnect()

        assertTrue(
            awaitCondition(30_000) { everybodyOn() },
            "four clients and ten lanes should log on; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        (clientCompIds + laneCompIds).forEach { compId ->
            assertTrue(
                awaitCondition(10_000) { viewModel.sessions.any { it.title == "$venueName ← $compId" } },
                "the platform should have opened a pane for $compId",
            )
        }

        val laneFiles =
            File(workspace, "store")
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { name -> laneCompIds.any { name.startsWith("FIX.4.4-$it-") } }
        assertEquals(emptyList(), laneFiles, "a lane on a memory store wrote a store file")
    }

    /** **All four bundled scenarios are green, twice, with the dealer lanes quoting beside them.** */
    @Test
    fun `the bundled scenarios run green twice`() {
        openAndConnect()
        assertTrue(awaitCondition(30_000) { everybodyOn() }, "the clients never logged on, so the scenarios cannot be judged")
        assertTrue(
            awaitCondition(10_000) {
                (clientCompIds + laneCompIds).all { compId -> viewModel.sessions.any { it.title == "$venueName ← $compId" } }
            },
            "the platform must know every party online before it relays a request to them",
        )

        scenarioIds.forEach { id ->
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
