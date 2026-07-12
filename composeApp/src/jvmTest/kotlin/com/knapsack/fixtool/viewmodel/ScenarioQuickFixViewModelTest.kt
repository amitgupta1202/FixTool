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
import com.knapsack.fixtool.service.AssertionQuickFixes.Kind
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The quick-fix batch flow end to end in the ViewModel: chips draft pending edits (toggle = undo),
 * Save writes the scenario file once and flips the edited rows green against the failed message,
 * Discard drops the drafts, and a new run invalidates leftovers.
 */
class ScenarioQuickFixViewModelTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-quickfix", "").apply {
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
        message.setString(150, "8")
        message.setString(31, "1.3")
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|35=8|150=8|31=1.3|10=000|",
            messageType = "8",
            quickfixMessage = message,
        )
    }

    private fun scenario(): Scenario =
        Scenario(
            id = "sc-qf",
            name = "fill check",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields =
                                    listOf(
                                        FieldExpectation(150, Matcher.Exact("F")),
                                        FieldExpectation(31, Matcher.Numeric(1.2345, 0.0)),
                                        FieldExpectation(37, Matcher.Presence),
                                    ),
                                messageType = "8",
                            ),
                    ),
                ),
        )

    private fun failedStep(): StepResult =
        StepResult(
            stepIndex = 1,
            kind = "expect",
            phase = "steps",
            passed = false,
            tags =
                listOf(
                    TagResult(150, "exact F", "F", "8", passed = false),
                    TagResult(31, "numeric 1.2345", "1.2345", "1.3", passed = false),
                    TagResult(37, "presence", "<present>", null, passed = false),
                ),
        )

    @Test
    fun `save writes the scenario once, clears pending, and flips edited rows green`() {
        val sc = scenario()
        assertTrue(viewModel.scenarioService.save(sc))
        val msg = failedMessage()
        viewModel.noteScenarioRun(sc)
        viewModel.setAssertionResults(mapOf(msg to failedStep()))

        val t150 = failedStep().tags[0]
        val t31 = failedStep().tags[1]
        val t37 = failedStep().tags[2]
        viewModel.toggleAssertionQuickFix(t150, Kind.ACCEPT_ACTUAL)
        viewModel.toggleAssertionQuickFix(t31, Kind.LOOSEN_TO_PRESENCE)
        viewModel.toggleAssertionQuickFix(t37, Kind.DROP)
        // Toggling the same verb again is an undo; a different verb replaces.
        viewModel.toggleAssertionQuickFix(t37, Kind.DROP)
        viewModel.toggleAssertionQuickFix(t37, Kind.ACCEPT_ACTUAL)
        assertEquals(3, viewModel.pendingAssertionEdits.size)

        viewModel.saveAssertionQuickFixes(msg)

        // Pending cleared; scenario on disk updated.
        assertEquals(0, viewModel.pendingAssertionEdits.size)
        val saved = viewModel.scenarioService.load(sc.id)!!
        val expect = saved.steps[1] as ScenarioStep.Expect
        assertEquals(Matcher.Exact("8"), expect.expectation.fields.single { it.tag == 150 }.matcher)
        assertEquals(Matcher.Presence, expect.expectation.fields.single { it.tag == 31 }.matcher)
        // 37 was absent in the actual → accept-actual becomes an Absent assertion.
        assertEquals(Matcher.Absent, expect.expectation.fields.single { it.tag == 37 }.matcher)

        // Edited rows re-evaluated against the failed message: all green now.
        val newStep = viewModel.assertionResults[msg]!!
        assertTrue(newStep.passed)
        assertTrue(newStep.tags.all { it.passed })
        assertTrue(viewModel.notifications.any { it.type == NotificationType.SUCCESS && "re-run" in it.message })
    }

    @Test
    fun `discard drops drafts and a new run invalidates leftovers`() {
        val sc = scenario()
        assertTrue(viewModel.scenarioService.save(sc))
        val msg = failedMessage()
        viewModel.noteScenarioRun(sc)
        viewModel.setAssertionResults(mapOf(msg to failedStep()))

        viewModel.toggleAssertionQuickFix(failedStep().tags[0], Kind.DROP)
        assertEquals(1, viewModel.pendingAssertionEdits.size)
        viewModel.discardAssertionQuickFixes()
        assertEquals(0, viewModel.pendingAssertionEdits.size)

        viewModel.toggleAssertionQuickFix(failedStep().tags[0], Kind.DROP)
        viewModel.setAssertionResults(emptyMap()) // a fresh run resets results
        assertEquals(0, viewModel.pendingAssertionEdits.size)
    }

    @Test
    fun `save against a deleted scenario keeps the drafts and reports the error`() {
        val sc = scenario() // never saved
        val msg = failedMessage()
        viewModel.noteScenarioRun(sc)
        viewModel.setAssertionResults(mapOf(msg to failedStep()))
        viewModel.toggleAssertionQuickFix(failedStep().tags[0], Kind.DROP)

        viewModel.saveAssertionQuickFixes(msg)

        assertEquals(1, viewModel.pendingAssertionEdits.size)
        assertTrue(viewModel.notifications.any { it.type == NotificationType.ERROR && "no longer saved" in it.message })
    }
}
