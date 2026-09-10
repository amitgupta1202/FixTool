package com.knapsack.fixtool.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The filter row cannot go silent, and these are the ways it could.**
 *
 * The regex used to sit in the middle of the toolbar, narrowing every pane from a field nobody had
 * looked at since they typed in it, and the direction boxes beside it were worse: two ticks that hide
 * half the traffic and say nothing at all when they are off. So the row is not merely *available*, it is
 * on screen whenever it is narrowing anything, and the only way to put it away clears it first.
 *
 * The visibility rule itself is [FilterQuery.shown], which the app reads in exactly one place, so these
 * ask it the same way the window does rather than re-deriving it.
 */
class FilterRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** The window's own `if`, over the same rule, so a test cannot pass on a condition the app does not use. */
    @Composable
    private fun Harness(
        query: FilterQuery,
        askedFor: Boolean,
        onRegexChange: (String) -> Unit = {},
        onIncomingChange: (Boolean) -> Unit = {},
        onOutgoingChange: (Boolean) -> Unit = {},
        onUnfollow: () -> Unit = {},
        onHide: () -> Unit = {},
    ) {
        if (query.shown(askedFor)) {
            FilterRow(
                query = query,
                onRegexChange = onRegexChange,
                onIncomingChange = onIncomingChange,
                onOutgoingChange = onOutgoingChange,
                onUnfollow = onUnfollow,
                onHide = onHide,
            )
        }
    }

    @Test
    fun `the row is not there until it is asked for`() {
        composeTestRule.setContent { Harness(query = FilterQuery(), askedFor = false) }

        composeTestRule.onNodeWithTag("filter-row").assertDoesNotExist()
    }

    @Test
    fun `asking for it opens it`() {
        composeTestRule.setContent { Harness(query = FilterQuery(), askedFor = true) }

        composeTestRule.onNodeWithTag("filter-row").assertExists()
        composeTestRule.onNodeWithTag("filter-row-regex").assertExists()
    }

    @Test
    fun `a regex nobody asked to see holds the row open`() {
        val query = FilterQuery(global = MessageFilters.Global(regex = "35=8"))
        composeTestRule.setContent { Harness(query = query, askedFor = false) }

        composeTestRule.onNodeWithTag("filter-row").assertExists()
        assertEquals(listOf("regex"), query.narrowing)
    }

    @Test
    fun `a followed trace holds the row open`() {
        val query = FilterQuery(followingLabel = "RFQ-A1")
        composeTestRule.setContent { Harness(query = query, askedFor = false) }

        composeTestRule.onNodeWithTag("filter-row").assertExists()
        composeTestRule.onNodeWithTag("following-chip").assertExists()
        assertEquals(listOf("following RFQ-A1"), query.narrowing)
    }

    @Test
    fun `an unticked direction holds the row open, and says which way`() {
        val query = FilterQuery(global = MessageFilters.Global(showOutgoing = false))
        composeTestRule.setContent { Harness(query = query, askedFor = false) }

        composeTestRule.onNodeWithTag("filter-row").assertExists()
        assertEquals(listOf("incoming only"), query.narrowing)
        assertTrue(FilterQuery(global = MessageFilters.Global(showIncoming = false)).isNarrowing)
    }

    @Test
    fun `the cross clears everything before it closes`() {
        var regex: String? = null
        var incoming: Boolean? = null
        var outgoing: Boolean? = null
        var unfollowed = 0
        var hidden = 0
        composeTestRule.setContent {
            Harness(
                query =
                    FilterQuery(
                        global = MessageFilters.Global(regex = "35=8", showIncoming = false),
                        followingLabel = "RFQ-A1",
                    ),
                askedFor = true,
                onRegexChange = { regex = it },
                onIncomingChange = { incoming = it },
                onOutgoingChange = { outgoing = it },
                onUnfollow = { unfollowed++ },
                onHide = { hidden++ },
            )
        }

        composeTestRule.onNodeWithTag("filter-row-close").performClick()
        composeTestRule.waitForIdle()

        assertEquals("", regex, "the regex is emptied")
        assertEquals(true, incoming, "In is ticked back on")
        assertEquals(true, outgoing, "Out is ticked back on")
        assertEquals(1, unfollowed, "and the trace is let go")
        assertEquals(1, hidden, "only then does the row close")
    }

    @Test
    fun `In and Out report the direction they were clicked to`() {
        var incoming: Boolean? = null
        var outgoing: Boolean? = null
        composeTestRule.setContent {
            Harness(
                query = FilterQuery(),
                askedFor = true,
                onIncomingChange = { incoming = it },
                onOutgoingChange = { outgoing = it },
            )
        }

        composeTestRule.onNodeWithTag("filter-row-in").performClick()
        composeTestRule.onNodeWithTag("filter-row-out").performClick()
        composeTestRule.waitForIdle()

        assertEquals(false, incoming, "a ticked In unticks on one click, not two")
        assertEquals(false, outgoing, "and so does Out")
    }

    @Test
    fun `the funnel says whether the row is open`() {
        var toggles = 0
        composeTestRule.setContent {
            PaneControls(query = FilterQuery(), shown = false, onToggle = { toggles++ })
        }

        composeTestRule.onNodeWithTag("filter-row-toggle").assertIsNotSelected()
        composeTestRule.onNodeWithTag("filter-row-toggle").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, toggles, "with nothing narrowing, the funnel is an ordinary toggle")
    }

    @Test
    fun `an open funnel reads as pressed and names its shortcut`() {
        composeTestRule.setContent { PaneControls(query = FilterQuery(), shown = true) }

        composeTestRule.onNodeWithTag("filter-row-toggle").assertIsSelected()
        composeTestRule
            .onNodeWithTag("filter-row-toggle")
            .assertContentDescriptionContains("Filter row · $FILTER_ROW_SHORTCUT")
    }

    @Test
    fun `a narrowing funnel says why, and refuses to hide the row`() {
        var toggles = 0
        val query =
            FilterQuery(global = MessageFilters.Global(regex = "35=8"), followingLabel = "RFQ 7f3a")
        composeTestRule.setContent {
            PaneControls(query = query, shown = true, onToggle = { toggles++ })
        }

        composeTestRule
            .onNodeWithTag("filter-row-toggle")
            .assertContentDescriptionContains("Filter row · narrowing: regex, following RFQ 7f3a")

        composeTestRule.onNodeWithTag("filter-row-toggle").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, toggles, "a row that is narrowing something is closed by its own ✕, which clears first")
        assertTrue(query.shown(false), "and it stays on screen whatever the flag says")
    }

    /** The funnel as the pane bar draws it, since that is the only place it exists. */
    @Composable
    private fun PaneControls(
        query: FilterQuery,
        shown: Boolean,
        onToggle: () -> Unit = {},
    ) {
        PaneViewControls(
            viewMode = ViewMode.TABS,
            onViewModeChange = { },
            sessionViewMode = FixMessageSession.ViewMode.PARSED,
            onToggleGridView = { },
            hideProtocolTags = false,
            onToggleHideProtocolTags = { },
            groupByConversation = false,
            onToggleGroupByConversation = { },
            filterQuery = query,
            filterRowShown = shown,
            onToggleFilterRow = onToggle,
        )
    }
}
