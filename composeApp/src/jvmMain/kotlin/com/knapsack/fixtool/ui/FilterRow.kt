package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Whether this is a Mac, which is the only thing that decides how a shortcut is written down. */
private val IS_MAC = System.getProperty("os.name").lowercase().contains("mac")

/**
 * The filter row's shortcut, as a reader of this platform writes it.
 *
 * ⌘F is Search all sessions and stays that way, so the row takes the modifier beside it. The pane
 * grammar note settles the pair: ⌘F searches, ⌥⌘F filters.
 */
val FILTER_ROW_SHORTCUT: String = if (IS_MAC) "⌥⌘F" else "Ctrl+Alt+F"

/**
 * **One query over every pane: the regex, the two directions, and the followed trace.**
 *
 * The three used to be three unrelated things in the middle of the toolbar. They are one question,
 * *which messages am I looking at*, asked of every pane at once, which is why they share a row and one
 * value.
 *
 * [global] is [MessageFilters.Global] itself rather than a copy of its three fields, so the row draws
 * exactly what is ANDed into each pane and there is no second place for the answer to be kept. The
 * followed trace is named rather than flagged: a narrowing nobody can name is the silent-filter defect
 * this whole row exists to remove.
 */
data class FilterQuery(
    val global: MessageFilters.Global = MessageFilters.Global.NONE,
    /**
     * The followed trace's label, or null when nothing is followed. That is the chip's whole condition.
     *
     * A label rather than a flag plus a lookup: the chip's job is to *name* what every pane is narrowed
     * to, and the counts beside it only mean anything while there is a label to count for.
     */
    val followingLabel: String? = null,
    val followingSessionCount: Int = 0,
    val followingMessageCount: Int = 0,
    /** Panes whose ring dropped a message of this trace, by title. See `Traces.Trace.truncatedSessions`. */
    val followingTruncatedOn: List<String> = emptyList(),
) {
    /**
     * **Everything narrowing the panes right now, in words, or empty when nothing is.**
     *
     * The whole visibility rule turns on this list: a row with anything in it stays on screen and the
     * funnel refuses to hide it, because the one thing a filter must never do is narrow a view without
     * saying so. Every reason is listed rather than the first one found, since a row held open by a
     * followed trace and a regex has two things to undo and one of them would otherwise go unsaid.
     */
    val narrowing: List<String>
        get() =
            buildList {
                if (global.regex.isNotBlank()) add("regex")
                when {
                    !global.showIncoming && !global.showOutgoing -> add("neither direction")
                    !global.showIncoming -> add("outgoing only")
                    !global.showOutgoing -> add("incoming only")
                }
                followingLabel?.let { add("following $it") }
            }

    val isNarrowing: Boolean get() = narrowing.isNotEmpty()

    /**
     * **Whether the row is on screen**, given whether it has been asked for.
     *
     * The never-silent rule, in one place both the app and its tests read: a filter that is narrowing
     * the panes is a filter the reader can see, whatever the remembered flag says.
     */
    fun shown(askedFor: Boolean): Boolean = askedFor || isNarrowing

    /** What the funnel says on hover: its shortcut when it is free to close, its reasons when it is not. */
    val funnelTooltip: String
        get() =
            if (isNarrowing) {
                "Filter row · narrowing: ${narrowing.joinToString(", ")}"
            } else {
                "Filter row · $FILTER_ROW_SHORTCUT"
            }
}

/**
 * **The filter row: one query, directly above the panes it narrows.**
 *
 * The regex, the direction boxes and the Following chip were three separate things in the middle of the
 * toolbar, a row of app-level *actions* they never belonged to. A filter is a fourth kind of control, a
 * query, so it gets its own row against the thing it applies to, in both layouts, under the pane bar.
 *
 * **It cannot go silent.** The row is on screen whenever it is narrowing anything, whatever the stored
 * flag says, and the ✕ at its right is the only way out: it clears the regex, ticks both directions back
 * on, unfollows, *and then* closes. That is the rule the Following chip already enforced for itself,
 * extended to the regex, which could sit narrowing every pane in a field nobody had looked at since they
 * typed in it.
 *
 * @param focusTick a counter the keyboard bumps when it opens the row, so ⌥⌘F lands in the regex field.
 *   A tick rather than a flag: the row also appears because something started narrowing the panes, and
 *   an appearing row that steals the keyboard from whatever was typing is not what anybody asked for.
 */
