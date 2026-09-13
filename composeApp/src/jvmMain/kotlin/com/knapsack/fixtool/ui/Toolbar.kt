package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The one row, in five kinds of thing, with the filter down the middle.**
 *
 * Left to right: where am I (the workspace), what am I looking at (the filter), what is connected (Connect,
 * Disconnect all, Close all), what do I do to every session at once (Capture, Search, Blank line), what is
 * dangerous (Clear all), what runs when I press ▶ (the run configuration widget), how do the panes draw
 * (the layout segments and View ▾), and the two the operating system expects at the far right. A 1dp rule
 * separates each group from the next, and every group answers one question.
 *
 * The run widget is its own group rather than the fourth chip of the sessions group, which is where `Run ▾`
 * sat. A menu whose rows each started something is a different kind of thing from a button that connects a
 * profile, and putting them in one group made the row read as "five ways to make something happen". Now the
 * sessions group is only about sessions and the widget answers one question of its own, the one an IDE's
 * run configuration answers: what does ▶ do.
 *
 * Two things came back here after a build spent elsewhere. The filter had a row of its own under a pane
 * bar and the view controls had that bar, which is two lines of pane height for six controls, in a window
 * whose scarcest resource is pane height. The middle of this row was empty at the time. The rule that
 * row was built for, that a filter must never narrow a view without saying so, is kept by the controls
 * being here always, in the open, rather than by a row that refuses to close.
 *
 * **It never wraps.** When the window is too narrow the chips give up their words for their glyphs, the run
 * widget gives up its kind and then its name, the layout segments fold into `View ▾`, and the filter
 * shrinks. The workspace, Settings and Help never fold. See [ToolbarFold] for the order and the arithmetic.
 *
 * @param sessionControls `Disconnect all` and `Close all`, which read live session state. A slot rather
 *   than a dozen parameters, because this file has no business knowing a ViewModel. [ToolbarSessionControls]
 *   is what goes in here, and it is handed whether the chips have room for their words.
 * @param runConfiguration the run widget, handed how much of itself it has room to draw.
 *   [ToolbarRunConfiguration] is what goes in here.
 * @param viewControls [PaneViewControls], the same way, handed whether the row is too narrow for the
 *   layout segments.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
