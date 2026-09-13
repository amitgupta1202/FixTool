package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The Latency dock has its header in every state, including the one a fresh window opens it in.**
 *
 * With no session up it drew "No active session" alone: no title, no name, and no Hide, so the only way to put
 * it away was its stripe tab. Found on screen, opening the dock over the control surface's new `latency` name.
 */
class LatencyDockTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-latency-dock", "").apply {
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
    fun `with no session the dock still has its title and its Hide, and the Hide hides it`() {
        viewModel.show(ToolWindow.LATENCY)
        rule.setContent { AppLatencyPanel(viewModel) }

        rule.onNodeWithTag("tool-window-latency-header").assertIsDisplayed()
        rule.onNodeWithTag("latency-empty").assertTextEquals("No active session")
        assertTrue(viewModel.showLatencyPanel.value)

        rule.onNodeWithTag("tool-window-latency-hide").performClick()
        assertFalse(viewModel.showLatencyPanel.value)
    }
}
