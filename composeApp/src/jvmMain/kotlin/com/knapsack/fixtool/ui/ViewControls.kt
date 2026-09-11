package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewArray
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixMessageSession

/** A segment is a tab's height without a tab's chrome, so the group sits inside the bar rather than filling it. */
private val SEGMENT_HEIGHT = 22.dp

/** The gap between two segments, wide enough for the group's own ground to read as a hairline rule. */
private val SEGMENT_GAP = 1.dp

/** The fixed mark slot every menu row carries, so the four labels start at the same x whatever their state. */
private val MARK_SLOT = 16.dp

/** Between the layout group and `View ▾`, and between anything else these controls come to hold. */
private val CONTROL_GAP = 6.dp

/**
 * Four characters of 11sp label, at the 0.6em average advance mixed-case sans text runs to.
 *
 * Both labels drawn here are four letters long, "Tabs" and "View", so one figure covers both.
 */
private val LABEL_WIDTH = 26.dp

/**
 * **The three pane layouts, in the order the segmented control draws them.**
 *
 * This was one button that cycled, and a three-state button that cycles can never show what the next click
 * does. That is why its tooltip had to read "Layout: Tabs (click for Horizontal Split)": a control
 * explaining its own mechanism instead of naming its own state. Three segments say all three states at
 * once, the pressed one says which is in force, and a click selects rather than advances.
 *
 * Only [TABS] prints a word beside its glyph. One label is what makes the group self-labelling, which is
 * how the design note draws it, and three labels would be a sentence where a hairline of icons will do.
 */
enum class ViewMode(
    val icon: ImageVector,
    /** What the segment says on hover, its content description, and its name in the folded menu. */
    val tooltip: String,
    /** The tag its segment carries, so a test names a layout instead of a position in a row. */
    val testTag: String,
    /** The word printed beside the glyph, or null for a segment that is its glyph alone. */
    val label: String? = null,
) {
    TABS(Icons.Default.Tab, "Tabs", "layout-tabs", label = "Tabs"),
    SPLIT_HORIZONTAL(Icons.Default.ViewAgenda, "Split horizontal", "layout-split-horizontal"),
    SPLIT_VERTICAL(Icons.Default.ViewArray, "Split vertical", "layout-split-vertical"),
}

/**
 * `View ▾` on its own: 8dp of padding each side, a [LABEL_WIDTH] word, a 2dp gap and a 14dp chevron.
 *
 * This is what the view controls still measure once the segments have folded into the menu, which is the
 * last thing the toolbar gives up. See [ToolbarFold].
 */
internal val VIEW_MENU_WIDTH = (16.dp + LABEL_WIDTH + 2.dp + 14.dp)

/**
 * The width the whole set of view controls needs: the three segments, their gaps, and `View ▾`.
 *
 * Added up rather than measured, from the sizes this file actually draws. The Tabs segment is 6dp of
 * padding each side, a 14dp glyph, a 4dp gap and a [LABEL_WIDTH] word, which is 56dp. The other two are
 * padding and glyph alone, 26dp each. Two [SEGMENT_GAP] hairlines separate the three, and a [CONTROL_GAP]
 * sits between the group and the menu. That comes to **174dp**.
 */
internal val VIEW_CONTROLS_WIDTH =
    (12.dp + 14.dp + 4.dp + LABEL_WIDTH) +
        (12.dp + 14.dp) * 2 +
        SEGMENT_GAP * 2 +
        CONTROL_GAP +
        VIEW_MENU_WIDTH

