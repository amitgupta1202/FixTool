package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.service.SavedRunSet
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **What ▶ is pointed at**, which is a saved load set or a saved scenario set, named by kind and by name.
 *
 * The toolbar used to hold a `Run ▾` whose every row ran something the moment it was clicked, so the one
 * thing a reader could never tell from the window was *what would run next*. An IDE answers that with a
 * run configuration: a name on the bar, a menu that changes the name, and a button that runs the name. The
 * two kinds are kept apart rather than merged into one list of "things that run", because a load set and a
 * scenario set are stopped by different calls and counted in different units, and a widget that forgot
 * which one it was holding would have to ask twice.
 */
internal data class RunConfiguration(
    val kind: Kind,
    val name: String,
) {
    /**
     * The two kinds, with the word each one is persisted under and the word it is printed under.
     *
     * [prefix] is written to `layout.json` and must outlive a rename of the enum constant, which is why it
     * is spelled rather than taken from [name]. [label] is what the chip prints after the name: "scenario
     * set" and not "run set", because "run set" beside a ▶ reads as an instruction.
     */
    internal enum class Kind(
        val prefix: String,
        val label: String,
    ) {
        LOAD_SET("LOADSET", "load set"),
        RUN_SET("RUNSET", "scenario set"),
    }

    /** The persisted form: the kind's prefix, a colon, and the name exactly as the file on disk carries it. */
    val key: String get() = "${kind.prefix}:$name"

    companion object {
        /**
         * The configuration [key] names, or null when nothing does.
         *
         * Split on the **first** colon only: a set's name is a file name and nothing stops one containing a
         * colon, so splitting on the last would quietly rename it. An unknown prefix is null rather than a
         * guess, because a key written by a later version naming a kind this one does not have should fall
         * back to the default rule rather than resolve to the wrong kind.
         */
        fun parse(key: String?): RunConfiguration? {
            val text = key ?: return null
            val colon = text.indexOf(':')
            if (colon <= 0 || colon == text.lastIndex) return null
            return Kind.entries
                .firstOrNull { it.prefix == text.substring(0, colon) }
                ?.let { RunConfiguration(it, text.substring(colon + 1)) }
        }
    }
}

/**
 * Why ▶ is dark on a workspace that has never saved anything, said once and shared.
 *
 * Four words, because the door out is a row in the chip's own menu right beside it: the sentence no longer
 * has to name `Load run…` to send anybody anywhere, and a tooltip that spells out a click the reader is one
 * chip away from making is longer than the thing it explains.
 */
internal const val NOTHING_SAVED_TO_RUN = "Nothing saved to run"

/** Why ▶ is dark while something else holds the sessions. The same refusal Disconnect all gives, in its own words. */
internal const val ANOTHER_RUN_IN_PROGRESS = "Another run is in progress. Wait for it, or stop it first."

/**
 * ⌃R as the button spells it, decided once at class-load rather than per recomposition.
 *
 * Mac users read `⌃R` and nobody else does, and the handler in `App` takes Meta or Ctrl on every platform,
 * so the difference is in the printing and not in the binding.
 */
private val RUN_SHORTCUT_WORD =
    if (System.getProperty("os.name").lowercase().contains("mac")) "⌃R" else "Ctrl+R"

/**
 * **⌃R's target, published by the run widget so the window's key handler can reach it.**
 *
 * A plain holder and not Compose state on purpose: the key handler reads it at the moment a key arrives, so
 * nothing here should take part in recomposition.
 */
class RunConfigurationShortcut {
    internal var action: (() -> Unit)? = null

    /** Runs or stops the selected configuration. False when nothing is composed to act on. */
    fun fire(): Boolean {
        val act = action ?: return false
        act()
        return true
    }
}

