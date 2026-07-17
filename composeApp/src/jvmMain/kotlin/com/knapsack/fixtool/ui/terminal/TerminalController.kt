package com.knapsack.fixtool.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared show/hide state for the embedded terminal, so the toolbar button (which lives in the main
 * window's composition) and the window host (the application scope in main.kt) both read and write one
 * source of truth. It's Compose snapshot state, so flipping [visible] recomposes both the button tint
 * and the window.
 */
object TerminalController {
    var visible by mutableStateOf(false)
        private set

    fun toggle() {
        visible = !visible
    }

    fun hide() {
        visible = false
    }
}
