package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    // Single-session pass-through
    // ========================================

    @Test
    fun `sessionCount 1 returns the config untouched`() {
        val config = FixConnectionConfig(senderCompID = "LOADGEN{n}", targetCompID = "MDGW")
        assertSame(config, SessionIdentityResolver.resolve(config, 1, 1))
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
