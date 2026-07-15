// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.ui.AppTheme
import kotlin.math.roundToInt

/**
 * **A drag in flight — and it is the one piece of Phase 4's state that belongs in the composable.**
 *
 * Everything else this phase added is hoisted into the session, because only the active document is composed
 * and a `remember` is destroyed by a glance at the session grid (trap 5). A drag is the exception, and not
 * grudgingly: it *cannot* survive the tab being switched away from, because the pointer cannot. It dies with
 * the subtree, which is exactly right.
 *
 * [origin] is what was picked up — a [DiffSelection.Row] or a [DiffSelection.Entry], the same vocabulary the
 * keyboard moves speak, so the two paths cannot come to disagree about what a move's unit is.
 */
internal data class Dragging(
    val origin: DiffSelection,
    /** Where the pointer is, in the body's own coordinates. The tooltip follows it; the landing does not. */
    val pointerY: Float,
    /**
     * **What landing this is** — the target index, and nothing else.
     *
     * The tooltip's answer is a full re-judge of the whole expectation (`ReconcileSession.verdictOf`), and a
     * drag fires sixty times a second. So the answer is cached against this, and recomputed only when the
     * pointer crosses into a different landing. Nothing would *fail* if it were per-pixel; it would merely be
     * slow, for a reason nobody would ever find.
     */
    val key: Int? = null,
    val landing: Landing? = null,
)

/** Where the drop would go, what it would do, and what to say about it before the mouse is released. */
internal data class Landing(
    val op: EditOp,
    /** Where the insertion line is drawn, in the body's coordinates. */
    val y: Float,
    val tip: DragTip,
)

/**
 * **The only question a drag has to answer**: *would every row pass here?*
 *
 * Three sentences, and the mockup draws two of them. The third is the one the author will meet constantly —
 * the move is perfectly legal and the step **still fails**, because a value is wrong somewhere else. Saying
 * *"every row would pass"* there is a lie, and saying nothing leaves the tooltip's own question unanswered at
 * the exact moment it is interesting.
 */
internal data class DragTip(val ok: Boolean, val text: String) {
    companion object {
        fun of(attention: Int): DragTip =
            when (attention) {
                0 -> DragTip(true, "drop here — every row would pass ✓")
                1 -> DragTip(true, "drop here — 1 row would still need attention")
                else -> DragTip(true, "drop here — $attention rows would still need attention")
            }

        /** The engine's own sentence, at the cursor. Never a paraphrase of it. */
        fun refused(why: String) = DragTip(false, why)
    }
}

/**
 * **The gutter's horizontal band, computed the way the rows compute it.**
 *
 * The connector is drawn on a `Canvas` overlaying the whole body, so it has to place itself in the same
 * geometry `DiffRow` lays out with — one weighted column, a fixed gutter, another weighted column, inside the
 * body's horizontal padding. Two copies of that arithmetic would drift the moment either changed, so there is
 * one, and both call it.
 */
internal fun gutterBand(widthPx: Float, density: Density): ClosedFloatingPointRange<Float> {
    val pad = with(density) { ROW_PADDING.toPx() }
    val gutter = with(density) { GUTTER.toPx() }
    val content = (widthPx - 2 * pad).coerceAtLeast(gutter)
    val left = (content - gutter) * (LEFT_WEIGHT / (LEFT_WEIGHT + 1f))
    val start = pad + left
    return start..(start + gutter)
}

/** The vertical middle of a block of items, in the body's coordinates — or null if none of it is on screen. */
private fun centreOf(state: LazyListState, items: List<Int>): Float? {
    val visible = state.layoutInfo.visibleItemsInfo.filter { it.index in items }
    if (visible.isEmpty()) return null
    val top = visible.minOf { it.offset }
    val bottom = visible.maxOf { it.offset + it.size }
    return (top + bottom) / 2f
}

/**
 * **The violet crossing** — one curve per moved chunk, from where its rows are *listed* to where they
 * actually *are*.
 *
 * It is drawn from [com.knapsack.fixtool.service.compare.Chunk.landing], never from `moveLink`. A move is a
 * **function**, not a pairing: chunk *k*'s rows went to span *L(k)*. For two entries that traded places the
 * two happen to describe the same fact, and `moveLink` would do — but for a three-way rotation `moveLink` is
 * the *inverse* edge, and a surface drawn from it would draw the rotation backwards. Three entries rotating
 * draw three curves that cycle, and they are right.
 *
 * An end that is scrolled off the viewport is drawn *leaving* the viewport, rather than not drawn at all: the
 * entry is off-screen, and a line that goes off-screen says so.
 */
