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
import androidx.compose.runtime.Stable
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
import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.scenario.RunSet
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

/** What the chip reads with nothing saved to name: the dialog configurations are made in. See [EmptyRunChip]. */
internal const val NOTHING_SAVED_CHIP = "Load sets…"

/** Why ▶ is dark while something else holds the sessions. The same refusal Disconnect all gives, in its own words. */
internal const val ANOTHER_RUN_IN_PROGRESS = "Another run is in progress. Wait for it, or stop it first."

/**
 * **What the widget's dialogs are doing, held apart from the widget** so the Run menu can open them too.
 *
 * The toolbar hosts the two dialogs because the menu that opens them is behind its chip. The menu bar's Run
 * menu opens the same two, and a second copy of either dialog would be two drafts of one load run. So the
 * flags live here, one set per window, and both doors write them.
 */
@Stable
internal class RunDoors {
    var loadRun by mutableStateOf(false)
    var loadSets by mutableStateOf(false)

    /** A set made from a burst, waiting for the load sets editor to open on it. */
    var pendingLoadSet by mutableStateOf<LoadSet?>(null)

    /**
     * The saved set a refused run wants fixed. By name, because it is already on disk: opening it as an
     * unsaved draft would put "unsaved" in the footer of a set nobody has touched.
     */
    var loadSetToFix by mutableStateOf<String?>(null)

    /** Whether the chip's dropdown is open, which is when its lane count is worth recounting. */
    var menuOpen by mutableStateOf(false)

    fun openLoadRun() {
        loadRun = true
    }

    fun openLoadSets() {
        loadSetToFix = null
        loadSets = true
    }

    fun closeLoadSets() {
        loadSets = false
        pendingLoadSet = null
        loadSetToFix = null
    }
}

/**
 * **What ▶ is pointed at and every way to change it, worked out by one function** for the two things that
 * draw it: the toolbar's widget and the menu bar's Run menu.
 *
 * The widget used to work all of this out inside itself, which was fine while it was the only door. A Run
 * menu that worked it out again would be a second answer to "what does ▶ run", and the first time the two
 * disagreed a reader would press ⌃R from the menu and run something the chip did not name. Each of them
 * calls [rememberRunChoice] over the window's one [RunDoors], so they read the same state through the same
 * rules, and each recomposes on its own rather than dragging the whole window with it.
 */
@Suppress("LongParameterList")
internal class RunChoice(
    val selected: RunConfiguration?,
    /** A load set's label falling back to its name, a run set's name, or [NOTHING_SAVED_CHIP] with nothing selected. */
    val displayName: String,
    /** The selected configuration itself is running, so ▶ is ■. */
    val running: Boolean,
    /** Non-null refuses ▶ and is its reason. Null while [running]. */
    val refusal: String?,
    /** Any run holds the sessions, which is what greys the rows that would start another. */
    val busy: Boolean,
    val loadSets: List<LoadSet>,
    val runSets: List<SavedRunSet>,
    val recent: List<RecentRun>,
    val lanes: Lanes,
    /** Saved profiles a load run could issue from at all, connected or not, since a run connects them. */
    val issuers: Int,
    val doors: RunDoors,
    val sessionsOf: (LoadSet) -> FixMessageViewModel.LoadSetSessions,
    val start: () -> Unit,
    val stop: () -> Unit,
    val select: (RunConfiguration) -> Unit,
    /** A Recent row: selects its configuration while the file is still there, and opens the record when not. */
    val openRecent: (RecentRun) -> Unit,
) {
    /** Whether ▶ does anything now: stop what runs, or run what is selected and not refused. */
    val canAct: Boolean get() = running || (refusal == null && selected != null)

    /** What ▶, ⌃R and the Run menu's first row do. */
    fun act() {
        when {
            running -> stop()
            refusal == null && selected != null -> start()
        }
    }

    /** "Run RFQ round trip", "Stop RFQ round trip", or "Run" with nothing to name. */
    val actLabel: String
        get() =
            when {
                running -> "Stop $displayName"
                selected == null -> "Run"
                else -> "Run $displayName"
            }
}

/**
 * **The live run's id when this configuration is the one running**, or null — which is what makes ▶ a ■.
 *
 * The live record has to be *this* configuration's, not merely running: ▶ becomes ■ only for what it is
 * pointed at, and stays a refused ▶ for any other run. One rule for the widget, the Run menu and `/run`.
 */
