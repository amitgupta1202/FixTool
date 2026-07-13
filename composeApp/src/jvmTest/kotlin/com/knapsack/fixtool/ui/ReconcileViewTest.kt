package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
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
 * This is the surface where a tired engineer clicks things to make a red build go green, which makes it
 * the likeliest place in the whole tool to manufacture a false green. So the test that matters is not
 * "does Save work" — it is that **nothing is written until Save**, and that the one-click order fix
 * cannot be used to accept a value change it did not actually reconcile.
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

    /** Captured with ClOrdID ahead of OrderID; the venue sends OrderID first. Nothing is wrong with either. */
    private val reply = wire(35 to "8", 150 to "0", 37 to "EXEC-1", 11 to "ORD-1")
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
        composeTestRule.setContent {
            Box(modifier = Modifier.size(900.dp, 400.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                ReconcileView(outOfOrder, reply, dictionary, onChange = { edited = it })
            }
        }

        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("fail", substring = true)
        snapshot("reconcile_before.png")

        // One click puts the rows in the order the venue sends them.
        composeTestRule.onAllNodesWithText("Accept new order").onFirst().performClick()
        composeTestRule.waitForIdle()

        // The diff re-runs against the same message, so the author watches it go green.
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("would now pass", substring = true)
        snapshot("reconcile_after.png")

        // The edit is in the step already. It used to sit behind this view's own Save button, and clicking
        // another step in the list threw a whole session of repairs away without a word.
        val result = edited ?: error("the edit must reach the step as it happens, not on some later click")
        assertEquals(listOf(35, 150, 37, 11), result.fields.map { it.tag }, "re-ordered into the venue's order")
        assertTrue(
            ExpectationEvaluator.evaluate(reply, result).all { it.passed },
            "what the author watched turn green is what the step now holds",
        )
    }

    @Test
    fun `Revert puts the expectation back exactly as it was`() {
        var edited: Expectation? = null
        composeTestRule.setContent {
            Box(modifier = Modifier.size(900.dp, 400.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                ReconcileView(outOfOrder, reply, dictionary, onChange = { edited = it })
            }
        }

        composeTestRule.onAllNodesWithText("Accept new order").onFirst().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Revert").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("fail", substring = true)
        assertEquals(outOfOrder, edited, "revert must restore the expectation the author started from")
    }

    /**
     * Drop is offered on every failing row. Click it enough times and the step asserts nothing at all — and
     * a step that asserts nothing passes every run for ever while saying nothing about the venue. It used to
     * be rewarded with "✓ every assertion would now pass" in success green. That is the worst outcome this
     * tool can produce, and it is never allowed to read as a success.
     */
    @Test
    fun `dropping every row is never reported as a pass`() {
        var edited: Expectation? = null
        composeTestRule.setContent {
            Box(modifier = Modifier.size(900.dp, 400.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                ReconcileView(outOfOrder, reply, dictionary, onChange = { edited = it })
            }
        }

        repeat(outOfOrder.fields.size) {
            composeTestRule.onAllNodesWithText("Drop", substring = true).onFirst().performClick()
            composeTestRule.waitForIdle()
        }

        assertEquals(0, edited?.fields?.size, "precondition: everything has been dropped")
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("nothing is asserted", substring = true)
        composeTestRule.onNodeWithTag("reconcile-asserts-nothing").assertExists()
        snapshot("reconcile_asserts_nothing.png")
    }

    /**
     * The venue swapped its two party entries — the *values* moved, not just the positions. Accept-new-order
     * must not be a way to click through that: it would accept, in one click, an assertion that now checks
     * the other firm. The button is not even offered, because nothing here is merely out of place.
     */
    @Test
    fun `two entries that genuinely swapped cannot be reconciled by accepting an order`() {
        val swapped = wire(448 to "FIRMA", 452 to "4", 448 to "FIRMA", 452 to "1")
        val expectation =
            Expectation(
                listOf(
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("1")),
                    FieldExpectation(448, Matcher.Exact("FIRMA")),
                    FieldExpectation(452, Matcher.Exact("4")),
                ),
                messageType = "8",
            )
        var saved: Expectation? = null
        composeTestRule.setContent {
            Box(modifier = Modifier.size(900.dp, 400.dp).background(AppTheme.Colors.background).padding(8.dp)) {
                ReconcileView(expectation, swapped, dictionary, onChange = { saved = it })
            }
        }

        // Both roles mismatch on value, so there is nothing "moved" to accept — only values to accept
        // deliberately, one row at a time.
        composeTestRule.onNodeWithTag("reconcile-summary").assertTextContains("2 of 4", substring = true)
        composeTestRule.onAllNodesWithText("Accept new order").assertCountEquals(0)
        assertEquals(null, saved)
        snapshot("reconcile_swapped_entries.png")
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
