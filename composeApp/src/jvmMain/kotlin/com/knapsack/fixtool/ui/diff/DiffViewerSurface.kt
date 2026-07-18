// The viewer is a dense two-column layout; the read-best-on-one-line rule from DiffSurface applies here too.
@file:Suppress("MaxLineLength", "LongMethod")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.FixIndent

/**
 * **The plain diff viewer — the same two-sided diff as [DiffSurface], with the writing removed.**
 *
 * It shares the *body* — the tag/name/value cell, the row tint, the group bands — with the editor (Phase 7, G2,
 * `DiffBodyParts`), and forks only the *chrome*: no matcher chips (the left column is a plain value), no gutter
 * applies (the gutter is a status glyph, `=`/`≠`/`+A`/`+B`), no Save. It **diagnoses nothing**: neither side is
 * the truth, so the header is a descriptive **difference count**, not a pass/fail verdict, and there is no
 * FAILED chip (G5).
 *
 * The one door out is [onSeed]: *"Seed expectation from A/B"* turns a side into a seeded expectation and the
 * window becomes the editor — diff first, assert when ready (G6). That is the only thing here that leads to a
 * write, and it is explicit.
 */
@Composable
fun DiffViewerSurface(
    session: DiffViewerSession,
    onSwapSides: () -> Unit,
    onSelectMode: (MatchMode) -> Unit,
    onSeed: (SeedFrom) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = session.model
    // The surface holds focus so the bare `n`/`p` keys reach it. Unlike the editor there is no value field
    // here to steal focus and eat the letters, so a plain KeyDown gate is enough — no `focused` guard needed.
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // Keep the keyboard-driven next-diff on screen. `selectedItem` only moves on `n`/`p` and the header's
    // ↑/↓; scrolling on anything else would yank the page out from under a reader who is simply looking (U4).
    LaunchedEffect(session.selectedItem) {
        session.selectedItem?.let { listState.scrollToItem(it) }
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { onViewerKey(it, session) }
                .testTag("diff-viewer"),
    ) {
        ViewerHeader(session, onSwapSides, onSelectMode, onSeed)
        ViewerColumnHeaders("A — ${session.left.label}", "B — ${session.right.label}")
        // A LazyColumn now, not a plain scrolling Column: `selectedItem` deep-links to the row the keyboard
        // (or the header's ↑/↓) chose, and `scrollToItem` needs a list that knows where its rows are (U4).
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(model.items.size) { index ->
                when (val item = model.items[index]) {
                    is DiffItem.Band -> ViewerBand(item)
                    is DiffItem.Line -> ViewerRow(item.line, item.depth, session)
                }
            }
        }
        ViewerFooter()
    }
}

/** `n` → the next differing chunk, `p` → the previous — the visible half of the header's ↑/↓, same two doors. */
private fun onViewerKey(event: KeyEvent, session: DiffViewerSession): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return false
    return when (event.key) {
        Key.N -> true.also { session.nextChunk() }
        Key.P -> true.also { session.prevChunk() }
        else -> false
    }
}

// ------------------------------------------------------------------------------------------- the header

@Composable
private fun ViewerHeader(
    session: DiffViewerSession,
    onSwapSides: () -> Unit,
    onSelectMode: (MatchMode) -> Unit,
    onSeed: (SeedFrom) -> Unit,
) {
    val model = session.model
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        SideChip("A", session.left.label, AppTheme.Colors.info, "diff-viewer-a")
        Text(" vs ", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
        SideChip("B", session.right.label, AppTheme.Colors.warning, "diff-viewer-b")
        Spacer(Modifier.width(8.dp))
        // The mode chip — the same idea as the reconcile mode chip, except it is not an edit here (there is
        // nothing to save). It changes how a difference is CLASSIFIED, never whether it is shown.
        val strict = session.mode == MatchMode.STRICT
        ViewerModeChip(
            label = if (strict) "STRICT" else "OPEN",
            onToggle = { onSelectMode(if (strict) MatchMode.OPEN else MatchMode.STRICT) },
        )
        // The visible half of `n`/`p`, and they call the same two functions the keys do — one decider, two
        // doors. Nothing to steer when the two sides are identical, so they appear only once there is a diff.
        if (model.diffChunks.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            SlimHeaderButton("↑ prev", "diff-viewer-prev-chunk", session::prevChunk)
            SlimHeaderButton("↓ next diff", "diff-viewer-next-chunk", session::nextChunk)
        }
        Box(Modifier.weight(1f))
        DifferenceCount(session)
        Spacer(Modifier.width(10.dp))
        SeedMenu(onSeed)
        SlimHeaderButton("Swap sides", "diff-viewer-swap", onSwapSides)
    }
}

