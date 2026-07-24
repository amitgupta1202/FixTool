package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.AcceptorLatencyConfig
import com.knapsack.fixtool.model.AcceptorLatencyConfig.Mode

/**
 * Authoring for an acceptor profile's simulated response latency.
 *
 * The number this configures is drawn once per triggering message and shifts the matched rule's whole
 * reply; the per-step delays in [AcceptorRulesEditor] are the venue's own processing time, layered on
 * top. The two editors sit in adjacent sections because they answer adjacent questions — *what* the
 * venue replies, and *how long the wire to it takes* — and a tester reasoning about a timeout wants
 * both in view. See [AcceptorLatencyConfig] for why one sample, not one per step.
 */
@Composable
fun AcceptorLatencyEditor(
    latency: AcceptorLatencyConfig,
    onLatencyChange: (AcceptorLatencyConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mode", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            SlimDropdown(
                value = latency.mode,
                options = Mode.entries.toList(),
                onValueChange = { it?.let { mode -> onLatencyChange(latency.copy(mode = mode)) } },
                displayText = ::modeLabel,
                modifier = Modifier.width(150.dp),
            )
        }

        // Only the fields the current mode reads are shown — an author setting a fixed delay is not
        // asked about a standard deviation that does nothing, and the values kept off-screen are kept,
        // not cleared, so flipping between modes to compare does not lose what was typed.
        when (latency.mode) {
            Mode.NONE -> Unit
            Mode.FIXED ->
                LatencyRow {
                    Text("Delay", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    MillisField(latency.fixedMillis) { onLatencyChange(latency.copy(fixedMillis = it)) }
                    Text("ms", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                }
            Mode.RANDOM_RANGE ->
                LatencyRow {
                    Text("Between", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    MillisField(latency.minMillis) { onLatencyChange(latency.copy(minMillis = it)) }
                    Text("and", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    MillisField(latency.maxMillis) { onLatencyChange(latency.copy(maxMillis = it)) }
                    Text("ms", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                }
            Mode.NORMAL ->
                LatencyRow {
                    Text("Mean", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    MillisField(latency.meanMillis) { onLatencyChange(latency.copy(meanMillis = it)) }
                    Text("ms  ±", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
                    MillisField(latency.stdDevMillis) { onLatencyChange(latency.copy(stdDevMillis = it)) }
                    Text("ms sd", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
                }
        }

        // Spikes ride on top of any mode, [Mode.NONE] included — a venue that is instant until it
        // isn't is a real and worth-testing shape, so the row is always offered rather than nested
        // inside a mode that would hide it.
        LatencyRow {
            Text("Spike", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            PercentField(latency.spikeProbability) { onLatencyChange(latency.copy(spikeProbability = it)) }
            Text("% of replies →", color = AppTheme.Colors.textSecondary, fontSize = 9.sp)
            MillisField(latency.spikeMinMillis) { onLatencyChange(latency.copy(spikeMinMillis = it)) }
            Text("–", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
            MillisField(latency.spikeMaxMillis) { onLatencyChange(latency.copy(spikeMaxMillis = it)) }
            Text("ms", color = AppTheme.Colors.textDisabled, fontSize = 9.sp)
        }

        Text(
            text = describeLatency(latency),
            color = AppTheme.Colors.textSecondary,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        // Said here for the same reason a rule's error is said on its card: a config that cannot make
        // sense — a max below its min — looks configured, and the engine's own coercion would quietly
        // paper over it rather than tell the author which number to fix.
        latency.validationError()?.let { problem ->
            Text(
                text = "⚠ $problem",
                color = AppTheme.Colors.warning,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A one-line "on/off + shape" summary, echoing the collapsed section header so both agree at a glance. */
internal fun describeLatency(c: AcceptorLatencyConfig): String {
    val base =
        when (c.mode) {
            Mode.NONE -> if (c.spikeProbability > 0.0) "No base delay" else "No added delay"
            Mode.FIXED -> "${c.fixedMillis}ms, fixed"
            Mode.RANDOM_RANGE -> "${c.minMillis}–${c.maxMillis}ms"
            Mode.NORMAL -> "~${c.meanMillis}ms ± ${c.stdDevMillis}ms"
        }
    if (c.spikeProbability <= 0.0) return base
    return "$base · ${formatPercent(c.spikeProbability)}% spike to ${c.spikeMinMillis}–${c.spikeMaxMillis}ms"
}

private fun modeLabel(mode: Mode): String =
    when (mode) {
        Mode.NONE -> "Off (no delay)"
        Mode.FIXED -> "Fixed"
        Mode.RANDOM_RANGE -> "Random range"
        Mode.NORMAL -> "Normal distribution"
    }

@Composable
private fun LatencyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = { content() },
    )
}

@Composable
private fun MillisField(
    value: Long,
    width: Dp = 56.dp,
    onChange: (Long) -> Unit,
) {
    // Only digits reach the model, so a half-typed value cannot silently become 0 and move a response
    // to a moment the author never chose — the same guard the step-delay field makes.
    SlimField(
        value = value.toString(),
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() }.toLongOrNull() ?: 0L) },
        modifier = Modifier.width(width),
        monospace = true,
    )
}

@Composable
private fun PercentField(
    value: Double,
    onChange: (Double) -> Unit,
) {
    // Shown as a percentage because "5%" is how a spike rate is spoken; stored as the 0–1 probability
    // the sampler flips against. A single dot is allowed through for sub-percent rates (0.5%).
    SlimField(
        value = formatPercent(value),
        onValueChange = { typed -> onChange(parsePercent(typed)) },
        modifier = Modifier.width(48.dp),
        monospace = true,
    )
    Spacer(Modifier.width(0.dp))
}

private fun parsePercent(typed: String): Double {
    val cleaned = StringBuilder()
    var seenDot = false
    for (ch in typed) {
        when {
            ch.isDigit() -> cleaned.append(ch)
            ch == '.' && !seenDot -> {
                seenDot = true
                cleaned.append(ch)
            }
        }
    }
    val pct = cleaned.toString().toDoubleOrNull() ?: 0.0
    return (pct / 100.0).coerceIn(0.0, 1.0)
}

private fun formatPercent(probability: Double): String {
    val pct = probability * 100.0
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()
}
