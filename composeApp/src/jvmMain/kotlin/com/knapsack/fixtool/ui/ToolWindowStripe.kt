package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The side stripes are as wide as one line of text plus its padding, which is all a rotated label needs. */
private val SIDE_STRIPE_WIDTH = 26.dp

/** The mark on the outer edge of a pressed tab. Two device-independent pixels, read from across the room. */
private val PRESSED_BAR = 2.dp

/**
 * **One side's tool-window tabs.**
 *
 * The button sits where its window appears, and an open window is a pressed tab rather than a grey icon
 * tinted slightly less grey. Two stripes, both running the full height of the content: the left carries
 * [StripeGroup.LEFT_TOP] at the top and [StripeGroup.LEFT_BOTTOM] at the foot, with a weighted gap between
 * them, and the right carries [StripeGroup.RIGHT]. There is no bottom stripe: the bottom edge belongs to
 * the dock itself, and its tabs live at the foot of the left stripe, which is IntelliJ's arrangement.
 *
 * The stripe stays on screen with nothing open. It is how a reader finds the windows at all, and 26dp on
 * an edge is a cheap price for the app saying what it can show.
 *
 * @param open the windows currently on screen, which is what draws a tab pressed
 * @param onToggle what a tab does, which is exactly what its ⌘ digit does (see App's key handler)
 * @param documentsOpen whether any document is open, which is the only thing the Documents tab is for
 */
@Composable
fun ToolWindowStripe(
    side: StripeSide,
    open: Set<ToolWindow>,
    onToggle: (ToolWindow) -> Unit,
    modifier: Modifier = Modifier,
    documentsOpen: Boolean = false,
) {
    val groups = StripeGroup.entries.filter { it.side == side }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(SIDE_STRIPE_WIDTH)
                .background(AppTheme.Colors.background)
                .padding(vertical = 4.dp)
                .testTag(if (side == StripeSide.LEFT) "tool-window-stripe-left" else "tool-window-stripe-right"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        groups.forEachIndexed { index, group ->
            // The gap that pushes the second group to the foot of the stripe. Weighted rather than a fixed
            // spacer, so the bottom group sits on the bottom edge at any window height.
            if (index > 0) Spacer(modifier = Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ToolWindow.inGroup(group).forEach { window ->
                    // Documents names the open documents, so with none open there is nothing to name.
                    if (window == ToolWindow.DOCUMENTS && !documentsOpen) return@forEach
                    StripeTab(window, window in open, onToggle)
                }
            }
        }
    }
}

/**
 * One tab: an icon, a name, and whether its window is on screen.
 *
 * The open state is on the node three ways, because three readers ask differently: the fill and the edge
 * bar for an eye, `selected` for a test, and `stateDescription` for a screen reader. The tooltip is also
 * the content description, so the shortcut is available to a reader who cannot hover.
 */
@Composable
private fun StripeTab(
    window: ToolWindow,
    open: Boolean,
    onToggle: (ToolWindow) -> Unit,
) {
    val colour = if (open) AppTheme.Colors.text else AppTheme.Colors.textSecondary
    val tab =
        Modifier
            .background(if (open) AppTheme.Colors.surface else Color.Transparent)
            .pressedBar(window.group.side, open)
            .clickable { onToggle(window) }
            .semantics {
                contentDescription = window.tooltip
                stateDescription = if (open) "open" else "closed"
                selected = open
            }.testTag(window.testTag)

    AppTooltip(text = window.tooltip) {
        Column(
            modifier = tab.padding(vertical = 6.dp, horizontal = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = window.icon,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stripeLabel(window, if (open) AppTheme.Colors.textSecondary else AppTheme.Colors.textDisabled),
                color = colour,
                fontSize = 11.sp,
                maxLines = 1,
                // Left reads bottom to top and right reads top to bottom, as IntelliJ's stripes do:
                // each label turns towards the window it opens.
                modifier = Modifier.alongStripe(clockwise = window.group.side == StripeSide.RIGHT),
            )
        }
    }
}

/**
 * **The tab's name with its digit before it, dim**: "1  Editor", IntelliJ's classic "1: Project".
 *
 * The digit used to be in the tooltip alone, which taught ⌘1 to nobody who did not hover — the stripe windows
 * were asked for as a new feature by someone who had used the tool for months. Printed on the tab, the key is
 * read where the window is found. A window with no digit prints its name alone.
 */
internal fun stripeLabel(
    window: ToolWindow,
    digitColour: Color,
): AnnotatedString =
    buildAnnotatedString {
        window.shortcut?.let { digit ->
            withStyle(SpanStyle(color = digitColour, fontFamily = FontFamily.Monospace, fontSize = 10.sp)) {
                append("$digit")
            }
            append("  ")
        }
        append(window.title)
    }

/**
 * The pressed tab's bar, on the outer edge of the window.
 *
 * Drawn rather than laid out as a 2dp child: a bar inside a wrap-content tab would have to be told how
 * tall the tab is, and a `fillMaxHeight` there measures the whole stripe.
 */
private fun Modifier.pressedBar(
    side: StripeSide,
    open: Boolean,
): Modifier =
    if (!open) {
        this
    } else {
        drawBehind {
            val thickness = PRESSED_BAR.toPx()
            val bar =
                when (side) {
                    StripeSide.LEFT -> Offset.Zero to Size(thickness, size.height)
                    StripeSide.RIGHT -> Offset(size.width - thickness, 0f) to Size(thickness, size.height)
                }
            drawRect(color = AppTheme.Colors.primary, topLeft = bar.first, size = bar.second)
        }
    }

/**
 * A label laid out along the stripe instead of across it, so a tab is as long as its name and as wide as
 * one line of text.
 *
 * Measured unbounded and reported with its axes swapped, because a label measured against a 26dp stripe
 * would wrap to one letter per line. The rotation is a draw-time transform about the label's own centre,
 * which is why the child is placed off-centre by exactly half the difference between its sides.
 */
private fun Modifier.alongStripe(clockwise: Boolean): Modifier =
    layout { measurable, _ ->
        val placeable = measurable.measure(Constraints())
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2),
            )
        }
    }.rotate(if (clockwise) 90f else -90f)
