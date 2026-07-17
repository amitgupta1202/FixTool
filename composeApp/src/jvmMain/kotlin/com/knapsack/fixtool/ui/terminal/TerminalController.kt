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

    // Minimized collapses the dock to its header, reclaiming the screen space *without* leaving
    // composition — the PTY (and any running claude session) stays alive, unlike closing. Restoring
    // brings the same session back. Kept here beside [visible] so it survives the terminal being moved
    // between layout call sites (see App.kt's movable terminal content).
    var minimized by mutableStateOf(false)
        private set

    fun toggle() {
        visible = !visible
        // Reopening always shows the body — a stale minimized flag would open to a header-only dock.
        if (visible) minimized = false
    }

    fun hide() {
        visible = false
        minimized = false
    }

    fun toggleMinimized() {
        minimized = !minimized
    }
}
