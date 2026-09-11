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
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import kotlinx.coroutines.delay

/**
 * **The one row, in four kinds of thing, with the filter down the middle.**
 *
 * Left to right: where am I (the workspace), what am I looking at (the filter), what is connected (Quick
 * Connect, Run, Disconnect all, Close all), what do I do to every session at once (Capture, Search, Blank
 * line), what is dangerous (Clear all), how do the panes draw (the layout segments and View ▾), and the
 * two the operating system expects at the far right. A 1dp rule separates each group from the next, and
 * every group answers one question.
 *
 * Two things came back here after a build spent elsewhere. The filter had a row of its own under a pane
 * bar and the view controls had that bar, which is two lines of pane height for six controls, in a window
 * whose scarcest resource is pane height. The middle of this row was empty at the time. The rule that
 * row was built for, that a filter must never narrow a view without saying so, is kept by the controls
 * being here always, in the open, rather than by a row that refuses to close.
 *
 * **It never wraps.** When the window is too narrow the chips give up their words for their glyphs, then
 * the layout segments fold into `View ▾`, then the filter shrinks. The workspace, Settings and Help never
 * fold. See [ToolbarFold] for the order and the arithmetic behind it.
 *
 * @param runControls `Run ▾`, `Disconnect all` and `Close all`, which read live session state and own
 *   dialogs. A slot rather than a dozen parameters, because this file has no business knowing a ViewModel.
 *   [ToolbarRunControls] is what goes in here, and it is handed whether the chips have room for their
 *   words.
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
     * leaves Quick Connect exactly as it was: pick a profile and it connects.
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
    runControls: (@Composable (words: Boolean) -> Unit)? = null,
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

            // **What is connected.** Quick Connect ▾ picks a profile, Run ▾ runs a saved configuration,
            // and the other two put the box back to nothing. The three that follow the chip are passed as
            // one slot, because all three read live session state. See [ToolbarRunControls].
            if (onQuickConnect != null && connectionProfiles.isNotEmpty()) {
                QuickConnectChip(
                    connectionProfiles = connectionProfiles,
                    onQuickConnect = onQuickConnect,
                    onGetProfileConnectionState = onGetProfileConnectionState,
                    environments = environments,
                    onConnectProfileIn = onConnectProfileIn,
                    words = fold.commandWords,
                )
                Spacer(modifier = Modifier.width(CHIP_GAP))
            }
            runControls?.invoke(fold.commandWords)
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

            // **How the panes draw**: the three layout segments and the four named lines behind View ▾.
            viewControls?.invoke(!fold.layoutSegments)
            GroupDivider(6)

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
 * **Quick Connect ▾ — the profile selector, and the demo workspace's home.**
 *
 * Shown even with no saved profiles, because that is the one moment the trailing item matters: a fresh
 * install has nothing to connect to, and the demo is how it gets something. The item sits after the
 * profiles the way "Edit Configurations…" trails a run-configuration list — the things you can pick, then
 * the thing that makes more of them. Once the workspace is installed its three profiles are ordinary rows
 * above with their own state dots, so there is no second status light here to disagree with them.
 */
