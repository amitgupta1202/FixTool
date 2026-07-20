package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.ReconcileCompletion
import com.knapsack.fixtool.ui.RepairedStep
import com.knapsack.fixtool.ui.StepChip
import com.knapsack.fixtool.ui.StepStatus
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **The two surfaces the pass added, drawn.**
 *
 * The strip and the completion state are the whole visible difference between "the window is a step" and "the
 * window is the pass", and neither can be reached from the control surface — Save & re-run is a click. So they
 * are pinned here instead: what a reader sees, and that a chip goes where it says it goes.
 */
class StepStripRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun chip(index: Int, status: StepStatus, current: Boolean = false, armed: Boolean = false) =
        StepChip(
            stepId = "step-$index",
            index = index,
            label = "${index + 1}",
            status = status,
            current = current,
            armed = armed,
            tooltip = "Step ${index + 1}",
        )

    private val chips =
        listOf(
            chip(1, StepStatus.REPAIRED),
            chip(3, StepStatus.FAILING, current = true),
            chip(5, StepStatus.NOT_RUN, armed = true),
        )

    @Test
    fun `the strip draws a chip per step and says what is left`() {
        rule.setContent { StepStrip(chips = chips, onSelect = {}) }

        rule.onNodeWithTag("diff-step-strip").assertIsDisplayed()
        chips.forEach { rule.onNodeWithTag("diff-step-chip-${it.index}").assertIsDisplayed() }
        rule
            .onNodeWithTag("diff-step-strip-summary")
            .assertTextContains("1 of 3 failing · 1 unsaved")
    }

    /** The chip is the navigation — clicking one must name the step it is about, not its position. */
    @Test
    fun `clicking a chip asks for that step by id`() {
        var asked: String? = null
        rule.setContent { StepStrip(chips = chips, onSelect = { asked = it }) }

        rule.onNodeWithTag("diff-step-chip-5").performClick()

        assertEquals("step-5", asked)
    }

    /**
     * **The pass has an ending, and it says what it took.** A green re-run used to leave the author staring at
     * a solved diff with no signal that anything had concluded.
     */
    @Test
    fun `the completion state names the scenario, the repairs and the way out`() {
        var done = false
        rule.setContent {
            ScenarioGreen(
                completion =
                    ReconcileCompletion(
                        listOf(
                            RepairedStep("a", "Step 2", listOf("Set 39 to one of [WRONG_A,2]")),
                            RepairedStep("b", "Step 4", listOf("Set 150 to exact 2")),
                        ),
                    ),
                scenarioName = "rfq flow",
                onDone = { done = true },
            )
        }

        rule.onNodeWithTag("diff-scenario-green").assertIsDisplayed()
        rule.onNodeWithText("This scenario is green.").assertIsDisplayed()
        rule.onNodeWithText("2 steps repaired · saved to rfq flow").assertIsDisplayed()
        rule.onNodeWithText("Step 2 — Set 39 to one of [WRONG_A,2]").assertIsDisplayed()

        rule.onNodeWithTag("diff-green-done").performClick()
        assertEquals(true, done)
    }

    /** A pass that repaired nothing still ended — it says so without claiming credit it has not earned. */
    @Test
    fun `a green run that needed no repair says so plainly`() {
        rule.setContent {
            ScenarioGreen(ReconcileCompletion(emptyList()), scenarioName = "rfq flow", onDone = {})
        }

        rule.onNodeWithText("rfq flow ran clean.").assertIsDisplayed()
    }

    /** A picture of both, for a reader who wants to see the pass rather than read assertions about it. */
    @Test
    fun `capture the strip and the completion state`() {
        val out = java.io.File(System.getProperty("fixtool.shots") ?: "build/shots").apply { mkdirs() }
        rule.setContent {
            Box(Modifier.size(760.dp, 300.dp).background(AppTheme.Colors.background)) {
                Column {
                    StepStrip(chips = chips, onSelect = {})
                    ScenarioGreen(
                        completion =
                            ReconcileCompletion(
                                listOf(
                                    RepairedStep("a", "Step 2", listOf("Set 39 to one of [WRONG_A,2]")),
                                    RepairedStep("b", "Step 4", listOf("Set 150 to exact 2")),
                                ),
                            ),
                        scenarioName = "rfq flow",
                        onDone = {},
                    )
                }
            }
        }
        javax.imageio.ImageIO.write(
            rule.onRoot().captureToImage().toAwtImage(),
            "png",
            java.io.File(out, "reconcile-pass.png"),
        )
    }

    /**
     * The close prompt names the size of what it would throw away. One step reads as it always did; a pass
     * says how many steps, because "unsaved edits" over five repaired steps teaches the author to click through.
     */
    @Test
    fun `the close prompt counts the steps once there is more than one`() {
        assertEquals("Discard unsaved edits and close?", discardPrompt(0))
        assertEquals("Discard unsaved edits and close?", discardPrompt(1))
        assertEquals("Discard unsaved repairs to 3 steps and close?", discardPrompt(3))
    }
}
