package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The run report, driven the way a tester drives it — and the reason this file exists.
 *
 * Every reconcile test in this repo verified the *data path*: given an expectation and an actual, does the
 * diff come out right. All of them passed while the feature was, in practice, unreachable — a scenario run
 * announced its failure in the workbench, named the failed tags, and then told the author to go back to the
 * main window and hunt for the message. The route from *"this failed"* to *"here is the diff that fixes it"*
 * did not exist, and no test noticed, because no test ever clicked through the workflow.
 *
 * So this test starts where the failure is announced and clicks. It wires the real ViewModel to the real
 * rail and the real document host — the two composables [App] itself composes — and asserts you can get from
 * a failed run to the reconcile view without touching anything else.
 */
class ScenarioRunReportTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-runreport", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val wireBytes =
        listOf("8=FIX.4.4", "35=8", "11=X", "150=8", "151=500000", "10=000")
            .joinToString("\u0001", postfix = "\u0001")

    private val scenario =
        Scenario(
            id = "sc-run",
            name = "book-a-trade",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields =
                                    listOf(
                                        FieldExpectation(150, Matcher.Exact("2")),
                                        FieldExpectation(151, Matcher.Numeric(0.0)),
                                    ),
                                messageType = "8",
                                golden = wireBytes,
                            ),
                    ),
                ),
        )

    private fun failedMessage(wire: String? = wireBytes): FixMessage {
        val message = quickfix.Message()
        message.header.setString(35, "8")
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = "8=FIX.4.4|35=8|11=X|150=8|151=500000|10=000|",
            messageType = "8",
            quickfixMessage = message,
            wireRaw = wire,
        )
    }

    /** The failed expect step, as the runner publishes it: matched a message, two tags red. */
    private val failedStep =
        StepResult(
            stepIndex = 1,
            kind = "expect",
            phase = "steps",
            passed = false,
            detail = "messageType=8",
            tags =
                listOf(
                    TagResult(150, "exact 2", "2", "8", passed = false),
                    TagResult(151, "numeric 0", "0", "500000", passed = false),
                ),
        )

    /** Puts the ViewModel in the state a real failing run leaves it in. */
    private fun stageFailedRun(message: FixMessage) {
        assertTrue(viewModel.scenarioService.save(scenario))
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(message to failedStep))
        viewModel.publishScenarioResult(ScenarioResult(scenario.name, passed = false, steps = listOf(failedStep)))
    }

    /**
     * The whole of defect #1: from the report of the failure, one click reaches the diff that fixes it.
     * Before this, the report ended at "go and find the message yourself".
     */
    @Test
    fun `a failed run offers the route to the reconcile view, and it lands there`() {
        stageFailedRun(failedMessage())
        composeTestRule.setContent { RailAndDocuments(viewModel) }

        composeTestRule.onNodeWithTag("reconcile-failure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-failure").performClick()
        composeTestRule.waitForIdle()

        // Landed on the failing step's diff — in a document tab, in the main window, not an unfocused editor
        // and not a second window.
        composeTestRule.onNodeWithTag("reconcile-view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-summary").assertIsDisplayed()
        assertEquals(ScenarioDoc.editorId(scenario.id), viewModel.activeDocumentId.value)
    }

    /**
     * The rail's own door. The run line answers "what failed"; the tree answers "where", and the step that
     * failed carries the route on the row that names it.
     */
    @Test
    fun `the failing step in the rail's tree routes to the same diff`() {
        stageFailedRun(failedMessage())
        composeTestRule.setContent { RailAndDocuments(viewModel) }

        // The tree opens itself on the scenario that just failed — a failure the author cannot see is a
        // failure they will not fix.
        composeTestRule.onNodeWithTag("rail-reconcile-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rail-reconcile-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reconcile-view").assertIsDisplayed()
        val doc = viewModel.activeDocument as ScenarioDoc.Editor
        assertEquals(1, doc.focusStep, "the step the rail named, not the first one")
    }

    /**
     * The tab is not a window, and only the active document is composed: switch to the sessions and back, and
     * an edit that lived in the composable would be gone. This is the regression Phase 2 could most easily
     * have shipped — a document tab is *worse* than the window it replaced unless the document owns its state.
     */
    @Test
    fun `an unsaved edit survives a trip to the session view and back`() {
        stageFailedRun(failedMessage())
        composeTestRule.setContent { RailAndDocuments(viewModel) }
        composeTestRule.onNodeWithTag("reconcile-failure").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scenario-name").performTextReplacement("renamed but not saved")
        composeTestRule.waitForIdle()
        val id = viewModel.activeDocumentId.value!!
        assertTrue(viewModel.scenarioDraft(scenario.id)!!.dirty, "the scenario knows it is holding an edit")

        viewModel.showSessions() // the author glances at the session grid — the document is disposed
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scenario-name").assertDoesNotExist()

        viewModel.focusDocument(id)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scenario-name").assertTextContains("renamed but not saved")
        assertEquals("renamed but not saved", viewModel.scenarioDraft(scenario.id)!!.draft.name)
    }

    /**
     * A step the engine refused to judge (no wire bytes, so the venue's field order is unknown) offers no
     * route, because there is no honest diff at the end of it: the view would have to reconstruct the
     * message from `toString()`, show entries the venue never moved, and let the author accept them.
     *
     * And it must SAY so. Silently omitting the button is how the author concludes the feature does not
     * exist — which is exactly what happened with the reorder arrows. So this asserts the report is still
     * there and carries the reason, not merely that the button is gone: an absence assertion on its own
     * passes just as happily when the whole report has vanished.
     */
    @Test
    fun `a failure with no wire bytes offers no route, and says why`() {
        stageFailedRun(failedMessage(wire = null))
        composeTestRule.setContent { RailAndDocuments(viewModel) }

        composeTestRule.onNodeWithTag("run-failure-line").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-failure").assertDoesNotExist()
        composeTestRule.onNodeWithTag("reconcile-refused").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-refused").assertTextContains(
            "no wire bytes",
            substring = true,
        )
    }

    /** The rail and the document host, wired exactly as `App` wires them. */
    @Composable
    private fun RailAndDocuments(viewModel: FixMessageViewModel) {
        Row(modifier = Modifier.fillMaxSize()) {
            ScenariosRail(viewModel, modifier = Modifier.weight(0.35f))
            val documents by viewModel.openDocuments.collectAsState()
            val activeId by viewModel.activeDocumentId.collectAsState()
            val active = documents.firstOrNull { it.id == activeId }
            Box(modifier = Modifier.weight(0.65f)) {
                if (active != null) ScenarioDocumentPane(viewModel, active)
            }
        }
    }
}
