package com.knapsack.fixtool.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.ui.AppTheme

fun latencyPage(): SettingsPage =
    SettingsPage(
        id = "latency",
        title = "Latency",
        subtitle = "Round-trip measurement, and the tags a reply is recognised by.",
        contains =
            listOf(
                "latency tracking",
                "round trip",
                "socket",
                "correlation tags",
                "warning threshold",
                "critical threshold",
                "history size",
                "latency column",
            ),
        owns = {
            listOf(
                it.enableLatencyTracking,
                it.showLatencyColumn,
                it.latencyCorrelationTags,
                it.latencyWarningThresholdMicros,
                it.latencyCriticalThresholdMicros,
                it.latencyHistorySize,
            )
        },
        content = { LatencyContent(it) },
    )

@Composable
private fun LatencyContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    SettingsBlock(
        title = "Latency tracking",
        description =
            "Times a request against its reply, stamped where the bytes leave and enter FixTool's socket. " +
                "Works through TLS and needs no privileges.",
    ) {
        SettingsCheckbox(
            label = "Enable latency tracking",
            description = "Measure round-trip time by following a correlation id from send to reply",
            checked = settings.enableLatencyTracking,
            onCheckedChange = { draft.edit { copy(enableLatencyTracking = it) } },
        )
    }

    if (!settings.enableLatencyTracking) return

    SettingsBlock(title = "Display") {
        SettingsCheckbox(
            label = "Show latency column in grid view",
            description = "Give measured round-trip times their own column in the message grid",
            checked = settings.showLatencyColumn,
            onCheckedChange = { draft.edit { copy(showLatencyColumn = it) } },
        )
    }

    // The one setting the previous dialog stored but never drew. It sat in AppSettings, was read on every
    // session, and could only be changed by hand-editing ~/.fixtool/app_settings.json — which is to say
    // that everyone ran the six defaults whether they suited the venue or not.
    SettingsBlock(
        title = "Correlation tags",
        description =
            "The tags latency follows a message by: a reply is matched to its request when one of these " +
                "carries the same value both ways. Venue identifiers declared under Protocol › Venue tag " +
                "roles are read by scenario capture, not here — add one to this list to time it as well.",
    ) {
        TagListEditor(
            selected = settings.latencyCorrelationTags,
            fields = context.fields,
            nameOf = { tag -> context.dictionary.getFieldName(tag) ?: "Unknown" },
            onChange = { picked -> draft.edit { copy(latencyCorrelationTags = picked) } },
            emptyNote = "No correlation tags — nothing can be paired, so no latency will be measured.",
            testTagPrefix = "settings-correlation-tags",
        )
    }

    SettingsBlock(
        title = "Thresholds",
        description = "Where a measured round trip stops being ordinary, and where it stops being tolerable.",
    ) {
        NumberField(draft = draft, setting = NumberSetting.LATENCY_WARNING)
        NumberField(draft = draft, setting = NumberSetting.LATENCY_CRITICAL)
        NumberField(draft = draft, setting = NumberSetting.LATENCY_HISTORY)
    }
}

fun developerPage(): SettingsPage =
    SettingsPage(
        id = "developer",
        title = "Developer",
        subtitle = "A loopback door for driving FixTool from a script or an agent.",
        contains = listOf("automation control", "control server", "MCP", "Claude", "port", "curl", "HTTP"),
        owns = { listOf(it.automationControlEnabled, it.automationControlPort) },
        content = { DeveloperContent(it) },
    )

@Composable
private fun DeveloperContent(context: SettingsContext) {
    val draft = context.draft
    val settings = draft.value

    SettingsBlock(
        title = "Automation control",
        description =
            "Runs a control and MCP server bound to 127.0.0.1 so Claude, an MCP client or curl can drive " +
                "FixTool for automated testing. Off by default; applied when you click Save.",
    ) {
        SettingsCheckbox(
            label = "Enable automation control",
            description = "Serves on the loopback port below — the FIXTOOL_CONTROL_PORT env var, if set, wins over it",
            checked = settings.automationControlEnabled,
            onCheckedChange = { draft.edit { copy(automationControlEnabled = it) } },
        )
        NumberField(draft = draft, setting = NumberSetting.CONTROL_PORT)
    }

    SettingsBlock(title = "Connect Claude Code", description = "Uses the embedded MCP server.") {
        SelectionContainer {
            Text(
                text = "claude mcp add --transport http fixtool http://127.0.0.1:${settings.automationControlPort}/mcp",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = AppTheme.Colors.text,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.Colors.background, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
