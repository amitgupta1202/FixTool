package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass

/**
 * **A pane that opens leaves keyboard focus where it is, unless nothing holds it.**
 *
 * A pane took focus the moment it was created, and panes are created by more than the user: a venue client
 * logging on opens one, and so does each lane of a load run. Typing in the message editor or the filter, the
 * next keys went into the new pane's grid. Composed as the window composes it, through [App].
 */
class PaneFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var dir: File
    private lateinit var previousPaths: WorkspacePaths
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var owner: ViewModelStoreOwner

    @Before
    fun setup() {
        dir =
            File.createTempFile("fixtool-pane-focus", "").apply {
                delete()
                mkdirs()
            }
        previousPaths = WorkspacePaths.current
        WorkspacePaths.use(dir.absolutePath)
        viewModel = FixMessageViewModel(testSettingsDir = dir.absolutePath)
        owner = seeded(viewModel)
        rule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                Box(modifier = Modifier.size(1400.dp, 800.dp)) { App() }
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        viewModel.closeAllSessions()
        owner.viewModelStore.clear()
        WorkspacePaths.use(previousPaths)
        dir.deleteRecursively()
    }

    @Test
    fun `a pane that opens while the filter is being typed in leaves the filter focused`() {
        rule.onNodeWithTag("toolbar-filter-regex").performClick()
        rule.onNodeWithTag("toolbar-filter-regex").performTextInput("35=")
        rule.onNodeWithTag("toolbar-filter-regex").assertIsFocused()

        // The pane a venue client's logon opens, by the same door: the view model's one way to add a pane.
        rule.runOnIdle { viewModel.createSessionForTest("VENUE ← CLIENT") }
        rule.waitForIdle()
        rule.onAllNodesWithText("VENUE ← CLIENT").assertCountEquals(1)

        rule.onNodeWithTag("toolbar-filter-regex").assertIsFocused()
    }

    @Test
    fun `a pane that opens into a window where nothing holds focus takes it`() {
        rule.onAllNodes(isFocused()).assertCountEquals(0)

        rule.runOnIdle { viewModel.createSessionForTest("FIRST") }
        rule.waitForIdle()

        rule.onAllNodes(isFocused()).assertCountEquals(1)
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
}