fun Toolbar(
    connectionProfiles: List<FixConnectionProfile> = emptyList(),
    isDictionaryValid: Boolean = true,
    onQuickConnect: ((String, FixConnectionProfile) -> Unit)? = null,
    onGetProfileConnectionState: ((String) -> FixConnectionState)? = null,
    /**
     * The workspace's environments. Empty — which is every workspace until someone extracts some —
     * leaves Connect exactly as it was: pick a profile and it connects.
     */
    environments: List<Environment> = emptyList(),
    onConnectProfileIn: ((FixConnectionProfile, Environment) -> Unit)? = null,
    /** The workspace switcher, top left. See [WorkspaceMenu]. */
    workspace: WorkspaceMenuState = WorkspaceMenuState(),
    /** The one query over every pane, drawn in the middle of the row. See [ToolbarFilter]. */
    filter: FilterQuery = FilterQuery(),
    onFilterRegexChange: (String) -> Unit = {},
    onFilterIncomingChange: (Boolean) -> Unit = {},
    onFilterOutgoingChange: (Boolean) -> Unit = {},
    onUnfollow: () -> Unit = {},
    onSearchAllSessions: (() -> Unit)? = null,
    onAddSeparatorToAll: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    onCaptureScenario: (() -> Unit)? = null,
    sessionControls: (@Composable (words: Boolean) -> Unit)? = null,
    runConfiguration: (@Composable (RunWidgetFold) -> Unit)? = null,
    viewControls: (@Composable (folded: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The fold is decided before anything is laid out, and from this row's own width: a Row hands a
    // non-weighted child a shrinking constraint that the child then overflows anyway, so measuring the
    // space left over would find out too late.
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surface),
    ) {
        val fold = ToolbarFold.forWidth(maxWidth)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The eight tool-window toggles are not here any more. Editor, Scenarios, Detail, Connection,
            // Order book, Latency, Terminal and Trace are tabs on the stripe of the edge each one opens
            // from, where an open window reads as a pressed tab rather than as a grey icon tinted slightly
            // less grey. See [ToolWindow] and [ToolWindowStripe]. Nothing left in this row toggles
            // anything, which frees the pressed look entirely for those stripes.
            WorkspaceMenu(state = workspace)
            GroupDivider(1)

            // The filter, centred in whatever the groups either side leave. Two weighted spacers and a
            // weighted, capped middle: it grows with the window to a width a regex can be read in, and it
            // is the last thing to give way when the window shrinks.
            Spacer(modifier = Modifier.weight(1f))
            ToolbarFilter(
                query = filter,
                onRegexChange = onFilterRegexChange,
                onIncomingChange = onFilterIncomingChange,
                onOutgoingChange = onFilterOutgoingChange,
                onUnfollow = onUnfollow,
                modifier =
                    Modifier
                        .weight(FILTER_WEIGHT, fill = false)
                        .widthIn(
                            min = FILTER_MIN_WIDTH,
                            // Room for the Following chip on top of the regex's own, because a named
                            // trace is an answer to the same question and neither should crowd the other.
                            max =
                                if (filter.followingLabel != null) {
                                    FILTER_MAX_WIDTH + FOLLOWING_CHIP_MAX_WIDTH
                                } else {
                                    FILTER_MAX_WIDTH
                                },
                        ),
            )
            Spacer(modifier = Modifier.weight(1f))
            GroupDivider(2)

            // **What is connected.** Connect ▾ picks a profile and the other two put the box back to
            // nothing. The two that follow the chip are passed as one slot, because both read live session
            // state. See [ToolbarSessionControls].
            if (onQuickConnect != null && connectionProfiles.isNotEmpty()) {
                ConnectChip(
                    connectionProfiles = connectionProfiles,
                    onQuickConnect = onQuickConnect,
                    onGetProfileConnectionState = onGetProfileConnectionState,
                    environments = environments,
                    onConnectProfileIn = onConnectProfileIn,
                    words = fold.commandWords,
                )
                Spacer(modifier = Modifier.width(CHIP_GAP))
            }
            sessionControls?.invoke(fold.commandWords)
            GroupDivider(3)

            // **What I do to every session at once.**
            AllSessionsActions(
                onCaptureScenario = onCaptureScenario,
                onSearchAllSessions = onSearchAllSessions,
                onAddSeparatorToAll = onAddSeparatorToAll,
                words = fold.actionWords,
            )
            GroupDivider(4)

            // **What is dangerous**, alone between two rules and in the warn colour Close all takes when
            // it is armed. Nothing here comes back: the panes keep their sessions and lose their messages.
            if (onClearAll != null) {
                ToolbarChip(
                    icon = Icons.Default.Delete,
                    label = "Clear all",
                    tint = AppTheme.Colors.warning,
                    onClick = onClearAll,
                    tag = "toolbar-clear-all",
                    words = fold.commandWords,
                    tooltip = "Clear all panes · no undo",
                )
            }
            GroupDivider(5)

            // **What ▶ runs.** A name and a button, the way an IDE keeps its run configuration, so the one
            // question the old `Run ▾` could not answer (what happens next) is answered by the row itself.
            runConfiguration?.invoke(fold.runWidget)
            GroupDivider(6)

            // **How the panes draw**: the three layout segments and the four named lines behind View ▾.
            viewControls?.invoke(!fold.layoutSegments)
            GroupDivider(7)

            // Settings button
            if (onOpenSettings != null) {
                TooltipIconButton(
                    tooltip = if (isDictionaryValid) "Settings" else "Settings · dictionary needs configuring",
                    onClick = onOpenSettings,
                    modifier = tooltipModifier,
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = if (isDictionaryValid) AppTheme.Colors.textSecondary else AppTheme.Colors.error,
                        modifier = tooltipIconModifier,
                    )
                }
            }

            // Help button
            if (onOpenHelp != null) {
                TooltipIconButton(
                    tooltip = "Help",
                    onClick = onOpenHelp,
                    modifier = tooltipModifier,
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = "Help",
                        tint = AppTheme.Colors.textSecondary,
                        modifier = tooltipIconModifier,
                    )
                }
            }
        }
    }
}

private val tooltipModifier = Modifier.size(32.dp)
private val tooltipIconModifier = Modifier.size(20.dp)

