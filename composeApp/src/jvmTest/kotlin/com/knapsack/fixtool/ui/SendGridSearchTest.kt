package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * **Finding one row in a sixty-row Send step**, in the message editor's own idiom — the same box, the same
 * rule ([com.knapsack.fixtool.service.FieldSearch]) and the same gold, because an author who has learned it
 * in one field grid should not have to learn it again in the next.
 *
 * It marks and never filters, and that is not only taste here: a row's *position* decides which repeating
 * group entry it belongs to (see `ORDER_HINT`), so a filtered grid would leave Insert-below and the move
 * arrows acting on a list the author cannot see.
 */
class SendGridSearchTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val scenario =
        Scenario(
            id = "sc-search",
            name = "search",
            steps = listOf(ScenarioStep.Send("35=D|11=ORD-1|38=1000|54=1|55=EUR/USD|", "DEMO1")),
        )

    private fun editor() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                Box(modifier = Modifier.size(1200.dp, 640.dp).background(AppTheme.Colors.background)) {
                    ScenarioEditor(
                        initial = scenario,
                        dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                        sessionOptions = listOf("DEMO1"),
                        onSave = {},
                        selectedStep = 0,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun search(text: String) {
        composeTestRule.onNodeWithTag("send-search").performTextReplacement(text)
        composeTestRule.waitForIdle()
    }

    /** Rows carry one of two tags — matched or not — so the mark is assertable without reading pixels. */
    private fun assertMarked(vararg rows: Int) {
        val marked = rows.toSet()
        (0..4).forEach { i ->
            val tag = if (i in marked) "send-row-matched-$i" else "send-row-$i"
            composeTestRule.onNodeWithTag(tag).assertExists("row $i should be ${if (i in marked) "marked" else "unmarked"}")
        }
    }

    @Test
    fun `a field name marks its row and leaves the others alone`() {
        editor()
        // 35=D, 11=ORD-1, 38=1000, 54=1, 55=EUR/USD — row 1 is the ClOrdID.
        search("clordid")
        assertMarked(1)
        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, "send_grid_search.png"))
    }

    @Test
    fun `a tag marks its row`() {
        editor()
        search("55")
        assertMarked(4)
    }

    @Test
    fun `a value marks its row`() {
        editor()
        search("EUR")
        assertMarked(4)
    }

    /** Every row keeps its place — the grid marks, it does not filter. Row order is repeating-group meaning. */
    @Test
    fun `no row leaves the grid when a query matches only one`() {
        editor()
        search("clordid")
        // The unmatched rows are all still present and still editable, in their original positions.
        listOf(0, 2, 3, 4).forEach { composeTestRule.onNodeWithTag("send-value-$it").assertExists() }
        composeTestRule.onNodeWithTag("send-remove-4").assertExists()
    }

    @Test
    fun `clearing the query puts every row back to plain`() {
        editor()
        search("clordid")
        assertMarked(1)
        search("")
        assertMarked()
    }

    /**
     * A query with no matches has to *say so*. Without the tally an empty result is indistinguishable from a
     * grid that simply has nothing gold in it, and the author reads "not in this message" when the truth is
     * "you have mistyped it".
     */
    @Test
    fun `a query that matches nothing says no match`() {
        editor()
        search("nosuchfield")
        composeTestRule.onNodeWithTag("search-tally").assertIsDisplayed()
        composeTestRule.onNodeWithText("no match").assertExists()
        assertMarked()
    }

    /**
     * Asserted on the tally node itself, not on the text: this grid is full of ones and "1" alone finds the
     * `54=1` value field as readily as the count. A number needs its label to be a fact.
     */
    @Test
    fun `the tally counts the rows that answered`() {
        editor()
        composeTestRule.onNodeWithTag("search-tally").assertDoesNotExist() // silent until there is a query
        search("clordid")
        composeTestRule.onNodeWithTag("search-tally").assertTextEquals("1")
        search("1")
        // Tag 11, and the values 1000 and 1 — three rows answer, and the count is how the author knows to
        // narrow rather than to conclude the field is absent.
        composeTestRule.onNodeWithTag("search-tally").assertTextEquals("3")
    }

    /** The box takes what is typed — the search is per-step local state, and it must survive a keystroke. */
    @Test
    fun `the box holds the query it was given`() {
        editor()
        composeTestRule.onNodeWithTag("send-search").performTextInput("side")
        composeTestRule.waitForIdle()
        assertMarked(3)
    }
}
