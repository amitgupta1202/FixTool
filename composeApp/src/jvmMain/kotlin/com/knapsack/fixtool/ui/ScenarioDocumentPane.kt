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
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.ui.diff.DiffSurface
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
                            focusStep = doc.focusStep,
                            selectedStep = doc.selectedStep,
                            // The editor's door to the one surface that authors an assertion. Same tab, same
                            // surface, whichever message the slot happens to hold.
                            onOpenDiff = { stepId -> viewModel.openDiffForStep(doc.scenarioId, stepId) },
                            onSelectStep = { index -> viewModel.updateEditorDocument(doc.id) { it.copy(selectedStep = index) } },
                            onChange = { edited -> viewModel.updateScenarioDraft(doc.scenarioId) { it.copy(draft = edited) } },
                            onSave = { edited -> viewModel.saveScenarioDocument(edited) },
                            onBack = { viewModel.showSessions() },
                        )
                    }
                }
                is ScenarioDoc.Reconcile -> ReconcileDocument(viewModel, doc)
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
                        // Capture's second source (S9). Null for a live scan; the paste box otherwise.
                        paste = doc.paste,
                        onPasteChange = { text, session -> viewModel.updateCapturePaste(text, session) },
                        onSetDirection = { index, direction -> viewModel.setCandidateDirection(index, direction) },
                        sessionOptions = viewModel.sessions.map { it.title },
                    )
            }
        }
    }
}

/**
 * The diff — **the only surface in the app that can author or repair an assertion, and this is its only host.**
 *
 * It edits the expectation of one step of the scenario's draft. Every keystroke lands in the session, the
 * session writes it into the draft, and nothing reaches disk until Save — which saves the *scenario*, because
 * there is one draft of it however many tabs are looking at it.
 */
@Composable
private fun ReconcileDocument(viewModel: FixMessageViewModel, doc: ScenarioDoc.Reconcile) {
    val workspace by viewModel.openScenarios.collectAsState()
    val draft = workspace[doc.scenarioId]?.draft ?: return
    val at = draft.steps.indexOfFirst { it.stepId == doc.stepId }
    val step = draft.steps.getOrNull(at) as? ScenarioStep.Expect
    val session = doc.session

    if (step == null) {
        Text(
            "The step this diff was opened on is no longer in the scenario. Close this tab.",
            color = AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
        )
        return
    }
    if (session == null) {
        NothingToDiffAgainst(viewModel, doc, step)
        return
    }

    val running by viewModel.scenarioRunning.collectAsState()
    val armedSlot by viewModel.armedReferenceSlot.collectAsState()
    val messageType = step.expectation.messageType ?: step.match?.messageType ?: ""
    val typeName =
        viewModel.dictionary
            ?.getFieldEnumValues(35)
            ?.firstOrNull { it.first == messageType }
            ?.second
    DiffSurface(
        session = session,
        crumb =
            "${draft.name} › Step ${at + 1} · Expect ${typeName?.let { "$it ($messageType)" } ?: messageType} · " +
                "session ${step.session ?: "(active)"}",
        onSave = { viewModel.saveScenario(doc.scenarioId) },
        onSaveAndRerun = { viewModel.saveAndRerun(doc.scenarioId) },
        runInFlight = running,
        focusTag = doc.focusTag,
        // The slot: what may be in it is the host's to decide (it is the only thing that knows whether the step
        // has run, was ever captured, or has a second instance on a session) — the surface only draws it.
        referenceOptions = viewModel.referenceOptions(doc),
        onSelectReference = { kind -> viewModel.selectReference(doc, kind) },
        onPasteWire = { paste -> viewModel.bindPastedReference(doc, paste) },
        armed = armedSlot == doc.id,
        onDisarm = { viewModel.disarmReferenceSlot() },
        pastedOrigin = step.origin == StepOrigin.PASTED,
        onCancel = {
            // Cancel puts the expectation back exactly as it was found — in the *draft*, which is where the
            // session has been writing all along — and then the tab has nothing left to say.
            session.discard()
            viewModel.closeDocument(doc.id)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * **A step with no reference is a prompt, not a diff against nothing.**
 *
 * A never-run step that was never captured has no message on the right. Hand the surface an empty one and every
 * asserted row comes back `missing`: a wall of red, a verdict announcing a catastrophe, and a gutter offering to
 * drop every row of a step whose only crime is not having run yet.
 */
@Composable
private fun NothingToDiffAgainst(viewModel: FixMessageViewModel, doc: ScenarioDoc.Reconcile, step: ScenarioStep.Expect) {
    val selected by viewModel.selectedMessage.collectAsState()
    val pickable = selected?.wireRaw
    Column(modifier = Modifier.padding(16.dp).testTag("diff-no-reference")) {
        Text(
            "There is nothing on the right yet.",
            color = AppTheme.Colors.text,
            fontSize = 13.sp,
        )
        Text(
            "This step has never run and carries no captured message, so there is no reply to diff its " +
                "assertions against. Run the scenario — or bind a message from a session and edit against that.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        if (pickable != null) {
            SlimButton(
                text = "Use the message selected in the grid",
                onClick = { viewModel.bindPickedReference(doc, pickable, selected?.timestamp) },
                color = AppTheme.Colors.primary,
                modifier = Modifier.testTag("diff-bind-picked"),
            )
        } else {
            Text(
                "Select a message in a session grid and it can be bound here.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
            )
        }
        if (step.expectation.fields.isEmpty()) {
            Text(
                "This step asserts nothing at all yet: it only checks that a matching message arrives.",
                color = AppTheme.Colors.textDisabled,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
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