/** The gap between two chips of the same group. The groups themselves are separated by [GroupDivider]. */
private val CHIP_GAP = 8.dp

/**
 * **Connect ▾: the profile selector, and the demo workspace's home.**
 *
 * It was "Quick Connect" for as long as it was the shortcut past the Connection panel, which is a name that
 * describes how it came to exist rather than what it does. Every other chip in the row is a verb, and this
 * one is too: it connects a profile. The word "quick" was also the only thing implying there is a slow way,
 * and the Connection panel is not a slower connect, it is where a profile is authored.
 *
 * Shown even with no saved profiles, because that is the one moment the trailing item matters: a fresh
 * install has nothing to connect to, and the demo is how it gets something. The item sits after the
 * profiles the way "Edit Configurations…" trails a run-configuration list — the things you can pick, then
 * the thing that makes more of them. Once the workspace is installed its three profiles are ordinary rows
 * above with their own state dots, so there is no second status light here to disagree with them.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun ConnectChip(
    connectionProfiles: List<FixConnectionProfile>,
    onQuickConnect: (String, FixConnectionProfile) -> Unit,
    onGetProfileConnectionState: ((String) -> FixConnectionState)?,
    environments: List<Environment>,
    onConnectProfileIn: ((FixConnectionProfile, Environment) -> Unit)?,
    words: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    // Which profile is being asked "in which environment?". Null means the profile list is showing. Reset
    // with the menu, so it never reopens on an environment page.
    var pickingEnvironmentFor by remember { mutableStateOf<FixConnectionProfile?>(null) }
    val close = {
        expanded = false
        pickingEnvironmentFor = null
    }

    Box {
        ToolbarChip(
            icon = Icons.Default.PlayArrow,
            label = "Connect",
            tint = AppTheme.Colors.text,
            iconTint = AppTheme.Colors.primary,
            onClick = { expanded = true },
            tag = "connect",
            chevron = true,
            words = words,
            tooltip = "Connect a saved profile",
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = close,
            modifier =
                Modifier
                    .background(AppTheme.Colors.surface)
                    .widthIn(min = 200.dp),
        ) {
            val choosingEnvironment = pickingEnvironmentFor
            if (choosingEnvironment != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${choosingEnvironment.name} in…",
                            color = AppTheme.Colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Back",
                            tint = AppTheme.Colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = { pickingEnvironmentFor = null },
                    modifier = Modifier.testTag("environment-back"),
                )
                environments.forEach { environment ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(text = environment.name, color = AppTheme.Colors.text, fontSize = 11.sp)
                                Text(
                                    text = environment.host.ifBlank { "the profile's own host" },
                                    color = AppTheme.Colors.textDisabled,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                )
                            }
                        },
                        onClick = {
                            close()
                            onConnectProfileIn?.invoke(choosingEnvironment, environment)
                        },
                        modifier = Modifier.testTag("environment-${environment.name}"),
                    )
                }
                // The endpoint the profile already names is an environment nobody extracted, and refusing
                // to offer it would make the feature a downgrade for anything it did not classify.
                HorizontalDivider(
                    color = AppTheme.Separators.color,
                    thickness = AppTheme.Separators.dividerThickness,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "As saved (${choosingEnvironment.config.host})",
                            color = AppTheme.Colors.text,
                            fontSize = 11.sp,
                        )
                    },
                    onClick = {
                        close()
                        onQuickConnect(choosingEnvironment.id, choosingEnvironment)
                    },
                    modifier = Modifier.testTag("environment-as-saved"),
                )
            } else {
                connectionProfiles.forEach { profile ->
                    val connectionState =
                        onGetProfileConnectionState?.invoke(profile.id) ?: FixConnectionState.DISCONNECTED
                    val stateColor = connectionState.getColor()

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Status indicator dot
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .background(stateColor, CircleShape),
                                )
                                Text(
                                    text = profile.name,
                                    color = AppTheme.Colors.text,
                                    fontSize = 11.sp,
                                )
                            }
                        },
                        trailingIcon = {
                            if (environments.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Choose an environment",
                                    tint = AppTheme.Colors.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            if (environments.isEmpty()) {
                                close()
                                onQuickConnect(profile.id, profile)
                            } else {
                                pickingEnvironmentFor = profile
                            }
                        },
                        modifier = Modifier.testTag("connect-${profile.name}"),
                    )
                }
            }
        }
    }
}

/**
 * **Capture, Search and Blank line: the three that act on every session at once.**
 *
 * One-shots, not toggles, and none of them destroys anything, which is exactly why Clear all is not among
 * them but alone in the group after. Capture turns the whole flow across all sessions into an editable
 * scenario and opens the editor on it directly, because curation is editing and there is no separate
 * read-only review screen.
 */
