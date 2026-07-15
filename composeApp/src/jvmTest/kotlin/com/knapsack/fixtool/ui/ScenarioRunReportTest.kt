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
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.ui.diff.EditOp
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
    fun `a failed run offers the route to the diff, and it lands there`() {
        stageFailedRun(failedMessage())
        composeTestRule.setContent { RailAndDocuments(viewModel) }

        composeTestRule.onNodeWithTag("reconcile-failure").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-failure").performClick()
        composeTestRule.waitForIdle()

        // The route opened the failing step's diff — the one surface that can repair an assertion — in its own
        // window (Phase 6), bound to the bytes that failed it. The window is a top-level composition, not part
        // of this test's; that it *renders* is DiffSurfaceTest's job, and that the route *reaches* it is this.
        val window = viewModel.openDiffWindows.value.single()
        assertEquals(stepId(), window.stepId)
        val verdict = window.session?.model?.verdict
        assertTrue(verdict?.needsAttention == true, "and it agrees with the run that this failed")
    }

    /** The single open diff window — every test here opens exactly one. */
    private fun onlyWindow() = viewModel.openDiffWindows.value.single()

    /** The step this scenario's run failed at, by identity. */
    private fun stepId(): String =
        viewModel
            .scenarioDraft(scenario.id)!!
            .draft.steps[1]
            .stepId

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

        assertEquals(stepId(), onlyWindow().stepId, "the step the rail named, not the first one")
    }

    /**
     * **The session survives the main window doing whatever it likes (F5).** A repair staged in the diff, and
     * its undo stack, live in the ViewModel's `openDiffWindows`, not in the diff window's composable — so a
     * glance at the session grid, a focused editor tab, anything the main window does, cannot touch them. (The
     * *reason* changed with Phase 6: a window is not disposed when you look away, unlike the tab it replaced;
     * but Save & re-run, cross-window arming, and reopen still need the session reachable from here.)
     */
    @Test
    fun `a staged repair and its undo stack live in the ViewModel, untouched by the main window`() {
        stageFailedRun(failedMessage())
        composeTestRule.setContent { RailAndDocuments(viewModel) }
        composeTestRule.onNodeWithTag("reconcile-failure").performClick()
        composeTestRule.waitForIdle()

        // Accept the actual on the failing 150 row — one staged repair, applied to the window's own session.
        val session = onlyWindow().session!!
        session.apply(EditOp.acceptActual(0, 150, "8"))
        assertEquals(1, session.staged)
        assertTrue(viewModel.scenarioDraft(scenario.id)!!.dirty, "the scenario knows it is holding an edit")

        viewModel.showSessions() // the author glances at the session grid — the diff WINDOW is untouched by this
        composeTestRule.waitForIdle()

        // The repair is still staged and ⌘Z still has somewhere to go, because the session was never in a
        // composable that the main window could dispose.
        val after = onlyWindow().session!!
        assertEquals(1, after.staged)
        assertTrue(after.canUndo)
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