/**
 * **The four view controls, at the right of the toolbar.**
 *
 * These say how every pane *draws*: the layout, whether a row is raw FIX or parsed fields, whether the
 * session envelope is shown, and whether rows fold into business exchanges. They had a bar of their own
 * above the panes for one build, which is where a control beside the thing it changes belongs in the
 * abstract and where nothing else was, so the whole bar was a line of pane height holding two controls.
 * Screen is the scarcer thing. The toolbar already has a right end, and these are what an application
 * keeps there.
 *
 * The segmented group's ground is [AppTheme.Colors.border] and the pressed segment is
 * [AppTheme.Colors.surface]. The toolbar itself is `surface`, so a pressed segment can only read as
 * pressed against a lighter group ground: the same relation the toolbar's chips already have to it.
 *
 * `View ▾` carries no tooltip. It is the one control here that says its own name, and a tooltip repeating
 * a visible label is noise.
 *
 * @param onToggleGridView a *flip* of the all-sessions row mode, so the radio pair only calls it when the
 *   chosen mode differs from [sessionViewMode]
 * @param folded true on a toolbar too narrow for the segments, computed by [Toolbar] against
 *   [VIEW_CONTROLS_WIDTH]. The segments go before the menu, because a menu can carry the layout as three
 *   more named rows and nothing else here can be reached at all once it has been clipped away.
 */
@Composable
@Suppress("LongParameterList")
fun PaneViewControls(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    sessionViewMode: FixMessageSession.ViewMode,
    onToggleGridView: () -> Unit,
    hideProtocolTags: Boolean,
    onToggleHideProtocolTags: () -> Unit,
    groupByConversation: Boolean,
    onToggleGroupByConversation: () -> Unit,
    folded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP),
    ) {
        if (!folded) {
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppTheme.Colors.border),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            ) {
                ViewMode.entries.forEach { mode ->
                    LayoutSegment(mode = mode, current = mode == viewMode, onSelect = onViewModeChange)
                }
            }
        }

        ViewMenu(
            viewMode = viewMode,
            onViewModeChange = onViewModeChange,
            showLayoutRows = folded,
            sessionViewMode = sessionViewMode,
            onToggleGridView = onToggleGridView,
            hideProtocolTags = hideProtocolTags,
            onToggleHideProtocolTags = onToggleHideProtocolTags,
            groupByConversation = groupByConversation,
            onToggleGroupByConversation = onToggleGroupByConversation,
        )
    }
}

/**
 * One segment of the layout group: a glyph, sometimes a word, and whether its layout is the one on screen.
 *
 * The state is on the node twice because two readers ask differently: the fill and the tint for an eye,
 * `selected` for a test. The tooltip is also the content description, so the layout has a name for a
 * reader who cannot hover.
 */