@Composable
private fun AllSessionsActions(
    onCaptureScenario: (() -> Unit)?,
    onSearchAllSessions: (() -> Unit)?,
    onAddSeparatorToAll: (() -> Unit)?,
    words: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        if (onCaptureScenario != null) {
            ToolbarChip(
                icon = Icons.Default.PlaylistAdd,
                label = "Capture",
                tint = AppTheme.Colors.text,
                onClick = onCaptureScenario,
                tag = "toolbar-capture",
                words = words,
                tooltip = "Capture scenario from all sessions",
            )
        }
        if (onSearchAllSessions != null) {
            ToolbarChip(
                icon = Icons.Default.Search,
                label = "Search",
                tint = AppTheme.Colors.text,
                onClick = onSearchAllSessions,
                tag = "toolbar-search",
                words = words,
                tooltip = "Search all sessions · $SEARCH_ALL_SHORTCUT",
            )
        }
        if (onAddSeparatorToAll != null) {
            ToolbarChip(
                icon = Icons.Default.Add,
                label = "Blank line",
                tint = AppTheme.Colors.text,
                onClick = onAddSeparatorToAll,
                tag = "toolbar-blank-line",
                words = words,
                tooltip = "Add blank line to all panes",
            )
        }
    }
}

/**
 * **What the run configuration widget has room to draw.** Computed by [Toolbar] from [ToolbarFold].
 *
 * Its own type rather than a second Boolean on the slot, because the widget folds in two steps of its own
 * and one of them is a width rather than a flag: the name keeps being worth printing at a narrower chip
 * long after the kind stopped being worth the room beside it.
 */
data class RunWidgetFold(
    /** Whether the chip prints `· load set` after the name. The first thing the widget gives up. */
    val kind: Boolean,
    /** How wide the chip may draw, or null once the widget has folded to the ▶ alone. */
    val chipMax: Dp?,
) {
    companion object {
        /** Everything drawn, which is what a widget outside the toolbar's own fold should assume. */
        val FULL = RunWidgetFold(kind = true, chipMax = RUN_CHIP_MAX)
    }
}

/**
 * One group boundary: 6dp, a 1dp rule the height of a chip's text, 6dp.
 *
 * Tagged by position so a test can ask that the row is still in its groups. [index] runs left to right
 * from the workspace, which is the order a reader meets them in.
 */
@Composable
private fun GroupDivider(index: Int) {
    VerticalDivider(
        color = AppTheme.Separators.color,
        thickness = AppTheme.Separators.dividerThickness,
        modifier =
            Modifier
                .padding(horizontal = 6.dp)
                .height(18.dp)
                .testTag("toolbar-divider-$index"),
    )
}

/**
 * **What the toolbar gives up, in order, as the window narrows.**
 *
 * Each constant names what has gone by the time it is reached. The words go before anything else because
 * every chip keeps its glyph and gains its label back on hover, so nothing is lost but reading speed. The
 * run widget's kind goes among them, early, because "load set" is the half of that chip a reader can work
 * out and the name is the half they recognise. The name itself goes much later, and only into a tooltip.
 * The segments go next, into `View ▾` as `Layout ▸` rows, because a menu can carry them and they cannot be
 * reached at all once they are clipped away. The filter goes last, and only by shrinking: it is the one
 * control here that says what the panes are showing, so it is the one that must stay legible longest.
 * The workspace, Settings and Help never fold.
 *
 * The thresholds are added up from the sizes the row actually draws rather than measured, in the spirit of
 * [SessionPanelHeader]'s own fold rule, and each one counts the filter at its [FILTER_MIN_WIDTH], the
 * width below which the next thing has to give way.
 */
internal enum class ToolbarFold {
    /** Every chip has its word, the run chip has its kind, the layout has its segments. */
    NONE,

    /** Capture, Search and Blank line are their glyphs. */
    ACTION_WORDS,

