package com.knapsack.fixtool.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.ui.AppTheme
import com.knapsack.fixtool.ui.StepChip
import com.knapsack.fixtool.ui.StepStatus
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **The step strip lends its empty span, and lends it last.**
 *
 * The strip is a `LazyRow` of chips plus a one-line summary, and on any real window most of it is air — which
 * is why the summary rides beside the chips rather than under them, and now why the diff's search box does
 * too. A window whose whole job is two dense columns of FIX fields should not spend a band of height on a
 * control one line wide.
 *
 * What this pins is the part that could quietly regress: the borrowed control keeps its width *whatever the
 * chip count*, because the chips are the thing that scrolls and a lender can only lend what it does not need.
 * Fourteen steps must not squeeze the search box to nothing — that is the same defect the Send field row's
 * delete button had (see `RowTool`).
 */
class StepStripTrailingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun chips(n: Int) =
        (1..n).map { i ->
            StepChip(
                stepId = "s$i",
                index = i,
                label = "$i",
                status = if (i == 1) StepStatus.FAILING else StepStatus.PASSING,
                current = i == 1,
                tooltip = "step $i",
                armed = false,
            )
        }

    private fun strip(chipCount: Int, width: Int = 900) {
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width.dp, 80.dp).background(AppTheme.Colors.background)) {
                StepStrip(
                    chips = chips(chipCount),
                    onSelect = {},
                    trailing = { Text("borrowed", modifier = Modifier.testTag("strip-trailing")) },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun trailingWidth(): Int =
        composeTestRule
            .onNodeWithTag("strip-trailing", useUnmergedTree = true)
            .fetchSemanticsNode()
            .size.width

    @Test
    fun `the trailing slot is drawn`() {
        strip(chipCount = 2)
        composeTestRule.onNodeWithTag("strip-trailing", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(trailingWidth() > 0)
    }

    /** The summary still gets its place — the borrowed slot is added beside it, not in place of it. */
    @Test
    fun `the strip keeps its own summary`() {
        strip(chipCount = 3)
        composeTestRule.onNodeWithTag("diff-step-strip-summary", useUnmergedTree = true).assertExists()
    }

    /**
     * The one that matters. A fourteen-step pass fills the chip row; the chips must give (they scroll) and the
     * borrowed control must not shrink by a pixel.
     */
    @Test
    fun `a long pass does not squeeze the borrowed control`() {
        strip(chipCount = 2)
        val roomy = trailingWidth()
        composeTestRule.runOnIdle {}

        strip(chipCount = 14)
        assertTrue(
            trailingWidth() == roomy,
            "fourteen chips must scroll rather than eat the trailing slot; was $roomy, now ${trailingWidth()}",
        )
    }

    /** And on a narrow window, likewise: the chips are the flexible thing, so they are what narrows. */
    @Test
    fun `a narrow window does not squeeze the borrowed control`() {
        strip(chipCount = 14, width = 900)
        val roomy = trailingWidth()

        strip(chipCount = 14, width = 520)
        assertTrue(trailingWidth() == roomy, "the chips narrow, not the borrowed control; was $roomy, now ${trailingWidth()}")
    }
}
