// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.TagStatus
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.MATCHER_TYPES
import com.knapsack.fixtool.ui.MatcherEditor
import com.knapsack.fixtool.ui.SlimButton

/**
 * **A failed step as a diff you can edit.** The one surface in the app that authors an assertion.
 *
 * Left: the expectation, every row directly editable — the matcher chip *is* the value column. Right: the
 * message the reference slot holds, read-only, in the venue's own wire order. The gutter between them carries
 * the applies, and every one of them is an operation the engine already implements, wearing a better control.
 *
 * It renders a [ReconcileSession] and decides nothing:
 *
 * - **the pairing** — which row faces which field — comes off `Chunk.pairs`, so the two columns cannot come
 *   to disagree about what is opposite what;
 * - **the offers** come off `offersFor`, so the gutter cannot draw a button the engine would refuse;
 * - **the verdict** comes off [com.knapsack.fixtool.service.compare.Verdict], so the headline cannot count
 *   the rows differently from the view it replaces;
 * - **the entries** come off `GroupOverlay`, built once from the dictionary and fed to both sides.
 *
 * What is left for this file is drawing, and the drawing is the point: a party that arrived out of order is
 * *one violet thing that crossed*, not six red rows, and no amount of correct engine output says that.
 */
@Composable
fun DiffSurface(
    session: ReconcileSession,
    modifier: Modifier = Modifier,
    crumb: String = "",
    onSave: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    /** Save, and run the scenario again — the third click of the daily loop, and the one that proves the fix. */
    onSaveAndRerun: (() -> Unit)? = null,
    /** A run is in flight. The shared run slot allows exactly one, so the button says so rather than failing. */
    runInFlight: Boolean = false,
    /** The tag the author clicked in the message viewer — the body scrolls to its row. */
    focusTag: Int? = null,
) {
    val model = session.model
    val focusRequester = remember { FocusRequester() }
    var dragging by remember { mutableStateOf<Dragging?>(null) }
    // Whether the SURFACE holds the focus, or a value field has taken it. The bare keys hang on this: see
    // `onBareKey`, where the mechanism that looked sufficient turned out not to be.
    var focused by remember { mutableStateOf(false) }

    // The surface holds focus so the bare keys work at all; a click on any row takes it back from a value
    // field, which is what makes ↑/↓ and n/p work again the moment the author stops typing.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .border(1.dp, AppTheme.Colors.border)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { session.onModifiedKey(it) }
                .onKeyEvent { session.onBareKey(it, focused, dragging != null) { dragging = null } }
                .testTag("diff-surface"),
    ) {
        DiffHeader(session, model, crumb, onSave, onCancel, onSaveAndRerun, runInFlight)
        VerdictLine(model, session.reference.provenance)
        // The engine knows exactly why it is not offering a move, and it used to keep that to itself. An
        // author looking at a group full of red rows with no re-order on offer concludes — reasonably — that
        // re-ordering was never built. That is what happened. Now it says.
        model.withheldMove?.let { WhyNoMove(it) }
        // A move the engine refused. The drag says it at the cursor; `alt+↑/↓` has no cursor, so it says it
        // here — and either way it is the engine's sentence, which names the assertion the move would have
        // quietly re-aimed. A refused action says why. It does not simply fail to happen.
        session.refusal?.let { RefusedMove(it) { session.clearRefusal() } }
        ColumnHeaders(session.reference.label)
        DiffBody(session, model, focusTag, dragging, focusRequester) { dragging = it }
        DiffFooter(session)
    }
}

// ------------------------------------------------------------------------------------------- the header

