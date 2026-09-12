package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **One header for every dock, so a reader finds the same things in the same places.**
 *
 * The audit behind `docs/mockups/pane-grammar.html` found ten distinct header patterns across the panes.
 * Title sizes ran from 10sp mono bold to 14sp medium, the bar behind the title was the header grey in one
 * place and a hard-coded `0xFF2D2D2D` in the next, and the close sat last in one, second of two in
 * another, above the controls in a third and nowhere at all in a fourth. None of them was wrong against
 * itself. They were never designed against each other.
 *
 * Four things, always in this order:
 *
 *  - **The title is the stripe noun.** The same word on the tab, on the header and in the shortcut
 *    tooltip, so "Message Details", "Latency Stats" and "Repeatable Scenarios" stop being three names for
 *    things whose tabs say Detail, Latency and Scenarios.
 *  - **The status is the one place a dock speaks in its own words**, in 9sp dim, and it is optional. It
 *    goes before the title shortens, because the status is optional and the title is not.
 *  - **The actions fold** — see [FoldingActions]. Labels to glyphs, then glyphs into one ⋯, never a second
 *    line and never half a control.
 *  - **Hide, not Close.** Nothing is lost when a dock hides: the stripe tab brings it straight back with
 *    one click or one ⌘ digit. Close is reserved for the things that hold content — tabs, sessions,
 *    documents — and the two words stop being interchangeable in the twelve phrasings the audit found.
 *
 * @param leading drawn between the title and the folding actions and never folded: a segmented control, a
 *   menu button, anything that is not a plain action. Its [leadingWidth] is declared rather than measured,
 *   because the fold has to know what the row costs before it composes it.
 */
@Composable
@Suppress("LongParameterList")
fun DockHeader(
    title: String,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    /** What the status means, for a status that is a term rather than a sentence — "Socket", say. */
    statusTooltip: String? = null,
    leading: (@Composable () -> Unit)? = null,
    leadingWidth: Dp = 0.dp,
    actions: List<BarAction> = emptyList(),
    hideTooltip: String = "Hide $title",
    hideTag: String = "dock-hide",
    tag: String = "dock-header",
    statusTag: String = "$tag-status",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceHeader)) {
            val available = maxWidth
            // Everything the actions may not spend: the padding, the title's floor, the leading slot, and
            // the Hide that never folds.
            val reserved = DockHeaders.H_PAD + DockHeaders.MIN_TITLE + leadingWidth + BAR_GAP + BAR_BUTTON
            // The status goes first, while the title is still at full length: it is the optional half.
            val glyphs = (BAR_BUTTON + BAR_GAP) * actions.size
            val showStatus = status != null && available >= reserved + glyphs + DockHeaders.MIN_STATUS

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(DockHeaders.HEIGHT)
                        .padding(horizontal = DockHeaders.H_PAD / 2)
                        .testTag(tag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTitle(
                    title = title,
                    status = status.takeIf { showStatus },
                    statusTooltip = statusTooltip,
                    titleTag = "$tag-title",
                    statusTag = statusTag,
                    modifier = Modifier.weight(1f),
                )
                leading?.let {
                    it()
                    Spacer(modifier = Modifier.width(BAR_GAP))
                }
                FoldingActions(actions = actions, available = available, reserved = reserved)
                if (actions.isNotEmpty()) Spacer(modifier = Modifier.width(BAR_GAP))
                TooltipIconButton(
                    tooltip = hideTooltip,
                    onClick = onHide,
                    modifier = Modifier.size(BAR_BUTTON).testTag(hideTag),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = hideTooltip,
                        tint = AppTheme.Colors.textSecondary,
                        modifier = Modifier.size(BAR_ICON),
                    )
                }
            }
        }
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
    }
}

/**
 * **The same header, named by its tool window**, which is where the title and the Hide tooltip come from.
 *
 * A dock that takes its own title from [ToolWindow] cannot drift from its stripe tab, and its Hide names
 * the shortcut that brings it back: "Hide Detail · ⌘3".
 */
@Composable
@Suppress("LongParameterList")
fun DockHeader(
    window: ToolWindow,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusTooltip: String? = null,
    leading: (@Composable () -> Unit)? = null,
    leadingWidth: Dp = 0.dp,
    actions: List<BarAction> = emptyList(),
    hideTooltip: String = window.hideTooltip,
    hideTag: String = "${window.testTag}-hide",
    statusTag: String = "${window.testTag}-status",
) {
    DockHeader(
        title = window.title,
        onHide = onHide,
        modifier = modifier,
        status = status,
        statusTooltip = statusTooltip,
        leading = leading,
        leadingWidth = leadingWidth,
        actions = actions,
        hideTooltip = hideTooltip,
        hideTag = hideTag,
        tag = "${window.testTag}-header",
        statusTag = statusTag,
    )
}

