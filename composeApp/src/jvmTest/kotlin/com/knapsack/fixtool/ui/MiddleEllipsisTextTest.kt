package com.knapsack.fixtool.ui

import androidx.compose.ui.unit.Constraints
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The middle-ellipsis rule, without a renderer.**
 *
 * [middleEllipsis] takes its measuring function as a parameter, so these give it a fixed ten pixels per
 * character and read the answer as arithmetic. A candidate that keeps `kept` characters is `kept + 1`
 * characters long once the ellipsis is counted, which is `(kept + 1) * 10` pixels wide here.
 */
class MiddleEllipsisTextTest {
    private val tenPxPerChar: (String) -> Int = { it.length * 10 }

    @Test
    fun `a title that fits comes back untouched`() {
        assertEquals("RFQLG3", middleEllipsis("RFQLG3", 500, tenPxPerChar))
    }

    @Test
    fun `an unbounded width never truncates`() {
        val long = "RFQ Demo Venue ← RFQLG3"
        assertEquals(long, middleEllipsis(long, Constraints.Infinity, tenPxPerChar))
    }

    @Test
    fun `a venue title keeps three characters each side at seventy pixels`() {
        val shown = middleEllipsis("RFQ Demo Venue ← RFQLG3", 70, tenPxPerChar)

        assertEquals("RFQ…LG3", shown)
        assertTrue(shown.startsWith("RFQ"), "the head went missing from $shown")
        assertTrue(shown.endsWith("LG3"), "the tail went missing from $shown")
        assertTrue(shown.contains("…"), "$shown carries no ellipsis")
        assertEquals(7, shown.length)
    }

    @Test
    fun `a load client keeps the bracketed number that tells it from its siblings`() {
        val shown = middleEllipsis("RFQ Load Client [3]", 80, tenPxPerChar)

        assertEquals("RFQ …[3]", shown)
        assertTrue(shown.endsWith("[3]"), "the tail went missing from $shown")
    }

    @Test
    fun `a width with room for nothing else is the ellipsis on its own`() {
        assertEquals("…", middleEllipsis("RFQ Demo Venue ← RFQLG3", 10, tenPxPerChar))
    }

    @Test
    fun `a width too narrow even for the ellipsis is still the ellipsis`() {
        assertEquals("…", middleEllipsis("RFQ Demo Venue ← RFQLG3", 4, tenPxPerChar))
    }
}
