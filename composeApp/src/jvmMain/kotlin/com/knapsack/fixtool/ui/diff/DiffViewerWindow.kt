// A dedicated Window is a separate composition; dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongMethod")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.knapsack.fixtool.service.compare.WirePaste
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
        // No session yet: the empty *"Diff messages…"* / *"Diff against…"* opener, one side (or none) filled so
        // far. A slot the author has not filled is a prompt, not a diff against nothing (G7).
        else -> {
            val armed by viewModel.armedViewerSlot.collectAsState()
            EmptySlots(
                pendingLeft = state.pendingLeft,
                pendingRight = state.pendingRight,
                armedSlot = armed?.takeIf { it.viewerId == state.id }?.slot,
                onArm = { slot -> viewModel.armViewerSlot(state.id, slot) },
                onDisarm = { viewModel.disarmViewerSlot() },
                onUsePaste = { slot, paste -> viewModel.fillViewerSlotFromPaste(state.id, slot, paste) },
            )
        }
    }
}

/**
 * **The two-empty-slots opener** — *"Diff messages…"* (both empty) and *"Diff against…"* (A pre-filled, B armed).
 * Each side is filled from a **session pick** ([onArm] the slot, then click a grid row) or a **paste** (the same
 * [PasteSheet] and refusals the reconcile reference slot uses); the host promotes the pair into a read-only diff
 * the moment both are known (G4/G7). Pure — state and callbacks in, no ViewModel — so the click-only gate can
 * drive and photograph it.
 */
@Suppress("LongParameterList") // A composable slot: its state and callbacks read clearer flat than boxed in a DTO.
@Composable
internal fun EmptySlots(
    pendingLeft: DiffSide? = null,
    pendingRight: DiffSide? = null,
    armedSlot: ViewerSlot? = null,
    onArm: (ViewerSlot) -> Unit = {},
    onDisarm: () -> Unit = {},
    onUsePaste: (ViewerSlot, WirePaste) -> Unit = { _, _ -> },
) {
    // Which slot's paste sheet is open — UI-local, and mutually exclusive with an armed slot (opening one closes
    // the other, so a grid click is never ambiguous about which side it feeds).
    var pasteSlot by remember { mutableStateOf<ViewerSlot?>(null) }

    fun arm(slot: ViewerSlot) {
        pasteSlot = null
        onArm(slot)
    }

    fun openPaste(slot: ViewerSlot) {
        onDisarm()
        pasteSlot = slot
    }

    fun use(slot: ViewerSlot, paste: WirePaste) {
        onUsePaste(slot, paste)
        pasteSlot = null
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("diff-viewer-empty")) {
        Text("Diff two messages", color = AppTheme.Colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            "Fill each side from a session pick or a paste. The diff opens as soon as both sides are set.",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        SlotCard(
            letter = "A",
            color = AppTheme.Colors.info,
            side = pendingLeft,
            armedForThis = armedSlot == ViewerSlot.LEFT,
            pasteOpen = pasteSlot == ViewerSlot.LEFT,
            onArm = { arm(ViewerSlot.LEFT) },
            onOpenPaste = { openPaste(ViewerSlot.LEFT) },
            onDisarm = onDisarm,
            onUsePaste = { paste -> use(ViewerSlot.LEFT, paste) },
            onCancelPaste = { pasteSlot = null },
        )
        Spacer(Modifier.height(10.dp))
        SlotCard(
            letter = "B",
            color = AppTheme.Colors.warning,
            side = pendingRight,
            armedForThis = armedSlot == ViewerSlot.RIGHT,
            pasteOpen = pasteSlot == ViewerSlot.RIGHT,
            onArm = { arm(ViewerSlot.RIGHT) },
            onOpenPaste = { openPaste(ViewerSlot.RIGHT) },
            onDisarm = onDisarm,
            onUsePaste = { paste -> use(ViewerSlot.RIGHT, paste) },
            onCancelPaste = { pasteSlot = null },
        )
    }
}

@Suppress("LongParameterList") // A composable slot: its state and callbacks read clearer flat than boxed in a DTO.
@Composable
private fun SlotCard(
    letter: String,
    color: Color,
    side: DiffSide? = null,
    armedForThis: Boolean = false,
    pasteOpen: Boolean = false,
    onArm: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onDisarm: () -> Unit = {},
    onUsePaste: (WirePaste) -> Unit = {},
    onCancelPaste: () -> Unit = {},
) {
    val lower = letter.lowercase()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, color)
                .padding(10.dp)
                .testTag("viewer-slot-$lower"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(letter, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            if (side != null) {
                Text(
                    side.label,
                    color = AppTheme.Colors.text,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("viewer-slot-$lower-filled"),
                )
            } else {
                Text("no message yet", color = AppTheme.Colors.textDisabled, fontSize = 11.sp, fontStyle = FontStyle.Italic)
            }
            Spacer(Modifier.weight(1f))
            SlimButton("pick from session…", onClick = onArm, modifier = Modifier.testTag("viewer-slot-$lower-pick"))
            SlimButton(
                "paste wire…",
                onClick = onOpenPaste,
                modifier = Modifier.padding(start = 6.dp).testTag("viewer-slot-$lower-paste"),
            )
        }
        if (armedForThis) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(AppTheme.Colors.notificationInfoBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("viewer-slot-$lower-armed"),
            ) {
                Text(
                    "◎  Click any row in a session grid to fill side $letter. A message FixTool has no wire bytes " +
                        "for cannot be bound, and it will say so.",
                    color = AppTheme.Colors.info,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                SlimButton(
                    "cancel",
                    onClick = onDisarm,
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.testTag("viewer-slot-$lower-disarm"),
                )
            }
        }
        if (pasteOpen) {
            Box(Modifier.padding(top = 8.dp)) {
                PasteSheet(onUse = onUsePaste, onCancel = onCancelPaste, useLabel = "Use as side $letter")
            }
        }
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
