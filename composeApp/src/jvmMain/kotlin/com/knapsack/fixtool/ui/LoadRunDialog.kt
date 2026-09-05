package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.headless.HeadlessRun
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.StoreAndLogOverride
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.RunSets
import com.knapsack.fixtool.service.load.CompiledTemplate
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * **The load run dialog**: one row per decision, the same shape as the fan-out dialog.
 *
 * Two doors open it, the editor's Load button with the editor's fields as the template and the rail's
 * Run menu with a template picker, and both end in the same [LoadPlan]. Every refusal is a sentence on
 * screen and Run is what refuses: a memory store on a profile without Reset on Logon, a template with no
 * correlation tag, a name nothing seeds. The far-end notice is amber and does not refuse.
 */
@Composable
fun LoadRunDialog(
    viewModel: FixMessageViewModel,
    fixedTemplate: LoadTemplate?,
    onDismiss: () -> Unit,
    onRun: (LoadPlan) -> Unit,
) {
    Dialog(onCloseRequest = onDismiss, title = "Load run", state = rememberDialogState(width = 620.dp, height = 560.dp)) {
        LoadRunDialogContent(viewModel, fixedTemplate, onDismiss, onRun)
    }
}

/** The dialog's body without its window, so a test can drive it in a plain composition. */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun LoadRunDialogContent(
    viewModel: FixMessageViewModel,
    fixedTemplate: LoadTemplate?,
    onDismiss: () -> Unit,
    onRun: (LoadPlan) -> Unit,
) {
    val profiles = viewModel.connectionProfiles
    var profileId by remember {
        mutableStateOf(profiles.firstOrNull { viewModel.loadLanes(it.id) is FixMessageViewModel.FanOutLanes.Available }?.id ?: profiles.firstOrNull()?.id)
    }
    val templates = remember(profileId) { if (fixedTemplate != null) listOf(fixedTemplate) else viewModel.loadTemplates(profileId) }
    var template by remember { mutableStateOf(fixedTemplate ?: templates.firstOrNull()) }
    var listen by remember { mutableStateOf(setOf<String>()) }
    var burst by remember { mutableStateOf(true) }
    var count by remember { mutableStateOf("4000") }
    var rate by remember { mutableStateOf("500") }
    var forText by remember { mutableStateOf("10m") }
    var requestTag by remember(template) { mutableStateOf(template?.inferMatch()?.requestTag?.toString() ?: "") }
    var replyTag by remember(template) { mutableStateOf(template?.inferMatch()?.replyTag?.toString() ?: "") }
    var replyType by remember { mutableStateOf("") }
    var settle by remember { mutableStateOf("60s") }
    var seedText by remember { mutableStateOf("run=") }
    var forLoad by remember { mutableStateOf(true) }

    val profile = profiles.firstOrNull { it.id == profileId }
    val lanes = profileId?.let { viewModel.loadLanes(it) }
    val compiled = remember(template) { template?.takeIf { it.msgType != null }?.let { runCatching { CompiledTemplate.compile(it) }.getOrNull() } }
    val seed = remember(seedText) { parseSeed(seedText) }
    val shape: LoadShape? =
        if (burst) {
            count.trim().toIntOrNull()?.takeIf { it > 0 }?.let { LoadShape.Burst(it) }
        } else {
            val r = rate.trim().removeSuffix("/s").toIntOrNull()?.takeIf { it > 0 }
            val f = HeadlessRun.parseDuration(forText)?.takeIf { it > 0 }
            if (r != null && f != null) LoadShape.Rate(r, f) else null
        }
    val match = requestTag.trim().toIntOrNull()?.let { req -> LoadMatch(req, replyTag.trim().toIntOrNull() ?: req, replyType.trim().ifBlank { null }) }
    val override = if (forLoad) StoreAndLogOverride.FOR_LOAD else null
    val storeProblem = profile?.let { (override?.applyTo(it.config) ?: it.config).storeProblem() }
    val missing = compiled?.missingVariables(seed.keys + Lane.SEED_NAMES).orEmpty()
    val refusals =
        listOfNotNull(
            if (template == null) "Pick a template, or save a message under this profile first." else null,
            if (template != null && template?.msgType == null) "The template has no MsgType (35)." else null,
            if (template != null && match == null) "The template carries no tag a reply can be matched on. Name the request and reply tags." else null,
            if (missing.isNotEmpty()) "The template reads ${missing.joinToString(", ") { "\${$it}" }} and nothing seeds ${if (missing.size == 1) "it" else "them"}. Add ${missing.first()}=… under Seed." else null,
            storeProblem?.let { "${profile?.name}: $it Turn Reset on Logon on for the profile, or run with the file store." },
            if (shape == null) (if (burst) "Count must be a whole number above zero." else "Rate needs a number per second and a duration such as 10m.") else null,
            (lanes as? FixMessageViewModel.FanOutLanes.Unavailable)?.why,
        )

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize().background(AppTheme.Colors.background).padding(12.dp).verticalScroll(rememberScrollState()).testTag("load-dialog"),
    ) {
        run {
            Text(
                "Issues this message across a profile's sessions without waiting for replies, then accounts for every " +
                    "reply that lands on any session that is logged on.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
            )

            DialogRow("Template") {
                if (fixedTemplate != null) {
                    Text(fixedTemplate.name, color = AppTheme.Colors.text, fontSize = 11.sp, modifier = Modifier.testTag("load-template"))
                } else {
                    Picker(template?.name ?: "pick a template", templates.map { it.name to it }, "load-template") { template = it }
                }
                compiled?.let {
                    Sub("35=${it.msgType} · per message: ${it.perMessageTags.joinToString(", ").ifEmpty { "none" }} · fixed: ${it.fixedTags.joinToString(", ")}")
                }
            }
            DialogRow("Issue on") {
                Picker(profile?.name ?: "pick a profile", profiles.map { p -> (p.name + laneCount(viewModel, p.id)) to p.id }, "load-profile") { profileId = it }
                (lanes as? FixMessageViewModel.FanOutLanes.Available)?.let { a ->
                    Sub("${a.lanes.size} lanes logged on · " + a.lanes.take(LANES_NAMED).joinToString(", ") { it.senderCompID } + (if (a.lanes.size > LANES_NAMED) " …" else ""))
                    a.shortfall?.let { Sub(it, AppTheme.Colors.warning) }
                }
            }
            DialogRow("Also match on") {
                val others = profiles.filter { p -> p.id != profileId && viewModel.getProfileSessions(p.id).any { it.connectionState.value == FixConnectionState.LOGGED_ON } }
                if (others.isEmpty()) Sub("no other profile is logged on")
                others.forEach { p ->
                    Choice(p.name, selected = p.id in listen, tag = "load-listen-${p.id}") { listen = if (p.id in listen) listen - p.id else listen + p.id }
                }
                if (listen.isNotEmpty()) Sub("listen only, never issue")
            }
            DialogRow("Shape") {
                Choice("Burst", selected = burst, tag = "load-shape-burst") { burst = true }
                if (burst) {
                    SlimField(count, { count = it }, modifier = Modifier.width(64.dp).testTag("load-count"))
                    Sub("messages, as fast as the lanes carry them")
                }
                Choice("Rate", selected = !burst, tag = "load-shape-rate") { burst = false }
                if (!burst) {
                    SlimField(rate, { rate = it }, modifier = Modifier.width(56.dp).testTag("load-rate"))
                    Sub("/s for")
                    SlimField(forText, { forText = it }, modifier = Modifier.width(52.dp).testTag("load-for"))
                }
            }
            DialogRow("Match") {
                Sub("request")
                SlimField(requestTag, { requestTag = it }, modifier = Modifier.width(44.dp).testTag("load-request-tag"))
                Sub("→ reply")
                SlimField(replyTag, { replyTag = it }, modifier = Modifier.width(44.dp).testTag("load-reply-tag"))
                Sub("reply type")
                SlimField(replyType, { replyType = it }, modifier = Modifier.width(36.dp).testTag("load-reply-type"))
                Sub("optional")
            }
            DialogRow("Settle") {
                SlimField(settle, { settle = it }, modifier = Modifier.width(52.dp).testTag("load-settle"))
                Sub("how long to wait for replies after the last send")
            }
            DialogRow("Seed") {
                SlimField(seedText, { seedText = it }, modifier = Modifier.width(200.dp).testTag("load-seed"))
                Sub("name=value, in scope as \${name}")
            }
            DialogRow("Store and log") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Choice("As the profile" + (profile?.let { ": ${it.config.messageStore.name.lowercase()} store, ${if (it.config.messageLog.name == "NONE") "no log" else "file log"}" } ?: ""), selected = !forLoad, tag = "load-store-profile") { forLoad = false }
                    Choice("Memory store, no log  ·  recommended for a load run", selected = forLoad, tag = "load-store-memory") { forLoad = true }
                }
            }

            refusals.forEach { Text(it, color = AppTheme.Colors.error, fontSize = 10.sp, modifier = Modifier.testTag("load-refusal")) }
            profileId?.let { id -> viewModel.fanOutFarEndNotice(id)?.let { Text(it, color = AppTheme.Colors.warning, fontSize = 10.sp, modifier = Modifier.testTag("load-far-end")) } }
            if (forLoad && profile != null && (profile.config.messageStore != StoreAndLogOverride.FOR_LOAD.store || profile.config.messageLog != StoreAndLogOverride.FOR_LOAD.log) && storeProblem == null) {
                Text("The lanes reconnect with a memory store and no log for this run, and reconnect back when it ends.", color = AppTheme.Colors.textDisabled, fontSize = 10.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimButton("Cancel", onClick = onDismiss)
                SlimButton(
                    "Run",
                    color = AppTheme.Colors.success,
                    enabled = refusals.isEmpty() && lanes is FixMessageViewModel.FanOutLanes.Available,
                    onClick = {
                        val t = template ?: return@SlimButton
                        val p = profile ?: return@SlimButton
                        val s = shape ?: return@SlimButton
                        val m = match ?: return@SlimButton
                        val label = LoadPlan.label(t, s, p.name)
                        onRun(
                            LoadPlan(
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
                            ),
                        )
                    },
                    modifier = Modifier.testTag("load-run"),
                )
            }
        }
    }
}

