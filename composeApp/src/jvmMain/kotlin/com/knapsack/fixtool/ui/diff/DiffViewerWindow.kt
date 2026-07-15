// A dedicated Window is a separate composition; dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongMethod")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.FixToolWindowChrome
import com.knapsack.fixtool.ui.NotificationPopupContainer
import com.knapsack.fixtool.ui.SlimButton
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The plain diff viewer, in its own window** — a second window subject alongside `DiffWindow`, and it belongs
 * to no scenario at all (Phase 7, G3). It reuses the Phase-6 window mechanism verbatim: application-scope
 * `Window`, its own `rememberWindowState` (the `8f93596` trap, F2), its own chrome and notifications, and it
 * raises itself on a `focusEpoch` bump (F6).
 *
 * Its title is **distinct** — `diff: 8 vs 8 — FixTool` — so `?window=diff:` addresses it by substring while a
 * reconcile window (whose title carries `reconcile`) is also open; `?window=diff`'s "first non-main window"
 * heuristic is a coin flip with two non-main windows, and the gate does not lean on it (G8).
 */
@Composable
fun DiffViewerWindow(viewModel: FixMessageViewModel, state: DiffViewerState, onClose: () -> Unit) {
    val windowState = rememberWindowState(size = DpSize(1100.dp, 820.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = onClose,
        title = "diff: ${state.subjectTypes} — FixTool",
        state = windowState,
    ) {
        LaunchedEffect(state.focusEpoch) {
            if (windowState.isMinimized) windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        FixToolWindowChrome {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(AppTheme.Colors.background)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                onClose()
                                true
                            } else {
                                false
                            }
                        },
            ) {
                DiffViewerBody(viewModel, state)
                NotificationPopupContainer(
                    notifications = viewModel.notifications,
                    onDismiss = { id -> viewModel.dismissNotification(id) },
                )
            }
        }
    }
}

@Composable
private fun DiffViewerBody(viewModel: FixMessageViewModel, state: DiffViewerState) {
    val editing = state.editing
    val session = state.session
    when {
        // The one-way door: Seed floated a scenario-less expectation, so the window is the editor now (G6).
        editing != null -> SeededEditor(viewModel, state, editing)
        session != null ->
            DiffViewerSurface(
                session = session,
                onSwapSides = { viewModel.swapDiffViewerSides(state.id) },
                onSelectMode = { mode -> viewModel.selectDiffViewerMode(state.id, mode) },
                onSeed = { from -> viewModel.seedFromViewer(state.id, from) },
                modifier = Modifier.fillMaxSize(),
            )
        else ->
            Text(
                "Nothing is bound to diff yet.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 12.sp,
                modifier = Modifier.padding(16.dp),
            )
    }
}

/**
 * **The seeded, scenario-less editor.** The viewer flipped into authoring mode with the other side as its
 * reference; there is no scenario yet, so Save is replaced by *"Add to scenario…"* — a picker of existing
 * scenarios and a fresh one — and Cancel drops the seed and returns to the read-only viewer (G6).
 */
@Composable
private fun SeededEditor(viewModel: FixMessageViewModel, state: DiffViewerState, editing: com.knapsack.fixtool.ui.diff.ReconcileSession) {
    Column(modifier = Modifier.fillMaxSize()) {
        AddToScenarioBar(viewModel, state)
        DiffSurface(
            session = editing,
            crumb = "seeded expectation · not in a scenario yet",
            onCancel = { viewModel.cancelSeed(state.id) },
            // No Save and no reference menu: this expectation is not a step of anything until it is filed.
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AddToScenarioBar(viewModel: FixMessageViewModel, state: DiffViewerState) {
    val scenarios by viewModel.scenarios.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("seeded-editor-bar"),
    ) {
        Text("Seeded expectation — assert when ready.", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        Box(Modifier.padding(start = 8.dp)) {
            SlimButton(
                "Add to scenario… ▾",
                onClick = { open = true },
                color = AppTheme.Colors.primary,
                modifier = Modifier.testTag("add-to-scenario"),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    onClick = {
                        open = false
                        viewModel.addSeededToScenario(state.id, viewModel.newScenarioForSeed())
                    },
                    modifier = Modifier.testTag("add-to-new-scenario"),
                    text = { Text("＋ New scenario", color = AppTheme.Colors.primary, fontSize = 11.sp) },
                )
                scenarios.forEach { scenario ->
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            viewModel.addSeededToScenario(state.id, scenario)
                        },
                        modifier = Modifier.testTag("add-to-scenario-${scenario.id}"),
                        text = { Text(scenario.name.ifBlank { "untitled scenario" }, color = AppTheme.Colors.text, fontSize = 11.sp) },
                    )
                }
            }
        }
    }
}
