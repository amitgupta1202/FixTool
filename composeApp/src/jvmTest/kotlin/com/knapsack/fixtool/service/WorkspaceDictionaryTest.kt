package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixVersion
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which dictionary a workspace is read in: its own, else its example's, else Settings'.
 *
 * The order is the whole feature. A workspace's scenarios are written in the wire order of the dictionary they were
 * captured under, so the one that knows is the workspace; Settings is only for a workspace that has not said.
 */
class WorkspaceDictionaryTest {
    private val theirs = AppSettings.default().copy(useBundledDictionary = false, defaultDataDictionary = "/venues/own-FIX44.xml")

    private fun workspace(): File = Files.createTempDirectory("workspace-dictionary").toFile()

    private fun declare(
        workspace: File,
        json: String,
    ) = File(workspace, WorkspaceDictionary.FILE).writeText(json)

    @Test
    fun `a workspace that names nothing is read in the dictionary Settings name`() {
        val choice = DictionaryChoice.resolve(theirs, workspace())

        assertEquals(DictionaryChoice.Files(File("/venues/own-FIX44.xml"), null, ChosenBy.Settings()), choice)
    }

    @Test
    fun `with no workspace at all, Settings keep the rule they always had - ticked or blank is bundled`() {
        val ticked = theirs.copy(useBundledDictionary = true, defaultFixVersion = FixVersion.FIX_5_0_SP2)
        val blank = AppSettings.default().copy(useBundledDictionary = false, defaultDataDictionary = "")

        assertEquals(DictionaryChoice.Bundled(FixVersion.FIX_5_0_SP2, ChosenBy.Settings()), DictionaryChoice.resolve(ticked, null))
        assertEquals(DictionaryChoice.Bundled(blank.defaultFixVersion, ChosenBy.Settings()), DictionaryChoice.resolve(blank, null))
    }

    @Test
    fun `a workspace that names a bundled version is read in it, whatever Settings name`() {
        val workspace = workspace()
        WorkspaceDictionary.write(workspace, WorkspaceDictionary(fixVersion = FixVersion.FIX_4_4))

        val choice = DictionaryChoice.resolve(theirs, workspace)

        assertEquals(DictionaryChoice.Bundled(FixVersion.FIX_4_4, ChosenBy.Workspace(File(workspace, WorkspaceDictionary.FILE))), choice)
        assertEquals("the bundled FIX 4.4", choice.name)
    }

    /** Relative to the workspace, so a workspace committed with its dictionary opens the same on every checkout. */
    @Test
    fun `a dictionary file a workspace names is found beside the workspace, and an absolute one where it says`() {
        val workspace = workspace()
        declare(workspace, """{"dictionary": {"path": "dictionaries/venue.xml", "transportPath": "/shared/FIXT11.xml"}}""")

        val choice = assertIs<DictionaryChoice.Files>(DictionaryChoice.resolve(AppSettings.default(), workspace))

        assertEquals(File(workspace, "dictionaries/venue.xml"), choice.data)
        assertEquals(File("/shared/FIXT11.xml"), choice.transport)
        assertEquals("venue.xml", choice.name)
    }

    /**
     * The machine the defect was found on: its fixed-income folder was laid down before examples named a dictionary,
     * and a folder that is there is never written over. The example it came from answers for it instead.
     */
    @Test
    fun `a copy of an example made before examples named a dictionary is read in the example's`() {
        val workspace = workspace()
        File(workspace, ExampleWorkspaces.ORIGIN_FILE).writeText(ExampleWorkspaces.FI_RFQ_VENUE + "\n")

        val choice = DictionaryChoice.resolve(theirs, workspace)

        assertEquals(DictionaryChoice.Bundled(FixVersion.FIX_4_4, ChosenBy.Example("Fixed Income RFQ Platform")), choice)
        assertTrue(!File(workspace, WorkspaceDictionary.FILE).exists(), "answering for an old copy wrote into it")
    }

    @Test
    fun `a workspace file that names no dictionary means Settings, even in a copy of an example`() {
        val workspace = workspace()
        File(workspace, ExampleWorkspaces.ORIGIN_FILE).writeText(ExampleWorkspaces.FX_VENUE + "\n")
        declare(workspace, "{}")

        assertEquals(DictionaryChoice.Files(File("/venues/own-FIX44.xml"), null, ChosenBy.Settings()), DictionaryChoice.resolve(theirs, workspace))
    }

    @Test
    fun `an unreadable workspace file falls back to Settings and says why`() {
        val workspace = workspace()
        declare(workspace, """{"dictionary": {"fixVersion": "FIX_9_9"}}""")

        val chosenBy = assertIs<ChosenBy.Settings>(DictionaryChoice.resolve(theirs, workspace).chosenBy)

        assertTrue(assertNotNull(chosenBy.because).contains("could not be read"), chosenBy.because)
    }

    @Test
    fun `a declaration naming both a version and a file is refused rather than read as either`() {
        val workspace = workspace()
        declare(workspace, """{"dictionary": {"fixVersion": "FIX_4_4", "path": "venue.xml"}}""")

        val choice = DictionaryChoice.resolve(theirs, workspace)

        assertIs<DictionaryChoice.Files>(choice)
        val because = assertNotNull(assertIs<ChosenBy.Settings>(choice.chosenBy).because)
        assertTrue("names both" in because, because)
    }

    @Test
    fun `what is written is what is read back`() {
        val workspace = workspace()
        val named = WorkspaceDictionary(path = "dictionaries/venue.xml")

        WorkspaceDictionary.write(workspace, named)

        assertEquals(named, WorkspaceDictionary.parse(File(workspace, WorkspaceDictionary.FILE).readText()).dictionary)
    }
}
