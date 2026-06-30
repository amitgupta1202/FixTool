package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * Verifies the in-app run-results rendering (the answer to "where does a failed assertion show up")
 * and writes PNG snapshots of the red/green report for eyeballing. Image capture is best-effort so
 * the test stays CI-safe on headless agents.
 */
class ScenarioResultsViewTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outDir = File("build/scenario-screenshots").absoluteFile

    private fun passingResult() =
        ScenarioResult(
            scenario = "book-a-trade",
            passed = true,
            steps = listOf(
                StepResult(0, "send", "steps", passed = true, detail = "35=D|11=ORD-1|"),
                StepResult(
                    1, "expect", "steps", passed = true,
                    tags = listOf(
                        TagResult(35, "exact 8", "8", "8", passed = true),
                        TagResult(150, "exact 0", "0", "0", passed = true),
                        TagResult(11, "reference \${out.D.11}", "ORD-1", "ORD-1", passed = true),
                        TagResult(37, "presence", "<present>", "OID-42", passed = true),
                    ),
                ),
            ),
        )

    private fun failingResult() =
        ScenarioResult(
            scenario = "book-a-trade",
            passed = false,
            steps = listOf(
                StepResult(0, "send", "steps", passed = true, detail = "35=D|11=ORD-1|"),
                StepResult(
                    1, "expect", "steps", passed = false,
                    tags = listOf(
                        TagResult(35, "exact 8", "8", "8", passed = true),
                        TagResult(39, "oneOf [0,1,2]", "0 | 1 | 2", "8", passed = false),
                        TagResult(58, "absent", "<absent>", "Order rejected: limit", passed = false),
                    ),
                ),
            ),
        )

    @Composable
    private fun view(result: ScenarioResult) {
        // Reproduce the real container (ResultsPane wraps this in a verticalScroll) so a
        // LazyColumn-in-scrollable regression would crash this test.
        Box(modifier = Modifier.size(860.dp, 360.dp).background(AppTheme.Colors.background).padding(12.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ScenarioResultsView(result)
            }
        }
    }

    @Test
    fun `passing result renders green pass rows`() {
        composeTestRule.setContent { view(passingResult()) }
        composeTestRule.onNodeWithText("book-a-trade").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("PASS").fetchSemanticsNodes().isNotEmpty())
        snapshot("scenario_results_pass.png")
    }

    @Test
    fun `failing result renders the offending tag with expected and actual`() {
        composeTestRule.setContent { view(failingResult()) }
        // Overall + step verdicts both fail.
        assertTrue(composeTestRule.onAllNodesWithText("FAIL").fetchSemanticsNodes().isNotEmpty())
        // The failing tags explain themselves: expected (oneOf set) and the offending actual.
        composeTestRule.onNodeWithText("0 | 1 | 2", substring = true).assertExists()
        composeTestRule.onNodeWithText("Order rejected: limit", substring = true).assertExists()
        snapshot("scenario_results_fail.png")
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val image = composeTestRule.onRoot().captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(outDir, name))
            println("[ScenarioResultsViewTest] wrote ${File(outDir, name).absolutePath}")
        } catch (e: Exception) {
            println("[ScenarioResultsViewTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
