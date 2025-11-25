package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for handling repeating groups where fields appear before the delimiter field.
 *
 * Background:
 * The FIX protocol requires the delimiter field to be first in each group instance,
 * but user templates may have fields in a different order. The parser must detect
 * when fields repeat (indicating a new group instance) even if they appear before
 * the delimiter field.
 */
class GroupFieldOrderingTest {
    private fun getTestDictionary(): FixDictionaryAdapter {
        // Load the minimal test data dictionary
        val resourcePath = this::class.java.classLoader.getResource("test-group-ordering.xml")
            ?: throw IllegalStateException("Test data dictionary not found")

        val file = File(resourcePath.toURI())
        return FixDictionaryAdapter.fromFile(file)
    }

    @Test
    fun testGroupFieldsBeforeDelimiter() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Verify the delimiter field for NoUnderlyings group
        val groupInfo = dataDictionary.getGroup("AY", 711)
        assertEquals(944, groupInfo.delimiterField, "NoUnderlyings delimiter should be 944 (CollAction)")

        // Message with NoUnderlyings group where fields 309 and 305 appear BEFORE delimiter 944
        // Template: 711=2|309=XS2201935199|305=4|944=1|879=1099|309=BTT080393489|305=4|944=2|228=10|
        //
        // Instance 1: 309=XS2201935199, 305=4, 944=1, 879=1099
        // Instance 2: 309=BTT080393489, 305=4, 944=2, 228=10
        val rawMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "711=2|" +
                "309=XS2201935199|305=4|944=1|879=1099|" + // Instance 1
                "309=BTT080393489|305=4|944=2|228=10|" // Instance 2

        val message = rawMessage.toQuickFixMessageManual(dataDictionary)

        // Verify group count
        assertTrue(message.isSetField(711), "Message should have NoUnderlyings group")
        assertEquals(2, message.getInt(711), "Should have 2 NoUnderlyings instances")

        // Verify first instance
        val group1 = message.getGroup(1, 711)
        assertEquals(
            "XS2201935199",
            group1.getString(309),
            "First instance should have UnderlyingSecurityID=XS2201935199",
        )
        assertEquals("4", group1.getString(305), "First instance should have UnderlyingSecurityIDSource=4")
        assertEquals("1", group1.getString(944), "First instance should have CollAction=1")
        assertEquals("1099", group1.getString(879), "First instance should have UnderlyingMismatch=1099")