    /** And the run chip has dropped `· load set`. */
    RUN_KIND,

    /** And so are Connect, Disconnect all, Close all and Clear all. */
    ALL_WORDS,

    /** And the run chip is gone: the widget is ▶ with the name in its tooltip. */
    RUN_NAME,

    /** And the three layout segments have folded into `View ▾`. */
    SEGMENTS,
    ;

    /** Whether Capture, Search and Blank line print their words. */
    val actionWords: Boolean get() = this == NONE

    /** Whether the run chip prints the kind after the name. */
    val runKind: Boolean get() = ordinal <= ACTION_WORDS.ordinal

    /** Whether Connect, Disconnect all, Close all and Clear all print their words. */
    val commandWords: Boolean get() = ordinal <= RUN_KIND.ordinal

    /** Whether the run chip is drawn at all, rather than folded into the ▶'s own tooltip. */
    val runName: Boolean get() = ordinal < RUN_NAME.ordinal

    /** Whether the three layout segments are drawn, rather than folded into `View ▾`. */
    val layoutSegments: Boolean get() = this != SEGMENTS

    /** The two facts above as the run widget wants them, with the chip's width rather than a flag. */
    val runWidget: RunWidgetFold
        get() =
            RunWidgetFold(
                kind = runKind,
                chipMax =
                    when {
                        !runName -> null
                        runKind -> RUN_CHIP_MAX
                        commandWords -> RUN_CHIP_MID
                        else -> RUN_CHIP_MIN
                    },
            )

    companion object {
        fun forWidth(width: Dp): ToolbarFold =
            when {
                width >= toolbarFullWidth(NONE) -> NONE
                width >= toolbarFullWidth(ACTION_WORDS) -> ACTION_WORDS
                width >= toolbarFullWidth(RUN_KIND) -> RUN_KIND
                width >= toolbarFullWidth(ALL_WORDS) -> ALL_WORDS
                width >= toolbarFullWidth(RUN_NAME) -> RUN_NAME
                else -> SEGMENTS
            }
    }
}

/**
 * The filter's share of the room the groups do not want, against one part for each spacer beside it.
 *
 * The spacers exist to centre it, not to hold space back: a toolbar with room to spare should spend it on
 * the one control here that says what the panes are showing, and at six to one it does.
 */
private const val FILTER_WEIGHT = 6f

/** Narrow enough to be worth keeping at all: a short pattern, the two ticks, and the chip when it is on. */
internal val FILTER_MIN_WIDTH = 200.dp

/** Wide enough for a regex nobody has to scroll, past which the row would just be a long empty box. */
private val FILTER_MAX_WIDTH = 420.dp

/** 11sp mixed-case sans runs to about a 0.6em advance, the figure the layout segments are sized from. */
private val LABEL_ADVANCE = 6.5.dp

/** A chip: 10dp of padding each side, a 16dp glyph, a 4dp gap, its word, and a chevron where it opens a menu. */
private fun chipWidth(
    label: String,
    chevron: Boolean = false,
    words: Boolean = true,
): Dp =
    20.dp + 16.dp +
        (if (words) 4.dp + LABEL_ADVANCE * label.length else 0.dp) +
        (if (chevron) 4.dp + 16.dp else 0.dp)

/**
 * 8dp each side of the row, the workspace switcher, and the two 32dp icons at the far end.
 *
 * The switcher is "FixTool" at 14sp, a short workspace name after it and a chevron. A longer name takes
 * its room from the filter, which is the right thing for it to take: the filter is elastic and the name
 * is not.
 */
private val TOOLBAR_PADDING = 16.dp
private val WORKSPACE_WIDTH = 180.dp
private val SYSTEM_GROUP_WIDTH = 64.dp

/** 6dp, a 1dp rule and 6dp, seven times over: one boundary after each of the row's first seven groups. */
private val GROUP_DIVIDER_WIDTH = 13.dp

/** Wide enough for a set name a reader does not have to hover to know. Past this the name middle-ellipsises. */
internal val RUN_CHIP_MAX = 180.dp

/** With the kind gone the name has the chip to itself, so it can give up a third of the room. */
private val RUN_CHIP_MID = 130.dp

/** The last width a name is still worth printing at: about nine characters and an ellipsis. */
private val RUN_CHIP_MIN = 90.dp

