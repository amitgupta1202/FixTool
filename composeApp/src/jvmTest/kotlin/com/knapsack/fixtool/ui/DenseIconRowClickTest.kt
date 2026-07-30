package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **Every button in a dense icon row has to take a click, not just the last one.**
 *
 * The rules editor's step row has four 16dp buttons side by side, and the one immediately left of
 * another was not receiving clicks at all — the semantics action fired the callback, the pointer never
 * arrived. This renders the same shape in isolation and clicks each button in turn, which is the only
 * way to tell "this button is wired wrongly" from "this row eats clicks".
 */
class DenseIconRowClickTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `each button in a dense row receives its own click`() {
        val clicked = mutableListOf<Int>()
        composeTestRule.setContent {
            // The surface is what fixes this — a dense row has to declare the touch target its density
            // implies, or Material3's 48dp default makes each button's target cover its left neighbour.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 16.dp) {
                Row(modifier = Modifier.width(300.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
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
        }

        composeTestRule.onNodeWithTag("dense-0").performClick()
        composeTestRule.onNodeWithTag("dense-1").performClick()
        composeTestRule.onNodeWithTag("dense-2").performClick()

        assertEquals(
            listOf(0, 1, 2),
            clicked,
            "a button whose neighbour swallows its clicks looks enabled, draws a hover, and does nothing",
        )
    }
}
