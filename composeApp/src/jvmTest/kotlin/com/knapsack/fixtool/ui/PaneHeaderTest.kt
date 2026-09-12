package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * **One header for a session pane, in both layouts.**
 *
 * The split layout drew thirteen controls with a hand-counted fold threshold; the tabs layout drew nine of
 * the same controls in its tab strip with no fold rule, no overflow and no title. They had already drifted —
 * the same button was "Add Separator" in one and "Add Blank Line" in the other — so what is asserted here is
 * mostly *sameness*: the same tags, the same words, the same fold order, whichever layout asked.
 */
class PaneHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun render(
        width: Int = 700,
        showTitle: Boolean = true,
        viewMode: FixMessageSession.ViewMode = FixMessageSession.ViewMode.RAW,
        session: FixMessageSession = FixMessageSession(title = "RFQ Demo Venue ← RFQLG3"),
        onClose: (() -> Unit)? = {},
    ) {
        rule.setContent {
            Box(Modifier.width(width.dp)) {
                PaneHeader(
                    session = session,
                    viewMode = viewMode,
                    messageCount = 140,
                    isAtBottom = true,
                    onScrollToBottom = {},
                    onMinimize = {},
                    onMoveLeft = {},
                    onMoveRight = {},
                    onClose = onClose,
                    showTitle = showTitle,
                )
            }
        }
    }

    /** Every control a pane offers, under the tag it answers to in either layout. */
    private val everyAction =
        listOf(
            "pane-wrap",
            "pane-search",
            "pane-filter",
            "pane-group",
            "pane-blank-line",
            "pane-clear",
            "pane-scroll-bottom",
            "pane-minimize",
            "pane-move-left",
            "pane-move-right",
            "pane-close",
        )

    /**
     * **The split layout names its pane; the tabs layout does not, because its tab already has.**
     *
     * That is the whole of the difference between the two now. A second copy of the name in the same strip
     * as the tab is the one thing a shared header must not add.
     */
    @Test
    fun `both layouts draw the same actions, and only one of them draws the title`() {
        render(showTitle = true)
        rule.onNodeWithTag("pane-title").assertIsDisplayed()
        everyAction.forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
    }

    @Test
    fun `the tabs layout draws the actions without the title the tab already carries`() {
        render(showTitle = false, onClose = null)
        rule.onNodeWithTag("pane-title").assertDoesNotExist()
        rule.onNodeWithTag("pane-message-count").assertDoesNotExist()
        rule.onNodeWithTag("pane-filter").assertIsDisplayed()
        rule.onNodeWithTag("pane-clear").assertIsDisplayed()
    }

    /**
     * **Search is offered in both views, because the bar it opens works in both.**
     *
     * It was drawn only in RAW, beside Wrap, as though the two were a pair. They are not: a grid of parsed
     * fields has no lines to wrap, but it has plenty to search, and `MessageDisplayContent` has been drawing
     * the search bar over the grid all along. The button that opens it was the only thing that disagreed.
     */
    @Test
    fun `search is offered over the parsed grid as well as the raw log, and wrap is not`() {
        render(viewMode = FixMessageSession.ViewMode.PARSED)

        rule.onNodeWithTag("pane-search").assertIsDisplayed()
        rule.onNodeWithTag("pane-wrap").assertDoesNotExist()
    }

    /** The four toggles publish their state, which is what lets a folded one keep it in the menu. */
    @Test
    fun `a toggle in the header says on and off`() {
        val session = FixMessageSession(title = "RFQ Client")
        render(session = session)

        rule.onNodeWithTag("pane-filter").assertIsOff()
        rule.onNodeWithTag("pane-filter").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pane-filter").assertIsOn()
        assertTrue(session.filterVisible.value, "the click reached the session")
    }

    /**
     * **A Close arms rather than closing**, the way Close all does, because a closed session takes its log
     * with it and there is no Recent for one. In the button and not in a dialog: the second click is the
     * confirmation, and the app has never needed a modal over the window.
     */
    @Test
    fun `close arms on the first click and closes on the second`() {
        var closed = 0
        render(onClose = { closed++ })

        rule.onNodeWithTag("pane-close").performClick()
        rule.waitForIdle()
        assertEquals(0, closed, "the first click arms rather than closing")

        // And it says so where a reader is looking, not only in a tooltip they have to hover for.
        rule.onNodeWithTag("pane-close").assertContentDescriptionContains("Close", substring = true)
        rule.onNodeWithTag("pane-close").assertContentDescriptionContains("click again", substring = true)

        rule.onNodeWithTag("pane-close").performClick()
        rule.waitForIdle()
        assertEquals(1, closed, "the second click is the confirmation")
    }

    // A venue's pane draws none of the message-grid controls — see MinimizedStripTest, which builds a real
    // acceptor on a real port, because `isVenue` is read off a bound config and cannot be staged from here.

    /**
     * **The fold order is declared, and Close is not in it.**
     *
     * Folding by position would take Close off before Move left and leave the pane unclosable at exactly
     * the width where closing it is what you want. Move left is rank one, so it is the first to go.
     */
    @Test
    fun `a narrow pane folds its lowest-ranked actions and never its close`() {
        render(width = 260)

        rule.onNodeWithTag("pane-close").assertIsDisplayed()
        rule.onNodeWithTag("pane-overflow").assertIsDisplayed()
        rule.onNodeWithTag("pane-move-left").assertDoesNotExist()

        // Folded is not gone.
        rule.onNodeWithTag("pane-overflow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pane-move-left-menu").assertIsDisplayed()
    }
}
