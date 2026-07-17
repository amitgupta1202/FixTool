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
import com.knapsack.fixtool.viewmodel.FixMessageViewModel

/**
 * Duplicate [scenario] with its sessions re-aimed — the "same flow, other environment" door. The left
 * column is what the original was recorded against; the right column is what the copy should target,
 * picked from every name the app can resolve: saved profiles and open sessions. A target that is not
 * connected is fine — the copy's runs auto-connect its profile.
 *
 * The result is a **new scenario**, on purpose. Environments diverge in data — QA's fills are not
 * dev's — so each environment's scenario must evolve and reconcile independently: a QA failure's
 * "Accept actual" lands in the QA copy, and the original's expectations never hear about it. The price
 * is the usual one for copies (a flow change must land in each), paid knowingly; the copies stay
 * side-by-side in the rail and git-diffable against each other.
 */
@Composable
fun RemapScenarioDialog(
    scenario: Scenario,
    viewModel: FixMessageViewModel,
    onDismiss: () -> Unit,
) {
    val from =
        remember(scenario) {
            (scenario.setup + scenario.steps + scenario.teardown).mapNotNull { it.sessionOrNull() }.distinct()
        }
    // Everything a target name can currently resolve to. Profiles first — the whole point is "the
    // other environment", whose sessions are typically not connected yet.
    val candidates =
        remember(viewModel.connectionProfiles, viewModel.sessions) {
            (viewModel.connectionProfiles.map { it.name } + viewModel.sessions.map { it.title }).distinct()
        }
    var name by remember(scenario) { mutableStateOf("${scenario.name} (copy)") }
    val targets = remember(from) { mutableStateMapOf<String, String>().apply { from.forEach { put(it, it) } } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(6.dp), color = AppTheme.Colors.surface) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.width(440.dp).padding(14.dp).testTag("remap-scenario-dialog"),
            ) {
                Text("Save as scenario for other sessions", color = AppTheme.Colors.text, fontSize = 13.sp)
                Text(
                    "Creates a copy of '${scenario.name}' whose steps target the sessions on the right — " +
                        "e.g. a QA version of a flow recorded on dev. The copy is its own scenario: run it, " +
                        "reconcile it, and edit its expectations without touching the original. A target " +
                        "that is not connected is fine — the run connects its profile automatically.",
                    color = AppTheme.Colors.textSecondary,
                    fontSize = 10.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Name", color = AppTheme.Colors.textSecondary, fontSize = 10.sp, modifier = Modifier.width(110.dp))
                    SlimField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "e.g. ${scenario.name} (qa)",
                        modifier = Modifier.weight(1f).testTag("remap-name"),
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
                                modifier = Modifier.weight(1f).testTag("remap-target-$session"),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    SlimButton("Cancel", onClick = onDismiss, color = AppTheme.Colors.textSecondary)
                    // Identity entries are dropped — the copy's difference from the original is the
                    // sessions it targets, and "dev-buyside → dev-buyside" is not a difference.
                    val sessionMap = targets.filter { (k, v) -> k != v }
                    val valid = name.isNotBlank() && sessionMap.isNotEmpty()
                    fun create(): Scenario? = viewModel.duplicateScenarioRemapped(scenario, name.trim(), sessionMap)
                    SlimButton(
                        "Create",
                        onClick = {
                            create()
                            onDismiss()
                        },
                        color = if (valid) AppTheme.Colors.text else AppTheme.Colors.textDisabled,
                        enabled = valid,
                        modifier = Modifier.testTag("remap-create"),
                    )
                    SlimButton(
                        "Create & run",
                        onClick = {
                            create()?.let { viewModel.runScenario(it) }
                            onDismiss()
                        },
                        color = if (valid) AppTheme.Colors.success else AppTheme.Colors.textDisabled,
                        enabled = valid,
                        modifier = Modifier.testTag("remap-create-run"),
                    )
                }
            }
        }
    }
}