/**
 * The name and, while there is room for it, the status — the half of the header that gives ground.
 *
 * The title loses its middle rather than its tail, because the tail of "FX Demo Venue ← DEMO_CLIENT1" is the
 * half that says which one this is. The platform cannot do it — `TextOverflow.MiddleEllipsis` draws an end
 * ellipsis on Compose Desktop — so [MiddleEllipsisText] measures and cuts the string itself.
 */
@Composable
@Suppress("LongParameterList")
private fun DockTitle(
    title: String,
    status: String?,
    statusTooltip: String?,
    titleTag: String,
    statusTag: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // **The title is unweighted and the status carries the weight, not the other way round.**
        // A Row measures its unweighted children first, so whichever of the two has no weight is the one
        // that gets what it asks for. With the weight on the title, a long status took the whole bar at its
        // intrinsic width and left the name with nothing: the Order book header came up reading ".." beside
        // "Equity Demo Venue ← EQTY_CLIENT1 · 1 order · 0 working". Found by opening it against a real book —
        // no test width reproduced it, because every status a test writes is short.
        AppTooltip(text = title) {
            MiddleEllipsisText(title, AppTheme.Colors.text, DockHeaders.TITLE_SIZE, Modifier.testTag(titleTag))
        }
        if (status == null) return@Row
        Spacer(modifier = Modifier.width(6.dp))
        val line: @Composable (Modifier) -> Unit = { m ->
            Text(
                status,
                color = AppTheme.Colors.textDisabled,
                fontSize = DockHeaders.STATUS_SIZE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = m.testTag(statusTag),
            )
        }
        // The status ellipsises inside whatever the title left, which is what "the status gives ground
        // before the title does" has to mean in a row that measures rather than in a rule that decides.
        if (statusTooltip != null) {
            AppTooltip(statusTooltip, modifier = Modifier.weight(1f, fill = false)) { line(Modifier) }
        } else {
            line(Modifier.weight(1f, fill = false))
        }
    }
}

/** What a dock header is made of, in numbers — the fold table's "minimum width" row, as code. */
object DockHeaders {
    /** Every dock header is this tall, at every width. A bar whose height moves reads as two bars. */
    internal val HEIGHT = 26.dp

    /** 6dp each side. */
    internal val H_PAD = 12.dp

    /** The register every dock title speaks. */
    internal val TITLE_SIZE = 11.sp

    /** The status, one step down and dim, because it is the optional half. */
    internal val STATUS_SIZE = 9.sp

    /** Eight characters at 11sp: the shortest title still worth reading, and the fold's floor for one. */
    internal val MIN_TITLE = 52.dp

    /** Below this there is no room for a status worth printing, so it goes rather than being clipped. */
    internal val MIN_STATUS = 56.dp

    /**
     * **The narrowest a dock may be drawn: its own folded header.**
     *
     * The title at eight characters, the gap, and the Hide. A resize handle that stopped anywhere narrower
     * would draw half a button, which is the one thing the fold rule promises never happens — so the handle
     * stops here instead. See [dockRatio].
     */
    val MIN_WIDTH = H_PAD + MIN_TITLE + BAR_GAP + BAR_BUTTON
}

/**
 * **The ratio floor a dock's resize handle stops at: its own declared floor, or its folded header.**
 *
 * The ratio floors the docks already had are the ones that matter on a wide window — a tenth of 1600px is
 * far more than any header needs. They stop meaning anything on a narrow one, where a tenth of the width is
 * not a header at all, and that is the width at which [DockHeaders.MIN_WIDTH] takes over. The wider of the
 * two wins, so nothing about today's behaviour on a wide window changes.
 *
 * A [containerPx] of zero is the frame before the row has been measured, where there is nothing to clamp
 * against yet — the same case [boundedPaneWidth] handles.
 */
fun dockFloorRatio(floor: Float, containerPx: Float, density: Density): Float {
    if (containerPx <= 0f) return floor
    val headerFloor = with(density) { DockHeaders.MIN_WIDTH.toPx() } / containerPx
    return maxOf(floor, headerFloor).coerceAtMost(1f)
}
