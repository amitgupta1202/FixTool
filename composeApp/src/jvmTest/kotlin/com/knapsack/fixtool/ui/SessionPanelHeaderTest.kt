package com.knapsack.fixtool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.knapsack.fixtool.model.FixMessageSession
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **A pane header at ten-pane width.**
 *
 * Ten panes in the top-to-bottom grid leave each one about 190dp wide, and the header used to answer
 * that by wrapping its title onto three lines, stacking the message count a digit per line, and
 * measuring every button after the title at zero width, so a pane that narrow could not be minimized,
 * moved or closed at all. These pin the three halves of the fix: one title line, one count line, and
 * an overflow menu that appears only when the full button set does not fit.
 */
class SessionPanelHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    private fun renderHeader(
        width: Dp,
        title: String,
        messageCount: Int,
    ) {
        val session = FixMessageSession(title = title)
        rule.setContent {
            Box(modifier = Modifier.width(width)) {
                SessionPanelHeader(
                    session = session,
                    viewMode = FixMessageSession.ViewMode.RAW,
                    messageCount = messageCount,
                    isAtBottom = true,
                    onScrollToBottom = {},
                    onMinimize = {},
                    onConnect = {},
                    onDisconnect = {},
                    onMoveLeft = {},
                    onMoveRight = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `a 190dp header keeps one title line, its count and its close button, and folds the rest into a menu`() {
        val title = "RFQ Demo Venue ← RFQLG3"
        renderHeader(width = 190.dp, title = title, messageCount = 140)

        // Two lines of 12sp would clear 30dp, so anything under 22dp is the single line asked for.
        val titleHeight = rule.onNodeWithTag("pane-title").getUnclippedBoundsInRoot().height
        assertTrue(titleHeight < 22.dp, "the title measured $titleHeight, which is more than one line")

        // The tail is the half that says which client this is, so it has to survive the cut. The desktop
        // engine only ellipsizes the end, which is why the header measures and cuts the string itself.
        val titleNode = rule.onNodeWithTag("pane-title").fetchSemanticsNode()
        val shown = titleNode.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(shown.contains("…"), "the title read \"$shown\", which was never shortened")
        assertTrue(shown.endsWith("3"), "the title read \"$shown\", so the tail of \"$title\" was cut off")
        assertTrue(shown.length < title.length, "the title read \"$shown\", which is not shorter than \"$title\"")
        rule.onNodeWithTag("pane-message-count").assertIsDisplayed().assertTextEquals("140").assertWidthIsAtLeast(8.dp)
        rule.onNodeWithTag("pane-overflow").assertIsDisplayed()

        // **Close is the one action a pane header may never lose**, and it is still its own button here.
        // Minimize is not: the fold order is declared now, and it is third in it, after the two moves.
        // The header used to hold Minimize out of the fold by hand — one more thing the two layouts each
        // decided for themselves.
        rule.onNodeWithTag("pane-close").assertIsDisplayed()
        rule.onNodeWithTag("pane-minimize").assertDoesNotExist()

        // Folded is not gone: the ⋯ carries the word the button would have shown.
        rule.onNodeWithTag("pane-overflow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pane-minimize-menu").assertIsDisplayed()
    }

    @Test
    fun `a 700dp header draws every action as its own button, with no overflow menu`() {
        renderHeader(width = 700.dp, title = "RFQ Demo Venue ← RFQLG3", messageCount = 140)

        rule.onNodeWithTag("pane-overflow").assertDoesNotExist()
        rule.onNodeWithTag("pane-filter").assertIsDisplayed()
        rule.onNodeWithTag("pane-move-right").assertIsDisplayed()
    }
}
