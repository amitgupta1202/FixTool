// Compose UI: dense composable calls read best on one line.
@file:Suppress("MaxLineLength", "LongParameterList")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.EntryNode
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

    Column(modifier = modifier.fillMaxWidth().border(1.dp, AppTheme.Colors.border).testTag("diff-surface")) {
        DiffHeader(session, model, crumb, onSave, onCancel, onSaveAndRerun, runInFlight)
        VerdictLine(model, session.reference.provenance)
        // The engine knows exactly why it is not offering a move, and it used to keep that to itself. An
        // author looking at a group full of red rows with no re-order on offer concludes — reasonably — that
        // re-ordering was never built. That is what happened. Now it says.
        model.withheldMove?.let { WhyNoMove(it) }
        ColumnHeaders(session.reference.label)
        DiffBody(session, model, focusTag)
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

@Composable
private fun ColumnHeaders(referenceLabel: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 3.dp)) {
        Header("EXPECTATION (EDITABLE)", Modifier.weight(1f))
        Spacer(Modifier.width(GUTTER))
        Header("RECEIVED — ${referenceLabel.uppercase()}", Modifier.weight(1f))
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

// --------------------------------------------------------------------------------------------- the body

private val GUTTER = 56.dp
private val TAG_COL = 52.dp
private val NAME_COL = 112.dp

/** What the body draws, in order: a band opens an entry, and the lines inside it are indented under it. */
private sealed interface Item {
    data class Band(
        val entry: EntryNode,
        val hue: Int,
        val depth: Int,
        val moved: Boolean,
        val rightLabel: String?,
        val first: Boolean,
    ) : Item

    data class Line(
        val line: DiffLine,
        val depth: Int,
    ) : Item
}

/**
 * A **lazy** list, and it has to be: the row-level deep link scrolls to the row the author clicked in the
 * message viewer, and a `Column(verticalScroll)` has no idea where its rows are. It is also what makes a
 * 200-field market-data snapshot cheap to draw, and what Phase 4's next/previous-chunk keys will steer.
 *
 * (It is only *possible* because the diff is a document of its own. Inside the step editor's detail pane —
 * itself a `verticalScroll` — a lazy list is measured with infinite height, which is not a layout, it is a
 * crash waiting for a long message.)
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DiffBody(session: ReconcileSession, model: DiffModel, focusTag: Int?) {
    val items = itemsOf(model)
    val listState = rememberLazyListState()

    // The tag the author clicked, in the viewer, on the message that failed. Land on it.
    LaunchedEffect(focusTag, items.size) {
        if (focusTag == null) return@LaunchedEffect
        val at = items.indexOfFirst { it is Item.Line && it.line.row.tag == focusTag }
        if (at >= 0) listState.scrollToItem(at)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(items.size) { index ->
            when (val item = items[index]) {
                is Item.Band -> {
                    // The one-click order goes on the FIRST moved band and nowhere else: `acceptNewOrder`
                    // rewrites the whole expectation atomically, so a second button would be the same button
                    // pretending to be about a different entry. Which band that is cannot be counted *while*
                    // drawing any more — a lazy list composes what it likes, in whatever order it likes — so
                    // `Item.Band.first` says, decided once, where the items were built.
                    val offerOrder = item.first
                    EntryBand(session, model, item, offerOrder)
                }
                is Item.Line -> DiffRow(session, item.line, item.depth)
            }
        }
    }
}

private fun itemsOf(model: DiffModel): List<Item> {
    val hues =
        model.overlay.entries
            .withIndex()
            .associate { (i, e) -> e to i }
    val depths = model.overlay.entries.associateWith { e -> model.overlay.entries.count { e.rows.first in it.rows } }
    val out = mutableListOf<Item>()
    var open: EntryNode? = null
    var movedSeen = false

    model.lines.forEach { line ->
        val entry = line.entry
        if (entry != open) {
            open = entry
            if (entry != null) {
                val moved = line.kind == ChunkKind.MOVED
                out +=
                    Item.Band(
                        entry = entry,
                        hue = hues[entry] ?: 0,
                        depth = (depths[entry] ?: 1) - 1,
                        moved = moved,
                        rightLabel = line.right?.let { model.referenceOverlay.entryAt(it.wireIndex)?.label },
                        first = moved && !movedSeen,
                    )
                if (moved) movedSeen = true
            }
        }
        out += Item.Line(line, if (entry == null) 0 else depths[entry] ?: 1)
    }
    return out
}

/**
 * The entry header — `NoPartyIDs · entry 1 — FIRMA · 1 ExecutingFirm`, and its counterpart on the right.
 *
 * The label is not built here. [com.knapsack.fixtool.service.compare.GroupOverlay] computes it, from the
 * dictionary, for both sides — so the expectation's side and the message's side cannot come to describe the
 * same entry differently, which is the seam that produced the defect the sequence model was built to end.
 */
@Composable
private fun EntryBand(session: ReconcileSession, model: DiffModel, band: Item.Band, offerOrder: Boolean) {
    val border = if (band.moved) DiffPalette.movedBorder else DiffPalette.entryBorder(band.hue)
    val fill = if (band.moved) DiffPalette.movedBand else DiffPalette.entryBand(band.hue)
    val siblings = model.overlay.siblingsOf(band.entry)
    val slot = siblings.indexOf(band.entry)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    fill,
                ).padding(start = (band.depth * 10).dp)
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .testTag(if (band.moved) "moved-band" else "entry-band"),
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
private fun DiffRow(session: ReconcileSession, line: DiffLine, depth: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowTint(line))
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .testTag("diff-row"),
    ) {
        Box(modifier = Modifier.weight(1f)) { LeftCell(session, line, depth) }
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
private fun LeftCell(session: ReconcileSession, line: DiffLine, depth: Int) {
    if (line.leftIsGap) {
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap).padding(start = (depth * 10).dp)) {
            Text("not asserted", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontStyle = FontStyle.Italic)
        }
        return
    }
    val row = line.row
    val index = row.index
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (depth * 10).dp)) {
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
        Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap)) {
            Text(
                "not sent",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.testTag("not-sent"),
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
