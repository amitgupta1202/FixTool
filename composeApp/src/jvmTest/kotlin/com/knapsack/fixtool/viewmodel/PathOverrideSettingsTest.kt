package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.integration.settled
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.ui.FixField
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Clearing a path override in Settings hands that store back to the workspace on Save, not on restart.**
 *
 * The three stores a legacy override can point (profiles, saved messages, scenarios) read it once, when they
 * are built. Settings > Storage > Clear > Save changed the setting and left every store where it was, so the
 * rail kept listing the override directory and new captures kept landing in it until the next launch.
 *
 * Each test starts a second view model over the same settings, because that is how an override comes to be in
 * effect: it was there when the app started.
 */
class PathOverrideSettingsTest {
    private lateinit var home: File
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("override-home").toFile()
        WorkspacePaths.use(home.absolutePath)
    }

    @After
    fun cleanup() {
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    private fun viewModel() = FixMessageViewModel(testSettingsDir = home.absolutePath)

    private fun scenario(id: String) = Scenario(id = id, name = id, steps = listOf(ScenarioStep.Send("35=D|11=$id|")))

    @Test
    fun `clearing the scenarios override moves the rail and the next save back to the workspace`() {
        val override = File(home, "elsewhere/scenarios").apply { mkdirs() }
        val before = viewModel()
        before.scenarioService.save(scenario("in-workspace"))
        before.saveAppSettings(before.appSettings.copy(scenariosPath = override.absolutePath))
        val viewModel = viewModel()
        viewModel.scenarioService.save(scenario("in-override"))
        assertEquals(listOf("in-override"), viewModel.scenarios.value.map { it.id }, "the override is in effect")
        val overrideFiles = override.listFiles().orEmpty().size

        viewModel.saveAppSettings(viewModel.appSettings.copy(scenariosPath = ""))

        assertEquals(listOf("in-workspace"), viewModel.scenarios.value.map { it.id }, "the rail reads the workspace")
        viewModel.scenarioService.save(scenario("captured-after"))
        assertEquals(overrideFiles, override.listFiles().orEmpty().size, "and a new capture does not land in the override")
        assertEquals(setOf("in-workspace", "captured-after"), viewModel.scenarios.value.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun `clearing the profiles override reloads the profiles from the workspace`() {
        val override = File(home, "elsewhere").apply { mkdirs() }
        val before = viewModel()
        before.saveConnectionProfile(profile("in-workspace"))
        before.saveAppSettings(before.appSettings.copy(connectionProfilesPath = File(override, "profiles.json").absolutePath))
        val viewModel = viewModel()
        viewModel.saveConnectionProfile(profile("in-override"))
        assertEquals(listOf("in-override"), viewModel.connectionProfiles.map { it.id }, "the override is in effect")

        viewModel.saveAppSettings(viewModel.appSettings.copy(connectionProfilesPath = ""))

        assertEquals(listOf("in-workspace"), viewModel.connectionProfiles.map { it.id }, "the profiles are the workspace's")
    }

    @Test
    fun `clearing the saved messages override reloads the templates from the workspace`() {
        val override = File(home, "elsewhere").apply { mkdirs() }
        val before = viewModel()
        before.saveConnectionProfile(profile("desk"))
        before.saveEditorMessage("workspace NOS", listOf(FixField("35", "D")), "desk")
        before.saveAppSettings(before.appSettings.copy(savedMessagesPath = File(override, "messages.json").absolutePath))
        val viewModel = viewModel()
        viewModel.saveEditorMessage("override NOS", listOf(FixField("35", "D")), "desk")
        assertEquals(listOf("override NOS"), viewModel.savedMessages.map { it.name }, "the override is in effect")

        viewModel.saveAppSettings(viewModel.appSettings.copy(savedMessagesPath = ""))

        assertEquals(listOf("workspace NOS"), viewModel.savedMessages.map { it.name }, "the templates are the workspace's")
    }

    /**
     * Now that a Settings save moves the scenarios store, it must not move it under a run set in flight: the
     * rest of the set would look its scenarios up in the new directory and skip each one it could not find.
     * A set reads the stores it started with, the way a load run already did.
     */
    @Test
    fun `a run set in flight keeps reading the scenarios it started with when the override changes`() {
        val viewModel = viewModel()
        viewModel.createSessionForTest("S")
        // Parked in a Wait for a logon that never comes, so each entry runs for its timeout and then fails.
        val waits =
            Scenario(
                id = "waits",
                name = "waits",
                steps = listOf(ScenarioStep.Wait(session = "S", state = "LOGGED_ON", timeoutMs = 1_500)),
            )
        viewModel.scenarioService.save(waits)
        val set = assertNotNull(viewModel.startRunSet(RunSets.repeat(waits, times = 2, now = System.currentTimeMillis())))
        try {
            assertTrue(awaitCondition(5_000) { viewModel.isRunSetRunning(set.id) }, "the set should be running")

            val elsewhere = File(home, "elsewhere/scenarios").apply { mkdirs() }
            viewModel.saveAppSettings(viewModel.appSettings.copy(scenariosPath = elsewhere.absolutePath))

            assertTrue(awaitCondition(15_000) { !viewModel.isRunSetRunning(set.id) }, "the set should finish")
            val entries = assertNotNull(viewModel.activeRunSet.value).entries
            assertEquals(
                listOf(RunState.FAILED, RunState.FAILED),
                entries.map { it.state },
                "both entries ran the scenario the set started with: ${entries.map { it.note }}",
            )
        } finally {
            viewModel.requestScenarioStop()
            awaitCondition(10_000) { !viewModel.scenarioRunning.value }
        }
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

    private fun profile(id: String) =
        FixConnectionProfile(id = id, name = id, config = FixConnectionConfig(senderCompID = id, targetCompID = "V"))
}
