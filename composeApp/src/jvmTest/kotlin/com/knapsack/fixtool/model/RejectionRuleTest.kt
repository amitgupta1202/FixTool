package com.knapsack.fixtool.model

import org.junit.Test
import quickfix.Message
import quickfix.field.MsgType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RejectionRuleTest {
    private fun createQuickFixMessage(messageType: String): Message {
        val message = Message()
        message.header.setString(MsgType.FIELD, messageType)
        return message
    }

    private fun createQuickFixMessageWithField(messageType: String, tag: Int, value: String): Message {
        val message = createQuickFixMessage(messageType)
        message.setString(tag, value)
        return message
    }

    @Test
    fun testSimpleRuleMatchesMessageType() {
        val rule = RejectionRule(messageType = "3")
        val message = createQuickFixMessage("3")

        assertTrue(rule.matches(message, "3"))
    }

    @Test
    fun testSimpleRuleDoesNotMatchDifferentMessageType() {
        val rule = RejectionRule(messageType = "3")
        val message = createQuickFixMessage("D")

        assertFalse(rule.matches(message, "D"))
    }

    @Test
    fun testRuleWithAdditionalTagMatches() {
        val rule =
            RejectionRule(
                messageType = "AZ",
                additionalTag = 905,
                additionalValue = "3",
            )
        val message = createQuickFixMessageWithField("AZ", 905, "3")

        assertTrue(rule.matches(message, "AZ"))
    }

    @Test
    fun testRuleWithAdditionalTagDoesNotMatchWrongValue() {
        val rule =
            RejectionRule(
                messageType = "AZ",
                additionalTag = 905,
                additionalValue = "3",
            )
        val message = createQuickFixMessageWithField("AZ", 905, "0")

        assertFalse(rule.matches(message, "AZ"))
    }

    @Test
    fun testRuleWithAdditionalTagDoesNotMatchMissingTag() {
        val rule =
            RejectionRule(
                messageType = "AZ",
                additionalTag = 905,
                additionalValue = "3",
            )
        val message = createQuickFixMessage("AZ")

        assertFalse(rule.matches(message, "AZ"))
    }

    @Test
    fun testRuleWithAdditionalTagRequiresMessageTypeMatch() {
        val rule =
            RejectionRule(
                messageType = "AZ",
                additionalTag = 905,
                additionalValue = "3",
            )
        val message = createQuickFixMessageWithField("BH", 905, "3")

        assertFalse(rule.matches(message, "BH"))
    }

    @Test
    fun testDefaultRulesContainSessionReject() {
        val rules = RejectionRule.defaultRules()
        val sessionRejectRule = rules.find { it.messageType == "3" }

        assertTrue(sessionRejectRule != null)
        assertEquals("3", sessionRejectRule.messageType)
        assertEquals(null, sessionRejectRule.additionalTag)
    }

    @Test
    fun testDefaultRulesContainBusinessReject() {
        val rules = RejectionRule.defaultRules()
        val businessRejectRule = rules.find { it.messageType == "j" }

        assertTrue(businessRejectRule != null)
        assertEquals("j", businessRejectRule.messageType)
    }

    @Test
    fun testDefaultRulesContainOrderCancelReject() {
        val rules = RejectionRule.defaultRules()
        val cancelRejectRule = rules.find { it.messageType == "9" }

        assertTrue(cancelRejectRule != null)
        assertEquals("9", cancelRejectRule.messageType)
    }

    @Test
    fun testDefaultRulesContainCustomRejection() {
        val rules = RejectionRule.defaultRules()
        val customRule = rules.find { it.messageType == "AZ" && it.additionalTag == 905 }

        assertTrue(customRule != null)
        assertEquals("AZ", customRule.messageType)
        assertEquals(905, customRule.additionalTag)
        assertEquals("3", customRule.additionalValue)
    }

    @Test
    fun testDescriptionForSimpleRule() {
        val rule = RejectionRule(messageType = "3")
        assertEquals("35=3", rule.description())
    }

    @Test
    fun testDescriptionForComplexRule() {
        val rule =
            RejectionRule(
                messageType = "AZ",
                additionalTag = 905,
                additionalValue = "3",
            )
        assertEquals("35=AZ and 905=3", rule.description())
    }

    @Test
    fun testFixMessageUsesDefaultRulesWhenNotProvided() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=3|10=123|",
                messageType = "3",
                quickfixMessage = createQuickFixMessage("3"),
            )

        assertTrue(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageRecognizesSessionReject() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=3|10=123|",
                messageType = "3",
                quickfixMessage = createQuickFixMessage("3"),
            )

        assertTrue(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageRecognizesBusinessReject() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=j|10=123|",
                messageType = "j",
                quickfixMessage = createQuickFixMessage("j"),
            )

        assertTrue(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageRecognizesOrderCancelReject() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=9|10=123|",
                messageType = "9",
                quickfixMessage = createQuickFixMessage("9"),
            )

        assertTrue(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageRecognizesCustomRejection() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=AZ|905=3|10=123|",
                messageType = "AZ",
                quickfixMessage = createQuickFixMessageWithField("AZ", 905, "3"),
            )

        assertTrue(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageDoesNotMatchNormalMessage() {
        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=D|10=123|",
                messageType = "D",
                quickfixMessage = createQuickFixMessage("D"),
            )

        assertFalse(message.isRejectionOrLogout())
    }

    @Test
    fun testFixMessageWithCustomRulesOnly() {
        val customRules =
            listOf(
                RejectionRule(messageType = "X"),
                RejectionRule(messageType = "Y", additionalTag = 100, additionalValue = "1"),
            )

        val message =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=X|10=123|",
                messageType = "X",
                quickfixMessage = createQuickFixMessage("X"),
            )

        assertTrue(message.isRejectionOrLogout(customRules))

        // Should not match default rules when custom rules provided
        val sessionReject =
            FixMessage(
                timestamp = java.time.LocalDateTime.now(),
                direction = FixMessage.Direction.INCOMING,
                rawMessage = "8=FIX.4.2|9=100|35=3|10=123|",
                messageType = "3",
                quickfixMessage = createQuickFixMessage("3"),
            )

        assertFalse(sessionReject.isRejectionOrLogout(customRules))
    }

    @Test
    fun testRuleWithAdditionalTagSearchesHeader() {
        val rule =
            RejectionRule(
                messageType = "A",
                additionalTag = 49, // SenderCompID is in header
                additionalValue = "SENDER",
            )
        val message = Message()
        message.header.setString(MsgType.FIELD, "A")
        message.header.setString(49, "SENDER")

        assertTrue(rule.matches(message, "A"))
    }

    @Test
    fun testRuleWithAdditionalTagSearchesBody() {
        val rule =
            RejectionRule(
                messageType = "D",
                additionalTag = 11, // ClOrdID is in body
                additionalValue = "ORDER123",
            )
        val message = Message()
        message.header.setString(MsgType.FIELD, "D")
        message.setString(11, "ORDER123")

        assertTrue(rule.matches(message, "D"))
    }
}
