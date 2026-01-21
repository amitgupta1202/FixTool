package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Integration tests for multi-FIX version support.
 * Tests dictionary loading, message parsing, and configuration for all supported FIX versions.
 */
class FixVersionIntegrationTest {
    @Before
    fun setup() {
        // Clear any cached dictionaries to ensure fresh loading
        FixDictionaryAdapter.clearCache()
    }

    // ========================================
    // FixVersion Enum Tests
    // ========================================

    @Test
    fun `all FIX versions are defined`() {
        val versions = FixVersion.entries
        assertEquals(8, versions.size, "Should have 8 FIX versions defined")

        val expectedVersions =
            listOf(
                "FIX 4.0",
                "FIX 4.1",
                "FIX 4.2",
                "FIX 4.3",
                "FIX 4.4",
                "FIX 5.0",
                "FIX 5.0 SP1",
                "FIX 5.0 SP2",
            )
        expectedVersions.forEach { name ->
            assertTrue(versions.any { it.displayName == name }, "Missing version: $name")
        }
    }

    @Test
    fun `FIX 4x versions have correct BeginString`() {
        assertEquals("FIX.4.0", FixVersion.FIX_4_0.beginString)
        assertEquals("FIX.4.1", FixVersion.FIX_4_1.beginString)
        assertEquals("FIX.4.2", FixVersion.FIX_4_2.beginString)
        assertEquals("FIX.4.3", FixVersion.FIX_4_3.beginString)
        assertEquals("FIX.4.4", FixVersion.FIX_4_4.beginString)
    }

    @Test
    fun `FIX 5x versions use FIXT 1_1 BeginString`() {
        assertEquals("FIXT.1.1", FixVersion.FIX_5_0.beginString)
        assertEquals("FIXT.1.1", FixVersion.FIX_5_0_SP1.beginString)
        assertEquals("FIXT.1.1", FixVersion.FIX_5_0_SP2.beginString)
    }

    @Test
    fun `FIX 5x versions have correct ApplVerID`() {
        assertEquals("7", FixVersion.FIX_5_0.applVerID)
        assertEquals("8", FixVersion.FIX_5_0_SP1.applVerID)
        assertEquals("9", FixVersion.FIX_5_0_SP2.applVerID)
    }

    @Test
    fun `FIX 4x versions have null ApplVerID`() {
        assertNull(FixVersion.FIX_4_0.applVerID)
        assertNull(FixVersion.FIX_4_1.applVerID)
        assertNull(FixVersion.FIX_4_2.applVerID)
        assertNull(FixVersion.FIX_4_3.applVerID)
        assertNull(FixVersion.FIX_4_4.applVerID)
    }

    @Test
    fun `isFix50Plus returns correct values`() {
        assertFalse(FixVersion.FIX_4_0.isFix50Plus)
        assertFalse(FixVersion.FIX_4_1.isFix50Plus)
        assertFalse(FixVersion.FIX_4_2.isFix50Plus)
        assertFalse(FixVersion.FIX_4_3.isFix50Plus)
        assertFalse(FixVersion.FIX_4_4.isFix50Plus)
        assertTrue(FixVersion.FIX_5_0.isFix50Plus)
        assertTrue(FixVersion.FIX_5_0_SP1.isFix50Plus)
        assertTrue(FixVersion.FIX_5_0_SP2.isFix50Plus)
    }

    @Test
    fun `fromBeginString resolves FIX 4x versions correctly`() {
        assertEquals(FixVersion.FIX_4_0, FixVersion.fromBeginString("FIX.4.0"))
        assertEquals(FixVersion.FIX_4_1, FixVersion.fromBeginString("FIX.4.1"))
        assertEquals(FixVersion.FIX_4_2, FixVersion.fromBeginString("FIX.4.2"))
        assertEquals(FixVersion.FIX_4_3, FixVersion.fromBeginString("FIX.4.3"))
        assertEquals(FixVersion.FIX_4_4, FixVersion.fromBeginString("FIX.4.4"))
    }

    @Test
    fun `fromBeginString resolves FIXT 1_1 with ApplVerID correctly`() {
        assertEquals(FixVersion.FIX_5_0, FixVersion.fromBeginString("FIXT.1.1", "7"))
        assertEquals(FixVersion.FIX_5_0_SP1, FixVersion.fromBeginString("FIXT.1.1", "8"))
        assertEquals(FixVersion.FIX_5_0_SP2, FixVersion.fromBeginString("FIXT.1.1", "9"))
    }

    @Test
    fun `fromBeginString returns default for unknown version`() {
        assertEquals(FixVersion.DEFAULT, FixVersion.fromBeginString("FIX.3.0"))
        assertEquals(FixVersion.DEFAULT, FixVersion.fromBeginString("UNKNOWN"))
    }