@Composable
private fun DialogRow(label: String, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppTheme.Colors.textSecondary, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        content()
    }
}

@Composable
private fun Sub(text: String, color: Color = AppTheme.Colors.textDisabled) {
    Text(text, color = color, fontSize = 10.sp)
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Text(
        (if (selected) "◉ " else "○ ") + label,
        color = if (selected) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
        fontSize = 11.sp,
        modifier = Modifier.selectable(selected = selected, onClick = onSelect).testTag(tag),
    )
}

@Composable
private fun <T> Picker(current: String, options: List<Pair<String, T>>, tag: String, onPick: (T) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        SlimButton(current, onClick = { open = true }, modifier = Modifier.testTag(tag))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 11.sp) },
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

private fun laneCount(viewModel: FixMessageViewModel, profileId: String): String =
    when (val l = viewModel.loadLanes(profileId)) {
        is FixMessageViewModel.FanOutLanes.Available -> "  (${l.lanes.size} lanes)"
        is FixMessageViewModel.FanOutLanes.Unavailable -> "  (no lanes)"
    }

/** `run=b7f2, desk=fx` or one per line. A key with no value is left out, so a bare `run=` seeds nothing. */
internal fun parseSeed(text: String): Map<String, String> =
    text
        .split(',', '\n', ';')
        .mapNotNull { part ->
            val (k, v) = part.split("=", limit = 2).takeIf { it.size == 2 }?.map { it.trim() } ?: return@mapNotNull null
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }.toMap()

private const val LANES_NAMED = 6
