package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.headless.HeadlessRun
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.LoadRunDefaults
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.load.humanDuration
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.load.CompiledTemplate
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.random.Random

/**
 * **The load run dialog**: three named groups, and a footer that says why Run is off.
 *
 * Two doors open it, the editor's Load button with the editor's fields as the template and the rail's
 * Run menu with a template picker, and both end in the same [LoadPlan]. Every refusal is a sentence on
 * screen and Run is what refuses, but a refusal now sits under the row that caused it rather than in a
 * heap above the buttons — which is why Advanced opens itself whenever one names something inside it. A
 * group that stayed shut while hiding the control a refusal points at would make the placement a lie.
 *
 * The sentences themselves are [LoadPlan.problems], shared with `fixtool load` and `POST /load` so the
 * three cannot drift, and printed whole: `storeProblem()` and `fanOutFarEndNotice()` each end with their
 * own remedy and are owned in one place on purpose.
 */
@Composable
fun LoadRunDialog(
    viewModel: FixMessageViewModel,
    fixedTemplate: LoadTemplate?,
    onDismiss: () -> Unit,
    onRun: (LoadPlan) -> Unit,
) {
    Dialog(onCloseRequest = onDismiss, title = "Load run", state = rememberDialogState(width = 640.dp, height = 580.dp)) {
        LoadRunDialogContent(viewModel, fixedTemplate, onDismiss, onRun)
    }
}

