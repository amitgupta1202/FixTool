package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.RunSetStats
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.service.load.StampMatcher
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

    /**
     * Put back in [tearDown], because one test here opens a workspace and that moves the global. Captured
     * rather than replaced: the tests that never open one keep resolving their stores through
     * `testSettingsDir`, which only wins while the installation is still the installation.
     */
    private lateinit var previousPaths: WorkspacePaths

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-doc", "").apply {
                delete()
                mkdirs()
            }
        previousPaths = WorkspacePaths.current
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        WorkspacePaths.use(previousPaths)
        testDir.deleteRecursively()
    }

    /**
     * **A document left open across a workspace switch stops drawing the previous workspace's run.**
     *
     * The record was read inside `remember(doc.loadId, live)`, and neither key moves when the stores are
     * re-pointed at another directory — so the tab went on showing a run that is not in the box you are
     * now looking at, with its figures, its verdict and its "Run this plan again" all live. The record is
     * the ViewModel's state now, and this workspace has no loads directory at all, so the document says so.
     */
    @Test
    fun `a document left open across a workspace switch stops drawing the previous workspace's run`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("UNANSWERED  4 of 4,000")

        viewModel.openWorkspace(File(testDir, "workspaces/somewhere-else").apply { mkdirs() }).getOrThrow()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("load-verdict").assertTextContains("NO RECORD")
        composeTestRule.onNodeWithText("This load run is no longer on disk.").assertIsDisplayed()
    }

    @Test
    fun `a finished report renders its counts, its unanswered ids and its verdict`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)

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

    /**
     * **Every figure carries its meaning, where the figure is.**
     *
     * The document had no tooltips at all, so `peak outstanding` and `strays` were defined nowhere in the
     * product and a reader of a finished report had to go and find the guide. The definition is in the
     * semantics as well as the hover bubble, because a Compose tooltip exists only while the pointer is
     * over it — which is the only reason this can be asserted at all.
     */
    @Test
    fun `each figure carries what it means, on its label`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule
            .onNodeWithTag("load-duplicates-label", useUnmergedTree = true)
            .assertContentDescriptionContains("had already been matched", substring = true)
        composeTestRule
            .onNodeWithTag("load-unmatched-label", useUnmergedTree = true)
            .assertContentDescriptionContains("anything above zero fails the run", substring = true)
        composeTestRule
            .onNodeWithTag("load-peak-label", useUnmergedTree = true)
            .assertContentDescriptionContains("at any one moment", substring = true)
    }

    /**
     * **The cost of explaining the figures does not grow with the run.**
     *
     * This is the whole performance argument, and it is a property rather than a promise: definitions go
     * on labels and column headers, never on cells. A lane table is one header row and up to fifty rows
     * of six, so tooltips on the cells would be three hundred hover targets on a fifty-lane run and six
     * on a two-lane one. Rendering the same report at two lanes and at fifty must produce the *same*
     * number of explained nodes, or somebody has put one on a cell.
     */
    @Test
    fun `a fifty-lane report explains no more nodes than a two-lane one`() {
        fun explainedNodes(lanes: Int): Int {
            val base = LoadFixtures.burstReport(unmatched = 4)
            val report =
                base.copy(
                    id = "explained-$lanes",
                    perLane =
                        (1..lanes).map {
                            LoadReport.LaneCounts(
                                it,
                                matched = 80,
                                unanswered = 0,
                                duplicates = 1,
                                p50Us = 12_000,
                                p95Us = 99_000,
                            )
                        },
                )
            viewModel.stageLoadRecord(report)
            composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }
            composeTestRule.waitForIdle()
            return composeTestRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }

        val few = explainedNodes(2)
        assertTrue(few > 0, "the figures should be explained at all")

        // A second composition in one test needs its own rule, so the count is taken from a fresh one.
        val many = explainedNodes(50)
        assertEquals(few, many, "explaining the report must not scale with the lane count")
    }

    /** Three separate judgements, as pills, and the exit code on the header's meta line. */
    @Test
    fun `the three judgements are named in the footer`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("completeness · unanswered").assertExists()
        composeTestRule.onNodeWithText("rate · n/a, burst").assertExists()
        composeTestRule.onNodeWithText("tool · clean").assertExists()
    }

    /**
     * **A reactive phase is not a burst, and the document said it was in two places.**
     *
     * The pill read the literal "n/a, burst" for anything the verdict could not score, and the tool
     * strip fell through its burst arm into "the run did not finish, so its schedule was never judged",
     * which of a finished reactive phase is simply false. The comment three lines above that arm records
     * fixing the same lie once already, for rate runs interrupted mid-flight.
     */
    @Test
    fun `a reactive phase is drawn as reactive rather than as a burst`() {
        val report = LoadFixtures.burstReport(unmatched = 0).copy(shape = LoadShape.Triggered())
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("rate · n/a, reactive").assertExists()
        composeTestRule.onNodeWithTag("load-tool-rate").assertTextContains("reactive, so no schedule to lag", substring = true)
    }

    /** The two lead figures carry the run's rank. The other five are a strip, reported and not shouted. */
    @Test
    fun `the two counts that decide the verdict are the figures, and the five that do not are a strip`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)

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
        composeTestRule.onNodeWithTag("load-unmatched-label", useUnmergedTree = true).assertTextContains("outstanding")
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
     * **A wire longer than the row must not take the row with it.** With no weight on the wire column it
     * took every pixel the three fixed columns left over, so " open ›" was measured at about nothing and
     * wrapped a character to a line, five lines of row for each unanswered request.
     */
    @Test
    fun `a wide pane keeps an overlong wire to one line, with the open link beside it`() {
        val report = LoadFixtures.burstReport(unmatched = 4)
        viewModel.stageLoadRecord(report)
        viewModel.loadRecordStore.writeEvidence(
            report.id,
            LoadReport.Evidence.forPhase(1),
            unmatched =
                report.unmatched.map {
                    StampMatcher.Unmatched(it.id, it.lane, sentMicros = it.sentAt * 1_000, wire = longWire(it.id))
                },
            specimens = emptyList(),
        )

        composeTestRule.setContent {
            LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.width(900.dp).fillMaxHeight())
        }

        val row =
            composeTestRule
                .onNodeWithTag("load-unmatched-0")
                .performScrollTo()
                .getUnclippedBoundsInRoot()
                .height
        assertTrue(row < 24.dp, "the row is one line of wire, not two or the five the wrapped link made it ($row)")
        composeTestRule.onAllNodesWithText(" open ›")[0].assertIsDisplayed().assertWidthIsAtLeast(20.dp)
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

    /**
     * **The chain, on the phase it ends at, with a line per hop.**
     *
     * The round trip block above it is this phase asking the venue. This is every phase of the chain in
     * turn, and it is what a three-phase RFQ set could not say at all before: how long a quote request
     * took from asking to being filled. The two are never added, so they are two blocks.
     */
    @Test
    fun `the phase a chain ends at draws the whole journey, and each hop under it`() {
        val chain =
            LoadReport.Chain(
                legs =
                    listOf(
                        LoadReport.Leg(1, "Ask for a quote", handover = null, roundTrip = dist(3_000), answered = 196),
                        LoadReport.Leg(2, "Quote it", handover = dist(400), roundTrip = dist(2_000), answered = 196),
                    ),
                endToEnd = dist(5_400),
                complete = 196,
                requested = 200,
            )
        val chained = LoadFixtures.burstReport(unmatched = 0).copy(chain = chain)

        composeTestRule.setContent { LoadReportView(chained, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-chain").assertExists()
        composeTestRule.onNodeWithText("196 of 200 whole", substring = true).assertExists()
        composeTestRule.onNodeWithTag("load-chain-leg-1").assertTextContains("answered 196 · p50 3.0ms", substring = true)
        composeTestRule.onNodeWithTag("load-chain-leg-2").assertTextContains("waited 400µs", substring = true)
    }

    /** A run that is nobody's chain draws no chain block, which is every single run and every paced set. */
    @Test
    fun `a run that ends no chain draws none`() {
        composeTestRule.setContent {
            LoadReportView(LoadFixtures.burstReport(unmatched = 0), emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize())
        }

        composeTestRule.onNodeWithTag("load-chain").assertDoesNotExist()
    }

    /** A distribution shaped like a real one, in microseconds, for a block measured where it happens. */
    private fun dist(us: Long) =
        RunSetStats.Distribution(p50 = us, p95 = us * 2, max = us * 3, samples = 196, min = us / 2, p99 = us * 2, mean = us)

    /**
     * **A cap is drawn as a ceiling, with no floor and nothing red under it.**
     *
     * Fed in as a schedule the cap would have been a line every starved second falls under, and a phase
     * that was waiting on its trigger would have read as a sustained shortfall in red. So the ceiling has
     * its own key and the floor stays absent, which is what keeps the picture and the CAPPED verdict
     * saying the same thing.
     */
    @Test
    fun `a capped reactive phase draws its ceiling, and no second under it is red`() {
        val base = LoadFixtures.burstReport(unmatched = 0)
        val capped =
            base.copy(
                shape = LoadShape.Triggered(cap = 500),
                issue = base.issue.copy(lastSendAt = base.issue.firstSendAt!! + 600_000),
                // Half of them well under the cap, which under a schedule would be three hundred red bars.
                perSecond = (0 until 600).map { LoadReport.Second(it, 500, if (it % 2 == 0) 120 else 498, 4_000) },
            )

        composeTestRule.setContent { LoadReportView(capped, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-chart-seconds").assertExists()
        composeTestRule.onNodeWithText("the ceiling nothing was released above", substring = true).assertExists()
        composeTestRule.onNodeWithText("the pacer's own floor", substring = true).assertDoesNotExist()
    }

    /** A rate run that ended before its schedule was judged is not a burst, whatever its rate report says. */
    @Test
    fun `a rate run with no rate report says why, instead of calling itself a burst`() {
        val base = LoadFixtures.burstReport(unmatched = 0, status = LoadStatus.STOPPED)
        val interrupted = base.copy(shape = LoadShape.Rate(500, 600_000), rate = null)

        composeTestRule.setContent { LoadReportView(interrupted, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-tool-rate").assertTextContains("never judged", substring = true)
    }

    /**
     * **Fifty lanes must not push the verdict off the bottom.** All fifty rows inline did exactly that,
     * and printed the 50 × 6 matrix the design note says answers no question anyone asks.
     */
    @Test
    fun `a fifty-lane run shows the lanes worth looking at, and asks before printing the rest`() {
        val base = LoadFixtures.burstReport(unmatched = 4)
        val fifty =
            base.copy(
                lanes = 50,
                perLane = (1..50).map { LoadReport.LaneCounts(it, 79, if (it == 37) 1 else 0, 0, 3_162, if (it == 37) 19_952 else 7_943) },
            )

        composeTestRule.setContent { LoadReportView(fifty, emptyList(), File("loads/x"), onStop = {}, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("load-lane-count").assertTextContains("8 of 50 lanes", substring = true)
        composeTestRule.onNodeWithTag("load-lane-37").assertExists("the lane with something unanswered is always shown")
        composeTestRule.onNodeWithTag("load-lane-50").assertDoesNotExist()
        // The verdict is still on the screen, which is the point of the cap.
        composeTestRule.onNodeWithTag("load-judgements").assertExists()

        composeTestRule.onNodeWithTag("load-lane-all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("load-lane-50").assertExists("and the whole matrix is one click away")
    }

    @Test
    fun `a stored report that claims to be running reads as stopped, because nothing is running it`() {
        val abandoned = LoadFixtures.burstReport(unmatched = 590, status = LoadStatus.RUNNING).copy(finishedAt = null)
        viewModel.stageLoadRecord(abandoned)

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

    /**
     * A single order, 176 characters of it, as the matcher hands it to the record: SOH separated, which is
     * what the store turns into the pipes the document reads back.
     */
    private fun longWire(id: String): String =
        (
            "8=FIX.4.4|9=241|35=D|34=1187|49=LOADGEN|56=VENUE|52=20260905-14:02:11.443|11=$id|" +
                "55=EUR/USD|54=1|38=1000000|40=2|44=1.09385|59=0|21=1|60=20260905-14:02:11.443|10=071|"
        ).replace('|', '\u0001')
}
