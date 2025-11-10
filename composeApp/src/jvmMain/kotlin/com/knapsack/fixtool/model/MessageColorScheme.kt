package com.knapsack.fixtool.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Defines a color scheme for FIX messages based on direction and message type.
 * Colors are specified as ARGB hex values (0xAARRGGBB format).
 */
@Serializable
data class MessageColorScheme(
    // Outgoing messages (sent from client)
    val outgoingBright: Long = 0xFF569CD6, // Blue - bright variant for odd rows
    val outgoingDull: Long = 0xFF4A7DAF, // Blue - dull variant for even rows

    // Incoming messages (received from server)
    val incomingBright: Long = 0xFF4EC9B0, // Teal/Cyan - bright variant for odd rows
    val incomingDull: Long = 0xFF3DA89F, // Teal/Cyan - dull variant for even rows

    // Rejection/Logout messages (special case for incoming)
    val rejectionBright: Long = 0xFFE06C75, // Red - bright variant for odd rows
    val rejectionDull: Long = 0xFFC55A64, // Red - dull variant for even rows
) {
    /**
     * Convert stored Long color value to Compose Color
     */
    fun getOutgoingColor(isBright: Boolean): Color =
        Color(if (isBright) outgoingBright else outgoingDull)

    fun getIncomingColor(isBright: Boolean): Color =
        Color(if (isBright) incomingBright else incomingDull)

    fun getRejectionColor(isBright: Boolean): Color =
        Color(if (isBright) rejectionBright else rejectionDull)

    /**
     * Get message color based on direction and rejection status
     */
    fun getMessageColor(direction: FixMessage.Direction, isRejection: Boolean, isBright: Boolean): Color =
        when {
            isRejection -> getRejectionColor(isBright)
            direction == FixMessage.Direction.OUTGOING -> getOutgoingColor(isBright)
            else -> getIncomingColor(isBright)
        }

    companion object {
        /**
         * Default color scheme matching the original hardcoded colors
         */
        fun default(): MessageColorScheme = MessageColorScheme()

        /**
         * Alternative color scheme examples
         */
        fun greenRed(): MessageColorScheme =
            MessageColorScheme(
                outgoingBright = 0xFF98C379, // Green
                outgoingDull = 0xFF7AA862,
                incomingBright = 0xFF569CD6, // Blue
                incomingDull = 0xFF4A7DAF,
                rejectionBright = 0xFFE06C75, // Red (same)
                rejectionDull = 0xFFC55A64,
            )

        fun highContrast(): MessageColorScheme =
            MessageColorScheme(
                outgoingBright = 0xFFFF8C00, // Orange - bright
                outgoingDull = 0xFFE67E00, // Orange - dull
                incomingBright = 0xFF9370DB, // Purple - bright
                incomingDull = 0xFF7B5DBB, // Purple - dull
                rejectionBright = 0xFFE06C75, // Red (same)
                rejectionDull = 0xFFC55A64,
            )

        fun monochrome(): MessageColorScheme =
            MessageColorScheme(
                outgoingBright = 0xFFFFFFFF, // White
                outgoingDull = 0xFFF0F0F0, // Slightly off-white for dull variant
                incomingBright = 0xFFFFFFFF, // White
                incomingDull = 0xFFF0F0F0, // Slightly off-white for dull variant
                rejectionBright = 0xFFE06C75, // Red (only colored exception)
                rejectionDull = 0xFFC55A64,
            )
    }
}
