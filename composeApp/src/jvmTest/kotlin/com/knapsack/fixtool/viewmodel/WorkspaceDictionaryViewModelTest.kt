package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.ChosenBy
import com.knapsack.fixtool.service.WorkspaceDictionary
import com.knapsack.fixtool.service.WorkspacePaths
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * **The open workspace's dictionary is the one loaded**, from the moment it opens until it closes.
 *
 * Settings name FIX 4.2 throughout; the workspace names FIX 4.4. Each test reads which one the app actually parses with,
 * because that — not what either file says — is what a scenario is judged in.
 */
class WorkspaceDictionaryViewModelTest {
    private lateinit var home: File
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var previous: WorkspacePaths

    @Before
    fun setup() {
        previous = WorkspacePaths.current
        home = Files.createTempDirectory("dictionary-home").toFile()
        WorkspacePaths.use(home.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = home.absolutePath)
        viewModel.saveAppSettings(viewModel.appSettings.copy(useBundledDictionary = true, defaultFixVersion = FixVersion.FIX_4_2))
    }

    @After
    fun cleanup() {
        WorkspacePaths.use(previous)
        home.deleteRecursively()
    }

    private fun loaded(): FixVersion = assertIs<FixDictionaryAdapter>(viewModel.dictionary).fixVersion

    private fun namingFix44() =
        File(home, "workspaces/names-its-own").apply {
            mkdirs()
            WorkspaceDictionary.write(this, WorkspaceDictionary(fixVersion = FixVersion.FIX_4_4))
        }

    @Test
    fun `opening a workspace that names its dictionary loads that one, and says who chose it`() {
        assertEquals(FixVersion.FIX_4_2, loaded())

        viewModel.openWorkspace(namingFix44()).getOrThrow()

        assertEquals(FixVersion.FIX_4_4, loaded())
        assertIs<ChosenBy.Workspace>(viewModel.dictionaryChoice?.chosenBy)
        // The New workspace dialog's note is about a workspace that names nothing, which gets Settings' 4.2.
        assertEquals("Sessions will speak FIX.4.2, from the dictionary in Settings -> Protocol.", viewModel.wireVersionNote())
        assertEquals(
            "\"names-its-own\" names its own dictionary, the bundled FIX 4.4, in workspace.json, and that is what is loaded " +
                "while it is open. What you choose here is for workspaces that name none.",
            viewModel.workspaceDictionaryNote(),
        )
    }

    /** Settings answer for the workspaces that name none; saving them must not reach past one that does. */
    @Test
    fun `saving Settings while it is open keeps the workspace's dictionary, and closing it brings Settings' back`() {
        viewModel.openWorkspace(namingFix44()).getOrThrow()

        viewModel.saveAppSettings(viewModel.appSettings.copy(defaultFixVersion = FixVersion.FIX_4_3))
        assertEquals(FixVersion.FIX_4_4, loaded(), "a Settings save replaced the workspace's dictionary")

        viewModel.closeWorkspace()
        assertEquals(FixVersion.FIX_4_3, loaded())
        assertIs<ChosenBy.Settings>(viewModel.dictionaryChoice?.chosenBy)
        assertEquals("", viewModel.workspaceDictionaryNote(), "Settings chose, so there is nothing to explain")
    }

    @Test
    fun `a workspace that names none is read in Settings' dictionary, as it always was`() {
        viewModel.openWorkspace(namingFix44()).getOrThrow()

        viewModel.openWorkspace(File(home, "workspaces/names-none").apply { mkdirs() }).getOrThrow()

        assertEquals(FixVersion.FIX_4_2, loaded())
    }
}
