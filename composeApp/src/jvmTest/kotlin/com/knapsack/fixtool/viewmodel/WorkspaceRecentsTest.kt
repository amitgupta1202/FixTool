package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.service.WorkspacePaths
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Default never appears in Recent, however you arrive at it.
 *
 * Found in use: browsing to `~/.fixtool` recorded it as a workspace called `.fixtool`, so Recent
 * offered an entry for the one place Close already returns to. Opening Default is not opening a
 * workspace, it is closing whatever was open.
 */
class WorkspaceRecentsTest {
    private lateinit var home: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("recents-home").toFile()
        WorkspacePaths.use(home.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = home.absolutePath)
    }

    @After
    fun cleanup() {
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    private fun workspace(name: String) = File(home, "workspaces/$name").apply { mkdirs() }

    @Test
    fun `opening a workspace records it`() {
        val alpha = workspace("alpha")
        viewModel.openWorkspace(alpha).getOrThrow()

        assertEquals(listOf(alpha), viewModel.recentWorkspaces)
    }

    @Test
    fun `opening the installation's own directory closes instead, and records nothing`() {
        val alpha = workspace("alpha")
        viewModel.openWorkspace(alpha).getOrThrow()

        viewModel.openWorkspace(home).getOrThrow()

        assertTrue(viewModel.openWorkspaceIsHome, "browsing to the home directory should land on Default")
        assertEquals("Default", viewModel.openWorkspaceName)
        assertFalse(viewModel.recentWorkspaces.any { it.absolutePath == home.absolutePath })
        assertEquals(listOf(alpha), viewModel.recentWorkspaces, "the real workspace should still be offered")
    }

    @Test
    fun `two workspaces in the same folder are two entries`() {
        val first = workspace("fx-venue")
        val second = workspace("fx-venue-2")
        viewModel.openWorkspace(first).getOrThrow()
        viewModel.openWorkspace(second).getOrThrow()

        assertEquals(listOf(second, first), viewModel.recentWorkspaces, "newest first, and both kept")
    }

    @Test
    fun `opening the same workspace twice keeps one entry`() {
        val alpha = workspace("alpha")
        viewModel.openWorkspace(alpha).getOrThrow()
        viewModel.openWorkspace(alpha).getOrThrow()

        assertEquals(listOf(alpha), viewModel.recentWorkspaces)
    }
}
