// A dedicated Window is a separate composition; dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongMethod", "TooManyFunctions")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.StepOrigin
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.DiffWindowState
import com.knapsack.fixtool.ui.FixToolWindowChrome
import com.knapsack.fixtool.ui.NotificationPopupContainer
import com.knapsack.fixtool.ui.SlimButton
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The diff, in its own window.** The one surface in the app that can author or repair an assertion — and it
 * opens *beside* the grid it is about, not instead of it (Phase 6, proposal §1c). It is a top-level window
 * composed at application scope, one per `(scenarioId, stepId)` subject.
 *
 * Three things a second `Window` must do for itself, learned the hard way (redesign 2, and Phase 6's F-decisions):
 *
 * - **Its own [rememberWindowState].** A bare `WindowState` is re-created on every recomposition of the
 *   application scope and re-applies size/position to the live window — the `8f93596` trap, which moved the
 *   *main* window last time (F2). `rememberWindowState`, keyed by the window's id in the caller's `key(...)`.
 * - **Its own [FixToolWindowChrome].** A separate composition inherits none of the main window's
 *   CompositionLocals — theme, text-selection, scrollbars — so it applies them at its own root.
 * - **Its own [NotificationPopupContainer].** A failed save or run raised while this window has focus must
 *   surface *here*; the main window's popups are invisible behind a focused second window.
 */
@Composable
fun DiffWindow(viewModel: FixMessageViewModel, state: DiffWindowState, onClose: () -> Unit) {
    // Centred, not cascaded — the platform must not shove this window onto the main one's corner (nor the
    // reverse). Its own remembered state; see the kdoc for why a bare WindowState is a bug here.
    val windowState = rememberWindowState(size = DpSize(1100.dp, 900.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = onClose,
        title = diffWindowTitle(viewModel, state),
        state = windowState,
    ) {
        // A deep-link that lands *behind* the window that sent the author there has not gone anywhere. Every
        // re-open bumps the epoch; this raises the window to the front on the bump (F6).
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
                        // esc closes the window — but only after the diff's own esc stack (cancel a drag, dismiss
                        // a refusal) has had it. DiffSurface consumes esc in those cases and returns true, so this
                        // outer handler only ever sees the esc that means "close the view" (F7).
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                onClose()
                                true
                            } else {
                                false
                            }
                        },
            ) {
                DiffWindowBody(viewModel, state)
                NotificationPopupContainer(
                    notifications = viewModel.notifications,
                    onDismiss = { id -> viewModel.dismissNotification(id) },
                )
                // The dirty-last-view confirm, inline in the window, in the app's own confirm idiom (F4).
                val confirming by viewModel.confirmingCloseId.collectAsState()
                if (confirming == state.id) {
                    DiscardConfirm(
                        onDiscard = { viewModel.closeDiffWindow(state.id) },
                        onKeep = { viewModel.cancelCloseDocument() },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

/** `rfq flow v2 · Step 2 · Expect ExecutionReport(8) — FixTool` — a non-main title, so `?window=diff` finds it. */
@Composable
private fun diffWindowTitle(viewModel: FixMessageViewModel, state: DiffWindowState): String {
    val workspace by viewModel.openScenarios.collectAsState()
    val draft = workspace[state.scenarioId]?.draft
    val at = draft?.steps?.indexOfFirst { it.stepId == state.stepId } ?: -1
    val name = draft?.name?.ifBlank { null } ?: "scenario"
    val where = if (at >= 0) "Step ${at + 1}" else "step"
    return "$name · $where · reconcile — FixTool"
}

/**
 * The window's content: the diff of one step of the scenario's draft. Every keystroke lands in the session,
 * the session writes it into the draft, and nothing reaches disk until Save — which saves the *scenario*,
 * because there is one draft of it however many views are looking at it.
 */
@Composable
private fun DiffWindowBody(viewModel: FixMessageViewModel, state: DiffWindowState) {
    val workspace by viewModel.openScenarios.collectAsState()
    val draft = workspace[state.scenarioId]?.draft ?: return
    val at = draft.steps.indexOfFirst { it.stepId == state.stepId }
    val step = draft.steps.getOrNull(at) as? ScenarioStep.Expect
    val session = state.session

    if (step == null) {
        Text(
            "The step this diff was opened on is no longer in the scenario. Close this window.",
            color = AppTheme.Colors.textDisabled,
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (session == null) {
        NothingToDiffAgainst(viewModel, state, step)
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
        onSave = { viewModel.saveScenario(state.scenarioId) },
        onSaveAndRerun = { viewModel.saveAndRerun(state.scenarioId) },
        runInFlight = running,
        focusTag = state.focusTag,
        // The slot: what may be in it is the host's to decide (it is the only thing that knows whether the step
        // has run, was ever captured, or has a second instance on a session) — the surface only draws it.
        referenceOptions = viewModel.referenceOptions(state),
        onSelectReference = { kind -> viewModel.selectReference(state, kind) },
        onPasteWire = { paste -> viewModel.bindPastedReference(state, paste) },
        armed = armedSlot == state.id,
        onDisarm = { viewModel.disarmReferenceSlot() },
        pastedOrigin = step.origin == StepOrigin.PASTED,
        onCancel = {
            // Cancel puts the expectation back exactly as it was found — in the *draft*, which is where the
            // session has been writing all along — and then the window has nothing left to say.
            session.discard()
            viewModel.closeDiffWindow(state.id)
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
private fun NothingToDiffAgainst(viewModel: FixMessageViewModel, state: DiffWindowState, step: ScenarioStep.Expect) {
    val selected by viewModel.selectedMessage.collectAsState()
    val pickable = selected?.wireRaw
    Column(modifier = Modifier.padding(16.dp).testTag("diff-no-reference")) {
        Text("There is nothing on the right yet.", color = AppTheme.Colors.text, fontSize = 13.sp)
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
                onClick = { viewModel.bindPickedReference(state, pickable, selected?.timestamp) },
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

/** Closing the last view of a dirty scenario asks first, in the app's own confirm idiom (F4). */
@Composable
private fun DiscardConfirm(onDiscard: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.notificationErrorBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("diff-window-confirm-close"),
    ) {
        Text(
            "Discard unsaved edits and close?",
            color = AppTheme.Colors.warning,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
        SlimButton("Discard", onClick = onDiscard, color = AppTheme.Colors.error, modifier = Modifier.testTag("diff-window-discard"))
        SlimButton("Keep", onClick = onKeep, color = AppTheme.Colors.textSecondary)
    }
}