@Composable
@Suppress("LongParameterList")
private fun QuickConnectChip(
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
            label = "Quick Connect",
            tint = AppTheme.Colors.text,
            iconTint = AppTheme.Colors.primary,
            onClick = { expanded = true },
            tag = "quick-connect",
            chevron = true,
            words = words,
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
                        modifier = Modifier.testTag("quick-connect-${profile.name}"),
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
                tooltip = "Search all sessions · ⌘F",
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
 * segments go next, into `View ▾` as `Layout ▸` rows, because a menu can carry them and they cannot be
 * reached at all once they are clipped away. The filter goes last, and only by shrinking: it is the one
 * control here that says what the panes are showing, so it is the one that must stay legible longest.
 * The workspace, Settings and Help never fold.
 *
 * The thresholds are added up from the sizes the row actually draws rather than measured, in the spirit of
 * [SessionPanelHeader]'s own fold rule, and each one counts the filter at its [FILTER_MIN_WIDTH], the
 * width below which the next thing has to give way.
 */
internal enum class ToolbarFold {
    /** Every chip has its word, the layout has its segments, and the filter has room to grow. */
    NONE,

    /** Capture, Search and Blank line are their glyphs. */
    ACTION_WORDS,

    /** And so are Quick Connect, Run, Disconnect all, Close all and Clear all. */
    ALL_WORDS,

    /** And the three layout segments have folded into `View ▾`. */
    SEGMENTS,
    ;

    /** Whether Capture, Search and Blank line print their words. */
    val actionWords: Boolean get() = this == NONE

    /** Whether Quick Connect, Run, Disconnect all, Close all and Clear all print theirs. */
    val commandWords: Boolean get() = this == NONE || this == ACTION_WORDS

    /** Whether the three layout segments are drawn, rather than folded into `View ▾`. */
    val layoutSegments: Boolean get() = this != SEGMENTS

    companion object {
        fun forWidth(width: Dp): ToolbarFold =
            when {
                width >= toolbarFullWidth(NONE) -> NONE
                width >= toolbarFullWidth(ACTION_WORDS) -> ACTION_WORDS
                width >= toolbarFullWidth(ALL_WORDS) -> ALL_WORDS
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

/** 6dp, a 1dp rule and 6dp, six times over: one boundary after each of the row's first six groups. */
private val GROUP_DIVIDER_WIDTH = 13.dp

/**
 * The width the row needs to draw itself at a given fold, with the filter at its minimum.
 *
 * At [ToolbarFold.NONE] that is **1573dp**: the padding, the workspace, six dividers, a 200dp filter, the
 * four connect chips with their words, the three action chips with theirs, Clear all, the view controls
 * and the two system icons. Dropping the action words saves 162dp, dropping the rest saves another 332dp,
 * and folding the segments saves the last 116dp.
 *
 * Every figure here is a floor, not a measurement: the chips are measured by what they draw, so a row
 * that folds a little early wastes a word and one that folds late would wrap, and only one of those is a
 * bug. What the estimate is short by goes to the filter, which is weighted and takes what is left.
 */
private fun toolbarFullWidth(fold: ToolbarFold): Dp =
    TOOLBAR_PADDING +
        WORKSPACE_WIDTH +
        GROUP_DIVIDER_WIDTH * 6 +
        FILTER_MIN_WIDTH +
        chipWidth("Quick Connect", chevron = true, words = fold.commandWords) +
        chipWidth("Run", chevron = true, words = fold.commandWords) +
        chipWidth("Disconnect all", words = fold.commandWords) +
        chipWidth("Close all", words = fold.commandWords) +
        chipWidth("Clear all", words = fold.commandWords) +
        chipWidth("Capture", words = fold.actionWords) +
        chipWidth("Search", words = fold.actionWords) +
        chipWidth("Blank line", words = fold.actionWords) +
        CHIP_GAP * 5 +
        (if (fold.layoutSegments) VIEW_CONTROLS_WIDTH else VIEW_MENU_WIDTH) +
        SYSTEM_GROUP_WIDTH

/**
 * **Run ▾ and Disconnect all — the app-level run controls, beside Quick Connect.**
 *
 * Run ▾ is the run-configurations chooser. Every item in it is a *named* door: it runs a saved
 * configuration by name, or opens the chooser that edits them. That is why it is here and not in the
 * Scenarios rail, where it grew: a load set has no steps, no expectations and no bindings, and its only
 * connection to the scenario list was that the menu was already there. The rail keeps the *contextual*
 * doors, the ones that read the list beside them.
 *
 * The state is the state the rail computed, through the same stores, the same [Lanes] count and the same
 * ViewModel handlers, so there is one answer to "which sets are saved" and one to "how many lanes are
 * there". Every item keeps the test tag it had in the rail, so the tests moved rather than being rewritten.
 *
 * Both dialogs are hosted here because both are opened from this menu. A `Dialog` is its own window
 * composition and adds nothing to the toolbar's own layout.
 *
 * @param words false on a toolbar too narrow for the labels, where each chip is its glyph and says its
 *   name on hover. See [ToolbarFold].
 */
@Composable
@Suppress("LongMethod")
fun ToolbarRunControls(
    viewModel: FixMessageViewModel,
    words: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val running by viewModel.scenarioRunning.collectAsState()
    val activeSet by viewModel.activeRunSet.collectAsState()
    val activeLoad by viewModel.activeLoadRun.collectAsState()
    // Read so that a run starting or finishing recomposes what turns on it: Disconnect all's reason.
    val runningIds by viewModel.runningSetIds.collectAsState()
    // Which sessions are logged on, observed here so the lane count and Disconnect all's count follow
    // them. A count remembered on anything coarser reads 0 until something unrelated happens to change.
    val sessionStates = viewModel.sessions.map { it.connectionState.collectAsState().value }

    var menuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var editingLoadSets by remember { mutableStateOf(false) }
    var pendingLoadSet by remember { mutableStateOf<LoadSet?>(null) }
    // The saved set a refused `Load set ▸` wants fixed. By name, because it is already on disk: opening it
    // as an unsaved draft would put "unsaved" in the footer of a set nobody has touched.
    var loadSetToFix by remember { mutableStateOf<String?>(null) }

    // Re-read on every open, because a set saved from the rail or written by the control surface is on
    // disk before anything in this composition has changed.
    val savedSets = remember(menuOpen, activeSet) { viewModel.runSetStore.list() }
    val loadSets = remember(menuOpen, activeLoad, editingLoadSets) { viewModel.loadSets() }
    val recent =
        remember(menuOpen, activeSet, activeLoad) {
            RecentRun
                .merge(viewModel.runRecordStore.listSets(), viewModel.loadRecordStore.listRecords())
                .take(RECENT_RUNS)
        }
    // Counted for a load run, not for a fan-out: every row this menu gates on it is a load, and a load
    // issues from one lane as happily as from fifty.
    val lanes = remember(menuOpen, sessionStates, viewModel.connectionProfiles.size) { Lanes.forLoad(viewModel) }
    // What "Load run…" needs to be worth opening: a profile that could issue, whether or not it is up.
    val issuers =
        remember(menuOpen, viewModel.connectionProfiles.size) {
            val initiator = FixConnectionConfig.ConnectionType.INITIATOR
            viewModel.connectionProfiles.count { it.config.connectionType == initiator }
        }

    if (loading) {
        LoadRunDialog(
            viewModel = viewModel,
            fixedTemplate = null,
            onDismiss = { loading = false },
            onRun = { plan ->
                loading = false
                viewModel.startLoadRun(plan)
            },
            // The path from one burst to a set: tune the burst here, then want the cancel storm after it.
            onMakeSet = { set ->
                loading = false
                pendingLoadSet = set
                editingLoadSets = true
            },
        )
    }
    if (editingLoadSets) {
        LoadSetsDialog(
            viewModel = viewModel,
            onDismiss = {
                editingLoadSets = false
                pendingLoadSet = null
                loadSetToFix = null
            },
            onRun = { planned ->
                editingLoadSets = false
                pendingLoadSet = null
                loadSetToFix = null
                viewModel.startLoadSet(planned)
            },
            initial = pendingLoadSet,
            initialName = loadSetToFix,
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        Box {
            ToolbarChip(
                icon = Icons.Default.PlayCircleOutline,
                label = "Run",
                tint = if (running) AppTheme.Colors.textDisabled else AppTheme.Colors.success,
                onClick = { menuOpen = true },
                tag = "toolbar-run-menu",
                chevron = true,
                words = words,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // Wide enough for the widest thing under a row: "on A · Run connects A, B" is a sentence,
                // and a menu that has to ellipsise it on an ordinary two-profile set says nothing useful.
                modifier = Modifier.background(AppTheme.Colors.surface).widthIn(min = 320.dp),
            ) {
                RunConfigurationsMenu(
                    savedSets = savedSets,
                    loadSets = loadSets,
                    recent = recent,
                    lanes = lanes,
                    profiles = issuers,
                    sessionsOf = { viewModel.loadSetSessions(it) },
                    running = running,
                    onChose = { menuOpen = false },
                    onLoadRun = { loading = true },
                    // A set that would be refused opens the editor **on that set**, rather than
                    // half-running. "Cannot run now" is a different answer: no lane, or a run already
                    // holding the sessions, is nothing the file can fix, so it stays a notification.
                    onRunLoadSet = { name ->
                        (viewModel.startSavedLoadSet(name) as? FixMessageViewModel.SavedLoadSetRun.Refused)?.let {
                            loadSetToFix = it.set.name
                            editingLoadSets = true
                        }
                    },
                    onRunSaved = { name -> viewModel.startSavedRunSet(name) },
                    onLoadSets = {
                        loadSetToFix = null
                        editingLoadSets = true
                    },
                    onOpenRecent = { run ->
                        when (run) {
                            is RecentRun.Set -> viewModel.focusRunSet(run.id)
                            is RecentRun.Load -> viewModel.openLoadRun(run.id)
                        }
                    },
                )
            }
        }

        // No confirmation. Quick Connect puts everything back in one click, and disconnecting clears no
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
 * cannot be put back: Quick Connect returns the sessions, and nothing returns a pane's messages. It asks
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
    var armed by remember { mutableStateOf(false) }
    if (armed && !offer.enabled) armed = false
    // Keyed on the count as well as the arming, so a pane closing or opening under an armed button
    // restarts the countdown rather than leaving it armed over a number that has changed.
    if (armed) {
        LaunchedEffect(panes) {
            delay(CLOSE_ALL_ARMED_MS)
            armed = false
        }
    }
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
            armed = !armed
        },
        tag = "toolbar-close-all",
        words = words,
        enabled = offer.enabled,
        tooltip = sentence,
    )
}

/** How long Close all stays armed. Long enough to mean the second click, short enough not to lie in wait. */
private const val CLOSE_ALL_ARMED_MS = 5_000L

/**
 * **The run-configurations chooser's rows.**
 *
 * One row per saved load set and per saved scenario set, each running it at once, the chooser-editor under
 * them, and Recent as its own titled group with the verdict first. Every item that cannot be used stays
 * **visible and disabled with its count showing**, because an author cannot tell "no set is saved" from
 * "this feature does not exist" if the item is withheld.
 */
@Composable
@Suppress("LongParameterList")
private fun RunConfigurationsMenu(
    savedSets: List<SavedRunSet>,
    loadSets: List<LoadSet>,
    recent: List<RecentRun>,
    lanes: Lanes,
    /** Saved profiles a load run could issue from at all — connected or not, since Run connects them. */
    profiles: Int,
    /** What each saved set runs on, and what Run would bring up for it. */
    sessionsOf: (LoadSet) -> FixMessageViewModel.LoadSetSessions,
    running: Boolean,
    onChose: () -> Unit,
    onLoadRun: () -> Unit,
    onRunLoadSet: (String) -> Unit,
    onRunSaved: (String) -> Unit,
    onLoadSets: () -> Unit,
    onOpenRecent: (RecentRun) -> Unit,
) {
    // The lane sentence fan-out uses: "2" on its own is a count of *profiles* and reads as two lanes, and
    // a lane is sequential, so fifty sessions give fifty outstanding rather than four thousand.
    //
    // **Neither row waits for a lane to be up any more.** A load run dials the profile it is pointed at
    // and a set dials every profile it names, so a greyed row here would be hiding the door that connects
    // them. What holds them now is a run already in flight, and — for a load run — having nowhere to issue
    // from at all.
    RailMenuItem(
        "Load run…  ${if (lanes.profiles > 0) lanes.sentence else "nothing up yet"}",
        enabled = !running && profiles > 0,
        tag = "rail-run-load",
    ) {
        onChose()
        onLoadRun()
    }
    loadSets.forEach { set ->
        val phases = "${set.phases.size} phase${if (set.phases.size == 1) "" else "s"}"
        RailMenuItem(
            "Load set ▸  ${set.label.ifBlank { set.name }}  $phases",
            enabled = !running,
            tag = "rail-run-load-set-${set.name}",
            // Which sessions this one runs on, and which of them the click will connect — the one thing a
            // set's name has never said, and the reason running a saved set meant opening its file first.
            sub = sessionsOf(set).sentence,
        ) {
            onChose()
            onRunLoadSet(set.name)
        }
    }
    if (savedSets.isEmpty()) {
        RailMenuItem("Run set ▸  none saved", enabled = false, tag = "rail-run-set-none") {}
    } else {
        savedSets.forEach { set ->
            val runs = set.entries.sumOf { it.repeat.coerceAtLeast(1) }
            RailMenuItem(
                "Run set ▸  ${set.name}  $runs scenario${if (runs == 1) "" else "s"}",
                enabled = !running,
                tag = "rail-run-set-${set.name}",
            ) {
                onChose()
                onRunSaved(set.name)
            }
        }
    }
    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
    RailMenuItem("Load sets…  ${loadSets.size} saved", enabled = !running, tag = "rail-load-sets") {
        onChose()
        onLoadSets()
    }
    // A menu about saved things is also where somebody looks for what they produced, and Recent already
    // merges load runs with scenario sets. A titled group rather than "Recent ▸" on every row: the rows
    // are a list of records, and the prefix was saying the same word five times over.
    if (recent.isNotEmpty()) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Text(
            "recent",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp).testTag("toolbar-recent-group"),
        )
        recent.forEach { run ->
            RailMenuItem(run.line, tag = "rail-recent-${run.id}") {
                onChose()
                onOpenRecent(run)
            }
        }
    }
}

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
    /** The glyph's own colour where it says something the word does not, as Quick Connect's green does. */
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
