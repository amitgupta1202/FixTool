package com.knapsack.fixtool.ui.diff

import androidx.compose.ui.graphics.Color
import com.knapsack.fixtool.ui.AppTheme

/**
 * The diff surface's own colours — and only the ones the app's palette does not already have.
 *
 * `AppTheme.Colors` is the vocabulary everywhere else and stays so here: a tag is `tagNumber`, a field name
 * is `fieldName`, a failure is `error`. What it has no word for is the thing this surface invented — **a run
 * of rows that moved** — and the entry hues that let a reader see, without reading a single value, that these
 * three rows are one party and those three are another. Those live here rather than in the app-wide theme,
 * because nothing else in the app has entries or moves to colour.
 */
object DiffPalette {
    /** Moved. The one status that is neither pass nor fail nor missing: it is *elsewhere*, and intact. */
    val moved = Color(0xFFC586C0)

    /** The band and the crossing connector, at the weight a background can carry. */
    val movedBand = Color(0x1AC586C0)
    val movedBorder = Color(0xA6C586C0)

    /**
     * One hue per entry, alternating — the cheapest way to see that a party is a *thing* rather than three
     * adjacent rows, which is precisely what the reader has to see before a move can mean anything to them.
     */
    fun entryBorder(index: Int): Color =
        if (index % 2 == 0) AppTheme.Colors.primary.copy(alpha = 0.45f) else AppTheme.Colors.info.copy(alpha = 0.45f)

    fun entryBand(index: Int): Color =
        if (index % 2 == 0) AppTheme.Colors.primary.copy(alpha = 0.04f) else AppTheme.Colors.info.copy(alpha = 0.04f)

    /** A row the reply did not answer, or one the expectation never mentioned: the other side is nothing. */
    val gap = Color(0x143A3A3A)

    /**
     * The author's cursor. It has to beat every other tint, because it is the row `alt+↑/↓` is about to move
     * and the one `n` just landed on — and a selection you cannot find is a keyboard you cannot use.
     */
    val selectedRow = Color(0x382D5A8C)

    /** Row tints. Deliberately faint: the gutter and the values carry the meaning, not the background. */
    val valueRow = Color(0x1AE06C75)
    val missingRow = Color(0x12E06C75)
    val addedRow = Color(0x1761AFEF)
    val unjudgedRow = Color(0x0FCE9178)
    val editedRow = Color(0x1A98C379)
}
