package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.ResponseStep
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/**
 * **What the two surfaces draw, and what they refuse to draw.**
 *
 * Three claims that only a rendered tree can settle. That a venue's pane carries none of the grid
 * controls, which is the defect this began as: eleven controls of which four did anything, the filter
 * the worst of them because it drew a working-looking regex box above a list it could not touch. That
 * move-left is withheld at *visible* position zero rather than real index zero, or the leftmost pane
 * grows a dead arrow whenever something is minimized ahead of it. And that a healthy venue's chip is
 * bare — the assertion that keeps the strip narrow, and the one a future badge would quietly break.
 */
class MinimizedStripTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private val runId = System.nanoTime().toString().takeLast(8)

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-minimized-strip", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        viewModel.disconnectAllSessions()
        testDir.deleteRecursively()
    }

    @Test
    fun `a venue's pane draws none of the message-grid controls`() {
        val venue = pane("VENUE", anyClient = true)
        viewModel.setSessionMinimized(venue, false)
        render()

        // The seven that did nothing. Their absence is the whole of proposal A.
        composeTestRule.onAllNodesWithContentDescription("Toggle Filter").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Group by Conversation").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Add Blank Line").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Clear All Messages").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Scroll to bottom").assertCountEquals(0)
        // And the bare power icon, whose replacement says what it will take down.
        composeTestRule.onAllNodesWithContentDescription("Disconnect").assertCountEquals(0)
        composeTestRule.onNodeWithText("Stop").assertExists()
        composeTestRule.onNodeWithText("Rules").assertExists()
    }

    @Test
    fun `an ordinary pane keeps every one of them`() {
        pane("PLAIN")
        render()

        composeTestRule.onAllNodesWithContentDescription("Toggle Filter").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Group by Conversation").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Clear All Messages").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Minimize Pane").assertCountEquals(1)
    }

    @Test
    fun `move left is withheld at visible position zero, not at real index zero`() {
        val a = pane("A")
        pane("B")
        pane("C")
        render()

        // Three panes: A has no move-left, B and C do.
        composeTestRule.onAllNodesWithContentDescription("Move Session Left").assertCountEquals(2)

        // Minimize A. B is now leftmost *visible* and must lose its move-left, even though it is still
        // at real index 1 — the case an `index > 0` test on the model's list gets wrong.
        composeTestRule.runOnIdle { viewModel.setSessionMinimized(a, true) }
        composeTestRule.onAllNodesWithContentDescription("Move Session Left").assertCountEquals(1)
    }

    @Test
    fun `a minimized pane leaves the grid for a chip that restores it`() {
        val a = pane("A")
        pane("B")
        render()

        composeTestRule.onAllNodesWithContentDescription("Minimize Pane").assertCountEquals(2)
        composeTestRule.runOnIdle { viewModel.setSessionMinimized(a, true) }

        // One pane left in the grid, and A is a chip above it.
        composeTestRule.onAllNodesWithContentDescription("Minimize Pane").assertCountEquals(1)
        composeTestRule.onNodeWithTag("minimized-strip").assertExists()
        composeTestRule.onNodeWithTag("chip:A").assertExists()

        // Clicking the chip puts the pane back.
        composeTestRule.onNodeWithTag("chip:A").performClick()
        composeTestRule.onAllNodesWithContentDescription("Minimize Pane").assertCountEquals(2)
    }

    @Test
    fun `the strip is absent while nothing is minimized`() {
        pane("A")
        render()
        // A bar that is always there is the furniture this change removes.
        composeTestRule.onAllNodesWithText("restore all").assertCountEquals(0)
        composeTestRule.onNodeWithTag("minimized-strip").assertDoesNotExist()
    }

    /**
     * A venue running normally reduces to identity, a client count and its two controls.
     *
     * The regression this guards is a badge that stops being conditional: every one of them widens the
     * strip on every venue in the world, which is what the deviation rule in [VenueSummary] exists to
     * prevent. Note the rule loaded on purpose — a venue with *no* rules is legitimately not quiet.
     */
    @Test
    fun `a healthy venue's chip carries no badges`() {
        pane("VENUE", anyClient = true)
        render()

        composeTestRule.onNodeWithTag("chip:VENUE").assertExists()
        listOf("⚠ no rules", "1 pending", "3 pending", "latency on").forEach { badge ->
            composeTestRule.onAllNodesWithText(badge).assertCountEquals(0)
        }
        composeTestRule.onAllNodesWithText("⚠ 1 refused").assertCountEquals(0)
        // What it does say: the same words its pane uses for the same fact.
        composeTestRule.onAllNodesWithText("no clients", substring = true).assertCountEquals(1)
    }

    /**
     * **The venue's Rules button works from the chip**, which is the only surface most testers see it on
     * since a venue starts minimized.
     *
     * A click-only path, so nothing but a rendered tree can check it. What it must do is open the
     * connection panel *on that venue's profile* — rules live in the profile editor and there was
     * previously no route to them from the venue at all.
     */
    @Test
    fun `clicking Rules on a venue's chip opens the connection panel on that profile`() {
        val venue = pane("VENUE", anyClient = true)
        render()

        composeTestRule.onNodeWithText("Rules").performClick()

        composeTestRule.runOnIdle {
            val profileId = viewModel.profileIdForSession(venue)
            assert(viewModel.showConnectionPanel.value) { "the connection panel should be open" }
            assert(viewModel.rulesExpandRequest.value == profileId) {
                "the rules section should be asked to unfold for ${venue.title}, " +
                    "got ${viewModel.rulesExpandRequest.value}"
            }
        }
    }

    @Test
    fun `a venue with no rules loaded says so on its chip`() {
        pane("BARE", anyClient = true, rules = emptyList())
        render()

        composeTestRule.onNodeWithText("⚠ no rules").assertExists()
    }

    // ------------------------------------------------------------------ helpers

    private fun render() {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(1400.dp, 700.dp).background(AppTheme.Colors.background)) {
                SplitView(
                    sessions = viewModel.sessions,
                    dictionary = viewModel.dictionary,
                    viewMode = FixMessageSession.ViewMode.PARSED,
                    onCloseSession = { viewModel.closeSession(it) },
                    onMoveSession = { session, target -> viewModel.moveSessionTo(session, target) },
                    onFocusSession = { viewModel.setActiveSessionByObject(it) },
                    activeSession = viewModel.activeSession,
                    onEditVenueRules = { viewModel.openVenueRules(it) },
                    onMinimize = { session, on -> viewModel.setSessionMinimized(session, on) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun pane(
        name: String,
        anyClient: Boolean = false,
        rules: List<AcceptorResponseRule> = listOf(AcceptorResponseRule(whenMsgType = "D", steps = listOf(ResponseStep(template = "35=8|39=0|")))),
    ): FixMessageSession {
        val port = ServerSocket(0).use { it.localPort }.toString()
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "$name$runId",
                        targetCompID = if (anyClient) FixConnectionConfig.ANY_CLIENT else "PEER$runId",
                        port = port,
                        socketAcceptPort = port,
                        beginString = "FIX.4.4",
                        fileStorePath = File(testDir, "${name}store").absolutePath,
                        fileLogPath = File(testDir, "${name}log").absolutePath,
                        acceptorResponseRules = if (anyClient) rules else emptyList(),
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        return viewModel.sessions.first { it.title == name }
    }
}
