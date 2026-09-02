package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.demo.DemoScenarioProvider
import com.knapsack.fixtool.service.demo.DemoServerManager
import com.knapsack.fixtool.service.demo.DemoTemplatesProvider
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Press Start, and the demo works.**
 *
 * The claim a fresh install makes and the one nothing could check before: the old demo was a server
 * behind a button, on a fixed port, with no test of any kind. Pressing the button was the test.
 *
 * The port is parameterised precisely so this can exist — 19876 is a singleton, and a suite that bound
 * it could not run beside a developer's own instance.
 *
 * The scenario is run **twice**, and the second run is the one that matters. A demo scenario with a
 * fixed ClOrdID is answered the second time out of the venue's memory of the first — the duplicate rule
 * fires and the order is rejected where it was acknowledged a moment earlier — unless the `ClearOrderBook`
 * step in its setup does its job. That is the whole reason the step is there, and a green first run
 * would prove nothing about it.
 */
class DemoWorkspaceIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private var demoPort = 0

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-demo-workspace", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        demoPort = TestPorts.free()
    }

    /**
     * **Waits for QuickFIX/J to let go, not merely for the panes to disappear.**
     *
     * The demo's CompIDs are fixed — that is the product's design, and every test here uses the same
     * ones. A session's disconnect finishes on its own coroutine and only then stops the engine, which
     * is what unregisters the SessionID. Left to overlap, the *previous* test's engine stop deregisters
     * an id the *next* test has already claimed, and that test's sends fail with SessionNotFound while
     * its panes cheerfully report LOGGED_ON. Polling the registry is the exact condition, where a sleep
     * would be a guess.
     */
    @After
    fun cleanup() {
        runCatching { viewModel.stopDemoServer() }
        viewModel.disconnectAllSessions()
        awaitCondition(10_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        awaitCondition(15_000) {
            portIsFree(demoPort) && demoSessionIds().none { quickfix.Session.lookupSession(it) != null }
        }
        testDir.deleteRecursively()
    }

    /** Every SessionID the demo workspace registers with QuickFIX/J, both sides of every client. */
    private fun demoSessionIds(): List<quickfix.SessionID> =
        DemoServerManager.DEMO_CLIENTS.flatMap { client ->
            listOf(
                quickfix.SessionID("FIX.4.4", client, DemoServerManager.VENUE_COMP_ID),
                quickfix.SessionID("FIX.4.4", DemoServerManager.VENUE_COMP_ID, client),
            )
        }

    /**
     * The whole workspace, from one call: the venue listening, both clients logged on to it, and a
     * venue-side pane per client.
     */
    @Test
    fun `starting the demo brings up a venue and two clients that reach it`() {
        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)

        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == DemoServerManager.DEMO_CLIENTS.size },
            "both demo clients should log on to the demo venue; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )
        assertTrue(
            viewModel.sessions.any { it.title == DemoServerManager.VENUE_NAME },
            "the venue should have a session of its own",
        )
        DemoServerManager.DEMO_CLIENTS.forEach { compId ->
            assertTrue(
                awaitCondition(10_000) {
                    viewModel.sessions.any { it.title == DemoServerManager.venuePaneFor(compId) }
                },
                "the venue should have opened a pane for $compId",
            )
        }
        assertTrue(viewModel.demoServerRunning.value)
    }

    /**
     * **The bundled scenario is green, twice.** The second run is the assertion; see the class comment.
     */
    @Test
    fun `the bundled scenario runs green twice`() {
        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)
        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == DemoServerManager.DEMO_CLIENTS.size },
            "the demo clients should be up before the scenario runs",
        )
        // The venue's per-client pane is what the scenario's ClearOrderBook targets, and it exists only
        // once its client has logged on.
        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.any {
                    it.title == DemoServerManager.venuePaneFor(DemoServerManager.DEMO_CLIENTS.first())
                }
            },
            "the venue pane the scenario clears must exist",
        )

        val scenario = viewModel.scenarioService.load(DemoScenarioProvider.LIFECYCLE_ID)
        assertNotNull(scenario, "starting the demo should have installed the bundled scenario")

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

    /** Stop is a promise about the user's own data: nothing demo-prefixed survives, and no session does. */
    @Test
    fun `stopping the demo removes everything it installed`() {
        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)
        assertTrue(awaitCondition(20_000) { loggedOnClients() == DemoServerManager.DEMO_CLIENTS.size })
        assertTrue(viewModel.connectionProfiles.any { DemoServerManager.isDemoProfile(it.id) })
        assertNotNull(viewModel.scenarioService.load(DemoScenarioProvider.LIFECYCLE_ID))

        viewModel.stopDemoServer()

        assertTrue(!viewModel.demoServerRunning.value, "the workspace should report itself gone")
        assertEquals(
            emptyList(),
            viewModel.connectionProfiles.filter { DemoServerManager.isDemoProfile(it.id) },
            "a demo profile survived Stop",
        )
        assertEquals(
            null,
            viewModel.scenarioService.load(DemoScenarioProvider.LIFECYCLE_ID),
            "the bundled scenario survived Stop",
        )
        assertEquals(
            emptyList(),
            viewModel.savedMessages.filter { DemoTemplatesProvider.isDemoTemplate(it.id) },
            "a demo template survived Stop",
        )
        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON }
            },
            "a demo session was left connected: " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )

        // **Not merely disconnected — gone.** Live verification caught what "nothing is LOGGED_ON" let
        // through: the venue's two per-client panes survived Stop as dead tabs, because the demo's
        // clients reconnect while everything is winding down and the venue was still minting panes for
        // them after the sweep. A pane nobody can use and nothing will remove is exactly the litter the
        // demo promises not to leave.
        assertTrue(
            awaitCondition(10_000) {
                viewModel.sessions.none { session ->
                    session.title == DemoServerManager.VENUE_NAME ||
                        DemoServerManager.DEMO_CLIENTS.any { c ->
                            session.title == DemoServerManager.venuePaneFor(c)
                        } ||
                        DemoServerManager.DEMO_CLIENTS.indices.any { i ->
                            session.title == DemoServerManager.clientName(i)
                        }
                }
            },
            "Stop left a demo pane behind: " + viewModel.sessions.map { it.title },
        )
    }

    /**
     * **Start, Stop, Start** — the path a presenter takes between takes, and the one that catches a
     * teardown which only looked complete.
     *
     * The demo's CompIDs are fixed, so a second Start asks QuickFIX/J for session ids the first one
     * used. If Stop left them registered, the new sessions are the old ones' ghosts: the panes say
     * LOGGED_ON and every send fails with SessionNotFound, which is the most confusing shape a failure
     * can take — the tool reporting healthy and doing nothing.
     */
    @Test
    fun `the demo can be stopped and started again`() {
        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)
        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == DemoServerManager.DEMO_CLIENTS.size },
            "first start",
        )

        viewModel.stopDemoServer()
        assertTrue(
            awaitCondition(10_000) { viewModel.sessions.none { it.title == DemoServerManager.VENUE_NAME } },
            "the demo's sessions should be gone before it is started again",
        )
        // Sessions leaving the list is not the same as the port being free: a session's disconnect runs
        // on its own coroutine and only stops the QuickFIX engine at the end of it. The venue cannot
        // rebind until that has happened, so this is the condition a restart actually waits on.
        assertTrue(awaitCondition(10_000) { portIsFree(demoPort) }, "Stop should release the venue's port")

        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)
        assertTrue(
            awaitCondition(20_000) { loggedOnClients() == DemoServerManager.DEMO_CLIENTS.size },
            "the demo should come back up after a stop; sessions are " +
                viewModel.sessions.map { "${it.title}=${it.connectionState.value}" },
        )

        // Healthy panes are not proof: the failure this guards against reports LOGGED_ON and still
        // cannot send. So actually send, and require the venue to answer.
        val client = viewModel.sessions.first { it.title == DemoServerManager.clientName(0) }
        client.sendFixMessage(
            "35=D|11=RESTART-1|55=EUR/USD|54=1|38=1000000|40=2|44=1.08950|60=20260101-00:00:00.000",
            viewModel.dictionary,
        )
        assertTrue(
            awaitCondition(15_000) {
                client.messages.value
                    .filterIsInstance<com.knapsack.fixtool.model.FixMessage>()
                    .any {
                        it.direction == com.knapsack.fixtool.model.FixMessage.Direction.INCOMING &&
                            "35=8" in it.rawMessage
                    }
            },
            "after a restart the venue should still answer an order",
        )
    }

    /** Templates land where the app was told to keep them — not in the real user's home. */
    @Test
    fun `starting the demo installs its templates in the configured store`() {
        viewModel.startDemoServer(FixVersion.FIX_4_4, demoPort)

        assertTrue(
            awaitCondition(10_000) {
                viewModel.savedMessages.count { DemoTemplatesProvider.isDemoTemplate(it.id) } ==
                    DemoTemplatesProvider.getDemoTemplateIds().size
            },
            "expected ${DemoTemplatesProvider.getDemoTemplateIds().size} demo templates, got " +
                viewModel.savedMessages.count { DemoTemplatesProvider.isDemoTemplate(it.id) },
        )
        assertTrue(
            File(testDir, "saved_messages.json").exists(),
            "the demo wrote its templates somewhere other than the configured store",
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun loggedOnClients(): Int =
        DemoServerManager.DEMO_CLIENTS.indices.count { index ->
            viewModel.sessions.any {
                it.title == DemoServerManager.clientName(index) &&
                    it.connectionState.value == FixConnectionState.LOGGED_ON
            }
        }

    private fun portIsFree(port: Int): Boolean =
        runCatching { java.net.ServerSocket(port).close() }.isSuccess

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
        return predicate()
    }
}
