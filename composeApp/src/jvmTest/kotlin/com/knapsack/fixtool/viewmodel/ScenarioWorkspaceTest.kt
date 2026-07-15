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
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.DiffWindowState
import com.knapsack.fixtool.ui.ScenarioDoc
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **One unsaved draft per scenario, however many documents are looking at it.**
 *
 * The draft used to belong to the editor document. It cannot: the reconcile diff is a document too, and it
 * edits an expectation *of the same scenario*. Two drafts of one scenario and they diverge — save from the
 * diff and the editor's next Save writes the old expectation straight back over the repair. That is the
 * two-editing-surfaces defect from the assertion model, re-created between two tabs, and the workspace is
 * what makes it unrepresentable.
 */
class ScenarioWorkspaceTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-workspace", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val scenario =
        Scenario(
            id = "sc-1",
            name = "rfq flow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation = Expectation(fields = listOf(FieldExpectation(150, Matcher.Exact("F"))), messageType = "8"),
                    ),
                ),
        )

    private fun saved(): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario))
        return viewModel.scenarioService.load(scenario.id)!!
    }

    @Test
    fun `a scenario opens clean, and an edit dirties the scenario rather than the tab`() {
        viewModel.openScenarioEditor(saved())
        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty, "opened, untouched, and therefore clean")

        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed")) }

        assertTrue(viewModel.scenarioDraft("sc-1")!!.dirty)
        assertEquals("renamed", viewModel.scenarioDraft("sc-1")!!.draft.name)
    }

    /**
     * A second door onto a scenario that is already open must not re-seed it. The copy that arrives at that
     * door came off *disk*; the one in the workspace may be carrying an hour of unsaved repairs.
     */
    @Test
    fun `a second door onto an open scenario does not re-seed its draft`() {
        val onDisk = saved()
        viewModel.openScenarioEditor(onDisk)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed, unsaved")) }

        viewModel.openScenarioEditor(onDisk, focusStep = 1)

        assertEquals("renamed, unsaved", viewModel.scenarioDraft("sc-1")!!.draft.name)
        assertTrue(viewModel.scenarioDraft("sc-1")!!.dirty)
    }

    /**
     * A draft with nothing looking at it is unreachable and unsaveable. Leaving it behind would mean the next
     * time the author opened the scenario they would silently be handed edits they had already walked away
     * from — and would have no way of knowing they were not what is on disk.
     */
    @Test
    fun `closing the last document of a scenario drops its draft`() {
        val onDisk = saved()
        viewModel.openScenarioEditor(onDisk)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "abandoned")) }

        viewModel.closeDocument(ScenarioDoc.editorId("sc-1"))
        assertNull(viewModel.scenarioDraft("sc-1"), "no document, no draft")

        viewModel.openScenarioEditor(onDisk)
        assertEquals("rfq flow", viewModel.scenarioDraft("sc-1")!!.draft.name, "re-opened from disk, not from the ghost")
        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty)
    }

    /** Save writes the workspace's draft and re-seeds it from what actually reached the disk. */
    @Test
    fun `save writes the draft and leaves the scenario clean`() {
        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed")) }

        assertTrue(viewModel.saveScenarioDocument(viewModel.scenarioDraft("sc-1")!!.draft))

        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty, "saved, so there is nothing left to lose")
        assertEquals("renamed", viewModel.scenarioService.load("sc-1")!!.name)
        assertTrue(viewModel.openDocuments.value.isNotEmpty(), "and the tab stays open on it")
    }

    /** A view of a file that is gone is a trap: deleting the scenario closes everything looking at it. */
    @Test
    fun `deleting a scenario closes its documents and drops its draft`() {
        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "doomed")) }

        viewModel.deleteScenario("sc-1")

        assertTrue(viewModel.openDocuments.value.isEmpty())
        assertNull(viewModel.scenarioDraft("sc-1"))
        assertNull(viewModel.activeDocumentId.value)
    }

    /**
     * **A run lands, and every open diff re-binds to the message it just produced.**
     *
     * Without this, the daily loop ends in a lie. The author repairs the step, hits Save & re-run, the rail goes
     * green — and the diff they are looking at is still bound to the **old** run's failing bytes: still red,
     * still offering to fix what is already fixed. The one surface that is supposed to prove the fix would be
     * the one contradicting it.
     */
    @Test
    fun `a new run re-binds the open diff to what it actually produced`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        val failing = message(wire("150=0"))
        viewModel.openScenarioEditor(onDisk)
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(failing to StepResult(1, "expect", "steps", false, stepId = stepId)))
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = failing.wireRaw)

        val window = viewModel.diffWindow(DiffWindowState.diffWindowId("sc-1", stepId))!!
        assertEquals(failing.wireRaw, window.thisRunWire)
        val session = window.session!!
        assertEquals(
            "0",
            session.model.lines
                .single { it.row.tag == 150 }
                .right
                ?.value,
            "bound to the failure",
        )

        // The next run: same step, a different reply — the one the repair was meant to produce.
        val passing = message(wire("150=F"))
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(passing to StepResult(1, "expect", "steps", true, stepId = stepId)))
        viewModel.publishScenarioResult(ScenarioResult(onDisk.name, passed = true, steps = emptyList()))

        val rebound = viewModel.openDiffWindows.value.single()
        assertSame(session, rebound.session, "the same session — the undo stack is not a casualty of a re-run")
        assertEquals(passing.wireRaw, rebound.thisRunWire, "and it is looking at the new run's bytes")
        assertEquals(
            "F",
            session.model.lines
                .single { it.row.tag == 150 }
                .right
                ?.value,
            "the diff shows what the venue sent THIS time, not what it sent last time",
        )
        assertEquals(0, session.model.verdict.attention, "so the step it just fixed reads as fixed")
    }

    /**
     * **…and it re-binds the slots it owns, which are not all of them.**
     *
     * The other half of the rule above, and it is the half that was missing. An author who binds a message
     * *by hand* — a row picked out of a grid, a reply pasted from UAT — has said what they want on the right,
     * and it is usually the whole reason the diff is open. Re-binding it because a run happened takes the thing
     * they were comparing against away **at the moment they were using it**, and says nothing.
     *
     * The run's bytes are still held on the window, so the swap menu can offer them. They are simply not
     * forced into a slot the author has already answered.
     */
    @Test
    fun `a run does not take away the reference the author bound by hand`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        val failing = message(wire("150=0"))
        viewModel.openScenarioEditor(onDisk)
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(failing to StepResult(1, "expect", "steps", false, stepId = stepId)))
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = failing.wireRaw)

        val window = viewModel.diffWindow(DiffWindowState.diffWindowId("sc-1", stepId))!!
        val session = window.session!!
        viewModel.bindPickedReference(window, wire("150=X"), null)
        assertEquals(ReferenceMessage.Provenance.PICKED, session.reference.provenance, "the author has answered the question")

        // A run lands — from Save & re-run, or from the rail, or from an agent over the control surface.
        val next = message(wire("150=F"))
        viewModel.noteScenarioRun(onDisk)
        viewModel.setAssertionResults(mapOf(next to StepResult(1, "expect", "steps", true, stepId = stepId)))
        viewModel.publishScenarioResult(ScenarioResult(onDisk.name, passed = true, steps = emptyList()))

        assertEquals(
            ReferenceMessage.Provenance.PICKED,
            session.reference.provenance,
            "the message the author chose is still the message on the right",
        )
        assertEquals(
            "X",
            session.model.lines
                .single { it.row.tag == 150 }
                .right
                ?.value,
            "and it is still THEIR bytes being diffed, not the run's",
        )
        val after = viewModel.openDiffWindows.value.single()
        assertEquals(next.wireRaw, after.thisRunWire, "the run's bytes are held — the menu offers them, the run does not impose them")
    }

    // ---- Phase 6: the diff is a window now, and these are its lifecycle rules (F4, F6) ------------------

    /**
     * **Re-opening the same subject focuses the existing window; it does not mint a second.** The window is
     * keyed on `(scenarioId, stepId)`, and a second failure of the same step raises the window that is already
     * there (the epoch bump toFronts it) rather than opening another over the same undo stack.
     */
    @Test
    fun `re-opening a step focuses its window, and does not open a second`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        viewModel.openScenarioEditor(onDisk)
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = message(wire("150=0")).wireRaw)
        val epochBefore = viewModel.openDiffWindows.value.single().focusEpoch

        viewModel.openDiffWindow(onDisk, stepId, focusTag = 150)

        assertEquals(1, viewModel.openDiffWindows.value.size, "one window per subject — never a duplicate")
        val window = viewModel.openDiffWindows.value.single()
        assertEquals(epochBefore + 1, window.focusEpoch, "the epoch bumps, which is what raises the window (F6)")
        assertEquals(150, window.focusTag, "and it re-aims at the row the deep-link named")
    }

    /**
     * **Closing the last editor tab does not drop the draft while a diff window is still open (F4).** The
     * window is a view of the scenario that `_openDocuments` cannot see; counting only documents would drop the
     * draft out from under a live window, and its next edit would write into a workspace that no longer exists.
     */
    @Test
    fun `closing the editor tab keeps the draft while the diff window still views it`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        viewModel.openScenarioEditor(onDisk)
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = message(wire("150=0")).wireRaw)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "unsaved")) }

        // Through requestClose, so it exercises the "is this the last VIEW" test: the diff window is another
        // view, so closing the editor tab discards nothing and must NOT stop to ask (F4).
        viewModel.requestCloseDocument(ScenarioDoc.editorId("sc-1"))
        assertNull(viewModel.confirmingCloseId.value, "the diff window still views it: closing the tab discards nothing")

        assertNotNull(viewModel.scenarioDraft("sc-1"), "the diff window is still a view: the draft survives")
        assertTrue(viewModel.scenarioDraft("sc-1")!!.dirty, "and it is still the unsaved one")

        // Now close the window too — the last view of a dirty scenario, so it confirms rather than dropping.
        viewModel.requestCloseDiffWindow(DiffWindowState.diffWindowId("sc-1", stepId))
        assertEquals(DiffWindowState.diffWindowId("sc-1", stepId), viewModel.confirmingCloseId.value, "the last view asks first")
        assertNotNull(viewModel.scenarioDraft("sc-1"), "and until it is answered, nothing is dropped")
    }

    /** Closing a diff window while the editor tab is still open discards nothing, and must not stop to ask (F4). */
    @Test
    fun `closing a diff window with the editor tab open does not confirm`() {
        val onDisk = saved()
        val stepId = onDisk.withIds().steps[1].stepId
        viewModel.openScenarioEditor(onDisk)
        viewModel.openDiffWindow(onDisk, stepId, thisRunWire = message(wire("150=0")).wireRaw)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "unsaved")) }

        viewModel.requestCloseDiffWindow(DiffWindowState.diffWindowId("sc-1", stepId))

        assertNull(viewModel.confirmingCloseId.value, "the editor tab still views the draft: closing the window discards nothing")
        assertTrue(viewModel.openDiffWindows.value.isEmpty(), "so it just closes")
        assertNotNull(viewModel.scenarioDraft("sc-1"), "and the draft stays, held by the editor tab")
    }

    /** The venue's bytes, SOH-delimited — never the `|` display string, which is not what the engine reads. */
    private fun wire(execType: String): String =
        listOf("8=FIX.4.4", "35=8", "11=X", execType, "10=000")
            .joinToString("\u0001", postfix = "\u0001")

    private fun message(raw: String): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 14, 9, 35, 44),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = raw.replace('\u0001', '|'),
            quickfixMessage = Message(),
            wireRaw = raw,
        )

    /** A clean tab closes without a word; a dirty one stops to ask, because closing it would discard the draft. */
    @Test
    fun `the close prompt is about the draft, not about the tab`() {
        viewModel.openScenarioEditor(saved())
        val id = ScenarioDoc.editorId("sc-1")

        viewModel.requestCloseDocument(id)
        assertTrue(viewModel.openDocuments.value.isEmpty(), "clean: it just closes")

        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "unsaved")) }
        viewModel.requestCloseDocument(id)

        assertEquals(id, viewModel.confirmingCloseId.value, "dirty, and the last one looking at it: it asks")
        assertTrue(viewModel.openDocuments.value.isNotEmpty(), "and it is still open until the author answers")
    }
}
