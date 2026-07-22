package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchMode
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.TemporalKind
import com.knapsack.fixtool.service.compare.ReferenceMessage
import com.knapsack.fixtool.service.wireView
import com.knapsack.fixtool.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/**
 * **The search strip, rendered** (`diff_search.png`) — and the one structural claim a picture cannot make:
 * a matched row wears the gold **edge**, never a gold **background**, because its background is already the
 * pass/fail ledger (`DiffPalette.valueRow` and its siblings) and repainting it would erase the very thing
 * the reader opened the diff to see.
 */
class DiffSearchScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
    private val arrival: Instant = Instant.parse("2026-07-14T09:35:44Z")

    /** A failing step: 31 and 151 diverge, so matched rows and red rows overlap and the edge has to show. */
    private val expectation =
        Expectation(
            fields =
                listOf(
                    FieldExpectation(35, Matcher.Exact("8")),
                    FieldExpectation(11, Matcher.Exact("ORD-1")),
                    FieldExpectation(14, Matcher.Numeric(1000000.0)),
                    FieldExpectation(17, Matcher.Presence),
                    FieldExpectation(31, Matcher.Numeric(1.09244)),
                    FieldExpectation(37, Matcher.Presence),
                    FieldExpectation(38, Matcher.Numeric(1000000.0)),
                    FieldExpectation(39, Matcher.Exact("2")),
                    FieldExpectation(54, Matcher.Exact("1")),
                    FieldExpectation(60, Matcher.Temporal(TemporalKind.NOW_WITHIN_TOLERANCE, 60)),
                    FieldExpectation(150, Matcher.Exact("2")),
                    FieldExpectation(151, Matcher.Numeric(0.0)),
                ),
            messageType = "8",
            mode = MatchMode.OPEN,
        )

    private val reply =
        wireView(
            35 to "8",
            11 to "ORD-1",
            14 to "1000000",
            17 to "EXEC-9",
            31 to "1.09999",
            37 to "VENUE-77",
            38 to "1000000",
            39 to "2",
            54 to "1",
            60 to "20260714-09:35:44",
            150 to "2",
            151 to "250",
        )

    private fun surface() {
        composeTestRule.setContent {
            var current by remember { mutableStateOf(expectation) }
            val session =
                remember(Unit) {
                    ReconcileSession(
                        original = current,
                        initialReference =
                            ReferenceMessage.live(reply, ReferenceMessage.Provenance.THIS_RUN, "received — this run · 09:35:44", arrival),
                        dictionary = dictionary,
                        onChange = { current = it },
                    )
                }
            Box(modifier = Modifier.size(1180.dp, 620.dp).background(AppTheme.Colors.background)) {
                DiffSurface(session, crumb = "rfq flow v2 › Step 2 · Expect ExecutionReport (8)")
            }
        }
        composeTestRule.waitForIdle()
    }

    /** How many line rows the surface is drawing, under whichever of the three marks each one wears. */
    private fun rowCount(): Int =
        listOf("diff-row", "diff-row-highlighted", "diff-row-search-match").sumOf { tag ->
            composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        }

    @Test
    fun `a matched row is outlined, and the rest of the reply stays on screen`() {
        surface()
        val rowsBefore = rowCount()

        composeTestRule.onNodeWithTag("diff-search").performTextReplacement("lastpx")
        composeTestRule.waitForIdle()

        val matched = composeTestRule.onAllNodesWithTag("diff-row-search-match", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(1, matched, "LastPx is tag 31 and nothing else answers 'lastpx'")

        // Nothing left the list — the right column is still the reply, whole. Rows only changed their mark.
        assertEquals(rowsBefore, rowCount(), "search must mark rows, never remove them")

        val out = File("build/scenario-screenshots").absoluteFile.apply { mkdirs() }
        ImageIO.write(composeTestRule.onRoot().captureToImage().toAwtImage(), "png", File(out, "diff_search.png"))
    }

    /**
     * The walk: `↓` moves the cursor through the matches, the tally says where it has got to, and the end of
     * the list is not the end of the walk — an arrow that goes dead at the bottom of a list whose bottom the
     * author cannot see is an arrow that looks broken.
     */
    @Test
    fun `the arrows walk the matches and wrap`() {
        surface()
        composeTestRule.onNodeWithTag("diff-search").performTextReplacement("1000000")
        composeTestRule.waitForIdle()

        // OrderQty (38) and CumQty (14) both carry 1000000 — two matches, and the walk starts on the first.
        assertTally("1 / 2")
        next()
        assertTally("2 / 2")
        next()
        assertTally("1 / 2")
    }

    private fun next() {
        composeTestRule.onNodeWithTag("diff-search-next").performClick()
        composeTestRule.waitForIdle()
    }

    private fun assertTally(expected: String) =
        composeTestRule.onNodeWithTag("diff-search-tally", useUnmergedTree = true).assertTextEquals(expected)
}