@Composable
private fun LayoutSegment(
    mode: ViewMode,
    current: Boolean,
    onSelect: (ViewMode) -> Unit,
) {
    val colour = if (current) AppTheme.Colors.text else AppTheme.Colors.textSecondary
    AppTooltip(text = mode.tooltip) {
        Row(
            modifier =
                Modifier
                    .height(SEGMENT_HEIGHT)
                    .background(if (current) AppTheme.Colors.surface else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 6.dp)
                    .semantics {
                        contentDescription = mode.tooltip
                        selected = current
                    }.testTag(mode.testTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = colour,
                modifier = Modifier.size(14.dp),
            )
            mode.label?.let { word ->
                Text(text = word, color = colour, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/**
 * `View ▾` and the named lines behind it.
 *
 * Named lines with a state mark rather than four more icons: none of these is pressed often enough to earn
 * a permanent glyph in a bar, and "Hide protocol tags" beside a tick says more than an eye that changes
 * shape. Parsed and Raw are a radio pair over one setting, which is why they are grouped above the divider
 * and the other two below it.
 */
@Composable
@Suppress("LongParameterList")
private fun ViewMenu(
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    /** True on a folded toolbar, where the layout has no segments and arrives here as three more rows. */
    showLayoutRows: Boolean,
    sessionViewMode: FixMessageSession.ViewMode,
    onToggleGridView: () -> Unit,
    hideProtocolTags: Boolean,
    onToggleHideProtocolTags: () -> Unit,
    groupByConversation: Boolean,
    onToggleGroupByConversation: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier =
                Modifier
                    .height(SEGMENT_HEIGHT)
                    .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "View" }
                    .testTag("view-menu"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = "View", color = AppTheme.Colors.text, fontSize = 11.sp, maxLines = 1)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = AppTheme.Colors.text,
                modifier = Modifier.size(14.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier =
                Modifier
                    .background(AppTheme.Colors.surface)
                    .widthIn(min = 220.dp),
        ) {
            if (showLayoutRows) {
                // A prefixed row, not a sub-menu: Material3's DropdownMenu has none, and this repo
                // already writes the relation this way — the Run menu's "Load set ▸  …" rows are the
                // same form. A row that says where it belongs beats a chevron that opens nothing.
                ViewMode.entries.forEach { mode ->
                    ViewMenuRow(
                        label = "Layout ▸  ${mode.tooltip}",
                        on = mode == viewMode,
                        mark = ViewMark.CHECK,
                        tag = "view-${mode.testTag}",
                    ) {
                        // A layout pick is a radio pick, and the panes under this menu are about to
                        // relayout, so the menu closes either way.
                        expanded = false
                        onViewModeChange(mode)
                    }
                }
                HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
            }

            // A pick is an answer, so the menu closes on either row mode. The callback is a flip of the
            // all-sessions mode, so picking the mode already in force calls nothing: flipping there would
            // flip the view away from what was asked for.
            ViewMenuRow(
                label = "Parsed rows",
                on = sessionViewMode == FixMessageSession.ViewMode.PARSED,
                mark = ViewMark.RADIO,
                tag = "view-parsed",
            ) {
                expanded = false
                if (sessionViewMode != FixMessageSession.ViewMode.PARSED) onToggleGridView()
            }
            ViewMenuRow(
                label = "Raw rows",
                on = sessionViewMode == FixMessageSession.ViewMode.RAW,
                mark = ViewMark.RADIO,
                tag = "view-raw",
            ) {
                expanded = false
                if (sessionViewMode != FixMessageSession.ViewMode.RAW) onToggleGridView()
            }

            HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)

            // The two checkmark rows leave the menu open, which is how a checkmark menu behaves on macOS:
            // the tick moves under the pointer, and a reader who wanted both settings gets both without
            // opening the menu twice.
            ViewMenuRow(
                label = "Hide protocol tags",
                on = hideProtocolTags,
                mark = ViewMark.CHECK,
                tag = "view-hide-tags",
                onClick = onToggleHideProtocolTags,
            )
            ViewMenuRow(
                label = "Group by conversation",
                on = groupByConversation,
                mark = ViewMark.CHECK,
                tag = "view-group",
                onClick = onToggleGroupByConversation,
            )
        }
    }
}

/** What a menu row draws when it is on: a dot for one of a pair, a tick for a setting that stands alone. */
private enum class ViewMark(
    val icon: ImageVector,
    val size: Dp,
) {
    RADIO(Icons.Default.Circle, 8.dp),
    CHECK(Icons.Default.Check, 14.dp),
}

/**
 * One line of the View menu: its mark slot, its name, and what clicking it does.
 *
 * The mark slot is a fixed [MARK_SLOT] box whether or not anything is in it, so the labels line up however
 * the settings happen to stand. An off row draws nothing rather than a hollow glyph: an outline beside a
 * filled one is a difference a reader has to look for, and an empty slot is not.
 */
@Composable
private fun ViewMenuRow(
    label: String,
    on: Boolean,
    mark: ViewMark,
    tag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = label, color = AppTheme.Colors.text, fontSize = 12.sp) },
        leadingIcon = {
            Box(modifier = Modifier.size(MARK_SLOT), contentAlignment = Alignment.Center) {
                if (on) {
                    Icon(
                        imageVector = mark.icon,
                        contentDescription = null,
                        tint = AppTheme.Colors.primary,
                        modifier = Modifier.size(mark.size),
                    )
                }
            }
        },
        onClick = onClick,
        modifier =
            Modifier
                .semantics {
                    contentDescription = label
                    selected = on
                }.testTag(tag),
    )
}
