package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
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
        composeTestRule.setContent { view(captured, reply) {} }

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
        composeTestRule.setContent { view(captured, reply) { edited = it } }

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
        composeTestRule.setContent { view(outOfOrder, outOfOrderReply) { edited = it } }

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
        composeTestRule.setContent { view(outOfOrder, outOfOrderReply) { edited = it } }

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
        composeTestRule.setContent { view(expectation, swapped) { edited = it } }

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
        composeTestRule.setContent { view(allWrong, outOfOrderReply) { edited = it } }

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
}
