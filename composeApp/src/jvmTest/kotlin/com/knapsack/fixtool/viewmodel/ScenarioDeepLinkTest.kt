package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.DiffWindowState
import com.knapsack.fixtool.ui.diff.EditOp
import com.knapsack.fixtool.ui.diff.EditResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FixMessageViewModel.reconcileRoute] — the one rule that decides whether a failed step can be taken to the
 * reconcile view — and the two doors that must both obey it: the run report's "Reconcile assertions →"
 * button and the session window's "Reconcile assertions…".
 *
 * The reconcile view is where a tired engineer clicks buttons to turn a red build green, so every refusal
 * here is load-bearing: what it lets through, "Accept actual" will write into the scenario.
 */
class ScenarioDeepLinkTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-deeplink", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val soh = '\u0001'

    /**
     * The venue's actual bytes: 37 before 11 — a wire order `toString()` cannot produce, because it sorts the
     * body by ascending tag — and a `|` *inside* tag 58's value.
     */
    private val wireBytes =
        listOf("8=FIX.4.4", "35=8", "37=OID-1", "11=X", "150=8", "58=Rejected|insufficient margin", "10=000")
            .joinToString("$soh", postfix = "$soh")

    /**
     * The display string for that same message, and everything wrong with it: it comes from
     * `quickfix.Message.toString()`, so the body is re-sorted ascending (11 before 37), and SOH is
     * substituted with `|` — which leaves 58's embedded pipe indistinguishable from a delimiter. Reconciling
     * against this would diff the expectation against a message no venue sent. It exists here to be the
     * wrong answer that the code must not reach for.
     */
    private val displayString = "8=FIX.4.4|35=8|11=X|37=OID-1|58=Rejected|insufficient margin|150=8|10=000|"

    private fun failedMessage(wire: String? = wireBytes): FixMessage {
        val message = quickfix.Message()
        message.header.setString(35, "8")
        message.setString(150, "8") // ExecType = REJECTED, say
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = displayString,
            messageType = "8",
            quickfixMessage = message,
            wireRaw = wire,
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

    private fun refusal(step: StepResult): String {
        val route = viewModel.reconcileRoute(step)
        assertTrue(route is FixMessageViewModel.ReconcileRoute.Refused, "expected a refusal, got: $route")
        return route.why
    }

    private fun opened(step: StepResult): FixMessageViewModel.ScenarioEditRequest {
        val route = viewModel.reconcileRoute(step)
        assertTrue(route is FixMessageViewModel.ReconcileRoute.Open, "expected an open route, got: $route")
        return route.request
    }

    // ----- the happy path ------------------------------------------------------------------------------

    @Test
    fun `failure opens a diff window on the failing expect step`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult()))

        viewModel.openScenarioEditorForFailure(msg)

        // The destination is the DIFF — the one surface that can repair an assertion — in its own dedicated
        // window (Phase 6), bound to this run's failing bytes. It is not a document: the centre pane's active
        // document is untouched.
        val window = viewModel.openDiffWindows.value.single()
        val stepId =
            viewModel
                .scenarioDraft(scenario.id)!!
                .draft.steps[1]
                .stepId
        assertNull(viewModel.activeDocumentId.value, "the diff is a window, not the active document")
        assertEquals(DiffWindowState.diffWindowId(scenario.id, stepId), window.id)
        assertEquals(scenario.id, window.scenarioId)
        assertEquals(stepId, window.stepId, "by the step's identity, not by where it sits today")
        assertEquals(wireBytes, window.thisRunWire)
        assertEquals(ReferenceMessage.Provenance.THIS_RUN, window.session!!.reference.provenance)
        // The draft is the SCENARIO's, not the window's, and it opens clean: nothing has been edited, so closing
        // it must not stop to ask.
        val workspace = viewModel.scenarioDraft(scenario.id)!!
        assertEquals(scenario.id, workspace.draft.id)
        assertFalse(workspace.dirty)
        assertFalse(window.session.isDirty)
    }

    /**
     * **The two documents of one scenario, and the one draft underneath them.**
     *
     * The editor tab is open and carrying an unsaved rename; the failure deep-links to the diff window. The
     * editor tab and the diff window now both look at this scenario, and there must still be exactly one draft:
     * the rename survives, and it is visible from the view that did not make it. Two drafts here is the
     * two-editing-surfaces defect, and it ends with one view's Save writing the other's work away.
     */
    @Test
    fun `the diff window and the editor are two views of one draft`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult()))

        viewModel.openScenarioEditor(scenario)
        viewModel.updateScenarioDraft(scenario.id) { it.copy(draft = it.draft.copy(name = "renamed, unsaved")) }

        viewModel.openScenarioEditorForFailure(msg)

        assertEquals(1, viewModel.openDocuments.value.size, "the editor tab")
        assertEquals(1, viewModel.openDiffWindows.value.size, "and the diff window")
        assertEquals(
            1,
            viewModel.openScenarios.value.size,
            "and ONE draft under them — two would be the defect this all exists to prevent",
        )
        val workspace = viewModel.scenarioDraft(scenario.id)!!
        assertEquals("renamed, unsaved", workspace.draft.name, "the diff did not re-seed the scenario off disk")
        assertTrue(workspace.dirty)

        // An edit made in the diff lands in the same draft the editor is looking at.
        val window = viewModel.openDiffWindows.value.single()
        assertIs<EditResult.Applied>(window.session!!.apply(EditOp.drop(0, 150)), "a repair, staged in the diff")
        val expectation = (viewModel.scenarioDraft(scenario.id)!!.draft.steps[1] as ScenarioStep.Expect).expectation
        assertTrue(expectation.fields.none { it.tag == 150 }, "and the editor's draft has it too")
        assertEquals("renamed, unsaved", viewModel.scenarioDraft(scenario.id)!!.draft.name, "without losing the rename")
    }

    /**
     * A second failure of a step whose diff window is already open, **carrying staged repairs**, must land on
     * that window — re-aimed at the new bytes (and raised to the front), not rebuilt. Throwing the author's work
     * away to show them a diff would be a strange way to help them fix it.
     */
    @Test
    fun `a second deep-link re-aims the open diff window instead of rebuilding it`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult()))

        viewModel.openScenarioEditorForFailure(msg)
        val first = viewModel.openDiffWindows.value.single()
        assertIs<EditResult.Applied>(first.session!!.apply(EditOp.drop(0, 150)))
        val epochBefore = first.focusEpoch

        viewModel.openScenarioEditorForFailure(msg)

        val window = viewModel.openDiffWindows.value.single()
        assertEquals(1, viewModel.openDiffWindows.value.size, "one diff window for the step, not two")
        assertSame(first.session, window.session, "the same session — the undo stack and the staged count survive")
        assertEquals(1, window.session!!.staged, "and it is still holding the repair")
        assertEquals(epochBefore + 1, window.focusEpoch, "which the window sees as a new epoch, and raises itself on")
    }

    /**
     * The reconcile view diffs — and "Accept actual" then *saves* — whatever this hands it. Hand it the
     * display string and the author would be shown entries the venue never moved (toString relocates every
     * repeating group and sorts the body), offered "Accept new order" on them, and would save an order
     * nobody sent, plus a `58` truncated at its embedded pipe.
     */
    @Test
    fun `the route carries the venue's wire bytes, never the display string`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        val step = failedStepResult()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        val request = opened(step)

        assertEquals(wireBytes, request.actualRaw)
        assertTrue(request.actualRaw!!.contains("37=OID-1${soh}11=X"), "wire order (37 before 11) must survive")
        assertTrue(request.actualRaw != msg.rawMessage)
    }

    // ----- the refusals. Each one is a false green that did not happen. ---------------------------------

    /** The two-Expect scenario the index-versus-identity cases are argued on, already identified. */
    private fun partialFills(): Scenario =
        Scenario(
            id = "sc-edit",
            name = "partial fills",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(fields = listOf(FieldExpectation(151, Matcher.Exact("750000"))), messageType = "8"),
                    ),
                    ScenarioStep.Expect(
                        expectation =
                            Expectation(fields = listOf(FieldExpectation(151, Matcher.Exact("0"))), messageType = "8"),
                    ),
                ),
        ).withIds()

    /**
     * THE FALSE GREEN THE REVIEW FOUND. A run addressed its step by *index*, and nothing invalidates a run
     * when the scenario is edited. Delete a step above the failure and index 1 holds a **different** Expect —
     * so the reconcile view would diff *that* step's expectation against the failing step's message, and
     * "Accept actual" would overwrite its matchers and golden with bytes the venue never sent for it. The
     * scenario goes green while asserting a response it was never supposed to get.
     *
     * The step now carries an id, so the route does not have to reason about indices at all: it asks for the
     * step that ran, by name. Here the author has *edited that very step*, so there is nothing to reconcile
     * against — the failure describes an expectation that no longer exists.
     */
    @Test
    fun `the step that failed, edited since the run, is refused`() {
        val scenario = partialFills()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        val step = failedStepResult(stepIndex = 1) // the 25%-fill Expect is what failed
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        // The author retunes the 25%-fill assertion and saves. Same step, same id, different assertion.
        val edited =
            scenario.copy(
                steps =
                    scenario.steps.mapIndexed { i, s ->
                        if (i != 1) {
                            s
                        } else {
                            (s as ScenarioStep.Expect).copy(
                                expectation = Expectation(fields = listOf(FieldExpectation(151, Matcher.Exact("500000"))), messageType = "8"),
                            )
                        }
                    },
            )
        assertTrue(viewModel.scenarioService.save(edited))

        assertTrue("changed since this run" in refusal(step), "the refusal must say the step moved under the run")

        viewModel.openScenarioEditorForFailure(msg)
        assertTrue(
            viewModel.openDiffWindows.value.isEmpty(),
            "reconciling here would diff an expectation the author has since rewritten — refused, so no window",
        )
    }

    /**
     * **And the half the old rule got wrong.** It refused the route whenever *anything* in the scenario had
     * changed — so renaming step 1, or deleting a Send above the failure, withdrew the fix for step 2. The
     * author's only way back was to run the whole scenario again, which on a slow venue is minutes, and the
     * refusal did not even hint that the edit they had made was harmless.
     *
     * An edit to another step is exactly as relevant as it sounds: not at all. The failing step is unchanged,
     * so it still reconciles — and the route lands on it **where it is now**, not where it ran.
     */
    @Test
    fun `an edit to a different step still routes, focused where that step now sits`() {
        val scenario = partialFills()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        val step = failedStepResult(stepIndex = 1) // the 25%-fill Expect, at index 1 when it ran
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        // The author deletes the Send above it and saves. The failing Expect is untouched — and is now at
        // index 0, which is precisely the case an index-keyed route could not survive.
        assertTrue(viewModel.scenarioService.save(scenario.copy(steps = scenario.steps.drop(1))))

        val request = opened(step)

        assertEquals(0, request.focusStep, "the route follows the step, not the slot it used to occupy")
        assertEquals(
            scenario.steps[1],
            request.scenario.steps[0],
            "and it is the same step — same id, same assertion — that failed",
        )
    }

    /**
     * **THE CORRUPTION AN ID IS SUPPOSED TO PREVENT, WALKED BACK IN THROUGH THE ID ITSELF.**
     *
     * A minted id is a hash of (scenario, phase, index). `withIds` used to mint for the first blank id it met
     * *before* the steps below it had claimed theirs — so a step inserted at index 1 was minted with the id
     * the existing step at index 1 was already carrying, took it, and pushed that step onto its own
     * successor's id. Every step below the insertion slid one place down the id list.
     *
     * Here the two Expects await two fills that happen to look alike, and one of them failed. After the
     * insert, the failing step's id names the *other* Expect — and because the two are equal, the "is this
     * still the step that ran?" check passed. The route opened on the wrong Expect, and one click of "Accept
     * actual" would have written the failing message's bytes into an assertion that never saw it: the exact
     * false green the id was introduced to close, produced by the id.
     */
    @Test
    fun `a step inserted above the failure does not re-point the route at another step`() {
        val scenario =
            Scenario(
                id = "sc-twins",
                name = "two fills of the same shape",
                steps =
                    listOf(
                        ScenarioStep.Send("35=D|11=X|"),
                        ScenarioStep.Expect(
                            expectation =
                                Expectation(fields = listOf(FieldExpectation(150, Matcher.Exact("F"))), messageType = "8"),
                        ),
                        ScenarioStep.Expect(
                            expectation =
                                Expectation(fields = listOf(FieldExpectation(150, Matcher.Exact("F"))), messageType = "8"),
                        ),
                    ),
            ).withIds()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        // The SECOND of the twins is what failed.
        val step = failedStepResult(stepIndex = 2).copy(stepId = scenario.steps[2].stepId)
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        // The author inserts a step at the top and saves. Nothing about the failing step has changed.
        assertTrue(
            viewModel.scenarioService.save(
                scenario.copy(steps = listOf(ScenarioStep.Send("35=D|11=Y|")) + scenario.steps),
            ),
        )

        val request = opened(step)

        assertEquals(
            3,
            request.focusStep,
            "the failing Expect is the LAST step now — routing to the twin above it would let Accept actual " +
                "write this message's bytes into an assertion that never saw it",
        )
        assertEquals(
            scenario.steps[2].stepId,
            request.scenario.steps[3].stepId,
            "and the step it landed on is the step that ran, by name",
        )
    }

    /** A step that is gone is gone: there is no expectation left to reconcile the failure against. */
    @Test
    fun `the step that failed, deleted since the run, is refused`() {
        val scenario = partialFills()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        val step = failedStepResult(stepIndex = 1)
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        assertTrue(viewModel.scenarioService.save(scenario.copy(steps = scenario.steps.filterIndexed { i, _ -> i != 1 })))

        val why = refusal(step)
        assertTrue("no longer in scenario" in why && "deleted" in why, why)
    }

    /**
     * No wire bytes, no diff. The engine refuses to judge such a step and capture refuses to seed from one;
     * the reconcile view must refuse too, rather than fall back to the re-serialised display string and let
     * the author accept a body the venue never sent.
     */
    @Test
    fun `a step with no wire bytes is refused, and says whose fault it is`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage(wire = null)
        val step = failedStepResult()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        val why = refusal(step)
        assertTrue("no wire bytes" in why && "FixTool limitation" in why, why)

        viewModel.openScenarioEditorForFailure(msg)
        assertTrue(viewModel.openDiffWindows.value.isEmpty(), "refused: no diff window opened")
        assertTrue(viewModel.notifications.any { "no wire bytes" in it.message })
    }

    /** An Expect that timed out matched nothing, so there is no actual message to diff against. */
    @Test
    fun `an expect step that matched no message is refused`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(emptyMap())

        assertTrue("No message matched" in refusal(failedStepResult()))
    }

    /** A run of an inline, never-persisted scenario (the control surface mints one) has nothing to edit. */
    @Test
    fun `an unsaved scenario is refused, and the session-window door refuses it too`() {
        val scenario = scenarioWithExpect()
        val msg = failedMessage()
        val step = failedStepResult()
        viewModel.noteScenarioRun(scenario) // never saved
        viewModel.setAssertionResults(mapOf(msg to step))

        assertTrue("not saved" in refusal(step))

        viewModel.openScenarioEditorForFailure(msg)
        assertNull(viewModel.activeDocumentId.value)
        assertTrue(viewModel.notifications.any { "not saved" in it.message })
    }

    /**
     * The editor edits `steps`, so a setup-phase failure has no step for it to open on — and the refusal must
     * leave the author somewhere to go, which is what the report's old pointer sentence did.
     */
    @Test
    fun `a setup phase failure is refused, and points at the session window`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        val step = failedStepResult(phase = "setup")
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to step))

        val why = refusal(step)
        assertTrue("setup" in why && "session window" in why, "a refusal must leave the author somewhere to go: $why")
    }

    /**
     * Both doors, one decider. The session window's "Reconcile assertions…" used to be ungated: on a
     * setup-phase failure it opened an unfocused editor with no failure in it, under a note claiming the
     * scenario had "changed since this run" when nothing had changed at all.
     */
    @Test
    fun `the session window door refuses exactly what the run report refuses`() {
        val scenario = scenarioWithExpect()
        assertTrue(viewModel.scenarioService.save(scenario))
        val msg = failedMessage()
        viewModel.noteScenarioRun(scenario)
        viewModel.setAssertionResults(mapOf(msg to failedStepResult(phase = "setup")))

        viewModel.openScenarioEditorForFailure(msg)

        assertTrue(viewModel.openDiffWindows.value.isEmpty(), "the message viewer's door must honour the same rule")
        assertTrue(viewModel.notifications.any { "setup" in it.message })
    }

    /**
     * The `phase` half of the (phase, stepIndex) key, pinned. `setup` and `steps` index independently, so a
     * scenario with an Expect at index 1 in *each* has two entries under the same index — and the setup one
     * is first in the map. Keying on stepIndex alone hands the steps-phase failure the **setup** step's
     * message, and "Accept actual" then saves the logon ack's body as the fill's golden.
     */
    @Test
    fun `the same step index in a different phase resolves to its own message`() {
        val scenario =
            Scenario(
                id = "sc-phases",
                name = "logon then fill",
                setup =
                    listOf(
                        ScenarioStep.Send("35=A|"),
                        ScenarioStep.Expect(expectation = Expectation(fields = emptyList(), messageType = "A")),
                    ),
                steps =
                    listOf(
                        ScenarioStep.Send("35=D|"),
                        ScenarioStep.Expect(expectation = Expectation(fields = emptyList(), messageType = "8")),
                    ),
            )
        assertTrue(viewModel.scenarioService.save(scenario))
        val ackMessage = failedMessage(wire = null) // setup's message: no wire bytes, so it CANNOT be reconciled
        val fillMessage = failedMessage() // steps' message: has them, so it can
        val setupStep = failedStepResult(stepIndex = 1, phase = "setup")
        val stepsStep = failedStepResult(stepIndex = 1, phase = "steps")
        viewModel.noteScenarioRun(scenario)
        // The setup entry is FIRST in the map — a stepIndex-only lookup returns it for both steps.
        viewModel.setAssertionResults(linkedMapOf(ackMessage to setupStep, fillMessage to stepsStep))

        // Resolving the steps-phase failure must reach the fill's bytes, not the ack's (which are absent —
        // so a stepIndex-only lookup would refuse this with "no wire bytes" and hide the reconcile route).
        assertEquals(wireBytes, opened(stepsStep).actualRaw)
    }
}
