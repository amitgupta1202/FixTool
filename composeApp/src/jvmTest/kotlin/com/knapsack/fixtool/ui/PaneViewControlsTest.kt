package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The pane bar's view controls: the layout segments, the View menu, and what each row calls.**
 *
 * These controls are the only door to the four settings now. The toolbar's cycling Layout button and its
 * three all-sessions toggles are gone, so a row that stops calling its toggle here is a setting nobody can
 * reach at all, and these are the tests that stop that happening.
 *
 * The radio pair gets the most attention because it is the one place a wrong call is worse than no call:
 * [PaneViewControls]'s `onToggleGridView` is a *flip* of the all-sessions mode, so picking the mode that
 * is already in force has to call nothing.
 */
class PaneViewControlsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Wide enough that nothing folds, so a test about the segments is about the segments. */
    private val wideBar = 600.dp

    /**
     * Narrower than [paneBarFullWidth] for a bar with no tabs and no per-session buttons, which is 186dp.
     * Asserted rather than assumed in the fold test below, so the premise cannot rot.
     */
    private val narrowBar = 180.dp

    @Test
    fun `the three layouts are three segments, and only the one in force reads as pressed`() {
        renderControls(viewMode = ViewMode.SPLIT_HORIZONTAL)

        composeTestRule.onNodeWithTag(ViewMode.TABS.testTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_HORIZONTAL.testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertIsNotSelected()
    }

    @Test
    fun `clicking a segment asks for that layout and no other`() {
        val chosen = mutableListOf<ViewMode>()
        renderControls(viewMode = ViewMode.TABS, onViewModeChange = { chosen += it })

        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ViewMode.SPLIT_VERTICAL), chosen, "a segment selects its own mode, once")
    }

    @Test
    fun `View opens on all four settings at once`() {
        renderControls()

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-parsed").assertExists()
        composeTestRule.onNodeWithTag("view-raw").assertExists()
        composeTestRule.onNodeWithTag("view-hide-tags").assertExists()
        composeTestRule.onNodeWithTag("view-group").assertExists()
    }

    @Test
    fun `the row-mode pair marks whichever mode every pane is drawing`() {
        renderControls(sessionViewMode = FixMessageSession.ViewMode.PARSED)
        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-parsed").assertIsSelected()
        composeTestRule.onNodeWithTag("view-raw").assertIsNotSelected()
    }

    @Test
    fun `the row-mode pair marks RAW when RAW is what the panes are drawing`() {
        renderControls(sessionViewMode = FixMessageSession.ViewMode.RAW)
        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-raw").assertIsSelected()
        composeTestRule.onNodeWithTag("view-parsed").assertIsNotSelected()
    }

    @Test
    fun `picking the other row mode flips every pane, and the menu closes on the answer`() {
        var flips = 0
        renderControls(sessionViewMode = FixMessageSession.ViewMode.PARSED, onToggleGridView = { flips++ })

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("view-raw").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, flips, "picking Raw rows from Parsed should flip the all-sessions mode exactly once")
        composeTestRule.onNodeWithTag("view-parsed").assertDoesNotExist()
    }

    @Test
    fun `picking the row mode already in force calls nothing`() {
        var flips = 0
        renderControls(sessionViewMode = FixMessageSession.ViewMode.PARSED, onToggleGridView = { flips++ })

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("view-parsed").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            flips,
            "the callback is a flip, so calling it on the mode already in force would flip the view away from it",
        )
    }

    @Test
    fun `Hide protocol tags toggles its setting and leaves the menu open`() {
        var toggles = 0
        renderControls(onToggleHideProtocolTags = { toggles++ })

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("view-hide-tags").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, toggles, "the row should call its own toggle once")
        composeTestRule.onNodeWithTag("view-group").assertExists()
    }

    @Test
    fun `Group by conversation toggles its setting and leaves the menu open`() {
        var toggles = 0
        renderControls(onToggleGroupByConversation = { toggles++ })

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("view-group").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, toggles, "the row should call its own toggle once")
        composeTestRule.onNodeWithTag("view-hide-tags").assertExists()
    }

    /** The split layouts get the same controls through their own bar, which is the point of the slot. */
    @Test
    fun `the split layouts' bar carries the same controls`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(wideBar)) {
                SplitViewBar { folded ->
                    PaneViewControls(
                        viewMode = ViewMode.SPLIT_VERTICAL,
                        onViewModeChange = { },
                        sessionViewMode = FixMessageSession.ViewMode.PARSED,
                        onToggleGridView = { },
                        hideProtocolTags = true,
                        onToggleHideProtocolTags = { },
                        groupByConversation = false,
                        onToggleGroupByConversation = { },
                        folded = folded,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("split-view-bar").assertExists()
        composeTestRule.onNodeWithTag("view-menu").assertExists()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertIsSelected()
    }

    /**
     * The fold rule, from the wide side: with room for everything the segments are drawn, so the narrow
     * test below is pinned against a threshold that does something rather than one that folds always.
     */
    @Test
    fun `a bar with room for them draws the three segments`() {
        renderSplitBar(width = wideBar, viewMode = ViewMode.TABS)

        composeTestRule.onNodeWithTag(ViewMode.TABS.testTag).assertExists()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_HORIZONTAL.testTag).assertExists()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertExists()
    }

    /**
     * And from the narrow side. The segments go first and `View ▾` stays, because the layout can be three
     * more rows in a menu and nothing here can be reached at all once it has been clipped away.
     */
    @Test
    fun `a bar too narrow for the segments folds them into the View menu and keeps View`() {
        assertTrue(
            paneBarFullWidth(actionButtons = 0, showsTabs = false) > narrowBar,
            "this test only means anything below the fold threshold, and $narrowBar was not below it",
        )

        val chosen = mutableListOf<ViewMode>()
        renderSplitBar(width = narrowBar, viewMode = ViewMode.TABS, onViewModeChange = { chosen += it })

        composeTestRule.onNodeWithTag(ViewMode.TABS.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_HORIZONTAL.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag("view-menu").assertExists()

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-layout-tabs").assertIsSelected()
        composeTestRule.onNodeWithTag("view-layout-split-horizontal").assertIsNotSelected()
        composeTestRule.onNodeWithTag("view-layout-split-vertical").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ViewMode.SPLIT_VERTICAL), chosen, "a folded layout row selects its own mode, once")
        composeTestRule.onNodeWithTag("view-layout-tabs").assertDoesNotExist()
    }

    /**
     * The regression the weighted, scrolling tab strip guards: the tabs are measured last, so however many
     * panes are open the view controls keep their natural width instead of being pushed off the right edge.
     */
    @Test
    fun `many open panes cannot push the View menu off the tab bar`() {
        val sessions = (1..8).map { FixMessageSession(id = "s$it", title = "Session $it") }
        composeTestRule.setContent {
            Box(modifier = Modifier.width(420.dp)) {
                TabBar(
                    sessions = sessions,
                    activeSession = sessions.first(),
                    viewMode = FixMessageSession.ViewMode.PARSED,
                    onTabClick = { },
                    onCloseTab = { },
                    onToggleWrapText = { },
                    onConnect = { },
                    onDisconnect = { },
                    viewControls = { folded ->
                        PaneViewControls(
                            viewMode = ViewMode.TABS,
                            onViewModeChange = { },
                            sessionViewMode = FixMessageSession.ViewMode.PARSED,
                            onToggleGridView = { },
                            hideProtocolTags = true,
                            onToggleHideProtocolTags = { },
                            groupByConversation = false,
                            onToggleGroupByConversation = { },
                            folded = folded,
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("view-menu").assertExists()
    }

    /** The controls on their own, at a width nothing folds at. */
    private fun renderControls(
        viewMode: ViewMode = ViewMode.TABS,
        onViewModeChange: (ViewMode) -> Unit = { },
        sessionViewMode: FixMessageSession.ViewMode = FixMessageSession.ViewMode.PARSED,
        onToggleGridView: () -> Unit = { },
        onToggleHideProtocolTags: () -> Unit = { },
        onToggleGroupByConversation: () -> Unit = { },
    ) {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(wideBar)) {
                PaneViewControls(
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                    sessionViewMode = sessionViewMode,
                    onToggleGridView = onToggleGridView,
                    hideProtocolTags = true,
                    onToggleHideProtocolTags = onToggleHideProtocolTags,
                    groupByConversation = false,
                    onToggleGroupByConversation = onToggleGroupByConversation,
                )
            }
        }
    }

    /** The split layouts' bar at a chosen width, which is what decides the fold. */
    private fun renderSplitBar(
        width: androidx.compose.ui.unit.Dp,
        viewMode: ViewMode,
        onViewModeChange: (ViewMode) -> Unit = { },
    ) {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(width)) {
                SplitViewBar { folded ->
                    PaneViewControls(
                        viewMode = viewMode,
                        onViewModeChange = onViewModeChange,
                        sessionViewMode = FixMessageSession.ViewMode.PARSED,
                        onToggleGridView = { },
                        hideProtocolTags = true,
                        onToggleHideProtocolTags = { },
                        groupByConversation = false,
                        onToggleGroupByConversation = { },
                        folded = folded,
                    )
                }
            }
        }
    }
}
