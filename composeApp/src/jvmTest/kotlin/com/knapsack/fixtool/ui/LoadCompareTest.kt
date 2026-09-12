package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * **Compare's own tab, and the state that makes it trustworthy.** Both runs come off disk, so nothing is
 * rerun and a record the CLI wrote overnight is as good a partner as one fired here.
 */
class LoadCompareTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-compare", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private fun set(id: String, unmatchedInPhaseTwo: Int, phases: Int = 3) =
        LoadRecord(
            id = id,
            label = "RFQ round trip",
            startedAt = 1_000,
            finishedAt = 64_000,
            phases =
                (1..phases).map { n ->
                    LoadFixtures
                        .burstReport(unmatched = if (n == 2) unmatchedInPhaseTwo else 0)
                        .copy(id = id, label = "Phase $n", match = LoadMatch(11, 11, "8"))
                },
            set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
        )

    /**
     * Phases pair by position, one chip each, and the pane draws the pair that is clicked. Two one-phase
     * records draw today's Compare exactly, with no rail at all.
     */
    @Test
    fun `a set against a set is one chip per phase pair, and no counterpart when one has more`() {
        viewModel.stageLoadRecord(set("yesterday", unmatchedInPhaseTwo = 4))
        viewModel.stageLoadRecord(set("today", unmatchedInPhaseTwo = 0, phases = 2))

        composeTestRule.setContent {
            LoadCompareDocument(
                viewModel,
                ScenarioDoc.LoadCompare(afterId = "yesterday", beforeId = "today"),
                Modifier.fillMaxSize(),
            )
        }

        composeTestRule.onNodeWithTag("compare-verdict").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-pair-rail").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-pair-1").assertIsDisplayed()
        // Phase 2 moved: 0 unanswered against 4, which is the one delta a chip has room for.
        composeTestRule.onNodeWithTag("compare-pair-2").assertIsDisplayed()
        // Phase 3 exists in one record and not the other.
        composeTestRule.onNodeWithTag("compare-pair-3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("This phase has no counterpart.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `two one-phase records draw no rail, which is today's Compare exactly`() {
        viewModel.stageLoadRecord(LoadFixtures.burstReport(unmatched = 0).copy(id = "before", startedAt = 1_000))
        viewModel.stageLoadRecord(LoadFixtures.burstReport(unmatched = 4).copy(id = "after", startedAt = 2_000))

        composeTestRule.setContent {
            LoadCompareDocument(
                viewModel,
                ScenarioDoc.LoadCompare(afterId = "after", beforeId = "before"),
                Modifier.fillMaxSize(),
            )
        }

        composeTestRule.onNodeWithTag("compare-pair-rail").assertDoesNotExist()
        composeTestRule.onNodeWithText("What changed", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the document header opens Compare over this run`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadRunDocument(viewModel, ScenarioDoc.LoadRunView(report.id), Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("load-compare").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val docs = viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadCompare>()
        assert(docs.map { it.afterId } == listOf(report.id)) { docs.toString() }
    }

    /**
     * **A run that finishes while Compare is open joins its picker, with nothing reopened.**
     *
     * The list was `remember(doc.id) { loadRecordStore.listRecords() }` — whatever was on disk the moment
     * the tab opened. So the run you fired *because* the comparison asked for one could not be compared
     * against until the tab had been closed and opened again, and a workspace opened behind it left the
     * previous box's runs in the menu. The records are the ViewModel's state now, and the open menu follows
     * them: this asserts against a dropdown that is already showing, so nothing but the data has moved.
     */
    @Test
    fun `a run that finishes while Compare is open joins its picker`() {
        viewModel.stageLoadRecord(LoadFixtures.burstReport(unmatched = 4).copy(id = "the-one-open", startedAt = 2_000))

        composeTestRule.setContent {
            LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare("the-one-open"), Modifier.fillMaxSize())
        }
        composeTestRule.onNodeWithTag("compare-pick-other").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("no other run on disk").assertIsDisplayed()

        viewModel.stageLoadRecord(LoadFixtures.burstReport(unmatched = 0).copy(id = "fired-since", startedAt = 3_000))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare-other-fired-since").assertIsDisplayed()
        composeTestRule.onNodeWithText("no other run on disk").assertDoesNotExist()
    }

    @Test
    fun `Compare opens asking for the other run rather than showing a delta against nothing`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(report.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("compare-verdict").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-empty").assertIsDisplayed()
    }

    /** The state the feature is built around: it names the difference instead of subtracting. */
    @Test
    fun `two runs that measured different exchanges are refused by name`() {
        val order = LoadFixtures.burstReport(unmatched = 0)
        val rfq =
            LoadFixtures
                .burstReport(unmatched = 0)
                .let { it.copy(id = "${it.id}-rfq", match = LoadMatch(131, 131, "S"), template = it.template.copy(msgType = "R")) }
        viewModel.stageLoadRecord(order)
        viewModel.stageLoadRecord(rfq)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(order.id, rfq.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("NOT COMPARABLE").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-why").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-match tags").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-answered").assertDoesNotExist()
    }

    @Test
    fun `two runs of the same exchange are subtracted`() {
        val before = LoadFixtures.burstReport(unmatched = 4)
        val after = LoadFixtures.burstReport(unmatched = 0).let { it.copy(id = "${it.id}-after") }
        viewModel.stageLoadRecord(before)
        viewModel.stageLoadRecord(after)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(after.id, before.id), Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("COMPARED").assertIsDisplayed()
        composeTestRule.onNodeWithTag("compare-row-unanswered").assertIsDisplayed()
        composeTestRule.onNodeWithText("cleared").assertIsDisplayed()
    }

    /**
     * **The badge judges the pairs, not the last phase of each.** It read `before.only` against
     * `after.only`, so a set whose *last* phases measured the same exchange was called COMPARED however
     * badly the earlier pairs disagreed, and the chip beside them said "not comparable" under a badge that
     * said the opposite.
     */
    @Test
    fun `a set whose first pair measured a different exchange is not comparable`() {
        val yesterday = set("yesterday", unmatchedInPhaseTwo = 0, phases = 2)
        // Phase 1 alone measured an RFQ round trip: 131 on a 35=S rather than 11 on a 35=8.
        val today =
            set("today", unmatchedInPhaseTwo = 0, phases = 2).let { record ->
                record.copy(
                    phases =
                        record.phases.mapIndexed { i, phase ->
                            if (i == 0) {
                                phase.copy(match = LoadMatch(131, 131, "S"), template = phase.template.copy(msgType = "R"))
                            } else {
                                phase
                            }
                        },
                )
            }
        viewModel.stageLoadRecord(yesterday)
        viewModel.stageLoadRecord(today)

        composeTestRule.setContent {
            LoadCompareDocument(
                viewModel,
                ScenarioDoc.LoadCompare(afterId = "yesterday", beforeId = "today"),
                Modifier.fillMaxSize(),
            )
        }

        composeTestRule.onNodeWithTag("compare-verdict").assertTextContains("NOT COMPARABLE")
    }

    /** Every pair comparable, and the badge counts them rather than saying COMPARED about one. */
    @Test
    fun `a set whose every pair is comparable says how many pairs it compared`() {
        viewModel.stageLoadRecord(set("yesterday", unmatchedInPhaseTwo = 4))
        viewModel.stageLoadRecord(set("today", unmatchedInPhaseTwo = 0, phases = 2))

        composeTestRule.setContent {
            LoadCompareDocument(
                viewModel,
                ScenarioDoc.LoadCompare(afterId = "yesterday", beforeId = "today"),
                Modifier.fillMaxSize(),
            )
        }

        composeTestRule.onNodeWithTag("compare-verdict").assertTextContains("COMPARED · 2 PHASE PAIRS")
    }

    /**
     * **A set reruns as a set.** "Run this set again" replanned `after.only`, which is one phase of it, so
     * clicking it on a three-phase regression fired a third of the run. It goes back through the saved set
     * by name, and refuses in a sentence when the record came from no file.
     */
    @Test
    fun `Run this set again refuses a set that came from no saved file`() {
        viewModel.stageLoadRecord(set("after", unmatchedInPhaseTwo = 4).copy(set = null))
        viewModel.stageLoadRecord(set("before", unmatchedInPhaseTwo = 0).copy(set = null))

        composeTestRule.setContent {
            LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare("after", "before"), Modifier.fillMaxSize())
        }
        composeTestRule.onNodeWithTag("compare-rerun").assertTextContains("Run this set again")
        composeTestRule.onNodeWithTag("compare-rerun").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare-rerun-refusal").assertTextContains("no file to run again", substring = true)
    }

    /** The set had a file, so the refusal is the set's own and not a replan of one phase. */
    @Test
    fun `Run this set again goes to the saved set by name`() {
        viewModel.stageLoadRecord(set("after", unmatchedInPhaseTwo = 4))
        viewModel.stageLoadRecord(set("before", unmatchedInPhaseTwo = 0))

        composeTestRule.setContent {
            LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare("after", "before"), Modifier.fillMaxSize())
        }
        composeTestRule.onNodeWithTag("compare-rerun").performClick()
        composeTestRule.waitForIdle()

        // Nothing is saved under that name here, which is what the set store says rather than the template
        // resolver: proof the click went through the saved set and not through replanLoad.
        composeTestRule
            .onNodeWithTag("compare-rerun-refusal")
            .assertTextContains("No load set 'rfq-round-trip' is saved", substring = true)
    }

    /** It refuses rather than substituting: a different template of the same name is the worse outcome. */
    @Test
    fun `Run this plan again refuses when the profile the plan names is gone`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.stageLoadRecord(report)

        composeTestRule.setContent { LoadCompareDocument(viewModel, ScenarioDoc.LoadCompare(report.id), Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("compare-rerun").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("compare-rerun-refusal").assertIsDisplayed()
        composeTestRule.onNodeWithText("no longer exists", substring = true).assertExists()
    }
}
