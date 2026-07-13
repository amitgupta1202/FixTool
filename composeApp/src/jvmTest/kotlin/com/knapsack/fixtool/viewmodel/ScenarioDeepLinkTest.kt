package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.NotificationType
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The failure → editor deep-link ([FixMessageViewModel.openScenarioEditorForFailure]): a failed
 * assertion in the session window opens the workbench editor on the exact expect step, with the
 * failed tags and the actual message in hand — and degrades gracefully when the scenario was
 * deleted or reshaped since the run.
 */
class ScenarioDeepLinkTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-deeplink", "").apply {
            delete()
            mkdirs()
        }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun failedMessage(): FixMessage {
        val message = quickfix.Message()
        message.header.setString(35, "8")
        message.setString(150, "8") // ExecType = REJECTED, say
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|35=8|150=8|10=000|",
            messageType = "8",
            quickfixMessage = message,
        )
    }

    private fun scenarioWithExpect(id: String = "sc-1"): Scenario =
        Scenario(
            id = id,
            name = "fill check",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields = listOf(FieldExpectation(150, Matcher.Exact("F"))),
                                messageType = "8",
                            ),
                    ),
                ),
        )

    private fun failedStepResult(stepIndex: Int = 1, phase: String = "steps"): StepResult =
        StepResult(
            stepIndex = stepIndex,
            kind = "expect",
            phase = phase,
            passed = false,
            tags =
                listOf(
                    TagResult(150, "exact F", "F", "8", passed = false),
                    TagResult(37, "presence", "<present>", "OID-1", passed = true),
                    TagResult(448, "exact B", "B", "X", passed = false, index = 5, occurrence = 1),
                ),
        )

    @Test
    fun `failure opens the workbench focused on the failing expect step`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult()))

        viewModel.openScenarioEditorForFailure(msg)

        val request = viewModel.workbenchEditRequest.value
        assertNotNull(request)
        assertEquals(scenario.id, request.scenario.id)
        assertEquals(1, request.focusStep)
        // Only the failed tags travel, each still naming the occurrence it checked.
        assertEquals(listOf(150 to 0, 448 to 1), request.failedTags.map { it.tag to it.occurrence })
        assertEquals(msg.rawMessage, request.actualRaw)
        assertTrue(viewModel.showScenariosDialog.value)

        viewModel.consumeWorkbenchEditRequest()
        assertNull(viewModel.workbenchEditRequest.value)
    }

    @Test
    fun `deleted scenario yields an error and no request`() {
        val scenario = scenarioWithExpect()
        // Never saved — same as deleted-since-run.
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult()))

        viewModel.openScenarioEditorForFailure(msg)

        assertNull(viewModel.workbenchEditRequest.value)
        assertTrue(viewModel.notifications.any { it.type == NotificationType.ERROR && "no longer saved" in it.message })
    }

    @Test
    fun `reshaped scenario opens unfocused with a note instead of the wrong step`() {
        val scenario = scenarioWithExpect()
        // Saved shape differs: the run's step index 1 is now a Send, not an Expect.
        val reshaped = scenario.copy(steps = listOf(scenario.steps[1], ScenarioStep.Send("35=D|")))
        assertTrue(viewModel.scenarioService.save(reshaped))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult(stepIndex = 1)))

        viewModel.openScenarioEditorForFailure(msg)

        val request = viewModel.workbenchEditRequest.value
        assertNotNull(request)
        assertNull(request.focusStep)
        assertTrue(viewModel.notifications.any { it.type == NotificationType.INFO && "changed since this run" in it.message })
    }

    @Test
    fun `setup phase failures open unfocused - the editor only edits steps`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult(stepIndex = 1, phase = "setup")))

        viewModel.openScenarioEditorForFailure(msg)

        val request = viewModel.workbenchEditRequest.value
        assertNotNull(request)
        assertNull(request.focusStep)
    }
}