internal fun DrawScope.drawMoveConnectors(model: DiffModel, state: LazyListState, density: Density) {
    val band = gutterBand(size.width, density)
    val stroke = with(density) { 1.5.dp.toPx() }
    val mid = (band.start + band.endInclusive) / 2f

    model.chunks
        .filter { it.kind == ChunkKind.MOVED && it.landing.isNotEmpty() }
        .forEach { chunk ->
            val from = model.itemsOfChunk(chunk.id)
            val ySrc = centreOf(state, from) ?: return@forEach
            val facing = model.itemFacingWire(chunk.landing.first()) ?: return@forEach
            val landedChunk = (model.items.getOrNull(facing) as? DiffItem.Line)?.line?.chunkId ?: return@forEach
            val to = model.itemsOfChunk(landedChunk)
            // Off-screen ends leave the viewport in the direction the entry actually went.
            val yDst = centreOf(state, to) ?: if (facing > from.first()) size.height + stroke * 8 else -stroke * 8

            val path =
                Path().apply {
                    moveTo(band.start, ySrc)
                    cubicTo(mid, ySrc, mid, yDst, band.endInclusive, yDst)
                }
            drawPath(path, color = DiffPalette.moved, style = Stroke(width = stroke))
            drawCircle(DiffPalette.moved, radius = stroke, center = Offset(band.start, ySrc))
        }
}

/** The insertion line: where the row would land, previewed before the mouse is released. */
internal fun DrawScope.drawDropLine(drag: Dragging, density: Density) {
    val landing = drag.landing ?: return
    val pad = with(density) { ROW_PADDING.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val colour = if (landing.tip.ok) AppTheme.Colors.success else AppTheme.Colors.error
    drawLine(
        color = colour,
        start = Offset(pad, landing.y),
        end = Offset(size.width - pad, landing.y),
        strokeWidth = stroke,
    )
    drawCircle(colour, radius = stroke * 1.5f, center = Offset(pad, landing.y))
}

/**
 * The tooltip, at the cursor. Green when the drop would land, red with the **engine's own refusal sentence**
 * when it would not — the sentence that names the assertion the move would have quietly re-aimed.
 */
@Composable
internal fun DragTooltip(drag: Dragging, density: Density) {
    val tip = drag.landing?.tip ?: return
    val colour = if (tip.ok) AppTheme.Colors.success else AppTheme.Colors.error
    Box(
        modifier =
            Modifier
                .offset { IntOffset(with(density) { TOOLTIP_INSET.toPx() }.roundToInt(), (drag.pointerY + 12f).roundToInt()) }
                // Capped, or the refusal — which is a paragraph, because it names the assertion that would
                // have broken — spans the window and covers the entry it is about.
                .widthIn(max = 380.dp)
                .border(1.dp, colour)
                .background(AppTheme.Colors.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(if (tip.ok) "drag-tip-ok" else "drag-tip-refused"),
    ) {
        Text(tip.text, color = colour, fontSize = 10.sp)
    }
}

private val TOOLTIP_INSET = 220.dp

/**
 * **Click a row to select it — without swallowing the row.**
 *
 * `Modifier.clickable` sets `mergeDescendants`, which folds every child of the row into one semantics node.
 * On a row whose left half is a *live matcher editor*, that is not a test inconvenience: it makes the value
 * field, the chip and the gutter buttons unreachable to the semantics tree, which is what a screen reader
 * reads and what a UI test drives. (It was found by the tests going red, and it would have been found by a
 * blind user going nowhere.)
 *
 * A tap gesture carries no semantics and merges nothing. A child that consumes the press — a text field, a
 * gutter button — keeps it, so clicking into a value does not also move the selection, and clicking anywhere
 * else on the row does.
 */
internal fun Modifier.selectable(onSelect: () -> Unit): Modifier =
    this.pointerInput(onSelect) { detectTapGestures { onSelect() } }

// ------------------------------------------------------------------------------------------ the handle

/** What a drag does, from the row that started it. See [DragHandle] for why these are passed rather than read. */
internal class DragHandlers(
    val start: () -> Unit,
    val by: (Float) -> Unit,
    val drop: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * **The grip.** `⠿`, always drawn, brightening on hover — it never *appears* on hover.
 *
 * A handle that materialises under the pointer reflows the row it is in, which moves the drop target the
 * author is aiming at, underneath the pointer that is aiming at it. So it occupies its cell from the start
 * and only its colour changes, and a test can drag it without first having to hover it.
 */
@Composable
internal fun DragHandle(handlers: DragHandlers, testTag: String, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // `pointerInput` captures its lambdas for the life of the gesture; the handlers are rebuilt every frame.
    // Read through this and the gesture always calls the current ones, with the current drag state behind them.
    val current by rememberUpdatedState(handlers)

    Text(
        "⠿",
        color = if (hovered) AppTheme.Colors.info else AppTheme.Colors.textDisabled,
        fontSize = 10.sp,
        modifier =
            modifier
                .hoverable(interaction)
                .testTag(testTag)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current.start() },
                        onDrag = { change, amount ->
                            change.consume()
                            current.by(amount.y)
                        },
                        onDragEnd = { current.drop() },
                        onDragCancel = { current.cancel() },
                    )
                },
    )
}

