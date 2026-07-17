package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.service.SessionMapping
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * Author a [SessionMapping] for [scenario] and (optionally) run with it at once — the "same flow,
 * other environment" door. The left column is fixed: it is what the scenario *says* (its recorded
 * session names, which never change here — a mapping is a run input, not an edit). The right column
 * is what those names should mean this time, picked from every name the app can currently resolve:
 * saved profiles and open sessions. A profile that is not connected is a fine target — the run's
 * preflight auto-connects it.
 *
 * The mapping is saved app-locally ([FixMessageViewModel.saveSessionMapping]) and is not tied to this
 * scenario: every scenario recorded against the same sessions can run through it.
 */
@Composable
fun SessionMappingDialog(
    scenario: Scenario,
    viewModel: FixMessageViewModel,
    onDismiss: () -> Unit,
) {
    val from =
        remember(scenario) {
            (scenario.setup + scenario.steps + scenario.teardown).mapNotNull { it.sessionOrNull() }.distinct()
        }
    // Everything a target name can currently resolve to. Profiles first — the mapping's whole point is
    // "the other environment", whose sessions are typically not connected yet.
    val candidates =
        remember(viewModel.connectionProfiles, viewModel.sessions) {
            (viewModel.connectionProfiles.map { it.name } + viewModel.sessions.map { it.title }).distinct()
        }
    var name by remember { mutableStateOf("") }
    val targets = remember(from) { mutableStateMapOf<String, String>().apply { from.forEach { put(it, it) } } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(6.dp), color = AppTheme.Colors.surface) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(440.dp).padding(14.dp).testTag("session-mapping-dialog"),
            ) {
                Text("Run on other sessions", color = AppTheme.Colors.text, fontSize = 13.sp)
                Text(
                    "Map the sessions '${scenario.name}' was recorded against onto the ones to run " +
                        "against now. The scenario itself is not changed. A target that is not connected " +
                        "is fine — the run connects its profile automatically.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Name", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.width(110.dp))
                    SlimField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "e.g. QA",
                        modifier = Modifier.weight(1f).testTag("mapping-name"),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    // A capture can touch many sessions; the dialog must not grow past the screen with them.
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                ) {
                    from.forEach { session ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                session,
                                color = AppTheme.Colors.text,
                                fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier.width(110.dp),
                            )
                            Text("→", color = AppTheme.Colors.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp))
                            SlimDropdown(
                                value = targets[session],
                                options = candidates,
                                onValueChange = { targets[session] = it ?: session },
                                displayText = { it },
                                modifier = Modifier.weight(1f).testTag("mapping-target-$session"),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    SlimButton("Cancel", onClick = onDismiss, color = AppTheme.Colors.textSecondary)
                    // Identity entries are dropped: a mapping is the differences it makes, and an entry
                    // "dev-buyside → dev-buyside" saved today would silently re-aim nothing forever.
                    val effective = targets.filter { (k, v) -> k != v }
                    SlimButton(
                        "Save & run",
                        onClick = {
                            val mapping =
                                SessionMapping(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    map = effective,
                                )
                            viewModel.saveSessionMapping(mapping)
                            viewModel.runScenario(scenario, mapping)
                            onDismiss()
                        },
                        color = if (name.isNotBlank() && effective.isNotEmpty()) AppTheme.Colors.success else AppTheme.Colors.textDisabled,
                        enabled = name.isNotBlank() && effective.isNotEmpty(),
                        modifier = Modifier.testTag("mapping-save-run"),
                    )
                }
            }
        }
    }
}