/** The dialog's body without its window, so a test can drive it in a plain composition. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun LoadRunDialogContent(
    viewModel: FixMessageViewModel,
    fixedTemplate: LoadTemplate?,
    onDismiss: () -> Unit,
    onRun: (LoadPlan) -> Unit,
) {
    val profiles = viewModel.connectionProfiles
    var profileId by remember {
        mutableStateOf(
            profiles.firstOrNull { viewModel.loadLanes(it.id) is FixMessageViewModel.FanOutLanes.Available }?.id ?: profiles.firstOrNull()?.id,
        )
    }
    val templates = remember(profileId) { if (fixedTemplate != null) listOf(fixedTemplate) else viewModel.loadTemplates(profileId) }
    var template by remember { mutableStateOf(fixedTemplate ?: templates.firstOrNull()) }
    var listen by remember { mutableStateOf(setOf<String>()) }

    // The shape, the settle window and the seed come back from the view-state store, per profile. They were
    // `remember` locals, so every open reset to 4000 / 500 / 60s however often a run had been tuned by hand.
    val saved = remember(profileId) { viewModel.loadRunDefaults(profileId) }
    var burst by remember(saved) { mutableStateOf(saved.burst) }
    var count by remember(saved) { mutableStateOf(saved.count) }
    var rate by remember(saved) { mutableStateOf(saved.rate) }
    var forText by remember(saved) { mutableStateOf(saved.forText) }
    var settle by remember(saved) { mutableStateOf(saved.settle) }
    var seedRows by remember(saved) { mutableStateOf(saved.seed.map { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }) }

    var requestTag by remember(template) { mutableStateOf(template?.inferMatch()?.requestTag?.toString() ?: "") }
    var replyTag by remember(template) { mutableStateOf(template?.inferMatch()?.replyTag?.toString() ?: "") }
    var replyType by remember { mutableStateOf("") }
    var forLoad by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }

    val dictionary = viewModel.dictionary
    val profile = profiles.firstOrNull { it.id == profileId }
    val lanes = profileId?.let { viewModel.loadLanes(it) }
    val compiled = remember(template) { template?.takeIf { it.msgType != null }?.let { runCatching { CompiledTemplate.compile(it) }.getOrNull() } }
    val seed = remember(seedRows) { seedMap(seedRows) }
    val shape: LoadShape? =
        if (burst) {
            count
                .trim()
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { LoadShape.Burst(it) }
        } else {
            val r =
                rate
                    .trim()
                    .removeSuffix("/s")
                    .toIntOrNull()
                    ?.takeIf { it > 0 }
            val f = HeadlessRun.parseDuration(forText)?.takeIf { it > 0 }
            if (r != null && f != null) LoadShape.Rate(r, f) else null
        }
    val match =
        requestTag.trim().toIntOrNull()?.let { req ->
            LoadMatch(req, replyTag.trim().toIntOrNull() ?: req, replyType.trim().ifBlank { null })
        }
    val override = if (forLoad) StoreAndLogOverride.FOR_LOAD else null

    // Two kinds of refusal, both placed under the row that caused them. The plan's own are LoadPlan.problems,
    // shared with the CLI and the API. The rest are about the *form* — a plan with no template and no shape
    // does not exist to be asked — and each surface phrases those for itself.
    val chosen = template
    val planProblems =
        if (chosen != null && profile != null) {
            LoadPlan.problems(chosen, seed, profile.name, profile.config, override, LoadPlan.Surface.DIALOG)
        } else {
            emptyList()
        }
    val refusals =
        buildList {
            if (template == null) add(Refusal(Where.TEMPLATE, "Pick a template, or save a message under this profile first."))
            planProblems.forEach { add(Refusal(placeOf(it), it)) }
            if (template != null && match == null) {
                add(Refusal(Where.MATCH, "The template carries no tag a reply can be matched on. Name the request and reply tags."))
            }
            if (shape == null) {
                add(
                    Refusal(
                        Where.SHAPE,
                        if (burst) "Count must be a whole number above zero." else "Rate needs a number per second and a duration such as 10m.",
                    ),
                )
            }
            (lanes as? FixMessageViewModel.FanOutLanes.Unavailable)?.let { add(Refusal(Where.PROFILE, it.why)) }
        }
    val hidden = refusals.firstOrNull { it.where in ADVANCED }

    // The rule, and the reason LoadRunDialogTest can still reach the store radios: a refusal that names
    // something inside Advanced opens Advanced. It stays open afterwards — a group that shut itself the
    // instant its refusal cleared would take the control away in the middle of correcting it.
    LaunchedEffect(hidden != null) { if (hidden != null) advancedOpen = true }

    val runnable = refusals.isEmpty() && lanes is FixMessageViewModel.FanOutLanes.Available

    fun plan(): LoadPlan? {
        val t = template ?: return null
        val p = profile ?: return null
        val s = shape ?: return null
        val m = match ?: return null
        val label = LoadPlan.label(t, s, p.name)
        return LoadPlan(
            id = RunSets.id(System.currentTimeMillis(), label),
            label = label,
            template = t,
            profileId = p.id,
            profileName = p.name,
            listenProfileIds = listen.toList(),
            shape = s,
            match = m,
            settleMs = HeadlessRun.parseDuration(settle) ?: LoadPlan.DEFAULT_SETTLE_MS,
            seed = seed,
            storeAndLog = override,
        )
    }

    fun start() {
        if (!runnable) return
        val ready = plan() ?: return
        profileId?.let {
            viewModel.rememberLoadRunDefaults(it, LoadRunDefaults(burst, count, rate, forText, settle, seedRows.map { (k, v) -> listOf(k, v) }))
        }
        onRun(ready)
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AppTheme.Colors.background)
                .testTag("load-dialog")
                // Esc cancels and Ctrl/Cmd+Enter runs, from anywhere in the dialog. Preview, so a field that
                // has focus does not swallow the Enter first.
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isMetaPressed || event.isCtrlPressed) -> {
                            start()
                            true
                        }
                        else -> false
                    }
                },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                "Issues this message across a profile's sessions without waiting for replies, then accounts for every " +
                    "reply that lands on any session that is logged on.",
                color = AppTheme.Colors.textSecondary,
                style = AppTheme.Type.body,
            )

            GroupHead("What to send")
            FormRow("Template") {
                if (fixedTemplate != null) {
                    // Opened from the editor, whose fields *are* the template: there is nothing to go and view.
                    Text(fixedTemplate.name, color = AppTheme.Colors.text, style = AppTheme.Type.body, modifier = Modifier.testTag("load-template"))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Picker(template?.name ?: "pick a template", templates.map { it.name to it }, "load-template") { template = it }
                        val saved = viewModel.savedMessages.firstOrNull { it.name == template?.name }
                        if (saved != null) {
                            Text(
                                "view message",
                                color = AppTheme.Colors.info,
                                style = AppTheme.Type.meta,
                                modifier = Modifier.clickable { viewModel.loadEditorMessage(saved) }.testTag("load-view-template"),
                            )
                        }
                    }
                }
                compiled?.let {
                    Sub(
                        "35=${it.msgType} ${msgTypeName(
                            dictionary,
                            it.msgType,
                        )}· per message ${it.perMessageTags.joinToString(", ").ifEmpty { "none" }} · " +
                            "fixed ${it.fixedTags.joinToString(", ")}",
                    )
                }
                Refusals(refusals, Where.TEMPLATE)
            }
            FormRow("Issue on") {
                Picker(profile?.name ?: "pick a profile", profiles.map { p -> p.name to p.id }, "load-profile") { profileId = it }
                (lanes as? FixMessageViewModel.FanOutLanes.Available)?.let { a ->
                    Sub(
                        "${a.lanes.size} lanes logged on · " + a.lanes.take(LANES_NAMED).joinToString(", ") { it.senderCompID } +
                            (if (a.lanes.size > LANES_NAMED) " …" else ""),
                    )
                    a.shortfall?.let { Sub(it, AppTheme.Colors.warning) }
                }
                Refusals(refusals, Where.PROFILE)
            }

            GroupHead("How hard")
            FormRow("Shape") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlimSegmented(
                        options = listOf(true, false),
                        selected = burst,
                        onSelect = { burst = it },
                        label = { if (it) "Burst" else "Rate" },
                        optionTestTag = { if (it) "load-shape-burst" else "load-shape-rate" },
                    )
                    if (burst) {
                        SlimField(count, { count = it }, modifier = Modifier.width(64.dp).testTag("load-count"))
                        Sub("messages, as fast as the lanes carry them")
                    } else {
                        SlimField(rate, { rate = it }, modifier = Modifier.width(56.dp).testTag("load-rate"))
                        Sub("/s for")
                        SlimField(forText, { forText = it }, modifier = Modifier.width(52.dp).testTag("load-for"))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Sub("presets")
                    PRESETS.forEach { preset ->
                        Chip(preset.label, on = preset.matches(burst, count, rate, forText), tag = "load-preset-${preset.slug}") {
                            burst = preset.burst
                            preset.count?.let { count = it }
                            preset.rate?.let { rate = it }
                            preset.forText?.let { forText = it }
                        }
                    }
                }
                Refusals(refusals, Where.SHAPE)
            }
            FormRow("Settle") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlimField(settle, { settle = it }, modifier = Modifier.width(52.dp).testTag("load-settle"))
                    Sub("wait for replies after the last send, then judge")
                }
            }

            Disclosure(
                open = advancedOpen,
                summary =
                    listOfNotNull(
                        match?.let { "${it.requestTag} ${dictionary.getFieldName(it.requestTag) ?: "?"}" },
                        replyType.trim().takeIf { it.isNotEmpty() }?.let { "reply $it" },
                        if (forLoad) "memory store" else "the profile's store",
                        listen.size.takeIf { it > 0 }?.let { "+$it listening" },
                    ).joinToString(" · "),
                changed = hidden?.let { "open, because a refusal names ${it.where.noun}" },
                onToggle = { advancedOpen = !advancedOpen },
            )
            if (advancedOpen) {
                FormRow("Match") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        SlimField(requestTag, { requestTag = it }, monospace = true, modifier = Modifier.width(44.dp).testTag("load-request-tag"))
                        Sub(tagName(dictionary, requestTag))
                        Sub("→")
                        SlimField(replyTag, { replyTag = it }, monospace = true, modifier = Modifier.width(44.dp).testTag("load-reply-tag"))
                        Sub(tagName(dictionary, replyTag))
                        Sub("reply type")
                        SlimField(replyType, { replyType = it }, monospace = true, modifier = Modifier.width(36.dp).testTag("load-reply-type"))
                        Sub(if (replyType.isBlank()) "optional" else msgTypeName(dictionary, replyType.trim()).trim())
                    }
                    Sub("tags read off the template. A reply carrying the tag under any other type is a stray.")
                    Refusals(refusals, Where.MATCH)
                }
                FormRow("Also match on") {
                    val others =
                        profiles.filter { p ->
                            p.id != profileId && viewModel.getProfileSessions(p.id).any { it.connectionState.value == FixConnectionState.LOGGED_ON }
                        }
                    if (others.isEmpty()) {
                        Sub("no other profile is logged on")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            others.forEach { p ->
                                SlimCheckbox(
                                    checked = p.id in listen,
                                    onCheckedChange = { listen = if (p.id in listen) listen - p.id else listen + p.id },
                                    testTag = "load-listen-${p.id}",
                                ) { Text(p.name, color = AppTheme.Colors.text, style = AppTheme.Type.body) }
                            }
                        }
                        Sub("listen only, never issue")
                    }
                }
                FormRow("Seed") {
                    seedRows.forEachIndexed { index, (name, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SlimField(
                                name,
                                { seedRows = seedRows.replaceAt(index, it to value) },
                                modifier = Modifier.width(88.dp).testTag("load-seed-name-$index"),
                            )
                            Sub("=")
                            SlimField(
                                value,
                                { seedRows = seedRows.replaceAt(index, name to it) },
                                modifier = Modifier.width(88.dp).testTag("load-seed-value-$index"),
                            )
                            if (index == seedRows.lastIndex) {
                                Chip("+ add", on = false, tag = "load-seed-add") { seedRows = seedRows + ("" to "") }
                                Chip("mint a new one", on = false, tag = "load-seed-mint") {
                                    seedRows = seedRows.replaceAt(index, name.ifBlank { "run" } to mintSeed())
                                }
                            }
                        }
                    }
                    Sub("in scope as \${name}, on every message this run issues")
                    Refusals(refusals, Where.SEED)
                }
                FormRow("Store and log") {
                    SlimRadioGroup(
                        options = listOf(false, true),
                        selected = forLoad,
                        onSelect = { forLoad = it },
                        optionTestTag = { if (it) "load-store-memory" else "load-store-profile" },
                    ) { memory ->
                        if (memory) {
                            Text("Memory store, no log", color = AppTheme.Colors.text, style = AppTheme.Type.body)
                            Sub("recommended")
                        } else {
                            Text(
                                "As the profile" +
                                    (
                                        profile?.let {
                                            ": ${it.config.messageStore.name.lowercase()} store, " +
                                                (if (it.config.messageLog.name == "NONE") "no log" else "file log")
                                        } ?: ""
                                    ),
                                color = AppTheme.Colors.text,
                                style = AppTheme.Type.body,
                            )
                        }
                    }
                    Refusals(refusals, Where.STORE)
                    if (forLoad &&
                        profile != null &&
                        refusals.none { it.where == Where.STORE } &&
                        (
                            profile.config.messageStore != StoreAndLogOverride.FOR_LOAD.store ||
                                profile.config.messageLog != StoreAndLogOverride.FOR_LOAD.log
                        )
                    ) {
                        Sub("The lanes reconnect with a memory store and no log for this run, and reconnect back when it ends.")
                    }
                }
            }

            profileId?.let { id -> viewModel.fanOutFarEndNotice(id)?.let { Notice(it, AppTheme.Colors.warning, "note", "load-far-end") } }
        }

        Footer(
            why =
                when {
                    refusals.isNotEmpty() -> refusals.first().text
                    lanes !is FixMessageViewModel.FanOutLanes.Available -> "No lane is logged on, so there is nothing to issue on."
                    else -> null
                },
            runnable = runnable,
            onCopy = { plan()?.let { copyToClipboard(cliLine(it)) } },
            onDismiss = onDismiss,
            onRun = ::start,
        )
    }
}

// ------------------------------------------------------------------------------------------------
// The pieces
// ------------------------------------------------------------------------------------------------

/** Which row a refusal belongs under. [ADVANCED] is the set that makes the Advanced group open itself. */
private enum class Where(
    val noun: String,
) {
    TEMPLATE("the template"),
    PROFILE("the profile"),
    SHAPE("the shape"),
    MATCH("the match tags"),
    SEED("the seed"),
    STORE("the store"),
}