@Composable
private fun DiffHeader(
    session: ReconcileSession,
    model: DiffModel,
    crumb: String,
    onSave: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onSaveAndRerun: (() -> Unit)? = null,
    runInFlight: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (crumb.isNotBlank()) {
                Text(crumb, color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
            // The mode chip. It is an EDIT — staged, undoable, saved — and not a read-only preview: with only
            // STRICT and OPEN registered, a preview you cannot save is strictly worse than an edit you can
            // undo. The semantics is DERIVED from the mode, so the chip and the scenario cannot disagree.
            val strict = session.draft.mode == MatchMode.STRICT
            Chip(
                label = session.semantics.label,
                color = AppTheme.Colors.warning,
                testTag = "diff-mode",
                onClick = { session.apply(EditOp.setMode(if (strict) MatchMode.OPEN else MatchMode.STRICT)) },
            )
            Chip(
                label = session.reference.label,
                color = AppTheme.Colors.info,
                testTag = "diff-reference",
            )
            if (model.verdict.needsAttention) Chip("failed", AppTheme.Colors.error, tinted = true, testTag = "diff-failed")
        }
        Spacer(Modifier.width(0.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            // The visible half of `n`/`p`. A keystroke nothing on screen mentions is a keystroke nobody finds,
            // and these call exactly the functions the keys call — one decider, two doors.
            SlimButton(
                "↑ prev",
                onClick = session::prevChunk,
                color = AppTheme.Colors.textSecondary,
                enabled = model.diffChunks.isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp).testTag("diff-prev-chunk"),
            )
            SlimButton(
                "↓ next diff",
                onClick = session::nextChunk,
                color = AppTheme.Colors.textSecondary,
                enabled = model.diffChunks.isNotEmpty(),
                modifier = Modifier.padding(end = 6.dp).testTag("diff-next-chunk"),
            )
            SlimButton(
                "⟲ undo",
                onClick = session::undo,
                color = AppTheme.Colors.textSecondary,
                enabled = session.canUndo,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-undo"),
            )
            SlimButton(
                "⟳ redo",
                onClick = session::redo,
                color = AppTheme.Colors.textSecondary,
                enabled = session.canRedo,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-redo"),
            )
            Box(Modifier.weight(1f))
            // Shape churn is the common case and it is tedious, not interesting. This takes the reorder, the
            // tags the venue added and the ones it stopped sending — and never a value mismatch, because
            // those are the rows that mean something.
            if (model.canAcceptShape) {
                SlimButton(
                    "Accept all shape changes",
                    onClick = { session.apply(EditOp.acceptAllShape(session.reference.view, session.dictionary)) },
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-accept-shape"),
                )
            }
            SlimButton(
                "Re-seed from reference",
                onClick = { session.apply(EditOp.reseed(session.reference.view, session.dictionary)) },
                color = AppTheme.Colors.textSecondary,
                modifier = Modifier.padding(end = 6.dp).testTag("diff-reseed"),
            )
            onCancel?.let {
                SlimButton(
                    "Cancel",
                    onClick = it,
                    color = AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-cancel"),
                )
            }
            onSave?.let {
                SlimButton(
                    "Save",
                    onClick = it,
                    color = AppTheme.Colors.primary,
                    enabled = session.isDirty,
                    modifier = Modifier.padding(end = 6.dp).testTag("diff-save"),
                )
            }
            onSaveAndRerun?.let {
                // Enabled on a clean step too: re-running a step you have not touched is how you find out
                // whether the venue has settled down, and that is a question worth being able to ask.
                SlimButton(
                    if (runInFlight) "Running…" else "Save & re-run",
                    onClick = it,
                    color = AppTheme.Colors.success,
                    enabled = !runInFlight,
                    modifier = Modifier.testTag("diff-save-rerun"),
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, tinted: Boolean = false, testTag: String = "", onClick: (() -> Unit)? = null) {
    val box =
        Modifier
            .padding(end = 6.dp)
            .border(1.dp, color)
            .background(if (tinted) AppTheme.Colors.notificationErrorBackground else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    Box(modifier = (if (onClick != null) box.clickable(onClick = onClick) else box).testTag(testTag)) {
        Text(label.uppercase(), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * **Shape versus behaviour** — the one line a reader must never have to work out for themselves, and it is
 * not counted here. [com.knapsack.fixtool.service.compare.Verdict] counts it, for this surface and the one it
 * replaces, so the two can never come to disagree about how many rows are red.
 */
@Composable
private fun VerdictLine(model: DiffModel, provenance: ReferenceMessage.Provenance) {
    val v = model.verdict
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (v.needsAttention) AppTheme.Colors.notificationErrorBackground else AppTheme.Colors.surfaceVariant,
                ).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            // Against a SECOND_INSTANCE a red row is an over-specified assertion, not a venue regression —
            // and the sentence has to say which, or the author goes hunting a bug that does not exist.
            text = v.headlineAgainst(provenance),
            color =
                when {
                    v.needsAttention -> AppTheme.Colors.error
                    v.assertsNothing -> AppTheme.Colors.warning
                    else -> AppTheme.Colors.success
                },
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.testTag("diff-summary"),
        )
        if (v.parts.isNotEmpty()) {
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
            Text(v.parts.joinToString(" · "), color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
            Text(" │ ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
            Text(
                text = v.shapeVersusBehaviour,
                color = if (v.values > 0) AppTheme.Colors.error else AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.testTag("diff-shape-or-behaviour"),
            )
        }
    }
}

@Composable
private fun WhyNoMove(why: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.notificationInfoBackground).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("⇄  ", color = AppTheme.Colors.warning, fontSize = 11.sp)
        Text(why, color = AppTheme.Colors.warning, fontSize = 11.sp, modifier = Modifier.testTag("diff-no-move"))
    }
}

/**
 * A move the engine refused, in the engine's own words — the sentence `EditOp` used to compute and throw
 * away, because its apply returned `null` for *"refused"* and *"nothing changed"* alike (M1).
 */
@Composable
private fun RefusedMove(why: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.notificationErrorBackground)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("✕  ", color = AppTheme.Colors.error, fontSize = 11.sp)
        Text(why, color = AppTheme.Colors.error, fontSize = 11.sp, modifier = Modifier.weight(1f).testTag("diff-refused-move"))
        SlimButton("esc", onClick = onDismiss, color = AppTheme.Colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ColumnHeaders(referenceLabel: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 3.dp)) {
        Header("EXPECTATION (EDITABLE)", Modifier.weight(LEFT_WEIGHT))
        Spacer(Modifier.width(GUTTER))
        Header("RECEIVED — ${referenceLabel.uppercase()}", Modifier.weight(1f))
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

// --------------------------------------------------------------------------------------------- the body

internal val GUTTER = 56.dp
internal val ROW_PADDING = 12.dp
private val TAG_COL = 46.dp

// The left column is the EDITABLE one — a chip, a value field, sometimes a tolerance — and the right is read-
// only text that can be ellipsized without losing anything. Splitting the width evenly starved the side that
// has controls in it, and at a narrow window the matcher editor's own labels wrapped one character per line.
private val NAME_COL = 96.dp
internal const val LEFT_WEIGHT = 1.25f

/**
 * A **lazy** list, and it has to be: the row-level deep link scrolls to the row the author clicked in the
 * message viewer, and a `Column(verticalScroll)` has no idea where its rows are. It is also what makes a
 * 200-field market-data snapshot cheap to draw, and what `n`/`p` steer.
 *
 * (It is only *possible* because the diff is a document of its own. Inside the step editor's detail pane —
 * itself a `verticalScroll` — a lazy list is measured with infinite height, which is not a layout, it is a
 * crash waiting for a long message.)
 *
 * The list is overlaid, in one `Box`, by the three things that cannot live inside a lazy item because they
 * span several of them: the **violet crossing connector** between moved entries, the insertion line a drag
 * previews its landing with, and the tooltip that answers *would every row pass here?* before the mouse is
 * released.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DiffBody(
    session: ReconcileSession,
    model: DiffModel,
    focusTag: Int?,
    dragging: Dragging?,
    focusRequester: FocusRequester,
    onDrag: (Dragging?) -> Unit,
) {
    val items = model.items
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The tag the author clicked, in the viewer, on the message that failed. Land on it.
    LaunchedEffect(focusTag, items.size) {
        if (focusTag == null) return@LaunchedEffect
        val at = items.indexOfFirst { it is DiffItem.Line && it.line.row.tag == focusTag }
        if (at >= 0) listState.scrollToItem(at)
    }

    // Keep the selection on screen when the KEYBOARD moved it — and only then. Scrolling because the author
    // clicked a row they can already see would yank the page out from under them.
    LaunchedEffect(session.selection) {
        val at = model.indexOf(session.selection) ?: return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == at }) listState.scrollToItem(at)
    }

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items.size) { index ->
                val item = items[index]
                val selected = session.selection != null && item.selection == session.selection
                val select = {
                    session.selection = item.selection
                    runCatching { focusRequester.requestFocus() } // back from a value field: the bare keys work again
                    Unit
                }
                // Rebuilt on every recomposition, and read through `rememberUpdatedState` inside the handle —
                // a `pointerInput` block captures its lambdas once, and a captured `dragging` would be the one
                // from the frame the gesture started on, for the whole of the gesture.
                val handlers =
                    DragHandlers(
                        start = {
                            val origin = item.selection
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            if (origin != null && info != null) {
                                session.clearRefusal()
                                val y = info.offset + info.size / 2f
                                onDrag(Dragging(origin, y).aimedAt(session, model, listState, y))
                            }
                        },
                        by = { dy -> dragging?.let { onDrag(it.aimedAt(session, model, listState, it.pointerY + dy)) } },
                        // **The drop is the only thing that applies, and it applies what the engine approved.**
                        // The tooltip already asked; this is the same op, put to the same validator. A refusal
                        // sets `session.refusal`, the draft is untouched, and the row snaps back — which is
                        // simply what happens when nothing changed.
                        drop = {
                            dragging?.landing?.let { session.apply(it.op) }
                            onDrag(null)
                        },
                        cancel = { onDrag(null) },
                    )
                when (item) {
                    is DiffItem.Band -> EntryBand(session, model, item, selected, select, handlers)
                    is DiffItem.Line -> DiffRow(session, item.line, item.depth, selected, select, handlers)
                }
            }
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            drawMoveConnectors(model, listState, density)
            dragging?.let { drawDropLine(it, density) }
        }
        dragging?.let { DragTooltip(it, density) }
    }
}

/**
 * The entry header — `NoPartyIDs · entry 1 — FIRMA · 1 ExecutingFirm`, and its counterpart on the right.
 *
 * The label is not built here. [com.knapsack.fixtool.service.compare.GroupOverlay] computes it, from the
 * dictionary, for both sides — so the expectation's side and the message's side cannot come to describe the
 * same entry differently, which is the seam that produced the defect the sequence model was built to end.
 */
@Composable
private fun EntryBand(
    session: ReconcileSession,
    model: DiffModel,
    band: DiffItem.Band,
    selected: Boolean,
    onSelect: () -> Unit,
    handlers: DragHandlers,
) {
    val offerOrder = band.first
    val border = if (band.moved) DiffPalette.movedBorder else DiffPalette.entryBorder(band.hue)
    val fill =
        when {
            selected -> DiffPalette.selectedRow
            band.moved -> DiffPalette.movedBand
            else -> DiffPalette.entryBand(band.hue)
        }
    val siblings = model.overlay.siblingsOf(band.entry)
    val slot = siblings.indexOf(band.entry)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    fill,
                ).selectable(onSelect)
                .padding(start = (band.depth * 10).dp)
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag(if (band.moved) "moved-band" else "entry-band"),
    ) {
        Row(modifier = Modifier.weight(LEFT_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // The band's grip drags the whole entry — the unit a venue actually moves. Every row of it
            // travels, which is what makes the occurrence mapping survive the crossing (D1).
            DragHandle(handlers, testTag = "entry-handle-${band.entry.rows.first}", modifier = Modifier.padding(end = 3.dp))
            Text("▏", color = border, fontSize = 11.sp)
            Text(
                band.entry.label.ifBlank {
                    "entry ${band.entry.entryIndex + 1}"
                },
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp).testTag("entry-label"),
            )
            // The dictionary did not know this group, so its boundaries are a guess from the row order — and
            // the reader is entitled to know that before they drag anything by them.
            if (band.entry.source == EntrySource.HEURISTIC) {
                Text("  guessed", color = AppTheme.Colors.warning, fontSize = 9.sp, modifier = Modifier.testTag("entry-guessed"))
            }
            Box(Modifier.weight(1f))
            // ↑/↓ ship now: the surface this replaces has them, and a replacement that can do less is not one.
            // The drag arrives in Phase 4; both route through the same engine validator.
            EntryArrow("↑", enabled = slot > 0, testTag = "entry-up") { session.apply(EditOp.moveEntry(model.overlay, band.entry.rows, slot - 1)) }
            EntryArrow("↓", enabled = slot in 0 until siblings.lastIndex, testTag = "entry-down") {
                session.apply(
                    EditOp.moveEntry(
                        model.overlay,
                        band.entry.rows,
                        slot + 1,
                    ),
                )
            }
        }
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) {
            if (offerOrder && model.acceptOrder != null) {
                Box(
                    modifier =
                        Modifier
                            .border(1.dp, DiffPalette.moved)
                            .background(AppTheme.Colors.surface)
                            .clickable {
                                session.apply(model.acceptOrder)
                            }.padding(horizontal = 4.dp)
                            .testTag("accept-new-order"),
                ) {
                    Text("⇄", color = DiffPalette.moved, fontSize = 11.sp)
                }
            }
        }
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("▏", color = border, fontSize = 11.sp)
            Text(band.rightLabel ?: "—", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
            if (band.moved) {
                Box(Modifier.weight(1f))
                Text(
                    "⇅ moved — same tags, same values, different position",
                    color = DiffPalette.moved,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("moved-note"),
                )
            }
        }
    }
}

@Composable
private fun EntryArrow(glyph: String, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    SlimButton(
        glyph,
        onClick = onClick,
        color = AppTheme.Colors.textSecondary,
        enabled = enabled,
        modifier = Modifier.padding(start = 2.dp).testTag(testTag),
    )
}

// ---------------------------------------------------------------------------------------------- a row

@Composable
private fun DiffRow(
    session: ReconcileSession,
    line: DiffLine,
    depth: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    handlers: DragHandlers,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) DiffPalette.selectedRow else rowTint(line))
                .selectable(onSelect)
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag("diff-row"),
    ) {
        Box(modifier = Modifier.weight(LEFT_WEIGHT)) { LeftCell(session, line, depth, handlers) }
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) { Gutter(session, line) }
        Box(modifier = Modifier.weight(1f)) { RightCell(line) }
    }
}

