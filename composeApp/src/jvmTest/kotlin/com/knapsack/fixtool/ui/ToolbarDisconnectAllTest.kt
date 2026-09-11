package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.integration.TestFixServer
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * **Disconnect all, and its three states.**
 *
 * No confirmation: Connect puts everything back in one click and nothing is lost, so a dialog would
 * only be a step to click through. The count is in the tooltip because it is the number that decides
 * whether you meant to press it, and the two refusals say *why* rather than leaving a dead button to
 * explain itself. The reason is in the semantics as well as the hover bubble: a Compose tooltip exists
 * only while the pointer is over it, so that is the only place a test can read it.
 */
class ToolbarDisconnectAllTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-disconnect-all", "").apply {
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
    fun `with nothing connected the button is disabled and says so`() {
        composeTestRule.setContent { ToolbarSessionControls(viewModel) }

        composeTestRule
            .onNodeWithTag("toolbar-disconnect-all")
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assertContentDescriptionContains("Nothing is connected")
    }

    /**
     * **The count is of sessions, and of the profiles they came from.** "2 sessions on 1 profile" is a
     * different act from "10 sessions on 3 profiles", and a bare "2" cannot tell them apart.
     */
    @Test
    fun `with sessions logged on it counts them, and pressing it drops them`() {
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
                            senderCompID = "DISC{nn}$runId",
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
            composeTestRule.setContent { ToolbarSessionControls(viewModel) }
            viewModel.connectProfile(profile.id, profile)
            composeTestRule.waitUntil(25_000) {
                viewModel.getProfileSessions(profile.id).count { it.connectionState.value == FixConnectionState.LOGGED_ON } == 2
            }
            composeTestRule.waitForIdle()

            composeTestRule
                .onNodeWithTag("toolbar-disconnect-all")
                .assertHasClickAction()
                .assertContentDescriptionContains("Disconnect all · 2 sessions on 1 profile")

            // No confirmation dialog between the click and the sessions going down.
            composeTestRule.onNodeWithTag("toolbar-disconnect-all").performClick()
            composeTestRule.waitUntil(25_000) {
                viewModel.getProfileSessions(profile.id).none { it.connectionState.value == FixConnectionState.LOGGED_ON }
            }
        } finally {
            viewModel.disconnectAllSessions()
            server.stop()
        }
    }

    /**
     * **A live load run is the one thing a disconnect would ruin**, so the button is refused with the
     * sentence `POST /disconnect` refuses with, word for word. A record with more than one phase, or with
     * a set name on it, is a set: the two are different words to whoever is reading the tooltip.
     */
    @Test
    fun `a live load run refuses in the same words the control surface uses`() {
        val single = LoadRecord.of(LoadFixtures.burstReport(unmatched = 0))

        val run = viewModel.disconnectAllOffer(activeLoad = single, runningSetIds = setOf(single.id))
        assertEquals(false, run.enabled)
        assertEquals("A load run is running. Stop it first.", run.tooltip)

        val set =
            single.copy(
                id = "set-1",
                phases = listOf(LoadFixtures.burstReport(unmatched = 0), LoadFixtures.burstReport(unmatched = 0)),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            )
        val asSet = viewModel.disconnectAllOffer(activeLoad = set, runningSetIds = setOf(set.id))
        assertEquals(false, asSet.enabled)
        assertEquals("A load set is running. Stop it first.", asSet.tooltip)
    }

    /** A record on screen that has *finished* holds nothing, so it must not refuse the button. */
    @Test
    fun `a finished run on screen does not refuse the button`() {
        val done = LoadRecord.of(LoadFixtures.burstReport(unmatched = 0))

        val offer = viewModel.disconnectAllOffer(activeLoad = done, runningSetIds = emptySet())

        assertEquals("Nothing is connected", offer.tooltip, "the run is over, so the reason is the sessions")
    }
}
