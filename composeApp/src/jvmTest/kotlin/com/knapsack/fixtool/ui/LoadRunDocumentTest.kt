package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * **The report as a screen, from the record.** What a reopened load run shows is the five counts, the
 * verdict with its three judgements, and the unanswered requests by id, the same things the summary block
 * prints, because they are read from the same record.
 */
class LoadRunDocumentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-load-doc", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `a finished report renders its counts, its unanswered ids and its verdict`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-run-document").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-issued").assertTextContains("4,000", substring = true)
        composeTestRule.onNodeWithTag("load-matched").assertTextContains("3,996", substring = true)
        composeTestRule.onNodeWithTag("load-unmatched").assertTextContains("4", substring = true)
        composeTestRule.onNodeWithTag("load-duplicates").assertTextContains("12", substring = true)
        composeTestRule.onNodeWithTag("load-state").assertTextContains("unmatched 4")
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("UNMATCHED  4 of 4,000")
        composeTestRule.onNodeWithText("ORD-b7f2-1187").assertExists()
        composeTestRule.onNodeWithText("exit 1 · completeness unmatched · rate not applicable · tool clean").assertExists()
        composeTestRule.onNodeWithTag("load-stop").assertDoesNotExist()
    }

    /**
     * A running report reaches the document through the live flow, never the store: a stored report that
     * says running with no run behind it heals to stopped on read, which is the right answer for a record
     * and the wrong fixture for this. So the drawing is driven directly.
     */
    @Test
    fun `a running report shows Stop and calls it pending rather than unmatched`() {
        val running = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.RUNNING).copy(finishedAt = null)
        var stopped = false

        composeTestRule.setContent { LoadReportView(running, emptyList(), "loads/x", onStop = { stopped = true }, Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-stop").assertIsDisplayed().performClick()
        assertTrue(stopped)
        composeTestRule.onNodeWithTag("load-unmatched-label").assertTextContains("pending")
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("RUNNING")
    }

    @Test
    fun `a stored report that claims to be running reads as stopped, because nothing is running it`() {
        val abandoned = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.RUNNING).copy(finishedAt = null)
        viewModel.loadRecordStore.write(abandoned)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(abandoned.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-stop").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-state").assertTextContains("stopped")
    }

    @Test
    fun `a pruned record says so instead of drawing nothing`() {
        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView("gone"), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("no longer on disk", substring = true).assertExists()
    }
}
