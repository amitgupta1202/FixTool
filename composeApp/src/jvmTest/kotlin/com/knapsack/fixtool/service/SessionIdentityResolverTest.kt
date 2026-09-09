package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionIdentityResolverTest {
    // ========================================
    // Numbering patterns
    // ========================================

    @Test
    fun `n pattern expands to the slot number`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{n}", targetCompID = "MDGW")

        assertEquals("LOADGEN1", SessionIdentityResolver.resolve(config, 1, 3).senderCompID)
        assertEquals("LOADGEN3", SessionIdentityResolver.resolve(config, 3, 3).senderCompID)
    }

    @Test
    fun `nn pattern zero-pads the slot number`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{nn}", targetCompID = "MDGW")

        assertEquals("LOADGEN01", SessionIdentityResolver.resolve(config, 1, 12).senderCompID)
        assertEquals("LOADGEN12", SessionIdentityResolver.resolve(config, 12, 12).senderCompID)
    }

    @Test
    fun `pattern applies to all identity fields`() {
        val config =
            FixConnectionConfig(
                senderCompID = "SND{n}",
                targetCompID = "TGT{n}",
                username = "user{n}",
                password = "pw{n}",
            )

        val resolved = SessionIdentityResolver.resolve(config, 2, 3)
        assertEquals("SND2", resolved.senderCompID)
        assertEquals("TGT2", resolved.targetCompID)
        assertEquals("user2", resolved.username)
        assertEquals("pw2", resolved.password)
    }

    // ========================================
    // Comma-separated lists
    // ========================================

    @Test
    fun `list assigns the slot-th value with whitespace trimmed`() {
        val config = FixConnectionConfig(senderCompID = "ALPHA, BETA , GAMMA", targetCompID = "MDGW")

        assertEquals("ALPHA", SessionIdentityResolver.resolve(config, 1, 3).senderCompID)
        assertEquals("BETA", SessionIdentityResolver.resolve(config, 2, 3).senderCompID)
        assertEquals("GAMMA", SessionIdentityResolver.resolve(config, 3, 3).senderCompID)
    }

    @Test
    fun `single value is shared by all slots`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "MDGW", username = "user1")

        val resolved = SessionIdentityResolver.resolve(config, 2, 3)
        assertEquals("LOADGEN", resolved.senderCompID)
        assertEquals("user1", resolved.username)
    }

    // ========================================
    // Qualifier derivation
    // ========================================

    @Test
    fun `shared CompID pair derives a unique qualifier per slot`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "MDGW", sessionQualifier = "MD")

        assertEquals("MD-1", SessionIdentityResolver.resolve(config, 1, 3).sessionQualifier)
        assertEquals("MD-3", SessionIdentityResolver.resolve(config, 3, 3).sessionQualifier)
    }

    @Test
    fun `shared CompID pair with blank base derives S-numbered qualifiers`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "MDGW")

        assertEquals("S1", SessionIdentityResolver.resolve(config, 1, 2).sessionQualifier)
        assertEquals("S2", SessionIdentityResolver.resolve(config, 2, 2).sessionQualifier)
    }

    @Test
    fun `distinct CompID pairs keep the configured qualifier verbatim`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{n}", targetCompID = "MDGW", sessionQualifier = "QA")

        assertEquals("QA", SessionIdentityResolver.resolve(config, 1, 3).sessionQualifier)
        assertEquals("QA", SessionIdentityResolver.resolve(config, 3, 3).sessionQualifier)
    }

    @Test
    fun `distinct targets alone make the pair unique`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "GW_A,GW_B")

        val resolved = SessionIdentityResolver.resolve(config, 1, 2)
        assertEquals("GW_A", resolved.targetCompID)
        assertEquals("", resolved.sessionQualifier)
    }

    // ========================================
    // A single session is slot 1
    // ========================================

    @Test
    fun `one session renders the n pattern as slot 1`() {
        val config =
            FixConnectionConfig(
                senderCompID = "initiator_org_1_user_{n}@load.test",
                targetCompID = "MDGW",
                username = "initiator_org_1_user_{n}@load.test",
            )

        val resolved = SessionIdentityResolver.resolve(config, 1, 1)
        assertEquals("initiator_org_1_user_1@load.test", resolved.senderCompID)
        assertEquals("initiator_org_1_user_1@load.test", resolved.username)
    }

    @Test
    fun `one session renders slot 1 even when the caller passes slot 0`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{n}", targetCompID = "MDGW")

        assertEquals("LOADGEN1", SessionIdentityResolver.resolve(config, 0, 1).senderCompID)
    }

    @Test
    fun `one session zero-pads the nn pattern`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{nn}", targetCompID = "MDGW")

        assertEquals("LOADGEN01", SessionIdentityResolver.resolve(config, 1, 1).senderCompID)
    }

    @Test
    fun `one session returns a plain identity byte-identical`() {
        val config =
            FixConnectionConfig(
                senderCompID = "LOADGEN",
                targetCompID = "MDGW",
                username = " padded user ",
                password = "pw",
                sessionQualifier = "MD",
            )

        val resolved = SessionIdentityResolver.resolve(config, 1, 1)
        assertEquals(config.senderCompID, resolved.senderCompID)
        assertEquals(config.targetCompID, resolved.targetCompID)
        assertEquals(config.username, resolved.username)
        assertEquals(config.password, resolved.password)
        assertEquals(config.sessionQualifier, resolved.sessionQualifier)
    }

    @Test
    fun `one session keeps a comma in a value verbatim`() {
        val config =
            FixConnectionConfig(
                senderCompID = "LOADGEN",
                targetCompID = "MDGW",
                password = "pw,with,commas",
            )

        assertEquals("pw,with,commas", SessionIdentityResolver.resolve(config, 1, 1).password)
    }

    @Test
    fun `one session keeps the configured qualifier because no other slot can collide`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "MDGW", sessionQualifier = "MD")

        assertEquals("MD", SessionIdentityResolver.resolve(config, 1, 1).sessionQualifier)
    }

    @Test
    fun `one session with a blank qualifier is not given an S-numbered one`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN", targetCompID = "MDGW")

        assertEquals("", SessionIdentityResolver.resolve(config, 1, 1).sessionQualifier)
    }

    @Test
    fun `four sessions render the n pattern as 1 to 4`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{n}", targetCompID = "MDGW")

        assertEquals(
            listOf("LOADGEN1", "LOADGEN2", "LOADGEN3", "LOADGEN4"),
            (1..4).map { SessionIdentityResolver.resolve(config, it, 4).senderCompID },
        )
    }

    // ========================================
    // Validation
    // ========================================

    @Test
    fun `list size matching session count is valid`() {
        val config = FixConnectionConfig(senderCompID = "A,B,C", targetCompID = "MDGW")
        assertTrue(SessionIdentityResolver.validate(config, 3).isEmpty())
    }

    @Test
    fun `list size mismatch is reported per field`() {
        val config = FixConnectionConfig(senderCompID = "A,B", targetCompID = "MDGW", username = "u1,u2,u3,u4")

        val errors = SessionIdentityResolver.validate(config, 3)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.startsWith("SenderCompID") })
        assertTrue(errors.any { it.startsWith("Username") })
    }

    @Test
    fun `single session config is never reported invalid`() {
        val config = FixConnectionConfig(senderCompID = "A,B", targetCompID = "MDGW")
        assertTrue(SessionIdentityResolver.validate(config, 1).isEmpty())
    }

    @Test
    fun `single session config carrying an n pattern is valid`() {
        val config =
            FixConnectionConfig(
                senderCompID = "initiator_org_1_user_{n}@load.test",
                targetCompID = "MDGW",
                username = "initiator_org_1_user_{n}@load.test",
            )
        assertTrue(SessionIdentityResolver.validate(config, 1).isEmpty())
    }

    // ========================================
    // isPerSession
    // ========================================

    @Test
    fun `isPerSession detects lists and patterns`() {
        assertTrue(SessionIdentityResolver.isPerSession("A,B"))
        assertTrue(SessionIdentityResolver.isPerSession("LOADGEN{n}"))
        assertTrue(SessionIdentityResolver.isPerSession("LOADGEN{nn}"))
        assertFalse(SessionIdentityResolver.isPerSession("LOADGEN"))
        assertFalse(SessionIdentityResolver.isPerSession(""))
    }
}
