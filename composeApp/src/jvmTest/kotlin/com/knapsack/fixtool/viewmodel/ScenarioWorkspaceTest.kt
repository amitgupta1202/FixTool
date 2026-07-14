package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.ui.ScenarioDoc
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **One unsaved draft per scenario, however many documents are looking at it.**
 *
 * The draft used to belong to the editor document. It cannot: the reconcile diff is a document too, and it
 * edits an expectation *of the same scenario*. Two drafts of one scenario and they diverge — save from the
 * diff and the editor's next Save writes the old expectation straight back over the repair. That is the
 * two-editing-surfaces defect from the assertion model, re-created between two tabs, and the workspace is
 * what makes it unrepresentable.
 */
class ScenarioWorkspaceTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-workspace", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val scenario =
        Scenario(
            id = "sc-1",
            name = "rfq flow",
            steps =
                listOf(
                    ScenarioStep.Send("35=D|11=X|"),
                    ScenarioStep.Expect(
                        expectation = Expectation(fields = listOf(FieldExpectation(150, Matcher.Exact("F"))), messageType = "8"),
                    ),
                ),
        )

    private fun saved(): Scenario {
        assertTrue(viewModel.scenarioService.save(scenario))
        return viewModel.scenarioService.load(scenario.id)!!
    }

    @Test
    fun `a scenario opens clean, and an edit dirties the scenario rather than the tab`() {
        viewModel.openScenarioEditor(saved())
        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty, "opened, untouched, and therefore clean")

        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed")) }

        assertTrue(viewModel.scenarioDraft("sc-1")!!.dirty)
        assertEquals("renamed", viewModel.scenarioDraft("sc-1")!!.draft.name)
    }

    /**
     * A second door onto a scenario that is already open must not re-seed it. The copy that arrives at that
     * door came off *disk*; the one in the workspace may be carrying an hour of unsaved repairs.
     */
    @Test
    fun `a second door onto an open scenario does not re-seed its draft`() {
        val onDisk = saved()
        viewModel.openScenarioEditor(onDisk)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed, unsaved")) }

        viewModel.openScenarioEditor(onDisk, focusStep = 1)

        assertEquals("renamed, unsaved", viewModel.scenarioDraft("sc-1")!!.draft.name)
        assertTrue(viewModel.scenarioDraft("sc-1")!!.dirty)
    }

    /**
     * A draft with nothing looking at it is unreachable and unsaveable. Leaving it behind would mean the next
     * time the author opened the scenario they would silently be handed edits they had already walked away
     * from — and would have no way of knowing they were not what is on disk.
     */
    @Test
    fun `closing the last document of a scenario drops its draft`() {
        val onDisk = saved()
        viewModel.openScenarioEditor(onDisk)
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "abandoned")) }

        viewModel.closeDocument(ScenarioDoc.editorId("sc-1"))
        assertNull(viewModel.scenarioDraft("sc-1"), "no document, no draft")

        viewModel.openScenarioEditor(onDisk)
        assertEquals("rfq flow", viewModel.scenarioDraft("sc-1")!!.draft.name, "re-opened from disk, not from the ghost")
        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty)
    }

    /** Save writes the workspace's draft and re-seeds it from what actually reached the disk. */
    @Test
    fun `save writes the draft and leaves the scenario clean`() {
        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "renamed")) }

        assertTrue(viewModel.saveScenarioDocument(viewModel.scenarioDraft("sc-1")!!.draft))

        assertFalse(viewModel.scenarioDraft("sc-1")!!.dirty, "saved, so there is nothing left to lose")
        assertEquals("renamed", viewModel.scenarioService.load("sc-1")!!.name)
        assertTrue(viewModel.openDocuments.value.isNotEmpty(), "and the tab stays open on it")
    }

    /** A view of a file that is gone is a trap: deleting the scenario closes everything looking at it. */
    @Test
    fun `deleting a scenario closes its documents and drops its draft`() {
        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "doomed")) }

        viewModel.deleteScenario("sc-1")

        assertTrue(viewModel.openDocuments.value.isEmpty())
        assertNull(viewModel.scenarioDraft("sc-1"))
        assertNull(viewModel.activeDocumentId.value)
    }

    /** A clean tab closes without a word; a dirty one stops to ask, because closing it would discard the draft. */
    @Test
    fun `the close prompt is about the draft, not about the tab`() {
        viewModel.openScenarioEditor(saved())
        val id = ScenarioDoc.editorId("sc-1")

        viewModel.requestCloseDocument(id)
        assertTrue(viewModel.openDocuments.value.isEmpty(), "clean: it just closes")

        viewModel.openScenarioEditor(saved())
        viewModel.updateScenarioDraft("sc-1") { it.copy(draft = it.draft.copy(name = "unsaved")) }
        viewModel.requestCloseDocument(id)

        assertEquals(id, viewModel.confirmingCloseId.value, "dirty, and the last one looking at it: it asks")
        assertTrue(viewModel.openDocuments.value.isNotEmpty(), "and it is still open until the author answers")
    }
}
