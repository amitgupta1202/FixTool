package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ExpectationEvaluator
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
import kotlin.test.assertTrue

/**
 * **The diff surface, driven the way the app drives it.**
 *
 * The harness matters more than any single assertion in here. `ReconcileView`'s tests once recorded `onChange`
 * into a local variable and stopped there — and a **completely dead staging mechanism survived seven passing
 * tests**: the footer read "0 fixes staged" for ever, Undo and Discard did nothing, and no test noticed,
 * because no test fed the change back the way `ScenarioEditor` does. So [surface] holds the expectation in
 * state, feeds every change back into a fresh session keyed on the step, and only then asserts.
 */
class DiffSurfaceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val outDir = File("build/scenario-screenshots").absoluteFile
    private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

    /** The four-failures ExecutionReport: a value changed, a tag added, a tag dropped, two parties swapped. */
    private val reply =
        wireView(
            35 to "8",
            11 to "ORD-7f3a",
            150 to "2",
            151 to "500000",
            453 to "2",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            2376 to "Y",
        )

    private val captured =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Reference("\${id0}")),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
                FieldExpectation(453, Matcher.Exact("2")),
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(58, Matcher.Exact("filled")),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )

    /** Two firms exchanging ROLES — identical on the wire to two entries swapping places, and a regression. */
    private val roleSwapExpectation =
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
            mode = MatchMode.OPEN,
        )
    private val rolesSwapped =
        wireView(453 to "2", 448 to "FIRMA", 447 to "D", 452 to "4", 448 to "FIRMB", 447 to "D", 452 to "1")

    /**
     * **The change is fed back**, as `ScenarioEditor` feeds it — a fresh `Expectation` arrives on the next
     * recomposition, and the session must survive it. A harness that merely records `onChange` proves nothing
     * about a surface whose whole job is to stage edits across that loop.
     */
    private fun ComposeContentTestRule.surface(
        initial: Expectation,
        message: MessageView,
        onEdit: (Expectation) -> Unit = {},
    ) {
        setContent {
            var expectation by remember { mutableStateOf(initial) }
            val session =
                // Keyed on the STEP — here, a constant — exactly as the app will key it on `stepId`. Keyed on
                // the expectation instead, every edit would build a new session and the stack would be lost
                // on the very click that created it.
                remember(Unit) {
                    ReconcileSession(
                        original = expectation,
                        initialReference =
                            ReferenceMessage.live(message, ReferenceMessage.Provenance.THIS_RUN, "this run", arrival),
                        dictionary = dictionary,
                        onChange = {
                            expectation = it
                            onEdit(it)
                        },
                    )
                }
            Box(modifier = Modifier.size(1180.dp, 620.dp).background(AppTheme.Colors.background)) {
                DiffSurface(session, crumb = "rfq flow v2 › Step 2 · Expect ExecutionReport (8)")
            }
        }
    }

    /**
     * The **value** field inside a row's MatcherEditor. The tag sits on the editor's root; the fields are
     * within it — and a numeric matcher has two of them (the value, and its tolerance), so the first is the
     * one an author means when they say "the value".
     */
    private fun valueFieldOf(matcherTag: String) =
        composeTestRule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag(matcherTag)))[0]

    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[DiffSurfaceTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    // ----- what a failure looks like ----------------------------------------------------------------------

    /** Every kind of failure at once, and the line that says which of them is a regression. */
    @Test
    fun `the surface renders every kind of failure, and separates shape from behaviour`() {
        composeTestRule.surface(captured, reply)

        composeTestRule.onNodeWithTag("diff-surface").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diff-summary").assertTextContains("need attention", substring = true)
        composeTestRule
            .onNodeWithTag("diff-shape-or-behaviour")
            .assertTextContains("only the value change alters what this scenario checks", substring = true)

        // A value mismatch offers Accept-actual; a missing tag offers absent and drop; an added tag, assert-it.
        composeTestRule.onNodeWithTag("accept_actual-151-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("assert_absent-58-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drop-58-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("assert_it-2376-0").assertIsDisplayed()
        // ...and the party that moved is banded, once per entry, with the crossing offered once.
        composeTestRule.onAllNodesWithTag("moved-band").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("accept-new-order").assertCountEquals(1)

        // AND NOT ONE PER-ROW FIX INSIDE THEM. A moved row reads as a value mismatch (FIRMA's 448 faces
        // FIRMB), so a gutter keyed on status alone drew an Accept-actual under it — and one click would
        // rebase FIRMA's assertion onto FIRMB while the 452 rows stayed put, deleting "FIRMA holds role 1"
        // and turning the step green. The entry moved as a unit; it is repaired as one.
        composeTestRule.onAllNodesWithTag("accept_actual-448-0").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("accept_actual-452-0").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("drop-448-0").assertCountEquals(0)

        snapshot("diff_surface_full.png")
    }

    /** A reference row is the third state: neither pass nor fail, amber, and offered nothing to click. */
    @Test
    fun `a reference row renders unjudged and is offered no fix`() {
        composeTestRule.surface(captured, reply)

        composeTestRule.onNodeWithTag("unjudged-note").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("accept_actual-11-0").assertCountEquals(0)
        composeTestRule.onNodeWithTag("diff-summary").assertTextContains("need attention", substring = true)
    }

    /** The occurrence is part of the address, and the reader has to be able to see which 452 is which. */
    @Test
    fun `a repeated tag shows which occurrence each row asserts`() {
        composeTestRule.surface(captured, reply)

        composeTestRule.onAllNodesWithText("#2").assertCountEquals(6) // 448, 447, 452 — both sides
    }

    // ----- the gutter edits, through the session ----------------------------------------------------------

    @Test
    fun `Accept actual stages the edit and the row goes green in the same frame`() {
        var edited: Expectation? = null
        composeTestRule.surface(captured, reply) { edited = it }

        composeTestRule.onNodeWithTag("accept_actual-151-0").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("diff-staged").assertTextEquals("1")
        assertTrue(
            (edited!!.fields[3].matcher as Matcher.Numeric).expected == 500000.0,
            "accept-actual re-seeds the row, keeping it numeric — it does not flatten it to exact",
        )
        composeTestRule.onAllNodesWithTag("accept_actual-151-0").assertCountEquals(0)
    }

    /** One click, one atomic rewrite: the party that arrived second is the one the expectation now lists second. */
    @Test
    fun `Accept new order fixes the moved entries in one click`() {
        var edited: Expectation? = null
        composeTestRule.surface(captured, reply) { edited = it }

        composeTestRule.onNodeWithTag("accept-new-order").performClick()
        composeTestRule.waitForIdle()

        val firms = edited!!.fields.filter { it.tag == 448 }.map { (it.matcher as Matcher.Exact).value }
        val roles = edited!!.fields.filter { it.tag == 452 }.map { (it.matcher as Matcher.Exact).value }
        assertEquals(listOf("FIRMB", "FIRMA"), firms)
        assertEquals(listOf("4", "1"), roles, "FIRMA still holds role 1 — the entry moved, it was not torn")
        composeTestRule.onAllNodesWithTag("moved-band").assertCountEquals(0)

        snapshot("diff_surface_order_accepted.png")
    }

    /** The bulk button is a promise: it never touches a value mismatch, which is the row that means something. */
    @Test
    fun `Accept all shape changes leaves the value mismatch alone`() {
        var edited: Expectation? = null
        composeTestRule.surface(captured, reply) { edited = it }

        composeTestRule.onNodeWithTag("diff-accept-shape").performClick()
        composeTestRule.waitForIdle()

        // The echo resolves at replay, so judge it with the scope a run would have: otherwise tag 11 "fails"
        // for a reason that has nothing to do with the button under test.
        val scope = { e: String -> if (e == "\${id0}") "ORD-7f3a" else null }
        val failing =
            ExpectationEvaluator.evaluate(reply, edited!!, scope, { arrival }).filterNot { it.passed }
        assertEquals(
            listOf(151),
            failing.map { it.tag },
            "the shape churn is gone and the one behaviour change is untouched: ${failing.map { it.tag }}",
        )
        snapshot("diff_surface_shape_accepted.png")
    }

    // ----- direct editing, judged live --------------------------------------------------------------------

    /**
     * **The row flips green as you type**, because the preview runs the real evaluator against the real
     * message. And the six keystrokes of `500000` are ONE edit: `⌘Z` means "undo the value I set", not "undo
     * the last character of a word I was in the middle of".
     */
    @Test
    fun `editing a value re-judges live, and the keystrokes are one edit`() {
        composeTestRule.surface(captured, reply)
        composeTestRule.onNodeWithTag("diff-summary").assertTextContains("need attention", substring = true)

        valueFieldOf("matcher-151-0").performTextReplacement("500000")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("diff-staged").assertTextEquals("1")
        composeTestRule.onAllNodesWithTag("accept_actual-151-0").assertCountEquals(0)

        composeTestRule.onNodeWithTag("diff-undo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("diff-staged").assertTextEquals("0")
        composeTestRule.onNodeWithTag("accept_actual-151-0").assertIsDisplayed()

        snapshot("diff_surface_editing.png")
    }

    /** Undo and redo walk the stack both ways — and the footer names what is staged. */
    @Test
    fun `undo and redo walk the stack, and the footer names the edits`() {
        composeTestRule.surface(captured, reply)

        composeTestRule.onNodeWithTag("accept_actual-151-0").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("diff-staged-labels").assertTextContains("Accepted 151", substring = true)

        composeTestRule.onNodeWithTag("diff-undo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("diff-staged").assertTextEquals("0")

        composeTestRule.onNodeWithTag("diff-redo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("diff-staged").assertTextEquals("1")
    }

    /** The promise in the footer is verbatim, and it is the whole contract of the surface. */
    @Test
    fun `the footer promises that nothing is written until save`() {
        composeTestRule.surface(captured, reply)

        composeTestRule
            .onNodeWithTag("diff-promise")
            .assertTextContains("nothing is written to the scenario until you save", substring = true)
    }

    // ----- the step that asserts nothing ------------------------------------------------------------------

    /** Drop every row and the step passes for ever while checking nothing. It must never read as a success. */
    @Test
    fun `dropping every row is never reported as a pass`() {
        val draft =
            Expectation(
                listOf(FieldExpectation(150, Matcher.Exact("X")), FieldExpectation(151, Matcher.Exact("Y"))),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        composeTestRule.surface(draft, wireView(150 to "2", 151 to "0"))

        composeTestRule.onNodeWithTag("drop-150-0").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drop-151-0").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("diff-summary")
            .assertTextContains("nothing is asserted", substring = true)

        snapshot("diff_surface_asserts_nothing.png")
    }

    // ----- the withheld move ------------------------------------------------------------------------------

    /**
     * THE ONE THAT MUST NEVER BE OFFERED. Two firms swapped roles: it looks exactly like two entries swapping
     * places, and a re-order here would rewrite "FIRMA holds role 1" into "FIRMA holds role 4" and call it
     * green. No violet, no ⇄ — and the engine's reason rendered where the author is looking.
     */
    @Test
    fun `a role swap offers no move, and the reason renders on the group`() {
        composeTestRule.surface(roleSwapExpectation, rolesSwapped)

        composeTestRule.onAllNodesWithTag("accept-new-order").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("moved-band").assertCountEquals(0)
        composeTestRule.onNodeWithTag("diff-no-move").assertTextContains("did not move", substring = true)
        // ...and the entries are still bracketed, with their arrows, because a hand move is still the author's
        // to make — the diff re-judges after it, so a move that lies goes red rather than green.
        composeTestRule.onAllNodesWithTag("entry-band").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("entry-up").assertCountEquals(2)

        snapshot("diff_surface_role_swap.png")
    }

    /** And a hand move on a role swap cannot fake a pass: the surface re-judges, and it stays red. */
    @Test
    fun `moving an entry by hand on a role swap cannot fake a pass`() {
        composeTestRule.surface(roleSwapExpectation, rolesSwapped)

        composeTestRule.onAllNodesWithTag("entry-down")[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("diff-summary").assertTextContains("need attention", substring = true)
    }

    // ----- the group bands --------------------------------------------------------------------------------

    /** The dictionary named these entries, and the band says what they are — not merely where they are. */
    @Test
    fun `entry bands carry the dictionary's own label`() {
        composeTestRule.surface(roleSwapExpectation, rolesSwapped)

        composeTestRule.onAllNodesWithTag("entry-label").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("entry-label")[0].assertTextContains("FIRMA", substring = true)
        composeTestRule.onAllNodesWithTag("entry-label")[1].assertTextContains("FIRMB", substring = true)
    }

    /** A group the dictionary has never heard of is still bracketed — and is visibly a guess. */
    @Test
    fun `a heuristic entry is badged as the guess it is`() {
        val draft =
            Expectation(
                listOf(
                    FieldExpectation(9001, Matcher.Exact("A")),
                    FieldExpectation(9002, Matcher.Exact("1")),
                    FieldExpectation(9001, Matcher.Exact("B")),
                    FieldExpectation(9002, Matcher.Exact("2")),
                ),
                messageType = "8",
                mode = MatchMode.OPEN,
            )
        val message = wireView(9001 to "A", 9002 to "1", 9001 to "B", 9002 to "2")

        composeTestRule.surface(draft, message)

        composeTestRule.onAllNodesWithTag("entry-guessed").assertCountEquals(2)
        snapshot("diff_surface_heuristic_entry.png")
    }
}
