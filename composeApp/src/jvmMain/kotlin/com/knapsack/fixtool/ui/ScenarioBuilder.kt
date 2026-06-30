// Compose UI: dense composable calls read best on one line; multi-arg composables are idiomatic.
@file:Suppress("MaxLineLength", "LongParameterList", "MatchingDeclarationName")

package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.service.RawMessageView

/** A mutable, kind-discriminated step under construction in the [ScenarioBuilder]. */
data class StepDraft(
    val kind: String,
    val session: String = "",
    val raw: String = "",
    val state: String = "",
    val direction: String = "in",
    val timeoutMs: Long = 10_000,
    val goldenRaw: String = "",
    val expectation: Expectation? = null,
    val sender: Int? = null,
    val target: Int? = null,
)

private fun StepDraft.toStep(): ScenarioStep? {
    val s = session.ifBlank { null }
    return when (kind) {
        "send" -> ScenarioStep.Send(raw, s)
        "wait" -> ScenarioStep.Wait(s, state.ifBlank { null }, null, timeoutMs)
        "expect" -> expectation?.let { ScenarioStep.Expect(s, direction, null, timeoutMs, it) }
        "clear" -> ScenarioStep.ClearMessages(s)
        "reset" -> ScenarioStep.ResetSeqNum(s, sender, target)
        else -> null
    }
}

/**
 * Visual scenario builder (no JSON): add Send / Wait / Expect / Clear / Reset steps, set a per-step
 * session (multi-session authoring — drive an initiator and assert on an acceptor in one scenario),
 * reorder/remove, author each Expect's expectation inline via [ExpectationBuilder], then save.
 */
@Composable
fun ScenarioBuilder(dictionary: FixDictionary?, onSave: (Scenario) -> Unit, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<StepDraft>() }
    var editingExpect by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Scenario name", fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.width(260.dp).testTag("scenario-name"),
            )
            OutlinedButton(
                onClick = { onSave(Scenario(id = java.util.UUID.randomUUID().toString(), name = name, steps = steps.mapNotNull { it.toStep() })) },
                enabled = name.isNotBlank() && steps.isNotEmpty(),
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Text("Save scenario", color = AppTheme.Colors.success, fontSize = 12.sp)
            }
        }
        AddStepBar { steps.add(StepDraft(kind = it)) }
        // Plain Column (not LazyColumn): the dialog wraps this builder in a verticalScroll, and a
        // LazyColumn nested in a scrollable parent throws ("infinity max height").
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            steps.forEachIndexed { index, draft ->
                StepCard(
                    index = index,
                    total = steps.size,
                    draft = draft,
                    dictionary = dictionary,
                    editingExpect = editingExpect == index,
                    onChange = { steps[index] = it },
                    onToggleEditExpect = { editingExpect = if (editingExpect == index) null else index },
                    onMove = { delta ->
                        val to = index + delta
                        if (to in steps.indices) {
                            val tmp = steps[index]; steps[index] = steps[to]; steps[to] = tmp
                        }
                    },
                    onRemove = { steps.removeAt(index) },
                )
            }
        }
    }
}

@Composable
private fun AddStepBar(onAdd: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add step:", color = AppTheme.Colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        listOf("send", "wait", "expect", "clear", "reset").forEach { kind ->
            OutlinedButton(onClick = { onAdd(kind) }) {
                Text(kind, fontSize = 11.sp, color = AppTheme.Colors.text)
            }
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    total: Int,
    draft: StepDraft,
    dictionary: FixDictionary?,
    editingExpect: Boolean,
    onChange: (StepDraft) -> Unit,
    onToggleEditExpect: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("#$index ${draft.kind}", color = AppTheme.Colors.text, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.width(96.dp))
            CompactField("session", draft.session, 120.dp, tag = "session-$index") { onChange(draft.copy(session = it)) }
            Row(modifier = Modifier.weight(1f)) {}
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = AppTheme.Colors.textSecondary)
            }
            IconButton(onClick = { onMove(1) }, enabled = index < total - 1) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Down", tint = AppTheme.Colors.textSecondary)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AppTheme.Colors.error)
            }
        }
        StepFields(draft, dictionary, editingExpect, onChange, onToggleEditExpect)
    }
}

@Composable
private fun StepFields(
    draft: StepDraft,
    dictionary: FixDictionary?,
    editingExpect: Boolean,
    onChange: (StepDraft) -> Unit,
    onToggleEditExpect: () -> Unit,
) {
    when (draft.kind) {
        "send" -> CompactField("raw FIX (| delimited)", draft.raw, 540.dp) { onChange(draft.copy(raw = it)) }
        "wait" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField("state (e.g. LOGGED_ON)", draft.state, 220.dp) { onChange(draft.copy(state = it)) }
            CompactField("timeoutMs", draft.timeoutMs.toString(), 110.dp) { onChange(draft.copy(timeoutMs = it.toLongOrNull() ?: draft.timeoutMs)) }
        }
        "reset" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField("sender", draft.sender?.toString() ?: "", 100.dp) { onChange(draft.copy(sender = it.toIntOrNull())) }
            CompactField("target", draft.target?.toString() ?: "", 100.dp) { onChange(draft.copy(target = it.toIntOrNull())) }
        }
        "expect" -> ExpectFields(draft, dictionary, editingExpect, onChange, onToggleEditExpect)
        else -> Unit
    }
}

@Composable
private fun ExpectFields(
    draft: StepDraft,
    dictionary: FixDictionary?,
    editingExpect: Boolean,
    onChange: (StepDraft) -> Unit,
    onToggleEditExpect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactField("direction", draft.direction, 90.dp) { onChange(draft.copy(direction = it)) }
        CompactField("timeoutMs", draft.timeoutMs.toString(), 110.dp) { onChange(draft.copy(timeoutMs = it.toLongOrNull() ?: draft.timeoutMs)) }
        OutlinedButton(onClick = onToggleEditExpect) {
            val label = draft.expectation?.let { "expectation ✓ ${it.fields.size} tags" } ?: "edit expectation"
            Text(label, fontSize = 11.sp, color = AppTheme.Colors.text)
        }
    }
    if (editingExpect) {
        CompactField("golden raw (paste a captured message, then seed)", draft.goldenRaw, 620.dp) {
            onChange(draft.copy(goldenRaw = it))
        }
        val drafts = remember(draft.goldenRaw) { ExpectationDrafts.fromRaw(draft.goldenRaw, dictionary) }
        val golden = remember(draft.goldenRaw) { RawMessageView(draft.goldenRaw) }
        val messageType = remember(draft.goldenRaw) { drafts.firstOrNull { it.tag == 35 }?.value ?: "" }
        ExpectationBuilder(
            messageType = messageType,
            initialFields = drafts,
            goldenView = golden,
            secondView = null,
            onSave = {
                onChange(draft.copy(expectation = it))
                onToggleEditExpect()
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun CompactField(
    label: String,
    value: String,
    fieldWidth: androidx.compose.ui.unit.Dp,
    tag: String? = null,
    onChange: (String) -> Unit,
) {
    val base = Modifier.width(fieldWidth).padding(top = 4.dp)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 9.sp) },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        modifier = if (tag != null) base.testTag(tag) else base,
    )
}
