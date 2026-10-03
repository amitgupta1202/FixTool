package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.io.File
import java.net.ServerSocket
import java.time.LocalDateTime
import kotlin.reflect.KClass

/**
 * **Opening or closing a tool window leaves the split panes as they were.**
 *
 * The split layout drew its centre from one of two call sites, picked by whether any dock was open, so the
 * first dock to open (a click on a row opens Detail onto an empty right stripe) threw every pane away and
 * built it again: rows ticked, rows expanded, dragged widths and the scroll all gone, and the clicked pane
 * scrolled to its bottom, because a new grid follows the tail and its delayed jump there beat the scroll to
 * the row just selected.
 */
class SplitCentreStabilityTest {
    @get:Rule
    val rule = createComposeRule()

    private fun fix(
        type: String,
        clOrdId: String,
        at: LocalDateTime = LocalDateTime.now(),
    ): FixMessage =
        FixMessage(
            timestamp = at,
            direction = FixMessage.Direction.OUTGOING,
            rawMessage = "8=FIX.4.4|35=$type|11=$clOrdId|",
            messageType = type,
            quickfixMessage = Message().apply { header.setField(MsgType(type)) },
        )

    @Test
    fun `a click that opens Detail keeps the pane's ticked rows`() {
        val dir =
            File.createTempFile("fixtool-split-centre", "").apply {
                delete()
                mkdirs()
            }
        val previousPaths = WorkspacePaths.current
        WorkspacePaths.use(dir.absolutePath)
        val viewModel = FixMessageViewModel(testSettingsDir = dir.absolutePath)
        val owner = seeded(viewModel)
        try {
            val first = LocalDateTime.now()
            listening(viewModel, dir, "ALPHA").apply {
                addMessage(fix("D", "ALPHA-1", first))
                addMessage(fix("8", "ALPHA-2", first.plusSeconds(1)))
                flushMessageQueue()
            }
            // Every dock closed, which is the state the second call site drew.
            viewModel.leftWindow.value?.let(viewModel::hide)
            viewModel.rightWindow.value?.let(viewModel::hide)

            rule.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    Box(modifier = Modifier.size(1400.dp, 800.dp)) { App() }
                }
            }
            rule.waitForIdle()

            rule.onAllNodesWithTag("grid-select-all").onFirst().performClick()
            rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)

            // A plain click on a row selects it, and with the right stripe empty that opens Detail.
            rule.onNodeWithTag("message-row-$first").performClick()
            rule.waitForIdle()
            check(viewModel.showDetailPanel.value) { "the click was meant to open Detail" }

            rule.onAllNodesWithContentDescription("Selected").assertCountEquals(2)
            rule.onAllNodesWithText("2 messages selected").assertCountEquals(1)
        } finally {
            viewModel.closeAllSessions()
            owner.viewModelStore.clear()
            WorkspacePaths.use(previousPaths)
            dir.deleteRecursively()
        }
    }

    /** What a rebuilt pane, or a tab coming up on a search result, starts with: a row selected before it is drawn. */
    @Test
    fun `a grid that comes up with a row selected shows that row, not its bottom`() {
        val start = LocalDateTime.now()
        val messages = (0 until 200).map { fix(if (it % 2 == 0) "D" else "8", "ORD$it", start.plusSeconds(it.toLong())) }
        val selected = messages[5]
        rule.setContent {
            Box(modifier = Modifier.size(900.dp, 400.dp)) {
                HierarchicalGridView(
                    messages = messages,
                    dictionary = FixDictionary.createDefault(),
                    hideProtocolTags = true,
                    selectedMessage = selected,
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        rule.onNodeWithTag("message-row-${selected.timestamp}").assertIsDisplayed()
    }

    /** A store already holding [viewModel], so the window's `viewModel { }` hands it this one rather than its own. */
    private fun seeded(viewModel: FixMessageViewModel): ViewModelStoreOwner {
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: KClass<T>,
                    extras: CreationExtras,
                ): T = viewModel as T
            }
        ViewModelProvider.create(owner.viewModelStore, factory)[FixMessageViewModel::class]
        return owner
    }

    /** A pane of the view model's, listening on a port of its own with nobody calling. */
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
        return viewModel.sessions.first { it.title == name }
    }
}
