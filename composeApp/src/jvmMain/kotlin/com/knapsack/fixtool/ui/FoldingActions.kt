package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **One action a folding bar can draw, and everything the fold needs to know about it.**
 *
 * The fold rule is declared here rather than inferred from the row, because the two questions a bar has to
 * answer when it runs out of room — which control gives up its word first, and which one leaves the bar
 * altogether — are not answerable from a control's position. Folding from the right by position would take
 * Close off a pane header before it took Move left, which is the one thing the fold must never do.
 *
 * @param label the word. It is the chip's text while there is room, the tooltip once there is not, and the
 *   row the ⋯ menu draws when the action folds, so there is exactly one string to keep right.
 * @param icon the glyph. Null for an action that has no glyph and can therefore only be a word or a menu
 *   row — it skips the icon stage and folds straight into ⋯.
 * @param labelled whether the action is worth a word at full width. Actions that get pressed in bursts
 *   while the eye is elsewhere stay glyphs at every width and set this false.
 * @param foldRank lower folds first. Equal ranks fold right to left, so a bar that declares nothing still
 *   behaves like the reading order.
 * @param neverFolds the controls a bar is not allowed to lose: a pane's Close, a dock's Hide.
 * @param shortcut printed dim beside the menu row and appended to the tooltip, so an action is learned at
 *   whatever width it is found at.
 */
data class BarAction(
    val label: String,
    val icon: ImageVector?,
    val onClick: () -> Unit,
    val tag: String,
    val enabled: Boolean = true,
    val shortcut: String? = null,
    val labelled: Boolean = false,
    val foldRank: Int = 0,
    val neverFolds: Boolean = false,
    /** Said instead of the bare label when the action is refused, so a dark control carries its reason. */
    val disabledReason: String? = null,
    /**
     * What the word cannot carry: what a click costs, what the pane will say afterwards.
     *
     * Kept separate from [label] because the label has to survive being a menu row and a chip's text, where
     * a sentence would not fit — and because the sentence is the half that is worth saying at every width.
     */
    val hint: String? = null,
) {
    /** What a glyph says on hover: its word, its shortcut, and whatever the word could not carry. */
    val hover: String
        get() =
            listOfNotNull(
                if (shortcut != null) "$label · $shortcut" else label,
                if (!enabled) disabledReason else hint,
            ).joinToString(" — ")

    /**
     * What a chip that still has its word says on hover, or null when all it could say is the word again.
     *
     * The toolbar note's rule, kept: a tooltip repeating a visible label is noise.
     */
    val chipHover: String?
        get() = hover.takeIf { shortcut != null || hint != null || (!enabled && disabledReason != null) }
}

/** What [foldBar] decided: whether words survive, what is drawn, and what went into the ⋯ menu. */
internal data class BarFold(
    /** True while the actions that asked for a word still have one. */
    val labelled: Boolean,
    /** Drawn as controls, in the order they were declared. */
    val shown: List<BarAction>,
    /** In the ⋯ menu, in the order they were declared. */
    val folded: List<BarAction>,
)

/**
 * **What a bar's controls cost at this density**, so the rule is arithmetic rather than a measurement of a
 * row that has already been drawn.
 *
 * [labelPx] is a function because a chip's width is a text measurement, and keeping it a parameter is what
 * lets [foldBar] be tested without a renderer — the same bargain [middleEllipsis] makes.
 */
internal data class BarMetrics(
    val buttonPx: Int,
    val gapPx: Int,
    val overflowPx: Int,
    val labelPx: (BarAction) -> Int,
)

/** What [drawn] costs, as controls, with or without their words and with or without the ⋯ beside them. */
private fun barWidth(
    actions: List<BarAction>,
    drawn: List<Int>,
    labelled: Boolean,
    overflow: Boolean,
    metrics: BarMetrics,
): Int {
    val controls = drawn.size + if (overflow) 1 else 0
    if (controls == 0) return 0
    val sum =
        drawn.sumOf { i ->
            val action = actions[i]
            if (labelled && action.labelled) metrics.labelPx(action) else metrics.buttonPx
        }
    return sum + (if (overflow) metrics.overflowPx else 0) + metrics.gapPx * (controls - 1)
}

