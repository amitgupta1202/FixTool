package com.knapsack.fixtool.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.AppSettingsService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.*
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Integration tests for the complete grid view column configuration flow:
 * - Configure columns in settings
 * - Save settings
 * - Load settings
 * - Display grid view with configured columns
 */
class GridViewColumnsIntegrationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var testDictionaryFile: File
    private lateinit var dictionary: FixDictionaryAdapter
    private lateinit var originalSettingsFile: File
    private lateinit var settingsService: AppSettingsService

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
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="MsgType" number="35" type="STRING">
            <value enum="D" description="NewOrderSingle"/>
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
    </fields>
</fix>""",
        )
        dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)

        // Backup settings file
        originalSettingsFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json.backup_integration")
        if (originalSettingsFile.exists()) {
            originalSettingsFile.copyTo(backupFile, overwrite = true)
            originalSettingsFile.delete()
        }

        settingsService = AppSettingsService()
    }

    @After
    fun cleanup() {
        if (testDictionaryFile.exists()) {
            testDictionaryFile.delete()
        }

        // Restore original settings
        if (originalSettingsFile.exists()) {
            originalSettingsFile.delete()
        }

        val backupFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json.backup_integration")
        if (backupFile.exists()) {
            backupFile.copyTo(originalSettingsFile, overwrite = true)
            backupFile.delete()
        }
    }

    private fun createTestMessage(
        msgType: String,
        clOrdId: String,
        symbol: String,
        side: Char,
        orderQty: Int,
        price: Double,
    ): FixMessage {
        val msg = Message()
        msg.header.setField(MsgType(msgType))
        msg.header.setField(SenderCompID("SENDER"))
        msg.header.setField(TargetCompID("TARGET"))
        msg.setField(ClOrdID(clOrdId))
        msg.setField(Symbol(symbol))
        msg.setField(Side(side))
        msg.setField(OrderQty(orderQty.toDouble()))
        msg.setField(Price(price))

        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = msg.toString(),
            messageType = msgType,
            quickfixMessage = msg,
        )
    }

    @Test
    fun testEndToEndColumnConfigurationFlow() {
        // Given: Initial settings with no columns configured
        var settings =
            AppSettings(
                defaultDataDictionary = testDictionaryFile.absolutePath,
                gridViewColumns = emptyList(),
            )
        settingsService.saveSettings(settings)

        // When: User configures columns to display Symbol(55) and Price(44)
        settings = settings.copy(gridViewColumns = listOf(55, 44))
        settingsService.saveSettings(settings)

        // And: Settings are reloaded (simulating app restart)
        val loadedSettings = settingsService.loadSettings()

        // Then: The configured columns should be persisted
        assertEquals(listOf(55, 44), loadedSettings.gridViewColumns)

        // And: When displaying messages in grid view
        val messages =
            listOf(
                createTestMessage("D", "ORD001", "AAPL", '1', 100, 150.25),
                createTestMessage("D", "ORD002", "GOOGL", '2', 50, 2800.50),
            )

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = loadedSettings.gridViewColumns,
            )
        }

        composeTestRule.waitForIdle()

        // Then: Column headers should be displayed
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("Price").assertExists()

        // And: Values should be displayed
        composeTestRule.onNodeWithText("AAPL").assertExists()
        composeTestRule.onNodeWithText("GOOGL").assertExists()
        composeTestRule.onNodeWithText("150.25").assertExists()
        composeTestRule.onNodeWithText("2800.5").assertExists()
    }

    @Test
    fun testAddingAndRemovingColumns() {
        // Given: Settings with some columns
        var settings =
            AppSettings(
                defaultDataDictionary = testDictionaryFile.absolutePath,
                gridViewColumns = listOf(55), // Symbol only
            )
        settingsService.saveSettings(settings)

        // When: User adds more columns
        settings = settings.copy(gridViewColumns = listOf(55, 11, 44)) // Symbol, ClOrdID, Price
        settingsService.saveSettings(settings)

        // Then: All columns should be saved
        var loadedSettings = settingsService.loadSettings()
        assertEquals(listOf(55, 11, 44), loadedSettings.gridViewColumns)

        // When: User removes a column
        settings = settings.copy(gridViewColumns = listOf(55, 44)) // Remove ClOrdID
        settingsService.saveSettings(settings)

        // Then: The updated configuration should be saved
        loadedSettings = settingsService.loadSettings()
        assertEquals(listOf(55, 44), loadedSettings.gridViewColumns)
    }

    @Test
    fun testReorderingColumns() {
        // Given: Settings with columns in one order
        var settings =
            AppSettings(
                defaultDataDictionary = testDictionaryFile.absolutePath,
                gridViewColumns = listOf(55, 11, 44), // Symbol, ClOrdID, Price
            )
        settingsService.saveSettings(settings)

        // When: User reorders columns
        settings = settings.copy(gridViewColumns = listOf(44, 55, 11)) // Price, Symbol, ClOrdID
        settingsService.saveSettings(settings)

        // Then: The new order should be preserved
        val loadedSettings = settingsService.loadSettings()
        assertEquals(listOf(44, 55, 11), loadedSettings.gridViewColumns)

        // And: Grid view should display columns in the new order
        val messages =
            listOf(
                createTestMessage("D", "ORD001", "AAPL", '1', 100, 150.25),
            )

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = loadedSettings.gridViewColumns,
            )
        }

        composeTestRule.waitForIdle()

        // All columns should exist
        composeTestRule.onNodeWithText("Price").assertExists()
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("ClOrdID").assertExists()
    }

    @Test
    fun testClearingAllColumns() {
        // Given: Settings with columns configured
        var settings =
            AppSettings(
                defaultDataDictionary = testDictionaryFile.absolutePath,
                gridViewColumns = listOf(55, 11, 44),
            )
        settingsService.saveSettings(settings)

        // When: User clears all columns
        settings = settings.copy(gridViewColumns = emptyList())
        settingsService.saveSettings(settings)

        // Then: No columns should be configured
        val loadedSettings = settingsService.loadSettings()
        assertEquals(emptyList(), loadedSettings.gridViewColumns)

        // And: Grid view should only show default columns
        val messages =
            listOf(
                createTestMessage("D", "ORD001", "AAPL", '1', 100, 150.25),
            )

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = loadedSettings.gridViewColumns,
            )
        }

        composeTestRule.waitForIdle()

        // Default columns should exist
        composeTestRule.onNodeWithText("Time").assertExists()
        composeTestRule.onNodeWithText("MsgType").assertExists()

        // Custom columns should not exist as headers
        composeTestRule.onNodeWithText("Symbol").assertDoesNotExist()
        composeTestRule.onNodeWithText("Price").assertDoesNotExist()
    }

    @Test
    fun testColumnsWorkWithMultipleMessageTypes() {
        // Given: Settings with Symbol and Side columns
        val settings =
            AppSettings(
                defaultDataDictionary = testDictionaryFile.absolutePath,
                gridViewColumns = listOf(55, 54), // Symbol, Side
            )
        settingsService.saveSettings(settings)

        // When: Displaying messages of different types
        val messages =
            listOf(
                createTestMessage("D", "ORD001", "AAPL", '1', 100, 150.25),
                createTestMessage("D", "ORD002", "GOOGL", '2', 50, 2800.50),
            )

        val loadedSettings = settingsService.loadSettings()

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = loadedSettings.gridViewColumns,
            )
        }

        composeTestRule.waitForIdle()

        // Then: Both columns should be displayed
        composeTestRule.onNodeWithText("Symbol").assertExists()
        composeTestRule.onNodeWithText("Side").assertExists()

        // And: Values from both messages should be visible
        composeTestRule.onNodeWithText("AAPL").assertExists()
        composeTestRule.onNodeWithText("GOOGL").assertExists()
        composeTestRule.onNodeWithText("1").assertExists()
        composeTestRule.onNodeWithText("2").assertExists()
    }
}
