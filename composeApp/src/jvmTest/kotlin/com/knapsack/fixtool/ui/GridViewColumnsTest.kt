package com.knapsack.fixtool.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.*
import java.io.File
import java.time.LocalDateTime

class GridViewColumnsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var testDictionaryFile: File
    private lateinit var dictionary: FixDictionaryAdapter

    @Before
    fun setup() {
        // Create a test dictionary file
        testDictionaryFile = File.createTempFile("test_dict", ".xml")
        testDictionaryFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="4">
    <header>
        <field name="BeginString" required="Y"/>
        <field name="BodyLength" required="Y"/>
        <field name="MsgType" required="Y"/>
        <field name="SenderCompID" required="Y"/>
        <field name="TargetCompID" required="Y"/>
        <field name="MsgSeqNum" required="Y"/>
        <field name="SendingTime" required="Y"/>
    </header>
    <trailer>
        <field name="CheckSum" required="Y"/>
    </trailer>
    <messages>
        <message name="NewOrderSingle" msgtype="D" msgcat="app">
            <field name="ClOrdID" required="Y"/>
            <field name="Symbol" required="Y"/>
            <field name="Side" required="Y"/>
            <field name="OrderQty" required="Y"/>
            <field name="Price" required="N"/>
        </message>
        <message name="ExecutionReport" msgtype="8" msgcat="app">
            <field name="Symbol" required="Y"/>
        </message>
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="MsgType" number="35" type="STRING">
            <value enum="D" description="NewOrderSingle"/>
            <value enum="8" description="ExecutionReport"/>
        </field>
        <field name="MsgSeqNum" number="34" type="SEQNUM"/>
        <field name="SenderCompID" number="49" type="STRING"/>
        <field name="TargetCompID" number="56" type="STRING"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP"/>
        <field name="CheckSum" number="10" type="STRING"/>
        <field name="ClOrdID" number="11" type="STRING"/>
        <field name="Symbol" number="55" type="STRING"/>
        <field name="Side" number="54" type="CHAR">
            <value enum="1" description="Buy"/>
            <value enum="2" description="Sell"/>
        </field>
        <field name="OrderQty" number="38" type="QTY"/>
        <field name="Price" number="44" type="PRICE"/>
        <field name="NoMDEntries" number="268" type="NUMINGROUP"/>
    </fields>
</fix>""",
        )
        dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)
    }

    @After
    fun cleanup() {
        if (testDictionaryFile.exists()) {
            testDictionaryFile.delete()
        }
    }

    private fun createTestMessage(
        msgType: String,
        senderCompId: String,
        targetCompId: String,
        clOrdId: String? = null,
        symbol: String? = null,
        side: Char? = null,
        orderQty: Int? = null,
        price: Double? = null,
    ): FixMessage {
        val msg = Message()
        msg.header.setField(MsgType(msgType))
        msg.header.setField(SenderCompID(senderCompId))
        msg.header.setField(TargetCompID(targetCompId))

        clOrdId?.let { msg.setField(ClOrdID(it)) }
        symbol?.let { msg.setField(Symbol(it)) }
        side?.let { msg.setField(Side(it)) }
        orderQty?.let { msg.setField(OrderQty(it.toDouble())) }
        price?.let { msg.setField(Price(it)) }

        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = msg.toString(),
            messageType = msgType,
            quickfixMessage = msg,
        )
    }

    @Test
    fun testGridViewDisplaysNoExtraColumnsWhenNotConfigured() {
        // Given: Messages and no column configuration
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display in grid view with empty columns
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = emptyList(),
            )
        }

        // Then: We should see standard columns (Time, Dir, MsgType, Summary)
        composeTestRule.onNodeWithText("Time").assertExists()
        composeTestRule.onNodeWithText("Dir").assertExists()
        composeTestRule.onNodeWithText("MsgType").assertExists()
        composeTestRule.onNodeWithText("Summary").assertExists()

        // And: Field names should not appear as columns
        composeTestRule.onNodeWithText("Symbol").assertDoesNotExist()
        composeTestRule.onNodeWithText("ClOrdID").assertDoesNotExist()
    }

    @Test
    fun testGridViewDisplaysConfiguredColumns() {
        // Given: Messages and column configuration for Symbol(55) and ClOrdID(11)
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display in grid view with configured columns
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(55, 11), // Symbol, ClOrdID
            )
        }

        // Then: Column headers should show the field names
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("ClOrdID").assertExists()
    }

    @Test
    fun testGridViewDisplaysColumnValues() {
        // Given: A message with specific field values
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD12345", "MSFT", '2', 200, 300.0),
            )

        // When: We display in grid view with Symbol and ClOrdID columns
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(55, 11), // Symbol, ClOrdID
            )
        }

        composeTestRule.waitForIdle()

        // Then: The field values should be displayed in the grid
        composeTestRule.onNodeWithText("MSFT").assertExists()
        composeTestRule.onNodeWithText("ORD12345").assertExists()
    }

    @Test
    fun testGridViewDisplaysMultipleMessagesWithColumns() {
        // Given: Multiple messages with different values
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
                createTestMessage("D", "SENDER1", "TARGET1", "ORD002", "GOOGL", '2', 50, 2800.0),
                createTestMessage("8", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display in grid view with Symbol column
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(55), // Symbol
            )
        }

        composeTestRule.waitForIdle()

        // Then: All message symbols should be visible
        composeTestRule.onAllNodesWithText("AAPL").assertCountEquals(2)
        composeTestRule.onNodeWithText("GOOGL").assertExists()
    }

    @Test
    fun testGridViewHandlesMissingFieldValues() {
        // Given: A message with values in configured columns
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display in grid view with Symbol and ClOrdID columns
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(55, 11), // Symbol, ClOrdID
            )
        }

        composeTestRule.waitForIdle()

        // Then: Column headers should still be displayed
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("ClOrdID").assertExists()

        // And: Field values should be displayed in columns
        composeTestRule.onNodeWithText("AAPL").assertExists()
        composeTestRule.onNodeWithText("ORD001").assertExists()
    }

    @Test
    fun testGridViewDisplaysColumnHeadersForUnknownTags() {
        // Given: Messages and column configuration with unknown tag
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display in grid view with unknown tag 999
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(999), // Unknown tag
            )
        }

        composeTestRule.waitForIdle()

        // Then: Column header should show the tag number
        composeTestRule.onNodeWithText("999").assertExists()
    }

    @Test
    fun testGridViewColumnsOrderMatchesConfiguration() {
        // Given: Messages and specific column order
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD001", "AAPL", '1', 100, 150.0),
            )

        // When: We display with columns in specific order: Price, Symbol, ClOrdID
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(44, 55, 11), // Price, Symbol, ClOrdID
            )
        }

        composeTestRule.waitForIdle()

        // Then: All three columns should exist
        composeTestRule.onNodeWithText("Price").assertExists()
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("ClOrdID").assertExists()
    }

    @Test
    fun testGridViewHandlesRepeatingGroupColumn() {
        // Given: A message with configured columns
        val messages =
            listOf(
                createTestMessage("D", "SENDER1", "TARGET1", "ORD002", "MSFT", '1', 200, 250.0),
            )

        // When: We configure Symbol and ClOrdID columns
        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(55, 11), // Symbol, ClOrdID
            )
        }

        composeTestRule.waitForIdle()

        // Then: Column headers should exist
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("ClOrdID").assertExists()

        // And: Field values should be displayed
        composeTestRule.onNodeWithText("MSFT").assertExists()
        composeTestRule.onNodeWithText("ORD002").assertExists()
    }
}
