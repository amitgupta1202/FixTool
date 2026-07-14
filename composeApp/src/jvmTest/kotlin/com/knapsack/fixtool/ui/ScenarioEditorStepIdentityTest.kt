package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.withIds
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // Steps are now [B, C, D]; the selection must still be on C, and an edit must land on C. (The edit
        // used to be the STRICT toggle, which lived in the expectation builder — deleted, along with the
        // builder. The step editor no longer edits assertions at all; it edits the step, and so does this.)
        composeTestRule.onNodeWithTag("expect-timeout").performTextReplacement("777")
        composeTestRule.onNodeWithTag("editor-save").performClick()

        val steps = saved!!.steps.map { it as ScenarioStep.Expect }
        assertEquals(3, steps.size)
        assertEquals(
            listOf("B", "C", "D"),
            steps.map {
                (
                    it.expectation.fields
                        .single()
                        .matcher as Matcher.Exact
                ).value
            },
        )
        // The edit landed on C — the step that was actually selected.
        assertEquals(777L, steps[1].timeoutMs)
        // ...and D, which the user never opened, is untouched.
        assertEquals(10_000L, steps[2].timeoutMs)
    }

    /**
     * And identity means the id on disk, not merely the draft under the cursor — which is what this file
     * never asked, in every test above, by never moving a step and never looking at a `stepId`.
     *
     * `EditStep` carried no id, so Save handed the service a scenario whose steps had none, and
     * [withIds] — finding nothing to claim — minted every one of them from `(scenario, phase, index)`.
     * The id was a hash of the position again. In-place edits survived by luck (the minting is
     * deterministic, so a step that had not moved got its own id back); a **move** slid every id below
     * it onto the next step down, and a run held from before the save then named, by id, a step that was
     * not the one that failed. Where the two are alike — two Expects awaiting two fills of the same
     * shape — `reconcileRoute`'s equality check passes and the route opens on the *wrong* Expect, one
     * click of Accept actual from writing the failing message's bytes into an assertion that never saw
     * them. That is the corruption the id was introduced to prevent, re-created by the editor.
     */
    @Test
    fun `moving a step carries its id with it, instead of handing it to the neighbour`() {
        val loaded = scenario.withIds()
        val idA = loaded.steps[0].stepId
        val idB = loaded.steps[1].stepId
        var saved: Scenario? = null
        composeTestRule.setContent {
            ScenarioEditor(
                initial = loaded,
                dictionary = null,
                sessionOptions = emptyList(),
                onSave = { saved = it },
                onBack = {},
            )
        }

        composeTestRule.onAllNodesWithContentDescription("Down")[0].performClick() // A moves below B
        composeTestRule.onNodeWithTag("editor-save").performClick()

        val out = saved!!.steps.map { it as ScenarioStep.Expect }
        assertEquals(
            listOf("B", "A", "C", "D"),
            out.map {
                (
                    it.expectation.fields
                        .single()
                        .matcher as Matcher.Exact
                ).value
            },
        )
        assertEquals(idB, out[0].stepId, "B kept its id")
        assertEquals(idA, out[1].stepId, "A carried its id down with it")
        // And the service's own `withIds()` — which mints for every blank — has nothing left to mint.
        assertEquals(saved!!.steps.map { it.stepId }, saved!!.withIds().steps.map { it.stepId })
    }

    /**
     * The other half of the same rule, and the one R1 was written about: a step the author *inserts* takes
     * an id that is nobody else's. Minted from its position, it would be handed exactly the id the step
     * already sitting at that position is carrying — so it must be minted only after every existing step
     * has claimed its own, and salted past the collision.
     */
    @Test
    fun `an inserted step is minted an id of its own, and steals nobody's`() {
        val loaded = scenario.withIds()
        val before = loaded.steps.map { it.stepId }
        var saved: Scenario? = null
        composeTestRule.setContent {
            ScenarioEditor(
                initial = loaded,
                dictionary = null,
                sessionOptions = emptyList(),
                onSave = { saved = it },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithTag("step-row-0").performClick() // select A; insert lands under it
        composeTestRule.onNodeWithTag("add-expect").performClick()
        composeTestRule.onNodeWithTag("editor-save").performClick()

        val persisted = saved!!.withIds().steps
        assertEquals(5, persisted.size)
        // Every original step still carries the id it had, in its new place.
        assertEquals(before, listOf(0, 2, 3, 4).map { persisted[it].stepId })
        val minted = persisted[1].stepId
        assertTrue(minted.isNotBlank(), "the new step reaches disk with an id")
        assertTrue(minted !in before, "and it is not one of theirs")
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
