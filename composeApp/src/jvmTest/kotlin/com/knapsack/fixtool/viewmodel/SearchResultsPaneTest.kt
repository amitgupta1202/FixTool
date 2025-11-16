package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import quickfix.Message
import java.io.File
import java.time.LocalDateTime

class SearchResultsPaneTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir = File.createTempFile("fixtool-test", "").apply {
            delete() // Delete the file
            mkdirs() // Create as directory
        }

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun cleanup() {
        // Clean up test directory
        testDir.deleteRecursively()
    }

    @Test
    fun testPinSearchResultsOpensPane() = runBlocking {
        // Create test session and add messages
        val session = viewModel.createSessionForTest("Test Session")

        // Add test messages
        addTestMessage(session, "D", 1, "SENDER", "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|55=AAPL|38=100|10=123|")
        addTestMessage(session, "8", 2, "SENDER", "8=FIX.4.2|9=100|35=8|49=SENDER|56=TARGET|34=2|55=MSFT|38=200|10=123|")

        delay(200) // Wait for messages to be added

        // Perform search
        viewModel.setGlobalSearchQuery("AAPL")

        delay(100) // Wait for search to complete

        // Verify results are found
        assertTrue("Search results should not be empty", viewModel.globalSearchResults.value.isNotEmpty())

        // Pin results
        viewModel.pinSearchResults()

        // Verify pane is shown
        assertTrue("Search results pane should be visible", viewModel.showSearchResultsPane.value)
        assertEquals(viewModel.globalSearchResults.value, viewModel.pinnedSearchResults.value)
    }

    @Test
    fun testCloseSearchResultsPane() = runBlocking {
        // Pin some results first
        val session = viewModel.createSessionForTest("Test Session")

        addTestMessage(session, "D", 1, "SENDER", "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|55=AAPL|38=100|10=123|")

        delay(200)

        viewModel.setGlobalSearchQuery("AAPL")

        delay(100)

        viewModel.pinSearchResults()

        assertTrue("Pane should be visible after pinning", viewModel.showSearchResultsPane.value)

        // Close pane
        viewModel.closeSearchResultsPane()

        // Verify pane is hidden and results cleared
        assertFalse("Pane should be hidden after closing", viewModel.showSearchResultsPane.value)
        assertTrue("Pinned results should be cleared", viewModel.pinnedSearchResults.value.isEmpty())
    }

    @Test
    fun testPinnedResultsPersistAfterDialogClose() = runBlocking {
        // Create session and add message
        val session = viewModel.createSessionForTest("Test Session")

        addTestMessage(session, "D", 1, "SENDER", "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|55=AAPL|38=100|10=123|")

        delay(200)

        // Open dialog first
        viewModel.toggleGlobalSearchDialog()

        // Search and pin
        viewModel.setGlobalSearchQuery("AAPL")

        delay(100)

        val searchResultsBeforePin = viewModel.globalSearchResults.value
        viewModel.pinSearchResults()

        // Close dialog
        viewModel.toggleGlobalSearchDialog()

        // Verify pinned results persist even though dialog is closed
        assertTrue("Pane should remain visible after dialog closes", viewModel.showSearchResultsPane.value)
        assertEquals(searchResultsBeforePin, viewModel.pinnedSearchResults.value)

        // Verify dialog search results are cleared (as per existing behavior)
        assertTrue("Dialog search results should be cleared", viewModel.globalSearchResults.value.isEmpty())
    }

    @Test
    fun testNavigateToSearchResultFromPane() = runBlocking {
        // Create two sessions
        val session1 = viewModel.createSessionForTest("Session 1")
        val session2 = viewModel.createSessionForTest("Session 2")

        // Add messages to both sessions
        addTestMessage(session1, "D", 1, "SENDER1", "8=FIX.4.2|9=100|35=D|49=SENDER1|56=TARGET|34=1|55=AAPL|38=100|10=123|")
        addTestMessage(session2, "8", 2, "SENDER2", "8=FIX.4.2|9=100|35=8|49=SENDER2|56=TARGET|34=2|55=AAPL|38=200|10=123|")

        delay(200)

        // Search and pin
        viewModel.setGlobalSearchQuery("AAPL")

        delay(100)

        viewModel.pinSearchResults()

        val results = viewModel.pinnedSearchResults.value
        assertEquals("Should find 2 results", 2, results.size)

        // Get the message from session2
        val session2Messages = session2.messages.value.filterIsInstance<FixMessage>()
        val msg2 = session2Messages.first()

        // Navigate to second result (from session2)
        viewModel.navigateToSearchResult(results[1])

        delay(100)

        // Verify active session is session2
        assertEquals("Active session index should be 1", 1, viewModel.activeSessionIndex)
        assertEquals("Active session should be session2", session2, viewModel.activeSession)

        // Verify message is selected
        assertEquals("Selected message should be msg2", msg2, viewModel.selectedMessage.value)
    }

    @Test
    fun testSearchResultsPaneShowsSessionUsername() = runBlocking {
        // Create session with custom username
        val session = viewModel.createSessionForTest("My Custom Session")

        addTestMessage(session, "D", 1, "SENDER", "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|55=AAPL|38=100|10=123|")

        delay(200)

        // Search
        viewModel.setGlobalSearchQuery("AAPL")

        delay(100)

        val results = viewModel.globalSearchResults.value
        assertEquals("Should find 1 result", 1, results.size)

        // Verify session username is included in search result
        assertEquals("Session username should match", "My Custom Session", results[0].sessionUsername)
    }

    @Test
    fun testSearchResultsIncludeMetadata() = runBlocking {
        // Create session
        val session = viewModel.createSessionForTest("Test Session")

        // Add message with symbol field
        addTestMessage(session, "D", 1, "SENDER", "8=FIX.4.2|9=100|35=D|49=SENDER|56=TARGET|34=1|55=AAPL|38=100|10=123|")

        delay(200)

        // Search
        viewModel.setGlobalSearchQuery("AAPL")

        delay(100)

        val results = viewModel.globalSearchResults.value
        assertEquals("Should find 1 result", 1, results.size)

        // Verify result contains expected metadata
        assertTrue("Matched text should contain AAPL", results[0].matchedText.contains("AAPL"))
        assertEquals("Session should match", session, results[0].session)
        assertNotNull("Message should not be null", results[0].message)
    }

    /**
     * Helper method to add a test message to a session
     */
    private fun addTestMessage(
        session: FixMessageSession,
        msgType: String,
        seqNum: Int,
        senderCompID: String,
        rawMsg: String,
    ) {
        val message = rawMsg.toQuickFixMessage()

        val fixMessage = FixMessage(
            timestamp = LocalDateTime.now(),
            direction = if (msgType == "D") FixMessage.Direction.OUTGOING else FixMessage.Direction.INCOMING,
            rawMessage = rawMsg,
            messageType = msgType,
            quickfixMessage = message,
        )

        // Add to session
        session.addMessage(fixMessage)
    }
}
