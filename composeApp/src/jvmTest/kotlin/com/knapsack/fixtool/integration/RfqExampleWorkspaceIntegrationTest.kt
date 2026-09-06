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
 * **Open the RFQ example, and it works.** The same claim `ExampleWorkspaceIntegrationTest` makes for the
 * FX venue, for the second example: the venue comes up, both clients and all five load lanes reach it,
 * and the bundled scenarios run green twice.
 *
 * The port is rewritten in the copy before anything connects, because the bundled 19877 is a singleton
 * a developer's own instance may already hold, and a copied workspace being editable is the point.
 *
 * The load client is on a memory store, which is the setting the example exists to demonstrate, so this
 * also checks the workspace's `store/` holds nothing for the lanes. The venue's own files for those
 * sessions do appear there: they are the venue's, on a file store, and that is not the lane's business.
 */
class RfqExampleWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var location: File
    private var venuePort = 0

    private val venueCompId = "RFQ_SERVER"
    private val clientCompIds = listOf("RFQ_CLIENT1", "RFQ_CLIENT2")
    private val laneCompIds = (1..5).map { "RFQLG$it" }

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-rfq-example-home", "").apply {
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

    /** Copies the example, moves it off the singleton port, opens it, and connects everything, venue first. */
    private fun openAndConnect(): File {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.RFQ_VENUE, "RFQ Venue", location)
                .getOrThrow()

        val profilesFile = File(workspace, "connection_profiles.json").absolutePath
        val profiles = ConnectionProfileService(customPath = profilesFile)
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
                .open(ExampleWorkspaces.RFQ_VENUE, "RFQ Venue", location)
                .getOrThrow()
        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(
            listOf("RFQ Client 1", "RFQ Client 2", "RFQ Demo Venue", "RFQ Load Client"),
            viewModel.connectionProfiles.map { it.name }.sorted(),
        )
        assertNotNull(viewModel.scenarioService.load("rfq-scenario-book-a-trade"))
        assertNotNull(viewModel.scenarioService.load("rfq-scenario-pass-and-counter"))
        assertEquals(workspace, viewModel.openWorkspace)
    }

    @Test
    fun `the venue comes up, both clients and all five load lanes reach it, and the lanes leave no store`() {
        val workspace = openAndConnect()

        assertTrue(
            awaitCondition(30_000) {
                loggedOn("RFQ Client") == clientCompIds.size && loggedOn("RFQ Load Client") == laneCompIds.size
            },
            "both clients and five lanes should log on; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        (clientCompIds + laneCompIds).forEach { compId ->
            assertTrue(
                awaitCondition(10_000) { viewModel.sessions.any { it.title == "RFQ Demo Venue ← $compId" } },
                "the venue should have opened a pane for $compId",
            )
        }

        val store = File(workspace, "store")
        val laneFiles = store.listFiles().orEmpty().map { it.name }.filter { it.startsWith("FIX.4.4-RFQLG") }
        assertEquals(emptyList(), laneFiles, "a lane on a memory store wrote a store file")
    }

    /** **Both bundled scenarios are green, twice.** The second run is the one that would catch leaked state. */
    @Test
    fun `the bundled scenarios run green twice`() {
        openAndConnect()
        assertTrue(
            awaitCondition(30_000) { loggedOn("RFQ Client") == clientCompIds.size },
            "the clients should be up before the scenarios run",
        )
        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.any { it.title == "RFQ Demo Venue ← ${clientCompIds.first()}" }
            },
            "the venue pane the scenarios clear must exist",
        )

        listOf("rfq-scenario-book-a-trade", "rfq-scenario-pass-and-counter").forEach { id ->
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
        // A torn read is 'not yet', not 'no' -- see [settled].
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
    }
}