private val ADVANCED = setOf(Where.MATCH, Where.SEED, Where.STORE)

private data class Refusal(
    val where: Where,
    val text: String,
)

/**
 * Which row one of [LoadPlan.problems]' sentences belongs under.
 *
 * Read off the sentence rather than returned beside it, because the sentence is the shared thing and the
 * placement is this surface's own: a command line has no rows to put anything under.
 */
private fun placeOf(problem: String): Where =
    when {
        problem.startsWith("The template has no MsgType") -> Where.TEMPLATE
        problem.startsWith("The template reads") -> Where.SEED
        else -> Where.STORE
    }

@Composable
private fun Refusals(refusals: List<Refusal>, where: Where) {
    refusals.filter { it.where == where }.forEach { Notice(it.text, AppTheme.Colors.error, "fix", "load-refusal") }
}

/** A refusal or a warning, on a stripe, with the marker that says which of the two it is. */
@Composable
private fun Notice(text: String, tint: Color, marker: String, tag: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(AppTheme.Colors.surfaceVariant, RoundedCornerShape(2.dp))
                .padding(start = 6.dp, top = 3.dp, end = 6.dp, bottom = 3.dp),
    ) {
        Box(Modifier.width(2.dp).height(14.dp).background(tint))
        Text(marker, color = tint, style = AppTheme.Type.meta, modifier = Modifier.width(22.dp))
        Text(text, color = tint, style = AppTheme.Type.body, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun GroupHead(title: String) {
    Text(title, color = AppTheme.Colors.textDisabled, style = AppTheme.Type.meta, modifier = Modifier.padding(top = 8.dp, bottom = 1.dp))
}

/** A label at a fixed width and everything the row says, stacked, so a refusal lands under its own cause. */
@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = AppTheme.Colors.textSecondary, style = AppTheme.Type.body, modifier = Modifier.width(92.dp).padding(top = 5.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Sub(text: String, color: Color = AppTheme.Colors.textDisabled) {
    if (text.isNotEmpty()) Text(text, color = color, style = AppTheme.Type.meta)
}

/** The Advanced disclosure: a caret, the name, and either what is inside it or why it opened itself. */
@Composable
private fun Disclosure(open: Boolean, summary: String, changed: String?, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable(onClick = onToggle)
                .testTag("load-advanced")
                .padding(vertical = 2.dp),
    ) {
        Text(if (open) "▾" else "▸", color = AppTheme.Colors.textSecondary, style = AppTheme.Type.meta)
        Text("Advanced", color = AppTheme.Colors.textSecondary, style = AppTheme.Type.body)
        Text(
            changed ?: if (open) "" else summary,
            color = if (changed != null) AppTheme.Colors.warning else AppTheme.Colors.textDisabled,
            style = AppTheme.Type.meta,
            modifier = Modifier.testTag("load-advanced-summary"),
        )
    }
}

/** The pinned footer: why Run is off, then the actions. Never inside the scroll region, which is the fix. */
@Composable
private fun Footer(why: String?, runnable: Boolean, onCopy: () -> Unit, onDismiss: () -> Unit, onRun: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surface)) {
        HorizontalDivider(color = AppTheme.Separators.color, thickness = AppTheme.Separators.dividerThickness)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                why.orEmpty(),
                color = AppTheme.Colors.textDisabled,
                style = AppTheme.Type.meta,
                maxLines = 2,
                modifier = Modifier.weight(1f).testTag("load-why"),
            )
            SlimButton("Copy as fixtool load", onClick = onCopy, enabled = runnable, modifier = Modifier.testTag("load-copy-cli"))
            SlimButton("Cancel", onClick = onDismiss)
            SlimButton(
                "Run  $RUN_KEYS",
                color = AppTheme.Colors.success,
                enabled = runnable,
                onClick = onRun,
                modifier = Modifier.testTag("load-run"),
            )
        }
    }
}

