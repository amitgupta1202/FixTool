package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.scenario.ScenarioResult
import com.knapsack.fixtool.model.scenario.StepResult
import com.knapsack.fixtool.model.scenario.TagResult

/** Pass/fail palette, from the shared app theme. */
private val PassGreen = AppTheme.Colors.success
private val FailRed = AppTheme.Colors.error
private val MutedText = AppTheme.Colors.textSecondary
private val PanelText = AppTheme.Colors.text

/**
 * Renders a [ScenarioResult] as a scrollable, tag-by-tag red/green report — the in-app answer to
 * "where does a failed assertion show up". Each step is a colored row; an `expect` step expands into
 * one row per asserted tag (tag, matcher, expected vs actual), red where it failed.
 */
@Composable
fun ScenarioResultsView(result: ScenarioResult, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Verdict(result.passed)
            Text(
                text = result.scenario,
                color = PanelText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            val counts = "${result.steps.count { it.passed }}/${result.steps.size} steps"
            Text(text = counts, color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        // Plain Column (not LazyColumn): the caller already provides vertical scrolling, and a
        // LazyColumn nested in a scrollable parent throws ("infinity max height").
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            result.steps.forEach { StepRow(it) }
        }
    }
}

@Composable
private fun StepRow(step: StepResult) {
    val color = if (step.passed) PassGreen else FailRed
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Verdict(step.passed)
            Text(
                text = "#${step.stepIndex} ${step.kind}",
                color = color,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = "(${step.phase})",
                color = MutedText,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
            step.detail?.takeIf { step.tags.isEmpty() }?.let {
                Text(text = it, color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        step.tags.forEach { TagRow(it) }
    }
}

@Composable
private fun TagRow(tag: TagResult) {
    val color = if (tag.passed) PassGreen else FailRed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 1.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = if (tag.passed) "✓" else "✗", color = color, fontSize = 12.sp)
        Text(
            text = "${tag.tag}",
            color = PanelText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = tag.matcher,
            color = MutedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
        // expected vs actual — the part that makes a failure read on its own.
        Text(
            text = "expected ${tag.expected}  ·  actual ${tag.actual ?: "<absent>"}",
            color = if (tag.passed) MutedText else FailRed,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun Verdict(passed: Boolean) {
    val color = if (passed) PassGreen else FailRed
    Text(
        text = if (passed) "PASS" else "FAIL",
        color = AppTheme.Colors.background,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
