package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.rememberDialogState
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * The Scenarios window — capture-driven. Record the current session flow into a scenario, run a
 * saved scenario (its results render back in the session window — green/red rows + per-tag drill-in),
 * or open one in the visual builder to relax/edit its assertions. No separate results pane: results
 * live where the messages do.
 */
@Composable
fun ScenariosDialog(viewModel: FixMessageViewModel, onClose: () -> Unit) {
    val dialogState = rememberDialogState(width = 1040.dp, height = 760.dp)
    var building by remember { mutableStateOf(false) }
    var editScenario by remember { mutableStateOf<Scenario?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    Dialog(onCloseRequest = onClose, title = "Repeatable Scenarios", state = dialogState) {
        Column(modifier = Modifier.fillMaxSize().background(AppTheme.Colors.surface)) {
            TitleBar(building = building, onCancelBuild = { building = false }, onClose = onClose)
            if (building) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                    ScenarioBuilder(
                        dictionary = viewModel.dictionary,
                        initial = editScenario,
                        onSave = {
                            viewModel.scenarioService.save(it)
                            refreshKey++
                            building = false
                        },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    androidx.compose.runtime.key(refreshKey) {
                        ScenarioListPane(
                            viewModel = viewModel,
                            onNew = { editScenario = null; building = true },
                            onEdit = { editScenario = it; building = true },
                            onRun = { viewModel.runScenario(it); onClose() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(building: Boolean, onCancelBuild: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(AppTheme.Colors.background)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (building) "Edit scenario" else "Repeatable Scenarios",
            color = AppTheme.Colors.text,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (building) {
            OutlinedButton(onClick = onCancelBuild) {
                Text("Back to list", color = AppTheme.Colors.text, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = AppTheme.Colors.text)
        }
    }
}

@Composable
private fun ScenarioListPane(
    viewModel: FixMessageViewModel,
    onNew: () -> Unit,
    onEdit: (Scenario) -> Unit,
    onRun: (Scenario) -> Unit,
) {
    var scenarios by remember { mutableStateOf(viewModel.scenarioService.list()) }
    var captureName by remember { mutableStateOf("") }
    fun refresh() {
        scenarios = viewModel.scenarioService.list()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // Capture-from-session is the primary way to author a scenario.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            OutlinedTextField(
                value = captureName,
                onValueChange = { captureName = it },
                label = { Text("New scenario name", fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
            OutlinedButton(
                onClick = {
                    val name = captureName.ifBlank { "Captured scenario" }
                    if (viewModel.captureScenarioFromSessions(name) != null) {
                        captureName = ""
                        refresh()
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Capture current session(s)", color = AppTheme.Colors.success, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onNew, modifier = Modifier.padding(start = 8.dp)) {
                Text("New (visual)", color = AppTheme.Colors.text, fontSize = 12.sp)
            }
        }
        Text(
            text = "Run replays the flow; results show in the session window (green/red rows + per-tag detail).",
            color = AppTheme.Colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (scenarios.isEmpty()) {
            Text(
                "No scenarios yet. Capture the current session flow to create one.",
                color = AppTheme.Colors.textSecondary,
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(scenarios) { scenario ->
                    ScenarioRow(
                        scenario = scenario,
                        onRun = { onRun(scenario) },
                        onEdit = { onEdit(scenario) },
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
private fun ScenarioRow(scenario: Scenario, onRun: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(AppTheme.Colors.surfaceVariant).padding(start = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(scenario.name, color = AppTheme.Colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val detail = "${scenario.steps.size} steps" + (scenario.profile?.let { " · $it" } ?: "")
            Text(detail, color = AppTheme.Colors.textSecondary, fontSize = 11.sp)
        }
        IconButton(onClick = onRun) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = AppTheme.Colors.success)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AppTheme.Colors.textSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AppTheme.Colors.error)
        }
    }
}