private fun rowTint(line: DiffLine): Color =
    when {
        line.unjudged -> DiffPalette.unjudgedRow
        line.kind == ChunkKind.MOVED -> DiffPalette.movedBand
        line.kind == ChunkKind.VALUE -> DiffPalette.valueRow
        line.kind == ChunkKind.RIGHT_ONLY -> DiffPalette.addedRow
        line.kind == ChunkKind.LEFT_ONLY -> DiffPalette.missingRow
        else -> Color.Transparent
    }

/** The expectation. The matcher chip **is** the value column — there is no separate value cell to disagree. */
@Composable
private fun LeftCell(session: ReconcileSession, line: DiffLine, depth: Int, handlers: DragHandlers) {
    if (line.leftIsGap) {
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap).padding(start = (depth * 10).dp)) {
            Text("not asserted", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontStyle = FontStyle.Italic)
        }
        return
    }
    val row = line.row
    val index = row.index
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (depth * 10).dp)) {
        // Every row of the expectation is draggable, and every drop is put to the engine. A row that may not
        // go where it was dropped is refused *there*, with the reason at the cursor — not by a handle that
        // was never drawn, which would leave the author to work out for themselves that it could not be done.
        if (index != null) {
            DragHandle(handlers, testTag = "row-handle-$index", modifier = Modifier.padding(end = 3.dp))
        }
        TagCell(row.tag, row.occurrence, line.kind)
        Text(row.name, color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(NAME_COL))
        if (index != null && row.matcher != null) {
            MatcherEditor(
                matcher = row.matcher,
                capturedValue = line.right?.value ?: row.actual ?: "",
                // `reference` is not offered. A dropdown-seeded `${out.D.11}` on a failing row makes that row
                // unjudgeable, drops it out of every count, and the verdict then announces that every
                // assertion would now pass. Reference rows are made at capture, where the binding is real.
                types = DIFF_MATCHERS,
                onChange = { session.apply(EditOp.setMatcher(index, row.tag, it)) },
                modifier = Modifier.testTag("matcher-${row.tag}-${row.occurrence}"),
            )
        }
    }
}