/** A preset or a seed action: a word, a border, and an on state. */
@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
        style = AppTheme.Type.meta,
        modifier =
            Modifier
                .background(if (on) AppTheme.Colors.selectionPrimary else AppTheme.Colors.surface, CHIP)
                .border(1.dp, if (on) AppTheme.Colors.primary else AppTheme.Colors.border, CHIP)
                .clickable(onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A picker that looks openable: the border the app's fields wear, and a chevron. */
@Composable
private fun <T> Picker(current: String, options: List<Pair<String, T>>, tag: String, onPick: (T) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier =
                Modifier
                    .height(24.dp)
                    .background(AppTheme.Colors.surface, RoundedCornerShape(2.dp))
                    .border(1.dp, AppTheme.Colors.border, RoundedCornerShape(2.dp))
                    .clickable { open = true }
                    .testTag(tag)
                    .padding(horizontal = 6.dp),
        ) {
            Text(current, color = AppTheme.Colors.text, style = AppTheme.Type.body)
            Text("▾", color = AppTheme.Colors.textSecondary, style = AppTheme.Type.meta)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name, style = AppTheme.Type.body) },
                    onClick = {
                        onPick(value)
                        open = false
                    },
                    modifier = Modifier.testTag("$tag-$name"),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// The plain functions
