package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A step's identity is the step, not its position in the list.
 *
 * The detail editor seeds its drafts once per step and is keyed so it does not re-seed on every
 * keystroke. Keyed on the *index*, deleting a step above the selection slid a different step under
 * that index while the key stayed put: the next edit wrote the previously selected step's
 * assertions onto it, and Save persisted assertions the user had never authored onto a step they
 * had never opened.
 */
class ScenarioEditorStepIdentityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun expectStep(value: String) =
        ScenarioStep.Expect(
            expectation =
                Expectation(
                    fields = listOf(FieldExpectation(150, Matcher.Exact(value))),
                    messageType = "8",
                    mode = MatchMode.OPEN,
                ),
        )

    /** Four steps: with three, removal happens to shift the index onto the same step and hides this. */
    private val scenario =
        Scenario(
            id = "sc-1",
            name = "four expects",
            steps = listOf(expectStep("A"), expectStep("B"), expectStep("C"), expectStep("D")),
        )

    @Test
    fun `deleting a step above the selection does not write its assertions onto another step`() {
        var saved: Scenario? = null
        composeTestRule.setContent {
            ScenarioEditor(
                initial = scenario,
                dictionary = null,
                sessionOptions = emptyList(),
                onSave = { saved = it },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag("step-row-2").performClick() // select step C
        composeTestRule.onAllNodesWithContentDescription("Remove")[0].performClick() // delete step A
        // Steps are now [B, C, D]; the selection must still be on C, and an edit must land on C.
        composeTestRule.onNodeWithTag("strict-mode").performClick()
        composeTestRule.onNodeWithTag("editor-save").performClick()

        val steps = saved!!.steps.map { it as ScenarioStep.Expect }
        assertEquals(3, steps.size)
        assertEquals(listOf("B", "C", "D"), steps.map { (it.expectation.fields.single().matcher as Matcher.Exact).value })
        // The edit landed on C — the step that was actually selected.
        assertEquals(MatchMode.STRICT, steps[1].expectation.mode)
        // ...and D, which the user never opened, is untouched.
        assertEquals(MatchMode.OPEN, steps[2].expectation.mode)
    }

    @Test
    fun `the selection follows its own step across a removal`() {
        assertEquals(1, selectionAfterRemoval(removed = 0, selected = 2, remaining = 3), "a step above shifts it down")
        assertEquals(2, selectionAfterRemoval(removed = 3, selected = 2, remaining = 3), "a step below leaves it alone")
        assertEquals(2, selectionAfterRemoval(removed = 2, selected = 2, remaining = 3), "the slot is refilled")
        assertEquals(1, selectionAfterRemoval(removed = 1, selected = 1, remaining = 2), "clamped to the last step")
        assertEquals(-1, selectionAfterRemoval(removed = 0, selected = 0, remaining = 0), "nothing left to select")
    }
}
