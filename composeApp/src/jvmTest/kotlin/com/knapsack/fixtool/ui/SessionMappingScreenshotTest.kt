package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.SessionMapping
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * The "Run on…" surface, screenshotted the way [ScenarioDocumentsScreenshotTest] gates its phase:
 * the saved-mapping menu a hover reveals, and the dialog that authors a new mapping. The one
 * behavioural pin here earns its keep: the menu must survive the pointer leaving the row to reach
 * it — the open menu overlays the row, so "hover reveals the actions" composed naively with "the
 * menu is one of the actions" closes the menu the instant anyone reaches for an item.
 */
class SessionMappingScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outDir = File("build/scenario-screenshots").absoluteFile
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-mapshots", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `the run-on menu survives losing hover, and the mapping dialog offers the scenario's sessions`() {
        viewModel.createSessionForTest("qa-buyside")
        viewModel.createSessionForTest("qa-sellside")
        val scenario =
            Scenario(
                id = "sc-env",
                name = "book a trade (dev)",
                steps =
                    listOf(
                        ScenarioStep.Send("35=D|11=ORD-1|", session = "dev-buyside"),
                        ScenarioStep.Expect(
                            session = "dev-sellside",
                            expectation = Expectation(fields = listOf(FieldExpectation(35, Matcher.Exact("8"))), messageType = "8"),
                        ),
                    ),
            )
        assertTrue(viewModel.scenarioService.save(scenario))
        viewModel.saveSessionMapping(
            SessionMapping("map-qa", "QA", mapOf("dev-buyside" to "qa-buyside", "dev-sellside" to "qa-sellside")),
        )
        viewModel.saveSessionMapping(
            SessionMapping("map-uat", "UAT", mapOf("dev-buyside" to "uat-buyside", "dev-sellside" to "uat-sellside")),
        )

        composeTestRule.setContent {
            // The same chrome every real window applies — without it the menu and dialog surfaces fall
            // back to Material's light defaults and the screenshot shows a theme no user ever sees.
            FixToolWindowChrome {
                Box(modifier = Modifier.size(900.dp, 560.dp).background(AppTheme.Colors.background)) {
                    ScenariosRail(viewModel, modifier = Modifier.size(380.dp, 560.dp))
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scenario-row-sc-env").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("run-on-sc-env").performClick()
        composeTestRule.waitForIdle()
        // The pin: reaching for an item takes the pointer off the row; both mappings must still be there.
        composeTestRule.onNodeWithTag("run-on-sc-env-map-qa").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-on-sc-env-map-uat").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("run-on-sc-env-map-qa").assertIsDisplayed()
        snapshot("run-on-menu.png")

        composeTestRule.onNodeWithTag("run-on-new-sc-env").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mapping-name").performTextReplacement("QA")
        // Aim one of the recorded sessions at a live QA one, through the dropdown a tester would use.
        composeTestRule.onNodeWithTag("mapping-target-dev-buyside").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("qa-buyside").performClick()
        composeTestRule.waitForIdle()
        snapshot("mapping-dialog.png")
    }

    /**
     * Like [ScenarioDocumentsScreenshotTest]'s snapshot, with one addition: a popup (menu, dialog) is its
     * own semantics root on desktop, so when one is open the *last* root is what the user is looking at.
     */
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val roots = composeTestRule.onAllNodes(isPopup())
            val image =
                if (roots.fetchSemanticsNodes().isNotEmpty()) {
                    roots.onFirst().captureToImage()
                } else {
                    composeTestRule.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureToImage()
                }
            ImageIO.write(image.toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[SessionMappingScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
