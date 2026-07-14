// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * The document tabs — scenario editors and capture reviews, beside the session tabs.
 *
 * Closing one that is [ScenarioDoc.dirty] asks first, inline on the tab, in the app's own confirm idiom. It
 * has to ask: a document tab holds the author's unsaved work (see [ScenarioDoc]), and a tab strip is a place
 * where a `×` gets clicked by accident.
 */
@Composable
fun DocumentTabs(
    tabs: List<DocumentTab>,
    activeId: String?,
    confirmingCloseId: String?,
    onFocus: (String) -> Unit,
    onRequestClose: (String) -> Unit,
    onConfirmClose: (String) -> Unit,
    onCancelClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            tabs.forEach { tab ->
                DocumentTabView(
                    tab = tab,
                    active = tab.id == activeId,
                    confirming = confirmingCloseId == tab.id,
                    onClick = { onFocus(tab.id) },
                    onRequestClose = { onRequestClose(tab.id) },
                    onConfirmClose = { onConfirmClose(tab.id) },
                    onCancelClose = onCancelClose,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun DocumentTabView(
    tab: DocumentTab,
    active: Boolean,
    confirming: Boolean,
    onClick: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmClose: () -> Unit,
    onCancelClose: () -> Unit,
) {
    val background = if (active) AppTheme.Colors.background else AppTheme.Colors.surface
    val textColor = if (active) AppTheme.Colors.text else AppTheme.Colors.textSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .testTag("doc-tab-${tab.id}"),
    ) {
        // The accent that says "this is not a session" — the mockup's violet on the document tabs.
        Text(
            tab.glyph,
            color = if (active) DocumentAccent else AppTheme.Colors.textDisabled,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(tab.title + if (tab.dirty) " •" else "", color = textColor, fontSize = 11.sp, maxLines = 1)
        if (confirming) {
            Text("discard edits?", color = AppTheme.Colors.warning, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
            SlimButton(
                "Discard",
                onClick = onConfirmClose,
                color = AppTheme.Colors.error,
                modifier = Modifier.padding(start = 4.dp).testTag("doc-discard-${tab.id}"),
            )
            SlimButton("Keep", onClick = onCancelClose, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(start = 2.dp))
        } else {
            Spacer(modifier = Modifier.width(6.dp))
            TooltipIconButton(tooltip = "Close tab", onClick = onRequestClose, modifier = Modifier.size(16.dp).testTag("doc-close-${tab.id}")) {
                Icon(Icons.Default.Close, contentDescription = "Close Document", tint = textColor, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/** The violet the mockup gives a document, so a document never reads as a session at a glance. */
internal val DocumentAccent =
    androidx.compose.ui.graphics
        .Color(0xFFC586C0)

/**
 * The active document's content.
 *
 * Only this one is composed — every other open document is state in the ViewModel and nothing else, which is
 * exactly why the state is there and not in these composables. See [ScenarioDoc].
 */
@Composable
fun ScenarioDocumentPane(viewModel: FixMessageViewModel, doc: ScenarioDoc, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
        Column(modifier = modifier.fillMaxSize().background(AppTheme.Colors.background).padding(8.dp)) {
            when (doc) {
                is ScenarioDoc.Editor -> {
                    // The draft is the SCENARIO's, not this tab's — the diff tab is looking at the same one.
                    val workspace by viewModel.openScenarios.collectAsState()
                    val draft = workspace[doc.scenarioId]?.draft ?: return@Column
                    // Keyed on the deep-link's epoch, not on the draft: a second failure in a scenario that is
                    // already open must move the cursor to the new step, and a seeded-once cursor will not
                    // move for a mere prop. Re-keying rebuilds the editor from the draft, which the composable
                    // has been mirroring out all along, so the author's unsaved edits survive the re-aim.
                    key(doc.id, doc.focusEpoch) {
                        ScenarioEditor(
                            initial = draft,
                            dictionary = viewModel.dictionary,
                            sessionOptions = (draft.sessionsInvolved() + viewModel.sessions.map { it.title }).distinct(),
                            secondInstance = { session, messageType, golden -> viewModel.liveSecondInstance(session, messageType, golden) },
                            focusStep = doc.focusStep,
                            runFailure = doc.failure,
                            selectedStep = doc.selectedStep,
                            onSelectStep = { index -> viewModel.updateEditorDocument(doc.id) { it.copy(selectedStep = index) } },
                            onChange = { edited -> viewModel.updateScenarioDraft(doc.scenarioId) { it.copy(draft = edited) } },
                            onSave = { edited -> viewModel.saveScenarioDocument(edited) },
                            onBack = { viewModel.showSessions() },
                        )
                    }
                }
                is ScenarioDoc.Capture ->
                    ScenarioCaptureReview(
                        candidates = doc.scan.candidates,
                        unreadable = doc.scan.unreadable,
                        dictionary = viewModel.dictionary,
                        state = doc.state,
                        onStateChange = { state -> viewModel.updateCaptureDocument { it.copy(state = state) } },
                        // The whole point of capture being a tab: the grid it was scanned from is right there,
                        // so selecting a candidate selects its source message in the session it came from.
                        onSelectSource = { message -> viewModel.selectMessage(message) },
                        onSave = { name, selection -> viewModel.saveCaptureDocument(name, selection) },
                        onBack = { viewModel.showSessions() },
                    )
            }
        }
    }
}

/**
 * The document area as the SPLIT layouts show it: its own tab strip, and the active document beneath.
 *
 * In SPLIT there are no session tabs to click, so a document cannot be *deselected* — which means a strip
 * with nothing selected would be a strip you could not get out of. The area therefore falls back to the last
 * document opened, and it renders only while there is one.
 */
@Composable
fun ScenarioDocumentArea(viewModel: FixMessageViewModel, modifier: Modifier = Modifier) {
    val documents by viewModel.openDocuments.collectAsState()
    val workspace by viewModel.openScenarios.collectAsState()
    val activeId by viewModel.activeDocumentId.collectAsState()
    val confirmingCloseId by viewModel.confirmingCloseId.collectAsState()
    val active = documents.firstOrNull { it.id == activeId } ?: documents.lastOrNull() ?: return
    Column(modifier = modifier.fillMaxSize()) {
        androidx.compose.material3.HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface).padding(horizontal = 6.dp, vertical = 2.dp)) {
            DocumentTabs(
                tabs = documentTabsOf(documents, workspace),
                activeId = active.id,
                confirmingCloseId = confirmingCloseId,
                onFocus = { viewModel.focusDocument(it) },
                onRequestClose = { viewModel.requestCloseDocument(it) },
                onConfirmClose = { viewModel.closeDocument(it) },
                onCancelClose = { viewModel.cancelCloseDocument() },
            )
        }
        ScenarioDocumentPane(viewModel, active, modifier = Modifier.fillMaxSize())
    }
}
