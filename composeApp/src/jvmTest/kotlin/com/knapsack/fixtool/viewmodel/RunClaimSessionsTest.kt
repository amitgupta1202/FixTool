package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A run claims the session it will drive, whatever name its steps use for it.**
 *
 * The host resolves a step's session by title, by id and by index into the window's sessions, but the run
 * slot compared the names as written. An inline scenario naming `"0"` and a rail scenario naming `"UAT"`
 * (session 0) were judged disjoint, so both drove one session, and one run's ClearMessages setup could
 * wipe the other's log mid-run.
 */
class RunClaimSessionsTest {
    private lateinit var testDir: File
    private lateinit var viewModel: FixMessageViewModel

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-claim-test", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        viewModel.requestScenarioStop()
        eventually { !viewModel.scenarioRunning.value }
        testDir.deleteRecursively()
    }

    @Test
    fun `a run naming a session by its index waits for the run naming it by title`() {
        val uat = viewModel.createSessionForTest("UAT")
        holdUat()

        val byIndex = viewModel.runScenarioBlocking(parkedOn(viewModel.sessions.indexOf(uat).toString()))

        assertNull(byIndex, "session ${viewModel.sessions.indexOf(uat)} is UAT, and UAT is taken")
        assertTrue("UAT" in viewModel.runBusyReason(), "the refusal names the session: ${viewModel.runBusyReason()}")
    }

    @Test
    fun `a run naming a session by its id waits for the run naming it by title`() {
        val uat = viewModel.createSessionForTest("UAT")
        holdUat()

        assertNull(viewModel.runScenarioBlocking(parkedOn(uat.id)), "the id is UAT's, and UAT is taken")
    }

    /** A name that resolves to no session is claimed as written, and so blocks nothing it does not name. */
    @Test
    fun `a run naming a session that does not exist does not wait for an unrelated one`() {
        viewModel.createSessionForTest("UAT")
        holdUat()

        assertNotNull(viewModel.runScenarioBlocking(parkedOn("NOSUCH")), "it runs, and fails in its own preflight")
    }

    /** A run parked on UAT for as long as the test needs it: a Wait for a logon that never comes. */
    private fun holdUat() {
        viewModel.runScenario(parkedOn("UAT", timeoutMs = 20_000))
        eventually { "UAT" in viewModel.busySessions.value }
        assertTrue("UAT" in viewModel.busySessions.value, "the first run never claimed UAT")
    }

    private fun parkedOn(session: String, timeoutMs: Long = 300) =
        Scenario(
            id = "on-$session",
            name = "on $session",
            steps = listOf(ScenarioStep.Wait(session = session, state = "LOGGED_ON", timeoutMs = timeoutMs)),
        )

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}
