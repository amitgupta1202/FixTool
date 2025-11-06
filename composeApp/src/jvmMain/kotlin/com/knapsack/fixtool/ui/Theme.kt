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
     * Standard colors used throughout the application
     */
    object Colors {
        val background: Color = Color(0xFF1E1E1E)
        val surface: Color = Color(0xFF2B2B2B)
        val surfaceVariant: Color = Color(0xFF252525)

        val text: Color = Color(0xFFE0E0E0)
        val textSecondary: Color = Color(0xFFB0B0B0)
        val textTertiary: Color = Color(0xFF6A6A6A)

        val primary: Color = Color(0xFF4EC9B0)
        val accent: Color = Color(0xFFCE9178)

        val separator: Color = Separators.color
        val separatorHover: Color = Separators.hoverColor
    }
}
