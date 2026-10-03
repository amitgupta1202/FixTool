package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.io.File
import java.net.ServerSocket
import java.time.LocalDateTime

/**
 * **A pane's view state belongs to its session, not to the slot or the tab it is drawn in.**
 *
 * The grid remembers what the reader has done to it: rows ticked, a search, column widths, where it is
 * scrolled. The panes were drawn by position, so a pane that moved, a pane closed to its left, or a switch of
 * tab left one session's ticks over another session's rows, and the bar said "2 messages selected" over a log
 * in which nothing was ticked.
 */
class PaneStateIdentityTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<FixMessageSession>()

    @After
    fun tearDown() {
        opened.forEach { it.destroy() }
    }

    private fun fix(
        type: String,
        clOrdId: String,
    ): FixMessage =
        FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.OUTGOING,
            rawMessage = "8=FIX.4.4|35=$type|11=$clOrdId|",
            messageType = type,
            quickfixMessage = Message().apply { header.setField(MsgType(type)) },
        )

    /** A session with two messages of its own in its log, and nothing on the wire. */
    private fun session(name: String): FixMessageSession =
        FixMessageSession(id = name, title = name).apply {
            addMessage(fix("D", "$name-1"))
            addMessage(fix("8", "$name-2"))
            flushMessageQueue()
            opened += this
        }

    @Test
    fun `ticked rows move with their pane, and the pane that takes its slot has none`() {
        val sessions = mutableStateListOf(session("ALPHA"), session("BRAVO"))
        rule.setContent {
            Box(modifier = Modifier.size(1400.dp, 700.dp).background(AppTheme.Colors.background)) {
                SplitView(
                    sessions = sessions,
                    dictionary = FixDictionary.createDefault(),
                    viewMode = FixMessageSession.ViewMode.PARSED,
                    onCloseSession = {},
                    onMoveSession = { moving, target ->
                        val to = sessions.indexOf(target)
                        sessions.remove(moving)
                        sessions.add(to, moving)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ALPHA is the left pane: its header's select-all ticks its two rows.
        rule.onAllNodesWithTag("grid-select-all").onFirst().performClick()
        rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Selected").assertCountEquals(2)

        // Only ALPHA can move right, so this is ALPHA's button: BRAVO takes the left slot.
        rule.onAllNodesWithTag("pane-move-right").onFirst().performClick()
        rule.waitForIdle()

        rule.onAllNodesWithContentDescription("Selected").assertCountEquals(2)
        rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)
    }

    /** Three panes in two columns, so closing the first one slides the other two along and up. */
    @Test
    fun `closing a pane to the left leaves the pane that slides into its slot with only its own ticks`() {
        val sessions = mutableStateListOf(session("ALPHA"), session("BRAVO"), session("CHARLIE"))
        rule.setContent {
            Box(modifier = Modifier.size(1400.dp, 700.dp).background(AppTheme.Colors.background)) {
                SplitView(
                    sessions = sessions,
                    dictionary = FixDictionary.createDefault(),
                    viewMode = FixMessageSession.ViewMode.PARSED,
                    onCloseSession = { sessions.remove(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // BRAVO is top right: the second select-all.
        rule.onAllNodesWithTag("grid-select-all")[1].performClick()
        rule.onAllNodesWithContentDescription("Selected").assertCountEquals(2)

        rule.runOnIdle { sessions.removeAt(0) }
        rule.waitForIdle()

        // BRAVO moved left along its row and kept its ticks. CHARLIE came up into the slot BRAVO left, and
        // was handed nothing of BRAVO's.
        rule.onAllNodesWithContentDescription("Selected").assertCountEquals(2)
        rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)
    }

    // ---------------------------------------------------------------- the tabs layout's one pane

    @Test
    fun `switching tab does not hand the next session the last one's ticked rows`() {
        val dir =
            File.createTempFile("fixtool-pane-identity", "").apply {
                delete()
                mkdirs()
            }
        val viewModel = FixMessageViewModel(testSettingsDir = dir.absolutePath)
        try {
            val alpha = listening(viewModel, dir, "ALPHA")
            listening(viewModel, dir, "BRAVO")
            viewModel.setActiveSessionByObject(alpha)
            rule.setContent {
                Column(modifier = Modifier.size(1400.dp, 700.dp).background(AppTheme.Colors.background)) {
                    TabsCentre(
                        viewModel = viewModel,
                        globalViewMode = FixMessageSession.ViewMode.PARSED,
                        selectedMessage = null,
                        globalFilter = MessageFilters.Global.NONE,
                        followedUids = null,
                        followedTraceIds = emptySet(),
                        onOpenWorkspace = {},
                    )
                }
            }

            rule.onAllNodesWithTag("grid-select-all").onFirst().performClick()
            rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)

            rule.onNodeWithText("BRAVO").performClick()
            rule.waitForIdle()

            rule.onAllNodesWithContentDescription("Selected").assertCountEquals(0)
            rule.onAllNodesWithText("2 messages selected").assertCountEquals(0)
        } finally {
            viewModel.closeAllSessions()
            dir.deleteRecursively()
        }
    }

    /** A pane of the view model's, listening on a port of its own with nobody calling, and two messages logged. */
    private fun listening(
        viewModel: FixMessageViewModel,
        dir: File,
        name: String,
    ): FixMessageSession {
        val port = ServerSocket(0).use { it.localPort }.toString()
        val profile =
            FixConnectionProfile(
                name = name,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "$name${System.nanoTime().toString().takeLast(6)}",
                        targetCompID = "PEER",
                        port = port,
                        socketAcceptPort = port,
                        beginString = "FIX.4.4",
                        fileStorePath = File(dir, "${name}store").absolutePath,
                        fileLogPath = File(dir, "${name}log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        return viewModel.sessions.first { it.title == name }.apply {
            addMessage(fix("D", "$name-1"))
            addMessage(fix("8", "$name-2"))
            flushMessageQueue()
        }
    }
}
