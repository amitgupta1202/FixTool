package com.knapsack.fixtool.ui

import org.junit.Test
import kotlin.test.assertEquals

/**
 * What the editor makes of a message pasted into its raw box.
 *
 * It split on `|` only, so a SOH-delimited line copied from a messages.log came back as one unreadable
 * field, and the editor cleared to a single blank row. It reads the delimiter the way the rest of the code
 * does now: SOH wins, so a pipe inside a wire value stays inside it.
 */
class EditorPasteTest {
    @Test
    fun `a pasted wire line fills the editor with its fields`() {
        val line = "8=FIX.4.4\u00019=65\u000135=D\u000111=ORD-1\u000155=IBM\u000154=1\u000138=100\u000140=1\u000110=123\u0001\n"

        val fields = parseRawMessageToFields(line)

        assertEquals(
            listOf("35" to "D", "11" to "ORD-1", "55" to "IBM", "54" to "1", "38" to "100", "40" to "1"),
            fields?.map { it.tag to it.value },
            "every application field, with the session-managed 8, 9 and 10 left to the engine",
        )
    }

    @Test
    fun `a pipe inside a pasted wire value stays inside it`() {
        val line = "35=3\u000145=2\u000158=Rejected|insufficient margin\u0001"

        val fields = parseRawMessageToFields(line)

        assertEquals(listOf("35" to "3", "45" to "2", "58" to "Rejected|insufficient margin"), fields?.map { it.tag to it.value })
    }

    @Test
    fun `a pipe-delimited paste still reads as it always did`() {
        assertEquals(listOf("35" to "D", "11" to "ORD-1"), parseRawMessageToFields("35=D|11=ORD-1|")?.map { it.tag to it.value })
    }
}
