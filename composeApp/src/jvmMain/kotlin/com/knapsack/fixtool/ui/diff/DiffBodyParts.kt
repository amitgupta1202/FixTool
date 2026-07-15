package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.service.compare.ChunkKind
import com.knapsack.fixtool.service.compare.MessageField
import com.knapsack.fixtool.ui.AppTheme

/**
 * **The diff body's shared primitives** — the tag cell, the value cell, and the row tint.
 *
 * The reconcile editor and the plain diff **viewer** render the same two-sided diff over the same
 * [DiffModel]; what they must *not* do is draw its body two different ways. That was the P5/`GroupOverlay`
 * seam — two renderers coming to disagree about what faces what — and it stays closed here by having one place
 * that draws a field and one that tints a row (Phase 7, G2). The editor forks only the *chrome* (an editable
 * left column, a gutter with applies, a Save); the body is this.
 */
internal val DIFF_TAG_COL = 46.dp
internal val DIFF_NAME_COL = 96.dp

/**
 * The tag, and its occurrence when it is not the first — `448#2`. The occurrence is part of the address: the
 * second `452` is not the first one, and a reader who cannot see which is which cannot reason about a move.
 */
@Composable
internal fun TagCell(tag: Int, occurrence: Int, kind: ChunkKind) {
    Row(modifier = Modifier.width(DIFF_TAG_COL)) {
        Text(
            "$tag",
            color = if (kind == ChunkKind.MOVED) DiffPalette.moved else AppTheme.Colors.tagNumber,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        if (occurrence > 0) {
            Text("#${occurrence + 1}", color = AppTheme.Colors.textDisabled, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

/** The row's background, keyed on what the diff did there — the same hues in both surfaces. */
internal fun rowTint(line: DiffLine): Color =
    when {
        line.unjudged -> DiffPalette.unjudgedRow
        line.kind == ChunkKind.MOVED -> DiffPalette.movedBand
        line.kind == ChunkKind.VALUE -> DiffPalette.valueRow
        line.kind == ChunkKind.RIGHT_ONLY -> DiffPalette.addedRow
        line.kind == ChunkKind.LEFT_ONLY -> DiffPalette.missingRow
        else -> Color.Transparent
    }

/**
 * **One field of a message, read-only** — tag, name, value, and the dictionary's word for the value as its own
 * dim span, never folded into the value string (which would be a message the venue never sent). This is the
 * reconcile view's right column *and* both columns of the viewer.
 */
@Composable
internal fun FieldValueCell(field: MessageField, kind: ChunkKind, unjudged: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TagCell(field.tag, field.occurrence, kind)
        Text(field.name, color = AppTheme.Colors.fieldName, fontSize = 10.sp, modifier = Modifier.width(DIFF_NAME_COL))
        Text(
            field.value,
            color =
                when (kind) {
                    ChunkKind.VALUE -> AppTheme.Colors.error
                    ChunkKind.MOVED -> DiffPalette.moved
                    else -> AppTheme.Colors.fieldValue
                },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = if (kind == ChunkKind.VALUE) FontWeight.Bold else FontWeight.Normal,
        )
        field.description?.takeIf { it != field.value }?.let {
            Text("  $it", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
        }
        if (unjudged) {
            Text(
                "  unjudged here — resolves at run time",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.testTag("unjudged-note"),
            )
        }
    }
}
