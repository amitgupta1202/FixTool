package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * **The Connection panel's form belongs to the workspace it was filled in.**
 *
 * The form is remembered state, and the profile it holds carries its own workspace's store and log paths,
 * because a config decodes them from whatever workspace is open at the time. Kept across a switch, Save
 * appended the previous workspace's profile to the new one's file, still pinned to the old folder's store,
 * and Connect dialled the old counterparty with its sequence numbers kept in the old folder.
 */
class ConnectionPanelWorkspaceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var home: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("panel-home").toFile()
        WorkspacePaths.use(home.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = home.absolutePath)
    }

    @After
    fun cleanup() {
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    private fun workspace(name: String) = File(home, "workspaces/$name").apply { mkdirs() }

    /** Built while its workspace is open, so its store and log paths are that workspace's. */
    private fun profile(id: String, name: String) =
        FixConnectionProfile(
            id = id,
            name = name,
            config = FixConnectionConfig(senderCompID = name, targetCompID = "V", host = "localhost", port = "9876"),
        )

    @Test
    fun `a switch empties the form, so Save cannot write the previous workspace's profile into the new one`() {
        val alpha = workspace("alpha")
        val beta = workspace("beta")
        // Beta has a profile of its own, so the panel draws its profile list there rather than an empty state.
        viewModel.openWorkspace(beta).getOrThrow()
        viewModel.saveConnectionProfile(profile("beta-venue", "BETA"))
        viewModel.openWorkspace(alpha).getOrThrow()
        viewModel.saveConnectionProfile(profile("alpha-venue", "ALPHA"))
        composeTestRule.setContent { AppConnectionPanel(viewModel) }
        composeTestRule.onNodeWithText("Select profile...").performClick()
        composeTestRule.onNodeWithText("ALPHA").performClick()
        composeTestRule.waitForIdle()

        viewModel.openWorkspace(beta).getOrThrow()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select profile...").assertExists("the form starts fresh in the new workspace")
        composeTestRule.onNodeWithContentDescription("Save profile").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            emptyList(),
            viewModel.connectionProfiles.filter { it.id == "alpha-venue" }.map { it.name },
            "alpha's profile must not have been saved into beta",
        )
        assertEquals(
            emptyList(),
            viewModel.connectionProfiles
                .map { it.config.fileStorePath }
                .filter { it.startsWith(alpha.absolutePath) },
            "and nothing in beta may keep its sequence numbers in alpha's store",
        )
    }
}
