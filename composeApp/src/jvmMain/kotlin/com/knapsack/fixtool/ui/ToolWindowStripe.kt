package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The side stripes are as wide as one line of text plus its padding, which is all a rotated label needs. */
private val SIDE_STRIPE_WIDTH = 26.dp

/** The bottom stripe is one row of small tabs, the height a console tab bar has always been. */
private val BOTTOM_STRIPE_HEIGHT = 24.dp

/** The mark on the outer edge of a pressed tab. Two device-independent pixels, read from across the room. */
private val PRESSED_BAR = 2.dp

/**
 * **One edge's tool-window tabs.**
 *
 * The button now sits where its window appears, and an open window is a pressed tab rather than a grey
 * icon tinted slightly less grey. The three stripes are three calls to this one composable, differing only
 * in [edge], because the edge is data on [ToolWindow] and the tab list is a filter over it.
 *
 * The stripe stays on screen with nothing open. It is how a reader finds the windows at all, and 26dp on
 * an edge is a cheap price for the app saying what it can show.
 *
 * @param open the windows currently on screen, which is what draws a tab pressed
 * @param onToggle what a tab does, which is exactly what its ⌘ digit does (see App's key handler)
 */
@Composable
fun ToolWindowStripe(
    edge: ToolWindowEdge,
    open: Set<ToolWindow>,
    onToggle: (ToolWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windows = ToolWindow.on(edge)
    if (edge == ToolWindowEdge.BOTTOM) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(BOTTOM_STRIPE_HEIGHT)
                    .background(AppTheme.Colors.background)
                    .padding(horizontal = 4.dp)
                    .testTag("tool-window-stripe-bottom"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            windows.forEach { window -> StripeTab(window, window in open, onToggle) }
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxHeight()
                    .width(SIDE_STRIPE_WIDTH)
                    .background(AppTheme.Colors.background)
                    .padding(vertical = 4.dp)
                    .testTag(if (edge == ToolWindowEdge.LEFT) "tool-window-stripe-left" else "tool-window-stripe-right"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            windows.forEach { window -> StripeTab(window, window in open, onToggle) }
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
            .pressedBar(window.edge, open)
            .clickable { onToggle(window) }
            .semantics {
                contentDescription = window.tooltip
                stateDescription = if (open) "open" else "closed"
                selected = open
            }.testTag(window.testTag)

    AppTooltip(text = window.tooltip) {
        if (window.edge == ToolWindowEdge.BOTTOM) {
            Row(
                modifier = tab.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = window.icon,
                    contentDescription = null,
                    tint = colour,
                    modifier = Modifier.size(12.dp),
                )
                Text(text = window.title, color = colour, fontSize = 11.sp, maxLines = 1)
            }
        } else {
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
                    text = window.title,
                    color = colour,
                    fontSize = 11.sp,
                    maxLines = 1,
                    // Left reads bottom to top and right reads top to bottom, as IntelliJ's stripes do:
                    // each label turns towards the window it opens.
                    modifier = Modifier.alongStripe(clockwise = window.edge == ToolWindowEdge.RIGHT),
                )
            }
        }
    }
}

/**
 * The pressed tab's bar, on the outer edge of the window.
 *
 * Drawn rather than laid out as a 2dp child: a bar inside a wrap-content tab would have to be told how
 * tall the tab is, and a `fillMaxHeight` there measures the whole stripe.
 */
private fun Modifier.pressedBar(
    edge: ToolWindowEdge,
    open: Boolean,
): Modifier =
    if (!open) {
        this
    } else {
        drawBehind {
            val thickness = PRESSED_BAR.toPx()
            val bar =
                when (edge) {
                    ToolWindowEdge.LEFT -> Offset.Zero to Size(thickness, size.height)
                    ToolWindowEdge.RIGHT -> Offset(size.width - thickness, 0f) to Size(thickness, size.height)
                    ToolWindowEdge.BOTTOM -> Offset(0f, size.height - thickness) to Size(size.width, thickness)
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