/** `A · ExecutionReport · TRADE · 09:35:44` — the side, its colour, and where the bytes came from. */
@Composable
private fun SideChip(letter: String, label: String, color: Color, testTag: String) {
    Box(
        modifier =
            Modifier
                .border(1.dp, color)
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .testTag(testTag),
    ) {
        Text("$letter · $label", color = color, fontSize = 10.sp)
    }
}

/**
 * **The mode toggle — STRICT ⇄ OPEN — the one *control* in this chip row.** It used to be a badge carrying a
 * `▾` that promised a menu it does not have: clicking it merely toggles, there is nothing to drop down (U1). So
 * no caret; and it is deliberately not drawn like the static side chips — a standing fill, a brighter hover tint
 * and a hand cursor those outline-only chips lack, plus a [TooltipArea] that spells out the choice. The
 * STRICT/OPEN label stays visible; the tooltip explains what the two words mean.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerModeChip(label: String, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = AppTheme.Colors.warning
    val shape = RoundedCornerShape(4.dp)
    TooltipArea(
        tooltip = {
            Text(
                MODE_TOOLTIP,
                modifier =
                    Modifier
                        .shadow(4.dp, shape)
                        .background(AppTheme.Colors.border, shape)
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                color = AppTheme.Colors.text,
                fontSize = 11.sp,
            )
        },
        delayMillis = 400,
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomCenter,
                alignment = Alignment.BottomCenter,
                offset = DpOffset(0.dp, 4.dp),
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .border(1.dp, color)
                    .background(color.copy(alpha = if (hovered) 0.28f else 0.14f))
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .testTag("diff-viewer-mode"),
        ) {
            Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// The exact wording is shared verbatim with the reconcile editor's mode chip, so both surfaces explain it alike.
private const val MODE_TOOLTIP =
    "Match mode. STRICT: the reply must carry the same tags, the same number of times, in the same order. " +
        "OPEN: the listed assertions must appear in order; any extra tag the reply adds is ignored. Click to switch."

@Composable
private fun SlimHeaderButton(label: String, testTag: String, onClick: () -> Unit) {
    com.knapsack.fixtool.ui.SlimButton(
        label,
        onClick = onClick,
        color = AppTheme.Colors.textSecondary,
        modifier = Modifier.padding(start = 6.dp).testTag(testTag),
    )
}

/** **A descriptive count, not a verdict.** The viewer diagnoses nothing — neither side is the truth (G5). */
@Composable
private fun DifferenceCount(session: DiffViewerSession) {
    val model = session.model
    val counts = model.lines.groupingBy { it.kind }.eachCount()
    val value = counts[ChunkKind.VALUE] ?: 0
    val onlyA = counts[ChunkKind.LEFT_ONLY] ?: 0
    val onlyB = counts[ChunkKind.RIGHT_ONLY] ?: 0
    val movedEntries = model.items.count { it is DiffItem.Band && it.moved }
    val total = value + onlyA + onlyB + movedEntries
    val parts =
        buildList {
            if (value > 0) add("$value value")
            if (onlyA > 0) add("$onlyA only in A")
            if (onlyB > 0) add("$onlyB only in B")
            if (movedEntries > 0) add("$movedEntries moved")
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (total == 0) "identical — no differences" else "$total difference${if (total == 1) "" else "s"}",
            color = if (total == 0) AppTheme.Colors.success else AppTheme.Colors.textSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.testTag("diff-viewer-count"),
        )
        if (parts.isNotEmpty()) {
            // The value/only-in-A/only-in-B breakdown is informational, not disabled chrome — so textSecondary,
            // above the WCAG-AA floor textDisabled sits under at this size (U2).
            Text("  ·  ${parts.joinToString(" · ")}", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SeedMenu(onSeed: (SeedFrom) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        com.knapsack.fixtool.ui.SlimButton(
            "Seed expectation from A ▾",
            onClick = { open = true },
            color = AppTheme.Colors.primary,
            modifier = Modifier.testTag("diff-viewer-seed"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(SeedFrom.A to "Seed from A (left)", SeedFrom.B to "Seed from B (right)").forEach { (side, label) ->
                DropdownMenuItem(
                    onClick = {
                        open = false
                        onSeed(side)
                    },
                    modifier = Modifier.testTag("diff-viewer-seed-${side.name.lowercase()}"),
                    text = { Text(label, color = AppTheme.Colors.text, fontSize = 11.sp) },
                )
            }
            Text(
                "SEEDS A NEW EXPECTATION FROM THAT SIDE · THE OTHER SIDE BECOMES ITS REFERENCE · ADD TO A SCENARIO TO ASSERT",
                // What Seed will do — the reader must read it before they act, so textSecondary. At 8sp
                // textDisabled is ~3:1, below WCAG AA, which is exactly the wrong contrast for a caption (U2).
                color = AppTheme.Colors.textSecondary,
                fontSize = 8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ViewerColumnHeaders(left: String, right: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(
            left.uppercase(),
            color = AppTheme.Colors.textDisabled,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(LEFT_WEIGHT),
        )
        Spacer(Modifier.width(GUTTER))
        Text(
            right.uppercase(),
            color = AppTheme.Colors.textDisabled,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------------------------------- the body

@Composable
private fun ViewerRow(line: DiffLine, depth: Int, session: DiffViewerSession) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(rowTint(line))
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag("diff-viewer-row"),
    ) {
        Box(modifier = Modifier.weight(LEFT_WEIGHT).padding(start = FixIndent.start(depth, FixIndent.DIFF_STEP))) {
            val a = line.leftField(session.dictionary)
            // A ghost's field is in BOTH messages — A merely carries it somewhere else, on the line that
            // reports the move. "only in B" would claim A never sent it, two lines from where A did.
            if (a == null) {
                Gap(if (line.row.ghost) "elsewhere in A" else "only in B")
            } else {
                FieldValueCell(a, line.kind, unjudged = false)
            }
        }
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) { ViewerGutter(line) }
        Box(modifier = Modifier.weight(1f)) {
            val b = line.right
            if (b == null) Gap("only in A") else FieldValueCell(b, line.kind, unjudged = false)
        }
    }
}

@Composable
private fun Gap(text: String) {
    Row(modifier = Modifier.fillMaxWidth().background(DiffPalette.gap)) {
        // "only in A/B" names a real, actionable difference — informational, not disabled chrome — so
        // textSecondary, above the WCAG-AA floor textDisabled sits under at this size (U2).
        Text(text, color = AppTheme.Colors.textSecondary, fontSize = 10.sp, fontStyle = FontStyle.Italic)
    }
}

/** `=` same · `≠` value · `+A` only in A · `+B` only in B. A moved row's meaning is on its band, not here. */
@Composable
private fun ViewerGutter(line: DiffLine) {
    val (glyph, color) =
        when {
            // A ghost faces B's field while A's line for the same tag sits elsewhere — `=` would say the
            // two sides agree here, and they do not. The gap text carries the meaning; the gutter is quiet.
            line.row.ghost -> "" to Color.Transparent
            else ->
                when (line.kind) {
                    ChunkKind.SAME -> "=" to AppTheme.Colors.textDisabled
                    // Only a VALUE mismatch is an error (red). A +A/+B marker takes its own side's chip
                    // colour — A is info, B is warning — so each reads as "present on that side", not as a
                    // value error (U3).
                    ChunkKind.VALUE -> "≠" to AppTheme.Colors.error
                    ChunkKind.LEFT_ONLY -> "+A" to AppTheme.Colors.info
                    ChunkKind.RIGHT_ONLY -> "+B" to AppTheme.Colors.warning
                    ChunkKind.MOVED -> "" to Color.Transparent
                }
        }
    if (glyph.isNotBlank()) {
        Text(
            glyph,
            color = color,
            fontSize = 10.sp,
            modifier = Modifier.testTag("diff-viewer-gutter-${line.kind.name.lowercase()}"),
        )
    }
}

/** The group band, read-only: the border, the entry label, and its counterpart on the other side. No arrows. */
@Composable
private fun ViewerBand(band: DiffItem.Band) {
    val border = if (band.moved) DiffPalette.movedBorder else DiffPalette.entryBorder(band.hue)
    val fill = if (band.moved) DiffPalette.movedBand else DiffPalette.entryBand(band.hue)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(fill)
                .padding(start = FixIndent.start(band.depth, FixIndent.DIFF_STEP))
                .padding(horizontal = ROW_PADDING, vertical = 2.dp)
                .testTag(if (band.moved) "viewer-moved-band" else "viewer-entry-band"),
    ) {
        Row(modifier = Modifier.weight(LEFT_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Text("▏", color = border, fontSize = 11.sp)
            Text(
                band.entry.label.ifBlank { "entry ${band.entry.entryIndex + 1}" },
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.width(GUTTER))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("▏", color = border, fontSize = 11.sp)
            Text(band.rightLabel ?: "—", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
            if (band.moved) {
                Box(Modifier.weight(1f))
                Text(
                    "⇅ moved — same tags, same values, different position",
                    color = DiffPalette.moved,
                    fontSize = 9.sp,
                    modifier = Modifier.testTag("viewer-moved-note"),
                )
            }
        }
    }
}

@Composable
private fun ViewerFooter() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("diff-viewer-footer"),
    ) {
        Text("read-only diff — nothing here writes anything", color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        Text("  ·  Seed expectation to start asserting", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
    }
}
