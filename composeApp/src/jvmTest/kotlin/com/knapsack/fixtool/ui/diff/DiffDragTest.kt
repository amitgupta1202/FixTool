package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import com.knapsack.fixtool.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The drag, driven by an actual mouse.**
 *
 * The control surface can run a scenario and toggle a pane, but it cannot click — and a drag is the purest
 * case of a thing that exists only under a pointer. So this is where the two scenarios the mockup draws are
 * proven: the hand-authored row that is in the wrong place and is fixed by dragging it, and the one drop the
 * tool refuses, with the engine's own sentence at the cursor.
 *
 * The harness feeds `onChange` back, as `ScenarioEditor` does — a harness that merely records it let a
 * completely dead staging mechanism survive seven passing tests once already.
 */
@OptIn(ExperimentalTestApi::class)
class DiffDragTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val outDir = File("build/scenario-screenshots").absoluteFile
    private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

    /** The session under test, so an assertion can ask the draft what it holds rather than read it off a pixel. */
    private lateinit var session: ReconcileSession

    // ----- the fixtures -----------------------------------------------------------------------------------

    /**
     * A hand-authored OPEN step whose rows are not in the venue's order — the false red the model doc predicts
     * ("*the venue sends 37 before 11 and the author lists 11 before 37*"), and the one a drag exists to fix.
     */
    private val handAuthored =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(37, Matcher.Presence),
            ),
            messageType = "8",
            mode = MatchMode.OPEN,
        )
    private val venueOrder = wireView(35 to "8", 37 to "OID-4417", 11 to "ORD-1")

    /** Two parties. Dragging the second `452` across the first is the one move the tool must never make. */
    private val twoParties =
        Expectation(
            listOf(
                FieldExpectation(453, Matcher.Exact("2")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )
    private val partiesAsSent =
        wireView(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4")

    private fun ComposeContentTestRule.surface(initial: Expectation, message: MessageView) {
        setContent {
            var expectation by remember { mutableStateOf(initial) }
            val live =
                remember(Unit) {
                    ReconcileSession(
                        original = expectation,
                        initialReference =
                            ReferenceMessage.live(message, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
                        dictionary = dictionary,
                        onChange = { expectation = it }, // fed back, the way the app feeds it
                    ).also { session = it }
                }
            Box(modifier = Modifier.size(1180.dp, 560.dp).background(AppTheme.Colors.background)) {
                DiffSurface(live, crumb = "rfq flow v2 › Step 2 · Expect ExecutionReport (8)")
            }
        }
    }

    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[DiffDragTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /** Pick a row up and hold it over [by] pixels away. The slop has to be crossed before a drag is a drag. */
    private fun grab(handle: String, by: Float) {
        composeTestRule.onNodeWithTag(handle).performMouseInput {
            moveTo(center)
            press()
        }
        composeTestRule.onNodeWithTag(handle).performMouseInput { moveBy(Offset(0f, if (by < 0) -12f else 12f)) }
        composeTestRule.onNodeWithTag(handle).performMouseInput { moveBy(Offset(0f, by)) }
        composeTestRule.waitForIdle()
    }

    private fun release(handle: String) {
        composeTestRule.onNodeWithTag(handle).performMouseInput { release() }
        composeTestRule.waitForIdle()
    }

    // ----- the drag that fixes the step -------------------------------------------------------------------

    /**
     * **The out-of-order row, fixed by dragging it** — and the tooltip answers the only question that matters
     * before the mouse is released.
     */
    @Test
    fun `dragging a row into the venue's order fixes the step, and the tooltip said it would`() {
        composeTestRule.surface(handAuthored, venueOrder)

        assertTrue(session.model.verdict.needsAttention, "the rows are out of the venue's order: this step fails")
        snapshot("diff_drag_1_before.png")

        // Tag 37 sits below tag 11 and belongs above it. Pick it up and carry it over the row above.
        grab("row-handle-2", by = -40f)

        composeTestRule.onNodeWithTag("drag-tip-ok").assertExists()
        snapshot("diff_drag_2_would_pass.png")

        release("row-handle-2")

        assertEquals(
            listOf(35, 37, 11),
            session.draft.fields.map { it.tag },
            "the row landed where the venue puts it",
        )
        assertEquals(0, session.model.verdict.attention, "and the step now passes — which is what the tooltip promised")
        assertEquals(1, session.staged, "one drop is one edit, however many pixels it took")
        assertNull(session.refusal)
        snapshot("diff_drag_3_after.png")

        // And it is an edit like any other: ⌘Z walks it back, byte for byte.
        composeTestRule.onNodeWithTag("diff-surface").performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        assertEquals(handAuthored, session.draft, "undo restores the expectation exactly, move included")
    }

    // ----- the drag the tool refuses ----------------------------------------------------------------------

    /**
     * **The one refused drag, and the reason at the cursor.**
     *
     * The second `452` cannot cross the first. Both rows would go on reading `452 exact …` while quietly
     * swapping which party they check — the assert-the-wrong-field failure the whole sequence model exists to
     * make impossible, walked back in through the editor. The drop is refused, the draft is untouched, and the
     * author is told exactly what would have broken.
     */
    @Test
    fun `a row of a repeated tag cannot cross its sibling, and the refusal says why at the cursor`() {
        composeTestRule.surface(twoParties, partiesAsSent)
        val before = session.draft

        // FIRMB's role, dragged up over FIRMA's party. Every pixel of it is legal to *hold*; none of it is
        // legal to drop.
        grab("row-handle-6", by = -220f)

        val tip = composeTestRule.onNodeWithTag("drag-tip-refused")
        tip.assertExists()
        snapshot("diff_drag_4_refused.png")

        release("row-handle-6")

        assertEquals(before, session.draft, "a refused drop leaves the draft byte-identical")
        assertEquals(0, session.staged)
        val why = assertNotNull(session.refusal, "and it is never a silent nothing")
        assertTrue("swap which occurrence" in why, why)
        composeTestRule.onNodeWithTag("diff-refused-move").assertExists()
        snapshot("diff_drag_5_snapped_back.png")
    }

    /** The entry travels whole, or it does not travel. Dragging the band is the move a venue actually makes. */
    @Test
    fun `dragging an entry band moves the whole party`() {
        composeTestRule.surface(twoParties, partiesAsSent)

        grab("entry-handle-1", by = 150f) // FIRMA's band, down past FIRMB's
        release("entry-handle-1")

        assertEquals(
            listOf("FIRMB", "FIRMA"),
            session.draft.fields.filter { it.tag == 448 }.map { (it.matcher as Matcher.Exact).value },
        )
        assertEquals(
            listOf("4", "1"),
            session.draft.fields.filter { it.tag == 452 }.map { (it.matcher as Matcher.Exact).value },
            "FIRMA is still beside role 1 — the pair that jointly says so travelled together",
        )
        snapshot("diff_drag_6_entry_moved.png")
    }

    // ----- the keyboard, where a text field is listening --------------------------------------------------

    /**
     * **Typing `n` into a value types `n`.**
     *
     * The bare keys bubble (`onKeyEvent`), so a focused text field consumes its own characters and its own
     * arrows, and the surface only ever sees what nothing else wanted. Capture them instead — the natural way
     * to write a keyboard shortcut — and `n` navigates to the next chunk while the author is trying to type
     * the word "not".
     */
    @Test
    fun `a value field keeps its own keys, and the diff's shortcuts keep theirs`() {
        composeTestRule.surface(handAuthored, venueOrder)
        val field = composeTestRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("matcher-11-0")))

        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performKeyInput { pressKey(Key.N) }
        composeTestRule.waitForIdle()

        assertNull(session.selection, "`n` in a value field is a letter, not a navigation key")

        // ...and ⌘Z, which is modified, belongs to the document even while the field has focus — otherwise the
        // text field's own undo stack walks characters back while the footer's count sits still.
        field.performTextReplacement("ORD-2")
        composeTestRule.waitForIdle()
        assertEquals(1, session.staged)

        field.performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
        composeTestRule.waitForIdle()

        assertEquals(0, session.staged, "⌘Z walked the DIFF's stack — there is only one of them")
        assertEquals(handAuthored, session.draft)
    }

    /**
     * **"not sent" is a claim about the venue, and it must be true.**
     *
     * A row the reply carries *in another position* is `TagStatus.MOVED`, and the engine has always had the
     * words for it — *"present, but not in this position"*. The surface rendered every gap as **"not sent"**,
     * so a hand-authored step whose rows are out of wire order accused the venue of dropping a field it had
     * sent, sitting two lines away on the same screen. Found by looking at the picture; no assertion had
     * anything to say about it.
     */
    @Test
    fun `a field the venue sent in another position is never called 'not sent'`() {
        composeTestRule.surface(handAuthored, venueOrder)

        val orderId = session.model.lines.single { it.row.tag == 37 }
        assertTrue(orderId.rightIsGap, "it did not pair here — that is what being in the wrong place means")
        assertTrue("present, but not in this position" in orderId.row.reason, orderId.row.reason)

        composeTestRule.onNodeWithTag("present-elsewhere").assertExists()
        composeTestRule.onAllNodesWithTag("not-sent").assertCountEquals(0)
    }

    /** `n` steps to the next difference, and the cursor is visible where it lands. */
    @Test
    fun `n selects the next difference`() {
        composeTestRule.surface(handAuthored, venueOrder)
        composeTestRule.onNodeWithTag("diff-surface").performKeyInput { pressKey(Key.N) }
        composeTestRule.waitForIdle()

        assertNotNull(session.selection, "n landed somewhere")
        snapshot("diff_drag_7_chunk_nav.png")
    }
}
