package com.knapsack.fixtool.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession.ViewMode
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.BeginString
import quickfix.field.MsgType
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Search in pane, in the parsed view as well as the raw one.**
 *
 * The pane's Search button and ⌘F stopped being RAW-only when the pane header was rebuilt, on the claim that
 * the bar they open was already drawn over the parsed grid. It was not: the RAW branch drew it and the PARSED
 * branch did not, so in the default view the button went pressed over a grid that showed no bar at all.
 */
class PaneSearchTest {
    @get:Rule
    val rule = createComposeRule()

    private fun message(
        type: String,
        clOrdId: String,
    ): FixMessage {
        val raw = "8=FIX.4.4|9=40|35=$type|11=$clOrdId|10=000|"
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.OUTGOING,
            rawMessage = raw,
            messageType = type,
            quickfixMessage =
                Message().apply {
                    header.setString(BeginString.FIELD, "FIX.4.4")
                    header.setString(MsgType.FIELD, type)
                },
        )
    }

    @Test
    fun `the parsed view draws the pane's search bar, and a match there is selected`() {
        val messages = listOf(message("D", "ORD-1"), message("D", "ORD-2"), message("F", "ORD-3"))
        var selected by mutableStateOf<FixMessage?>(null)

        rule.setContent {
            FixMessageDisplay(
                messages = messages,
                viewMode = ViewMode.PARSED,
                dictionary = FixDictionary.createDefault(),
                selectedMessage = selected,
                onSelectMessage = { selected = it },
                searchVisible = true,
            )
        }

        rule.onNodeWithTag("pane-search-input").assertIsDisplayed().performTextInput("ORD-2")
        rule.waitForIdle()

        // Above the grid rather than floated over it, where it covered the headings and the first row.
        val bar = rule.onNodeWithTag("pane-search-input").getUnclippedBoundsInRoot()
        val headings = rule.onNodeWithTag("grid-header").getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= headings.top, "the search bar sits above the column headings, not on them")

        rule.onNodeWithText("1 of 1").assertIsDisplayed()
        assertEquals(messages[1], selected, "the parsed grid scrolls to its selection, so a match is selected")
    }

    /**
     * **Clear empties the box; Close closes the bar; they are not one glyph twice.** They were the same × side
     * by side, with Clear the rightmost — the edge a hand reaches for to close a bar — so aiming at one hit the
     * other. Clear sits by the text it clears now, and Close keeps the edge.
     */
    @Test
    fun `clear sits by the field and empties it, and close keeps the bar's edge and closes it`() {
        var closed = 0
        rule.setContent {
            FixMessageDisplay(
                messages = listOf(message("D", "ORD-1")),
                viewMode = ViewMode.PARSED,
                dictionary = FixDictionary.createDefault(),
                searchVisible = true,
                onToggleSearch = { closed++ },
            )
        }
        rule.onNodeWithTag("pane-search-input").performTextInput("ORD")
        rule.waitForIdle()

        val clear = rule.onNodeWithTag("pane-search-input-clear").getUnclippedBoundsInRoot()
        val close = rule.onNodeWithTag("pane-search-close").getUnclippedBoundsInRoot()
        assertTrue(clear.right <= close.left, "clear is left of close, and close is the bar's last control")

        rule.onNodeWithTag("pane-search-input-clear").performClick()
        rule.waitForIdle()
        rule
            .onNodeWithTag("pane-search-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(0, closed, "clearing the box does not close the bar")

        rule.onNodeWithTag("pane-search-close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `with the bar closed the parsed view draws no search bar`() {
        rule.setContent {
            FixMessageDisplay(
                messages = listOf(message("D", "ORD-1")),
                viewMode = ViewMode.PARSED,
                dictionary = FixDictionary.createDefault(),
            )
        }

        rule.onNodeWithTag("pane-search-input").assertDoesNotExist()
    }
}