@Composable
@Suppress("LongParameterList")
fun FilterRow(
    query: FilterQuery = FilterQuery(),
    onRegexChange: (String) -> Unit = {},
    onIncomingChange: (Boolean) -> Unit = {},
    onOutgoingChange: (Boolean) -> Unit = {},
    onUnfollow: () -> Unit = {},
    /** Put the row away. Called only after the clearing above, never on its own. */
    onHide: () -> Unit = {},
    focusTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    val regexFocus = remember { FocusRequester() }
    LaunchedEffect(focusTick) {
        // The requester is attached by the time an effect of this composition runs, but a row that is
        // being torn down in the same frame is not worth an exception in the app's key handler.
        if (focusTick > 0) runCatching { regexFocus.requestFocus() }
    }

    Column(modifier = modifier) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The pane bar's height exactly, because the two read as one stack of bars and a
                    // row half a bar tall would read as a message.
                    .height(PANE_BAR_HEIGHT)
                    .background(AppTheme.Colors.surface)
                    .padding(horizontal = 6.dp)
                    .testTag("filter-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The same glyph as the funnel that opens the row, so the button and the row it opened say
            // the same word. Static: the button is on the bar above, and two funnels that do different
            // things a centimetre apart is a worse row than one that does none.
            Icon(
                imageVector = Icons.Default.FilterAlt,
                contentDescription = null,
                tint = if (query.isNarrowing) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )

            // The followed trace, named. It sits beside the regex rather than replacing it: they are two
            // filters of different kinds and both are in force, so hiding one while the other is on
            // would be the app narrowing a view without saying so.
            if (query.followingLabel != null) FollowingChip(query, onUnfollow)

            RegexField(query.global.regex, onRegexChange, regexFocus)

            DirectionBox("In", query.global.showIncoming, "filter-row-in", onIncomingChange)
            DirectionBox("Out", query.global.showOutgoing, "filter-row-out", onOutgoingChange)

            Spacer(modifier = Modifier.weight(1f))

            // The one way out, and it undoes everything before it goes. A ✕ that merely hid the row
            // would leave the panes narrowed by controls nobody can see, which is the defect.
            TooltipIconButton(
                tooltip = "Clear filter and close",
                onClick = {
                    onRegexChange("")
                    onIncomingChange(true)
                    onOutgoingChange(true)
                    onUnfollow()
                    onHide()
                },
                modifier = Modifier.size(28.dp).testTag("filter-row-close"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear filter and close",
                    tint = AppTheme.Colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** The followed trace, its counts, what it could not show, and the ✕ that stops following. */
@Composable
private fun FollowingChip(
    query: FilterQuery,
    onUnfollow: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(22.dp)
                .background(AppTheme.Colors.primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp)
                .testTag("following-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text =
                buildString {
                    append("Following ").append(query.followingLabel)
                    append(" · ").append(query.followingSessionCount).append(" session")
                    if (query.followingSessionCount != 1) append("s")
                    append(" · ").append(query.followingMessageCount).append(" message")
                    if (query.followingMessageCount != 1) append("s")
                    // What a first row cannot say for itself: this exchange opened before what the pane
                    // still holds. Better said here than silently absent.
                    if (query.followingTruncatedOn.isNotEmpty()) {
                        append(" · history lost on ").append(query.followingTruncatedOn.joinToString(", "))
                    }
                },
            color = AppTheme.Colors.background,
            fontSize = 11.sp,
            modifier = Modifier.testTag("following-chip-label"),
        )
        Text(
            text = "✕",
            color = AppTheme.Colors.background,
            fontSize = 11.sp,
            modifier =
                Modifier
                    .testTag("unfollow-chip")
                    .clickable { onUnfollow() },
        )
    }
}

/** The pattern every pane is narrowed by, typed straight through: a filter that lags is a filter that lies. */
@Composable
private fun RegexField(
    regex: String,
    onRegexChange: (String) -> Unit,
    focus: FocusRequester,
) {
    Row(
        modifier =
            Modifier
                .height(22.dp)
                .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = regex,
            onValueChange = onRegexChange,
            modifier = Modifier.width(240.dp).focusRequester(focus).testTag("filter-row-regex"),
            singleLine = true,
            textStyle = TextStyle(fontSize = 11.sp, color = AppTheme.Colors.text),
            cursorBrush = SolidColor(AppTheme.Colors.primary),
            decorationBox = { innerTextField ->
                Box {
                    if (regex.isEmpty()) {
                        Text(
                            text = "Filter all panes (regex)",
                            fontSize = 11.sp,
                            color = AppTheme.Colors.textSecondary,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * One direction, ticked or not, over every pane.
 *
 * The whole chip is the click target and the box inside it is drawn rather than clickable, so a click on
 * the tick and a click on the word do the same single thing. The pair used to answer separately, which
 * meant a click that landed on the box itself toggled twice and looked like a button that did nothing.
 */
@Composable
private fun DirectionBox(
    label: String,
    on: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(22.dp)
                .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                .clickable { onChange(!on) }
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = label
                    selected = on
                }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Checkbox(
                checked = on,
                onCheckedChange = null,
                modifier = Modifier.scale(0.75f),
                colors =
                    CheckboxDefaults.colors(
                        checkedColor = AppTheme.Colors.primary,
                        uncheckedColor = AppTheme.Colors.textSecondary,
                        checkmarkColor = AppTheme.Colors.surface,
                    ),
            )
        }
        Text(text = label, fontSize = 11.sp, color = AppTheme.Colors.text)
    }
}
