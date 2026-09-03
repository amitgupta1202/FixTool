package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workspace items' home in the toolbar: a trailing section in Quick Connect ▾.
 *
 * The cases that matter are the fresh install (no profiles, so the menu must still show and must
 * offer the example), an open workspace (Close, and the name on a header row), and that Recent asks
 * inside the same popup rather than firing on its own.
 */
class ToolbarWorkspaceMenuTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tempWorkspace(name: String): File {
        val parent = Files.createTempDirectory("toolbar-workspace").toFile()
        return File(parent, name).apply { mkdirs() }
    }

    @Test
    fun `quick connect shows on a fresh install and offers a new workspace`() {
        var asked = false
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                connectionProfiles = emptyList(),
                onQuickConnect = { _, _ -> },
                onNewWorkspace = { asked = true },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithText("No saved profiles").assertExists()
        rule.onNodeWithTag("workspace-close").assertDoesNotExist()

        rule.onNodeWithTag("workspace-new").performClick()
        assertTrue(asked)
    }

    /**
     * The point of the whole naming: an example is one of the things Open can open, not a second kind
     * of workspace with a verb of its own.
     */
    @Test
    fun `open offers a folder to browse to and the examples we ship`() {
        var browsed = false
        var opened: String? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                onNewWorkspace = { },
                onOpenWorkspace = { browsed = true },
                examples = listOf("fx-venue" to "FX Venue"),
                onOpenExample = { opened = it },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("workspace-open").performClick()
        assertTrue(!browsed, "Open asks what to open; it must not go straight to a file dialog")

        rule.onNodeWithText("FX Venue").assertExists()
        rule.onNodeWithTag("workspace-example-fx-venue").performClick()
        assertEquals("fx-venue", opened)
    }

    @Test
    fun `browse is the other half of open, and back returns to the profile list`() {
        var browsed = false
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                onNewWorkspace = { },
                onOpenWorkspace = { browsed = true },
                examples = listOf("fx-venue" to "FX Venue"),
                onOpenExample = { },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("workspace-open").performClick()
        rule.onNodeWithTag("workspace-open-back").performClick()
        rule.onNodeWithText("No saved profiles").assertExists()

        rule.onNodeWithTag("workspace-open").performClick()
        rule.onNodeWithTag("workspace-browse").performClick()
        assertTrue(browsed)
    }

    @Test
    fun `an open workspace is named and can be closed`() {
        var closed = false
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                workspaceOpen = true,
                workspaceName = "fx-venue",
                onNewWorkspace = { },
                onCloseWorkspace = { closed = true },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithText("Workspace: fx-venue").assertExists()
        rule.onNodeWithTag("workspace-close").performClick()
        assertTrue(closed)
    }

    @Test
    fun `recent asks inside the same popup, and back returns to the profile list`() {
        val first = tempWorkspace("alpha")
        var opened: File? = null
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                onNewWorkspace = { },
                recentWorkspaces = listOf(first),
                onOpenRecentWorkspace = { opened = it },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("workspace-recent").performClick()
        assertEquals(null, opened, "the Recent item asks for a workspace; it must not open one on its own")
        rule.onNodeWithText("No saved profiles").assertDoesNotExist()

        rule.onNodeWithTag("workspace-recent-back").performClick()
        rule.onNodeWithText("No saved profiles").assertExists()

        rule.onNodeWithTag("workspace-recent").performClick()
        rule.onNodeWithTag("workspace-recent-alpha").performClick()
        assertEquals(first, opened)
    }

    @Test
    fun `no recent workspaces means no submenu to walk into`() {
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
                onNewWorkspace = { },
            )
        }

        rule.onNodeWithTag("quick-connect").performClick()
        rule.onNodeWithTag("workspace-recent").assertDoesNotExist()
    }

    @Test
    fun `without the workspace callbacks an empty profile list still hides quick connect`() {
        rule.setContent {
            Toolbar(
                globalSessionViewMode = FixMessageSession.ViewMode.PARSED,
                viewMode = ViewMode.SPLIT_HORIZONTAL,
                onViewModeChange = { },
                onQuickConnect = { _, _ -> },
            )
        }

        rule.onNodeWithTag("quick-connect").assertDoesNotExist()
    }
}
