package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import com.knapsack.fixtool.ui.FixField
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the message template feature that test the complete workflow
 * of receiving messages and then referencing them in templates when sending messages.
 */
class MessageTemplateIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var originalFile: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }

        // Backup and clear saved messages file to ensure test isolation
        originalFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")

        if (originalFile.exists()) {
            originalFile.copyTo(backupFile, overwrite = true)
            originalFile.delete()
        }

        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Create a test session - ViewModel doesn't create one automatically
        viewModel.createSessionForTest("Test Session")
    }

    @After
    fun cleanup() {
        // Clean up test data and restore original file
        if (originalFile.exists()) {
            originalFile.delete()
        }
        // Clean up test directory
        testDir.deleteRecursively()

        // Restore backup if it exists
        val backupFile = File(System.getProperty("user.home"), ".fixtool/saved_messages.json.backup")
        if (backupFile.exists()) {
            backupFile.copyTo(originalFile, overwrite = true)
            backupFile.delete()
        }
    }

    /**
     * Helper to create a mock QuickFIX message
     */
    private fun createQuickFixMessage(messageType: String, vararg tagValues: Pair<Int, String>): Message {
        val message = Message()
        message.header.setString(MsgType.FIELD, messageType)
        tagValues.forEach { (tag, value) ->
            message.setString(tag, value)
        }
        return message
    }

    /**
     * Helper to add a message to the active session
     */
    private fun addMessageToActiveSession(
        messageType: String,
        direction: FixMessage.Direction,
        vararg tagValues: Pair<Int, String>,
    ) {
        val quickfixMessage = createQuickFixMessage(messageType, *tagValues)
        val rawMessage =
            "8=FIX.4.2|9=100|35=$messageType|" +
                tagValues.joinToString("|") { "${it.first}=${it.second}" } + "|10=123|"

        val fixMessage =
            FixMessage(
                timestamp = LocalDateTime.now(),
                direction = direction,
                rawMessage = rawMessage,
                messageType = messageType,
                quickfixMessage = quickfixMessage,
            )

        val session = viewModel.activeSession
        session?.addMessage(fixMessage)
    }

    @Test
    fun testUpdateMessageMapsPopulatesIncomingAndOutgoing() {
        // Add incoming NewOrderSingle (35=D)
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER123",
            55 to "EUR/USD",
        )

        // Add outgoing QuoteRequest (35=R)
        addMessageToActiveSession(
            messageType = "R",
            direction = FixMessage.Direction.OUTGOING,
            131 to "QUOTE456",
            55 to "GBP/USD",
        )

        // Update the message maps
        viewModel.updateMessageMaps()

        // Verify maps are populated
        assertEquals(1, viewModel.incomingMessagesByType.size)
        assertEquals(1, viewModel.outgoingMessagesByType.size)
        assertTrue(viewModel.incomingMessagesByType.containsKey("D"))
        assertTrue(viewModel.outgoingMessagesByType.containsKey("R"))
    }

    @Test
    fun testTemplateReferencesIncomingMessage() {
        // Add incoming NewOrderSingle
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER123",
            55 to "EUR/USD",
        )

        // Update maps
        viewModel.updateMessageMaps()

        // Create a template that references the incoming message
        val template = "\${incoming[\"D\"].valueOfTag(11)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        assertEquals("ORDER123", result)
    }

    @Test
    fun testTemplateReferencesOutgoingMessage() {
        // Add outgoing QuoteRequest
        addMessageToActiveSession(
            messageType = "R",
            direction = FixMessage.Direction.OUTGOING,
            131 to "QUOTE456",
            55 to "GBP/USD",
        )

        // Update maps
        viewModel.updateMessageMaps()

        // Create a template that references the outgoing message
        val template = "\${outgoing[\"R\"].valueOfTag(131)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        assertEquals("QUOTE456", result)
    }

    @Test
    fun testTemplateWithMultipleMessageReferences() {
        // Add incoming NewOrderSingle
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER123",
            55 to "EUR/USD",
        )

        // Add outgoing QuoteRequest
        addMessageToActiveSession(
            messageType = "R",
            direction = FixMessage.Direction.OUTGOING,
            131 to "QUOTE456",
            55 to "GBP/USD",
        )

        // Update maps
        viewModel.updateMessageMaps()

        // Template that references both incoming and outgoing
        val template = "Response to \${incoming[\"D\"].valueOfTag(11)} for quote \${outgoing[\"R\"].valueOfTag(131)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        assertEquals("Response to ORDER123 for quote QUOTE456", result)
    }

    @Test
    fun testMessageMapKeepsLatestMessagePerType() {
        // Add first NewOrderSingle
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER123",
            55 to "EUR/USD",
        )

        viewModel.updateMessageMaps()
        var template = "\${incoming[\"D\"].valueOfTag(11)}"
        var result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )
        assertEquals("ORDER123", result)

        // Add second NewOrderSingle (should replace the first)
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER999",
            55 to "USD/JPY",
        )

        viewModel.updateMessageMaps()
        result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )
        assertEquals("ORDER999", result) // Should get the latest message
    }

    @Test
    fun testTemplateWithMissingMessageTypeReturnsNull() {
        // Don't add any messages
        viewModel.updateMessageMaps()

        val template = "\${incoming[\"X\"].valueOfTag(11)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        assertEquals("null", result)
    }

    @Test
    fun testTemplateWithMissingTagReturnsNull() {
        // Add message with tag 11 but not tag 999
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.INCOMING,
            11 to "ORDER123",
        )

        viewModel.updateMessageMaps()

        val template = "\${incoming[\"D\"].valueOfTag(999)}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        assertEquals("null", result)
    }

    @Test
    fun testComplexTemplateWithMessageReferenceAndUuid() {
        // Add outgoing message
        addMessageToActiveSession(
            messageType = "D",
            direction = FixMessage.Direction.OUTGOING,
            11 to "ORDER123",
        )

        viewModel.updateMessageMaps()

        val template = "\${outgoing[\"D\"].valueOfTag(11)}-\${UUID.randomUUID()}"
        val result =
            FixMessageTemplate.evaluate(
                template,
                incomingMessages = viewModel.incomingMessagesByType,
                outgoingMessages = viewModel.outgoingMessagesByType,
            )

        // Verify it starts with the order ID
        assertTrue(result.startsWith("ORDER123-"))
        // Verify it has a UUID after the dash (36 chars for UUID)
        assertTrue(result.length > 45) // "ORDER123-" (9) + UUID (36)
    }

    @Test
    fun testResolveTemplatesInFieldList() {
        // Add incoming QuoteRequest
        addMessageToActiveSession(
            messageType = "R",
            direction = FixMessage.Direction.INCOMING,
            131 to "QUOTEREQ-2025-001",
            55 to "EUR/USD",
        )

        viewModel.updateMessageMaps()

        // Create field list with templates (using String for tag as required by FixField)
        val fields =
            listOf(
                FixField(tag = "35", value = "AJ"), // QuoteResponse
                FixField(tag = "131", value = "\${incoming[\"R\"].valueOfTag(131)}"), // Reference incoming QuoteReqID
                FixField(tag = "55", value = "\${incoming[\"R\"].valueOfTag(55)}"), // Reference incoming Symbol
                FixField(tag = "117", value = "\${UUID.randomUUID()}"), // Generate new QuoteID
            )

        // Resolve templates (this is what happens in App.kt onSend)
        val resolvedFields =
            fields.map { field ->
                if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                    field.copy(
                        value =
                            FixMessageTemplate.evaluate(
                                field.value,
                                incomingMessages = viewModel.incomingMessagesByType,
                                outgoingMessages = viewModel.outgoingMessagesByType,
                            ),
                    )
                } else {
                    field
                }
            }

        // Verify fields were resolved
        assertEquals("AJ", resolvedFields[0].value)
        assertEquals("QUOTEREQ-2025-001", resolvedFields[1].value)
        assertEquals("EUR/USD", resolvedFields[2].value)
        assertTrue(resolvedFields[3].value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testRealWorldQuoteWorkflow() {
        // Simulate a real quote workflow:
        // 1. Receive QuoteRequest from client
        addMessageToActiveSession(
            messageType = "R",
            direction = FixMessage.Direction.INCOMING,
            131 to "QUOTEREQ-2025-12345",
            55 to "EUR/USD",
            38 to "1000000",
        )

        viewModel.updateMessageMaps()

        // 2. Send QuoteResponse referencing the incoming QuoteReqID
        val quoteResponseTemplate =
            listOf(
                FixField(tag = "35", value = "AJ"),
                FixField(tag = "131", value = "\${incoming[\"R\"].valueOfTag(131)}"), // Same QuoteReqID
                FixField(tag = "55", value = "\${incoming[\"R\"].valueOfTag(55)}"), // Same Symbol
                FixField(tag = "117", value = "QUOTE-\${UUID.randomUUID()}"), // New QuoteID
                FixField(tag = "132", value = "100.50"), // Bid price
                FixField(tag = "133", value = "100.52"), // Offer price
            )

        val resolvedQuoteResponse =
            quoteResponseTemplate.map { field ->
                if (FixMessageTemplate.hasTemplateExpressions(field.value)) {
                    field.copy(
                        value =
                            FixMessageTemplate.evaluate(
                                field.value,
                                incomingMessages = viewModel.incomingMessagesByType,
                                outgoingMessages = viewModel.outgoingMessagesByType,
                            ),
                    )
                } else {
                    field
                }
            }

        // Verify the quote response has correct values
        assertEquals("AJ", resolvedQuoteResponse[0].value)
        assertEquals("QUOTEREQ-2025-12345", resolvedQuoteResponse[1].value) // Referenced from incoming
        assertEquals("EUR/USD", resolvedQuoteResponse[2].value) // Referenced from incoming
        assertTrue(resolvedQuoteResponse[3].value.startsWith("QUOTE-")) // Generated QuoteID
        assertEquals("100.50", resolvedQuoteResponse[4].value)
        assertEquals("100.52", resolvedQuoteResponse[5].value)
    }
}