internal fun RunConfiguration.liveRunId(
    activeLoad: LoadRecord?,
    activeSet: RunSet?,
    runningIds: Set<String>,
): String? =
    when (kind) {
        RunConfiguration.Kind.LOAD_SET -> activeLoad?.takeIf { it.set?.name == name && it.id in runningIds }?.id
        RunConfiguration.Kind.RUN_SET ->
            activeSet?.takeIf { (it.source as? RunSource.Saved)?.setName == name && it.id in runningIds }?.id
    }

/** Why ▶ is refused, or null when it is not: nothing saved to point at, or another run holding the sessions. */
internal fun runRefusal(
    selected: RunConfiguration?,
    running: Boolean,
    anyRunning: Boolean,
): String? =
    when {
        selected == null -> NOTHING_SAVED_TO_RUN
        anyRunning && !running -> ANOTHER_RUN_IN_PROGRESS
        else -> null
    }

/**
 * Stop [configuration]'s live run, if it is the one running.
 *
 * By the live record's id rather than the selection's name: what stops a run is the run, and with a second run in
 * flight on other sessions the name alone would stop the wrong thing.
 */
internal fun stopRunConfiguration(
    viewModel: FixMessageViewModel,
    configuration: RunConfiguration,
): String? {
    val live =
        configuration.liveRunId(
            viewModel.activeLoadRun.value,
            viewModel.activeRunSet.value,
            viewModel.runningSetIds.value,
        ) ?: return null
    when (configuration.kind) {
        RunConfiguration.Kind.LOAD_SET -> viewModel.stopLoadRun(live)
        RunConfiguration.Kind.RUN_SET -> viewModel.requestScenarioStop(live)
    }
    return live
}

/** "Load run…  5 lanes on 2 profiles": the one-off run, and what it has to issue from. */
internal fun loadRunRow(lanes: Lanes): String =
    "Load run…  ${if (lanes.profiles > 0) lanes.sentence else "nothing up yet"}"

/** "3 phases". */
internal fun phaseCount(set: LoadSet): String = "${set.phases.size} phase${if (set.phases.size == 1) "" else "s"}"

/** "12 scenarios", counting repeats, because a set that runs one scenario twenty times is twenty runs. */
internal fun scenarioCount(set: SavedRunSet): String {
    val runs = set.entries.sumOf { it.repeat.coerceAtLeast(1) }
    return "$runs scenario${if (runs == 1) "" else "s"}"
}

