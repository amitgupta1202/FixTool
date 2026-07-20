package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.withIds
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.DiffStepRef
import com.knapsack.fixtool.ui.DiffWindowState
import com.knapsack.fixtool.ui.diff.EditOp
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **A reconcile pass is one act, and it happens in one window.**
 *
 * The runner stops at the first failure, so a scenario that diverges from its environment in three places
 * surfaces one step per run: repair, Save & re-run, meet the next one. Keyed on the step, that loop opened a
 * *new window* at every stop and left the finished ones on screen showing green rows — ten repairs, ten
 * windows to dismiss. These are the rules that make the window the pass instead of the step.
 */
class ReconcilePassTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-pass", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val soh = "\u0001"

    private fun wire(vararg fields: String): String =
        (listOf("8=FIX.4.4", "35=8", "11=X") + fields + "10=000").joinToString(soh, postfix = soh)

    /** Three Expects, so a pass has somewhere to travel. */
    private val scenario =
        Scenario(
            id = "sc-1",
            name = "rfq flow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    expect(150, "0"),
                    expect(151, "0"),
                    expect(152, "0"),
                ),
        )

    /**
     * **Two fields, deliberately.** A travelled repair and an edit the author makes by hand must be able to
     * land on *different* rows of the same step — with one field they would always collide on index 0, and a
     * session working from a stale draft would be indistinguishable from one that had caught up.
     */
    private fun expect(tag: Int, value: String) =
        ScenarioStep.Expect(
            expectation =
                Expectation(
                    fields =
                        listOf(
                            FieldExpectation(tag, Matcher.Exact(value)),
                            FieldExpectation(tag + 100, Matcher.Exact(value)),
                        ),
                    messageType = "8",
                ),
        )

    private fun saved(): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario))
        return viewModel.scenarioService.load(scenario.id)!!
    }

    private fun stepIds(onDisk: Scenario) =
        onDisk
            .withIds()
            .steps
            .drop(1)
            .map { it.stepId }

    private fun message(raw: String) =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 20, 9, 41, 2),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw.replace(soh[0], '|'),
            quickfixMessage = Message(),
            wireRaw = raw,
        )

    private fun windowId() = DiffWindowState.diffWindowId("sc-1")

    private fun window() = viewModel.diffWindow(windowId())!!

    /** A run that failed at [failAt] (an index into the Expect steps), with a reply bound for every step. */
    private fun runFailingAt(onDisk: Scenario, failAt: Int) {
        val ids = stepIds(onDisk)
        viewModel.noteScenarioRun(onDisk)
        val results =
            ids.mapIndexed { i, id -> StepResult(i + 1, "expect", "steps", i != failAt, stepId = id) }
        viewModel.setAssertionResults(
            ids.mapIndexed { i, _ -> message(wire("${150 + i}=X$i")) to results[i] }.toMap(),
        )
        viewModel.publishScenarioResult(ScenarioResult(onDisk.name, passed = false, steps = results))
    }

    // ------------------------------------------------------------------ one window, however many steps

    /**
     * **The defect this whole design exists to remove.** Repair step 1, re-run, meet step 2, repair, re-run,
     * meet step 3: three stops of one pass. Keyed on the step that was three windows, two of them finished and
     * still on screen. It is one.
     */
    @Test
    fun `a pass that walks three failing steps opens one window, not three`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)

        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        viewModel.openDiffWindow(onDisk, ids[2], thisRunWire = wire("152=X"))

        assertEquals(1, viewModel.openDiffWindows.value.size, "one pass, one window")
        assertEquals(ids[2], window().stepId, "showing the step the pass has reached")
        assertEquals(setOf(ids[0], ids[1], ids[2]), window().slots.keys, "and carrying all three")
    }

    /**
     * **A revisited step keeps its slot.** The invariant the strip hangs on: rebuild it here and clicking back
     * to a step the author repaired an hour ago silently discards the repair and the undo stack that made it.
     */
    @Test
    fun `walking away from a step and back keeps its session, its staging and its undo stack`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        val first = window().slots[ids[0]]!!.session!!
        first.apply(EditOp.setMatcher(0, 150, Matcher.Exact("REPAIRED")))
        assertEquals(1, first.staged)

        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        viewModel.showStepInDiffWindow(windowId(), ids[0])

        val back = window().slots[ids[0]]!!.session!!
        assertSame(first, back, "the same session, not a fresh one over the same step")
        assertEquals(1, back.staged, "and it still has what was staged in it")
        assertTrue(back.canUndo, "and the undo stack that got it there")
    }

    /**
     * A chip click is not a deep-link. The window is already in front, so raising it would steal focus from a
     * value field the author is halfway through typing into.
     */
    @Test
    fun `the strip moves the view without raising the window, and a deep-link raises it`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        runFailingAt(onDisk, 1)
        val epoch = window().focusEpoch

        viewModel.showStepInDiffWindow(windowId(), ids[1])
        assertEquals(ids[1], window().stepId, "the view moved")
        assertEquals(epoch, window().focusEpoch, "but nothing raised itself at the author")

        viewModel.openDiffWindow(onDisk, ids[2], thisRunWire = wire("152=X"))
        assertEquals(epoch + 1, window().focusEpoch, "a deep-link is news, and raises")
    }

    @Test
    fun `the strip refuses a step this scenario does not have`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))

        viewModel.showStepInDiffWindow(windowId(), "no-such-step")

        assertEquals(ids[0], window().stepId, "an id that names nothing moves nothing")
    }

    /**
     * A step the author has not opened yet still has a reply, if the run produced one — the strip must be able
     * to reach it, not just the steps the loop happened to stop at.
     */
    @Test
    fun `jumping to an unvisited step builds its slot from the run that just happened`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        runFailingAt(onDisk, 0)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X0"))

        viewModel.showStepInDiffWindow(windowId(), ids[2])

        val slot = window().slots[ids[2]]
        assertNotNull(slot, "the strip reached it")
        assertNotNull(slot.session, "and it has this run's reply to diff against")
    }

    // ------------------------------------------------------------------ every slot, not just the visible one

    /**
     * **The write-amplification trap.** `rebindDiffWindows` used to write the window back inside its own loop;
     * with more than one slot each write re-read a window the loop had already moved on from, and every slot
     * after the first lost its rebind — silently, and invisibly to any single-slot test.
     */
    @Test
    fun `a new run re-binds every slot the window holds, not just the first`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=OLD"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=OLD"))
        viewModel.openDiffWindow(onDisk, ids[2], thisRunWire = wire("152=OLD"))

        runFailingAt(onDisk, 0)

        ids.forEachIndexed { i, id ->
            assertEquals(
                wire("${150 + i}=X$i"),
                window().slots[id]!!.thisRunWire,
                "slot ${i + 1} must carry what THIS run produced for it",
            )
        }
    }

    /** Save is the scenario's, so it settles every slot's baseline — not only the one on screen. */
    @Test
    fun `saving rebases every slot, so no step goes on counting edits that are already on disk`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        val one = window().slots[ids[0]]!!.session!!
        val two = window().slots[ids[1]]!!.session!!
        one.apply(EditOp.setMatcher(0, 150, Matcher.Exact("A")))
        two.apply(EditOp.setMatcher(0, 151, Matcher.Exact("B")))

        assertTrue(viewModel.saveScenario("sc-1"))

        assertEquals(0, one.staged, "the visible step's footer told the truth after a save")
        assertEquals(0, two.staged, "and so did the one behind it")
    }

    // ------------------------------------------------------------------ travelling repairs (C2)

    /**
     * **The headline regression.** A repair that travels into a sibling step is written straight into the
     * draft, bypassing that step's session. If the session is not told, its next `onChange` writes its stale
     * expectation back and **silently reverts the travelled repair** — work the author watched happen, undone
     * with no message.
     *
     * Latent while a window was one step (it needed two windows open on one scenario). Routine now that one
     * window holds every step the pass has visited.
     */
    @Test
    fun `a repair that travelled into a step survives the next edit made in that step`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        val sibling = window().slots[ids[1]]!!.session!!

        // The same fix travels from step 1 into step 2, writing step 2's expectation in the draft directly.
        viewModel.applySameFixEverywhere(
            "sc-1",
            listOf(
                ScenarioReconcile.StepFixes(
                    ids[1],
                    "Step 3",
                    listOf(ScenarioReconcile.SiblingFix(0, 151, Matcher.Regex(".*"), "travelled")),
                ),
            ),
        )
        assertEquals(Matcher.Regex(".*"), matcherOf(ids[1], 0), "it landed in the draft")

        // Now the author edits a DIFFERENT row of that same step — the moment a stale session writes its whole
        // expectation back and takes the travelled repair with it.
        sibling.apply(EditOp.setMatcher(1, 251, Matcher.Exact("MINE")))

        assertEquals(
            Matcher.Exact("MINE"),
            matcherOf(ids[1], 1),
            "the author's own edit stands",
        )
        assertEquals(
            Matcher.Regex(".*"),
            matcherOf(ids[1], 0),
            "and the travelled repair is STILL THERE — a stale session would have reverted it, silently",
        )
    }

    /** Adopt is not rebase: the sibling's own unsaved edits are still unsaved, and still counted. */
    @Test
    fun `a travelled repair does not flatten the staging the sibling step already had`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        val sibling = window().slots[ids[1]]!!.session!!
        sibling.apply(EditOp.setMatcher(0, 151, Matcher.Exact("MINE")))
        assertEquals(1, sibling.staged)

        viewModel.applySameFixEverywhere(
            "sc-1",
            listOf(
                ScenarioReconcile.StepFixes(
                    ids[1],
                    "Step 3",
                    listOf(ScenarioReconcile.SiblingFix(0, 151, Matcher.Regex(".*"), "travelled")),
                ),
            ),
        )

        assertEquals(
            2,
            sibling.staged,
            "the author's edit is still staged and still unsaved — rebase would have reported zero",
        )
    }

    private fun matcherOf(stepId: String, index: Int = 0): Matcher? =
        (
            viewModel
                .scenarioDraft("sc-1")!!
                .draft.steps
                .firstOrNull { it.stepId == stepId } as? ScenarioStep.Expect
        )?.expectation
            ?.fields
            ?.getOrNull(index)
            ?.matcher

    // ------------------------------------------------------------------ the armed slot follows its step

    /**
     * The author arms Step 2's slot, wanders to Step 3 looking for something, then clicks the grid row. It
     * binds Step 2 — and the window goes back there, because a reference bound somewhere the author cannot
     * see is the silence ground rule 6 forbids.
     */
    @Test
    fun `a grid click binds the step that was armed, and returns the window to it`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))

        viewModel.armReferenceSlot(DiffStepRef("sc-1", ids[1]))
        viewModel.showStepInDiffWindow(windowId(), ids[0])
        assertEquals(ids[0], window().stepId, "the author has wandered off")

        viewModel.selectMessageFromGrid(message(wire("151=PICKED")))

        assertEquals(ids[1], window().stepId, "back to the step that was waiting")
        assertEquals(
            ReferenceMessage.Provenance.PICKED,
            window()
                .slots[ids[1]]!!
                .session!!
                .reference.provenance,
            "the click bound THAT step, not the one that happened to be on screen",
        )
        assertEquals(
            ReferenceMessage.Provenance.THIS_RUN,
            window()
                .slots[ids[0]]!!
                .session!!
                .reference.provenance,
            "and left the step the author had wandered to alone",
        )
        assertNull(viewModel.armedReferenceSlot.value, "one click means one thing")
    }

    // ------------------------------------------------------------------ the pass has an ending

    /** A green re-run ends the pass in the window that carried it, naming what it took. */
    @Test
    fun `a green re-run ends the pass with a completion state instead of silence`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.noteScenarioRun(onDisk)
        val epoch = window().focusEpoch

        viewModel.continueReconcilePass(
            ScenarioResult(onDisk.name, passed = true, steps = emptyList()),
            staged =
                listOf(
                    com.knapsack.fixtool.ui
                        .RepairedStep(ids[0], "Step 2", listOf("loosen 150")),
                ),
        )

        val completion = window().completion
        assertNotNull(completion, "the pass has an ending now")
        assertEquals(listOf("loosen 150"), completion.repaired.single().edits)
        assertEquals(epoch + 1, window().focusEpoch, "the run may have taken a while — this is news")
    }

    /** Nothing open, nothing to announce: a run from the rail still pops no windows at anyone. */
    @Test
    fun `a green run with no window open opens nothing`() {
        val onDisk = saved()
        viewModel.noteScenarioRun(onDisk)

        viewModel.continueReconcilePass(ScenarioResult(onDisk.name, passed = true, steps = emptyList()))

        assertTrue(viewModel.openDiffWindows.value.isEmpty())
    }

    /**
     * A pass is several Save & re-runs long, and each one only sees what it staged. The completion state must
     * describe the pass, not whichever iteration happened to be last.
     */
    @Test
    fun `what the pass repaired accumulates across iterations, and a new failure ends only the announcement`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))

        // Iteration one repaired step 2, and the re-run failed at step 3.
        runFailingAt(onDisk, 1)
        viewModel.continueReconcilePass(
            viewModel.scenarioResult.value!!,
            staged =
                listOf(
                    com.knapsack.fixtool.ui
                        .RepairedStep(ids[0], "Step 2", listOf("loosen 150")),
                ),
        )
        assertNull(window().completion, "still work to do")
        assertEquals(1, window().repairs.size)

        // Iteration two repaired step 3, and the re-run was green.
        viewModel.continueReconcilePass(
            ScenarioResult(onDisk.name, passed = true, steps = emptyList()),
            staged =
                listOf(
                    com.knapsack.fixtool.ui
                        .RepairedStep(ids[1], "Step 3", listOf("loosen 151")),
                ),
        )

        val completion = window().completion
        assertNotNull(completion)
        assertEquals(
            listOf("Step 2", "Step 3"),
            completion.repaired.map { it.label },
            "the whole pass, not just its last stop",
        )

        // And a later failure takes the announcement down without forgetting the pass.
        viewModel.openDiffWindow(onDisk, ids[2], thisRunWire = wire("152=X"))
        assertNull(window().completion, "there is work again")
        assertEquals(2, window().repairs.size, "but the pass is the same pass")
    }

    // ------------------------------------------------------------------ a deleted step takes its slot

    /**
     * **A slot outliving its step is not just waste.** Its session's `onChange` goes on writing an expectation
     * into a draft that has no such step, and if the deleted one is the step on screen the window is left on a
     * dead end with nothing to show.
     */
    @Test
    fun `deleting a step drops its slot and moves the view to one that still exists`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        assertEquals(ids[1], window().stepId)

        viewModel.updateScenarioDraft("sc-1") { w ->
            w.copy(draft = w.draft.copy(steps = w.draft.steps.filterNot { it.stepId == ids[1] }))
        }

        assertEquals(setOf(ids[0]), window().slots.keys, "the deleted step's slot went with it")
        assertEquals(ids[0], window().stepId, "and the view is on a step that still exists")
    }

    /** Delete every Expect and the window has no subject left — it closes rather than showing a dead end. */
    @Test
    fun `deleting every reconcilable step closes the window`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))

        viewModel.updateScenarioDraft("sc-1") { w ->
            w.copy(draft = w.draft.copy(steps = w.draft.steps.filter { it !is ScenarioStep.Expect }))
        }

        assertTrue(viewModel.openDiffWindows.value.isEmpty(), "nothing left to reconcile")
    }

    /** Ordinary editing must not walk the slots — only a change of step COUNT can orphan one. */
    @Test
    fun `editing a step without changing the step count leaves the slots alone`() {
        val onDisk = saved()
        val ids = stepIds(onDisk)
        viewModel.openDiffWindow(onDisk, ids[0], thisRunWire = wire("150=X"))
        viewModel.openDiffWindow(onDisk, ids[1], thisRunWire = wire("151=X"))
        val session = window().slots[ids[0]]!!.session!!

        viewModel.updateScenarioDraft("sc-1") { w -> w.copy(draft = w.draft.copy(name = "renamed")) }

        assertEquals(2, window().slots.size)
        assertSame(session, window().slots[ids[0]]!!.session, "and the sessions are the same objects")
    }
}
