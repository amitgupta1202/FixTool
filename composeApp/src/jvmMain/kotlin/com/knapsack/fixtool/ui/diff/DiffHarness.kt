package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.ExpectationSeeder
import com.knapsack.fixtool.service.MessageView
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.SlimButton
import java.time.Instant

/**
 * **A dev-only bench for [DiffSurface]** — Phase 1's gate, and it is deliberately not a window.
 *
 * "No second window, ever" is a standing rule of this design, and Phase 2 gives the surface a real document
 * tab. So rather than build a throwaway window one phase before the real home arrives, this rides inside the
 * `ScenarioWorkbenchWindow` that Phase 2.2 **deletes anyway** — the host is already condemned, so nothing here
 * is written in order to be thrown away, and the surface is still clickable at full width a phase early.
 *
 * Reached with `FIXTOOL_DIFF_HARNESS=1`. **Delete this file with the workbench window in Phase 2.**
 *
 * The fixtures are the fake venue's own three replies (`tools/fake-venue/fake_venue.py`), because they are the
 * two cases the whole design turns on and they cannot be tested against the QuickFIX demo acceptor — its bytes
 * are already normalized, so a reorder is invisible to it:
 *
 * - **shape** — the party entries swap places, benignly (FIRMA still holds role 1), mixed with a real `151`
 *   regression, a tag added and a tag dropped. A re-order **must** be offered.
 * - **swap** — the two firms swap *roles*. Same tags, same positions, same everything. A re-order must
 *   **never** be offered here: it would rewrite "FIRMA holds role 1" into role 4 and call the step green.
 */
@Composable
fun DiffHarness(dictionary: FixDictionary?, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf("shape") }
    val adapter = dictionary as? FixDictionaryAdapter

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                "DIFF SURFACE — dev bench (fake-venue fixtures) ",
                color = AppTheme.Colors.textDisabled,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            listOf("shape", "swap").forEach { m ->
                SlimButton(
                    m,
                    onClick = { mode = m },
                    color = if (mode == m) AppTheme.Colors.primary else AppTheme.Colors.textSecondary,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        // A fresh session per fixture — keyed on the mode, which is this bench's stand-in for the step id.
        val session =
            remember(mode, adapter) {
                ReconcileSession(
                    original = ExpectationSeeder.seed(golden.fields(), adapter),
                    initialReference =
                        ReferenceMessage.live(
                            view = if (mode == "swap") swap else shape,
                            provenance = ReferenceMessage.Provenance.THIS_RUN,
                            label = "this run · $mode",
                            arrivedAt = arrival,
                        ),
                    dictionary = adapter,
                )
            }

        DiffSurface(session, crumb = "fake-venue › Expect ExecutionReport (8) · mode=$mode")
    }
}

/** The fixtures judge at a fixed instant, so a `~now` row does not go red because the bench was left open. */
private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

private fun view(vararg fields: Pair<Int, String>): MessageView =
    object : MessageView {
        override fun fields(): List<Pair<Int, String>> = fields.toList()
    }

/** The reply a scenario is captured from — the executing firm first. */
private val golden =
    view(
        35 to "8",
        37 to "OID-1",
        11 to "ORD-1",
        17 to "EXEC-1",
        150 to "2",
        39 to "2",
        55 to "EUR/USD",
        54 to "1",
        38 to "1000000",
        44 to "1.08510",
        6 to "1.08510",
        14 to "1000000",
        151 to "0",
        453 to "2",
        448 to "FIRMA",
        447 to "D",
        452 to "1",
        448 to "FIRMB",
        447 to "D",
        452 to "4",
        58 to "filled in full",
    )

/** The entries swap places — and the venue also fills 250,000, adds 2376, and stops sending 58. */
private val shape =
    view(
        35 to "8",
        37 to "OID-1",
        11 to "ORD-1",
        17 to "EXEC-1",
        150 to "2",
        39 to "2",
        55 to "EUR/USD",
        54 to "1",
        38 to "1000000",
        44 to "1.08510",
        6 to "1.08510",
        14 to "1000000",
        151 to "250000",
        453 to "2",
        448 to "FIRMB",
        447 to "D",
        452 to "4",
        448 to "FIRMA",
        447 to "D",
        452 to "1",
        2376 to "Y",
    )

/** The firms swap ROLES. Nothing moved. A re-order offered here would be a false green. */
private val swap =
    view(
        35 to "8",
        37 to "OID-1",
        11 to "ORD-1",
        17 to "EXEC-1",
        150 to "2",
        39 to "2",
        55 to "EUR/USD",
        54 to "1",
        38 to "1000000",
        44 to "1.08510",
        6 to "1.08510",
        14 to "1000000",
        151 to "0",
        453 to "2",
        448 to "FIRMB",
        447 to "D",
        452 to "1",
        448 to "FIRMA",
        447 to "D",
        452 to "4",
        58 to "filled in full",
    )
