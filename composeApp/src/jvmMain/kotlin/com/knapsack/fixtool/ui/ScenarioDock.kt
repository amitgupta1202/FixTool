// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The scenario editor, docked at the bottom of the main window — the IntelliJ tool-window layout, the
 * same one [com.knapsack.fixtool.ui.terminal.TerminalDock] already uses.**
 *
 * The point of the dock is what it is *not*: it is no longer a pane in the session split. The sessions are the
 * stable "code area"; this rides beneath them and does not reflow when the session layout (TABS / SPLIT) changes.
 * Every open document (scenario editor **and** capture review) shows here, behind its own tab strip — a
 * document tab strip grown a resize grip and a minimize chevron.
 *
 *  - **Resize**: drag the top edge (grow up, shrink down). Session-scoped, like the terminal's — not persisted.
 *  - **Minimize**: collapses to the header row, reclaiming the space *without leaving composition*, so the
 *    editor's scroll position and field focus survive. The draft was always safe (it lives in the ViewModel),
 *    but the view state inside the composable is not, and a glance away should not throw it out.
 *  - **Restore**: opening or focusing any document clears the minimized flag (in the ViewModel), so a click on
 *    a step in the rail brings the editor straight back, at that step. See [FixMessageViewModel.focusDocument].
 *
 * Absent from the layout entirely when there are no open documents — nothing changes for someone who never
 * opens a scenario.
 */
@Composable
fun ScenarioDock(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    val documents by viewModel.openDocuments.collectAsState()
    if (documents.isEmpty()) return
    val workspace by viewModel.openScenarios.collectAsState()
    val activeId by viewModel.activeDocumentId.collectAsState()
    val confirmingCloseId by viewModel.confirmingCloseId.collectAsState()
    val minimized by viewModel.scenarioDockMinimized.collectAsState()
    // Like the SPLIT area before it, the dock falls back to the last document opened: there is no session tab
    // in here to deselect to, so a strip with nothing active would be one you could not get out of.
    val active = documents.firstOrNull { it.id == activeId } ?: documents.last()

    val density = LocalDensity.current
    var heightDp by remember { mutableStateOf(viewModel.layoutState.value.scenarioDockHeightDp.dp) }

    Column(modifier.fillMaxWidth().testTag("scenario-dock")) {
        // Top edge. Expanded it is the resize handle (drag up to grow, and persist on release); minimized there
        // is no body to size, so it is a plain 1px divider like the app's other seams.
        if (minimized) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A3A)))
        } else {
            HeightResizeHandle(
                onDeltaPx = { dy ->
                    heightDp = (heightDp - with(density) { dy.toDp() }).coerceIn(140.dp, 760.dp)
                },
                onDragEnd = { viewModel.updateLayout { it.copy(scenarioDockHeightDp = heightDp.value) } },
            )
        }
        // Header — the document tab strip on the left, the minimize chevron on the right.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Colors.surface)
                    .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocumentTabs(
                tabs = documentTabsOf(documents, workspace),
                activeId = active.id,
                confirmingCloseId = confirmingCloseId,
                onFocus = { viewModel.focusDocument(it) },
                onRequestClose = { viewModel.requestCloseDocument(it) },
                onConfirmClose = { viewModel.closeDocument(it) },
                onCancelClose = { viewModel.cancelCloseDocument() },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { viewModel.toggleScenarioDockMinimized() },
                modifier = Modifier.size(24.dp).testTag("scenario-dock-minimize"),
            ) {
                Icon(
                    imageVector = if (minimized) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (minimized) "Restore Edit Scenario" else "Minimize Edit Scenario",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // The active document. Minimized it collapses to zero height but stays composed, so the editor's
        // scroll and focus survive — the same trick the terminal uses to keep its PTY alive.
        ScenarioDocumentPane(
            viewModel,
            active,
            modifier = Modifier.fillMaxWidth().height(if (minimized) 0.dp else heightDp),
        )
    }
}
