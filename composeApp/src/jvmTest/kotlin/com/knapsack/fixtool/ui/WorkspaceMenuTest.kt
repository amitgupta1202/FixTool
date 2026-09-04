package com.knapsack.fixtool.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workspace switcher: what is open, and the four ways to change it.
 *
 * These items used to be a trailing section in Quick Connect ▾, which is a session control — pick a
 * profile, it connects. Opening a workspace changes which profiles exist at all, so it now lives
 * where every editor puts the project: top left, showing the name, click to change it.
 */
class WorkspaceMenuTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tempWorkspace(name: String): File {
        val parent = Files.createTempDirectory("workspace-menu").toFile()
        return File(parent, name).apply { mkdirs() }
    }

    @Test
    fun `the open workspace is named without opening anything`() {
        rule.setContent { WorkspaceMenu(WorkspaceMenuState(name = "Default", onNew = { })) }

        // The name sits inside the clickable row, so its semantics merge into the parent.
        rule.onNodeWithTag("workspace-menu-name", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Default", substring = true).assertExists()
    }

    @Test
    fun `new asks the caller, and does not create anything itself`() {
        var asked = false
        rule.setContent { WorkspaceMenu(WorkspaceMenuState(name = "Default", onNew = { asked = true })) }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-new").performClick()
        assertTrue(asked)
    }

    /**
     * The point of the naming: an example is one of the things Open can open, not a second kind of
     * workspace with a verb of its own.
     */
    @Test
    fun `open offers a folder to browse to and the examples we ship`() {
        var browsed = false
        var opened: String? = null
        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(
                    name = "Default",
                    examples = listOf(ExampleEntry("fx-venue", "FX Venue", "opens ~/.fixtool/workspaces/fx-venue")),
                    onNew = { },
                    onBrowse = { browsed = true },
                    onOpenExample = { opened = it },
                ),
            )
        }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-open").performClick()
        assertTrue(!browsed, "Open asks what to open; it must not go straight to a file dialog")

        rule.onNodeWithText("FX Venue").assertExists()
        rule.onNodeWithTag("workspace-example-fx-venue").performClick()
        assertEquals("fx-venue", opened)
    }

    @Test
    fun `browse is the other half of open, and back returns to the root`() {
        var browsed = false
        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(
                    name = "Default",
                    examples = listOf(ExampleEntry("fx-venue", "FX Venue", "opens ~/.fixtool/workspaces/fx-venue")),
                    onNew = { },
                    onBrowse = { browsed = true },
                    onOpenExample = { },
                ),
            )
        }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-open").performClick()
        rule.onNodeWithTag("workspace-open-back").performClick()
        rule.onNodeWithTag("workspace-new").assertExists()

        rule.onNodeWithTag("workspace-open").performClick()
        rule.onNodeWithTag("workspace-browse").performClick()
        assertTrue(browsed)
    }

    @Test
    fun `recent asks inside the same popup rather than opening one on its own`() {
        val alpha = tempWorkspace("alpha")
        var opened: File? = null
        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(
                    name = "Default",
                    recents = listOf(alpha),
                    onNew = { },
                    onBrowse = { },
                    onOpenRecent = { opened = it },
                ),
            )
        }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-recent").performClick()
        assertNull(opened, "Recent asks which one; it must not open one on its own")

        rule.onNodeWithTag("workspace-recent-alpha").performClick()
        assertEquals(alpha, opened)
    }

    @Test
    fun `no recents means no submenu to walk into`() {
        rule.setContent { WorkspaceMenu(WorkspaceMenuState(name = "Default", onNew = { }, onBrowse = { })) }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-recent").assertDoesNotExist()
    }

    /** Close is what RETURNS you to Default, so on Default it is not an action that is unavailable. */
    @Test
    fun `close is absent on Default and present on any other workspace`() {
        var closed = false
        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(name = "Default", isDefault = true, onNew = { }, onClose = { closed = true }),
            )
        }
        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-close").assertDoesNotExist()
        rule.onNodeWithTag("workspace-menu").performClick()

        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(name = "fx-venue", isDefault = false, onNew = { }, onClose = { closed = true }),
            )
        }
        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-close").performClick()
        assertTrue(closed)
    }

    /**
     * Two workspaces side by side in the same folder read as one entry twice when the subtitle is the
     * parent, because the parent is the same string for both. Found by opening the example twice.
     */
    @Test
    fun `siblings in the same folder are told apart by their own paths`() {
        val parent = Files.createTempDirectory("workspace-siblings").toFile()
        val first = File(parent, "fx-venue").apply { mkdirs() }
        val second = File(parent, "fx-venue-2").apply { mkdirs() }
        rule.setContent {
            WorkspaceMenu(
                WorkspaceMenuState(
                    name = "Default",
                    recents = listOf(second, first),
                    onNew = { },
                    onBrowse = { },
                    onOpenRecent = { },
                ),
            )
        }

        rule.onNodeWithTag("workspace-menu").performClick()
        rule.onNodeWithTag("workspace-recent").performClick()
        // Exact, not substring: fx-venue is a substring of fx-venue-2, which is the whole reason
        // the parent directory was a useless subtitle in the first place.
        rule.onNodeWithText(shortPath(first), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(shortPath(second), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a path under home is shown against a tilde, because the home part is the same on every line`() {
        val home = File(System.getProperty("user.home"), ".fixtool/workspaces/fx-venue")
        assertEquals("~/.fixtool/workspaces/fx-venue", shortPath(home))
        assertEquals("/srv/elsewhere", shortPath(File("/srv/elsewhere")))
    }
}
