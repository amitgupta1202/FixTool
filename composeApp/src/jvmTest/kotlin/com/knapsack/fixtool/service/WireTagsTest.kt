package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One tag out of a wire string, on the I/O thread, without a parse. Shared by the latency tracker and the matcher. */
class WireTagsTest {
    @Test
    fun `reads a value with either delimiter and never mistakes a longer tag for a shorter one`() {
        assertEquals("ORD-1", WireTags.tagValue("8=FIX.4.4|35=D|111=x|11=ORD-1|55=EUR/USD|", 11))
        assertEquals("ORD-1", WireTags.tagValue("8=FIX.4.435=D111=x11=ORD-1", 11))
        assertEquals("x", WireTags.tagValue("8=FIX.4.4|111=x|11=ORD-1|", 111))
        assertEquals("D", WireTags.msgType("8=FIX.4.4|35=D|11=ORD-1|"))
    }

    @Test
    fun `an absent or empty tag is null`() {
        assertNull(WireTags.tagValue("8=FIX.4.4|35=D|", 11))
        assertNull(WireTags.tagValue("8=FIX.4.4|35=D|11=|55=EUR/USD|", 11))
        assertNull(WireTags.msgType("8=FIX.4.4|11=X|"))
    }

    @Test
    fun `session-level types are admin and application types are not`() {
        listOf("0", "1", "2", "3", "4", "5", "A").forEach { assertTrue(WireTags.isAdmin(it), it) }
        listOf("D", "8", "R", "S", "j", "AE").forEach { assertFalse(WireTags.isAdmin(it), it) }
    }
}