/**
 * **The name and the ▶, as one control with a hairline between them.**
 *
 * Stateless on purpose: everything it needs to decide is handed to it, so the refusals, the running look
 * and the fold can all be driven from a test without a ViewModel or a workspace on disk. [ToolbarRunConfiguration]
 * is the one that reads the stores.
 *
 * The two halves are joined the way `ViewControls` joins its layout segments (one rounded ground, a 1dp gap
 * of the toolbar's own colour showing through), because they are one thing with two verbs rather than two
 * buttons that happen to be adjacent. The chip changes what will run and the button runs it, and the gap is
 * what says a click lands on one of them and not the other.
 *
 * @param selected what is shown on the chip and named in the run button, or null when nothing is saved.
 * @param displayName a load set's label falling back to its name, or a run set's name. With nothing selected
 *   there is no configuration to name, so the chip names the dialog and this is read only by the run
 *   button, which in that state is refused and says so.
 * @param running true while the selected configuration itself is running: the button is the stop button.
 * @param refusal non-null disables the run button and is its tooltip. Ignored while [running].
 * @param menu the dropdown's rows, given the call that closes the menu. Drawn in **both** states: the rows
 *   that do not name a configuration are the ones a workspace with nothing saved most needs, since Recent
 *   and `Load sets…` are how anything gets saved in the first place.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun RunConfigurationWidget(
    selected: RunConfiguration?,
    displayName: String,
    running: Boolean,
    refusal: String?,
    fold: RunWidgetFold,
    onRun: () -> Unit,
    onStop: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chipMax = fold.chipMax

    Row(
        modifier =
            modifier
                .height(RUN_WIDGET_HEIGHT)
                .clip(RoundedCornerShape(4.dp))
                .background(AppTheme.Colors.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chipMax != null) {
            Box {
                if (selected == null) {
                    EmptyRunChip(onClick = { expanded = true })
                } else {
                    SelectedRunChip(
                        selected = selected,
                        displayName = displayName,
                        kind = fold.kind,
                        maxWidth = chipMax,
                        onClick = { expanded = true },
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    // Wide enough for the widest thing under a row: "on A · Run connects A, B" is a
                    // sentence, and a menu that ellipsises it on an ordinary two-profile set says nothing.
                    modifier =
                        Modifier
                            .background(AppTheme.Colors.surface)
                            .widthIn(min = 320.dp)
                            .testTag("run-config-menu"),
                ) {
                    menu { expanded = false }
                }
            }
            // The hairline of toolbar ground that says the two halves take separate clicks.
            Spacer(
                modifier =
                    Modifier
                        .width(RUN_WIDGET_GAP)
                        .height(RUN_WIDGET_HEIGHT)
                        .background(AppTheme.Colors.surface),
            )
        }

        val canAct = running || (refusal == null && selected != null)
        val hover =
            when {
                running -> "Stop $displayName"
                refusal != null -> refusal
                selected == null -> NOTHING_SAVED_TO_RUN
                else -> "Run $displayName · $RUN_SHORTCUT_WORD"
            }
        AppTooltip(hover) {
            Box(
                modifier =
                    Modifier
                        .size(RUN_BUTTON_WIDTH)
                        .let { if (canAct) it.clickable { if (running) onStop() else onRun() } else it }
                        .semantics(mergeDescendants = true) {
                            if (!canAct) disabled()
                            contentDescription = hover
                        }.testTag("run-button"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    // Said by the box around it, which is what carries the click and the refusal.
                    contentDescription = null,
                    tint =
                        when {
                            running -> AppTheme.Colors.warning
                            canAct -> AppTheme.Colors.success
                            else -> AppTheme.Colors.textDisabled
                        },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The chip on a workspace with nothing saved: it names the dialog, because there is no configuration to name.
 *
 * The chevron and the menu behind it are the same as the selected chip's, and for the same reason. Most of
 * what the menu holds does not name a saved configuration at all: `Load run…` is the door to the dialog,
 * `Load sets…` is where a set gets written, and Recent is every run this workspace has already finished. A
 * box that has run things and saved none of them had all three of those on disk and no way in from the
 * toolbar, so the empty state is the one that needs the menu most rather than the one that can do without it.
 *
 * No tooltip: the chip's own word is the answer a tooltip would repeat.
 */
