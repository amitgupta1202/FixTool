package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.ScenarioReconcile
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconcile view driven the way a tester drives it: a red step, a click, a green one.
 *
 * This is the surface where a tired engineer clicks buttons to make a red build go green, which makes it the
 * likeliest place in the whole tool to manufacture a false green. So the tests that matter are not "does the
 * save work" — they are that the one-click order fix cannot be used to accept a value change it did not
 * actually reconcile, and that clicking Drop until the red clears is never reported as a pass.
 */
class ReconcileViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun wire(vararg fields: Pair<Int, String>): MessageView =
        object : MessageView {
            override fun fields(): List<Pair<Int, String>> = fields.toList()
        }

    /** Stamped now, so the temporal matcher passes and the only behaviour change is the one under test. */
    private val transactTime: String =
        java.time.LocalDateTime
            .now(java.time.ZoneOffset.UTC)
            .format(
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd-HH:mm:ss"),
            )

    /**
     * Renders the view **the way the app wires it**: `onChange` writes the expectation back, so the composable
     * recomposes with a new value on every click.
     *
     * `ScenarioEditor` does exactly this. The tests did not — they handed `onChange` a lambda that recorded
     * into a local var and stopped there — and that is the whole reason a completely dead staging mechanism
     * survived seven passing tests. In the running app the footer read "0 fixes staged" for ever, Undo did
     * nothing and Discard did nothing, because all four of the view's `remember`s were keyed on the value each
     * fix replaced. Nothing in this file could see it.
     *
     * Every test here goes through this now, so the loop under test is the loop that ships.
     */
    private fun ComposeContentTestRule.reconcile(
        initial: Expectation,
        actual: MessageView,
        onEdit: (Expectation) -> Unit = {},
    ) {
        setContent {
            var expectation by remember { mutableStateOf(initial) }
            view(expectation, actual) {
                expectation = it
                onEdit(it)
            }
        }
    }

    @Composable
    private fun view(expectation: Expectation, actual: MessageView, onChange: (Expectation) -> Unit) {
        Box(modifier = Modifier.size(1180.dp, 560.dp).background(AppTheme.Colors.background).padding(8.dp)) {
            ReconcileView(
                expectation = expectation,
                actual = actual,
                dictionary = dictionary,
                onChange = onChange,
                crumb = "Step 2 · Expect · ExecutionReport (8) · session CLI",
            )
        }
    }

    // ------------------------------------------------------- all four failures, in one real step

    /**
     * An ExecutionReport where every kind of failure happens at once — the message the design was drawn
     * against. The clearing firm now arrives before the executing firm; the venue added PartyRoleQualifier,
     * stopped sending Text, and filled 500,000 where it used to leave nothing.
     */
    private val reply =
        wire(
            35 to "8",
            11 to "ORD-1",
            17 to "EXEC-9",
            150 to "2",
            39 to "2",
            151 to "500000",
            14 to "1000000",
            31 to "1.0851",
            453 to "2",
            448 to "FIRMB",
            447 to "D",
            452 to "4",
            448 to "FIRMA",
            447 to "D",
            452 to "1",
            2376 to "Y",
            60 to transactTime,
        )

    private val captured =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(17, Matcher.Presence),
                FieldExpectation(150, Matcher.Exact("2")),
                FieldExpectation(39, Matcher.Exact("2")),
                FieldExpectation(151, Matcher.Numeric(0.0)),
                FieldExpectation(14, Matcher.Numeric(1000000.0)),
                FieldExpectation(31, Matcher.Numeric(1.0851)),
                FieldExpectation(453, Matcher.Exact("2")),
                // Captured with the executing firm first...
                FieldExpectation(448, Matcher.Exact("FIRMA")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("1")),
                // ...and the clearing firm second. The venue now sends them the other way round.
                FieldExpectation(448, Matcher.Exact("FIRMB")),
                FieldExpectation(447, Matcher.Exact("D")),
                FieldExpectation(452, Matcher.Exact("4")),
                FieldExpectation(58, Matcher.Exact("filled")),
                FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
            ),
            messageType = "8",
            mode = MatchMode.STRICT,
        )

    @Test
    fun `the diff separates shape from behaviour, and says which is which`() {
        composeTestRule.reconcile(captured, reply)

        // The line that stops a reader having to work it out: the venue reshaped the message in three ways,
        // and behaved differently in exactly one.
        composeTestRule
            .onNodeWithTag("reconcile-shape-or-behaviour")
            .assertTextContains("what this scenario checks", substring = true)
        snapshot("reconcile_full.png")
    }

    @Test
    fun `accept every shape change leaves the value change alone`() {
        var edited: Expectation? = null
        composeTestRule.reconcile(captured, reply) { edited = it }

        composeTestRule.onNodeWithText("Accept every shape change").performClick()
        composeTestRule.waitForIdle()
        snapshot("reconcile_shape_accepted.png")

        val result = edited ?: error("the bulk fix must reach the step")
        val failures = ExpectationEvaluator.evaluate(reply, result).filterNot { it.passed }
        assertEquals(
            listOf(151),
            failures.map { it.tag },
            "the shape is reconciled; the one row that means the venue behaved differently is still red",
        )
    }

    // ------------------------------------------------------------------------- the false greens

    /** Captured with ClOrdID ahead of OrderID; the venue sends OrderID first. Nothing is wrong with either. */
    private val outOfOrderReply = wire(35 to "8", 150 to "0", 37 to "EXEC-1", 11 to "ORD-1")
    private val outOfOrder =
        Expectation(
            listOf(
                FieldExpectation(35, Matcher.Exact("8")),
                FieldExpectation(150, Matcher.Exact("0")),
                FieldExpectation(11, Matcher.Exact("ORD-1")),
                FieldExpectation(37, Matcher.Presence),
            ),
            messageType = "8",
        )

    @Test
    fun `a moved row is fixed in one click, and the edit reaches the step immediately`() {
        var edited: Expectation? = null
        composeTestRule.reconcile(outOfOrder, outOfOrderReply) { edited = it }

        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("need attention", substring = true)

        composeTestRule.onAllNodesWithText("Accept new order").onFirst().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("would now pass", substring = true)
        composeTestRule.onNodeWithTag("reconcile-staged").assertTextEquals("1")

        // The edit is in the step already. It used to sit behind this view's own Save button, and clicking
        // another step in the list threw a whole session of repairs away without a word.
        val result = edited ?: error("the edit must reach the step as it happens")
        assertEquals(listOf(35, 150, 37, 11), result.fields.map { it.tag })
        assertTrue(ExpectationEvaluator.evaluate(outOfOrderReply, result).all { it.passed })
    }

    @Test
    fun `Undo last walks the fix back`() {
        var edited: Expectation? = null
        composeTestRule.reconcile(outOfOrder, outOfOrderReply) { edited = it }

        composeTestRule.onAllNodesWithText("Accept new order").onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Undo last").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reconcile-staged").assertTextEquals("0")
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("need attention", substring = true)
        assertEquals(outOfOrder, edited, "undo must restore the expectation the author started from")
    }

    /**
     * A venue that reorders its party entries has changed the message's **shape**, not its behaviour — and
     * the spec is explicit that this is a one-click acknowledgement, not a redesign of the scenario.
     *
     * Reordering the rows does not re-aim anything: the row asserting FIRMA lands on the entry that *is*
     * FIRMA, carrying its own matcher with it. Both roles stay asserted, and no coverage is lost. What must
     * never happen — and is pinned in ScenarioReconcileTest — is a row that was *passing* being dragged onto
     * a different occurrence, which is the false green this button once manufactured.
     */
    @Test
    fun `a reordered party entry is one click, and every assertion survives it`() {
        val swapped =
            wire(
                448 to "FIRMB",
                452 to "4",
                448 to "FIRMA",
                452 to "1",
            )
        val expectation =
            Expectation(
                listOf(
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(448, Matcher.Exact("FIRMB")),
                    FieldExpectation(452, Matcher.Exact("4")),
                ),
                messageType = "8",
            )
        var edited: Expectation? = null
        composeTestRule.reconcile(expectation, swapped) { edited = it }

        composeTestRule
            .onNodeWithTag("reconcile-shape-or-behaviour")
            .assertTextContains("all shape", substring = true)
        snapshot("reconcile_moved_entry.png")

        composeTestRule.onAllNodesWithText("Accept new order").onFirst().performClick()
        composeTestRule.waitForIdle()

        val result = edited ?: error("the re-order must reach the step")
        assertTrue(ExpectationEvaluator.evaluate(swapped, result).all { it.passed })
        assertEquals(
            listOf(Matcher.Exact("FIRMB"), Matcher.Exact("4"), Matcher.Exact("FIRMA"), Matcher.Exact("1")),
            result.fields.map { it.matcher },
            "every assertion is still there — the clearing firm is simply expected first now",
        )
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("would now pass", substring = true)
    }

    /**
     * Drop is offered on every failing row. Click it enough times and the step asserts nothing at all — and a
     * step that asserts nothing passes every run for ever while saying nothing about the venue. It used to be
     * rewarded with "✓ every assertion would now pass", in success green.
     */
    @Test
    fun `dropping every row is never reported as a pass`() {
        // Every row is red, so every row offers Drop. Click them all and the step asserts nothing.
        val allWrong =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("D")),
                    FieldExpectation(150, Matcher.Exact("9")),
                    FieldExpectation(11, Matcher.Exact("SOMETHING-ELSE")),
                ),
                messageType = "8",
            )
        var edited: Expectation? = null
        composeTestRule.reconcile(allWrong, outOfOrderReply) { edited = it }

        repeat(allWrong.fields.size) {
            composeTestRule.onAllNodesWithText("Drop", substring = true).onFirst().performClick()
            composeTestRule.waitForIdle()
        }

        assertEquals(0, edited?.fields?.size, "precondition: everything has been dropped")
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("nothing is asserted", substring = true)
        snapshot("reconcile_asserts_nothing.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ReconcileViewTest] snapshot '$name' skipped: ${e.message}")
        }
    }

    /**
     * The staging mechanism must survive the `onChange` it causes — which is what the real editor does.
     *
     * Every test in this file passed `onChange` a lambda that recorded into a local var and stopped there. The
     * running app does not: `ScenarioEditor` pushes the new expectation straight back into `step.expectation`,
     * so the composable recomposes with a NEW `expectation` value on every click. All four of ReconcileView's
     * `remember`s were keyed on it, so history, draft and original were destroyed each time — the footer read
     * "0 fixes staged" for ever, Undo was permanently dead, Discard did nothing, and no row ever showed as
     * staged. The author could not walk back a wrong click on the one surface where a wrong click manufactures
     * a false green.
     *
     * A whole suite of green tests said nothing about it, because none of them wired the loop the app wires.
     * This one does.
     */
    @Test
    fun `fixes stay staged when the editor feeds the change back, as the real app does`() {
        val actual = wire(35 to "8", 39 to "8", 58 to "rejected")
        val initial =
            Expectation(
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(39, Matcher.Exact("2")), // the venue now says 8 — a value change
                ),
                messageType = "8",
            )

        composeTestRule.reconcile(initial, actual)

        composeTestRule.onAllNodesWithText("Accept actual").onFirst().performClick()

        composeTestRule.onNodeWithTag("reconcile-staged").assertTextEquals("1")
    }

    // ------------------------------------------------- moving an entry by hand, and being told why not

    private fun party(id: String, role: String) =
        listOf(
            FieldExpectation(448, Matcher.Exact(id)),
            FieldExpectation(447, Matcher.Exact("D")),
            FieldExpectation(452, Matcher.Exact(role)),
        )

    private fun partyExpectation() = Expectation(party("FIRMA", "1") + party("FIRMB", "4"), messageType = "8")

    private fun threePartyExpectation() =
        Expectation(party("FIRMA", "1") + party("FIRMB", "4") + party("FIRMC", "7"), messageType = "8")

    /**
     * The arrow must do something the engine would NOT do, or the test cannot tell it apart from
     * Accept-new-order. Three parties, of which two swapped: the engine's plan moves B and C. The author
     * instead moves A down — a placement the plan never proposes — and the expectation that comes out is one
     * only the arrow can produce.
     */
    @Test
    fun `the arrow moves the entry the author picked, not the one the engine planned`() {
        // B and C swapped; A did not move. The engine's Accept-new-order would swap B and C back.
        val actual =
            wire(
                448 to "FIRMA",
                447 to "D",
                452 to "1",
                448 to "FIRMC",
                447 to "D",
                452 to "7",
                448 to "FIRMB",
                447 to "D",
                452 to "4",
            )
        var edited: Expectation? = null

        composeTestRule.reconcile(threePartyExpectation(), actual) { edited = it }

        // B and C are the entries the engine bracketed. The author moves B *up* — past FIRMA, an entry the
        // engine did not touch and never proposed touching.
        composeTestRule.onAllNodesWithTag("move-block-up").onFirst().performClick()

        val fields = edited!!.fields.map { it.tag to (it.matcher as Matcher.Exact).value }
        assertEquals(
            listOf(
                448 to "FIRMB",
                447 to "D",
                452 to "4",
                448 to "FIRMA",
                447 to "D",
                452 to "1",
                448 to "FIRMC",
                447 to "D",
                452 to "7",
            ),
            fields,
            "B swapped with A — and every party is still whole, firm still beside its own role",
        )
        val reordered = ScenarioReconcile.acceptNewOrder(threePartyExpectation(), actual)
        val plan = reordered?.fields?.map { it.tag to (it.matcher as Matcher.Exact).value }
        assertTrue(
            fields != plan,
            "the arrow must reach a placement the engine's plan does not — otherwise it proves nothing: $plan",
        )
    }

    /**
     * The arrows the mockup drew and nobody built. They live on the block, and clicking one moves the whole
     * entry — which is why the author, looking at a bracket, has a way to fix an alignment they disagree with
     * that does not involve dragging a single row past its sibling.
     */
    @Test
    fun `a bracketed entry can be moved by hand, as a whole, from the block header`() {
        // The entries swapped places (shape). FIRMA still holds role 1, so this is a real move.
        val actual = wire(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1")
        var edited: Expectation? = null

        composeTestRule.reconcile(partyExpectation(), actual) { edited = it }

        // One bracket per entry — not one lump around the whole group, which is what left the arrows with
        // nothing to swap with.
        composeTestRule.onAllNodesWithTag("moved-block").assertCountEquals(2)
        composeTestRule.onAllNodesWithTag("move-block-down").onFirst().performClick()

        val fields = edited!!.fields.map { it.tag to (it.matcher as Matcher.Exact).value }
        assertEquals(
            listOf(448 to "FIRMB", 447 to "D", 452 to "4", 448 to "FIRMA", 447 to "D", 452 to "1"),
            fields,
            "the entry moved whole: FIRMA is still the firm asserted alongside role 1",
        )
        composeTestRule.onNodeWithTag("reconcile-staged").assertTextEquals("1")
    }

    /**
     * THE ROLE SWAP. Two firms exchange roles; the `452` rows still pass where they are. No move is offered —
     * correctly — and the view must **say so**, because an author who sees red rows in a party group and no
     * re-order anywhere concludes the feature was never built. That is exactly what happened.
     */
    @Test
    fun `a role swap offers no move, and says why not`() {
        // Same positions, same roles; the FIRMS swapped. Nothing moved — the values changed in place.
        val actual = wire(448 to "FIRMB", 447 to "D", 452 to "1", 448 to "FIRMA", 447 to "D", 452 to "4")

        composeTestRule.reconcile(partyExpectation(), actual)

        composeTestRule.onNodeWithTag("moved-block").assertDoesNotExist()
        composeTestRule.onNodeWithTag("move-block-up").assertDoesNotExist()
        composeTestRule.onNodeWithTag("move-block-down").assertDoesNotExist()
        composeTestRule.onNodeWithTag("reconcile-no-move").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reconcile-no-move").assertTextContains("did not move", substring = true)
    }
}
