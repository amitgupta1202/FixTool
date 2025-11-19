package com.knapsack.fixtool.model

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MessageColorSchemeTest {
    @Test
    fun testDefaultScheme() {
        val scheme = MessageColorScheme.default()

        // Outgoing should be blue
        assertEquals(Color(0xFF569CD6), scheme.getOutgoingColor(true))
        assertEquals(Color(0xFF4A7DAF), scheme.getOutgoingColor(false))

        // Incoming should be teal/cyan
        assertEquals(Color(0xFF4EC9B0), scheme.getIncomingColor(true))
        assertEquals(Color(0xFF3DA89F), scheme.getIncomingColor(false))

        // Rejection should be red
        assertEquals(Color(0xFFE06C75), scheme.getRejectionColor(true))
        assertEquals(Color(0xFFC55A64), scheme.getRejectionColor(false))
    }

    @Test
    fun testGreenRedScheme() {
        val scheme = MessageColorScheme.greenRed()

        // Outgoing should be green
        assertEquals(Color(0xFF98C379), scheme.getOutgoingColor(true))
        assertEquals(Color(0xFF7AA862), scheme.getOutgoingColor(false))

        // Incoming should be blue
        assertEquals(Color(0xFF569CD6), scheme.getIncomingColor(true))
        assertEquals(Color(0xFF4A7DAF), scheme.getIncomingColor(false))

        // Rejection should be red
        assertEquals(Color(0xFFE06C75), scheme.getRejectionColor(true))
        assertEquals(Color(0xFFC55A64), scheme.getRejectionColor(false))
    }

    @Test
    fun testHighContrastScheme() {
        val scheme = MessageColorScheme.highContrast()

        // Outgoing should be orange
        assertEquals(Color(0xFFFF8C00), scheme.getOutgoingColor(true))
        assertEquals(Color(0xFFE67E00), scheme.getOutgoingColor(false))

        // Incoming should be purple
        assertEquals(Color(0xFF9370DB), scheme.getIncomingColor(true))
        assertEquals(Color(0xFF7B5DBB), scheme.getIncomingColor(false))

        // Rejection should be red
        assertEquals(Color(0xFFE06C75), scheme.getRejectionColor(true))
        assertEquals(Color(0xFFC55A64), scheme.getRejectionColor(false))
    }

    @Test
    fun testMonochromeScheme() {
        val scheme = MessageColorScheme.monochrome()

        // Both outgoing and incoming should be white (or near-white)
        assertEquals(Color(0xFFFFFFFF), scheme.getOutgoingColor(true))
        assertEquals(Color(0xFFF0F0F0), scheme.getOutgoingColor(false))
        assertEquals(Color(0xFFFFFFFF), scheme.getIncomingColor(true))
        assertEquals(Color(0xFFF0F0F0), scheme.getIncomingColor(false))

        // Rejection should still be red (only colored exception)
        assertEquals(Color(0xFFE06C75), scheme.getRejectionColor(true))
        assertEquals(Color(0xFFC55A64), scheme.getRejectionColor(false))
    }

    @Test
    fun testGetMessageColorOutgoing() {
        val scheme = MessageColorScheme.default()

        val color =
            scheme.getMessageColor(
                direction = FixMessage.Direction.OUTGOING,
                isRejection = false,
                isBright = true,
            )

        // Should return outgoing color (blue)
        assertEquals(Color(0xFF569CD6), color)
    }

    @Test
    fun testGetMessageColorIncoming() {
        val scheme = MessageColorScheme.default()

        val color =
            scheme.getMessageColor(
                direction = FixMessage.Direction.INCOMING,
                isRejection = false,
                isBright = true,
            )

        // Should return incoming color (teal)
        assertEquals(Color(0xFF4EC9B0), color)
    }

    @Test
    fun testGetMessageColorRejection() {
        val scheme = MessageColorScheme.default()

        // Rejection should override direction
        val colorOutgoing =
            scheme.getMessageColor(
                direction = FixMessage.Direction.OUTGOING,
                isRejection = true,
                isBright = true,
            )

        val colorIncoming =
            scheme.getMessageColor(
                direction = FixMessage.Direction.INCOMING,
                isRejection = true,
                isBright = true,
            )

        // Both should return rejection color (red)
        assertEquals(Color(0xFFE06C75), colorOutgoing)
        assertEquals(Color(0xFFE06C75), colorIncoming)
    }

    @Test
    fun testBrightAndDullVariants() {
        val scheme = MessageColorScheme.default()

        // Bright and dull variants should be different
        assertNotEquals(
            scheme.getOutgoingColor(true),
            scheme.getOutgoingColor(false),
        )

        assertNotEquals(
            scheme.getIncomingColor(true),
            scheme.getIncomingColor(false),
        )

        assertNotEquals(
            scheme.getRejectionColor(true),
            scheme.getRejectionColor(false),
        )
    }

    @Test
    fun testMonochromeOnlyRedForErrors() {
        val scheme = MessageColorScheme.monochrome()

        // Outgoing normal message - should be white
        val outgoingNormal =
            scheme.getMessageColor(
                direction = FixMessage.Direction.OUTGOING,
                isRejection = false,
                isBright = true,
            )
        assertEquals(Color(0xFFFFFFFF), outgoingNormal)

        // Incoming normal message - should be white
        val incomingNormal =
            scheme.getMessageColor(
                direction = FixMessage.Direction.INCOMING,
                isRejection = false,
                isBright = true,
            )
        assertEquals(Color(0xFFFFFFFF), incomingNormal)

        // Rejection message - should be red
        val rejection =
            scheme.getMessageColor(
                direction = FixMessage.Direction.INCOMING,
                isRejection = true,
                isBright = true,
            )
        assertEquals(Color(0xFFE06C75), rejection)
    }

    @Test
    fun testHighContrastDistinctColors() {
        val scheme = MessageColorScheme.highContrast()

        val outgoing = scheme.getOutgoingColor(true)
        val incoming = scheme.getIncomingColor(true)
        val rejection = scheme.getRejectionColor(true)

        // All colors should be different
        assertNotEquals(outgoing, incoming)
        assertNotEquals(outgoing, rejection)
        assertNotEquals(incoming, rejection)
    }
}
