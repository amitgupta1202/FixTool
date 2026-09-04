package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.service.ExampleWorkspaces
import com.knapsack.fixtool.service.WorkspacePaths
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reset, from the app's side: only offered for a copy, and it opens what it laid down. */
class ResetOpenExampleTest {
    private lateinit var home: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("reset-home").toFile()
        WorkspacePaths.use(home.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = home.absolutePath)
    }

    @After
    fun cleanup() {
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    @Test
    fun `no reset is offered on Default`() {
        assertNull(viewModel.openWorkspaceExample())
        assertTrue(viewModel.resetOpenExample().isFailure)
    }

    @Test
    fun `no reset is offered for a workspace you made yourself`() {
        val mine = ExampleWorkspaces.createEmpty("Mine", ExampleWorkspaces.defaultLocation()).getOrThrow()
        viewModel.openWorkspace(mine).getOrThrow()

        assertNull(viewModel.openWorkspaceExample())
        assertTrue(viewModel.resetOpenExample().isFailure)
    }

    @Test
    fun `an opened example knows what it is`() {
        viewModel.openExample(ExampleWorkspaces.FX_VENUE).getOrThrow()

        assertEquals(ExampleWorkspaces.FX_VENUE, viewModel.openWorkspaceExample()?.id)
    }

    @Test
    fun `reset gives back the shipped profiles and stays in the same folder`() {
        val workspace = viewModel.openExample(ExampleWorkspaces.FX_VENUE).getOrThrow()
        viewModel.deleteConnectionProfile(viewModel.connectionProfiles.first().id)
        assertEquals(2, viewModel.connectionProfiles.size, "the fixture must start from a broken copy")

        val reset = viewModel.resetOpenExample().getOrThrow()

        assertEquals(workspace, reset)
        assertEquals(3, viewModel.connectionProfiles.size)
        assertEquals(workspace, viewModel.openWorkspace, "reset should leave the fresh copy open")
    }

    @Test
    fun `the broken copy is still on disk after a reset`() {
        val workspace = viewModel.openExample(ExampleWorkspaces.FX_VENUE).getOrThrow()
        File(workspace, "scenarios/mine.json").writeText("{}")

        viewModel.resetOpenExample().getOrThrow()

        val leftBehind =
            ExampleWorkspaces
                .defaultLocation()
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("fx-venue-before-reset-") }
        assertEquals(1, leftBehind.size, "exactly one folder should have been left behind")
        assertTrue(File(leftBehind.single(), "scenarios/mine.json").isFile)
    }
}