/** The ▶, which never folds: a 16dp glyph with 6dp each side. */
internal val RUN_BUTTON_WIDTH = 28.dp

/** The hairline of toolbar ground that joins the chip to the button, as the layout segments are joined. */
internal val RUN_WIDGET_GAP = 1.dp

/** A chip's height, which the widget's two halves share so the joined pair reads as one control. */
internal val RUN_WIDGET_HEIGHT = 28.dp

/** The chip and the ▶ with the hairline between them, or the ▶ alone once the name has folded away. */
private fun runWidgetWidth(fold: ToolbarFold): Dp =
    (fold.runWidget.chipMax?.let { it + RUN_WIDGET_GAP } ?: 0.dp) + RUN_BUTTON_WIDTH

/**
 * The width the row needs to draw itself at a given fold, with the filter at its minimum.
 *
 * At [ToolbarFold.NONE] that is **1669dp**: 16dp of padding, a 180dp workspace, seven 13dp dividers, a
 * 200dp filter, four gaps of 8dp, Connect with its chevron and Disconnect all, Close all and Clear all with
 * their words (433.5dp), the three action chips with theirs (269.5dp), the run widget at its widest
 * (209dp), the view controls (174dp) and the two system icons (64dp).
 *
 * What each level saves, in order: the action words 161.5dp, the run chip's kind 50dp, the command words
 * 309.5dp, the run chip itself 91dp, and the layout segments the last 116dp. So the thresholds are
 * **1669, 1507.5, 1457.5, 1148, 1057 and 941dp**.
 *
 * Every figure here is a floor, not a measurement: the chips are measured by what they draw, so a row
 * that folds a little early wastes a word and one that folds late would wrap, and only one of those is a
 * bug. What the estimate is short by goes to the filter, which is weighted and takes what is left.
 */
private fun toolbarFullWidth(fold: ToolbarFold): Dp =
    TOOLBAR_PADDING +
        WORKSPACE_WIDTH +
        GROUP_DIVIDER_WIDTH * 7 +
        FILTER_MIN_WIDTH +
        chipWidth("Connect", chevron = true, words = fold.commandWords) +
        chipWidth("Disconnect all", words = fold.commandWords) +
        chipWidth("Close all", words = fold.commandWords) +
        chipWidth("Clear all", words = fold.commandWords) +
        chipWidth("Capture", words = fold.actionWords) +
        chipWidth("Search", words = fold.actionWords) +
        chipWidth("Blank line", words = fold.actionWords) +
        CHIP_GAP * 4 +
        runWidgetWidth(fold) +
        (if (fold.layoutSegments) VIEW_CONTROLS_WIDTH else VIEW_MENU_WIDTH) +
        SYSTEM_GROUP_WIDTH

/**
 * **Disconnect all and Close all: the two whole-window session buttons, after Connect ▾.**
 *
 * `Run ▾` used to lead this group and has left it: it is a run configuration widget of its own now, in its
 * own group, because choosing what runs and connecting a profile are two different questions and a group
 * answers one. What is left here is only about sessions, which is why Connect ▾ leads it from [Toolbar]
 * itself and these two follow: pick a profile, take them all down, close them all.
 *
 * Both read live session state through the ViewModel's own offers, so the count in a tooltip and the reason
 * a button is refused are computed in one place and said the same way on the control surface.
 *
 * @param words false on a toolbar too narrow for the labels, where each chip is its glyph and says its
 *   name on hover. See [ToolbarFold].
 */
@Composable
fun ToolbarSessionControls(
    viewModel: FixMessageViewModel,
    words: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val activeLoad by viewModel.activeLoadRun.collectAsState()
    // Read so that a run starting or finishing recomposes what turns on it: Disconnect all's reason.
    val runningIds by viewModel.runningSetIds.collectAsState()
    // Which sessions are logged on, observed here so Disconnect all's count follows them. A count
    // remembered on anything coarser reads 0 until something unrelated happens to change.
    viewModel.sessions.forEach { it.connectionState.collectAsState().value }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        // No confirmation. Connect puts everything back in one click, and disconnecting clears no
        // book: the venue's orders and quotes stay where Clear order book can reach them. The one case
        // that would lose something is a live load run, and there the button is disabled and says why.
        val offer = viewModel.disconnectAllOffer(activeLoad, runningIds)
        ToolbarChip(
            icon = Icons.Default.PowerSettingsNew,
            label = "Disconnect all",
            tint = if (offer.enabled) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
            onClick = { viewModel.disconnectAllSessions() },
            tag = "toolbar-disconnect-all",
            words = words,
            enabled = offer.enabled,
            tooltip = offer.tooltip,
        )

        CloseAllChip(viewModel, activeLoad, runningIds, words)
    }
}

