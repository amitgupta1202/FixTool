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
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workbench editor: loads a captured multi-session scenario as a flow list, edits without data
 * loss (match predicate, golden, STRICT mode, sessions all survive), and edits the bind predicate.
 */
class ScenarioEditorTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    private val expectation = Expectation(
        fields = listOf(
            FieldExpectation(11, Matcher.Reference("\${id0}")),
            FieldExpectation(150, Matcher.Exact("2")),
        ),
        messageType = "8",
        mode = MatchMode.STRICT,
        golden = "8=FIX.4.4|35=8|11=ORD-1|150=2|10=000|",
    )
    private val scenario = Scenario(
        id = "sc-1",
        name = "rfq flow",
        setup = listOf(ScenarioStep.ClearMessages("QUOTE")),
        steps = listOf(
            ScenarioStep.Send("35=D|11=\${id0 = UUID.randomUUID()}|", "QUOTE"),
            ScenarioStep.Expect("TRADE", "in", MatchPredicate("8", null, listOf(TagValue(150, "2"))), 5_000, expectation),
        ),
    )

    private fun render(dictionary: com.knapsack.fixtool.model.FixDictionary? = null, onSave: (Scenario) -> Unit) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1200.dp, 700.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                ScenarioEditor(
                    initial = scenario,
                    dictionary = dictionary,
                    sessionOptions = listOf("QUOTE", "TRADE"),
                    onSave = onSave,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `save preserves match predicate, golden, strict mode, and per-step sessions`() {
        var saved: Scenario? = null
        // A real dictionary so the screenshot shows field names / enum help as users see them.
        render(dictionary = com.knapsack.fixtool.model.FixDictionaryAdapter.forVersion(com.knapsack.fixtool.model.FixVersion.FIX_4_4)) { saved = it }
        composeTestRule.onNodeWithTag("scenario-name").performTextClearance()
        composeTestRule.onNodeWithTag("scenario-name").performTextInput("rfq flow v2")
        snapshot("workbench_editor.png")
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        val result = saved
        assertTrue(result != null, "scenario should be saved")
        assertEquals("rfq flow v2", result!!.name)
        assertEquals(scenario.setup, result.setup, "setup is preserved untouched")
        val send = result.steps[0] as ScenarioStep.Send
        val expect = result.steps[1] as ScenarioStep.Expect
        assertEquals("QUOTE", send.session)
        assertEquals("TRADE", expect.session, "multi-session steps keep their sessions")
        // The two data-loss bugs of the old builder: the bind predicate and the golden must survive.
        assertEquals(MatchPredicate("8", null, listOf(TagValue(150, "2"))), expect.match)
        assertEquals(expectation.golden, expect.expectation.golden)
        assertEquals(MatchMode.STRICT, expect.expectation.mode)
        assertEquals(5_000, expect.timeoutMs)
        assertEquals(Matcher.Reference("\${id0}"), expect.expectation.fields.first { it.tag == 11 }.matcher)
    }

    @Test
    fun `tags are pickable by field name from the dictionary`() {
        var saved: Scenario? = null
        render(dictionary = com.knapsack.fixtool.model.FixDictionaryAdapter.forVersion(com.knapsack.fixtool.model.FixVersion.FIX_4_4)) { saved = it }
        // Add a field to the selected Send step, then find the tag by NAME instead of number.
        composeTestRule.onNodeWithText("+ field").performClick()
        composeTestRule.onNodeWithTag("send-tag-2").performTextInput("Symbol")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("  Symbol").performClick() // autocomplete item "55  Symbol"
        composeTestRule.onNodeWithTag("send-value-2").performTextInput("EUR/USD")
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        val send = saved!!.steps[0] as ScenarioStep.Send
        assertTrue(send.raw.contains("55=EUR/USD"), "picking 'Symbol' by name should add tag 55; got ${send.raw}")
    }

    @Test
    fun `bind predicate is editable from the step detail`() {
        var saved: Scenario? = null
        render { saved = it }
        // Select the Expect step, then edit its bind predicate's message type.
        composeTestRule.onNodeWithTag("step-row-1").performClick()
        composeTestRule.onNodeWithTag("match-type").performTextClearance()
        composeTestRule.onNodeWithTag("match-type").performTextInput("S")
        composeTestRule.onNodeWithTag("editor-save").performClick()
        composeTestRule.waitForIdle()

        val expect = saved!!.steps[1] as ScenarioStep.Expect
        assertEquals("S", expect.match?.messageType)
        assertEquals(listOf(TagValue(150, "2")), expect.match?.fields, "constraints survive the type edit")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ScenarioEditorTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
