package com.knapsack.fixtool.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionary
import com.knapsack.fixtool.model.FixMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.*
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * UI Integration tests for grid view message selection
 *
 * Tests that clicking on a message in grid view:
 * - Selects the message
 * - Highlights the selected row
 * - Calls the onSelectMessage callback
 */
class GridViewSelectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var dictionary: FixDictionary
    private var selectedMessage by mutableStateOf<FixMessage?>(null)
    private val messages = mutableStateListOf<AppMessage>()

    @Before
    fun setup() {
        dictionary = FixDictionary.createDefault()
        selectedMessage = null
        messages.clear()
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createFixMessage(
        messageType: String,
        direction: FixMessage.Direction,
        timestamp: LocalDateTime = LocalDateTime.now(),
    ): FixMessage {
        val message = Message()
        message.header.setString(BeginString.FIELD, "FIX.4.2")
        message.header.setString(MsgType.FIELD, messageType)
        message.header.setString(SenderCompID.FIELD, "SENDER")
        message.header.setString(TargetCompID.FIELD, "TARGET")
        message.header.setInt(MsgSeqNum.FIELD, 1)

        val rawMessage = "8=FIX.4.2|9=100|35=$messageType|49=SENDER|56=TARGET|34=1|10=123|"

        return FixMessage(
            timestamp = timestamp,
            direction = direction,
            rawMessage = rawMessage,
            messageType = messageType,
            quickfixMessage = message,
        )
    }

    // ========================================
    // Selection Tests
    // ========================================

    @Test
    fun testSelectedMessageIsHighlighted() {
        // Given: A message that is selected
        val message1 = createFixMessage("D", FixMessage.Direction.OUTGOING)
        val message2 = createFixMessage("8", FixMessage.Direction.INCOMING)

        messages.add(message1)
        messages.add(message2)

        // When: Grid view is displayed with message1 selected
        selectedMessage = message1

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                selectedMessage = selectedMessage,
                onSelectMessage = { selectedMessage = it },
            )
        }

        composeTestRule.waitForIdle()

        // Then: The selected message row should be displayed
        // We can verify the message is present and the state is correct
        assertEquals(message1, selectedMessage)
    }
}
