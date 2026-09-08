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
import com.knapsack.fixtool.integration.TestFixServer
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
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
 * **The rail's door to a load run, and Recent's row for one.** The menu item sits under fan-out with the
 * same lane count and stays visible and disabled when no profile can supply lanes. A finished load run is
 * a Recent row marked ⚡ that opens the document over its record.
 */
class LoadRunRailTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-load-rail", "").apply {
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
    fun `Load run sits in the Run menu, disabled with its count when nothing can supply lanes`() {
        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("rail-run-load")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertTextContains("Load run…  (0)")
    }

    /**
     * **The count follows the sessions, not the moment the rail was drawn.** It used to be remembered on the
     * active run set alone, so a five-lane profile that logged on after the rail was first composed left
     * Fan out and Load run reading (0) and disabled until a run set happened to start. Found by driving the
     * rail with the RFQ example's load client connected. The rail is composed first here, on purpose.
     *
     * And it now says what it counts. "(1)" was a count of *profiles* and read as one lane.
     */
    @Test
    fun `the lane count follows a profile whose lanes log on after the rail was drawn`() {
        val server = TestFixServer()
        server.start()
        val runId = System.nanoTime().toString().takeLast(6)
        try {
            composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
            composeTestRule.waitForIdle()

            val profile =
                FixConnectionProfile(
                    name = "LoadGen",
                    config =
                        FixConnectionConfig(
                            connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                            senderCompID = "RAIL{nn}$runId",
                            targetCompID = "VENUE$runId",
                            sessionCount = 2,
                            host = "localhost",
                            port = server.port.toString(),
                            socketConnectHost = "localhost",
                            beginString = "FIX.4.4",
                            autoReconnect = false,
                            resetOnLogon = true,
                            fileStorePath = File(testDir, "store").absolutePath,
                            fileLogPath = File(testDir, "log").absolutePath,
                        ),
                )
            viewModel.saveConnectionProfile(profile)
            viewModel.connectProfile(profile.id, profile)
            composeTestRule.waitUntil(25_000) {
                val lanes = viewModel.getProfileSessions(profile.id)
                lanes.count { it.connectionState.value == FixConnectionState.LOGGED_ON } == 2
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("rail-run-menu").performClick()
            composeTestRule.waitForIdle()
            composeTestRule
                .onNodeWithTag("rail-run-load")
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertTextContains("Load run…  2 lanes on 1 profile")
            composeTestRule
                .onNodeWithTag("rail-run-fanout")
                .assertIsEnabled()
                .assertTextContains("Fan out over sessions…  2 lanes on 1 profile")
        } finally {
            viewModel.disconnectAllSessions()
            server.stop()
        }
    }

    /**
     * **Verdict first, kind second, for both row types.** A load row used to lead with ⚡, which says kind,
     * and a set row with ✓, which says verdict, so the first column meant two different things. And a
     * passing load run got no mark at all, because the ✗ was appended only on a non-zero exit.
     */
    @Test
    fun `a Recent row leads with its verdict and says its kind second, and a pass is marked`() {
        val passed = LoadRecord.of(LoadFixtures.burstReport(unmatched = 0))
        val failed = LoadRecord.of(LoadFixtures.burstReport(unmatched = 4))

        assertTrue(RecentRun.Load(passed).line.startsWith("✓ ⚡ "), RecentRun.Load(passed).line)
        assertTrue(RecentRun.Load(failed).line.startsWith("✗ ⚡ "), RecentRun.Load(failed).line)
    }

    /** A set's row reads like a scenario set's: verdict, kind, name with its phase count, passed of total. */
    @Test
    fun `a set's Recent row carries its phase count and how many phases passed`() {
        val one = LoadFixtures.burstReport(unmatched = 0).copy(label = "Ask for a quote")
        val two = LoadFixtures.burstReport(unmatched = 4).copy(label = "Hit them")
        val set =
            LoadRecord(
                id = "set-1",
                label = "RFQ round trip",
                startedAt = 0,
                finishedAt = 1_000,
                phases = listOf(one, two),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            )

        val line = RecentRun.Load(set).line

        assertTrue(line.startsWith("✗ ⚡ RFQ round trip (2) · LOADGEN"), line)
        assertTrue(line.endsWith("(1/2)"), line)
    }

    /**
     * **A refused `Load set ▸` opens the editor on the set that was refused.**
     *
     * The rail only knew that nothing had started, so it opened the editor on whichever set the editor
     * happened to select, which was the first one alphabetically. "Zulu broken" is deliberately last in
     * that order, so an editor showing it can only have been sent there on purpose.
     */
    @Test
    fun `a refused Load set opens the editor on that set, not on the first one saved`() {
        val server = TestFixServer()
        server.start()
        val runId = System.nanoTime().toString().takeLast(6)
        try {
            val profile =
                FixConnectionProfile(
                    name = "LoadGen",
                    config =
                        FixConnectionConfig(
                            connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                            senderCompID = "REF{nn}$runId",
                            targetCompID = "VENUE$runId",
                            sessionCount = 2,
                            host = "localhost",
                            port = server.port.toString(),
                            socketConnectHost = "localhost",
                            beginString = "FIX.4.4",
                            autoReconnect = false,
                            resetOnLogon = true,
                            fileStorePath = File(testDir, "store").absolutePath,
                            fileLogPath = File(testDir, "log").absolutePath,
                        ),
                )
            viewModel.saveConnectionProfile(profile)
            // Neither set can run: each names a template nothing answers to. That is a refusal the file can
            // fix, so both belong in the editor rather than in a notification.
            viewModel.saveLoadSet(
                LoadSet(
                    name = "aaa-first",
                    label = "Aaa first",
                    phases = listOf(LoadPhaseSpec("Phase 1", "Nothing", "LoadGen", shape = LoadShape.Burst(10))),
                ),
            )
            viewModel.saveLoadSet(
                LoadSet(
                    name = "zulu-broken",
                    label = "Zulu broken",
                    phases = listOf(LoadPhaseSpec("Phase 1", "Also nothing", "LoadGen", shape = LoadShape.Burst(10))),
                ),
            )

            composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
            viewModel.connectProfile(profile.id, profile)
            composeTestRule.waitUntil(25_000) {
                viewModel.getProfileSessions(profile.id).count { it.connectionState.value == FixConnectionState.LOGGED_ON } == 2
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("rail-run-menu").performClick()
            composeTestRule.waitForIdle()
            composeTestRule
                .onNodeWithTag("rail-run-load-set-zulu-broken")
                .assertIsEnabled()
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("load-sets-dialog").assertIsDisplayed()
            composeTestRule.onNodeWithTag("load-set-name").assertTextContains("Zulu broken")
            composeTestRule.onNodeWithTag("load-set-phase-fixes-1").assertTextContains("1 fix", substring = true)
        } finally {
            viewModel.disconnectAllSessions()
            server.stop()
        }
    }

    /**
     * **"Cannot run now" is a different answer from "refused".** No lane logged on is nothing the file can
     * fix, so it stays a notification and leaves the editor shut.
     */
    @Test
    fun `a set whose profile has no lane notifies rather than opening an editor with nothing to fix`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "down",
                name = "DOWN",
                config = FixConnectionConfig(senderCompID = "D{n}", targetCompID = "V", sessionCount = 2),
            ),
        )
        viewModel.saveLoadSet(
            LoadSet(
                name = "lanes-down",
                label = "Lanes down",
                phases = listOf(LoadPhaseSpec("Phase 1", "Nothing", "DOWN", shape = LoadShape.Burst(10))),
            ),
        )

        val outcome = viewModel.startSavedLoadSet("lanes-down")

        assertTrue(
            outcome is FixMessageViewModel.SavedLoadSetRun.Refused,
            "a template nothing answers to is the file's problem: $outcome",
        )
        assertTrue(
            viewModel.startSavedLoadSet("no-such-set") is FixMessageViewModel.SavedLoadSetRun.CannotRunNow,
            "a set that is not saved has no fields to fix, so no editor",
        )
    }

    @Test
    fun `a finished load run is a Recent row that opens its document`() {
        val report = LoadFixtures.burstReport(unmatched = 0)
        viewModel.loadRecordStore.write(report)

        composeTestRule.setContent { ScenariosRail(viewModel, modifier = Modifier.fillMaxSize()) }
        composeTestRule.onNodeWithTag("rail-run-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag(
                "rail-recent-${report.id}",
            ).assertIsDisplayed()
            .assertTextContains("⚡ ${report.label}  (4,000/4,000)", substring = true)
        composeTestRule.onNodeWithTag("rail-recent-${report.id}").performClick()
        composeTestRule.waitForIdle()

        val docs = viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>()
        assertEquals(listOf(report.id), docs.map { it.loadId })
    }
}