    @Test
    fun `fromDisplayName resolves correctly`() {
        assertEquals(FixVersion.FIX_4_4, FixVersion.fromDisplayName("FIX 4.4"))
        assertEquals(FixVersion.FIX_5_0_SP2, FixVersion.fromDisplayName("FIX 5.0 SP2"))
        assertNull(FixVersion.fromDisplayName("Unknown Version"))
    }

    // ========================================
    // Header/Trailer Tags Tests
    // ========================================

    @Test
    fun `all versions have BeginString tag 8 in header`() {
        FixVersion.entries.forEach { version ->
            val headerTags = FixVersion.getHeaderTags(version)
            assertTrue(8 in headerTags, "Version ${version.displayName} should have tag 8 in header")
        }
    }

    @Test
    fun `all versions have MsgType tag 35 in header`() {
        FixVersion.entries.forEach { version ->
            val headerTags = FixVersion.getHeaderTags(version)
            assertTrue(35 in headerTags, "Version ${version.displayName} should have tag 35 in header")
        }
    }

    @Test
    fun `all versions have CheckSum tag 10 in trailer`() {
        FixVersion.entries.forEach { version ->
            val trailerTags = FixVersion.getTrailerTags(version)
            assertTrue(10 in trailerTags, "Version ${version.displayName} should have tag 10 in trailer")
        }
    }

    @Test
    fun `FIX 5x versions have ApplVerID tag 1128 in header`() {
        listOf(FixVersion.FIX_5_0, FixVersion.FIX_5_0_SP1, FixVersion.FIX_5_0_SP2).forEach { version ->
            val headerTags = FixVersion.getHeaderTags(version)
            assertTrue(1128 in headerTags, "Version ${version.displayName} should have tag 1128 in header")
        }
    }

    @Test
    fun `FIX 4x versions do not have ApplVerID tag 1128 in header`() {
        listOf(FixVersion.FIX_4_0, FixVersion.FIX_4_1, FixVersion.FIX_4_2, FixVersion.FIX_4_3, FixVersion.FIX_4_4).forEach { version ->
            val headerTags = FixVersion.getHeaderTags(version)
            assertFalse(1128 in headerTags, "Version ${version.displayName} should not have tag 1128 in header")
        }
    }

    // ========================================
    // Dictionary Loading Tests - One per Version
    // ========================================

