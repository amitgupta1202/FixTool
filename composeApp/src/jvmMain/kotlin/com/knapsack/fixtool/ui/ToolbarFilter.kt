package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The height of the filter's own chips, a little under the toolbar chips they sit between. */
private val FILTER_CHIP_HEIGHT = 22.dp

/**
 * The widest the Following chip is allowed to be, past which its sentence ellipsises.
 *
 * Wide enough for a label and both counts, and the toolbar gives the filter this much again while
 * something is followed, so naming a trace does not cost the regex field its box. See [Toolbar].
 */
internal val FOLLOWING_CHIP_MAX_WIDTH = 220.dp

/**
 * The width below which `In` and `Out` are their ticks alone.
 *
 * Everything in the filter but the regex box is fixed: the funnel and two ticks with their words come to
 * about 140dp, so a 200dp filter would leave 60dp to type a pattern into and a narrower one nothing at
 * all. Without the words the same furniture is about 90dp, which is the difference between a box and a
 * sliver.
 */
private val DIRECTION_WORDS_WIDTH = 300.dp

/**
 * **One query over every pane: the regex, the two directions, and the followed trace.**
 *
 * The three used to be three unrelated things in the middle of the toolbar. They are one question,
 * *which messages am I looking at*, asked of every pane at once, which is why they share a value and a
 * control.
 *
 * [global] is [MessageFilters.Global] itself rather than a copy of its three fields, so what is drawn is
 * exactly what is ANDed into each pane and there is no second place for the answer to be kept. The
 * followed trace is named rather than flagged: a narrowing nobody can name is the silent-filter defect
 * this control exists to remove.
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
     * **Whether anything is narrowing the panes right now.**
     *
     * Only the funnel's tint turns on it now. The rule it used to serve, that a filter must never narrow
     * a view without saying so, is kept by the control being on the toolbar at all times, with its regex
     * in its box and its ticks on its boxes, rather than by a row that refuses to close.
     */
    val isNarrowing: Boolean
        get() = global.regex.isNotBlank() || !global.showIncoming || !global.showOutgoing || followingLabel != null
}

/**
 * **The global filter, in the middle of the toolbar.**
 *
 * The regex, the direction boxes and the Following chip had a row of their own under the pane bar for
 * exactly one build. That row cost a line of pane height in both layouts to hold four controls, and it
 * bought a rule, *a filter cannot go silent*, that presence buys for nothing: the controls are on the
 * toolbar, always, so a regex narrowing every pane is a regex a reader can see without opening anything.
 * The toolbar's middle was empty, which is where a query over everything belongs.
 *
 * It is the row's flexible child, centred between two weighted spacers, so it takes the room the groups
 * either side do not want, up to a readable maximum. See [Toolbar].
 */
@Composable
fun ToolbarFilter(
    query: FilterQuery = FilterQuery(),
    onRegexChange: (String) -> Unit = {},
    onIncomingChange: (Boolean) -> Unit = {},
    onOutgoingChange: (Boolean) -> Unit = {},
    onUnfollow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // The filter's own fold, and it has exactly one thing worth folding. Everything here but the
        // regex is a fixed width, so on a narrow toolbar the box a pattern is typed into is the only
        // thing that can give way, and it was giving way to two words. The ticks keep the state, which
        // is what those words were labelling, and say their direction on hover.
        val words = maxWidth >= DIRECTION_WORDS_WIDTH

        Row(
            modifier = Modifier.fillMaxWidth().testTag("toolbar-filter"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Static, and the only thing here that is not a control: it says what this run of boxes is,
            // and it goes bright while any of them is narrowing something.
            Icon(
                imageVector = Icons.Default.FilterAlt,
                contentDescription = null,
                tint = if (query.isNarrowing) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )

            // The followed trace, named. It sits beside the regex rather than replacing it: they are two
            // filters of different kinds and both are in force, so hiding one while the other is on would
            // be the app narrowing a view without saying so.
            if (query.followingLabel != null) FollowingChip(query, onUnfollow)

            RegexField(query.global.regex, onRegexChange, modifier = Modifier.weight(1f))

            DirectionBox("In", query.global.showIncoming, "toolbar-filter-in", words, onIncomingChange)
            DirectionBox("Out", query.global.showOutgoing, "toolbar-filter-out", words, onOutgoingChange)
        }
    }
}

/**
 * The followed trace, its counts, what it could not show, and the ✕ that stops following.
 *
 * Capped at [FOLLOWING_CHIP_MAX_WIDTH] and ellipsised, because the sentence grows with the trace and an
 * uncapped chip would push the regex field it sits beside off the end of the filter. The ✕ is measured
 * first and the sentence takes what is left, so the way out is never the part that gets clipped.
 */
@Composable
private fun FollowingChip(
    query: FilterQuery,
    onUnfollow: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(FILTER_CHIP_HEIGHT)
                .widthIn(max = FOLLOWING_CHIP_MAX_WIDTH)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("following-chip-label"),
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(FILTER_CHIP_HEIGHT)
                .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = regex,
            onValueChange = onRegexChange,
            modifier = Modifier.fillMaxWidth().testTag("toolbar-filter-regex"),
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
                            maxLines = 1,
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
    /** False on a filter with no room for the word, where the tick stands alone and hover says which. */
    words: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val box: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .height(FILTER_CHIP_HEIGHT)
                    .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                    .clickable { onChange(!on) }
                    .padding(horizontal = if (words) 8.dp else 4.dp)
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
            if (words) Text(text = label, fontSize = 11.sp, color = AppTheme.Colors.text)
        }
    }
    if (words) box() else AppTooltip(label) { box() }
}