/** "Load sets…  2 saved". */
internal fun loadSetsRow(saved: Int): String = "Load sets…  $saved saved"

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
                else -> "Run $displayName · ${Shortcuts.RUN.label}"
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
 * The chip on a workspace with nothing saved: it names where a configuration comes from, because there is none
 * to name yet.
 *
 * **`Load sets…`, not `Load run…`.** It read `Load run…` beside a refused ▶, which is a name and a button, so
 * it said the ▶ would start a load run — and a one-off load run is exactly what the ▶ never runs, because it
 * is not a saved configuration. IntelliJ's empty run widget reads "Add Configuration…", the door to where
 * configurations are made; `Load sets…` is that door here. A desk that only runs scenario sets saves them from
 * the Scenarios rail, and they are listed in the same menu the moment one exists.
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
            text = NOTHING_SAVED_CHIP,
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
 * **The choice ▶ makes, read off the stores the old `Run ▾` read.**
 *
 * Everything the menu ever owned is still decided here: the same counts, the same lane sentence, the same
 * handlers. What changed is what a row *means*. A row used to run its set, so the menu was a list of five
 * ways to start something and the window said nothing about which one you would start next. A row now
 * selects, the selection is remembered in `layout.json`, and ▶ beside the name is the only thing that starts
 * anything, which is also what makes ⌃R possible, because it now has one unambiguous answer to "run what?".
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun rememberRunChoice(
    viewModel: FixMessageViewModel,
    doors: RunDoors = remember { RunDoors() },
): RunChoice {
    val scenarioRunning by viewModel.scenarioRunning.collectAsState()
    val activeSet by viewModel.activeRunSet.collectAsState()
    val activeLoad by viewModel.activeLoadRun.collectAsState()
    // Read so that a run starting or finishing recomposes what turns on it: the ▶ and its refusal.
    val runningIds by viewModel.runningSetIds.collectAsState()
    // Which sessions are logged on, observed here so the lane count follows them. A count remembered on
    // anything coarser reads 0 until something unrelated happens to change.
    val sessionStates = viewModel.sessions.map { it.connectionState.collectAsState().value }
    val layout by viewModel.layoutState.collectAsState()

    // **The saved sets and the records come from the ViewModel as state, not from a read remembered here.**
    // They used to be `remember(menuOpen, activeSet, activeLoad, editingLoadSets)` reads of the stores, and
    // none of those keys moves when a workspace is opened: a workspace with load sets in it came up with the
    // chip still reading its empty name from the empty one before it.
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
    // **Keyed on the profiles themselves, not on how many there are.** A workspace that swaps three profiles
    // for three others moves no count, so the lane sentence and the issuer count went on describing the box
    // before it — the same stale-key bug as the lists above, one step smaller. A snapshot copy of the state
    // list keys on its contents, so any edit to a profile moves it too.
    val profiles = viewModel.connectionProfiles.toList()
    // Counted for a load run, not for a fan-out: every row gated on it is a load, and a load issues from one
    // lane as happily as from fifty.
    val lanes = remember(doors.menuOpen, sessionStates, profiles) { Lanes.forLoad(viewModel) }
    // What "Load run…" needs to be worth opening: a profile that could issue, whether or not it is up.
    val issuers =
        remember(doors.menuOpen, profiles) {
            val initiator = FixConnectionConfig.ConnectionType.INITIATOR
            profiles.count { it.config.connectionType == initiator }
        }

    val selectedKey = layout.selectedRunConfiguration
    // Resolved from the very lists the rows are drawn from, handed in rather than read again: a resolver that
    // went back to disk on its own could name a set the menu does not list.
    val selected =
        remember(selectedKey, configurations) {
            RunConfiguration.parse(viewModel.resolvedRunConfiguration(configurations))
        }

    val displayName =
        when {
            selected == null -> NOTHING_SAVED_CHIP
            selected.kind == RunConfiguration.Kind.LOAD_SET ->
                loadSets.firstOrNull { it.name == selected.name }?.let { it.label.ifBlank { it.name } } ?: selected.name
            else -> selected.name
        }

    val running = selected?.liveRunId(activeLoad, activeSet, runningIds) != null
    val refusal = runRefusal(selected, running, scenarioRunning)

    return RunChoice(
        selected = selected,
        displayName = displayName,
        running = running,
        refusal = refusal,
        busy = scenarioRunning,
        loadSets = loadSets,
        runSets = savedSets,
        recent = recent,
        lanes = lanes,
        issuers = issuers,
        doors = doors,
        sessionsOf = { viewModel.loadSetSessions(it) },
        start = {
            when (selected?.kind) {
                null -> Unit
                // A set that would be refused opens the editor **on that set**, rather than half-running.
                // "Cannot run now" is a different answer: no lane, or a run already holding the sessions, is
                // nothing the file can fix, so it stays a notification.
                RunConfiguration.Kind.LOAD_SET ->
                    (viewModel.startSavedLoadSet(selected.name) as? FixMessageViewModel.SavedLoadSetRun.Refused)?.let {
                        doors.loadSetToFix = it.set.name
                        doors.loadSets = true
                    }
                RunConfiguration.Kind.RUN_SET -> viewModel.startSavedRunSet(selected.name)
            }
        },
        stop = { selected?.let { stopRunConfiguration(viewModel, it) } },
        select = { viewModel.selectRunConfiguration(it.key) },
        // **Whether a record still names something is decided here, not in the row.** A Recent row is a
        // record, and a record remembers a name whether or not the file behind it survived the branch it was
        // written on. The saved lists are in scope here and nowhere else.
        openRecent = { run ->
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
}

/**
 * **The run widget as the toolbar draws it**, and the host of the two dialogs its menu opens.
 *
 * Both dialogs are hosted here because both are opened from this menu, and the menu is behind the chip
 * whether or not anything is saved. A workspace with nothing selected still has runs on disk and a `Load
 * sets…` to write its first set with, so an empty chip that went straight to the load run dialog would be
 * shutting the door on both. A `Dialog` is its own window composition and adds nothing to the toolbar's own
 * layout. The Run menu opens the same two by writing the same [RunDoors].
 *
 * @param fold what the row has room for. See [ToolbarFold.runWidget].
 * @param run the window's one [RunChoice], shared with the Run menu. A widget drawn on its own works out its
 *   own.
 */
@Composable
fun ToolbarRunConfiguration(
    viewModel: FixMessageViewModel,
    fold: RunWidgetFold = RunWidgetFold.FULL,
    modifier: Modifier = Modifier,
) {
    ToolbarRunConfiguration(viewModel, rememberRunChoice(viewModel), fold, modifier)
}

@Composable
internal fun ToolbarRunConfiguration(
    viewModel: FixMessageViewModel,
    run: RunChoice,
    fold: RunWidgetFold = RunWidgetFold.FULL,
    modifier: Modifier = Modifier,
) {
    val doors = run.doors
    if (doors.loadRun) {
        LoadRunDialog(
            viewModel = viewModel,
            fixedTemplate = null,
            onDismiss = { doors.loadRun = false },
            onRun = { plan ->
                doors.loadRun = false
                viewModel.startLoadRun(plan)
            },
            // The path from one burst to a set: tune the burst here, then want the cancel storm after it.
            onMakeSet = { set ->
                doors.loadRun = false
                doors.pendingLoadSet = set
                doors.loadSets = true
            },
        )
    }
    if (doors.loadSets) {
        LoadSetsDialog(
            viewModel = viewModel,
            onDismiss = { doors.closeLoadSets() },
            onRun = { planned ->
                doors.closeLoadSets()
                viewModel.startLoadSet(planned)
            },
            initial = doors.pendingLoadSet,
            initialName = doors.loadSetToFix,
        )
    }

    RunConfigurationWidget(
        selected = run.selected,
        displayName = run.displayName,
        running = run.running,
        refusal = run.refusal,
        fold = fold,
        onRun = run.start,
        onStop = run.stop,
        menu = { close ->
            // The dropdown composes its content only while it is open, so this is the "on open" the lane
            // count is keyed on, in place of the flag the old menu owned. The re-read beside it is belt
            // and braces rather than the mechanism: everything that writes a set or a record through the
            // ViewModel has already said so, and a refresh that finds the same lists emits nothing.
            DisposableEffect(Unit) {
                doors.menuOpen = true
                viewModel.refreshRunConfigurations()
                onDispose { doors.menuOpen = false }
            }
            RunConfigurationsMenu(run, onChose = close)
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
private fun RunConfigurationsMenu(
    run: RunChoice,
    onChose: () -> Unit,
) {
    // The lane sentence fan-out uses: "2" on its own is a count of *profiles* and reads as two lanes, and
    // a lane is sequential, so fifty sessions give fifty outstanding rather than four thousand.
    //
    // **Neither row waits for a lane to be up.** A load run dials the profile it is pointed at and a set
    // dials every profile it names, so a greyed row here would be hiding the door that connects them. What
    // holds them is a run already in flight, and, for a load run, having nowhere to issue from at all.
    RailMenuItem(loadRunRow(run.lanes), enabled = !run.busy && run.issuers > 0, tag = "rail-run-load") {
        onChose()
        run.doors.openLoadRun()
    }
    run.loadSets.forEach { set ->
        RailMenuItem(
            "Load set ▸  ${set.label.ifBlank { set.name }}  ${phaseCount(set)}",
            enabled = !run.busy,
            tag = "run-config-${set.name}",
            // Which sessions this one runs on, and which of them ▶ will connect, the one thing a set's
            // name has never said, and the reason running a saved set meant opening its file first.
            sub = run.sessionsOf(set).sentence,
        ) {
            onChose()
            run.select(RunConfiguration(RunConfiguration.Kind.LOAD_SET, set.name))
        }
    }
    if (run.runSets.isEmpty()) {
        RailMenuItem("Run set ▸  none saved", enabled = false, tag = "rail-run-set-none") {}
    } else {
        run.runSets.forEach { set ->
            RailMenuItem(
                "Run set ▸  ${set.name}  ${scenarioCount(set)}",
                enabled = !run.busy,
                tag = "run-config-${set.name}",
            ) {
                onChose()
                run.select(RunConfiguration(RunConfiguration.Kind.RUN_SET, set.name))
            }
        }
    }
    HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
    RailMenuItem(loadSetsRow(run.loadSets.size), enabled = !run.busy, tag = "rail-load-sets") {
        onChose()
        run.doors.openLoadSets()
    }
    // A menu about saved things is also where somebody looks for what they produced, and Recent already
    // merges load runs with scenario sets. A titled group rather than "Recent ▸" on every row: the rows
    // are a list of records, and the prefix was saying the same word five times over.
    if (run.recent.isNotEmpty()) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Text(
            "recent",
            color = AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp).testTag("toolbar-recent-group"),
        )
        run.recent.forEach { record ->
            RailMenuItem(record.line, tag = "rail-recent-${record.id}") {
                onChose()
                run.openRecent(record)
            }
        }
    }
}
