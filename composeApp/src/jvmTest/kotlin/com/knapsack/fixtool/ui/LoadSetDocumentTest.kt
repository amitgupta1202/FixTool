package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The four states the set document has to draw**: failed at a phase, running one, passed, and stopped by
 * hand. What is checked is the badge that names the phase, the rail's marks, and that a skipped phase says
 * why rather than showing a report of zeroes.
 */
class LoadSetDocumentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-set-doc", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun phase(label: String, unmatched: Int = 0, status: LoadStatus = LoadStatus.DONE) =
        LoadFixtures.burstReport(unmatched = unmatched, status = status).copy(label = label, lanes = 5)

    private fun skipped(label: String, note: String): LoadReport {
        val plan =
            LoadPlan(
                id = "set-1",
                label = label,
                template = LoadTemplate(label, listOf(35 to "R")),
                profileId = "p",
                profileName = "LOADGEN",
                shape = LoadShape.Burst(2_000),
                match = LoadMatch(117, 117, "AI"),
                indexFrom = 2_001,
            )
        return LoadReport
            .stub(
                plan,
                LoadStatus.SKIPPED,
                lanes = 5,
                template = LoadReport.TemplateInfo(label, "AJ", listOf(117), listOf(35), emptyList()),
                startedAt = 0,
                note = note,
            ).copy(label = label)
    }

    private fun record(phases: List<LoadReport>, id: String = "set-1") =
        LoadRecord(
            id = id,
            label = "RFQ round trip",
            startedAt = 0,
            finishedAt = if (phases.any { it.status.isLive }) null else 63_100,
            phases = phases,
            set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            seed = mapOf("run" to "b7f2", "desk" to "LDN"),
        )

    /**
     * The document over its own open tab, the way the pane hands it one, so a click that pins a phase
     * re-renders the way it does in the app.
     */
    private fun show(record: LoadRecord) {
        viewModel.loadRecordStore.write(record)
        viewModel.openLoadRun(record.id)
        composeTestRule.setContent {
            val docs by viewModel.openDocuments.collectAsState()
            docs.filterIsInstance<ScenarioDoc.LoadRunView>().firstOrNull()?.let {
                LoadRunDocument(viewModel, it, Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun `a failed set names the phase, draws its rail, and says why the rest were skipped`() {
        val phases =
            listOf(
                phase("Ask for a quote"),
                phase("Hit the first 2,000", unmatched = 4),
                skipped("Pass the other 2,000", "phase 2 did not pass and the set stops on failure"),
            )

        show(record(phases))

        composeTestRule.onNodeWithTag("load-set-document").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-verdict").assertTextContains("FAILED · PHASE 2")
        composeTestRule.onNodeWithTag("load-set-meta").assertTextContains("run=b7f2 · desk=LDN", substring = true)
        composeTestRule.onNodeWithTag("load-set-meta").assertTextContains("1 passed, 1 failed, 1 skipped", substring = true)
        // The timeline's one sentence names the largest span, which is where tuning starts.
        composeTestRule.onNodeWithTag("load-set-timeline-note").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-set-phase-1").assertTextContains("Ask for a quote", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-3").assertTextContains("skipped", substring = true)
        // The live phase is the focused one, and here there is none, so the last is shown: the skipped one.
        composeTestRule.onNodeWithText("not run", substring = true).assertIsDisplayed()
    }

    @Test
    fun `clicking a phase pins it, without opening a second tab`() {
        val phases = listOf(phase("Ask for a quote"), phase("Hit the first 2,000", unmatched = 4))
        show(record(phases))

        composeTestRule.onNodeWithTag("load-set-phase-1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-set-phase-title").assertTextContains("1 · Ask for a quote")
        val docs = viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>()
        assertEquals(listOf(1), docs.map { it.phase }, "one tab, with the phase pinned on it")
    }

    /**
     * A running set reaches the document through the live flow, never the store: a stored record that says
     * running with nothing running it heals to stopped on read, which is the right answer for a record and
     * the wrong fixture for this. So the drawing is driven directly.
     */
    @Test
    fun `a running set names the phase it is on and offers Stop set`() {
        val live =
            record(
                listOf(
                    phase("Ask for a quote"),
                    phase("Hit the first 2,000", status = LoadStatus.RUNNING).copy(stage = LoadStage.ISSUING),
                    skipped("Pass the other 2,000", "").copy(status = LoadStatus.PENDING, note = null),
                ),
                id = "set-live",
            )
        var stopped = false

        composeTestRule.setContent {
            LoadSetView(
                record = live,
                focused = 2,
                onFocus = {},
                phaseWire = emptyList(),
                records = File("loads/set-live"),
                onStop = { stopped = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composeTestRule.onNodeWithTag("load-set-verdict").assertTextContains("ISSUING · PHASE 2 OF 3")
        composeTestRule.onNodeWithTag("load-set-stop").assertIsDisplayed().performClick()
        assertTrue(stopped, "Stop set is the one button, and its consequence is in the skipped rows")
        composeTestRule.onNodeWithTag("load-set-phase-3").assertTextContains("queued", substring = true)
        composeTestRule.onNodeWithTag("load-set-phase-title").assertTextContains("2 · Hit the first 2,000")
    }

    @Test
    fun `a passing set says so, with no phase named`() {
        show(record(listOf(phase("Ask for a quote"), phase("Hit them"), phase("Pass the rest"))))

        composeTestRule.onNodeWithTag("load-set-verdict").assertTextContains("PASSED")
        composeTestRule.onNodeWithTag("load-set-meta").assertTextContains("3 passed", substring = true)
    }

    @Test
    fun `a set stopped by hand names the phase and is not judged`() {
        val phases =
            listOf(
                phase("Ask for a quote"),
                phase("Hit the first 2,000", status = LoadStatus.STOPPED),
                skipped("Pass the other 2,000", "the set was stopped"),
            )

        show(record(phases, id = "set-stopped"))

        composeTestRule.onNodeWithTag("load-set-verdict").assertTextContains("STOPPED · PHASE 2")
        composeTestRule.onNodeWithTag("load-set-meta").assertTextContains("1 passed, 1 stopped, 1 skipped", substring = true)
    }

    /** One phase draws today's document, unchanged, which is the whole point of a run being a set of one. */
    @Test
    fun `a one-phase record still draws the single-run document`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-run-document").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("COMPLETE", substring = true)
    }
}
