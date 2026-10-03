package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.integration.settled
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.ExampleWorkspaces
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.WorkspacePaths
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **What a workspace switch refuses, and what it leaves behind.**
 *
 * Opening, closing and resetting a workspace point every store at another folder. Whatever still holds the
 * previous workspace's state at that moment carries it into the new one: a run set that is halfway through
 * loads the rest of its scenarios from the new folder and writes its records there, and its sessions are
 * torn down under it. So a switch is refused while a run holds sessions, in the sentence Close all uses for
 * a load run, and the run and the workspace it started in are both left as they were.
 */
class WorkspaceSwitchTest {
    private lateinit var home: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("switch-home").toFile()
        WorkspacePaths.use(home.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = home.absolutePath)
    }

    @After
    fun cleanup() {
        viewModel.requestScenarioStop()
        awaitCondition(10_000) { !viewModel.scenarioRunning.value }
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    private fun workspace(name: String) = File(home, "workspaces/$name").apply { mkdirs() }

    /** Parked in a Wait for a logon that never comes, so a run of it holds S until it is stopped. */
    private val parked =
        Scenario(
            id = "parked",
            name = "parked",
            steps = listOf(ScenarioStep.Wait(session = "S", state = "LOGGED_ON", timeoutMs = 20_000)),
        )

    /** A run set over [parked], started in whatever workspace is open, and holding S by the time this returns. */
    private fun startParkedRunSet() {
        viewModel.createSessionForTest("S")
        assertTrue(viewModel.scenarioService.save(parked))
        val set = assertNotNull(viewModel.startRunSet(RunSets.repeat(parked, times = 2, now = System.currentTimeMillis())))
        assertTrue(awaitCondition(5_000) { viewModel.isRunSetRunning(set.id) }, "the set should be holding S")
    }

    @Test
    fun `opening another workspace while a run set holds sessions is refused, and the run keeps them`() {
        val alpha = workspace("alpha")
        val beta = workspace("beta")
        viewModel.openWorkspace(alpha).getOrThrow()
        startParkedRunSet()

        val opened = viewModel.openWorkspace(beta)

        assertEquals("A run set is running. Stop it first.", opened.exceptionOrNull()?.message)
        assertEquals(alpha, viewModel.openWorkspace, "the workspace the run started in is still the open one")
        assertEquals(listOf("S"), viewModel.sessions.map { it.title }, "and the run's session is still up")
        assertTrue(
            viewModel.notifications.any { it.message == "A run set is running. Stop it first." },
            "the refusal is said, not only returned: ${viewModel.notifications.map { it.message }}",
        )
    }

    @Test
    fun `closing the workspace while a scenario run holds sessions is refused`() {
        val alpha = workspace("alpha")
        viewModel.openWorkspace(alpha).getOrThrow()
        viewModel.createSessionForTest("S")
        viewModel.runScenario(parked)
        assertTrue(awaitCondition(5_000) { "S" in viewModel.busySessions.value }, "the run should be holding S")

        viewModel.closeWorkspace()

        assertEquals(alpha, viewModel.openWorkspace, "the workspace stays open while the run holds its session")
        assertEquals(listOf("S"), viewModel.sessions.map { it.title })
        assertTrue(
            viewModel.notifications.any { it.message == "A scenario run is running. Stop it first." },
            "${viewModel.notifications.map { it.message }}",
        )
    }

    @Test
    fun `resetting an example while a run set holds sessions is refused, and the copy is not moved aside`() {
        val example = viewModel.openExample(ExampleWorkspaces.FX_VENUE).getOrThrow()
        startParkedRunSet()

        val reset = viewModel.resetOpenExample()

        assertEquals("A run set is running. Stop it first.", reset.exceptionOrNull()?.message)
        assertEquals(example, viewModel.openWorkspace)
        val movedAside =
            ExampleWorkspaces
                .defaultLocation()
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("fx-venue-before-reset-") }
        assertEquals(emptyList(), movedAside, "a refused reset must not have put the copy aside")
    }

    /**
     * The case the refusal exists for most: a load run's lanes, torn down mid-run, are its measurements.
     * A venue that never answers keeps the run waiting for its lanes, which is all this needs.
     */
    @Test
    fun `opening another workspace while a load run holds its lanes is refused in the load run's own words`() {
        val alpha = workspace("alpha")
        viewModel.openWorkspace(alpha).getOrThrow()
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG",
                        targetCompID = "V",
                        host = "localhost",
                        port = "1",
                        socketConnectHost = "localhost",
                        autoReconnect = false,
                    ),
            ),
        )
        viewModel.loadLogonWaitMs = 3_000
        val plan =
            LoadPlan(
                id = "x",
                label = "NOS on LOADGEN",
                template = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${messageIndex}")),
                profileId = "lg",
                profileName = "LOADGEN",
                shape = LoadShape.Burst(10),
                match = LoadMatch(11),
            )
        val started = assertNotNull(viewModel.startLoadRun(plan))
        assertTrue(viewModel.isLoadRunning(started.id))

        val opened = viewModel.openWorkspace(workspace("beta"))

        assertEquals("A load run is running. Stop it first.", opened.exceptionOrNull()?.message)
        assertEquals(alpha, viewModel.openWorkspace)
    }

    @Test
    fun `once the run has stopped the switch goes through`() {
        val alpha = workspace("alpha")
        val beta = workspace("beta")
        viewModel.openWorkspace(alpha).getOrThrow()
        startParkedRunSet()
        viewModel.requestScenarioStop()
        assertTrue(awaitCondition(10_000) { !viewModel.scenarioRunning.value }, "the set should stop when asked")

        viewModel.openWorkspace(beta).getOrThrow()

        assertEquals(beta, viewModel.openWorkspace)
    }

    /** Polled against a snapshot, because the sessions list is written on the view model's own dispatcher. */
    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(100)
        }
    }
}
