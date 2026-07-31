package com.knapsack.fixtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **A [TooltipIconButton] is clickable exactly where it is drawn — no further.**
 *
 * It used not to be. The hover watch was attached as `modifier.hoverable(interactionSource)`, which put the
 * hover node OUTSIDE Material3's `minimumInteractiveComponentSize`, so it measured that node's 48dp minimum
 * touch target rather than the 16dp the caller had asked for. The button then answered the pointer across
 * three times its own width, and in a dense row every button's claim covered its left neighbour entirely:
 * hit-testing hands the pointer to the last sibling drawn, so only the rightmost button in a row of four
 * worked. It shipped in 1.11.0's rules editor and nobody reported it, because a control that hovers and does
 * nothing reads as "the feature doesn't work" rather than "the click missed".
 *
 * The three surfaces that were dense enough to notice ([ScenarioDocumentPane], [ScenariosRail] and the
 * acceptor rules editor) each declared a smaller `LocalMinimumInteractiveComponentSize` to escape it. That
 * worked, but it made every *future* dense row a defect waiting to be found by hand. These tests hold the
 * property at the button, with no such declaration anywhere above them — which is the state every other
 * surface in the app is in.
 *
 * A pitch this dense is what the whole class of bug needs: at 20dp centres the old hit area overhung a
 * neighbour's centre by 4dp, and a click aimed at the middle of a button landed on the one to its right.
 */
class TooltipButtonHitAreaTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a dense row of buttons takes a click on every one of them`() {
        val clicked = mutableListOf<Int>()
        composeTestRule.setContent {
            Row(modifier = Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(4) { index ->
                    TooltipIconButton(
                        tooltip = "button $index",
                        onClick = { clicked += index },
                        modifier = Modifier.size(16.dp).testTag("dense-$index"),
                    ) {
                        Icon(Icons.Default.Close, "button $index", modifier = Modifier.size(10.dp))
                    }
                }
            }
        }

        repeat(4) { composeTestRule.onNodeWithTag("dense-$it").performClick() }

        assertEquals(
            listOf(0, 1, 2, 3),
            clicked,
            "a button whose neighbour swallows its clicks looks enabled, draws a hover, and does nothing",
        )
    }

    /**
     * The same overhang, turned ninety degrees: a list of 24dp rows each ending in a remove button — the
     * settings tag list's shape — where every row's × stood over the one above it. A row test that only
     * looks left and right would pass this and still lose the clicks.
     */
    @Test
    fun `a stack of short rows takes a click on every row's button`() {
        val clicked = mutableListOf<Int>()
        composeTestRule.setContent {
            Column(modifier = Modifier.width(300.dp)) {
                repeat(4) { index ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("row $index")
                        TooltipIconButton(
                            tooltip = "remove row $index",
                            onClick = { clicked += index },
                            modifier = Modifier.size(18.dp).testTag("stacked-$index"),
                        ) {
                            Icon(Icons.Default.Close, "remove row $index", modifier = Modifier.size(10.dp))
                        }
                    }
                }
            }
        }

        repeat(4) { composeTestRule.onNodeWithTag("stacked-$it").performClick() }

        assertEquals(listOf(0, 1, 2, 3), clicked, "the × of every row but the last stood under the next row's ×")
    }

    /**
     * And what the button takes, its neighbour keeps: the overhang did not only lose the *button's* clicks,
     * it stole its neighbour's. Here the neighbour is not a button at all — the Send grid, the diff search
     * bar and the settings rows all put plain clickable content immediately left of one of these.
     */
    @Test
    fun `the button does not take a click aimed at what sits beside it`() {
        var neighbourClicks = 0
        var buttonClicks = 0
        composeTestRule.setContent {
            Row(modifier = Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .background(Color.Gray)
                            .clickable { neighbourClicks++ }
                            .testTag("neighbour"),
                )
                TooltipIconButton(
                    tooltip = "button",
                    onClick = { buttonClicks++ },
                    modifier = Modifier.size(16.dp).testTag("button"),
                ) {
                    Icon(Icons.Default.Close, "button", modifier = Modifier.size(10.dp))
                }
            }
        }

        composeTestRule.onNodeWithTag("neighbour").performClick()

        assertEquals(1, neighbourClicks, "the click landed on the button overhanging its neighbour")
        assertEquals(0, buttonClicks)
    }
}
