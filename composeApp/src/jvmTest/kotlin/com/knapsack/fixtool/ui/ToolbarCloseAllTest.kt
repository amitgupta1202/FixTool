package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
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
 * **Close all, and the second click that is its confirmation.**
 *
 * The half Disconnect all deliberately leaves undone: a fifty-lane load set leaves fifty panes, and
 * putting a box back to nothing meant closing them one at a time. It asks, where Disconnect all does not,
 * because the difference between the two is exactly what cannot be put back: Connect returns the
 * sessions, and nothing returns a pane's messages.
 *
 * It counts **panes**, not connected sessions. A pane left over from a run that has already been
 * disconnected is the case this exists for, and "Nothing is connected" would refuse to clear it — which is
 * why the panes here are staged against a port nobody is listening on.
 */
class ToolbarCloseAllTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-close-all", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        viewModel.closeAllSessions()
        testDir.deleteRecursively()
    }

    @Test
    fun `with no pane open the button is disabled and says so`() {
        composeTestRule.setContent { ToolbarSessionControls(viewModel) }

        composeTestRule
            .onNodeWithTag("toolbar-close-all")
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assertContentDescriptionContains("No session is open")
    }

    /**
     * **One click arms it, the next one closes.** The count is in both sentences because it is the number
     * that decides whether you meant to press it, and the armed one says what the click will cost.
     */
    @Test
    fun `it counts panes, and the second click is what closes them`() {
        val profile =
            FixConnectionProfile(
                name = "LoadGen",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = "CLOSE{nn}${System.nanoTime().toString().takeLast(6)}",
                        targetCompID = "VENUE",
                        sessionCount = 2,
                        host = "localhost",
                        // Nobody is listening: the panes are what this is about, not what is on the wire.
                        port = "1",
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = false,
                        fileStorePath = File(testDir, "store").absolutePath,
                        fileLogPath = File(testDir, "log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        composeTestRule.setContent { ToolbarSessionControls(viewModel) }
        viewModel.connectProfile(profile.id, profile)
        composeTestRule.waitUntil(25_000) { viewModel.sessions.size == 2 }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag("toolbar-close-all")
            .assertHasClickAction()
            .assertContentDescriptionContains(
                "Close all · ${Shortcuts.CLOSE_ALL.label} · 2 panes on 1 profile. Their messages go with them.",
            )

        // Armed, and nothing has happened yet: this is the click a stray press would have cost you.
        composeTestRule.onNodeWithTag("toolbar-close-all").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, viewModel.sessions.size, "the first click asks, it does not close")
        composeTestRule.onNodeWithTag("toolbar-close-all").assertContentDescriptionContains("Close 2 panes? Click again.")

        composeTestRule.onNodeWithTag("toolbar-close-all").performClick()
        composeTestRule.waitUntil(25_000) { viewModel.sessions.isEmpty() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("toolbar-close-all").assertContentDescriptionContains("No session is open")
    }

    /**
     * **A folded chip asks its question in words.** ⌘⇧W and the Session menu arm this chip while nobody hovers
     * it, and a folded chip says its sentence only on hover, so the question would be asked where it cannot be
     * read. For as long as it is armed it wears its words, whatever the toolbar's fold took from it.
     */
    @Test
    fun `an armed Close all shows its question even on a toolbar folded to glyphs`() {
        val profile = unreachable("FOLDED", sessions = 1)
        composeTestRule.setContent { ToolbarSessionControls(viewModel, words = false) }
        viewModel.connectProfile(profile.id, profile)
        composeTestRule.waitUntil(25_000) { viewModel.sessions.size == 1 }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Close all").assertDoesNotExist()

        composeTestRule.onNodeWithTag("toolbar-close-all").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("toolbar-close-all").assertTextContains("Close 1?")
    }

    private fun unreachable(
        name: String,
        sessions: Int,
    ): FixConnectionProfile =
        FixConnectionProfile(
            name = name,
            config =
                FixConnectionConfig(
                    connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                    senderCompID = "$name{nn}${System.nanoTime().toString().takeLast(6)}",
                    targetCompID = "VENUE",
                    sessionCount = sessions,
                    host = "localhost",
                    port = "1",
                    socketConnectHost = "localhost",
                    beginString = "FIX.4.4",
                    autoReconnect = false,
                    fileStorePath = File(testDir, "store").absolutePath,
                    fileLogPath = File(testDir, "log").absolutePath,
                ),
        ).also { viewModel.saveConnectionProfile(it) }

    /**
     * A live load run refuses this for the same reason it refuses Disconnect all, in the same words: the
     * panes it is measuring are the run's evidence, and closing them loses more than dropping them does.
     */
    @Test
    fun `a live load run refuses it in the words Disconnect all uses`() {
        val single = LoadRecord.of(LoadFixtures.burstReport(unmatched = 0))

        val run = viewModel.closeAllOffer(activeLoad = single, runningSetIds = setOf(single.id))
        assertEquals(false, run.enabled)
        assertEquals("A load run is running. Stop it first.", run.tooltip)
        assertEquals(viewModel.disconnectAllOffer(single, setOf(single.id)).tooltip, run.tooltip)

        val set =
            single.copy(
                id = "set-1",
                phases = listOf(LoadFixtures.burstReport(unmatched = 0), LoadFixtures.burstReport(unmatched = 0)),
                set = LoadRecord.SetInfo("rfq-round-trip", OnFailure.STOP),
            )
        val asSet = viewModel.closeAllOffer(activeLoad = set, runningSetIds = setOf(set.id))
        assertEquals(false, asSet.enabled)
        assertEquals("A load set is running. Stop it first.", asSet.tooltip)
    }
}
