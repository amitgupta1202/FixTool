package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.MessageColorScheme

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
 * **Which directions every pane shows, as the three segments draw them.**
 *
 * This was two ticks, `In` and `Out`, and the pair had two defects between them. The ticks were the same
 * glyph, so the only thing telling them apart was a word, and the filter dropped its words below 300dp by a
 * rule of its own that [ToolbarFold] never counted: a toolbar with every chip still wearing its word drew two
 * anonymous ticks in the middle of it, the reverse of the fold order the toolbar promises. And unticking both
 * was a state that showed nothing at all, which nobody wants and a pair of ticks offers anyway.
 *
 * Three segments hold the three states worth having, and their words are the grid's own `IN` and `OUT`, in
 * the colours the grid prints them in. A word that short never has to fold, so there is nothing left for a
 * narrow toolbar to take away. Both-off is still reachable through the control surface, which sets the two
 * booleans separately; it draws with no segment pressed and the funnel lit.
 */
internal enum class DirectionChoice(
    /** What the segment prints. */
    val word: String,
    /** What it says on hover, and its content description. */
    val tooltip: String,
    val tag: String,
    val showIncoming: Boolean,
    val showOutgoing: Boolean,
) {
    BOTH("Both", "Incoming and outgoing", "toolbar-filter-both", showIncoming = true, showOutgoing = true),
    IN("IN", "Incoming only", "toolbar-filter-in", showIncoming = true, showOutgoing = false),
    OUT("OUT", "Outgoing only", "toolbar-filter-out", showIncoming = false, showOutgoing = true),
    ;

    companion object {
        /** The segment in force, or null for the both-off state only the control surface can set. */
        fun of(
            showIncoming: Boolean,
            showOutgoing: Boolean,
        ): DirectionChoice? = entries.firstOrNull { it.showIncoming == showIncoming && it.showOutgoing == showOutgoing }
    }
}

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
     * in its box and its direction segment pressed, rather than by a row that refuses to close.
     */
    val isNarrowing: Boolean
        get() = global.regex.isNotBlank() || !global.showIncoming || !global.showOutgoing || followingLabel != null
}

/**
 * **The global filter, in the middle of the toolbar.**
 *
 * The regex, the direction segments and the Following chip had a row of their own under the pane bar for
 * exactly one build. That row cost a line of pane height in both layouts to hold four controls, and it
 * bought a rule, *a filter cannot go silent*, that presence buys for nothing: the controls are on the
 * toolbar, always, so a regex narrowing every pane is a regex a reader can see without opening anything.
 * The toolbar's middle was empty, which is where a query over everything belongs.
 *
 * It is the row's flexible child, centred between two weighted spacers, so it takes the room the groups
 * either side do not want, up to a readable maximum. Nothing in it folds: the regex box is the one thing
 * that gives way. See [Toolbar].
 */
@Composable
@Suppress("LongParameterList")
fun ToolbarFilter(
    query: FilterQuery = FilterQuery(),
    onRegexChange: (String) -> Unit = {},
    onIncomingChange: (Boolean) -> Unit = {},
    onOutgoingChange: (Boolean) -> Unit = {},
    onUnfollow: () -> Unit = {},
    /** The grid's direction colours, so `IN` and `OUT` here are the `IN` and `OUT` of every pane. */
    messageColors: MessageColorScheme = MessageColorScheme.default(),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag("toolbar-filter"),
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

        DirectionSegments(
            current = DirectionChoice.of(query.global.showIncoming, query.global.showOutgoing),
            messageColors = messageColors,
            onSelect = { choice ->
                onIncomingChange(choice.showIncoming)
                onOutgoingChange(choice.showOutgoing)
            },
        )
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
 * **Both, IN and OUT, joined.** The layout segments' construction, so the toolbar has one pressed look.
 *
 * The group's ground is [AppTheme.Colors.border] and the pressed segment is [AppTheme.Colors.surface], the
 * relation [PaneViewControls] draws. `IN` and `OUT` keep their direction colour whether pressed or not,
 * because the colour is what names the direction and the fill is what says it is in force; `Both` is plain
 * text, brighter when pressed.
 */
@Composable
private fun DirectionSegments(
    current: DirectionChoice?,
    messageColors: MessageColorScheme,
    onSelect: (DirectionChoice) -> Unit,
) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AppTheme.Colors.border),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        DirectionChoice.entries.forEach { choice ->
            val pressed = choice == current
            val colour =
                when (choice) {
                    DirectionChoice.BOTH -> if (pressed) AppTheme.Colors.text else AppTheme.Colors.textSecondary
                    DirectionChoice.IN -> messageColors.getIncomingColor(isBright = true)
                    DirectionChoice.OUT -> messageColors.getOutgoingColor(isBright = true)
                }
            AppTooltip(choice.tooltip) {
                Box(
                    modifier =
                        Modifier
                            .height(SEGMENT_HEIGHT)
                            .background(if (pressed) AppTheme.Colors.surface else Color.Transparent)
                            // The segment already in force does nothing when clicked again. A segmented control
                            // selects; one whose second click went back to Both would be a cycle in disguise.
                            .clickable { if (!pressed) onSelect(choice) }
                            .padding(horizontal = 6.dp)
                            .semantics {
                                contentDescription = choice.tooltip
                                selected = pressed
                            }.testTag(choice.tag),
                    contentAlignment = Alignment.Center,
                ) {
                    val badge = choice != DirectionChoice.BOTH
                    Text(
                        text = choice.word,
                        color = colour,
                        // The grid's own type for IN and OUT: 10sp monospace.
                        fontSize = if (badge) 10.sp else 11.sp,
                        fontFamily = if (badge) FontFamily.Monospace else null,
                        fontWeight = if (pressed) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
