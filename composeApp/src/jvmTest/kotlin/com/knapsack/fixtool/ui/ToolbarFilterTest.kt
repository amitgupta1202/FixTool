package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **The filter cannot go silent, and presence is what keeps it that way.**
 *
 * The regex used to sit in the middle of the toolbar, narrowing every pane from a field nobody had
 * looked at since they typed in it, and the direction boxes beside it were worse: two ticks that hide
 * half the traffic and say nothing at all when they are off. A row of its own under the pane bar fixed
 * that with a rule, the row refuses to close while it is narrowing anything, at the price of a line of
 * pane height in both layouts.
 *
 * It is back in the middle of the toolbar, and the rule is kept by the plainest means there is: the
 * controls are always on screen, so the regex is in its box and one direction segment is pressed whether
 * anybody asked for them or not. These are the tests that stop that being quietly undone.
 */
class ToolbarFilterTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the filter is on the toolbar with nothing asked for and nothing narrowing`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.requiredWidth(1700.dp)) {
                Toolbar(onClearAll = { })
            }
        }

        composeTestRule.onNodeWithTag("toolbar-filter").assertExists()
        composeTestRule.onNodeWithTag("toolbar-filter-regex").assertExists()
        composeTestRule.onNodeWithTag("toolbar-filter-both").assertIsSelected()
        composeTestRule.onNodeWithTag("toolbar-filter-in").assertIsNotSelected()
        composeTestRule.onNodeWithTag("toolbar-filter-out").assertIsNotSelected()
    }

    /** And at a width where every chip beside it has given up its word, because it is the last to give way. */
    @Test
    fun `a toolbar too narrow for its own words still draws the filter`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.requiredWidth(900.dp)) {
                Toolbar(onClearAll = { })
            }
        }

        composeTestRule.onNodeWithTag("toolbar-filter-regex").assertExists()
    }

    @Test
    fun `typing in the box narrows every pane`() {
        var regex by mutableStateOf("")
        composeTestRule.setContent {
            ToolbarFilter(
                query = FilterQuery(global = MessageFilters.Global(regex = regex)),
                onRegexChange = { regex = it },
            )
        }

        composeTestRule.onNodeWithTag("toolbar-filter-regex").performTextInput("35=8")
        composeTestRule.waitForIdle()

        assertEquals("35=8", regex, "the field reports what was typed, straight through")
    }

    /**
     * **Each segment sets both directions at once**, because it names one of three states rather than
     * flipping one of two booleans. The model is still the two booleans the control surface writes.
     */
    @Test
    fun `a segment sets both directions to the state it names`() {
        var incoming by mutableStateOf(true)
        var outgoing by mutableStateOf(true)
        composeTestRule.setContent {
            ToolbarFilter(
                query = FilterQuery(global = MessageFilters.Global(showIncoming = incoming, showOutgoing = outgoing)),
                onIncomingChange = { incoming = it },
                onOutgoingChange = { outgoing = it },
            )
        }

        composeTestRule.onNodeWithTag("toolbar-filter-in").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true to false, incoming to outgoing, "IN shows incoming and hides outgoing")
        composeTestRule.onNodeWithTag("toolbar-filter-in").assertIsSelected()

        composeTestRule.onNodeWithTag("toolbar-filter-out").performClick()
        composeTestRule.waitForIdle()
        assertEquals(false to true, incoming to outgoing, "OUT goes straight from IN to outgoing only")

        composeTestRule.onNodeWithTag("toolbar-filter-both").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true to true, incoming to outgoing, "and Both puts every message back")
    }

    /** A segmented control selects. A second click on the pressed segment is not a way back to Both. */
    @Test
    fun `clicking the segment already in force changes nothing`() {
        var calls = 0
        composeTestRule.setContent {
            ToolbarFilter(
                query = FilterQuery(global = MessageFilters.Global(showOutgoing = false)),
                onIncomingChange = { calls++ },
                onOutgoingChange = { calls++ },
            )
        }

        composeTestRule.onNodeWithTag("toolbar-filter-in").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, calls)
    }

    /** Only the control surface can set both off, and the toolbar must not pretend one of its states is in force. */
    @Test
    fun `both directions off presses no segment`() {
        composeTestRule.setContent {
            ToolbarFilter(query = FilterQuery(global = MessageFilters.Global(showIncoming = false, showOutgoing = false)))
        }

        DirectionChoice.entries.forEach { composeTestRule.onNodeWithTag(it.tag).assertIsNotSelected() }
    }

    /**
     * **The defect this control replaced, pinned.** The two ticks lost their words below a 300dp filter, a
     * rule the toolbar's fold never counted, so a toolbar with every chip still wearing its word drew two
     * identical ticks. The segments' words are the whole segment, so they are there at the narrowest toolbar
     * the fold goes down to, where every chip beside them is a bare glyph.
     */
    @Test
    fun `the direction words are there at the narrowest toolbar`() {
        assertEquals(ToolbarFold.SEGMENTS, ToolbarFold.forWidth(900.dp), "this test means nothing above the last fold")
        composeTestRule.setContent {
            Box(modifier = Modifier.requiredWidth(900.dp)) {
                Toolbar(onClearAll = { })
            }
        }

        composeTestRule.onNodeWithTag("toolbar-filter-both").assertTextEquals("Both")
        composeTestRule.onNodeWithTag("toolbar-filter-in").assertTextEquals("IN")
        composeTestRule.onNodeWithTag("toolbar-filter-out").assertTextEquals("OUT")
    }

    @Test
    fun `the chip is there only while something is followed`() {
        composeTestRule.setContent { ToolbarFilter(query = FilterQuery()) }

        composeTestRule.onNodeWithTag("following-chip").assertDoesNotExist()
    }

    @Test
    fun `a followed trace is named beside the regex, not instead of it`() {
        composeTestRule.setContent {
            ToolbarFilter(
                query =
                    FilterQuery(
                        global = MessageFilters.Global(regex = "35=8"),
                        followingLabel = "RFQ-A1",
                        followingSessionCount = 4,
                        followingMessageCount = 14,
                    ),
            )
        }

        composeTestRule.onNodeWithTag("following-chip").assertExists()
        composeTestRule.onNodeWithTag("toolbar-filter-regex").assertExists()
    }

    /** Each of the four things that narrows a pane counts, because each of them has to be visible. */
    @Test
    fun `isNarrowing counts the regex, either direction and the followed trace`() {
        assertEquals(false, FilterQuery().isNarrowing, "a fresh filter narrows nothing")
        assertEquals(true, FilterQuery(global = MessageFilters.Global(regex = "35=8")).isNarrowing)
        assertEquals(true, FilterQuery(global = MessageFilters.Global(showIncoming = false)).isNarrowing)
        assertEquals(true, FilterQuery(global = MessageFilters.Global(showOutgoing = false)).isNarrowing)
        assertEquals(true, FilterQuery(followingLabel = "RFQ-A1").isNarrowing)
    }
}
