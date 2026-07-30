package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of the rules editor a mouse reaches and an HTTP call cannot.
 *
 * The control surface covers inserting a preset and editing a rule; what it cannot press is the
 * button. So the preset menu, the empty-state offer and the hand-off to the message editor are
 * pinned here — a click, and the callback it is supposed to produce. Without this they are covered by
 * screenshots, which show that a button is drawn and not that it does anything.
 */
class AcceptorRulesEditorActionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val twoStepRule =
        AcceptorResponseRule(
            whenMsgType = "D",
            steps =
                listOf(
                    ResponseStep(template = "35=8|150=0|39=0"),
                    ResponseStep(template = "35=8|150=F|39=2", delayMillis = 250),
                ),
        )

    @Test
    fun `the step's button hands the editor that step, and not the first one`() {
        val opened = mutableListOf<Pair<Int, Int>>()
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(
                    rules = listOf(AcceptorResponseRule(whenMsgType = "F", steps = listOf(ResponseStep("35=9"))), twoStepRule),
                    onRulesChange = {},
                    onOpenStepInEditor = { ruleIndex, stepIndex -> opened += ruleIndex to stepIndex },
                )
            }
        }

        // Addressed by tag rather than by index among identical icons: the point of the assertion is
        // *which* step the editor is handed, and an index into a flat list of buttons is exactly the
        // thing that would still pass if the rows handed over the wrong one.
        composeTestRule.onNodeWithTag("step-edit-1-2").performClick()

        assertEquals(listOf(1 to 1), opened, "the editor must be handed the step that was clicked")
    }

    @Test
    fun `there is no button to open a step where there is no editor to open it in`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = listOf(twoStepRule), onRulesChange = {}, onOpenStepInEditor = null)
            }
        }

        composeTestRule.onNodeWithTag("step-edit-0-1").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ presets, by mouse

    @Test
    fun `the empty state offers the starter venue, and the offer inserts it`() {
        var rules = emptyList<AcceptorResponseRule>()
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = rules, onRulesChange = { rules = it })
            }
        }

        composeTestRule.onNodeWithText("No rules — incoming messages get no reply").assertExists()
        composeTestRule.onNodeWithText("Starter venue — 4 rules").performClick()

        assertEquals(4, rules.size, "the one-click offer has to produce the venue it names")
        assertTrue(rules.any { it.whenMsgType == "F" } && rules.any { it.whenMsgType == "G" })
    }

    @Test
    fun `the preset menu inserts the preset that was picked`() {
        var rules = listOf<AcceptorResponseRule>()
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = rules, onRulesChange = { rules = it })
            }
        }

        composeTestRule.onNodeWithText("+ preset").performClick()
        composeTestRule.onNodeWithText("Cancel accepted").performClick()

        assertEquals(1, rules.size)
        assertEquals("F", rules.single().whenMsgType, "the menu must insert the entry that was clicked")
        assertEquals(2, rules.single().sequence().size, "pending cancel, then canceled")
    }

    @Test
    fun `a rule an earlier one already answers is told so on its own card`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(
                    rules =
                        listOf(
                            AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep("35=8|39=0"))),
                            AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep("35=8|39=8"))),
                        ),
                    onRulesChange = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("⚠ never fires — rule 1 answers every 35=D. Move it earlier, or give it a condition.")
            .assertExists()
    }
}
