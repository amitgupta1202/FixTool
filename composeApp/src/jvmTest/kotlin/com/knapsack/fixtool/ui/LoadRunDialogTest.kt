package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextInput
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every refusal is a sentence on screen, and Run is what refuses.** The dialog is driven without its
 * window, over a view model whose one profile has lanes configured and none logged on.
 */
class LoadRunDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    private val nos = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-load-dialog", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /** The refusal sentences on screen, as text. */
    private fun refusals(): List<String> =
        composeTestRule
            .onAllNodesWithTag("load-refusal")
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private fun profile(resetOnLogon: Boolean) =
        FixConnectionProfile(
            id = "lg",
            name = "LOADGEN",
            config = FixConnectionConfig(senderCompID = "LG{n}", targetCompID = "VENUE", host = "localhost", port = "9", sessionCount = 5, resetOnLogon = resetOnLogon),
        )

    @Test
    fun `the match is prefilled, the template is described, and no lanes means Run refuses with the fan-out sentence`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-request-tag").assertTextContains("11")
        composeTestRule.onNodeWithTag("load-reply-tag").assertTextContains("11")
        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        val refusals = composeTestRule.onAllNodesWithTag("load-refusal").fetchSemanticsNodes()
        assertEquals(2, refusals.size, "a missing seed and no lane logged on")
    }

    @Test
    fun `a memory store on a profile without Reset on Logon is refused until the store choice goes back to the profile's`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }
        composeTestRule.onNodeWithTag("load-seed").performTextClearance()
        composeTestRule.onNodeWithTag("load-seed").performTextInput("run=b7f2")

        val before = refusals()
        assertTrue(before.any { it.contains("Reset on Logon") }, before.toString())

        composeTestRule.onNodeWithTag("load-store-profile").performClick()
        composeTestRule.waitForIdle()

        val after = refusals()
        assertTrue(after.none { it.contains("Reset on Logon") }, after.toString())
    }

    @Test
    fun `switching the shape to a rate shows the rate fields`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-count").assertExists()
        composeTestRule.onNodeWithTag("load-rate").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-shape-rate").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").assertExists()
        composeTestRule.onNodeWithTag("load-for").assertTextContains("10m")
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
    }

    @Test
    fun `seeds read as name equals value, and a bare key seeds nothing`() {
        assertEquals(mapOf("run" to "b7f2", "desk" to "fx"), parseSeed("run=b7f2, desk=fx"))
        assertEquals(emptyMap(), parseSeed("run="))
        assertEquals(mapOf("a" to "1"), parseSeed("a=1\nnovalue"))
    }

    /** A SlimButton that is not enabled has no click action at all, which is what "refuses" means here. */
    @Test
    fun `the Run button is a real button that a test can read the state of`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }
        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        composeTestRule.onNodeWithTag("load-shape-burst").assertHasClickAction()
    }
}