/**
 * **The fold rule, as arithmetic: three stages, always in this order.**
 *
 * One, the labelled chips drop to their glyph and keep the word as a tooltip. Two, actions fold from the
 * right into one ⋯ menu, lowest [BarAction.foldRank] first. Three is not here: it is the title giving up
 * its middle, which belongs to whatever drew the title, and it happens only once the bar has folded
 * everything it is allowed to.
 *
 * Added up rather than measured as a whole because a bar must know what it can afford *before* it composes
 * the controls: measuring the drawn row would mean drawing a row that does not fit and then drawing it
 * again.
 *
 * **Folding one action saves nothing**, because the ⋯ that replaces it is the same width as the glyph it
 * took away. So a bar that has run out of room gives up at least two actions, or none — which is why the
 * loop below keeps going rather than stopping at the first fold and believing it has helped.
 *
 * [reservedPx] is everything in the bar that is not an action: the title's floor, a leading slot, the
 * trailing control that never folds, and the padding. Reserving it is what makes "never draws half a
 * control" true — the fold gives up actions until what is left fits in the room that is genuinely free.
 */
internal fun foldBar(
    actions: List<BarAction>,
    availablePx: Int,
    reservedPx: Int,
    metrics: BarMetrics,
): BarFold {
    // An unmeasured bar — the frame before BoxWithConstraints has a width — draws everything rather than
    // folding to nothing and springing open on the next frame, which is a flicker on every pane that opens.
    if (availablePx <= 0) return BarFold(labelled = true, shown = actions, folded = emptyList())

    // An action with no glyph cannot become one, so it is a menu row from the start rather than an empty
    // button. A glyphless action that never folds is a contradiction and is drawn anyway: a caller saying
    // "this must stay" is obeyed.
    val menuOnly = actions.indices.filter { actions[it].icon == null && !actions[it].neverFolds }.toSet()
    val drawn = actions.indices.filterNot { it in menuOnly }
    val room = availablePx - reservedPx
    val gone = menuOnly.toMutableSet()

    val labelled = menuOnly.isEmpty() && barWidth(actions, drawn, labelled = true, overflow = false, metrics) <= room
    val glyphsFit = barWidth(actions, drawn, labelled = false, overflow = menuOnly.isNotEmpty(), metrics) <= room
    if (!labelled && !glyphsFit) {
        // Lowest rank first, and within a rank the rightmost first, so a bar that declares no ranks folds
        // the way a reader expects and one that does is obeyed.
        val order = drawn.filterNot { actions[it].neverFolds }.sortedWith(compareBy({ actions[it].foldRank }, { -it }))
        for (i in order) {
            gone += i
            val left = actions.indices.filterNot { it in gone }
            if (barWidth(actions, left, labelled = false, overflow = true, metrics) <= room) break
        }
    }
    return BarFold(
        labelled = labelled,
        shown = actions.filterIndexed { i, _ -> i !in gone },
        folded = actions.filterIndexed { i, _ -> i in gone },
    )
}

/**
 * **A row of actions that folds rather than wraps or clips.**
 *
 * Every bar in the app is built on this: the dock headers, the pane header, the editor's toolbar, the load
 * documents' header, the toolbar itself. The height never changes, so a control is found at the same
 * height at every width, and the ⋯ menu carries the word and the shortcut the button would have shown —
 * nothing is reachable at one width and gone at another.
 *
 * @param available the bar's own width, from its [androidx.compose.foundation.layout.BoxWithConstraints]
 * @param reserved everything in that width this row may not spend: the title's floor, the never-folding
 *   trailing control, a leading slot, the padding
 */
