package com.knapsack.fixtool.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The authored-vs-wire split for Send steps: an excluded field survives every round trip an author
 * puts it through, and reaches the venue on none of them.
 */
class SendFieldsTest {
    @Test
    fun `an excluded field parses back as excluded, and keeps its tag and value`() {
        val fields = SendFields.parse("35=D|11=ORD|#9303=1|")
        assertEquals(
            listOf(
                SendField(35, "D"),
                SendField(11, "ORD"),
                SendField(9303, "1", excluded = true),
            ),
            fields,
        )
    }

    @Test
    fun `parse and join round trip, exclusions included`() {
        val raw = "35=D|11=ORD|#9303=1|38=100|"
        assertEquals(raw, SendFields.join(SendFields.parse(raw)))
    }

    @Test
    fun `the wire view drops excluded fields`() {
        assertEquals("35=D|11=ORD|38=100|", SendFields.wire("35=D|11=ORD|#9303=1|38=100|"))
    }

    /**
     * The common case must be untouched — not merely equivalent. Parse-then-join normalizes at the
     * edges, and a message with no exclusions has no reason to be re-derived on its way to the venue.
     */
    @Test
    fun `a raw with nothing excluded goes to the wire byte-identical`() {
        val raw = "35=D|11=ORD|38=100|"
        assertSame(raw, SendFields.wire(raw))
    }

    @Test
    fun `a hash inside a value is not an exclusion marker`() {
        val fields = SendFields.parse("35=D|58=order #5 rejected|")
        assertEquals(listOf(SendField(35, "D"), SendField(58, "order #5 rejected")), fields)
        assertFalse(fields.any { it.excluded })
        assertEquals("35=D|58=order #5 rejected|", SendFields.wire("35=D|58=order #5 rejected|"))
    }

    /**
     * The safety property the `#` marker was chosen for: the wire parser every send path already funnels
     * through cannot read an excluded field, so a path that forgets to ask for [SendFields.wire] fails by
     * dropping the field rather than by sending it. `-9303` would have parsed as the integer -9303 and
     * survived; this is why the marker is not `-`.
     */
    @Test
    fun `the wire parser cannot read an excluded field, so a missed strip drops it rather than sending it`() {
        assertEquals(
            listOf(35 to "D", 11 to "ORD"),
            FixMessageHelper.parseFixMessage("35=D|11=ORD|#9303=1|"),
        )
    }

    @Test
    fun `a value carrying a pipe joins with SOH so the round trip stays exact`() {
        val fields = listOf(SendField(35, "D"), SendField(58, "filled|in full", excluded = true))
        val raw = SendFields.join(fields)
        assertTrue(raw.contains(FixMessageHelper.SOH))
        assertEquals(fields, SendFields.parse(raw))
    }

    @Test
    fun `excluding every field leaves an empty message rather than a malformed one`() {
        assertEquals("", SendFields.wire("#35=D|#11=ORD|"))
    }
}
