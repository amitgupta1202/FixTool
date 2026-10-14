package com.knapsack.fixtool.ui

import org.junit.Test
import quickfix.SessionID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which rows the editor leaves to QuickFIX/J, and so greys and keeps out of the preview and the send.
 *
 * It held SenderSubID, TargetSubID and the two location IDs back on every session, as if the engine wrote them on
 * every send. The engine writes one only when the session's own SessionID names it, so on every profile FixTool
 * builds the row was greyed, the preview left it out, and Send never sent it.
 */
class EditorManagedTagsTest {
    private val order =
        listOf(FixField("35", "D"), FixField("49", "CLI"), FixField("50", "DESK1"), FixField("11", "ORD-1"))

    @Test
    fun `on a session that names no SubID the SubID is the author's and goes in the message`() {
        val managed = FixField.managedTagsOn(SessionID("FIX.4.4", "CLI", "SRV"))

        assertFalse("50" in managed, "SenderSubID must not be greyed on a session that does not write it")
        assertEquals("35=D|50=DESK1|11=ORD-1|", buildPreviewMessage(order, managed))
    }

    @Test
    fun `on a session that names its SubID the engine writes it and the row is the engine's`() {
        val managed = FixField.managedTagsOn(SessionID("FIX.4.4", "CLI", "DESK1", "", "SRV", "", "", ""))

        assertTrue("50" in managed, "the session writes SenderSubID itself")
        assertEquals("35=D|11=ORD-1|", buildPreviewMessage(order, managed))
    }

    @Test
    fun `before a session exists only what the engine writes on every send is held back`() {
        assertEquals(setOf("8", "9", "10", "34", "49", "52", "56"), FixField.managedTagsOn(null))
    }
}
