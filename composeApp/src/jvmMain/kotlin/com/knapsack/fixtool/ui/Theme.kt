package com.knapsack.fixtool.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global theme constants for the FixTool application.
 * These values provide consistent styling across all UI components.
 */
object AppTheme {
    /**
     * Standard separator/divider dimensions
     */
    object Separators {
        /** Standard width for vertical panel separators (between major panels) */
        val panelSeparatorWidth: Dp = 1.dp

        /** Standard thickness for horizontal dividers (lines between sections) */
        val dividerThickness: Dp = 1.dp

        /** Standard color for separators and dividers */
        val color: Color = Color(0xFF3A3A3A)

        /** Color for separators on hover */
        val hoverColor: Color = Color(0xFF4A4A4A)
    }

    /**
     * Standard spacing values
     */
    object Spacing {
        /** Extra small spacing (2dp) - between closely related items */
        val extraSmall: Dp = 2.dp

        /** Small spacing (4dp) - standard spacing between UI elements */
        val small: Dp = 4.dp

        /** Medium spacing (8dp) - spacing between groups */
        val medium: Dp = 8.dp

        /** Large spacing (12dp) - spacing between major sections */
        val large: Dp = 12.dp

        /** Extra large spacing (16dp) - padding for containers */
        val extraLarge: Dp = 16.dp
    }

    /**
     * Comprehensive color palette organized by semantic meaning
     */
    object Colors {
        // ========== Background Colors ==========
        /** Main application background - darkest level */
        val background = Color(0xFF1E1E1E)

        /** Panel and header backgrounds - medium dark level */
        val surface = Color(0xFF2B2B2B)

        /** Input fields and nested panel backgrounds - lighter dark level */
        val surfaceVariant = Color(0xFF252525)

        /** Even darker header/section backgrounds */
        val surfaceHeader = Color(0xFF202020)

        // ========== Text Colors ==========
        /** Primary text - highest contrast */
        val text = Color(0xFFE0E0E0)

        /** Secondary text - labels, inactive elements */
        val textSecondary = Color(0xFFB0B0B0)

        /** Tertiary text - disabled, placeholders */
        val textDisabled = Color(0xFF6A6A6A)

        // ========== Border & Separator Colors ==========
        /** Standard borders and separators */
        val border = Color(0xFF3A3A3A)

        /** Darker borders for specific use cases */
        val borderDark = Color(0xFF555555)

        /** Separator color (alias for consistency) */
        val separator = Separators.color

        /** Separator hover state */
        val separatorHover = Separators.hoverColor

        // ========== State Colors ==========
        /** Primary accent color - active states, links, focus */
        val primary = Color(0xFF4EC9B0)

        /** Success/positive state */
        val success = Color(0xFF98C379)

        /** Warning state */
        val warning = Color(0xFFCE9178)

        /** Error/destructive state */
        val error = Color(0xFFE06C75)

        /** Info accent */
        val info = Color(0xFF61AFEF)

        // ========== Message Direction Colors ==========
        /** Incoming messages - bright */
        val messageIncoming = Color(0xFF4EC9B0)

        /** Incoming messages - dull */
        val messageIncomingDull = Color(0xFF2B7A7A)

        /** Outgoing messages - bright */
        val messageOutgoing = Color(0xFF569CD6)

        /** Outgoing messages - dull */
        val messageOutgoingDull = Color(0xFF3A5A8A)

        /** Rejected messages - bright */
        val messageRejection = Color(0xFFE06C75)

        /** Rejected messages - dull */
        val messageRejectionDull = Color(0xFF8A4A4A)

        /** Recently sent message highlight - gold/amber tint overlay */
        val messageRecentlySent = Color(0x40FFA500) // 25% opacity orange/gold

        // ========== Selection & Highlight Colors ==========
        /** Selected row/item background */
        val selectionPrimary = Color(0xFF2D5A8C)

        /** Multi-selection secondary */
        val selectionSecondary = Color(0xFF1E4A6B)

        /** Text selection background - visible blue tint for selected text */
        val textSelectionBackground = Color(0xFF3D5A80)

        /** Text selection handle color */
        val textSelectionHandle = Color(0xFF4EC9B0)

        /** Current match highlight */
        val highlightCurrent = Color(0xFFFFAA00)

        /** Other matches highlight */
        val highlightOther = Color(0xFF4A4A00)

        /** Empty/blank field background highlight */
        val emptyFieldBackground = Color(0xFF3A3520) // Muted yellow-brown for dark theme

        // ========== Field & Tag Colors ==========
        /** Field names/labels */
        val fieldName = Color(0xFF4EC9B0)

        /** Field values */
        val fieldValue = Color(0xFF9CDCFE)

        /** Tag numbers */
        val tagNumber = Color(0xFFDCDCAA)

        /** Group instance numbers */
        val groupInstance = Color(0xFF9CDCFE)

        /** Group tags/headers */
        val groupTag = Color(0xFFFFAA00)

        // ========== Username Colors (for search results) ==========
        /** Username color palette - 6 distinct colors for different users */
        val usernameColors =
            listOf(
                Color(0xFF4EC9B0), // Teal
                Color(0xFF569CD6), // Blue
                Color(0xFFCE9178), // Orange
                Color(0xFFC586C0), // Purple
                Color(0xFFDCDCAA), // Yellow
                Color(0xFF98C379), // Green
            )

        // ========== Notification Colors ==========
        /** Success notification border */
        val notificationSuccessBorder = success

        /** Success notification background */
        val notificationSuccessBackground = Color(0xFF1E3A1E)

        /** Error notification border */
        val notificationErrorBorder = error

        /** Error notification background */
        val notificationErrorBackground = Color(0xFF3A1E1E)

        /**
         * A message the run's post-mortem marked: evidence, not a verdict.
         *
         * Neither green nor red on purpose. Green would say a step passed on a message no step ever bound —
         * and for the diagnosis that reports a stray which *would* have satisfied an expectation the run
         * never reached, that is precisely the false green the assertion model is built to make impossible.
         * Red would blame the message for a failure that happened somewhere else.
         */
        val diagnosisBackground = Color(0xFF3A331E)

        /** Info notification border */
        val notificationInfoBorder = info

        /** Info notification background */
        val notificationInfoBackground = Color(0xFF1E2A3A)

        // ========== Scrollbar Colors ==========
        /** Default scrollbar thumb */
        val scrollbar = Color(0xFF6A6A6A)

        /** Scrollbar thumb on hover */
        val scrollbarHover = Color(0xFF8A8A8A)
    }

    /**
     * Helper functions for conditional colors
     */
    object Helpers {
        /** Returns primary color if active, otherwise textSecondary */
        fun activeColor(isActive: Boolean): Color = if (isActive) Colors.primary else Colors.textSecondary

        /** Returns textSecondary if enabled, otherwise textDisabled */
        fun enabledColor(isEnabled: Boolean): Color = if (isEnabled) Colors.textSecondary else Colors.textDisabled

        /** Returns appropriate checkbox background color */
        fun checkboxBackground(isChecked: Boolean): Color = if (isChecked) Colors.primary else Colors.surface

        /** Returns appropriate checkbox border color */
        fun checkboxBorder(isChecked: Boolean): Color = if (isChecked) Colors.primary else Colors.textDisabled
    }
}
