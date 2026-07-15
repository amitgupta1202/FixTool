package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.ui.ScenarioDoc
import com.knapsack.fixtool.ui.diff.SeedFrom
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
import kotlin.test.assertTrue

/**
 * **The plain diff viewer, at the ViewModel — the click-only doors the control surface cannot drive.**
 *
 * Opening a viewer, refusing a message with no wire bytes, focusing rather than duplicating a pair, and the
 * one-way door (Seed → a scenario-less editor → *"add to scenario"* files it) are all reachable only by a
 * click; this pins the logic behind those clicks (Phase 7, G4/G6/G7).
 */
class DiffViewerViewModelTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-viewer", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val soh = "\u0001"

    private fun wire(vararg fields: String) = fields.joinToString(soh, postfix = soh)

    private fun msg(raw: String?, at: Int = 44) =
        FixMessage(
            timestamp = LocalDateTime.of(2026, 7, 14, 9, 35, at),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = (raw ?: "").replace(soh[0], '|'),
            quickfixMessage = Message(),
            wireRaw = raw,
        )

    private val a = msg(wire("35=8", "31=1.0851", "58=filled|in full"), at = 44)
    private val b = msg(wire("35=8", "31=1.0849"), at = 46)

    @Test
    fun `diff selected opens a viewer on two messages`() {
        assertTrue(viewModel.openDiffSelected(a, b))
        val viewers = viewModel.openDiffViewers.value
        assertEquals(1, viewers.size)
        val session = viewers.single().session
        assertNotNull(session)
        // The diff is real: 31 changed value, and it is not hidden.
        assertTrue(session.model.lines.any { it.row.tag == 31 }, "the two messages differ on 31")
    }

    @Test
    fun `a message with no wire bytes is refused, not diffed`() {
        val noWire = msg(null)
        assertFalse(viewModel.openDiffSelected(a, noWire), "a message with no wireRaw cannot be a side")
        assertTrue(viewModel.openDiffViewers.value.isEmpty(), "nothing opens when a side is refused")
        assertTrue(viewModel.notifications.isNotEmpty(), "the refusal is said, not silent")
    }

    @Test
    fun `the same pair focuses the existing viewer, it does not duplicate`() {
        viewModel.openDiffSelected(a, b)
        val epoch0 = viewModel.openDiffViewers.value.single().focusEpoch
        viewModel.openDiffSelected(a, b)
        assertEquals(1, viewModel.openDiffViewers.value.size, "the same pair must not open a second window")
        assertTrue(viewModel.openDiffViewers.value.single().focusEpoch > epoch0, "re-opening raises the window")
    }

    @Test
    fun `swap sides and mode mutate the session`() {
        viewModel.openDiffSelected(a, b)
        val id = viewModel.openDiffViewers.value.single().id
        val session = viewModel.diffViewer(id)!!.session!!
        val leftBefore = session.left.wire
        viewModel.swapDiffViewerSides(id)
        assertEquals(session.right.wire, leftBefore, "swap put the old left on the right")
        val modeBefore = session.mode
        viewModel.selectDiffViewerMode(id, if (modeBefore.name == "OPEN") com.knapsack.fixtool.model.scenario.MatchMode.STRICT else com.knapsack.fixtool.model.scenario.MatchMode.OPEN)
        assertTrue(session.mode != modeBefore, "the mode changed")
    }

    @Test
    fun `seed floats a scenario-less editor, and add-to-scenario files it and hands off`() {
        viewModel.openDiffSelected(a, b)
        val id = viewModel.openDiffViewers.value.single().id

        viewModel.seedFromViewer(id, SeedFrom.A)
        val editing = viewModel.diffViewer(id)!!.editing
        assertNotNull(editing, "Seed floats a scenario-less editor")
        assertTrue(editing.draft.fields.isNotEmpty(), "the seeded expectation has rows (seeded from A)")

        val scenario = viewModel.newScenarioForSeed()
        viewModel.addSeededToScenario(id, scenario)

        // The step is filed into the workspace (unsaved), with a real stepId, and the viewer is gone.
        val draft = viewModel.scenarioDraft(scenario.id)?.draft
        assertNotNull(draft, "the scenario now has a workspace draft")
        val expect = draft.steps.filterIsInstance<ScenarioStep.Expect>().single()
        assertTrue(expect.stepId.isNotBlank(), "withIds gave the filed step an id")
        assertNull(viewModel.diffViewer(id), "the viewer window handed off and closed")
        assertTrue(
            viewModel.openDocuments.value.any { it is ScenarioDoc.Editor && it.scenarioId == scenario.id },
            "the editor tab opened on the new scenario",
        )
    }
}
