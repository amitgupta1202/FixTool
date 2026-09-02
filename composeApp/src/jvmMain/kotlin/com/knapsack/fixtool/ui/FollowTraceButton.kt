package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **How a field row deep inside the detail pane reaches Follow.**
 *
 * Not a parameter, because the rows that need it sit at the bottom of a chain of private
 * `LazyListScope` builders in `MessageDetailPanel` that have no other business knowing about traces,
 * and threading a callback through all of them would make every one of them a place to forget it. Null
 * — the default — means the host does not offer Follow, and the affordance simply is not drawn: a
 * standalone panel in a test or a preview keeps working with nothing provided.
 */
internal val LocalFollowTrace = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * **"Follow across sessions"**, wherever a correlation id is on screen.
 *
 * The same mark in every place the gesture is offered — a group header, a message row's menu, a
 * correlation-id field in the detail pane — because they all set the same one app-level followed trace,
 * and three differently-drawn affordances would read as three different features.
 *
 * When [following] it draws pressed and *unfollows* on click. That is deliberate: with one followed
 * trace, the header that is currently followed is where the reader is already looking when they want it
 * off, and sending them to the toolbar chip instead would be a second place to say the same thing.
 */
@Composable
internal fun FollowTraceButton(
    following: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTooltip(text = if (following) "Stop following" else "Follow across sessions") {
        Box(
            modifier =
                modifier
                    .testTag(if (following) "unfollow-trace" else "follow-trace")
                    .background(
                        color = if (following) AppTheme.Colors.primary else Color.Transparent,
                        shape = RoundedCornerShape(2.dp),
                    ).clickable { onClick() }
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "↗",
                color = if (following) AppTheme.Colors.background else AppTheme.Colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
