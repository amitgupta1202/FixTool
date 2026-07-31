package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.OrderConstraint
import com.knapsack.fixtool.model.ResponseStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // "— always" because there is now a second cancel-accepted preset, conditioned on the book
        // holding the order. Naming which one is being clicked is the point of both names.
        composeTestRule.onNodeWithText("Cancel accepted — always").performClick()

        assertEquals(1, rules.size)
        assertEquals("F", rules.single().whenMsgType, "the menu must insert the entry that was clicked")
        assertEquals(2, rules.single().sequence().size, "pending cancel, then canceled")
        assertNull(rules.single().whenOrder, "the stateless preset stays stateless — it works with no history at all")
    }

    /**
     * The same menu, the entry beside it. Two presets whose names differ only in what they ask of the
     * book, so a click that landed on the wrong one would be invisible in everything but the rule.
     */
    @Test
    fun `the preset menu inserts the conditioned cancel rule with its constraint intact`() {
        var rules = listOf<AcceptorResponseRule>()
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = rules, onRulesChange = { rules = it })
            }
        }

        composeTestRule.onNodeWithText("+ preset").performClick()
        composeTestRule.onNodeWithText("Cancel rejected — unknown order").performClick()

        assertEquals(OrderConstraint.UNKNOWN, rules.single().whenOrder)
    }

    /**
     * Every button in the step row, one at a time. Not a formality: these are 16dp buttons 4dp apart,
     * and Material3's default 48dp touch target had each one covering its left neighbour — so the row
     * looked complete and only its rightmost button did anything. Clicking each is the only assertion
     * that can tell the difference.
     */
    /**
     * Every button in the step row, one at a time. Not a formality: these are 16dp buttons 4dp apart,
     * and Material3's default 48dp touch target had each one covering its left neighbour — so the row
     * looked complete and only its rightmost button did anything. Clicking each is the only assertion
     * that can tell the difference.
     */
    @Test
    fun `every button on a step row acts, not just the last one`() {
        var latest = listOf(twoStepRule)
        val opened = mutableListOf<Pair<Int, Int>>()
        composeTestRule.setContent {
            // Held as state, so the row the next click lands on is the row the last click produced.
            var rules by remember { mutableStateOf(listOf(twoStepRule)) }
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(
                    rules = rules,
                    onRulesChange = {
                        rules = it
                        latest = it
                    },
                    onOpenStepInEditor = { r, st -> opened += r to st },
                )
            }
        }

        composeTestRule.onNodeWithTag("step-edit-0-2").performClick()
        assertEquals(listOf(0 to 1), opened, "the open button")

        composeTestRule.onAllNodesWithContentDescription("Move step earlier")[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            listOf(250L, 0L),
            latest.single().sequence().map { it.delayMillis },
            "Move earlier on step 2 — the button its neighbour used to swallow",
        )

        composeTestRule.onAllNodesWithContentDescription("Move step later")[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(0L, 250L), latest.single().sequence().map { it.delayMillis }, "Move later puts it back")

        composeTestRule.onAllNodesWithContentDescription("Delete step")[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, latest.single().sequence().size, "Delete step")
    }

    @Test
    fun `every button on a rule's own row acts`() {
        val second = AcceptorResponseRule(whenMsgType = "F", steps = listOf(ResponseStep("35=9")))
        var latest = listOf(twoStepRule, second)
        composeTestRule.setContent {
            var rules by remember { mutableStateOf(listOf(twoStepRule, second)) }
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(
                    rules = rules,
                    onRulesChange = {
                        rules = it
                        latest = it
                    },
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Enabled")[0].performClick()
        composeTestRule.waitForIdle()
        assertFalse(latest.first().enabled, "the enable toggle")

        composeTestRule.onAllNodesWithContentDescription("Move rule later")[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("F", "D"), latest.map { it.whenMsgType }, "Move later on rule 1")

        composeTestRule.onAllNodesWithContentDescription("Move rule earlier")[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("D", "F"), latest.map { it.whenMsgType }, "Move earlier on rule 2 puts it back")

        composeTestRule.onAllNodesWithContentDescription("Delete rule")[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("D"), latest.map { it.whenMsgType }, "Delete rule")
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

    // ---------------------------------------------------------------- the constraint no tag can express

    /**
     * The book constraint is always on the card, including in its off position. A rule that *could*
     * ask the venue's memory and does not is a thing an author has to be able to see in order to
     * change it — hiding the control behind an "add" would make the whole feature something you have
     * to already know about before you can find it.
     */
    @Test
    fun `every rule shows what it asks the book, including the rules that ask nothing`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = listOf(twoStepRule), onRulesChange = {})
            }
        }

        composeTestRule.onNodeWithText("and the order is").assertExists()
        composeTestRule.onNodeWithTag("rule-when-order").assertExists()
        composeTestRule.onNodeWithText("any ▾").assertExists()
    }

    @Test
    fun `picking a word from the menu writes it onto the rule, and 'any' takes it back off`() {
        var latest: AcceptorResponseRule? = null
        composeTestRule.setContent {
            var rules by remember { mutableStateOf(listOf(twoStepRule)) }
            latest = rules.single()
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = rules, onRulesChange = { rules = it })
            }
        }

        composeTestRule.onNodeWithTag("rule-when-order").performClick()
        composeTestRule.onNodeWithText("working").performClick()
        composeTestRule.waitForIdle()
        assertEquals(OrderConstraint.WORKING, latest?.whenOrder)

        composeTestRule.onNodeWithTag("rule-when-order").performClick()
        composeTestRule.onNodeWithText("any").performClick()
        composeTestRule.waitForIdle()
        assertNull(latest?.whenOrder, "a rule must be able to stop asking, not only start")
    }

    /** `pending` and `working` are the pair an author has to choose between, so each says what it is. */
    @Test
    fun `each word in the menu says what it means`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(700.dp)) {
                AcceptorRulesEditor(rules = listOf(twoStepRule), onRulesChange = {})
            }
        }

        composeTestRule.onNodeWithTag("rule-when-order").performClick()

        composeTestRule.onNodeWithText("this venue has never seen it").assertExists()
        composeTestRule.onNodeWithText("the venue has it; the client has not been told anything yet").assertExists()
        composeTestRule.onNodeWithText("acknowledged and not finished").assertExists()
    }
}
