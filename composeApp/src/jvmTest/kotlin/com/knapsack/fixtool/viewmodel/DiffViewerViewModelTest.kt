package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.compare.WirePaste
import com.knapsack.fixtool.ui.ScenarioDoc
import com.knapsack.fixtool.ui.diff.SeedFrom
import com.knapsack.fixtool.ui.diff.ViewerSlot
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
        testDir =
            File.createTempFile("fixtool-viewer", "").apply {
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

    private fun soleViewerId(): String {
        val viewer = viewModel.openDiffViewers.value.single()
        return viewer.id
    }

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
        val epoch0 =
            viewModel.openDiffViewers.value
                .single()
                .focusEpoch
        viewModel.openDiffSelected(a, b)
        assertEquals(1, viewModel.openDiffViewers.value.size, "the same pair must not open a second window")
        assertTrue(
            viewModel.openDiffViewers.value
                .single()
                .focusEpoch > epoch0,
            "re-opening raises the window",
        )
    }

    @Test
    fun `swap sides and mode mutate the session`() {
        viewModel.openDiffSelected(a, b)
        val id =
            viewModel.openDiffViewers.value
                .single()
                .id
        val session = viewModel.diffViewer(id)!!.session!!
        val leftBefore = session.left.wire
        viewModel.swapDiffViewerSides(id)
        assertEquals(session.right.wire, leftBefore, "swap put the old left on the right")
        val modeBefore = session.mode
        viewModel.selectDiffViewerMode(
            id,
            if (modeBefore.name ==
                "OPEN"
            ) {
                com.knapsack.fixtool.model.scenario.MatchMode.STRICT
            } else {
                com.knapsack.fixtool.model.scenario.MatchMode.OPEN
            },
        )
        assertTrue(session.mode != modeBefore, "the mode changed")
    }

    @Test
    fun `seed floats a scenario-less editor, and add-to-scenario files it and hands off`() {
        viewModel.openDiffSelected(a, b)
        val id =
            viewModel.openDiffViewers.value
                .single()
                .id

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

    // -------------------------------------------------- the two Phase-8 entry points (both empty-slots doors)

    @Test
    fun `diff messages opens an empty viewer with two unfilled slots`() {
        viewModel.openEmptyDiffViewer()
        val viewer = viewModel.openDiffViewers.value.single()
        assertNull(viewer.session, "an empty opener has no diff yet — it is a prompt, not a diff against nothing")
        assertNull(viewer.pendingLeft)
        assertNull(viewer.pendingRight)
    }

    @Test
    fun `two picks fill the empty opener's slots and promote it to a diff`() {
        viewModel.openEmptyDiffViewer()
        val id = soleViewerId()

        viewModel.armViewerSlot(id, ViewerSlot.LEFT)
        assertTrue(viewModel.bindArmedViewerSlot(a), "the armed left slot binds the clicked message")
        assertNull(viewModel.diffViewer(id)!!.session, "one side is not a diff yet")
        assertEquals(a.wireRaw, viewModel.diffViewer(id)!!.pendingLeft!!.wire)

        viewModel.armViewerSlot(id, ViewerSlot.RIGHT)
        assertTrue(viewModel.bindArmedViewerSlot(b))
        val session = viewModel.diffViewer(id)!!.session
        assertNotNull(session, "both sides bound → the diff opens")
        assertNull(viewModel.diffViewer(id)!!.pendingLeft, "pending is cleared once promoted")
        assertTrue(session.model.lines.any { it.row.tag == 31 }, "the promoted diff shows the two messages differ on 31")
    }

    @Test
    fun `diff against pre-fills A and arms B, and a grid pick completes it`() {
        assertTrue(viewModel.openDiffAgainst(a), "A has wire bytes, so the viewer opens")
        val viewer = viewModel.openDiffViewers.value.single()
        assertEquals(a.wireRaw, viewer.pendingLeft!!.wire, "the detail message is side A")
        assertNull(viewer.session, "B is not bound yet, so it is still an opener")
        assertEquals(ViewerSlot.RIGHT, viewModel.armedViewerSlot.value!!.slot, "B is armed for the next pick")
        assertEquals(viewer.id, viewModel.armedViewerSlot.value!!.viewerId)

        assertTrue(viewModel.bindArmedViewerSlot(b))
        assertNotNull(viewModel.diffViewer(viewer.id)!!.session, "picking B completes the pair")
        assertNull(viewModel.armedViewerSlot.value, "binding disarms")
    }

    @Test
    fun `diff against refuses a message with no wire bytes, and says so`() {
        assertFalse(viewModel.openDiffAgainst(msg(null)), "no wire bytes → no side")
        assertTrue(viewModel.openDiffViewers.value.isEmpty(), "nothing opens")
        assertTrue(viewModel.notifications.isNotEmpty(), "the refusal is said, not silent")
    }

    @Test
    fun `an armed viewer slot refuses a no-wire pick at the click, and stays armed`() {
        viewModel.openDiffAgainst(a)
        val armed = viewModel.armedViewerSlot.value
        assertFalse(viewModel.bindArmedViewerSlot(msg(null)), "a message with no wire bytes cannot fill the slot")
        assertTrue(viewModel.notifications.isNotEmpty(), "refused in words")
        assertEquals(armed, viewModel.armedViewerSlot.value, "the slot stays armed for another try")
    }

    @Test
    fun `arming a viewer slot and a reference slot are mutually exclusive — one click means one thing`() {
        viewModel.armReferenceSlot("window-1")
        assertEquals("window-1", viewModel.armedReferenceSlot.value)
        viewModel.openEmptyDiffViewer()
        val id = soleViewerId()
        viewModel.armViewerSlot(id, ViewerSlot.LEFT)
        assertNull(viewModel.armedReferenceSlot.value, "arming a viewer slot disarms the reconcile reference")

        viewModel.armReferenceSlot("window-1")
        assertNull(viewModel.armedViewerSlot.value, "arming the reference disarms the viewer slot")
    }

    @Test
    fun `pasted bytes fill a viewer slot, and two pastes promote to a diff`() {
        viewModel.openEmptyDiffViewer()
        val id = soleViewerId()
        viewModel.fillViewerSlotFromPaste(id, ViewerSlot.LEFT, WirePaste.read(a.wireRaw!!))
        assertNull(viewModel.diffViewer(id)!!.session, "one pasted side is not a diff yet")
        viewModel.fillViewerSlotFromPaste(id, ViewerSlot.RIGHT, WirePaste.read(b.wireRaw!!))
        assertNotNull(viewModel.diffViewer(id)!!.session, "two pasted sides → the diff opens")
    }

    @Test
    fun `closing a viewer clears an arm that targeted it`() {
        viewModel.openDiffAgainst(a)
        val id = soleViewerId()
        viewModel.closeDiffViewer(id)
        assertNull(viewModel.armedViewerSlot.value, "closing the viewer disarms its slot")
    }
}