// ---------------------------------------------------------------------------------------- the keyboard

/**
 * **The keys that belong to the document, taken before anything else sees them.**
 *
 * `⌘Z`, `⌘⇧Z` and `alt+↑/↓` are *captured* (`onPreviewKeyEvent`), and that is not a stylistic choice.
 * Compose's text field carries **its own undo stack**, so leaving `⌘Z` to bubble would have an author editing
 * a value undo *characters* out of the field while the diff's snapshot stack — the thing the footer is
 * counting, the thing that promises nothing is written until save — sits perfectly still. Two undo stacks
 * over one state is the defect P2 refused to build; it must not arrive through the keyboard instead.
 */
internal fun ReconcileSession.onModifiedKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    val command = event.isMetaPressed || event.isCtrlPressed
    val arrow = event.key == Key.DirectionUp || event.key == Key.DirectionDown
    return when {
        command && event.key == Key.Z -> true.also { if (event.isShiftPressed) redo() else undo() }
        event.isAltPressed && arrow -> true.also { moveSelection(down = event.key == Key.DirectionDown) }
        else -> false
    }
}

/**
 * **The bare keys — and they are the surface's only while the surface has the focus.**
 *
 * The obvious guard is the wrong one, and a test caught it. *"Let them bubble (`onKeyEvent`), and a focused
 * text field will have consumed its own characters first"* is true on Android and **false on the desktop**:
 * Compose's text field takes a printable character from the separate `KEY_TYPED` event, and leaves the
 * `KeyDown` for `N` entirely unconsumed. It bubbles, cleanly, straight into `nextChunk()`. So an author
 * typing the word *"not"* into a value would have been thrown down the diff, twice, and the letter would
 * still have arrived — a shortcut that eats the alphabet without even taking it away.
 *
 * So [focused] is the guard: `isFocused` on the surface's own node, which is false the moment a value field
 * takes over. Bubbling still matters — it is what leaves the arrow keys to a text field's cursor — but it is
 * not enough on its own, and the reasoning that said it was is recorded here so it is not re-derived.
 *
 * `esc` is exempt, and it is a **stack**. `App.kt` binds it to *"close the focused document; a dirty one
 * confirms"* (T8), and that meaning survives: an `esc` while something is *in flight* is about the thing in
 * flight, wherever the focus happens to be, and only when nothing is does it mean the tab.
 */
internal fun ReconcileSession.onBareKey(
    event: KeyEvent,
    focused: Boolean,
    dragging: Boolean,
    cancelDrag: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return false
    return when {
        event.key == Key.Escape -> escaped(dragging, cancelDrag)
        // A value field is typing; these are its letters, not our shortcuts.
        !focused -> false
        else -> navigate(event.key)
    }
}

/** The `esc` stack: the drag first, then a refusal still on screen, then nothing — and the diff window closes. */
private fun ReconcileSession.escaped(dragging: Boolean, cancelDrag: () -> Unit): Boolean =
    when {
        dragging -> true.also { cancelDrag() }
        refusal != null -> true.also { clearRefusal() }
        else -> false
    }

private fun ReconcileSession.navigate(key: Key): Boolean =
    when (key) {
        Key.DirectionUp -> true.also { selectPrev() }
        Key.DirectionDown -> true.also { selectNext() }
        Key.N -> true.also { nextChunk() }
        Key.P -> true.also { prevChunk() }
        else -> false
    }

// ------------------------------------------------------------------------------------ where a drop lands

/** One candidate landing: a row of the body, and where it sits on screen. */
private data class Slot(val key: Int, val top: Float, val bottom: Float) {
    val centre: Float get() = (top + bottom) / 2f
}