// ------------------------------------------------------------------------------------------------

/** The four shapes people actually run. A chip reads as on when the fields already say what it says. */
private data class Preset(
    val label: String,
    val slug: String,
    val burst: Boolean,
    val count: String? = null,
    val rate: String? = null,
    val forText: String? = null,
) {
    fun matches(isBurst: Boolean, count: String, rate: String, forText: String): Boolean =
        isBurst == burst &&
            if (burst) this.count == count.trim() else this.rate == rate.trim() && this.forText == forText.trim()
}

private val PRESETS =
    listOf(
        Preset("100 smoke", "smoke", burst = true, count = "100"),
        Preset("4,000 burst", "burst", burst = true, count = "4000"),
        Preset("500/s for 10m", "500", burst = false, rate = "500", forText = "10m"),
        Preset("2,000/s for 1h", "2000", burst = false, rate = "2000", forText = "1h"),
    )

/**
 * The seed rows as the map a plan carries. A name with no value seeds nothing, which is what a bare
 * `run=` always did and is what leaves the "nothing seeds it" refusal standing over an empty field.
 */
internal fun seedMap(rows: List<Pair<String, String>>): Map<String, String> =
    rows.mapNotNull { (k, v) -> if (k.isBlank() || v.isBlank()) null else k.trim() to v.trim() }.toMap()

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }

