package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * **Compare's own tab, and the state that makes it trustworthy.** Both runs come off disk, so nothing is
 * rerun and a record the CLI wrote overnight is as good a partner as one fired here.
 */
class LoadCompareTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-compare", "").apply {
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
    fun `the document header opens Compare over this run`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("load-compare").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val docs = viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadCompare>()
        assert(docs.map { it.afterId } == listOf(report.id)) { docs.toString() }
    }

    @Test
    fun `Compare opens asking for the other run rather than showing a delta against nothing`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("compare-verdict").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-empty").assertIsDisplayed()
    }

    /** The state the feature is built around: it names the difference instead of subtracting. */
    @Test
    fun `two runs that measured different exchanges are refused by name`() {
        val order = LoadFixtures.burstReport(unmatched = 0)
        val rfq =
            LoadFixtures
                .burstReport(unmatched = 0)
                .let { it.copy(id = "${it.id}-rfq", match = LoadMatch(131, 131, "S"), template = it.template.copy(msgType = "R")) }
        viewModel.loadRecordStore.write(order)
        viewModel.loadRecordStore.write(rfq)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(order.id, rfq.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("NOT COMPARABLE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-why").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-match tags").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-answered").assertDoesNotExist()
    }

    @Test
    fun `two runs of the same exchange are subtracted`() {
        val before = LoadFixtures.burstReport(unmatched = 4)
        val after = LoadFixtures.burstReport(unmatched = 0).let { it.copy(id = "${it.id}-after") }
        viewModel.loadRecordStore.write(before)
        viewModel.loadRecordStore.write(after)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(after.id, before.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("COMPARED").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-unanswered").assertIsDisplayed()
        composeTestRule.onNodeWithText("cleared").assertIsDisplayed()
    }

    /** It refuses rather than substituting: a different template of the same name is the worse outcome. */
    @Test
    fun `Run this plan again refuses when the profile the plan names is gone`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(report.id), Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("compare-rerun").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare-rerun-refusal").assertIsDisplayed()
        composeTestRule.onNodeWithText("no longer exists", substring = true).assertExists()
    }
}