/**
 * **Re-aim a drag at the pointer** — and ask the engine what it thinks, but only when the landing changes.
 *
 * The pointer moves continuously; the landing does not. So the cheap thing (following the cursor) happens on
 * every event and the expensive thing (a full re-judge of the expectation as it would be after the drop)
 * happens when the answer could actually be different. See [Dragging.key].
 */
@Suppress("ReturnCount") // One exit per thing that can be true of a landing; nesting them reads far worse.
internal fun Dragging.aimedAt(session: ReconcileSession, model: DiffModel, state: LazyListState, y: Float): Dragging {
    val target =
        when (origin) {
            is DiffSelection.Row -> rowTarget(model, state, origin.index, y)
            is DiffSelection.Entry -> entryTarget(model, state, origin.rows, y)
            is DiffSelection.Added -> null // not a row of the expectation: there is nothing to pick up
        }
    if (target == null) return copy(pointerY = y, key = null, landing = null)
    if (target.first == key && landing != null) return copy(pointerY = y)

    val (to, dropY) = target
    val op =
        when (origin) {
            is DiffSelection.Row -> EditOp.moveRow(model.overlay, origin.index, to)
            is DiffSelection.Entry -> EditOp.moveEntry(model.overlay, origin.rows, to)
            is DiffSelection.Added -> return copy(pointerY = y, key = null, landing = null)
        }
    // The dry run and the drop are the SAME op, asked of the same validator. A tooltip that answered from a
    // second implementation of the rule would eventually promise a landing the drop then refused.
    val tip =
        when (val preview = session.preview(op)) {
            is EditResult.Refused -> DragTip.refused(preview.why)
            is EditResult.Applied -> DragTip.of(session.verdictOf(preview.expectation).attention)
            EditResult.Unchanged -> return copy(pointerY = y, key = to, landing = null) // it is already there
        }
    return copy(pointerY = y, key = to, landing = Landing(op, dropY, tip))
}

/** The draft index a row would move to, and where to draw the line. Null when there is nothing under the pointer. */
@Suppress("ReturnCount") // One exit per reason there is no landing here.
private fun rowTarget(model: DiffModel, state: LazyListState, from: Int, y: Float): Pair<Int, Float>? {
    val slots =
        state.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val row = (model.items.getOrNull(info.index) as? DiffItem.Line)?.line?.row?.index ?: return@mapNotNull null
            Slot(row, info.offset.toFloat(), (info.offset + info.size).toFloat())
        }
    if (slots.isEmpty()) return null

    // The *gap* the row lands in, in the original list's terms: above a row, or below it.
    val (gap, dropY) =
        when {
            y < slots.first().top -> slots.first().key to slots.first().top
            y > slots.last().bottom -> (slots.last().key + 1) to slots.last().bottom
            else -> {
                val over = slots.firstOrNull { y >= it.top && y <= it.bottom } ?: return null
                if (y < over.centre) over.key to over.top else (over.key + 1) to over.bottom
            }
        }

    // `moveRow` removes first and inserts after: landing in gap g is index g when g is at or above the row,
    // and g-1 once the row itself has been taken out from underneath it. Getting this backwards puts the row
    // one place from where the author aimed, which is the kind of wrong that reads as "the drag is janky".
    val to = if (gap <= from) gap else gap - 1
    if (to == from) return null // it is already there, and a drop that does nothing says nothing
    return to to dropY
}

/** The sibling slot an entry would move to. An entry lands *among its siblings*, never between two rows. */
@Suppress("ReturnCount") // One exit per reason there is no landing here.
private fun entryTarget(model: DiffModel, state: LazyListState, rows: IntRange, y: Float): Pair<Int, Float>? {
    val entry = model.overlay.entries.firstOrNull { it.rows == rows } ?: return null
    val siblings = model.overlay.siblingsOf(entry)
    val at = siblings.indexOf(entry)
    if (at < 0) return null

    val slots =
        siblings.mapIndexedNotNull { slot, sibling ->
            val items = model.itemsOfEntry(sibling.rows)
            val visible = state.layoutInfo.visibleItemsInfo.filter { it.index in items }
            if (visible.isEmpty()) {
                null
            } else {
                Slot(slot, visible.minOf { it.offset }.toFloat(), visible.maxOf { it.offset + it.size }.toFloat())
            }
        }
    if (slots.isEmpty()) return null

    val over =
        when {
            y < slots.first().top -> slots.first()
            y > slots.last().bottom -> slots.last()
            else -> slots.firstOrNull { y >= it.top && y <= it.bottom } ?: return null
        }
    if (over.key == at) return null
    return over.key to (if (over.key < at) over.top else over.bottom)
}
