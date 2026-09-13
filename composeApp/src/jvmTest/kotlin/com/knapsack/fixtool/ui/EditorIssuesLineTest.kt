package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What is wrong with a message, in one row, in the editor and in the scenario editor.**
 *
 * The defects behind it, each pinned: the editor's list had no height cap and pushed its grid away; it went on
 * naming an error after the field was corrected; its count was QuickFIX/J's first exception; a list with one
 * warning in it was titled "warnings"; and the scenario editor said what was wrong with a Send only while that
 * Send was selected.
 */
class EditorIssuesLineTest {
    @get:Rule
    val rule = createComposeRule()

    private val dictionary: FixDictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    /** Side is not a side, the quantity has a thousands separator, and Symbol is missing. */
    private val brokenOrder =
        listOf(
            FixField(tag = "35", value = "D"),
            FixField(tag = "11", value = "ORD-1"),
            FixField(tag = "54", value = "X"),
            FixField(tag = "60", value = "20260913-10:00:00.000"),
            FixField(tag = "38", value = "1,000"),
            FixField(tag = "40", value = "1"),
        )

    private var fields by mutableStateOf(brokenOrder)
    private var reported by mutableStateOf(emptyList<String>())
    private val selected = mutableListOf<Int>()

    private fun renderEditor() {
        rule.setContent {
            Box(Modifier.width(700.dp).size(700.dp, 800.dp)) {
                MessageEditorPanel(
                    sessions = emptyList(),
                    selectedSession = null,
                    dictionary = dictionary,
                    fields = fields,
                    selectedFieldIndex = 0,
                    onFieldUpdate = { i, f -> fields = fields.toMutableList().also { it[i] = f } },
                    onFieldAdd = { fields = fields + FixField() },
                    onFieldDelete = {},
                    onFieldMoveUp = {},
                    onFieldMoveDown = {},
                    onFieldSelect = { i, _, _ -> selected += i },
                    onClearFields = {},
                    onClose = {},
                    onSend = {},
                    onValidate = { emptyList() },
                    validationErrors = reported,
                    onClearValidationErrors = { reported = emptyList() },
                )
            }
        }
        rule.waitForIdle()
    }

    /** The count is every problem, and the line is one row whatever it holds. */
    @Test
    fun `Validate counts every problem, and the line under the search box is one row until opened`() {
        renderEditor()
        rule.onNodeWithTag("editor-issues").assertDoesNotExist()

        rule.onNodeWithTag("editor-validate").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("editor-validation-badge").assertTextContains("3 errors")
        rule.onNodeWithTag("editor-issues-errors", useUnmergedTree = true).assertTextContains("3")
        assertEquals(
            26f,
            rule
                .onNodeWithTag("editor-issues")
                .getUnclippedBoundsInRoot()
                .height.value,
            0.5f,
            "one row, collapsed",
        )
        rule.onNodeWithTag("editor-issues-item-0").assertDoesNotExist()

        rule.onNodeWithTag("editor-issues-toggle").performClick()
        rule.waitForIdle()

        (0..2).forEach { rule.onNodeWithTag("editor-issues-item-$it").assertExists() }
    }

    /** Each issue goes to the row it is about, and a missing field is added rather than gone to. */
    @Test
    fun `an issue selects its row, and a missing field is added`() {
        renderEditor()
        rule.onNodeWithTag("editor-validate").performClick()
        rule.onNodeWithTag("editor-issues-toggle").performClick()
        rule.waitForIdle()

        // Errors in row order, the rowless last: Side (row 2), OrderQty (row 4), then Symbol, which is missing.
        rule.onNodeWithTag("editor-issues-item-0").performClick()
        rule.waitForIdle()
        assertEquals(2, selected.last(), "Side's issue selects Side's row")

        rule.onNodeWithTag("editor-issues-item-2").assertTextContains("Add", substring = true)
        rule.onNodeWithTag("editor-issues-item-2").performClick()
        rule.waitForIdle()
        assertTrue(fields.any { it.tag == "55" }, "the missing Symbol is added; fields now $fields")
    }

    /**
     * **An edit withdraws what an action reported**, because it was about the message as it was. The badge used
     * to clear while the list under it went on naming an error in a value that had just been corrected.
     */
    @Test
    fun `editing a field withdraws what an action reported`() {
        reported = listOf("❌ Cannot send message - Fix template expression errors:", "Field 11: Unknown function 'uuidd'")
        renderEditor()
        rule.onNodeWithTag("editor-issues-first", useUnmergedTree = true).assertTextContains("Cannot send message", substring = true)

        rule.onNodeWithTag("editor-add-field").performClick()
        rule.waitForIdle()

        assertEquals(emptyList(), reported)
        rule.onNodeWithTag("editor-issues").assertDoesNotExist()
    }

    /** One warning in a list does not make the list a list of warnings. */
    @Test
    fun `a list holding an error and a warning counts each`() {
        fields =
            brokenOrder.filterNot { it.tag == "54" || it.tag == "38" } + FixField(tag = "54", value = "1") + FixField(tag = "55", value = "EUR/USD")
        reported = listOf("Field 11: Unknown function 'uuidd'", "WARNING: sent, but the venue has no session")
        renderEditor()

        rule.onNodeWithTag("editor-issues-errors", useUnmergedTree = true).assertTextContains("1")
        rule.onNodeWithTag("editor-issues-warnings", useUnmergedTree = true).assertTextContains("1")
        rule.onNodeWithTag("editor-validation-badge").assertTextContains("1 error")
    }

    /**
     * **The scenario says which steps are wrong without opening them.** The lint used to be a sentence above the
     * selected Send's grid and nowhere else.
     */
    @Test
    fun `the scenario editor counts a bad Send on its row and in the line, without selecting it`() {
        val scenario =
            Scenario(
                id = "sc-issues",
                name = "issues",
                steps =
                    listOf(
                        ScenarioStep.Send("35=D|11=ORD-1|55=EUR/USD|54=1|60=20260913-10:00:00.000|40=1|", "QUOTE"),
                        ScenarioStep.Send("35=D|11=ORD-2|55=EUR/USD|54=X|60=20260913-10:00:00.000|40=1|", "QUOTE"),
                    ),
            )
        rule.setContent {
            Box(Modifier.size(1200.dp, 700.dp).background(AppTheme.Colors.background)) {
                var picked by remember { mutableStateOf(0) }
                ScenarioEditor(
                    initial = scenario,
                    dictionary = dictionary,
                    sessionOptions = listOf("QUOTE"),
                    onSave = {},
                    selectedStep = picked,
                    onSelectStep = { picked = it },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("step-issues-0", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("step-issues-1", useUnmergedTree = true).assertTextContains("1", substring = true)
        rule.onNodeWithTag("scenario-issues-errors", useUnmergedTree = true).assertTextContains("1")

        rule.onNodeWithTag("scenario-issues-first", useUnmergedTree = true).assertTextContains("Step 2", substring = true)
        rule.onNodeWithTag("scenario-issues-toggle").performClick()
        rule.onNodeWithTag("scenario-issues-item-0").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("step-row-1").assertExists()
        rule.onNodeWithTag("send-row-2").assertExists()
    }
}