/** The reference. Read-only, in the venue's own wire order, with the dictionary's word for the value beside it. */
@Composable
private fun RightCell(line: DiffLine) {
    val field = line.right
    if (field == null) {
        // **"not sent" is a claim about the venue, and it has to be true.** A row the reply carries in
        // *another position* is TagStatus.MOVED — the engine says so, in those words — and rendering its gap
        // as "not sent" accuses the venue of dropping a field that is sitting two lines away on the same
        // screen. The author then goes hunting a regression that does not exist, which is the failure this
        // area keeps producing. (Found by looking at the picture. No assertion had a word to say about it.)
        val elsewhere = line.row.status == TagStatus.MOVED
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap)) {
            Text(
                if (elsewhere) "present in the reply — but not in this position" else "not sent",
                color = if (elsewhere) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.testTag(if (elsewhere) "present-elsewhere" else "not-sent"),
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TagCell(field.tag, field.occurrence, line.kind)
        Text(field.name, color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(NAME_COL))
        Text(
            field.value,
            color =
                if (line.kind ==
                    ChunkKind.VALUE
                ) {
                    AppTheme.Colors.error
                } else if (line.kind == ChunkKind.MOVED) {
                    DiffPalette.moved
                } else {
                    AppTheme.Colors.fieldValue
                },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = if (line.kind == ChunkKind.VALUE) FontWeight.Bold else FontWeight.Normal,
        )
        // The dictionary's word for the value, as its own dim span — never folded into the value string,
        // which is a message the venue never sent.
        field.description?.takeIf { it != field.value }?.let {
            Text("  $it", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
        }
        if (line.unjudged) {
            Text(
                "  unjudged here — resolves at run time",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.testTag("unjudged-note"),
            )
        }
    }
}

@Composable
private fun TagCell(tag: Int, occurrence: Int, kind: ChunkKind) {
    Row(modifier = Modifier.width(TAG_COL)) {
        Text(
            "$tag",
            color =
                if (kind ==
                    ChunkKind.MOVED
                ) {
                    DiffPalette.moved
                } else {
                    AppTheme.Colors.tagNumber
                },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        // The occurrence, in the tag column, because it is part of the address: the second 452 is not the
        // first one, and a reader who cannot see which is which cannot reason about a move at all.
        if (occurrence > 0) {
            Text("#${occurrence + 1}", color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

/** The gutter. It draws what the session offers, and it never invents an offer of its own. */
@Composable
private fun Gutter(session: ReconcileSession, line: DiffLine) {
    if (line.offers.isEmpty()) {
        // A moved row gets no glyph at all. It *passes* where the engine has it paired — FIRMA's `447` is
        // still `D` — so a tick would be literally true and completely misleading: it would say "this row is
        // fine" inside a band that says the entry it belongs to is in the wrong place. The band carries the
        // meaning, and the gutter does not argue with it.
        if (line.kind == ChunkKind.MOVED) return
        val glyph =
            if (line.unjudged) {
                "◌"
            } else if (line.row.passed && !line.row.unasserted) {
                "✓"
            } else {
                ""
            }
        val colour = if (line.unjudged) AppTheme.Colors.warning else AppTheme.Colors.success.copy(alpha = 0.55f)
        if (glyph.isNotBlank()) Text(glyph, color = colour, fontSize = 10.sp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        line.offers.forEach { offer ->
            SlimButton(
                offer.glyph,
                onClick = { session.apply(offer.op) },
                color =
                    when (offer.kind) {
                        OfferKind.DROP -> AppTheme.Colors.error
                        OfferKind.ASSERT_ABSENT -> AppTheme.Colors.textSecondary
                        else -> AppTheme.Colors.info
                    },
                modifier = Modifier.testTag("${offer.kind.name.lowercase()}-${line.row.tag}-${line.row.occurrence}"),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------- the footer

@Composable
private fun DiffFooter(session: ReconcileSession) {
    val staged = session.staged
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("$staged", color = AppTheme.Colors.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("diff-staged"))
        Text(
            if (staged == 1) " edit staged" else " edits staged",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
        )
        // Verbatim, and it is a promise: the scenario file is not touched until Save.
        Text(
            " · nothing is written to the scenario until you save",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.testTag("diff-promise"),
        )
        if (staged > 0) {
            Text(
                " — " + session.stagedLabels.joinToString(" · "),
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier = Modifier.testTag("diff-staged-labels"),
            )
        }
    }
}

/**
 * The chip's vocabulary: everything the matcher language has, **except `reference`**.
 *
 * A reference row is made at capture, where `${id0}` is bound to something real across steps. Offering it in
 * a dropdown lets an author seed one on a failing OrdStatus row, which makes that row unjudgeable, drops it
 * out of every count, and leaves the verdict announcing that every assertion would now pass. A row that
 * already *is* a reference still says so on its chip; nothing can switch *to* one here.
 */
private val DIFF_MATCHERS = MATCHER_TYPES.filter { it != "reference" }
