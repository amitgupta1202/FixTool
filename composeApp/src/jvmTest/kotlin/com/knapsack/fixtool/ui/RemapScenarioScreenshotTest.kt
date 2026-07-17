package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
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
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The environment-copy door, screenshotted the way [ScenarioDocumentsScreenshotTest] gates its phase:
 * the ▾ beside Run opens the remap dialog, and Create materializes a NEW scenario in the rail — the
 * original untouched. That last claim is the design ("environments diverge, copies reconcile
 * independently"), so the test pins it on the store, not just the pixels.
 */
class RemapScenarioScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val outDir = File("build/scenario-screenshots").absoluteFile
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-remapshots", "").apply {
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
    fun `the remap dialog creates a new scenario aimed at the picked sessions, leaving the original alone`() {
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

        composeTestRule.setContent {
            // The same chrome every real window applies — without it the dialog surface falls back to
            // Material's light defaults and the screenshot shows a theme no user ever sees.
            FixToolWindowChrome {
                Box(modifier = Modifier.size(900.dp, 560.dp).background(AppTheme.Colors.background)) {
                    ScenariosRail(viewModel, modifier = Modifier.size(380.dp, 560.dp))
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scenario-row-sc-env").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("remap-sc-env").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("remap-name").performTextReplacement("book a trade (qa)")
        composeTestRule.onNodeWithTag("remap-target-dev-buyside").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("qa-buyside").performClick()
        composeTestRule.onNodeWithTag("remap-target-dev-sellside").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("qa-sellside").performClick()
        composeTestRule.waitForIdle()
        snapshot("remap-dialog.png")

        composeTestRule.onNodeWithTag("remap-create").performClick()
        composeTestRule.waitForIdle()

        // The copy is a real scenario in the store, aimed at QA in every step — and the original still
        // says dev. Two documents, two environments, no shared fate.
        val copy = viewModel.scenarioService.list().single { it.name == "book a trade (qa)" }
        assertEquals(listOf("qa-buyside", "qa-sellside"), copy.steps.map { it.session })
        assertEquals(
            listOf("dev-buyside", "dev-sellside"),
            viewModel.scenarioService.load("sc-env")!!.steps.map { it.session },
            "creating the copy must not touch the original",
        )
        composeTestRule.onNodeWithTag("scenario-row-${copy.id}").assertIsDisplayed()
        snapshot("remap-rail-after-create.png")
    }

    /** A popup/dialog is its own semantics root on desktop; capture it when one is open. */
    private fun snapshot(name: String) {
        try {
            outDir.mkdirs()
            val popups = composeTestRule.onAllNodes(isPopup())
            val image =
                if (popups.fetchSemanticsNodes().isNotEmpty()) {
                    popups.onFirst().captureToImage()
                } else {
                    composeTestRule.onAllNodes(isRoot()).onFirst().captureToImage()
                }
            ImageIO.write(image.toAwtImage(), "png", File(outDir, name))
        } catch (e: Exception) {
            println("[RemapScenarioScreenshotTest] snapshot '$name' skipped: ${e.message}")
        }
    }
}
