package com.knapsack.fixtool.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.LoadRunDefaults
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every refusal is a sentence on screen, under the row that caused it, and Run is what refuses.** The
 * dialog is driven without its window, over a view model whose one profile has lanes configured and none
 * logged on.
 */
class LoadRunDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    private val nos = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${run}-\${messageIndex}", 55 to "EUR/USD"))

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-dialog", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /** The refusal sentences on screen, as text. */
    private fun refusals(): List<String> = texts("load-refusal")

    private fun texts(tag: String): List<String> =
        composeTestRule
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "" }

    private fun profile(resetOnLogon: Boolean) =
        FixConnectionProfile(
            id = "lg",
            name = "LOADGEN",
            config =
                FixConnectionConfig(
                    senderCompID = "LG{n}",
                    targetCompID = "VENUE",
                    host = "localhost",
                    port = "9",
                    sessionCount = 5,
                    resetOnLogon = resetOnLogon,
                ),
        )

    private fun seedTheRun() {
        composeTestRule.onNodeWithTag("load-seed-value-0").performTextClearance()
        composeTestRule.onNodeWithTag("load-seed-value-0").performTextInput("b7f2")
        composeTestRule.waitForIdle()
    }

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
        seedTheRun()

        val before = refusals()
        assertTrue(before.any { it.contains("Reset on Logon") }, before.toString())

        composeTestRule.onNodeWithTag("load-store-profile").performClick()
        composeTestRule.waitForIdle()

        val after = refusals()
        assertTrue(after.none { it.contains("Reset on Logon") }, after.toString())
    }

    /**
     * The store radios live inside Advanced, which is collapsed when everything in it is fine. A refusal
     * that names one of them opens the group and says so — otherwise "the refusal sits next to its cause"
     * would be a claim the dialog breaks the moment the cause is hidden.
     */
    @Test
    fun `Advanced opens itself when a refusal names something inside it, and says why`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = false))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-store-profile").assertExists()
        seedTheRun()
        composeTestRule.onNodeWithTag("load-advanced-summary", useUnmergedTree = true).assertTextContains("the store", substring = true)
    }

    @Test
    fun `the footer says why Run is off rather than leaving a dead button to explain itself`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-run").assertHasNoClickAction()
        assertTrue(texts("load-why").single().isNotBlank(), "a disabled Run always has a reason on screen")
    }

    @Test
    fun `a preset chip sets the shape it names`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-preset-500").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-rate").assertTextContains("500")
        composeTestRule.onNodeWithTag("load-for").assertTextContains("10m")
        composeTestRule.onNodeWithTag("load-count").assertDoesNotExist()
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

    /** The count, the rate and the settle window were `remember` locals, so every open reset them. */
    @Test
    fun `the dialog opens on what this profile was last asked for`() {
        viewModel.saveConnectionProfile(profile(resetOnLogon = true))
        viewModel.rememberLoadRunDefaults("lg", LoadRunDefaults(burst = true, count = "250", settle = "5s", seed = listOf(listOf("run", "b7f2"))))

        composeTestRule.setContent { LoadRunDialogContent(viewModel, fixedTemplate = nos, onDismiss = {}, onRun = {}) }

        composeTestRule.onNodeWithTag("load-count").assertTextContains("250")
        composeTestRule.onNodeWithTag("load-settle").assertTextContains("5s")
        // The seed is inside Advanced, which stays shut while everything in it is fine.
        composeTestRule.onNodeWithTag("load-advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-seed-value-0").assertTextContains("b7f2")
    }

    @Test
    fun `a seed name with no value seeds nothing, which is what leaves the refusal standing`() {
        assertEquals(mapOf("run" to "b7f2", "desk" to "fx"), seedMap(listOf("run" to "b7f2", "desk" to "fx")))
        assertEquals(emptyMap(), seedMap(listOf("run" to "")))
        assertEquals(mapOf("a" to "1"), seedMap(listOf("a" to "1", "" to "2")))
    }

    /** The CLI already takes every field the dialog collects, so this is a transcription, not a grammar. */
    @Test
    fun `Copy as fixtool load writes the line that runs the same plan`() {
        val plan =
            LoadPlan(
                id = "x",
                label = "l",
                template = nos,
                profileId = "lg",
                profileName = "RFQ Load Client",
                shape = LoadShape.Rate(500, 600_000),
                match = LoadMatch(131, 131, "S"),
                settleMs = 60_000,
                seed = mapOf("run" to "b7f2"),
                storeAndLog = StoreAndLogOverride.FOR_LOAD,
            )

        assertEquals(
            "fixtool load NOS --profile \"RFQ Load Client\" --rate 500/s --for 10m --settle 1m " +
                "--match 131=131 --reply-type S --set run=b7f2 --store memory --log none",
            cliLine(plan),
        )
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
