package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * The Scenarios window: list saved scenarios, run one (▶) and watch its per-tag red/green report,
 * delete (🗑), or author a new scenario by pasting its JSON. The runner is deterministic — no AI in
 * the loop — so this is also where a failed assertion is rendered in the app.
 */
@Composable
fun ScenariosDialog(viewModel: FixMessageViewModel, onClose: () -> Unit) {
    val dialogState = rememberDialogState(width = 1040.dp, height = 760.dp)
    var building by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    Dialog(onCloseRequest = onClose, title = "Repeatable Scenarios", state = dialogState) {
        Column(modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface)) {
            TitleBar(building = building, onToggleBuild = { building = !building }, onClose = onClose)
            if (building) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                    ScenarioBuilder(
                        dictionary = viewModel.dictionary,
                        onSave = {
                            viewModel.scenarioService.save(it)
                            refreshKey++
                            building = false
                        },
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxHeight().width(380.dp).padding(12.dp)) {
                        androidx.compose.runtime.key(refreshKey) { ScenarioListPane(viewModel) }
                    }
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(AppTheme.Colors.border))
                    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        ResultsPane(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(building: Boolean, onToggleBuild: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(AppTheme.Colors.background)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (building) "Build a scenario" else "Repeatable Scenarios",
            color = AppTheme.Colors.text,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onToggleBuild) {
            Text(if (building) "Cancel" else "Build scenario", color = AppTheme.Colors.text, fontSize = 12.sp)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = AppTheme.Colors.text)
        }
    }
}

@Composable
private fun ScenarioListPane(viewModel: FixMessageViewModel) {
    var scenarios by remember { mutableStateOf(viewModel.scenarioService.list()) }
    var authoring by remember { mutableStateOf(false) }
    fun refresh() {
        scenarios = viewModel.scenarioService.list()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                text = "Saved scenarios (${scenarios.size})",
                color = AppTheme.Colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { authoring = !authoring }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New",
                    tint = AppTheme.Colors.text,
                    modifier = Modifier.height(16.dp),
                )
                Text(
                    text = if (authoring) "Cancel" else "New (JSON)",
                    color = AppTheme.Colors.text,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (authoring) {
            JsonAuthoring(viewModel) {
                authoring = false
                refresh()
            }
        }
        if (scenarios.isEmpty()) {
            Text(
                text = "No scenarios yet. Create one with \"New (JSON)\" or the fixtool_save_scenario tool.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(scenarios) { scenario ->
                    ScenarioRow(
                        scenario = scenario,
                        onRun = { viewModel.runScenario(scenario) },
                        onDelete = {
                            viewModel.scenarioService.delete(scenario.id)
                            refresh()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioRow(scenario: Scenario, onRun: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.Colors.surfaceVariant)
            .padding(start = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(scenario.name, color = AppTheme.Colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val detail = "${scenario.steps.size} steps" + (scenario.profile?.let { " · $it" } ?: "")
            Text(detail, color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        IconButton(onClick = onRun) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = AppTheme.Colors.success)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AppTheme.Colors.error)
        }
    }
}

@Composable
private fun JsonAuthoring(viewModel: FixMessageViewModel, onSaved: () -> Unit) {
    var json by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            label = { Text("Scenario JSON", fontSize = 11.sp) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        OutlinedButton(
            onClick = { if (viewModel.saveScenarioJson(json) != null) onSaved() },
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text("Save scenario", color = AppTheme.Colors.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultsPane(viewModel: FixMessageViewModel) {
    val result by viewModel.scenarioResult.collectAsState()
    val running by viewModel.scenarioRunning.collectAsState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            running -> Text("Running scenario…", color = AppTheme.Colors.textSecondary, fontSize = 13.sp)
            result != null -> ScenarioResultsView(result!!)
            else -> {
                Text(
                    text = "Run a scenario to see its per-tag result here.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Each step shows pass/fail; an expect step expands into one row per asserted " +
                        "tag (tag, matcher, expected vs actual) — red where it failed.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
