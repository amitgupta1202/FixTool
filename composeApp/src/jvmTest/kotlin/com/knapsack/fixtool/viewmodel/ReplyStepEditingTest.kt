package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.EditorTarget
import com.knapsack.fixtool.service.AcceptorPresets
import com.knapsack.fixtool.ui.FixField
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A reply step, edited in the message editor.**
 *
 * A step is a raw FIX string, and the editor already round-trips exactly that — its preview pane is a
 * live two-way binding between the field grid and a raw message. So the thing under test is not the
 * grid, which works; it is the borrowing: what happens to the message someone was composing, whether
 * what comes back is the same template, and what is refused rather than written.
 */
class ReplyStepEditingTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    private val ack = "35=8|37=\${req.uuid}|17=\${uuid}|150=0|39=0|11=\${req.11}|60=\${now}"
    private val fill = "35=8|150=F|39=2|14=\${req.38}|151=0"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-reply-step", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        testDir.deleteRecursively()
    }

    /** A half-written message in the editor, as someone would leave it to go and look at a rule. */
    private fun composeAMessage() {
        viewModel.clearEditorFields()
        viewModel.updateEditorField(0, FixField("35", "D"))
        viewModel.addEditorField()
        viewModel.updateEditorField(1, FixField("11", "HALF-WRITTEN"))
    }

    private fun fieldsAsRaw() =
        viewModel.editorFields.filterNot { it.tag.isBlank() && it.value.isBlank() }.joinToString("|") {
            "${it.tag}=${it.value}"
        }

    // ------------------------------------------------------------------ borrowing the editor

    @Test
    fun `opening a step loads it, and cancelling gives the message back untouched`() {
        composeAMessage()

        viewModel.openReplyStep("profile-1", ruleIndex = 0, stepIndex = 1, template = ack)

        assertEquals(ack, fieldsAsRaw(), "the step should be in the grid, field by field")
        assertEquals(
            EditorTarget.ReplyStep("profile-1", 0, 1, ack),
            viewModel.editorTarget,
            "the editor has to know what it is editing, or Apply has nowhere to write",
        )

        viewModel.cancelReplyStep()

        assertEquals(
            "35=D|11=HALF-WRITTEN",
            fieldsAsRaw(),
            "the message being composed is not the price of looking at a rule",
        )
        assertEquals(EditorTarget.Wire, viewModel.editorTarget)
    }

    /**
     * Opening a second step while one is open must not stash the *first step* over the message. The
     * failure is quiet and total: cancel would then restore step one's fields as though they were
     * what the author had been writing, and the message would be gone with nothing to say so.
     */
    @Test
    fun `a second step does not stash the first one over the message`() {
        composeAMessage()

        viewModel.openReplyStep("profile-1", 0, 0, ack)
        viewModel.openReplyStep("profile-1", 0, 1, fill)
        assertEquals(fill, fieldsAsRaw(), "the editor should be showing the step just opened")

        viewModel.cancelReplyStep()

        assertEquals("35=D|11=HALF-WRITTEN", fieldsAsRaw(), "cancel returns to the message, not to the first step")
    }

    @Test
    fun `applying returns the step and restores the message`() {
        composeAMessage()
        viewModel.openReplyStep("profile-9", ruleIndex = 2, stepIndex = 0, template = ack)
        viewModel.updateEditorField(4, FixField("39", "8"))

        val applied = viewModel.applyReplyStep()

        assertTrue(applied != null)
        assertEquals("profile-9", applied.profileId)
        assertEquals(2, applied.ruleIndex)
        assertEquals(0, applied.stepIndex)
        assertEquals(ack, applied.snapshot, "the address is only good while what was there is still there")
        assertEquals(ack.replace("39=0", "39=8"), applied.template, "the edit, and only the edit")
        assertEquals(applied, viewModel.pendingReplyStepApply, "the panel that owns the rules has to be able to read it")

        assertEquals("35=D|11=HALF-WRITTEN", fieldsAsRaw(), "and the message comes back")
        assertEquals(EditorTarget.Wire, viewModel.editorTarget)

        viewModel.consumeReplyStepApply()
        assertNull(viewModel.pendingReplyStepApply, "an applied step must not be applied twice")
    }

    // ------------------------------------------------------------------ the round trip

    /**
     * The property that makes the grid safe to open a shipped rule in: what comes out is what went in.
     * Asserted over every preset because those are the templates users will open first, and a round
     * trip that quietly reorders or drops a field would rewrite a working venue on a glance.
     */
    @Test
    fun `every preset step survives the round trip unchanged`() {
        AcceptorPresets.all.forEach { preset ->
            preset.rules.forEachIndexed { ruleIndex, rule ->
                rule.sequence().forEachIndexed { stepIndex, step ->
                    viewModel.openReplyStep(preset.id, ruleIndex, stepIndex, step.template)
                    val applied = viewModel.applyReplyStep()
                    assertEquals(
                        step.template,
                        applied?.template,
                        "${preset.id} rule ${ruleIndex + 1} step ${stepIndex + 1} did not come back as it went in",
                    )
                    viewModel.consumeReplyStepApply()
                }
            }
        }
    }

    // ------------------------------------------------------------------ what Apply refuses

    @Test
    fun `a tag with no value is refused by tag, and nothing is applied`() {
        viewModel.openReplyStep("profile-1", 0, 0, fill)
        viewModel.updateEditorField(3, FixField("14", ""))

        assertNull(viewModel.applyReplyStep(), "a tag with no value is a malformed message, not an edit")
        assertNull(viewModel.pendingReplyStepApply)
        assertTrue(
            viewModel.editorValidationErrors.any { it.contains("tag 14") && it.contains("14=") },
            "the refusal has to name the tag and what it would have sent; got ${viewModel.editorValidationErrors}",
        )
        assertTrue(viewModel.editorTarget is EditorTarget.ReplyStep, "a refused apply leaves the author where they were")
    }

    @Test
    fun `a value carrying the field separator is refused, since it would come back as two fields`() {
        viewModel.openReplyStep("profile-1", 0, 0, fill)
        viewModel.updateEditorField(1, FixField("58", "rejected|see desk"))

        assertNull(viewModel.applyReplyStep())
        assertTrue(
            viewModel.editorValidationErrors.any { it.contains("tag 58") && it.contains("separator") },
            "got ${viewModel.editorValidationErrors}",
        )
    }

    @Test
    fun `a step emptied of every field is refused rather than saved as a rule that sends nothing`() {
        viewModel.openReplyStep("profile-1", 0, 0, "35=8|39=0")
        viewModel.updateEditorField(0, FixField("", ""))
        viewModel.updateEditorField(1, FixField("", ""))

        assertNull(viewModel.applyReplyStep())
        assertTrue(viewModel.editorValidationErrors.any { it.contains("no fields") })
    }

    @Test
    fun `applying without a step open does nothing at all`() {
        composeAMessage()

        assertNull(viewModel.applyReplyStep(), "the editor is holding a message; there is nothing to apply it to")
        assertEquals("35=D|11=HALF-WRITTEN", fieldsAsRaw())
    }
}
