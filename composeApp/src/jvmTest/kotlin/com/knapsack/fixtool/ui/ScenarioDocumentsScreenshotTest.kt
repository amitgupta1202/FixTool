package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
import com.knapsack.fixtool.ui.diff.EditOp
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
import kotlin.test.assertNull
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
     * The main window, as `App` composes it: the rail docked left, the sessions as the centre "code area", and
     * the scenario editor riding beneath them in the bottom dock (its own tab strip, resize and minimize). No
     * second window — that is the phase.
     */
    @Composable
    private fun MainWindow() {
        Column(modifier = Modifier.size(1600.dp, 900.dp).background(AppTheme.Colors.background)) {
            // The rail and the sessions share the upper row; the dock spans the full width beneath them —
            // mirroring App()'s structure, so a document is never coupled to the session view mode.
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ScenariosRail(viewModel, modifier = Modifier.width(300.dp).fillMaxHeight())
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    TabBar(
                        sessions = viewModel.sessions,
                        activeSession = viewModel.activeSession,
                        viewMode = com.knapsack.fixtool.model.FixMessageSession.ViewMode.PARSED,
                        onTabClick = { session -> viewModel.setActiveSessionByObject(session) },
                        onCloseTab = {},
                        onToggleWrapText = {},
                        onConnect = {},
                        onDisconnect = {},
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
            ScenarioDock(viewModel)
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

    /** This scenario's diff window — one per scenario, whichever of its steps it happens to be showing. */
    private val diffWindowId: String get() = DiffWindowState.diffWindowId(scenario.id)

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
     * The toolbar capture goes straight into the editor — as a **draft**. Nothing reaches disk until Save:
     * the click-to-look used to write a "Captured scenario" file every time, and the store filled with
     * identical twins nothing but a step count could tell apart. The draft is the scenario's, not the
     * tab's, so the trip to the session grid and back cannot discard it — and it opens dirty, so closing
     * its last view asks first.
     */
    @Test
    fun `toolbar capture opens an unsaved editor draft, and nothing reaches disk until Save`() {
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

        // Capture lives in the rail's "+ New ▾" menu now — one door to the three ways of creating a scenario.
        composeTestRule.onNodeWithTag("rail-new-menu").performClick()
        composeTestRule.onNodeWithTag("rail-capture").performClick()
        composeTestRule.waitForIdle()

        val doc = viewModel.activeDocument
        assertTrue(doc is ScenarioDoc.Editor, "capture opened the editor, got $doc")
        val scenarioId = (doc as ScenarioDoc.Editor).scenarioId
        assertTrue(
            viewModel.scenarioService.load(scenarioId) == null,
            "nothing reached disk — the author has not chosen to keep this",
        )
        val draft = viewModel.scenarioDraft(scenarioId)
        assertTrue(draft != null && draft.dirty, "the draft opens dirty, so closing its last view asks first")
        assertTrue(draft!!.draft.steps.size == 2, "both messages became steps, got ${draft.draft.steps.size}")
        assertTrue(draft.draft.name.startsWith("Capture "), "named by time, not a fixed twin-maker: '${draft.draft.name}'")
        snapshot("capture_to_editor_unsaved_draft.png")

        // The whole point of the draft living on the scenario: a glance at the grid and back loses nothing.
        viewModel.showSessions()
        composeTestRule.waitForIdle()
        viewModel.focusDocument(ScenarioDoc.editorId(scenarioId))
        composeTestRule.waitForIdle()
        assertTrue(viewModel.scenarioDraft(scenarioId)!!.draft == draft.draft, "the draft survived the trip")

        // Save is the author choosing to keep it: the file appears, and the draft goes clean.
        assertTrue(viewModel.saveScenario(scenarioId))
        assertTrue(viewModel.scenarioService.load(scenarioId) != null, "Save wrote it")
        assertTrue(viewModel.scenarioDraft(scenarioId)?.dirty == false, "and the tab went clean")
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

    @Test
    fun `the rail's Diff messages opens an empty plain diff viewer`() {
        composeTestRule.setContent { MainWindow() }
        composeTestRule.waitForIdle()
        // The viewer's door lives behind the rail's ⋯ menu — a utility, not a creation action.
        composeTestRule.onNodeWithTag("rail-more").performClick()
        composeTestRule.onNodeWithTag("rail-diff-messages").performClick()
        composeTestRule.waitForIdle()
        val viewer = viewModel.openDiffViewers.value.single()
        assertNull(viewer.session, "it opens empty — two slots to fill, not a diff against nothing yet")
    }

    /**
     * **The rail after its cleanup (Option B):** rows carry an identity line — steps · sessions · file date —
     * instead of a standing four-icon strip; the actions appear on the hovered row and nowhere else; and the
     * filter finds a scenario by name or by the session it drives, answering with a sentence rather than a
     * blank pane when nothing matches.
     */
    @Test
    fun `the rail filters by name and session, and hides actions until hover`() {
        assertTrue(viewModel.scenarioService.save(scenario)) // "rfq flow v2", no per-step session
        assertTrue(
            viewModel.scenarioService.save(
                Scenario(
                    id = "sc-md",
                    name = "market data burst",
                    steps =
                        listOf(
                            ScenarioStep.Send("35=V|262=REQ1|", "MD_CLIENT2"),
                            ScenarioStep.Expect(session = "MD_CLIENT2", expectation = Expectation(emptyList(), messageType = "W")),
                        ),
                ),
            ),
        )
        composeTestRule.setContent { MainWindow() }
        composeTestRule.waitForIdle()

        // The identity line is what tells same-named captures apart — here it names the session and count.
        composeTestRule.onNodeWithText("2 steps · MD_CLIENT2", substring = true).assertIsDisplayed()
        // No standing icon strip: the run button exists only once the pointer is on the row.
        composeTestRule.onAllNodesWithTag("run-sc-md").assertCountEquals(0)
        composeTestRule.onNodeWithTag("scenario-row-sc-md").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("run-sc-md").assertIsDisplayed()
        snapshot("rail_option_b.png")

        // Filter by the SESSION: the MD scenario stays, the rfq one goes.
        composeTestRule.onNodeWithTag("rail-filter").performTextReplacement("MD_CLIENT")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scenario-row-sc-md").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("scenario-row-sc-shot").assertCountEquals(0)

        // And a query nothing matches gets a sentence, not a pane that looks like deleted scenarios.
        composeTestRule.onNodeWithTag("rail-filter").performTextReplacement("zzz")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail-filter-empty").assertIsDisplayed()
    }

    /**
     * **The editor is a bottom dock now, not a pane in the session split.** Opening it must not touch the
     * session strip: the sessions stay the centre "code area", and the editor — its own tab and body — rides
     * beneath them in the dock. This is the coupling the change exists to remove: where the editor appears no
     * longer depends on the session view mode.
     */
    @Test
    fun `the scenario editor opens in the bottom dock, beneath the sessions`() {
        stageFailedRun() // two live sessions + the saved scenario
        viewModel.openScenarioEditor(scenario)
        composeTestRule.setContent { MainWindow() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scenario-dock").assertIsDisplayed()
        // The editor's own tab, in the dock's strip — and its body, since it opens expanded.
        composeTestRule.onNodeWithTag("doc-tab-${ScenarioDoc.editorId(scenario.id)}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scenario-name").assertIsDisplayed()
        assertEquals(ScenarioDoc.editorId(scenario.id), viewModel.activeDocumentId.value)
        assertTrue(!viewModel.scenarioDockMinimized.value, "it opens expanded")
        snapshot("dock_editor_open.png")
    }

    /**
     * Minimize is what makes the dock usable — collapse it to its header when you want the sessions to
     * yourself. And the rule that makes minimize usable in turn: clicking a step in the rail is
     * `openScenarioEditor(focusStep=…)`, and that must bring the editor straight back, at that step. Without
     * the restore, a minimized dock would swallow every subsequent click into the rail.
     */
    @Test
    fun `minimize collapses the dock, and opening a step restores it`() {
        stageFailedRun()
        viewModel.openScenarioEditor(scenario)
        composeTestRule.setContent { MainWindow() }
        composeTestRule.waitForIdle()

        // Minimize: the flag flips and the chevron becomes a Restore affordance.
        composeTestRule.onNodeWithTag("scenario-dock-minimize").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.scenarioDockMinimized.value, "the dock is minimized")
        composeTestRule.onNodeWithContentDescription("Restore Edit Scenario").assertIsDisplayed()

        // A rail step click (openScenarioEditor with a focusStep) restores the dock, at that step.
        viewModel.openScenarioEditor(scenario, focusStep = 2)
        composeTestRule.waitForIdle()
        assertTrue(!viewModel.scenarioDockMinimized.value, "opening a step restored the dock")
        composeTestRule.onNodeWithContentDescription("Minimize Edit Scenario").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scenario-name").assertIsDisplayed()
        snapshot("dock_restore_on_step.png")
    }
}