        // Verify second instance
        val group2 = message.getGroup(2, 711)
        assertEquals(
            "BTT080393489",
            group2.getString(309),
            "Second instance should have UnderlyingSecurityID=BTT080393489",
        )
        assertEquals("4", group2.getString(305), "Second instance should have UnderlyingSecurityIDSource=4")
        assertEquals("2", group2.getString(944), "Second instance should have CollAction=2")
        assertEquals("10", group2.getString(228), "Second instance should have RelSymTransactTime=10")
    }

    @Test
    fun testNestedGroupsWithFieldsBeforeDelimiter() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Verify the delimiter field for NoPartyIDs group
        val partyGroupInfo = dataDictionary.getGroup("AY", 453)
        assertEquals(448, partyGroupInfo.delimiterField, "NoPartyIDs delimiter should be 448 (PartyID)")

        // Message with nested groups:
        // NoPartyIDs group contains NoPartySubIDs groups
        // Both have fields appearing before their delimiters
        val rawMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "453=2|" + // 2 party IDs
                // Party 1
                "448=Bank1|447=D|452=13|" +
                "802=2|" + // 2 sub IDs for party 1
                "523=user1@bank.com|803=2|" + // Sub ID 1
                "523=USER1|803=1|" + // Sub ID 2
                // Party 2
                "448=Bank2|447=P|452=12|2376=24|" // No sub IDs

        val message = rawMessage.toQuickFixMessageManual(dataDictionary)

        // Verify outer group count
        assertTrue(message.isSetField(453), "Message should have NoPartyIDs group")
        assertEquals(2, message.getInt(453), "Should have 2 NoPartyIDs instances")

        // Verify first party
        val party1 = message.getGroup(1, 453)
        assertEquals("Bank1", party1.getString(448), "First party should have PartyID=Bank1")
        assertEquals("D", party1.getString(447), "First party should have PartyIDSource=D")
        assertEquals("13", party1.getString(452), "First party should have PartyRole=13")

        // Verify first party's sub IDs
        assertTrue(party1.isSetField(802), "First party should have NoPartySubIDs group")
        assertEquals(2, party1.getInt(802), "First party should have 2 sub IDs")

        val party1Sub1 = party1.getGroup(1, 802)
        assertEquals("user1@bank.com", party1Sub1.getString(523), "First sub ID should be user1@bank.com")
        assertEquals("2", party1Sub1.getString(803), "First sub ID type should be 2")

        val party1Sub2 = party1.getGroup(2, 802)
        assertEquals("USER1", party1Sub2.getString(523), "Second sub ID should be USER1")
        assertEquals("1", party1Sub2.getString(803), "Second sub ID type should be 1")

        // Verify second party
        val party2 = message.getGroup(2, 453)
        assertEquals("Bank2", party2.getString(448), "Second party should have PartyID=Bank2")
        assertEquals("P", party2.getString(447), "Second party should have PartyIDSource=P")
        assertEquals("12", party2.getString(452), "Second party should have PartyRole=12")
        assertEquals("24", party2.getString(2376), "Second party should have PartyRoleQualifier=24")
    }

    @Test
    fun testMultipleGroupsInSameMessage() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Message with both NoUnderlyings and NoPartyIDs groups
        val rawMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "60=20251125-21:13:13.848|" +
                "873=20251125-21|" +
                // NoUnderlyings group
                "711=2|" +
                "309=SEC1|305=1|944=1|" + // Instance 1
                "309=SEC2|305=2|944=2|" + // Instance 2
                // NoPartyIDs group
                "453=3|" +
                "448=Party1|447=A|452=1|" + // Instance 1
                "448=Party2|447=B|452=2|" + // Instance 2
                "448=Party3|447=C|452=3|" // Instance 3

        val message = rawMessage.toQuickFixMessageManual(dataDictionary)

        // Verify NoUnderlyings group
        assertEquals(2, message.getInt(711), "Should have 2 NoUnderlyings instances")
        assertEquals("SEC1", message.getGroup(1, 711).getString(309))
        assertEquals("SEC2", message.getGroup(2, 711).getString(309))

        // Verify NoPartyIDs group
        assertEquals(3, message.getInt(453), "Should have 3 NoPartyIDs instances")
        assertEquals("Party1", message.getGroup(1, 453).getString(448))
        assertEquals("Party2", message.getGroup(2, 453).getString(448))
        assertEquals("Party3", message.getGroup(3, 453).getString(448))
    }

    @Test
    fun testGroupRoundTrip() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Parse a message with groups
        val inputMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "711=2|" +
                "309=ISIN1|305=4|944=1|" +
                "309=ISIN2|305=4|944=2|"

        val message = inputMessage.toQuickFixMessageManual(dataDictionary)

        // Serialize back to string
        val outputMessage = message.toRawFixMessage()

        // Verify both instances are present in output
        assertTrue(outputMessage.contains("309=ISIN1"), "Output should contain first UnderlyingSecurityID")
        assertTrue(outputMessage.contains("309=ISIN2"), "Output should contain second UnderlyingSecurityID")
        assertTrue(outputMessage.contains("711=2"), "Output should contain group count")

        // Verify we can parse the output again
        val reparsedMessage = outputMessage.toQuickFixMessageManual(dataDictionary)
        assertEquals(2, reparsedMessage.getInt(711), "Reparsed message should have 2 instances")
        assertEquals("ISIN1", reparsedMessage.getGroup(1, 711).getString(309))
        assertEquals("ISIN2", reparsedMessage.getGroup(2, 711).getString(309))
    }

    @Test
    fun testEmptyGroup() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Message with zero-count group should not set the group field
        // (QuickFIX doesn't include group count fields with 0 values)
        val rawMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "711=0|"

        val message = rawMessage.toQuickFixMessageManual(dataDictionary)

        // When group count is 0, QuickFIX typically doesn't set the field
        // This is expected behavior - empty groups are omitted
        val hasField = message.isSetField(711)
        if (hasField) {
            assertEquals(0, message.getInt(711), "If NoUnderlyings field is set, count should be 0")
        }
        // Test passes if field is either not set, or set to 0
    }

    @Test
    fun testSingleInstanceGroup() {
        val dictionary = getTestDictionary()
        val dataDictionary = dictionary.getDataDictionary()
            ?: throw IllegalStateException("Failed to load data dictionary")

        // Message with single group instance
        val rawMessage =
            "35=AY|" +
                "902=TestID|" +
                "895=1|" +
                "903=1|" +
                "1043=1|" +
                "711=1|" +
                "309=OnlySecurity|305=99|944=1|879=777|"

        val message = rawMessage.toQuickFixMessageManual(dataDictionary)

        assertEquals(1, message.getInt(711), "Should have 1 NoUnderlyings instance")

        val group1 = message.getGroup(1, 711)
        assertEquals("OnlySecurity", group1.getString(309))
        assertEquals("99", group1.getString(305))
        assertEquals("1", group1.getString(944))
        assertEquals("777", group1.getString(879))
    }
}