@Composable
private fun EmptyRunChip(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .height(RUN_WIDGET_HEIGHT)
                .clickable(onClick = onClick)
                .padding(start = 8.dp, end = 2.dp)
                .testTag("run-config"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Load run…",
            color = AppTheme.Colors.text,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = AppTheme.Colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The chip with something selected: the name, the kind after it while there is room, and a chevron last.
 *
 * The chevron trails the words rather than leading them, which is where `Connect ▾` and `View ▾` keep
 * theirs and where every menu button in this row keeps its. Leading, it read as a bullet in front of a
 * label rather than as the thing that says a click opens a list.
 *
 * The kind is the first thing the widget gives up, because a name is what a reader recognises and "load
 * set" is what they can work out. The name itself goes to [MiddleEllipsisText] rather than a tail ellipsis:
 * saved sets on one desk share a prefix far more often than a suffix, so `rfq-round…` would name three of
 * them and `rfq-…-cancel` names one.
 */
@Composable
private fun SelectedRunChip(
    selected: RunConfiguration,
    displayName: String,
    kind: Boolean,
    maxWidth: Dp,
    onClick: () -> Unit,
) {
    val hover = "$displayName · ${selected.kind.label}"
    AppTooltip(hover) {
        Row(
            modifier =
                Modifier
                    .height(RUN_WIDGET_HEIGHT)
                    .widthIn(max = maxWidth)
                    .clickable(onClick = onClick)
                    .padding(start = 8.dp, end = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = hover }
                    .testTag("run-config"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The weight is on a box around the text rather than on the text: [MiddleEllipsisText] puts the
            // modifier it is handed on its *inner* Text, inside its own measuring box, where a Row's weight
            // would never be read. Weighted, the name is what gives way, so the kind keeps its room.
            Box(modifier = Modifier.weight(1f, fill = false)) {
                MiddleEllipsisText(text = displayName, color = AppTheme.Colors.text, fontSize = 11.sp)
            }
            if (kind) {
                Text(
                    text = " · ${selected.kind.label}",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = AppTheme.Colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * **The run widget as the toolbar draws it**, reading the stores the old `Run ▾` read.
 *
 * Everything the menu ever owned is still here: both dialogs, the same counts, the same lane sentence, the
 * same handlers. What changed is what a row *means*. A row used to run its set, so the menu was a list of
 * five ways to start something and the window said nothing about which one you would start next. A row now
 * selects, the selection is remembered in `layout.json`, and the ▶ beside the name is the only thing that
 * starts anything, which is also what makes a keyboard shortcut possible, because ⌃R now has one
 * unambiguous answer to "run what?".
 *
 * Both dialogs are hosted here because both are opened from this menu, and the menu is behind the chip
 * whether or not anything is saved. A workspace with nothing selected still has runs on disk and a `Load
 * sets…` to write its first set with, so an empty chip that went straight to the load run dialog would be
 * shutting the door on both. A `Dialog` is its own window composition and adds nothing to the toolbar's own
 * layout.
 *
 * @param fold what the row has room for. See [ToolbarFold.runWidget].
 * @param shortcut the window's ⌃R, which this fills in while it is composed and clears when it is not.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ToolbarRunConfiguration(
    viewModel: FixMessageViewModel,
    fold: RunWidgetFold = RunWidgetFold.FULL,
    shortcut: RunConfigurationShortcut? = null,
    modifier: Modifier = Modifier,
) {
    val scenarioRunning by viewModel.scenarioRunning.collectAsState()
    val activeSet by viewModel.activeRunSet.collectAsState()
    val activeLoad by viewModel.activeLoadRun.collectAsState()
    // Read so that a run starting or finishing recomposes what turns on it: the ▶ and its refusal.
    val runningIds by viewModel.runningSetIds.collectAsState()
    // Which sessions are logged on, observed here so the lane count follows them. A count remembered on
    // anything coarser reads 0 until something unrelated happens to change.
    val sessionStates = viewModel.sessions.map { it.connectionState.collectAsState().value }
    val layout by viewModel.layoutState.collectAsState()

    // Whether the dropdown is open, learned from the menu's own composition rather than owned here: the
    // widget holds that state, and the lane count below is worth recounting when the menu that prints it
    // opens.
    var menuOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var editingLoadSets by remember { mutableStateOf(false) }
    var pendingLoadSet by remember { mutableStateOf<LoadSet?>(null) }
    // The saved set a refused run wants fixed. By name, because it is already on disk: opening it as an
    // unsaved draft would put "unsaved" in the footer of a set nobody has touched.
    var loadSetToFix by remember { mutableStateOf<String?>(null) }

    // **The saved sets and the records come from the ViewModel as state, not from a read remembered here.**
    // They used to be `remember(menuOpen, activeSet, activeLoad, editingLoadSets)` reads of the stores, and
    // none of those keys moves when a workspace is opened: a workspace with load sets in it came up with the
    // chip still reading `Load run…` from the empty one before it.
    val configurations by viewModel.runConfigurations.collectAsState()
    val savedSets = configurations.runSets
    val loadSets = configurations.loadSets
    // Recent is still built here, because a row is a line of menu text and `RecentRun` is this file's
    // vocabulary rather than the ViewModel's. Keyed on the lists themselves, so it follows them exactly.
    val recent =
        remember(configurations) {
            RecentRun
                .merge(configurations.setRecords, configurations.loadRecords)
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

    val selectedKey = layout.selectedRunConfiguration
    // Resolved from the very lists the rows below are drawn from, handed in rather than read again: a
    // resolver that went back to disk on its own could name a set this menu does not list.
    val selected =
        remember(selectedKey, configurations) {
            RunConfiguration.parse(viewModel.resolvedRunConfiguration(configurations))
        }

    val displayName =
        when {
            selected == null -> "Load run…"
            selected.kind == RunConfiguration.Kind.LOAD_SET ->
                loadSets.firstOrNull { it.name == selected.name }?.let { it.label.ifBlank { it.name } } ?: selected.name
            else -> selected.name
        }

    // A set is running when the live record is *this* set's, not merely when something is running: the ▶
    // becomes a ■ only for the configuration it is pointed at, and stays a refused ▶ for any other run.
    val running =
        when (selected?.kind) {
            null -> false
            RunConfiguration.Kind.LOAD_SET ->
                activeLoad?.set?.name == selected.name && activeLoad?.id in runningIds
            RunConfiguration.Kind.RUN_SET ->
                (activeSet?.source as? RunSource.Saved)?.setName == selected.name && activeSet?.id in runningIds
        }

    val refusal =
        when {
            selected == null -> NOTHING_SAVED_TO_RUN
            scenarioRunning && !running -> ANOTHER_RUN_IN_PROGRESS
            else -> null
        }

    val start: () -> Unit = {
        when (selected?.kind) {
            null -> Unit
            // A set that would be refused opens the editor **on that set**, rather than half-running.
            // "Cannot run now" is a different answer: no lane, or a run already holding the sessions, is
            // nothing the file can fix, so it stays a notification.
            RunConfiguration.Kind.LOAD_SET ->
                (viewModel.startSavedLoadSet(selected.name) as? FixMessageViewModel.SavedLoadSetRun.Refused)?.let {
                    loadSetToFix = it.set.name
                    editingLoadSets = true
                }
            RunConfiguration.Kind.RUN_SET -> viewModel.startSavedRunSet(selected.name)
        }
        Unit
    }
    val stop: () -> Unit = {
        // The live record's id rather than the selection's name: what stops a run is the run, and the
        // record is where its id is.
        val liveLoad = viewModel.activeLoadRun.value?.id
        val liveSet = viewModel.activeRunSet.value?.id
        when (selected?.kind) {
            null -> Unit
            RunConfiguration.Kind.LOAD_SET -> liveLoad?.let(viewModel::stopLoadRun)
            RunConfiguration.Kind.RUN_SET -> liveSet?.let(viewModel::requestScenarioStop)
        }
        Unit
    }

    // Published after composition rather than during it, so the key handler always finds the action that
    // belongs to what is on screen now, and finds nothing once this widget has gone.
    SideEffect {
        shortcut?.action = {
            when {
                running -> stop()
                refusal == null -> start()
                else -> Unit
            }
        }
    }
    DisposableEffect(shortcut) { onDispose { shortcut?.action = null } }

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

    RunConfigurationWidget(
        selected = selected,
        displayName = displayName,
        running = running,
        refusal = refusal,
        fold = fold,
        onRun = start,
        onStop = stop,
        menu = { close ->
            // The dropdown composes its content only while it is open, so this is the "on open" the lane
            // count is keyed on, in place of the flag the old menu owned. The re-read beside it is belt
            // and braces rather than the mechanism: everything that writes a set or a record through the
            // ViewModel has already said so, and a refresh that finds the same lists emits nothing.
            DisposableEffect(Unit) {
                menuOpen = true
                viewModel.refreshRunConfigurations()
                onDispose { menuOpen = false }
            }
            RunConfigurationsMenu(
                savedSets = savedSets,
                loadSets = loadSets,
                recent = recent,
                lanes = lanes,
                profiles = issuers,
                sessionsOf = { viewModel.loadSetSessions(it) },
                running = scenarioRunning,
                onChose = close,
                onLoadRun = { loading = true },
                onSelect = { viewModel.selectRunConfiguration(it.key) },
                onLoadSets = {
                    loadSetToFix = null
                    editingLoadSets = true
                },
                // **Whether a record still names something is decided here, not in the row.** A Recent row
                // is a record, and a record remembers a name whether or not the file behind it survived the
                // branch it was written on. The saved lists are in scope here and nowhere else.
                onRecent = { run ->
                    val configuration = run.configuration?.takeIf { it.isSaved(loadSets, savedSets) }
                    if (configuration != null) {
                        viewModel.selectRunConfiguration(configuration.key)
                    } else {
                        when (run) {
                            is RecentRun.Set -> viewModel.focusRunSet(run.id)
                            is RecentRun.Load -> viewModel.openLoadRun(run.id)
                        }
                    }
                },
            )
        },
        modifier = modifier,
    )
}

/** Whether the file this names is still on disk, which is what separates selecting a record from opening one. */
private fun RunConfiguration.isSaved(
    loadSets: List<LoadSet>,
    runSets: List<SavedRunSet>,
): Boolean =
    when (kind) {
        RunConfiguration.Kind.LOAD_SET -> loadSets.any { it.name == name }
        RunConfiguration.Kind.RUN_SET -> runSets.any { it.name == name }
    }

/**
 * **The chooser's rows: one per saved configuration, the two dialogs under them, and Recent as its own group.**
 *
 * Every row that names a configuration now **selects** it and leaves the running to the ▶. That is the whole
 * point of the widget: a menu whose rows ran meant the window could never say what would run next, and a
 * mis-click on a list of five sets started one rather than aiming at it.
 *
 * `Load run…` and `Load sets…` are unchanged, because neither names a configuration: one opens a dialog for
 * a run that has not been saved, and the other opens the editor over all of them.
 *
 * Every item that cannot be used stays **visible and disabled with its count showing**, because an author
 * cannot tell "no set is saved" from "this feature does not exist" if the item is withheld.
 */
@Composable
@Suppress("LongParameterList")
private fun RunConfigurationsMenu(
    savedSets: List<SavedRunSet>,
    loadSets: List<LoadSet>,
    recent: List<RecentRun>,
    lanes: Lanes,
    /** Saved profiles a load run could issue from at all, connected or not, since a run connects them. */
    profiles: Int,
    /** What each saved set runs on, and what running it would bring up. */
    sessionsOf: (LoadSet) -> FixMessageViewModel.LoadSetSessions,
    running: Boolean,
    onChose: () -> Unit,
    onLoadRun: () -> Unit,
    onSelect: (RunConfiguration) -> Unit,
    onLoadSets: () -> Unit,
    /** A Recent row, which the caller either selects or opens depending on whether its file is still there. */
    onRecent: (RecentRun) -> Unit,
) {
    // The lane sentence fan-out uses: "2" on its own is a count of *profiles* and reads as two lanes, and
    // a lane is sequential, so fifty sessions give fifty outstanding rather than four thousand.
    //
    // **Neither row waits for a lane to be up.** A load run dials the profile it is pointed at and a set
    // dials every profile it names, so a greyed row here would be hiding the door that connects them. What
    // holds them is a run already in flight, and, for a load run, having nowhere to issue from at all.
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
            tag = "run-config-${set.name}",
            // Which sessions this one runs on, and which of them ▶ will connect, the one thing a set's
            // name has never said, and the reason running a saved set meant opening its file first.
            sub = sessionsOf(set).sentence,
        ) {
            onChose()
            onSelect(RunConfiguration(RunConfiguration.Kind.LOAD_SET, set.name))
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
                tag = "run-config-${set.name}",
            ) {
                onChose()
                onSelect(RunConfiguration(RunConfiguration.Kind.RUN_SET, set.name))
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
                onRecent(run)
            }
        }
    }
}
