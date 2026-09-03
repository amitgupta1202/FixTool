package com.knapsack.fixtool.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **A tooltip that is showing must not take the click out from under the thing it describes.**
 *
 * The detail pane's field list is a [SelectionContainer] so a tag value can be dragged and copied. The ↗
 * beside a correlation id lives inside it, and its "Follow across sessions" tooltip is a [Text] drawn in a
 * popup: a separate layout root. Composed inside the container, that popup text inherited the container's
 * selection registrar and joined the list of selectables. A mouse press on any text in the container
 * starts a selection gesture, and the first thing a selection does is sort every selectable by position
 * relative to the container — which for the popup's text means converting coordinates between two roots
 * that share no ancestor. Compose refuses with `IllegalArgumentException: layouts are not part of the
 * same hierarchy`, the pointer coroutine dies, the desktop error dialog comes up, and the click that
 * was meant to follow the trace is lost. Reported 2026-09-03 against the QuoteReqID ↗ in Message Details.
 *
 * The property here is that every tooltip bubble the app draws is outside the selection machinery
 * ([androidx.compose.foundation.text.selection.DisableSelection]), so the press is just a press.
 */
class TooltipInSelectionContainerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** The reported case: hover the ↗ until its tooltip shows, then click it. */
    @Test
    fun `clicking the follow glyph while its tooltip shows follows instead of crashing`() {
        var followed = 0
        composeTestRule.setContent {
            SelectionContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("9d171d7f-dbce-4f58-8fcd-97b1")
                    FollowTraceButton(following = false, onClick = { followed++ })
                }
            }
        }

        composeTestRule.onNodeWithTag("follow-trace").performMouseInput { moveTo(center) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithText("Follow across sessions").assertIsDisplayed()

        composeTestRule.onNodeWithTag("follow-trace").performMouseInput { click(center) }
        composeTestRule.waitForIdle()

        assertEquals(1, followed, "the press died in the selection sort before the click handler saw it")
    }

    /**
     * The same fault through [AppTooltip] around arbitrary content, pressed on a *neighbouring* text
     * rather than the tooltipped one: the popup text is what poisons the sort, not the glyph under the
     * pointer, so any press inside the container while the bubble is up is enough.
     */
    @Test
    fun `pressing selectable text while a neighbour's tooltip shows does not crash`() {
        var clicks = 0
        composeTestRule.setContent {
            SelectionContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("selectable value", modifier = Modifier.testTag("value"))
                    AppTooltip(text = "what the glyph does") {
                        Box(modifier = Modifier.testTag("glyph").clickable { clicks++ }.padding(4.dp)) {
                            Text("↗")
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("glyph").performMouseInput { moveTo(center) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithText("what the glyph does").assertIsDisplayed()

        // One injection block, so the press lands before the exit has had a frame to take the bubble
        // down — exactly the ordering a real press gets: the tooltip hides on the press's initial pass
        // and the selection gesture still runs on its main pass.
        composeTestRule.onNodeWithTag("value").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        composeTestRule.waitForIdle()

        assertEquals(0, clicks)
    }

    /** [TooltipIconButton] draws its bubble through its own path and must be held to the same property. */
    @Test
    fun `pressing selectable text while an icon button's tooltip shows does not crash`() {
        composeTestRule.setContent {
            SelectionContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("selectable value", modifier = Modifier.testTag("value"))
                    TooltipIconButton(
                        tooltip = "icon button tooltip",
                        onClick = {},
                        modifier = Modifier.size(16.dp).testTag("icon-button"),
                    ) {
                        Icon(Icons.Default.Close, "close", modifier = Modifier.size(10.dp))
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("icon-button").performMouseInput { moveTo(center) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithText("icon button tooltip").assertIsDisplayed()

        composeTestRule.onNodeWithTag("value").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        composeTestRule.waitForIdle()
    }
}
