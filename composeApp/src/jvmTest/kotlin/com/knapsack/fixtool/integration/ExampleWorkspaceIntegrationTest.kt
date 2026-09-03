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
 * **Open the example, and it works.**
 *
 * The claim a fresh install makes, carried over from the demo this replaced. What changed is where
 * the example lands: it used to be installed into the user's own profiles and scenarios and taken
 * back out again by id prefix, and it is now copied into a workspace of its own that the user keeps.
 *
 * The port is rewritten in the copy before anything connects. The bundled example names 19876, which
 * is a singleton a developer's own instance may already hold — and rewriting it is the honest test of
 * the model, because a copied workspace being editable is the whole point of copying it.
 *
 * The scenario is run **twice**, and the second run is the one that matters. A bundled scenario with a
 * fixed ClOrdID is answered the second time out of the venue's memory of the first — the duplicate
 * rule fires and the order is rejected where it was acknowledged a moment earlier — unless the
 * `ClearOrderBook` step in its setup does its job. A green first run would prove nothing about it.
 */
class ExampleWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var location: File
    private var venuePort = 0

    private val venueCompId = "DEMO_SERVER"
    private val clientCompIds = listOf("DEMO_CLIENT1", "DEMO_CLIENT2")

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-example-home", "").apply {
                delete()
                mkdirs()
            }
        location = File(testDir, "workspaces").apply { mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        venuePort = TestPorts.free()
    }

    /**
     * **Waits for QuickFIX/J to let go, not merely for the panes to disappear.**
     *
     * The example's CompIDs are fixed — that is the bundled data, and every test here uses the same
     * ones. A session's disconnect finishes on its own coroutine and only then stops the engine, which
     * is what unregisters the SessionID. Left to overlap, the previous test's engine stop deregisters
     * an id the next test has already claimed, and that test's sends fail with SessionNotFound while
     * its panes cheerfully report LOGGED_ON. Polling the registry is the exact condition, where a sleep
     * would be a guess.
     */
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
        clientCompIds.flatMap { client ->
            listOf(
                quickfix.SessionID("FIX.4.4", client, venueCompId),
                quickfix.SessionID("FIX.4.4", venueCompId, client),
            )
        }

    /**
     * Copies the example, moves it off the singleton port, opens it, and connects it — venue first,
     * because an initiator whose acceptor has not bound the port yet waits out a reconnect interval.
     */
    private fun openAndConnect(): File {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.FX_VENUE, "FX Venue", location)
                .getOrThrow()

        val profilesFile = File(workspace, "connection_profiles.json").absolutePath
        val profiles = ConnectionProfileService(customPath = profilesFile)
        profiles.saveProfiles(
            profiles.loadProfiles().map { profile ->
                profile.copy(
                    config =
                        profile.config.copy(
                            port = venuePort.toString(),
                            socketAcceptPort = profile.config.socketAcceptPort.takeIf { it.isBlank() } ?: venuePort.toString(),
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

    private fun loggedOnClients() =
        viewModel.sessions.count { session ->
            session.title.startsWith("Demo Client") && session.connectionState.value == FixConnectionState.LOGGED_ON
        }

    @Test
    fun `opening the example gives a workspace holding the venue, its clients, templates and scenarios`() {
        val workspace =
            ExampleWorkspaces
                .open(ExampleWorkspaces.FX_VENUE, "FX Venue", location)
                .getOrThrow()
        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(
            listOf("Demo Client 1", "Demo Client 2", "FX Demo Venue"),
            viewModel.connectionProfiles.map { it.name }.sorted(),
        )
        assertNotNull(viewModel.scenarioService.load("demo-scenario-eurusd-lifecycle"))
        assertNotNull(viewModel.scenarioService.load("demo-scenario-session-probe"))
        assertEquals(workspace, viewModel.openWorkspace)
        assertTrue(!viewModel.openWorkspaceIsHome, "the copy should be the open workspace, not the installation's own")
    }

    @Test
    fun `the venue comes up and both clients reach it`() {
        openAndConnect()

        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == clientCompIds.size },
            "both clients should log on to the venue; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        assertTrue(
            viewModel.sessions.any { it.title == "FX Demo Venue" },
            "the venue should have a session of its own",
        )
        clientCompIds.forEach { compId ->
            assertTrue(
                awaitCondition(10_000) { viewModel.sessions.any { it.title == "FX Demo Venue ← $compId" } },
                "the venue should have opened a pane for $compId",
            )
        }
    }

    /** **The bundled scenario is green, twice.** The second run is the assertion; see the class comment. */
    @Test
    fun `the bundled scenario runs green twice`() {
        openAndConnect()
        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == clientCompIds.size },
            "the clients should be up before the scenario runs",
        )
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.any { it.title == "FX Demo Venue ← ${clientCompIds.first()}" } },
            "the venue pane the scenario clears must exist",
        )

        val scenario = viewModel.scenarioService.load("demo-scenario-eurusd-lifecycle")
        assertNotNull(scenario, "opening the example should have brought the bundled scenario across")

        repeat(2) { attempt ->
            val result = viewModel.runScenarioBlocking(scenario)
            assertNotNull(result, "the run slot was busy on attempt ${attempt + 1}")
            assertTrue(
                result.passed,
                "run ${attempt + 1} of the bundled scenario was red:\n" +
                    result.steps.joinToString("\n") {
                        "  [${if (it.passed) "ok" else "RED"}] ${it.kind}/${it.phase} ${it.detail} " +
                            it.tags.joinToString { t -> "${t.tag}=${t.actual}(${t.status})" }
                    },
            )
        }
    }

    /**
     * Closing a workspace is a promise about what is on screen, not about what is on disk.
     *
     * Every session goes, including the venue's per-client panes — which is the bug the old Stop had:
     * the clients reconnect while everything winds down, so the venue was still minting panes after
     * the sweep meant to remove them. Nothing is deleted, because the copy belongs to whoever opened
     * it, and that is the difference from the demo this replaced.
     */
    @Test
    fun `closing the workspace takes the sessions and profiles with it, and leaves the files alone`() {
        val workspace = openAndConnect()
        assertTrue(awaitCondition(20_000) { loggedOnClients() == clientCompIds.size })

        viewModel.closeWorkspace()

        assertTrue(viewModel.openWorkspaceIsHome, "the installation's own directory should be back")
        assertEquals(emptyList(), viewModel.connectionProfiles.map { it.name }, "a profile survived the close")
        assertEquals(null, viewModel.scenarioService.load("demo-scenario-eurusd-lifecycle"))
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.isEmpty() },
            "a session survived the close: " + viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        assertTrue(File(workspace, "connection_profiles.json").isFile, "closing must not delete the user's copy")
    }

    @Test
    fun `a workspace closed and opened again is the same workspace`() {
        val workspace = openAndConnect()
        assertTrue(awaitCondition(20_000) { loggedOnClients() == clientCompIds.size })
        viewModel.closeWorkspace()

        viewModel.openWorkspace(workspace).getOrThrow()

        assertEquals(3, viewModel.connectionProfiles.size)
        assertNotNull(viewModel.scenarioService.load("demo-scenario-eurusd-lifecycle"))
        assertTrue(viewModel.recentWorkspaces.contains(workspace), "an opened workspace should be offered again")
    }

    private fun portIsFree(port: Int): Boolean = runCatching { java.net.ServerSocket(port).close() }.isSuccess

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
        return predicate()
    }
}