@Composable
internal fun FoldingActions(
    actions: List<BarAction>,
    available: Dp,
    reserved: Dp,
    modifier: Modifier = Modifier,
    overflowTag: String = "bar-overflow",
) {
    if (actions.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = LABEL_SIZE))

    val fold =
        remember(actions, available, reserved, style) {
            with(density) {
                foldBar(
                    actions = actions,
                    availablePx = available.roundToPx(),
                    reservedPx = reserved.roundToPx(),
                    metrics =
                        BarMetrics(
                            buttonPx = BAR_BUTTON.roundToPx(),
                            gapPx = BAR_GAP.roundToPx(),
                            overflowPx = BAR_BUTTON.roundToPx(),
                            labelPx = { action ->
                                val word = AnnotatedString(action.label)
                                val line = measurer.measure(word, style, softWrap = false, maxLines = 1)
                                line.size.width + CHIP_PADDING.roundToPx()
                            },
                        ),
                )
            }
        }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
    ) {
        fold.shown.forEach { action ->
            if (fold.labelled && action.labelled && action.icon != null) {
                BarChip(action)
            } else {
                BarIconButton(action)
            }
        }
        if (fold.folded.isNotEmpty()) BarOverflow(fold.folded, overflowTag)
    }
}

/** An action with its word: the same chip the toolbar draws, one size down, because a dock bar is denser. */
@Composable
private fun BarChip(action: BarAction) {
    val tint = if (action.enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled
    val chip: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .height(BAR_BUTTON)
                    .background(AppTheme.Colors.border, RoundedCornerShape(3.dp))
                    .let { if (action.enabled) it.clickable(onClick = action.onClick) else it }
                    .padding(horizontal = 6.dp)
                    .semantics {
                        if (!action.enabled) disabled()
                        contentDescription = action.hover
                    }.testTag(action.tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            action.icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(BAR_ICON)) }
            Text(action.label, color = tint, fontSize = LABEL_SIZE, maxLines = 1)
        }
    }
    val hover = action.chipHover
    if (hover != null) AppTooltip(hover) { chip() } else chip()
}

/** An action as a glyph, with the word it lost in the tooltip. */
@Composable
private fun BarIconButton(action: BarAction) {
    val tint = if (action.enabled) AppTheme.Colors.textSecondary else AppTheme.Colors.textDisabled
    TooltipIconButton(
        tooltip = action.hover,
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier.size(BAR_BUTTON).testTag(action.tag),
    ) {
        action.icon?.let {
            Icon(it, contentDescription = action.label, tint = tint, modifier = Modifier.size(BAR_ICON))
        }
    }
}

/**
 * **The one overflow the app has: ⋯, tooltip "More".**
 *
 * Three glyphs and three wordings shipped before this — ChevronRight with "More Options" in the editor,
 * MoreHoriz with "More actions" in the split pane header, a bare "⋯" on the rail and the load documents.
 * The menu draws the label and the shortcut the folded button would have carried.
 */
@Composable
private fun BarOverflow(folded: List<BarAction>, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            tooltip = "More",
            onClick = { open = true },
            modifier = Modifier.size(BAR_BUTTON).testTag(tag),
        ) {
            Icon(
                Icons.Default.MoreHoriz,
                contentDescription = "More",
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(BAR_ICON),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            folded.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                action.label,
                                color = if (action.enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
                                fontSize = LABEL_SIZE,
                            )
                            action.shortcut?.let {
                                Text(it, color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
                            }
                        }
                    },
                    enabled = action.enabled,
                    leadingIcon =
                        action.icon?.let {
                            {
                                Icon(
                                    it,
                                    contentDescription = null,
                                    tint = AppTheme.Colors.textSecondary,
                                    modifier = Modifier.size(BAR_ICON),
                                )
                            }
                        },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                    modifier = Modifier.testTag("${action.tag}-menu"),
                )
            }
        }
    }
}

/** Every control in a folding bar is this tall, at every width. A bar whose height moves is two bars. */
internal val BAR_BUTTON = 20.dp

/** The glyph inside it. */
internal val BAR_ICON = 14.dp

/** Between controls. */
internal val BAR_GAP = 2.dp

/** A chip's horizontal padding plus the gap between its glyph and its word — what a label costs beyond text. */
private val CHIP_PADDING = 12.dp + 4.dp + BAR_ICON

/** The register a bar speaks: 11sp, the same as every dock title. */
private val LABEL_SIZE = 11.sp
