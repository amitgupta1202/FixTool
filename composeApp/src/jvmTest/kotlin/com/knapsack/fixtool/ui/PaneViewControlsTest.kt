package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * **The toolbar's view controls: the layout segments, the View menu, and what each row calls.**
 *
 * These controls are the only door to the four settings. The cycling Layout button and the three
 * all-sessions toggles it sat beside are both gone, so a row that stops calling its toggle here is a
 * setting nobody can reach at all, and these are the tests that stop that happening.
 *
 * They are rendered through [Toolbar] rather than on their own, because the toolbar is what decides
 * whether the segments are drawn at all: it measures its own width and folds them into `View ▾` when
 * there is no room. A test that called [PaneViewControls] directly would be testing a `folded` flag it
 * had set itself.
 *
 * The radio pair gets the most attention because it is the one place a wrong call is worse than no call:
 * [PaneViewControls]'s `onToggleGridView` is a *flip* of the all-sessions mode, so picking the mode that
 * is already in force has to call nothing.
 */
class PaneViewControlsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** Wide enough that nothing folds, so a test about the segments is about the segments. */
    private val wideToolbar = 1700.dp

    /** Narrow enough that the segments have gone into the menu. Asserted, not assumed, in the fold test. */
    private val narrowToolbar = 900.dp

    @Test
    fun `the three layouts are three segments, and only the one in force reads as pressed`() {
        renderToolbar(viewMode = ViewMode.SPLIT_HORIZONTAL)

        composeTestRule.onNodeWithTag(ViewMode.TABS.testTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_HORIZONTAL.testTag).assertIsSelected()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertIsNotSelected()
    }

    @Test
    fun `clicking a segment asks for that layout and no other`() {
        val chosen = mutableListOf<ViewMode>()
        renderToolbar(viewMode = ViewMode.TABS, onViewModeChange = { chosen += it })

        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ViewMode.SPLIT_VERTICAL), chosen, "a segment selects its own mode, once")
    }

    @Test
    fun `View opens on all four settings at once`() {
        renderToolbar()

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-parsed").assertExists()
        composeTestRule.onNodeWithTag("view-raw").assertExists()
        composeTestRule.onNodeWithTag("view-hide-tags").assertExists()
        composeTestRule.onNodeWithTag("view-group").assertExists()
    }

    @Test
    fun `the row-mode pair marks whichever mode every pane is drawing`() {
        renderToolbar(sessionViewMode = FixMessageSession.ViewMode.PARSED)
        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-parsed").assertIsSelected()
        composeTestRule.onNodeWithTag("view-raw").assertIsNotSelected()
    }

    @Test
    fun `the row-mode pair marks RAW when RAW is what the panes are drawing`() {
        renderToolbar(sessionViewMode = FixMessageSession.ViewMode.RAW)
        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("view-raw").assertIsSelected()
        composeTestRule.onNodeWithTag("view-parsed").assertIsNotSelected()
    }

    @Test
    fun `picking the other row mode flips every pane, and the menu closes on the answer`() {
        var flips = 0
        renderToolbar(sessionViewMode = FixMessageSession.ViewMode.PARSED, onToggleGridView = { flips++ })

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
        renderToolbar(sessionViewMode = FixMessageSession.ViewMode.PARSED, onToggleGridView = { flips++ })

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
        renderToolbar(onToggleHideProtocolTags = { toggles++ })

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
        renderToolbar(onToggleGroupByConversation = { toggles++ })

        composeTestRule.onNodeWithTag("view-menu").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("view-group").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, toggles, "the row should call its own toggle once")
        composeTestRule.onNodeWithTag("view-hide-tags").assertExists()
    }

    /**
     * The fold rule, from the wide side: with room for everything the segments are drawn, so the narrow
     * test below is pinned against a threshold that does something rather than one that folds always.
     */
    @Test
    fun `a toolbar with room for them draws the three segments`() {
        assertEquals(ToolbarFold.NONE, ToolbarFold.forWidth(wideToolbar), "this test means nothing above the fold")

        renderToolbar(width = wideToolbar, viewMode = ViewMode.TABS)

        composeTestRule.onNodeWithTag(ViewMode.TABS.testTag).assertExists()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_HORIZONTAL.testTag).assertExists()
        composeTestRule.onNodeWithTag(ViewMode.SPLIT_VERTICAL.testTag).assertExists()
    }

    /**
     * And from the narrow side. The segments go before `View ▾` does, because the layout can be three more
     * rows in a menu and nothing here can be reached at all once it has been clipped away.
     */
    @Test
    fun `a toolbar too narrow for the segments folds them into the View menu and keeps View`() {
        assertEquals(
            ToolbarFold.SEGMENTS,
            ToolbarFold.forWidth(narrowToolbar),
            "this test only means anything below the segments' threshold, and $narrowToolbar was not below it",
        )

        val chosen = mutableListOf<ViewMode>()
        renderToolbar(width = narrowToolbar, viewMode = ViewMode.TABS, onViewModeChange = { chosen += it })

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
     * The regression the weighted, scrolling tab strip guards, which outlived the view controls it was
     * first written for: the tabs are measured last, so however many panes are open the per-session
     * buttons keep their natural width instead of being pushed off the right edge.
     */
    @Test
    fun `many open panes cannot push the per-session buttons off the tab bar`() {
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
                )
            }
        }

        composeTestRule.onNodeWithTag("pane-minimize").assertExists()
    }

    /** The controls as the window draws them: in the toolbar, at a width that decides the fold. */
    @Suppress("LongParameterList")
    private fun renderToolbar(
        width: Dp = wideToolbar,
        viewMode: ViewMode = ViewMode.TABS,
        onViewModeChange: (ViewMode) -> Unit = { },
        sessionViewMode: FixMessageSession.ViewMode = FixMessageSession.ViewMode.PARSED,
        onToggleGridView: () -> Unit = { },
        onToggleHideProtocolTags: () -> Unit = { },
        onToggleGroupByConversation: () -> Unit = { },
    ) {
        composeTestRule.setContent {
            // At half density, so a 1700dp toolbar is 850px and fits the test window's 1024. The view controls
            // sit at the toolbar's right edge, and a click the test makes has to land inside the window: they
            // used to be drawn short of the edge only because the filter's spacers left the row unfilled.
            CompositionLocalProvider(LocalDensity provides Density(0.5f)) {
                Box(modifier = Modifier.requiredWidth(width)) {
                    Toolbar(
                        viewControls = { folded ->
                            PaneViewControls(
                                viewMode = viewMode,
                                onViewModeChange = onViewModeChange,
                                sessionViewMode = sessionViewMode,
                                onToggleGridView = onToggleGridView,
                                hideProtocolTags = true,
                                onToggleHideProtocolTags = onToggleHideProtocolTags,
                                groupByConversation = false,
                                onToggleGroupByConversation = onToggleGroupByConversation,
                                folded = folded,
                            )
                        },
                    )
                }
            }
        }
    }
}
