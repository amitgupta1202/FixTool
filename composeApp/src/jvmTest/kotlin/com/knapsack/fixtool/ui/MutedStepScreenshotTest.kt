package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * **A muted step, photographed** (`muted_step_row.png`): the row keeps its place and dims its voice —
 * label in the disabled color, a MUTED chip beside it, the speaker toggle in warning tint — and the
 * toggle is a real toggle: one click parks the step, the next un-parks it.
 */
class MutedStepScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a muted step row wears the chip, and the toggle un-parks it`() {
        var latest: Scenario? = null
        val scenario = Scenario(
            id = "sc-m",
            name = "half-parked flow",
            steps = listOf(
                ScenarioStep.Send("35=D|11=\${id0 = uuid:20}|55=EUR/USD|", "DEMO1", muted = true),
                ScenarioStep.Expect(
                    "DEMO1", "in", MatchPredicate("8"), 10_000,
                    Expectation(emptyList(), messageType = "8"),
                ),
            ),
        )
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1280.dp, 400.dp).background(AppTheme.Colors.background).padding(10.dp)) {
                ScenarioEditor(
                    initial = scenario,
                    dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4),
                    sessionOptions = listOf("DEMO1"),
                    onSave = {},
                    selectedStep = 0,
                    onChange = { latest = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Muted, and saying so: the chip shows in the step row AND on the detail title.
        composeTestRule.onNodeWithTag("mute-step-0").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("MUTED").fetchSemanticsNodes().size >= 2,
            "the MUTED chip must show on the step row and the detail title",
        )
        assertTrue(latest?.steps?.get(0)?.muted == true, "the draft the host holds must carry the mute")

        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, "muted_step_row.png"))

        // The toggle un-parks: same button, opposite state, reported through onChange like any other edit.
        composeTestRule.onNodeWithTag("mute-step-0").performClick()
        composeTestRule.waitForIdle()
        assertTrue(latest?.steps?.get(0)?.muted == false, "one click on the speaker must unmute the step")
    }
}
