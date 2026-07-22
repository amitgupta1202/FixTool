package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.AppTooltip
import com.knapsack.fixtool.ui.ReconcileCompletion
import com.knapsack.fixtool.ui.SlimButton
import com.knapsack.fixtool.ui.StepChip
import com.knapsack.fixtool.ui.StepStatus

private val chipShape = RoundedCornerShape(3.dp)

/**
 * **The pass, along the top.** One chip per Expect step of the scenario, coloured by where it stands, the one
 * in view filled in, click to go there.
 *
 * It answers the question a reconcile pass is actually made of — *how much is left* — which a window scoped to
 * one step could never answer, because it could not see the others. Derived by
 * [com.knapsack.fixtool.ui.stepStripOf]; this draws it and decides nothing.
 *
 * The glyphs steer around two vocabularies this app already owns: `◀ ▶` mean message direction, and
 * `« » ± × ↧` are the diff gutter's repair offers. Neither may mean "step" as well.
 */
@Composable
fun StepStrip(
    chips: List<StepChip>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The right-hand end of the row, for a control the window would otherwise spend a band of height on —
     * today the diff's search box. The same bargain the summary beside it struck, and the reason the chips
     * scroll: a lender of space can only lend what it does not need, and a `LazyRow` needs none.
     */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    if (chips.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .background(AppTheme.Colors.surfaceHeader)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("diff-step-strip"),
    ) {
        // **The current chip is brought back into view whenever it moves.** A strip long enough to scroll is a
        // strip that can be showing steps 9–14 while the pass has just landed the author on step 2 — and a
        // "where am I" control that has to be hunted for has stopped being one. Save & re-run moves the step
        // without touching the scroll, so nothing else would put it right.
        val listState = rememberLazyListState()
        val currentAt = chips.indexOfFirst { it.current }
        LaunchedEffect(chips.getOrNull(currentAt)?.stepId) {
            if (currentAt >= 0) listState.animateScrollToItem(currentAt)
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Whatever the summary leaves, and the chips scroll inside it.
            modifier = Modifier.weight(1f),
        ) {
            items(chips, key = { it.stepId }) { chip -> StepChipView(chip, onSelect) }
        }
        // **Beside the chips, not under them.** A header of four stacked bands spends height in a window whose
        // whole job is two dense columns of FIX fields, and this band was one short sentence wide. And it sits
        // *outside* the scroller: a summary that scrolls away with the chips disappears at exactly the step
        // count that made it worth reading.
        val summary =
            com.knapsack.fixtool.ui
                .stepStripSummary(chips)
        if (summary.isNotBlank()) {
            Text(
                summary,
                color = AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp).testTag("diff-step-strip-summary"),
            )
        }
        // Last, at the far end, with a gap: the summary counts *steps* and the search box counts *rows*, and
        // two tallies that touch read as one thing said twice.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) { trailing() }
    }
}

/**
 * A chip is a glyph and a step number, and that is all it can afford to be — see `stepStripOf` for why the
 * message type is not on it. The type, the status and any armed slot are all in the tooltip, which is the
 * only place a fourteen-step strip has room to say them.
 */
@Composable
private fun StepChipView(chip: StepChip, onSelect: (String) -> Unit) {
    val tint = statusColor(chip.status)
    AppTooltip(chip.tooltip) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier =
                Modifier
                    // The current chip is filled, the way ModeChip fills to say "this is what is in force".
                    .background(if (chip.current) AppTheme.Colors.surface else Color.Transparent, chipShape)
                    .border(1.dp, if (chip.current) tint else Color.Transparent, chipShape)
                    .clickable { onSelect(chip.stepId) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("diff-step-chip-${chip.index}"),
        ) {
            Text(statusGlyph(chip.status), color = tint, fontSize = 10.sp)
            Text(
                chip.label,
                color = if (chip.current) AppTheme.Colors.text else AppTheme.Colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = if (chip.current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
            // An armed slot on a step that is NOT in view has no banner to announce it — the surface only draws
            // the banner for the step it is showing. Without this mark, arming Step 3 and walking to Step 5
            // leaves the author's next grid click bound to something nothing on screen mentions.
            if (chip.armed) Text("◎", color = AppTheme.Colors.primary, fontSize = 10.sp)
        }
    }
}

private fun statusGlyph(status: StepStatus): String =
    when (status) {
        StepStatus.FAILING -> "●"
        StepStatus.REPAIRED -> "◑"
        StepStatus.PASSING -> "✓"
        StepStatus.NOT_REACHED -> "·"
        StepStatus.NOT_RUN -> "·"
    }

private fun statusColor(status: StepStatus): Color =
    when (status) {
        StepStatus.FAILING -> AppTheme.Colors.error
        StepStatus.REPAIRED -> AppTheme.Colors.warning
        StepStatus.PASSING -> AppTheme.Colors.success
        StepStatus.NOT_REACHED -> AppTheme.Colors.textDisabled
        StepStatus.NOT_RUN -> AppTheme.Colors.textDisabled
    }

/**
 * **The pass has an ending.** A re-run that finally comes back green swaps the body for this: what the pass
 * repaired, and one button that closes it.
 *
 * Without it a green run left the author staring at a solved diff with no signal that anything had concluded —
 * and, before the window became the scenario's, left them closing one leftover window per step they had fixed.
 * The repairs are named because a pass is several Save & re-runs long and the earlier ones have scrolled out of
 * living memory by the time the last one lands.
 */
@Composable
fun ScenarioGreen(
    completion: ReconcileCompletion,
    scenarioName: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().testTag("diff-scenario-green"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text("This scenario is green.", color = AppTheme.Colors.success, fontSize = 15.sp)
            Text(
                if (completion.repaired.isEmpty()) {
                    "$scenarioName ran clean."
                } else {
                    "${completion.repaired.size} " +
                        (if (completion.repaired.size == 1) "step" else "steps") +
                        " repaired · saved to $scenarioName"
                },
                color = AppTheme.Colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            completion.repaired.forEach { repair ->
                Text(
                    "${repair.label} — ${repair.edits.joinToString(" · ").ifBlank { "repaired" }}",
                    color = AppTheme.Colors.textDisabled,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            SlimButton(
                text = "Done",
                onClick = onDone,
                color = AppTheme.Colors.primary,
                modifier = Modifier.padding(top = 16.dp).testTag("diff-green-done"),
            )
        }
    }
}
