package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.ScenarioDoc
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The view model's half of a load run: the doors in, the record store, and the refusal when there are no lanes. */
class LoadRunViewModelTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-load-vm", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val template = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${messageIndex}"))

    private fun plan(profileId: String) =
        LoadPlan(
            id = "x",
            label = "NOS ×10 on LOADGEN",
            template = template,
            profileId = profileId,
            profileName = "LOADGEN",
            shape = LoadShape.Burst(10),
            match = LoadMatch(11),
        )

    @Test
    fun `a profile with no lane logged on refuses to start, and nothing is recorded`() {
        val profile = FixConnectionProfile(id = "lg", name = "LOADGEN", config = FixConnectionConfig(senderCompID = "LG{n}", targetCompID = "V", sessionCount = 3))
        viewModel.saveConnectionProfile(profile)

        assertNull(viewModel.startLoadRun(plan("lg")))
        assertEquals(emptyList(), viewModel.loadRecordStore.list())
        assertTrue(viewModel.openDocuments.value.none { it is ScenarioDoc.LoadRunView })
    }

    @Test
    fun `Recent's load row opens the document over the record`() {
        val report = LoadFixtures.burstReport()
        viewModel.loadRecordStore.write(report)

        viewModel.openLoadRun(report.id)

        val doc = assertNotNull(viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>().singleOrNull())
        assertEquals(report.id, doc.loadId)
        assertEquals(listOf(report.id), viewModel.loadRecordStore.list().map { it.id })
    }

    @Test
    fun `the editor's fields become the dialog's template, and dismissing clears it`() {
        viewModel.requestLoadRun(listOf(FixField("35", "D"), FixField("11", "ORD-\${messageIndex}"), FixField("", "junk")))

        val t = assertNotNull(viewModel.loadDialogTemplate.value)
        assertEquals("D", t.msgType)
        assertEquals(listOf(35 to "D", 11 to "ORD-\${messageIndex}"), t.fields)
        assertEquals(LoadMatch(11), t.inferMatch())

        viewModel.dismissLoadDialog()
        assertNull(viewModel.loadDialogTemplate.value)
    }

    @Test
    fun `a stop aimed at a run that is not running is harmless`() {
        viewModel.stopLoadRun("nothing")
        assertTrue(!viewModel.isLoadRunning("nothing"))
    }
}
