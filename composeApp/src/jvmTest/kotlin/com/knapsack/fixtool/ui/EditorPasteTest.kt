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

    /**
     * A paste dropped SenderSubID and TargetSubID as if QuickFIX/J wrote them on every send. It writes them only
     * when the session's own SessionID names them, and no profile gives a session one, so the order went out with
     * no desk on it. A paste has no session to ask, so it drops only what the engine writes on every send.
     */
    @Test
    fun `a pasted SenderSubID and TargetSubID stay in the editor`() {
        val line = "8=FIX.4.4|9=70|35=D|34=7|49=CLI|50=DESK1|52=20261014-09:30:00|56=SRV|57=VDESK|11=ORD-1|10=000|"

        val fields = parseRawMessageToFields(line)

        assertEquals(
            listOf("35" to "D", "50" to "DESK1", "57" to "VDESK", "11" to "ORD-1"),
            fields?.map { it.tag to it.value },
            "the SubIDs are the author's to send, and 8, 9, 10, 34, 49, 52 and 56 are still the engine's",
        )
    }
}
