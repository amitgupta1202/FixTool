package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
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
 * **The report as a screen, from the record.** What a reopened load run shows is the two counts that
 * decide the verdict, the five that do not, the verdict itself and the unanswered requests by id — the
 * same things the summary block prints, because they are read from the same record.
 */
class LoadRunDocumentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-doc", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `a finished report renders its counts, its unanswered ids and its verdict`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-run-document").assertIsDisplayed()
        composeTestRule.onNodeWithTag("load-issued").assertTextContains("4,000", substring = true)
        composeTestRule.onNodeWithTag("load-matched").assertTextContains("3,996", substring = true)
        composeTestRule.onNodeWithTag("load-unmatched").assertTextContains("4", substring = true)
        composeTestRule.onNodeWithTag("load-duplicates").assertTextContains("12", substring = true)
        composeTestRule.onNodeWithTag("load-state").assertTextContains("unanswered 4")
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("UNANSWERED  4 of 4,000")
        composeTestRule.onNodeWithText("ORD-b7f2-1187").assertExists()
        composeTestRule.onNodeWithTag("load-stop").assertDoesNotExist()
    }

    /** Three separate judgements, as pills, and the exit code on the header's meta line. */
    @Test
    fun `the three judgements are named in the footer`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("completeness · unanswered").assertExists()
        composeTestRule.onNodeWithText("rate · n/a, burst").assertExists()
        composeTestRule.onNodeWithText("tool · clean").assertExists()
    }

    /** The two lead figures carry the run's rank. The other five are a strip, reported and not shouted. */
    @Test
    fun `the two counts that decide the verdict are the figures, and the five that do not are a strip`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        val figure =
            composeTestRule
                .onNodeWithTag("load-matched")
                .fetchSemanticsNode()
                .size.height
        val strip =
            composeTestRule
                .onNodeWithTag("load-duplicates")
                .fetchSemanticsNode()
                .size.height
        assertTrue(figure > strip * 2, "the figure is a rank above the strip, not the same weight ($figure vs $strip)")
    }

    /**
     * A running report reaches the document through the live flow, never the store: a stored report that
     * says running with no run behind it heals to stopped on read, which is the right answer for a record
     * and the wrong fixture for this. So the drawing is driven directly.
     */
    @Test
    fun `a running report shows Stop and calls what is outstanding outstanding, not unanswered`() {
        val running = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.RUNNING).copy(finishedAt = null)
        var stopped = false

        composeTestRule.setContent {
            LoadReportView(running, emptyList(), File("loads/x"), onStop = { stopped = true }, modifier = Modifier.fillMaxSize())
        }

        composeTestRule.onNodeWithTag("load-stop").assertIsDisplayed().performClick()
        assertTrue(stopped)
        composeTestRule.onNodeWithTag("load-unmatched-label").assertTextContains("outstanding")
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("ISSUING")
    }

    /**
     * **Stopped is reported and not judged.** Judging a fraction of a plan against that plan's thresholds
     * invents the one number this report exists to keep honest, so the three judgement names go and the
     * exit code leaves the header. It is still exit 1 headlessly: a build cannot pass on a run somebody
     * ended by hand.
     */
    @Test
    fun `a stopped run gets no judgements and no exit code on screen`() {
        val stopped = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.STOPPED)

        composeTestRule.setContent { LoadReportView(stopped, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("STOPPED", substring = true)
        composeTestRule.onNodeWithText("not judged · the run did not finish").assertExists()
        composeTestRule.onNodeWithText("completeness · unanswered").assertDoesNotExist()
        assertEquals(LoadReport.EXIT_FAILED, stopped.verdict.exitCode, "the record still fails a build")
    }

    /**
     * **The empty chart slot carries the diagnosis.** Replies are arriving and none carries the reply tag,
     * which the tool can prove from a count it has always kept and never surfaced.
     */
    @Test
    fun `a run that matched nothing spends its empty stat card on the reason`() {
        val base = LoadFixtures.burstReport(unmatched = 4_000)
        val nothing =
            base.copy(
                replies = base.replies.copy(matched = 0, unmatched = 4_000, strays = 4_000),
                roundTrip = null,
            )

        composeTestRule.setContent { LoadReportView(nothing, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-empty").assertExists()
        composeTestRule.onNodeWithText("none carried tag 11", substring = true).assertExists()
    }

    /** At 460px the leads drop a rank rather than overflowing, and the stat row goes to three columns. */
    @Test
    fun `a narrow pane drops the figures a rank instead of overflowing`() {
        val report = LoadFixtures.burstReport(unmatched = 4)

        composeTestRule.setContent {
            LoadReportView(report, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.width(430.dp))
        }

        composeTestRule.onNodeWithTag("load-overflow").assertExists()
        composeTestRule.onNodeWithTag("load-copy-json").assertDoesNotExist()
    }

    /**
     * Which chart you get follows the issue span, not the shape. A 4,000 burst leaves in 813ms and would
     * draw three bars that say nothing; the curve is drawn for every run, in the stat card's own slot.
     */
    @Test
    fun `a short burst gets the outstanding curve and no per-second panel`() {
        val report = LoadFixtures.burstReport(unmatched = 4)

        composeTestRule.setContent { LoadReportView(report, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-chart-outstanding").assertExists()
        composeTestRule.onNodeWithTag("load-chart-seconds").assertDoesNotExist()
    }

    /** A run that issued for minutes has a per-second story, whether it was a rate or a very long burst. */
    @Test
    fun `a run that issued for minutes gets the per-second panel, with the pacer's floor named`() {
        val base = LoadFixtures.burstReport(unmatched = 0, rate = LoadFixtures.shortfall)
        val long =
            base.copy(
                issue = base.issue.copy(lastSendAt = base.issue.firstSendAt!! + 600_000),
                perSecond = (0 until 600).map { LoadReport.Second(it, 500, if (it == 42) 318 else 498, 4_000) },
            )

        composeTestRule.setContent { LoadReportView(long, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-chart-seconds").assertExists()
        composeTestRule.onNodeWithText("the pacer's own floor", substring = true).assertExists()
    }

    /** A rate run that ended before its schedule was judged is not a burst, whatever its rate report says. */
    @Test
    fun `a rate run with no rate report says why, instead of calling itself a burst`() {
        val base = LoadFixtures.burstReport(unmatched = 0, status = LoadStatus.STOPPED)
        val interrupted = base.copy(shape = LoadShape.Rate(500, 600_000), rate = null)

        composeTestRule.setContent { LoadReportView(interrupted, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-tool-rate").assertTextContains("never judged", substring = true)
    }

    @Test
    fun `a stored report that claims to be running reads as stopped, because nothing is running it`() {
        val abandoned = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.RUNNING).copy(finishedAt = null)
        viewModel.loadRecordStore.write(abandoned)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(abandoned.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-stop").assertDoesNotExist()
        composeTestRule.onNodeWithTag("load-state").assertTextContains("stopped")
    }

    @Test
    fun `a pruned record says so instead of drawing nothing`() {
        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView("gone"), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("no longer on disk", substring = true).assertExists()
        composeTestRule.onNodeWithTag("load-reveal").assertExists()
    }
}
