package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
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
 * Tests for FixMessageValidator: the editor's linter judges a message's **content** against the
 * dictionary, not its wire framing.
 *
 * It used to parse the draft as a wire frame, which requires 8, 9, 35 as the first three tags and a
 * BodyLength and CheckSum that agree with the bytes — none of which an editor draft has, because
 * FixTool computes them at send time. Every draft failed, always, on framing rather than on anything
 * the author wrote.
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

    /**
     * The same body validates with or without framing. The wire-frame variant here carries a
     * deliberately wrong BodyLength and CheckSum — the values FixTool recomputes at send time — and
     * that must not be what the editor's linter reports on.
     */
    @Test
    fun `framing does not decide validity - only content does`() {
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)
        if (!dictionary.isLoaded()) return

        val body = "35=D|11=ORD123|55=EUR/USD|54=1|38=1000000|40=2|"
        val framedWithBogusLengthAndChecksum =
            "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
                "11=ORD123|55=EUR/USD|54=1|38=1000000|40=2|10=000|"

        assertTrue(
            FixMessageValidator.validate(body, dictionary).isValid,
            "a bare body should validate: ${FixMessageValidator.validate(body, dictionary).errors}",
        )
        assertTrue(
            FixMessageValidator.validate(framedWithBogusLengthAndChecksum, dictionary).isValid,
            "framing noise should not fail a valid body",
        )
    }

    /**
     * The verdict is a **content** verdict — that is July's decision and it stands (the editor's stale
     * frame is recomputed at send). But a frame that *disagrees with its own bytes* must be **said**:
     * POST /validate and fixtool_validate are handed captured frames to judge, and answering a corrupted
     * capture with a bare `isValid: true` told the operator a message a real FIX engine would discard as
     * garbled is well-formed. The frame speaks in warnings; the verdict stays about the content.
     */
    @Test
    fun `a frame that disagrees with its own bytes is said, while the verdict stays about content`() {
        val dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)
        if (!dictionary.isLoaded()) return

        val body = "35=0|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|"
        val soh = "\u0001"
        val framedBody = body.replace("|", soh)
        val head = "8=FIX.4.4${soh}9=${framedBody.length}$soh"
        val checksum = (head + framedBody).toByteArray(Charsets.ISO_8859_1).sumOf { it.toInt() and 0xFF } % 256

        val intact = "8=FIX.4.4|9=${framedBody.length}|${body}10=%03d|".format(checksum)
        val garbled = "8=FIX.4.4|9=${framedBody.length}|${body}10=%03d|".format((checksum + 7) % 256)

        val good = FixMessageValidator.validate(intact, dictionary)
        assertTrue(good.isValid, "an intact frame validates: ${good.errors}")
        assertTrue(good.warnings.isEmpty(), "and its frame has nothing to warn about: ${good.warnings}")

        val bad = FixMessageValidator.validate(garbled, dictionary)
        assertTrue(bad.isValid, "the content verdict is unchanged — the body is fine")
        assertTrue(
            bad.warnings.any { it.contains("CheckSum(10)") },
            "but the frame's disagreement with its own bytes must be said: ${bad.warnings}",
        )

        val draft = FixMessageValidator.validate("35=D|11=ORD123|55=EUR/USD|54=1|38=1000000|40=2|", dictionary)
        assertTrue(draft.warnings.isEmpty(), "a draft carries no frame, so there is nothing to hold it to")
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
        val invalidMessage =
            "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
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

    /**
     * What the message editor holds is a body, not a framed message — FixTool computes BodyLength(9)
     * and CheckSum(10) at send time. Validating it as a wire frame rejected every draft with "Header
     * fields out of order", which kept the editor's linter permanently lit. It validates content now.
     */
    @Test
    fun `an editor draft with no wire framing is judged on its content`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val draft = "35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=2|44=1.05|59=0|60=20250101-12:00:00|"

        val result = FixMessageValidator.validate(draft, dictionary)

        assertTrue(result.isValid, "a well-formed draft should validate. Errors: ${result.errors}")
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `a draft missing a required field names the tag`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val missingTransactTime = "35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=2|"

        val result = FixMessageValidator.validate(missingTransactTime, dictionary)

        assertFalse(result.isValid)
        assertTrue(result.errors.single().contains("60"), "should name TransactTime(60): ${result.errors}")
    }

    @Test
    fun `a draft carrying a tag the message type does not define is reported`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        // ExecType(150) belongs to ExecutionReport, not NewOrderSingle.
        val draft = "35=D|11=ORD-1|55=EUR/USD|54=1|38=100|40=1|60=20250101-12:00:00|150=2|"

        val result = FixMessageValidator.validate(draft, dictionary)

        assertFalse(result.isValid)
        assertTrue(result.errors.single().contains("150"), "should name ExecType(150): ${result.errors}")
    }

    @Test
    fun `a draft with a value outside the dictionary's enum is reported`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val badSide = "35=D|11=ORD-1|55=EUR/USD|54=Z|38=100|40=1|60=20250101-12:00:00|"

        val result = FixMessageValidator.validate(badSide, dictionary)

        assertFalse(result.isValid)
        assertTrue(result.errors.single().contains("54"), "should name Side(54): ${result.errors}")
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
        val invalidMessage =
            "8=FIX.4.4|9=100|35=D|49=SENDER|56=TARGET|34=1|52=20250101-12:00:00|" +
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
