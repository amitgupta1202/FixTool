// The viewer is a dense two-column layout; the read-best-on-one-line rule from DiffSurface applies here too.
@file:Suppress("MaxLineLength", "LongMethod")

package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.ui.AppTheme

/** Which side *"Seed expectation from …"* mints an assertion out of. */
enum class SeedFrom { A, B }

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
    Column(modifier = modifier.fillMaxSize().testTag("diff-viewer")) {
        ViewerHeader(session, onSwapSides, onSelectMode, onSeed)
        ViewerColumnHeaders("A — ${session.left.label}", "B — ${session.right.label}")
        // A plain scrolling column, not a LazyColumn: the viewer is read-only, so it needs neither the
        // reconcile surface's scroll-to-a-deep-linked-row nor its drag — and a non-lazy column keeps every row
        // in the tree, which is what a click-only gate photographs.
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            model.items.forEach { item ->
                when (item) {
                    is DiffItem.Band -> ViewerBand(item)
                    is DiffItem.Line -> ViewerRow(item.line, item.depth, session)
                }
            }
        }
        ViewerFooter()
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
        MiniChip(
            label = if (strict) "STRICT" else "OPEN",
            color = AppTheme.Colors.warning,
            testTag = "diff-viewer-mode",
            onClick = { onSelectMode(if (strict) MatchMode.OPEN else MatchMode.STRICT) },
        )
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

@Composable
private fun MiniChip(label: String, color: Color, testTag: String, onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .border(1.dp, color)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .testTag(testTag),
    ) {
        Text("$label ▾", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

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
            Text("  ·  ${parts.joinToString(" · ")}", color = AppTheme.Colors.textDisabled, fontSize = 11.sp)
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
                color = AppTheme.Colors.textDisabled,
                fontSize = 8.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ViewerColumnHeaders(left: String, right: String) {
    Row(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader).padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(left.uppercase(), color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(LEFT_WEIGHT))
        Spacer(Modifier.width(GUTTER))
        Text(right.uppercase(), color = AppTheme.Colors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
        Box(modifier = Modifier.weight(LEFT_WEIGHT).padding(start = (depth * 10).dp)) {
            val a = line.leftField(session.dictionary)
            if (a == null) Gap("only in B") else FieldValueCell(a, line.kind, unjudged = false)
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
        Text(text, color = AppTheme.Colors.textDisabled, fontSize = 10.sp, fontStyle = FontStyle.Italic)
    }
}

/** `=` same · `≠` value · `+A` only in A · `+B` only in B. A moved row's meaning is on its band, not here. */
@Composable
private fun ViewerGutter(line: DiffLine) {
    val (glyph, color) =
        when (line.kind) {
            ChunkKind.SAME -> "=" to AppTheme.Colors.textDisabled
            ChunkKind.VALUE -> "≠" to AppTheme.Colors.error
            ChunkKind.LEFT_ONLY -> "+A" to AppTheme.Colors.error
            ChunkKind.RIGHT_ONLY -> "+B" to AppTheme.Colors.info
            ChunkKind.MOVED -> "" to Color.Transparent
        }
    if (glyph.isNotBlank()) Text(glyph, color = color, fontSize = 10.sp, modifier = Modifier.testTag("diff-viewer-gutter-${line.kind.name.lowercase()}"))
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
                .padding(start = (band.depth * 10).dp)
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
                Text("⇅ moved — same tags, same values, different position", color = DiffPalette.moved, fontSize = 9.sp, modifier = Modifier.testTag("viewer-moved-note"))
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
