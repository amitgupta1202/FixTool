/**
 * **A one-line label that gives up its middle rather than its tail.**
 *
 * `TextOverflow.MiddleEllipsis` does nothing on Compose Desktop 1.9.3. The desktop text stack builds a Skia
 * paragraph through `androidx.compose.ui.text.platform.ParagraphBuilder`, which holds a single `ellipsis`
 * string and hands it to Skia's `ParagraphStyle.setEllipsis`, and Skia's paragraph ellipsis is end-of-line
 * only. The paragraph factory in `SkiaParagraph_skikoKt` separates nothing but `TextOverflow.Ellipsis` from
 * `Clip`, so a middle ellipsis is quietly drawn at the end instead.
 *
 * [MiddleEllipsisText] therefore measures the string itself and cuts it down, handing the platform only a
 * string that already fits. Worth re-checking when the Compose version moves, because the day Skia grows a
 * middle ellipsis this file is one `overflow` argument again.
 */
package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/**
 * The longest middle-ellipsised form of [text] that measures no wider than [maxWidthPx].
 *
 * Pure, and takes its [width] function as a parameter, so the rule can be tested without a renderer. The
 * candidate that keeps `kept` characters is `text.take((kept + 1) / 2) + "…" + text.takeLast(kept / 2)`,
 * which balances the two halves and gives the odd character to the head. The largest `kept` that fits is
 * found by binary search rather than by walking down from the full length, because every probe costs a real
 * text measurement.
 *
 * Returns [text] untouched when it already fits or when the width is unbounded, and "…" alone when not even
 * one character can be kept beside it.
 */
internal fun middleEllipsis(
    text: String,
    maxWidthPx: Int,
    width: (String) -> Int,
): String {
    if (maxWidthPx == Constraints.Infinity) return text
    if (width(text) <= maxWidthPx) return text

    fun candidate(kept: Int): String = text.take((kept + 1) / 2) + "…" + text.takeLast(kept / 2)

    var low = 0
    var high = text.length - 1
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (width(candidate(mid)) <= maxWidthPx) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate(best)
}

/**
 * [text] on one line, losing characters from the middle when the space it is given is too narrow.
 *
 * The measuring box carries no modifier of its own: the caller's [modifier] goes on the inner [Text], so a
 * weight or a width from the parent still reaches this through the layout it sits in, and a test tag still
 * lands on the node that carries the text.
 */
@Composable
internal fun MiddleEllipsisText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    // The style material3's own `Text(fontSize = ...)` resolves to, so what is measured is what is drawn.
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = fontSize))

    BoxWithConstraints {
        val shown =
            remember(text, constraints.maxWidth, style) {
                middleEllipsis(text, constraints.maxWidth) { candidate ->
                    measurer.measure(AnnotatedString(candidate), style, softWrap = false, maxLines = 1).size.width
                }
            }

        Text(
            text = shown,
            color = color,
            fontSize = fontSize,
            maxLines = 1,
            softWrap = false,
            modifier = modifier,
        )
    }
}
