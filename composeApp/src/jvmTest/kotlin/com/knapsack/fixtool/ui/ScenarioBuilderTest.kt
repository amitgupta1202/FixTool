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
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Evidence for 3.4 (visual builder) and 3.6 (per-step multi-session): add steps by clicking, set a
 * different session per step, and save — producing a [Scenario] whose steps carry those sessions.
 */
class ScenarioBuilderTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outDir = File("build/scenario-screenshots").absoluteFile

    @Test
    fun `builds a multi-session scenario from clicks`() {
        var saved: Scenario? = null
        composeTestRule.setContent {
            Box(modifier = Modifier.size(940.dp, 520.dp).background(AppTheme.Colors.surface).padding(10.dp)) {
                ScenarioBuilder(dictionary = null, onSave = { saved = it })
            }
        }
        // Add a Send step (on the initiator) and a Clear step (on the acceptor).
        composeTestRule.onNodeWithText("send").performClick()
        composeTestRule.onNodeWithText("clear").performClick()
        composeTestRule.onNodeWithTag("scenario-name").performTextInput("multi-session flow")
        composeTestRule.onNodeWithTag("session-0").performTextInput("CLI")
        composeTestRule.onNodeWithTag("session-1").performTextInput("ACC")
        composeTestRule.waitForIdle()
        snapshot("scenario_builder.png")

        composeTestRule.onNodeWithText("Save scenario").performClick()
        composeTestRule.waitForIdle()

        val scenario = saved
        assertTrue(scenario != null, "scenario should be saved")
        assertEquals(2, scenario!!.steps.size)
        val send = scenario.steps[0] as ScenarioStep.Send
        val clear = scenario.steps[1] as ScenarioStep.ClearMessages
        assertEquals("CLI", send.session, "step 0 runs on the initiator session")
        assertEquals("ACC", clear.session, "step 1 runs on the acceptor session — multi-session in one scenario")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[ScenarioBuilderTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
