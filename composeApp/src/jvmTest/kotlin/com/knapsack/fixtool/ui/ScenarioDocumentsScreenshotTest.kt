package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.ui.diff.EditOp
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
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
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The gate for this phase, and it is not the assertions below.**
 *
 * Phase 1's worst defect — a gutter offering Accept-actual on a *moved* row, one click from deleting an
 * assertion — was found by looking at the picture with fourteen tests green. A UI phase is gated by its
 * screenshots; the tests only pin what you already thought to ask.
 *
 * So this drives the real ViewModel through the real rail, the real tab strip and the real document host —
 * clicking, as a tester clicks — and writes a picture at each step of the loop Phase 2 exists to make
 * possible: a failure in the rail, the reconcile diff in a document tab beside the session tabs, an unsaved
 * edit, and the confirmation that stands between that edit and a stray click on the tab's `×`.
 */
class ScenarioDocumentsScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outDir = File("build/scenario-screenshots").absoluteFile
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-docshots", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val wire =
        listOf("8=FIX.4.4", "35=8", "11=ORD-7f3a", "150=0", "39=0", "151=500000", "10=000")
            .joinToString("", postfix = "")

    private val scenario =
        Scenario(
            id = "sc-shot",
            name = "rfq flow v2",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=ORD-7f3a|55=EUR/USD|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields =
                                    listOf(
                                        FieldExpectation(150, Matcher.Exact("F")),
                                        FieldExpectation(151, Matcher.Numeric(0.0)),
                                    ),
                                messageType = "8",
                                mode = MatchMode.OPEN,
                            ),
                    ),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(
                                fields = listOf(FieldExpectation(39, Matcher.Exact("2"))),
                                messageType = "8",
                                mode = MatchMode.OPEN,
                            ),
                    ),
                ),
        )

    private val failedStep =
        StepResult(
            stepIndex = 1,
            kind = "expect",
            phase = "steps",
            passed = false,
            detail = "messageType=8",
            tags =
                listOf(
                    TagResult(150, "exact F", "F", "0", passed = false),
                    TagResult(151, "numeric 0", "0", "500000", passed = false),
                ),
        )

    private val passedStep = StepResult(stepIndex = 0, kind = "send", phase = "steps", passed = true)

    /** The state a real failing run leaves behind: a matched message, one step green, one red, one unreached. */
    private fun stageFailedRun() {
        // Two live sessions, because the claim being made is that documents sit in the *same strip* as them —
        // and a strip with no session tabs in it cannot show that.
        viewModel.createSessionForTest("QUOTE")
        viewModel.createSessionForTest("TRADE")
        assertTrue(viewModel.scenarioService.save(scenario))
        val message =
            FixMessage(
                timestamp = LocalDateTime.of(2026, 7, 14, 9, 35, 44),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = wire.replace('', '|'),
                quickfixMessage = Message(),
                wireRaw = wire,
            )
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(message to failedStep))
        viewModel.publishScenarioResult(ScenarioResult(scenario.name, passed = false, steps = listOf(passedStep, failedStep)))
    }

    /**
     * The main window, as `App` composes it: the rail docked left, the document tabs in the same strip as the
     * session tabs, and the active document holding the centre. No second window — that is the phase.
     */
    @Composable
    private fun MainWindow() {
        val documents by viewModel.openDocuments.collectAsState()
        val workspace by viewModel.openScenarios.collectAsState()
        val activeId by viewModel.activeDocumentId.collectAsState()
        val confirming by viewModel.confirmingCloseId.collectAsState()
        val active = documents.firstOrNull { it.id == activeId }
        Row(modifier = Modifier.size(1600.dp, 900.dp).background(AppTheme.Colors.background)) {
            ScenariosRail(viewModel, modifier = Modifier.size(300.dp, 900.dp))
            Column(modifier = Modifier.fillMaxSize()) {
                TabBar(
                    sessions = viewModel.sessions,
                    activeIndex = viewModel.activeSessionIndex,
                    viewMode = com.knapsack.fixtool.model.FixMessageSession.ViewMode.PARSED,
                    onTabClick = { index ->
                        viewModel.setActiveSession(index)
                        viewModel.showSessions()
                    },
                    onCloseTab = {},
                    onToggleWrapText = {},
                    onConnect = {},
                    onDisconnect = {},
                    documents = documentTabsOf(documents, workspace),
                    activeDocumentId = activeId,
                    confirmingCloseId = confirming,
                    onFocusDocument = { viewModel.focusDocument(it) },
                    onRequestCloseDocument = { viewModel.requestCloseDocument(it) },
                    onConfirmCloseDocument = { viewModel.closeDocument(it) },
                    onCancelCloseDocument = { viewModel.cancelCloseDocument() },
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    if (active != null) ScenarioDocumentPane(viewModel, active)
                }
            }
        }
    }

    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ScenarioDocumentsScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /** The diff window for the step this scenario's run failed at. */
    private val diffWindowId: String
        get() =
            DiffWindowState.diffWindowId(
                scenario.id,
                viewModel
                    .scenarioDraft(scenario.id)!!
                    .draft.steps[1]
                    .stepId,
            )

    private fun diffSession() = viewModel.diffWindow(diffWindowId)!!.session!!

    /**
     * W1, the daily loop: the rail names the failure, one click opens the diff **window**, the repair is staged,
     * and closing the last view of the dirty scenario stops to ask. The diff surface is a top-level window (not
     * part of this composition — its pixels are `DiffSurfaceTest`/`ReferenceSlotScreenshotTest`, its live loop is
     * the Phase 6 gate); this pins the rail picture and the loop's state through the ViewModel.
     */
    @Test
    fun `the failure, the diff window it opens, the edit, and the confirmation`() {
        stageFailedRun()
        composeTestRule.setContent { MainWindow() }
        composeTestRule.waitForIdle()

        // 1 — the rail is the run report: the tree opened itself on the scenario that failed, the failing step
        // carries Reconcile →, and the step below it says it was never reached.
        composeTestRule.onNodeWithTag("rail-reconcile-1").assertIsDisplayed()
        // The runner stops at the first failure, so step 3 was never judged — and it says so. A bare "–"
        // would read as "it ran, and there was nothing to report", which is the wrong thing to believe.
        composeTestRule.onNodeWithText("not reached", substring = true).assertIsDisplayed()
        snapshot("phase2_rail_failed_run.png")

        // 2 — one click, and the DIFF opens in its own window (Phase 6), on the step that failed.
        composeTestRule.onNodeWithTag("rail-reconcile-1").performClick()
        composeTestRule.waitForIdle()
        val window = viewModel.openDiffWindows.value.single()
        assertEquals(diffWindowId, window.id, "the one surface that can repair an assertion, in its own window")

        // 3 — a repair, staged in the session and written into the scenario's draft. Nothing is on disk.
        diffSession().apply(EditOp.acceptActual(0, 150, "0"))
        assertTrue(viewModel.scenarioDraft(scenario.id)!!.dirty)
        assertEquals(1, diffSession().staged)

        // 4 — closing the window is the last view of a dirty scenario (the editor tab is not open), so it asks.
        viewModel.requestCloseDiffWindow(diffWindowId)
        assertEquals(diffWindowId, viewModel.confirmingCloseId.value, "the × asks before it throws the repair away")

        // Keep, and the window is still there with the edit in it.
        viewModel.cancelCloseDocument()
        assertTrue(viewModel.openDiffWindows.value.isNotEmpty(), "Keep must keep it")
        assertTrue(viewModel.scenarioDraft(scenario.id)!!.dirty)
    }

    /**
     * The session tab is the way back to the sessions, and it is the *only* thing that clears the centre's
     * document selection. It must not close the document, and it must not have moved the active session while
     * the document was up — the message editor, `fixtool_send` and the grid's tint all follow that.
     */
    @Test
    fun `opening the diff window leaves the main window's session view exactly as it was`() {
        stageFailedRun()
        viewModel.setActiveSession(1) // TRADE
        composeTestRule.setContent { MainWindow() }

        composeTestRule.onNodeWithTag("rail-reconcile-1").performClick()
        composeTestRule.waitForIdle()

        // The diff opens *beside* context (its own window, §1c), not instead of it: the active session is
        // untouched — the message editor, `fixtool_send` and the grid's tint all follow that — and the centre
        // pane is still the sessions, not a document.
        assertTrue(viewModel.openDiffWindows.value.isNotEmpty(), "the diff opened, in its own window")
        assertTrue(viewModel.activeSessionIndex == 1, "opening the diff window did not move the active session")
        assertTrue(viewModel.activeDocumentId.value == null, "and the centre is still the sessions, not a document")
        snapshot("phase6_diff_window_leaves_sessions.png")
    }

    /**
     * Capture review as a document: the session grid it was scanned from is a tab away, not a window away.
     * Its curation lives in the document, so the trip to that grid and back cannot discard it.
     */
    @Test
    fun `capture review is a document, and its curation survives the trip to the grid`() {
        val session = viewModel.createSessionForTest("QUOTE")
        listOf(
            "8=FIX.4.435=D11=ORD-155=EUR/USD10=000" to FixMessage.Direction.OUTGOING,
            "8=FIX.4.435=811=ORD-1150=039=010=000" to FixMessage.Direction.INCOMING,
        ).forEachIndexed { i, (raw, dir) ->
            session.addMessage(
                FixMessage(
                    timestamp = LocalDateTime.of(2026, 7, 14, 9, 0, i),
                    direction = dir,
                    rawMessage = raw.replace('', '|'),
                    quickfixMessage = Message(),
                    wireRaw = raw,
                ),
            )
        }
        session.flushMessageQueue() // addMessage only enqueues; the UI thread is what publishes
        val scan = viewModel.captureScan()
        assertTrue(scan.candidates.size == 2, "the scan sees both business messages, got ${scan.candidates.size}")
        composeTestRule.setContent { MainWindow() }

        composeTestRule.onNodeWithTag("rail-capture").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.activeDocument is ScenarioDoc.Capture, "capture opened as a document")
        composeTestRule.onNodeWithTag("capture-name").performTextReplacement("rfq")

        // Selecting a candidate selects the message it was scanned FROM, in its session grid and the detail
        // panel. That link is the reason capture is a tab and not a window: in a window there was nothing on
        // the other end of it.
        composeTestRule.onNodeWithTag("candidate-1").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.selectedMessage.value?.messageType == "8", "the ExecutionReport is selected in the grid")

        composeTestRule.onNodeWithTag("candidate-check-0").performClick() // untick the send
        composeTestRule.waitForIdle()
        snapshot("phase2_document_tab_capture.png")

        val curated = (viewModel.activeDocument as ScenarioDoc.Capture).state
        assertTrue(!curated.includes(0) && curated.includes(1), "the untick landed in the document")

        // The whole point of it being a tab: go and look at the grid it came from, and come back.
        viewModel.showSessions()
        composeTestRule.waitForIdle()
        viewModel.focusDocument(ScenarioDoc.CAPTURE_ID)
        composeTestRule.waitForIdle()

        assertTrue((viewModel.activeDocument as ScenarioDoc.Capture).state == curated, "the curation survived")
        composeTestRule.onNodeWithTag("capture-name").assertTextContains("rfq")
    }

    /**
     * Save writes, the tab **stays open and goes clean** — a tab is a document, not a modal — and the session
     * is **rebased**: the footer stops counting edits that are now on disk, because *"nothing is written to the
     * scenario until you save"* is a promise, and after a Save it has been kept.
     */
    @Test
    fun `save writes the repair, and the footer stops promising to`() {
        stageFailedRun()
        composeTestRule.setContent { MainWindow() }
        composeTestRule.onNodeWithTag("rail-reconcile-1").performClick()
        composeTestRule.waitForIdle()

        diffSession().apply(EditOp.acceptActual(0, 150, "0"))
        assertEquals(1, diffSession().staged)

        assertTrue(viewModel.saveScenario(scenario.id))

        val session = diffSession()
        assertTrue(!viewModel.scenarioDraft(scenario.id)!!.dirty, "saved, so the close will not stop to ask")
        assertEquals(0, session.staged, "and the footer no longer counts an edit that is already on disk (rebased)")
        assertTrue(!session.isDirty)
        // The repair is on disk: the venue's ExecType is what the step now expects.
        val onDisk = viewModel.scenarioService.load(scenario.id)!!.steps[1] as ScenarioStep.Expect
        assertEquals(
            "0",
            (
                onDisk.expectation.fields
                    .single { it.tag == 150 }
                    .matcher as Matcher.Exact
            ).value,
        )
    }
}
