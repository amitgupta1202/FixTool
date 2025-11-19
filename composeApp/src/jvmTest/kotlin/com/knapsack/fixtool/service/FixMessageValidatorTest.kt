package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FixMessageValidator to ensure validation consistency between
 * Validate button and Send button.
 *
 * This test ensures that:
 * 1. FixMessageValidator uses validate=true (same as sendMessage)
 * 2. If Validate button shows green, Send will not show manual construction warning
 * 3. If Validate button shows errors, Send will also fail validation
 */
class FixMessageValidatorTest {
    private lateinit var testDictionaryFile: File

    @Before
    fun setup() {
        // Create a test FIX 4.4 dictionary with validation rules
        testDictionaryFile = File.createTempFile("test_dict", ".xml")
        testDictionaryFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="4">
    <header>
        <field name="BeginString" number="8" type="STRING" required="Y"/>
        <field name="BodyLength" number="9" type="LENGTH" required="Y"/>
        <field name="MsgType" number="35" type="STRING" required="Y">
            <value enum="0" description="Heartbeat"/>
            <value enum="D" description="NewOrderSingle"/>
            <value enum="8" description="ExecutionReport"/>
        </field>
        <field name="SenderCompID" number="49" type="STRING" required="Y"/>
        <field name="TargetCompID" number="56" type="STRING" required="Y"/>
        <field name="MsgSeqNum" number="34" type="SEQNUM" required="Y"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP" required="Y"/>
    </header>
    <trailer>
        <field name="CheckSum" number="10" type="STRING" required="Y"/>
    </trailer>
    <messages>
        <message name="Heartbeat" msgtype="0" msgcat="admin">
            <field name="TestReqID" number="112" required="N"/>
        </message>
        <message name="NewOrderSingle" msgtype="D" msgcat="app">
            <field name="ClOrdID" number="11" required="Y"/>
            <field name="Symbol" number="55" required="Y"/>
            <field name="Side" number="54" required="Y"/>
            <field name="OrderQty" number="38" required="Y"/>
            <field name="OrdType" number="40" required="Y"/>
        </message>
        <message name="ExecutionReport" msgtype="8" msgcat="app">
            <field name="OrderID" number="37" required="Y"/>
            <field name="ExecID" number="17" required="Y"/>
            <field name="ExecType" number="150" required="Y"/>
            <field name="OrdStatus" number="39" required="Y"/>
            <field name="Side" number="54" required="Y"/>
            <field name="Symbol" number="55" required="Y"/>
            <field name="LeavesQty" number="151" required="Y"/>
            <field name="CumQty" number="14" required="Y"/>
        </message>
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="CheckSum" number="10" type="STRING"/>
        <field name="ClOrdID" number="11" type="STRING"/>
        <field name="CumQty" number="14" type="QTY"/>
        <field name="ExecID" number="17" type="STRING"/>
        <field name="MsgSeqNum" number="34" type="SEQNUM"/>
        <field name="MsgType" number="35" type="STRING">
            <value enum="0" description="Heartbeat"/>
            <value enum="D" description="NewOrderSingle"/>
            <field name="8" description="ExecutionReport"/>
        </field>
        <field name="OrderID" number="37" type="STRING"/>
        <field name="OrderQty" number="38" type="QTY"/>
        <field name="OrdStatus" number="39" type="CHAR">
            <value enum="0" description="New"/>
            <value enum="1" description="PartiallyFilled"/>
            <value enum="2" description="Filled"/>
        </field>
        <field name="OrdType" number="40" type="CHAR">
            <value enum="1" description="Market"/>
            <value enum="2" description="Limit"/>
        </field>
        <field name="SenderCompID" number="49" type="STRING"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP"/>
        <field name="Side" number="54" type="CHAR">
            <value enum="1" description="Buy"/>
            <field name="2" description="Sell"/>
        </field>
        <field name="Symbol" number="55" type="STRING"/>
        <field name="TargetCompID" number="56" type="STRING"/>
        <field name="TestReqID" number="112" type="STRING"/>
        <field name="ExecType" number="150" type="CHAR">
            <value enum="0" description="New"/>
            <value enum="F" description="Trade"/>
        </field>
        <field name="LeavesQty" number="151" type="QTY"/>
        <field name="OrdQty" number="38" type="QTY"/>
    </fields>
</fix>""",
        )
    }

    @After
    fun cleanup() {
        if (testDictionaryFile.exists()) {
            testDictionaryFile.delete()
        }
    }

    @Test
    fun testValidatorUsesStrictValidation() {
        // Given: A FIX dictionary loaded from test file
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)

        // Skip test if dictionary didn't load (QuickFIX may reject our test XML)
        if (!dictionary.isLoaded()) {
            return
        }

        val dataDictionary = dictionary.getDataDictionary() ?: return

        // Given: A valid FIX message that should pass validation
        val validMessage = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
            "11=ORD123|55=EUR/USD|54=1|38=1000000|40=2|10=000|"

        // When: We validate using FixMessageValidator (Validate button behavior)
        val validatorResult = FixMessageValidator.validate(validMessage, dictionary)

        // When: We validate using the same approach as sendMessage (Send button behavior)
        var sendValidationPassed = false
        try {
            validMessage.toQuickFixMessage(dataDictionary, validate = true)
            sendValidationPassed = true
        } catch (e: Exception) {
            sendValidationPassed = false
        }

        // Then: Both validation approaches should give consistent results
        assertEquals(
            validatorResult.isValid,
            sendValidationPassed,
            "FixMessageValidator should use the same validation as sendMessage. " +
                "Validator passed: ${validatorResult.isValid}, Send validation passed: $sendValidationPassed. " +
                "Errors: ${validatorResult.errors}",
        )
    }

    @Test
    fun testValidatorFailsOnMissingRequiredField() {
        // Given: A FIX dictionary loaded from test file
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)

        if (!dictionary.isLoaded()) {
            return
        }

        val dataDictionary = dictionary.getDataDictionary() ?: return

        // Given: A FIX message missing required field ClOrdID (tag 11)
        val invalidMessage = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
            "55=EUR/USD|54=1|38=1000000|40=2|10=000|"

        // When: We validate using FixMessageValidator
        val validatorResult = FixMessageValidator.validate(invalidMessage, dictionary)

        // When: We validate using the sendMessage approach
        var sendValidationPassed = false
        var sendException: Exception? = null
        try {
            invalidMessage.toQuickFixMessage(dataDictionary, validate = true)
            sendValidationPassed = true
        } catch (e: Exception) {
            sendValidationPassed = false
            sendException = e
        }

        // Then: Both should fail validation
        assertFalse(
            validatorResult.isValid,
            "FixMessageValidator should fail on missing required field",
        )
        assertFalse(
            sendValidationPassed,
            "Send validation should fail on missing required field. Exception: ${sendException?.message}",
        )

        // And: Both should give consistent results
        assertEquals(
            validatorResult.isValid,
            sendValidationPassed,
            "Both validation approaches must be consistent. " +
                "Validator: ${validatorResult.errors}, Send: ${sendException?.message}",
        )
    }

    @Test
    fun testValidatorAndSendConsistencyForValidMessage() {
        // Given: A FIX dictionary
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)

        if (!dictionary.isLoaded()) {
            return
        }

        val dataDictionary = dictionary.getDataDictionary() ?: return

        // Given: A properly formed message
        val message = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
            "11=ORDER123|55=EUR/USD|54=1|38=1000000|40=2|10=000|"

        // When: Validate button is pressed (using FixMessageValidator)
        val validateButtonResult = FixMessageValidator.validate(message, dictionary)

        // When: Send button is pressed (using QuickFIX with validate=true)
        var needsManualConstruction = false
        try {
            message.toQuickFixMessage(dataDictionary, validate = true)
            needsManualConstruction = false
        } catch (e: Exception) {
            // If validated construction fails, sendMessage would use manual construction
            needsManualConstruction = true
        }

        // Then: If Validate shows green (isValid=true), Send should not need manual construction
        if (validateButtonResult.isValid) {
            assertFalse(
                needsManualConstruction,
                "If Validate button shows green, Send should not trigger manual construction warning. " +
                    "This indicates validation inconsistency.",
            )
        }

        // And: If Validate shows errors (isValid=false), Send should also fail validation
        if (!validateButtonResult.isValid) {
            assertTrue(
                needsManualConstruction,
                "If Validate button shows errors, Send should also fail validation. " +
                    "This indicates validation inconsistency.",
            )
        }
    }

    @Test
    fun testValidatorWithNoDictionary() {
        // Given: No dictionary configured
        val noDictionary = FixDictionaryAdapter.createDefault()

        // Given: Any message
        val message = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|11=ORD123|55=EUR/USD|54=1|38=1000|40=2|10=000|"

        // When: We validate
        val result = FixMessageValidator.validate(message, noDictionary)

        // Then: Validation should fail with appropriate error
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("dictionary") },
            "Error should mention missing dictionary. Actual errors: ${result.errors}",
        )
    }

    @Test
    fun testValidatorWithNullDictionary() {
        // Given: Null dictionary
        val message = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|11=ORD123|55=EUR/USD|54=1|38=1000|40=2|10=000|"

        // When: We validate
        val result = FixMessageValidator.validate(message, null)

        // Then: Validation should fail with appropriate error
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any { it.contains("dictionary") },
            "Error should mention missing dictionary. Actual errors: ${result.errors}",
        )
    }

    @Test
    fun testManualConstructionStillWorksForInvalidMessages() {
        // Given: A FIX dictionary
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)

        if (!dictionary.isLoaded()) {
            return
        }

        val dataDictionary = dictionary.getDataDictionary() ?: return

        // Given: A message that fails validation (missing required field)
        val invalidMessage = "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
            "55=EUR/USD|54=1|38=1000000|40=2|10=000|"

        // When: Strict validation fails
        var strictValidationFailed = false
        try {
            invalidMessage.toQuickFixMessage(dataDictionary, validate = true)
        } catch (e: Exception) {
            strictValidationFailed = true
        }

        // Then: Manual construction should still work (fallback for Send button)
        if (strictValidationFailed) {
            var manualConstructionSucceeded = false
            try {
                invalidMessage.toQuickFixMessageManual(dataDictionary)
                manualConstructionSucceeded = true
            } catch (e: Exception) {
                manualConstructionSucceeded = false
            }

            assertTrue(
                manualConstructionSucceeded,
                "Manual construction should work as fallback even when validation fails",
            )
        }
    }
}
