package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.scenario.ExampleRow
import com.knapsack.fixtool.model.scenario.Examples
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The outline's table, in the editor.** Nobody hand-writes JSON to get one, so the claims worth pinning
 * are that it round-trips through the editor untouched, that editing it emits a scenario carrying the
 * change, and that renaming a column takes its cells with it — the one edit that could silently empty a
 * row, since a cell whose key no longer matches a column seeds nothing.
 */
class ScenarioExamplesTableTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outline =
        Scenario(
            id = "sc-1",
            name = "book-a-trade",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=ORD|55=\${symbol}|38=\${qty}|", "CLI"),
                    ScenarioStep.Expect(
                        session = "CLI",
                        expectation = Expectation(listOf(FieldExpectation(55, Matcher.Reference("\${symbol}"))), messageType = "8"),
                    ),
                ),
            examples =
                Examples(
                    columns = listOf("symbol", "qty"),
                    rows = listOf(ExampleRow("EUR/USD small", mapOf("symbol" to "EUR/USD", "qty" to "100"))),
                ),
        )

    private var latest: Scenario? = null

    private fun render(scenario: Scenario = outline) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1400.dp, 800.dp).background(AppTheme.Colors.background)) {
                ScenarioEditor(
                    initial = scenario,
                    dictionary = null,
                    sessionOptions = listOf("CLI"),
                    onSave = {},
                    onChange = { latest = it },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a scenario with a table opens on it, and emits it back unchanged`() {
        render()

        composeTestRule.onNodeWithTag("examples-table").assertIsDisplayed()
        composeTestRule.onNodeWithTag("examples-row-0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("examples-cell-0-0").assertIsDisplayed()

        val table = assertNotNull(latest?.examples, "the editor must emit the table it was given")
        assertEquals(listOf("symbol", "qty"), table.columns)
        assertEquals(mapOf("symbol" to "EUR/USD", "qty" to "100"), table.rows.single().values)
    }

    @Test
    fun `adding a column and a row reaches the emitted scenario`() {
        render()

        composeTestRule.onNodeWithTag("examples-add-column").performClick()
        composeTestRule.onNodeWithTag("examples-add-row").performClick()
        composeTestRule.waitForIdle()

        val table = assertNotNull(latest?.examples)
        assertEquals(3, table.columns.size, "a new column is named, not blank: ${table.columns}")
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[1].name.isNotBlank(), "and so is a new row")
    }

    /**
     * A cell whose key no longer matches a column seeds nothing — the value would vanish without a word,
     * and the codec drops it on the next load. So a rename takes the cells with it.
     */
    @Test
    fun `renaming a column renames its cells`() {
        render()

        composeTestRule.onNodeWithTag("examples-column-0").performTextClearance()
        composeTestRule.onNodeWithTag("examples-column-0").performTextInput("instrument")
        composeTestRule.waitForIdle()

        val table = assertNotNull(latest?.examples)
        assertEquals(listOf("instrument", "qty"), table.columns)
        assertEquals("EUR/USD", table.rows.single().values["instrument"], "the value moved with the name: ${table.rows}")
        assertNull(table.rows.single().values["symbol"])
    }

    /** Dropping a column drops its cells too, for the same reason. */
    @Test
    fun `dropping a column drops its cells`() {
        render()

        composeTestRule.onNodeWithTag("examples-drop-column-1").performClick()
        composeTestRule.waitForIdle()

        val table = assertNotNull(latest?.examples)
        assertEquals(listOf("symbol"), table.columns)
        assertEquals(mapOf("symbol" to "EUR/USD"), table.rows.single().values)
    }

    /**
     * The half-finished table, said out loud. Not an error — an author adding columns before the steps
     * that read them is working in the order that makes sense — but not something to discover by watching
     * a run pass while proving nothing about the column.
     */
    @Test
    fun `a column no step reads is named as unread`() {
        render(outline.copy(examples = outline.examples!!.copy(columns = outline.examples!!.columns + "unused")))

        composeTestRule.onNodeWithTag("examples-unread").assertIsDisplayed()
    }

    /**
     * **Nobody hand-writes the first table.** A captured scenario has literals baked into its sends, and
     * this is the door from one of them to a column: the literal becomes `${name}`, the column takes the
     * dictionary's name for the field, and the value it replaced becomes a cell.
     */
    @Test
    fun `extracting a send's literal makes a column, and the step reads it`() {
        render(
            Scenario(
                id = "sc-2",
                name = "captured",
                steps = listOf(ScenarioStep.Send("35=D|11=ORD-1|55=EUR/USD|", "CLI")),
            ),
        )

        // Row 2 is 55 — the send's own field order: 35, 11, 55.
        composeTestRule.onNodeWithTag("send-extract-2").performClick()
        composeTestRule.waitForIdle()

        val emitted = assertNotNull(latest)
        val table = assertNotNull(emitted.examples, "a table appears where there was none")
        assertEquals(1, table.columns.size)
        val column = table.columns.single()
        assertEquals("EUR/USD", table.rows.single().values[column], "the literal it replaced became the cell")
        val send = emitted.steps.single() as ScenarioStep.Send
        assertTrue(send.raw.contains("55=\${$column}"), "and the step now reads the column: ${send.raw}")
        assertTrue(!send.raw.contains("55=EUR/USD"), "the literal is gone from the wire")
    }

    /**
     * Every existing row keeps the literal — those rows already sent it, because the step's value *was*
     * one. Filling only the first would leave the others seeding an empty string: the same send, silently
     * carrying nothing where it used to carry a value.
     */
    @Test
    fun `extracting into a table that already has rows leaves every row sending what it sent`() {
        render(
            outline.copy(
                steps = listOf(ScenarioStep.Send("35=D|11=ORD-1|55=\${symbol}|40=1|", "CLI")),
                examples =
                    Examples(
                        columns = listOf("symbol"),
                        rows =
                            listOf(
                                ExampleRow("a", mapOf("symbol" to "EUR/USD")),
                                ExampleRow("b", mapOf("symbol" to "GBP/USD")),
                            ),
                    ),
            ),
        )

        // Row 3 is 40 (OrdType), the one literal left on this send.
        composeTestRule.onNodeWithTag("send-extract-3").performClick()
        composeTestRule.waitForIdle()

        val table = assertNotNull(latest?.examples)
        assertEquals(2, table.columns.size)
        val added = table.columns.last()
        assertEquals(listOf("1", "1"), table.rows.map { it.values[added] }, "both rows keep the value they sent")
    }

    /** And a scenario with no table costs one collapsed line — the feature existing is not a tax. */
    @Test
    fun `a scenario with no table shows the strip and emits no table`() {
        render(outline.copy(examples = null))

        composeTestRule.onNodeWithTag("examples-table").assertIsDisplayed()
        composeTestRule.onNodeWithTag("examples-toggle").assertIsDisplayed()
        assertNull(latest?.examples, "no columns and no rows is no table, not an empty one")
    }
}
