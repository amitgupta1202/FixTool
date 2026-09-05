package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * **The rail's door to a load run, and Recent's row for one.** The menu item sits under fan-out with the
 * same lane count and stays visible and disabled when no profile can supply lanes. A finished load run is
 * a Recent row marked ⚡ that opens the document over its record.
 */
class LoadRunRailTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-load-rail", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `Load run sits in the Run menu, disabled with its count when nothing can supply lanes`() {
        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-run-load").assertIsDisplayed().assertIsNotEnabled().assertTextContains("Load run…  (0)")
    }

    @Test
    fun `a finished load run is a Recent row that opens its document`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("rail-recent-${report.id}").assertIsDisplayed().assertTextContains("⚡ ${report.label}  (4,000/4,000)", substring = true)
        composeTestRule.onNodeWithTag("rail-recent-${report.id}").performClick()
        composeTestRule.waitForIdle()

        val docs = viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>()
        assertEquals(listOf(report.id), docs.map { it.loadId })
    }
}