/**
 * **Close all — the half Disconnect all deliberately leaves undone.**
 *
 * A fifty-lane load set leaves fifty panes, and putting the box back to nothing meant closing them one at
 * a time. It counts panes rather than connections, because a pane left over from a run already
 * disconnected is exactly what it is for.
 *
 * This one **does** ask, and Disconnect all does not, because the difference between them is exactly what
 * cannot be put back: Connect returns the sessions, and nothing returns a pane's messages. It asks
 * in the button rather than in a dialog — the second click is the confirmation — and it gives up on its
 * own after a few seconds, so an armed button never sits waiting to be pressed by somebody who has
 * forgotten what it is armed for.
 */
@Composable
private fun CloseAllChip(
    viewModel: FixMessageViewModel,
    activeLoad: LoadRecord?,
    runningIds: Set<String>,
    words: Boolean,
) {
    val offer = viewModel.closeAllOffer(activeLoad, runningIds)
    val panes = viewModel.sessions.size
    // Keyed on the count, so a pane closing or opening under an armed button restarts the countdown
    // rather than leaving it armed over a number that has changed. One clock for every armed control in
    // the app — see [rememberArmed], which this pattern is where it came from.
    val arming = rememberArmed(panes)
    if (arming.value && !offer.enabled) arming.value = false
    val armed = arming.value
    val sentence = if (armed) "Close $panes pane${if (panes == 1) "" else "s"}? Click again." else offer.tooltip
    ToolbarChip(
        icon = Icons.Default.Close,
        label = if (armed) "Close $panes?" else "Close all",
        tint =
            when {
                !offer.enabled -> AppTheme.Colors.textDisabled
                armed -> AppTheme.Colors.warning
                else -> AppTheme.Colors.text
            },
        onClick = {
            if (armed) viewModel.closeAllSessions()
            arming.value = !armed
        },
        tag = "toolbar-close-all",
        words = words,
        enabled = offer.enabled,
        tooltip = sentence,
    )
}

/**
 * ⌘⇧F on macOS, Ctrl+Shift+F elsewhere.
 *
 * It was ⌘F, which is find-in-file everywhere else in the world; that shortcut belongs to the pane's own
 * search now, and this is find-in-path beside it.
 */
internal val SEARCH_ALL_SHORTCUT: String
    get() = if (System.getProperty("os.name").lowercase().contains("mac")) "⌘⇧F" else "Ctrl+Shift+F"

/**
 * A 28dp toolbar chip: an icon, a word, and a chevron when it opens a menu.
 *
 * **A chip that has lost its word says it on hover instead**, and one that still has its word and no
 * sentence of its own says nothing: a tooltip repeating a visible label is noise. [tooltip] is for the
 * chips that have something else to say (what a refusal is about, what a click costs) and it goes into
 * the semantics as well as the hover bubble, so a test can read the reason a control is refused. A Compose
 * tooltip exists only while the pointer is over it.
 */
@Composable
@Suppress("LongParameterList")
private fun ToolbarChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    tag: String,
    chevron: Boolean = false,
    words: Boolean = true,
    enabled: Boolean = true,
    tooltip: String? = null,
    /** The glyph's own colour where it says something the word does not, as Connect's green does. */
    iconTint: Color = tint,
) {
    val hover = tooltip ?: label.takeIf { !words }
    val chip: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .height(28.dp)
                    .background(AppTheme.Colors.border, RoundedCornerShape(4.dp))
                    .let { if (enabled) it.clickable(onClick = onClick) else it }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics {
                        if (!enabled) disabled()
                        hover?.let { contentDescription = it }
                    }.testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(16.dp))
            if (words) Text(text = label, color = tint, fontSize = 11.sp, maxLines = 1)
            if (chevron) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Dropdown",
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    if (hover != null) AppTooltip(hover) { chip() } else chip()
}
