package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.RecordedMessage
import com.knapsack.fixtool.service.RunRecord
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.SavedRunEntry
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlin.test.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The rail's half of a run set**: the menu that makes one, and the report that reads one back.
 *
 * The claim worth a UI test rather than a unit test is the *reachability* — the same one
 * `ScenarioRunReportTest` exists for. A set that can only be started over HTTP is a feature the person
 * looking at the traffic cannot use, and an entry whose report cannot be brought back on screen is
 * evidence written for nobody.
 */
class RunSetRailTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-run-set-rail", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /**
     * Nothing starred and nothing filtered: the items stay **visible and disabled**, because an author
     * cannot tell "there is nothing to run" from "this feature does not exist" if they are withheld.
     */
    @Test
    fun `the run menu offers every way in, and disables the ones with nothing behind them`() {
        viewModel.scenarioService.save(scenario("book-a-trade"))
        viewModel.runSetStore.save(SavedRunSet("nightly", listOf(SavedRunEntry("book-a-trade", repeat = 3))))
        viewModel.refreshScenarios()

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()

        // A saved set carries its own size, so "run nightly" is not a leap of faith.
        composeTestRule.onNodeWithTag("rail-run-set-nightly").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("rail-run-favourites").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rail-run-filtered").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rail-run-repeat").assertIsEnabled()
        composeTestRule.onNodeWithTag("rail-save-set").assertIsEnabled()
    }

    /**
     * Saving what is on screen is what turns a click into something CI can name. Driven at the ViewModel
     * rather than through the dialog: a `Dialog` is its own window composition, and the test rule cannot
     * observe two of those on one thread — the dialog itself is three fields over this call.
     */
    @Test
    fun `save as set writes a file CI could run by name`() {
        val scenarios = listOf(scenario("book-a-trade"), scenario("cancel-replace"))
        scenarios.forEach { viewModel.scenarioService.save(it) }

        assertTrue(viewModel.saveRunSet("nightly", scenarios))

        val saved = assertNotNull(viewModel.runSetStore.load("nightly"), "the set should be on disk")
        assertEquals(
            listOf("book-a-trade", "cancel-replace"),
            saved.entries.map { it.scenario }.sorted(),
            "by name, because a name is what survives a scenario being re-saved",
        )
        // And it plans back into runnable entries, which is the only reason to write it.
        assertEquals(
            2,
            saved
                .plan(scenarios, now = 0L)
                .set.entries.size,
        )
    }

    /**
     * **Focusing an entry is what publishes it.** A set runs its entries without publishing anything —
     * twenty would re-aim an open reconcile window twenty times — so the click is the deliberate act that
     * says "this is the run I am looking at now", and it reads the verdict back off the record.
     */
    @Test
    fun `an entry of a finished set can be brought back on screen from its record`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set = writeFinishedSet(scenario)

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.waitForIdle()
        // "Recent ▸" reaches a set the app has since been restarted out of — the records are on disk
        // precisely so the answer does not depend on the process that produced it.
        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail-recent-${set.id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-set-report").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-set-entry-2").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val published = assertNotNull(viewModel.scenarioResult.value, "focusing an entry publishes its verdict")
        assertTrue(!published.passed, "entry 2 is the failed one, and its report is the one now on screen")
        assertEquals("book-a-trade", published.scenario)

        // And the click opened the set as a document, on that entry — the rail's report is a summary, and
        // the question a failed entry raises is answered by the bytes.
        val doc =
            assertNotNull(
                viewModel.openDocuments.value
                    .filterIsInstance<ScenarioDoc.RunSetView>()
                    .singleOrNull(),
                "the set should be open as a document",
            )
        assertEquals(set.id, doc.setId)
        assertEquals(2, doc.entry)
    }

    /**
     * **The rows say they open.** An author ran a set, saw only the last entry's traffic in the grid, and
     * asked where the other entries' logs had gone. The rows were plain text with a click on them: no
     * hover, no chevron, no hint. Now the report says so, and every entry with a record wears the chevron.
     */
    @Test
    fun `the set report says its entries open, and marks each one that has a record`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set = writeFinishedSet(scenario)

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rail-recent-${set.id}").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-set-hint").assertIsDisplayed()
        // The chevron is a child of a clickable row, so it lives in the unmerged tree.
        composeTestRule.onNodeWithTag("run-set-entry-1-open", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-set-entry-2-open", useUnmergedTree = true).assertIsDisplayed()
    }

    /** An entry that has not landed has nothing to open, and must not pretend otherwise. */
    @Test
    fun `an entry without a record wears no chevron`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set =
            RunSets.repeat(scenario, times = 2, now = System.currentTimeMillis()).let { planned ->
                planned.copy(
                    status = RunSetStatus.RUNNING,
                    entries =
                        listOf(
                            planned.entries[0].copy(state = RunState.PASSED, durationMs = 12, record = "01-book-a-trade.json"),
                            planned.entries[1].copy(state = RunState.PENDING),
                        ),
                )
            }
        viewModel.runRecordStore.begin(set)
        viewModel.runRecordStore.writeSet(set)
        viewModel.focusRunSet(set.id)

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-set-hint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-set-entry-1-open", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-set-entry-2-open", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * The document reads the record back: the entries down one side, and for the focused one its verdict
     * and **its own message grid**, from bytes that are nowhere else by the time anybody looks.
     */
    @Test
    fun `the run set document shows an entry's verdict and the messages from its record`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set = writeFinishedSet(scenario, withMessages = true)

        composeTestRule.setContent {
            RunSetDocument(viewModel, ScenarioDoc.RunSetView(set.id, entry = 1), modifier = Modifier.fillMaxSize())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-set-document").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-entry-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-entry-2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-entry-verdict").assertIsDisplayed()
        // The header names the entry and its file, because a record's grid that looked like the live one
        // would be worse than none.
        composeTestRule.onNodeWithTag("run-entry-header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("run-entry-grid").assertIsDisplayed()
    }

    /**
     * **Clicking a red row says why it is red.** The record's grid was rendered without a selection handler,
     * so a click expanded the row and nothing more: the detail panel never opened, and the reason a step
     * failed stayed in the file. Now the click selects the row for the detail panel with the entry's own
     * per-tag verdicts behind it, from the one parse the focus produced, so the panel's identity lookup hits.
     */
    @Test
    fun `clicking a row of the record's grid selects it with the entry's per-tag verdicts behind it`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set = writeFinishedSet(scenario, withMessages = true, judged = true)

        composeTestRule.setContent {
            RunSetDocument(viewModel, ScenarioDoc.RunSetView(set.id, entry = 2), modifier = Modifier.fillMaxSize())
        }
        composeTestRule.waitForIdle()

        // The document asked for the focus itself, and its grid shows that parse rather than one of its own.
        val focused = assertNotNull(viewModel.focusedRunEntry.value, "opening the document on an entry focuses it")
        assertEquals(2, focused.entry)
        val judged = focused.parsed.messages[1]

        composeTestRule.onNodeWithTag("message-row-${judged.timestamp}").performClick()
        // The row also answers a double click, so a single one is delivered only once the double-tap window
        // has closed. Move the test clock past it.
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()

        assertSame(judged, viewModel.selectedMessage.value, "the clicked row is the selected message, by identity")
        val tags = assertNotNull(viewModel.assertionResults[judged]?.tags, "the detail panel's lookup finds the entry's verdict")
        assertEquals(listOf(151), tags.map { it.tag })
    }

    /** The reason, readable before any click: the FAIL step line names the tag, expected beside actual. */
    @Test
    fun `the FAIL step line names the failed tag with expected and actual`() {
        val scenario = scenario("book-a-trade")
        viewModel.scenarioService.save(scenario)
        viewModel.refreshScenarios()
        val set = writeFinishedSet(scenario, withMessages = true, judged = true)

        composeTestRule.setContent {
            RunSetDocument(viewModel, ScenarioDoc.RunSetView(set.id, entry = 2), modifier = Modifier.fillMaxSize())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("run-entry-step-0").assertTextContains("151", substring = true)
        composeTestRule.onNodeWithTag("run-entry-step-0").assertTextContains("expected 999, got 1000000", substring = true)
    }

    // ----------------------------------------------------------------- fixtures

    /** A set as it looks the morning after: on disk, one entry green, one red, the app restarted since. */
    private fun writeFinishedSet(
        scenario: Scenario,
        withMessages: Boolean = false,
        /** The failed entry's step judged message 1 on tag 151, so a record has a verdict to click on. */
        judged: Boolean = false,
    ): com.knapsack.fixtool.model.scenario.RunSet {
        val set =
            RunSets.repeat(scenario, times = 2, now = System.currentTimeMillis()).copy(
                status = RunSetStatus.FAILED,
                startedAt = System.currentTimeMillis() - 5_000,
                finishedAt = System.currentTimeMillis(),
                entries =
                    listOf(
                        RunEntry(
                            scenario.id,
                            scenario.name,
                            iteration = 1,
                            state = RunState.PASSED,
                            durationMs = 12,
                            record = "01-book-a-trade.json",
                        ),
                        RunEntry(
                            scenario.id,
                            scenario.name,
                            iteration = 2,
                            state = RunState.FAILED,
                            durationMs = 15,
                            record = "02-book-a-trade.json",
                        ),
                    ),
            )
        viewModel.runRecordStore.begin(set)
        viewModel.runRecordStore.writeSet(set)
        listOf(true, false).forEachIndexed { i, passed ->
            viewModel.runRecordStore.write(
                RunRecord(
                    setId = set.id,
                    entry = i + 1,
                    iteration = i + 1,
                    scenarioId = scenario.id,
                    scenarioName = scenario.name,
                    startedAt = System.currentTimeMillis(),
                    durationMs = 12,
                    result =
                        ScenarioResult(
                            scenario = scenario.name,
                            passed = passed,
                            steps =
                                listOf(
                                    StepResult(
                                        0,
                                        "expect",
                                        "steps",
                                        passed = passed,
                                        detail = "messageType=8",
                                        tags =
                                            if (judged && !passed) {
                                                listOf(
                                                    TagResult(
                                                        tag = 151,
                                                        matcher = "exact 999",
                                                        expected = "999",
                                                        actual = "1000000",
                                                        passed = false,
                                                        index = 0,
                                                        status = TagStatus.VALUE,
                                                    ),
                                                )
                                            } else {
                                                emptyList()
                                            },
                                        stepId = "st-expect",
                                    ),
                                ),
                            durationMs = 12,
                        ),
                    scenario = scenario,
                    messages =
                        if (!withMessages) {
                            emptyList()
                        } else {
                            listOf(
                                // A second apart, so the two rows do not share a timestamp (and a grid row tag).
                                RecordedMessage(
                                    0,
                                    "CLI",
                                    incoming = false,
                                    atMicros = 1_000_000,
                                    raw = "8=FIX.4.4|35=D|11=ORD-1|55=EUR/USD|10=001|",
                                ),
                                RecordedMessage(
                                    1,
                                    "CLI",
                                    incoming = true,
                                    atMicros = 2_000_000,
                                    raw = "8=FIX.4.4|35=8|11=ORD-1|39=2|151=1000000|10=002|",
                                ),
                            )
                        },
                    bound = if (judged) mapOf("st-expect" to 1) else emptyMap(),
                ),
            )
        }
        return set
    }

    /**
     * **⏹ on a set stops that set, and nothing else.** The line used to read the global "something is
     * running" flag and stop with no id, so a fan-out on one profile and a bare run on another shared one
     * stop button — and a finished set reopened from Recent runs wore a ⏹ that halted whatever else
     * happened to be running.
     *
     * Two runs on two disconnected sessions, each parked in a Wait for a logon that never comes: the set's
     * stop lands on the set, and the bare run beside it is still holding its session afterwards.
     */
    @Test
    fun `the set line's stop stops its own set and leaves the run beside it alone`() {
        viewModel.createSessionForTest("S")
        viewModel.createSessionForTest("T")
        val onS = Scenario(id = "on-s", name = "on S", steps = listOf(ScenarioStep.Wait(session = "S", state = "LOGGED_ON", timeoutMs = 20_000)))
        val onT = Scenario(id = "on-t", name = "on T", steps = listOf(ScenarioStep.Wait(session = "T", state = "LOGGED_ON", timeoutMs = 20_000)))
        viewModel.scenarioService.save(onS)
        viewModel.scenarioService.save(onT)
        viewModel.refreshScenarios()
        val set = assertNotNull(viewModel.startRunSet(RunSets.repeat(onS, times = 1, now = System.currentTimeMillis())))
        viewModel.runScenario(onT)
        composeTestRule.waitUntil(5_000) { viewModel.isRunSetRunning(set.id) && "T" in viewModel.busySessions.value }

        try {
            composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
            composeTestRule.onNodeWithTag("stop-run-set").assertIsDisplayed().performClick()

            composeTestRule.waitUntil(5_000) { !viewModel.isRunSetRunning(set.id) }
            assertTrue("T" in viewModel.busySessions.value, "the bare run on T is still going")
            assertTrue("S" !in viewModel.busySessions.value, "the set released its session")
        } finally {
            viewModel.requestScenarioStop()
            composeTestRule.waitUntil(5_000) { !viewModel.scenarioRunning.value }
        }
    }

    private fun scenario(name: String) =
        Scenario(id = "sc-$name", name = name, steps = listOf(ScenarioStep.Send("35=D|", session = "s")))
}