    @Test
    fun `can load FIX 4_0 dictionary`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_0)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 4.0")
        assertEquals(FixVersion.FIX_4_0, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNull(adapter.getTransportDictionary(), "FIX 4.0 should not have transport dictionary")
    }

    @Test
    fun `can load FIX 4_1 dictionary`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_1)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 4.1")
        assertEquals(FixVersion.FIX_4_1, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNull(adapter.getTransportDictionary(), "FIX 4.1 should not have transport dictionary")
    }

    @Test
    fun `can load FIX 4_2 dictionary`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_2)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 4.2")
        assertEquals(FixVersion.FIX_4_2, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNull(adapter.getTransportDictionary(), "FIX 4.2 should not have transport dictionary")
    }

    @Test
    fun `can load FIX 4_3 dictionary`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_3)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 4.3")
        assertEquals(FixVersion.FIX_4_3, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNull(adapter.getTransportDictionary(), "FIX 4.3 should not have transport dictionary")
    }

    @Test
    fun `can load FIX 4_4 dictionary`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 4.4")
        assertEquals(FixVersion.FIX_4_4, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNull(adapter.getTransportDictionary(), "FIX 4.4 should not have transport dictionary")
    }

    @Test
    fun `can load FIX 5_0 dictionary with transport`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_5_0)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 5.0")
        assertEquals(FixVersion.FIX_5_0, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNotNull(adapter.getTransportDictionary(), "FIX 5.0 should have transport dictionary")
        assertNotNull(adapter.getTransportFilePath())
    }

    @Test
    fun `can load FIX 5_0 SP1 dictionary with transport`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_5_0_SP1)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 5.0 SP1")
        assertEquals(FixVersion.FIX_5_0_SP1, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNotNull(adapter.getTransportDictionary(), "FIX 5.0 SP1 should have transport dictionary")
        assertNotNull(adapter.getTransportFilePath())
    }

    @Test
    fun `can load FIX 5_0 SP2 dictionary with transport`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_5_0_SP2)
        assertTrue(adapter.isLoaded(), "Dictionary should be loaded for FIX 5.0 SP2")
        assertEquals(FixVersion.FIX_5_0_SP2, adapter.fixVersion)
        assertNotNull(adapter.getDataDictionary())
        assertNotNull(adapter.getTransportDictionary(), "FIX 5.0 SP2 should have transport dictionary")
        assertNotNull(adapter.getTransportFilePath())
    }

    // ========================================
    // Field Lookup Tests
    // ========================================

    @Test
    fun `can lookup common fields for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)

            // These fields should exist in all FIX versions
            assertNotNull(adapter.getFieldName(35), "MsgType (35) should exist in ${version.displayName}")
            assertNotNull(adapter.getFieldName(49), "SenderCompID (49) should exist in ${version.displayName}")
            assertNotNull(adapter.getFieldName(56), "TargetCompID (56) should exist in ${version.displayName}")
        }
    }

    @Test
    fun `dictionary caching works correctly`() {
        // Load same version twice
        val adapter1 = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val adapter2 = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

        // Should be the same cached instance
        assertSame(adapter1, adapter2, "Same version should return cached adapter")

        // Different version should be different instance
        val adapter3 = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_2)
        assertNotSame(adapter1, adapter3, "Different versions should return different adapters")
    }

    @Test
    fun `all versions have fields defined`() {
        // All versions should have fields defined in their dictionaries
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)
            val fieldCount = adapter.getAllFields().size
            assertTrue(
                fieldCount > 0,
                "${version.displayName} should have fields defined, got $fieldCount",
            )
            // Can look up common fields by tag
            assertNotNull(adapter.getFieldName(35), "${version.displayName} should have MsgType defined")
        }
    }

    // ========================================
    // Message Parsing Tests
    // ========================================

    @Test
    fun `can parse NewOrderSingle for FIX 4_2`() {
        testMessageParsing(FixVersion.FIX_4_2, "D")
    }

    @Test
    fun `can parse NewOrderSingle for FIX 4_4`() {
        testMessageParsing(FixVersion.FIX_4_4, "D")
    }

    @Test
    fun `can parse NewOrderSingle for FIX 5_0 SP2`() {
        testMessageParsing(FixVersion.FIX_5_0_SP2, "D")
    }

    @Test
    fun `can parse ExecutionReport for FIX 4_2`() {
        testExecutionReportParsing(FixVersion.FIX_4_2)
    }

    @Test
    fun `can parse ExecutionReport for FIX 4_4`() {
        testExecutionReportParsing(FixVersion.FIX_4_4)
    }

    @Test
    fun `can parse ExecutionReport for FIX 5_0 SP2`() {
        testExecutionReportParsing(FixVersion.FIX_5_0_SP2)
    }

    private fun testMessageParsing(version: FixVersion, expectedMsgType: String) {
        val adapter = FixDictionaryAdapter.forVersion(version)
        val dataDictionary = adapter.getDataDictionary()!!

        val rawMessage = buildNewOrderSingle(version)
        val message = rawMessage.toQuickFixMessageManual(dataDictionary, version)

        assertNotNull(message, "Message should be parsed")
        assertEquals(expectedMsgType, message.header.getString(35), "MsgType should be $expectedMsgType")
        assertEquals(version.beginString, message.header.getString(8), "BeginString should match")
    }

    private fun testExecutionReportParsing(version: FixVersion) {
        val adapter = FixDictionaryAdapter.forVersion(version)
        val dataDictionary = adapter.getDataDictionary()!!

        val rawMessage = buildExecutionReport(version)
        val message = rawMessage.toQuickFixMessageManual(dataDictionary, version)

        assertNotNull(message, "Message should be parsed")
        assertEquals("8", message.header.getString(35), "MsgType should be 8 (ExecutionReport)")
    }

    @Test
    fun `header fields are correctly identified for FIX 4_4`() {
        val adapter = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val dataDictionary = adapter.getDataDictionary()!!

        val rawMessage =
            "8=FIX.4.4\u00019=100\u000135=D\u000149=SENDER\u000156=TARGET\u000134=1\u000152=20240101-12:00:00\u0001" +
                "11=ORDER1\u000155=AAPL\u000154=1\u000160=20240101-12:00:00\u000138=100\u000140=2\u000144=150.00\u000110=000\u0001"

        val message = rawMessage.toQuickFixMessageManual(dataDictionary, FixVersion.FIX_4_4)

        // Verify header fields are in header
        assertTrue(message.header.isSetField(8), "BeginString should be in header")
        assertTrue(message.header.isSetField(35), "MsgType should be in header")
        assertTrue(message.header.isSetField(49), "SenderCompID should be in header")
        assertTrue(message.header.isSetField(56), "TargetCompID should be in header")

        // Verify body fields are in body
        assertTrue(message.isSetField(11), "ClOrdID should be in body")
        assertTrue(message.isSetField(55), "Symbol should be in body")
    }

    // ========================================
    // Field Enum Value Tests
    // ========================================

    @Test
    fun `MsgType field has enum values for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)

            assertTrue(
                adapter.hasFieldValues(35),
                "${version.displayName} should have MsgType (35) enum values",
            )

            val enumValues = adapter.getFieldEnumValues(35)
            assertTrue(
                enumValues.isNotEmpty(),
                "${version.displayName} should have MsgType enum values",
            )

            // Common message types should exist
            val msgTypes = enumValues.map { it.first }
            assertTrue("D" in msgTypes, "${version.displayName} should have NewOrderSingle (D)")
            assertTrue("8" in msgTypes, "${version.displayName} should have ExecutionReport (8)")
            assertTrue("A" in msgTypes, "${version.displayName} should have Logon (A)")
        }
    }

    @Test
    fun `Side field has enum values for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)

            assertTrue(
                adapter.hasFieldValues(54),
                "${version.displayName} should have Side (54) enum values",
            )

            val enumValues = adapter.getFieldEnumValues(54)
            val sides = enumValues.map { it.first }
            assertTrue("1" in sides, "${version.displayName} should have Buy (1)")
            assertTrue("2" in sides, "${version.displayName} should have Sell (2)")
        }
    }

    // ========================================
    // Resource Path Tests
    // ========================================

    @Test
    fun `all dictionary resources exist`() {
        FixVersion.entries.forEach { version ->
            val resourcePath = version.dictionaryResourcePath
            val resourceStream = javaClass.getResourceAsStream(resourcePath)

            assertNotNull(
                resourceStream,
                "Resource should exist at $resourcePath for ${version.displayName}",
            )
            resourceStream?.close()
        }
    }

    @Test
    fun `transport dictionary resources exist for FIX 5x`() {
        listOf(FixVersion.FIX_5_0, FixVersion.FIX_5_0_SP1, FixVersion.FIX_5_0_SP2).forEach { version ->
            val transportPath = version.transportDictionaryResourcePath
            assertNotNull(transportPath, "${version.displayName} should have transport dictionary path")

            val resourceStream = javaClass.getResourceAsStream(transportPath!!)
            assertNotNull(resourceStream, "Transport resource should exist at $transportPath")
            resourceStream?.close()
        }
    }

    // ========================================
    // Adapter Helper Method Tests
    // ========================================

    @Test
    fun `adapter getHeaderTags matches FixVersion for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)
            assertEquals(
                FixVersion.getHeaderTags(version),
                adapter.getHeaderTags(),
                "Adapter header tags should match FixVersion for ${version.displayName}",
            )
        }
    }

    @Test
    fun `adapter getTrailerTags matches FixVersion for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)
            assertEquals(
                FixVersion.getTrailerTags(version),
                adapter.getTrailerTags(),
                "Adapter trailer tags should match FixVersion for ${version.displayName}",
            )
        }
    }

    @Test
    fun `adapter isFix50Plus matches FixVersion for all versions`() {
        FixVersion.entries.forEach { version ->
            val adapter = FixDictionaryAdapter.forVersion(version)
            assertEquals(
                version.isFix50Plus,
                adapter.isFix50Plus(),
                "Adapter isFix50Plus should match FixVersion for ${version.displayName}",
            )
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun buildNewOrderSingle(version: FixVersion): String {
        val beginString = version.beginString
        val fields = mutableListOf<String>()

        fields.add("8=$beginString")
        fields.add("9=100")
        fields.add("35=D")
        fields.add("49=SENDER")
        fields.add("56=TARGET")
        fields.add("34=1")
        fields.add("52=20240101-12:00:00")

        if (version.isFix50Plus && version.applVerID != null) {
            fields.add("1128=${version.applVerID}")
        }

        fields.add("11=ORDER123")
        fields.add("55=AAPL")
        fields.add("54=1")
        fields.add("60=20240101-12:00:00")
        fields.add("38=100")
        fields.add("40=2")
        fields.add("44=150.00")
        fields.add("10=000")

        return fields.joinToString("\u0001") + "\u0001"
    }

    private fun buildExecutionReport(version: FixVersion): String {
        val beginString = version.beginString
        val fields = mutableListOf<String>()

        fields.add("8=$beginString")
        fields.add("9=150")
        fields.add("35=8")
        fields.add("49=TARGET")
        fields.add("56=SENDER")
        fields.add("34=1")
        fields.add("52=20240101-12:00:00")

        if (version.isFix50Plus && version.applVerID != null) {
            fields.add("1128=${version.applVerID}")
        }

        fields.add("37=EXEC123")
        fields.add("11=ORDER123")
        fields.add("17=EXECID1")
        fields.add("150=0")
        fields.add("39=0")
        fields.add("55=AAPL")
        fields.add("54=1")
        fields.add("38=100")
        fields.add("44=150.00")
        fields.add("14=0")
        fields.add("151=100")
        fields.add("10=000")

        return fields.joinToString("\u0001") + "\u0001"
    }
}
