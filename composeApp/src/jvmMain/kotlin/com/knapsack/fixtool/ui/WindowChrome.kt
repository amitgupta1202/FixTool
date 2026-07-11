package com.knapsack.fixtool.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF3A3A3A),
        secondary = Color(0xFF2B2B2B),
        background = Color(0xFF1E1E1E),
        surface = Color(0xFF2B2B2B),
        onPrimary = Color(0xFFE0E0E0),
        onSecondary = Color(0xFFE0E0E0),
        onBackground = Color(0xFFE0E0E0),
        onSurface = Color(0xFFE0E0E0),
    )

/**
 * The dark theme + text-selection + scrollbar chrome shared by every FixTool window. A secondary
 * `Window` (e.g. the Scenarios workbench) is a separate composition that inherits none of the main
 * window's locals, so each window applies this once at its root.
 */
@Composable
fun FixToolWindowChrome(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme) {
        val textSelectionColors =
            TextSelectionColors(
                handleColor = AppTheme.Colors.textSelectionHandle,
                backgroundColor = AppTheme.Colors.textSelectionBackground,
            )
        val scrollbarStyle =
            ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = AppTheme.Colors.scrollbar,
                hoverColor = AppTheme.Colors.scrollbarHover,
            )
        CompositionLocalProvider(
            LocalTextSelectionColors provides textSelectionColors,
            LocalScrollbarStyle provides scrollbarStyle,
            content = content,
        )
    }
}