/** Four hex characters: enough to tell two runs apart in an id, short enough to read off a wire. */
private fun mintSeed(): String = Random.nextInt(SEED_SPACE).toString(HEX).padStart(SEED_CHARS, '0')

/** "QuoteReqID", or "" when the dictionary does not know the tag — never the tag number twice. */
private fun tagName(dictionary: FixDictionary?, raw: String): String =
    raw
        .trim()
        .toIntOrNull()
        ?.let { dictionary?.getFieldName(it) }
        .orEmpty()

/** "QuoteRequest " ready to sit after `35=R`, or "" when the dictionary cannot name the type. */
private fun msgTypeName(dictionary: FixDictionary?, msgType: String?): String {
    val label = msgTypeLabel(dictionary, msgType)
    return if (label == msgType || label == "?") "" else label.substringBefore(" (") + " "
}

/**
 * **The dialog as the line that gates a build.** `fixtool load` already takes every field collected here,
 * so this is a transcription and not a second grammar — see [com.knapsack.fixtool.headless.HeadlessLoad].
 */
internal fun cliLine(plan: LoadPlan): String =
    buildString {
        append("fixtool load ").append(quoted(plan.template.name))
        append(" --profile ").append(quoted(plan.profileName))
        when (val shape = plan.shape) {
            is LoadShape.Burst -> append(" --count ").append(shape.count)
            is LoadShape.Rate -> append(" --rate ").append(shape.perSecond).append("/s --for ").append(compact(shape.forMs))
        }
        append(" --settle ").append(compact(plan.settleMs))
        append(" --match ").append(plan.match.requestTag).append("=").append(plan.match.replyTag)
        plan.match.replyType?.let { append(" --reply-type ").append(it) }
        plan.seed.forEach { (k, v) -> append(" --set ").append(quoted("$k=$v")) }
        plan.listenProfileIds.forEach { append(" --listen ").append(quoted(it)) }
        plan.storeAndLog?.let { append(" --store ").append(it.store.name.lowercase()).append(" --log ").append(it.log.name.lowercase()) }
    }

/** `1h 5m` reads well in a sentence and is two arguments on a command line. */
private fun compact(ms: Long): String = humanDuration(ms).replace(" ", "")

private fun quoted(text: String): String = if (text.any { it.isWhitespace() }) "\"$text\"" else text

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** `Cmd-Enter` on a Mac and `Ctrl+Enter` everywhere else. The app ships on both. */
private val RUN_KEYS: String =
    if (System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")
    ) {
        "⌘↵"
    } else {
        "Ctrl+↵"
    }

private val CHIP = RoundedCornerShape(8.dp)
private const val LANES_NAMED = 6
private const val SEED_SPACE = 0x10000
private const val HEX = 16
private const val SEED_CHARS = 4
