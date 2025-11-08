package com.knapsack.fixtool.model

import androidx.compose.ui.graphics.Color

enum class FixConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOGGED_ON,
    ERROR,
    ;

    fun canConnect(): Boolean = this == DISCONNECTED || this == ERROR

    fun canDisconnect(): Boolean = this == CONNECTING || this == CONNECTED || this == LOGGED_ON

    fun getDisplayText(): String =
        when (this) {
            DISCONNECTED -> "Disconnected"
            CONNECTING -> "Connecting..."
            CONNECTED -> "Connected"
            LOGGED_ON -> "Logged On"
            ERROR -> "Error"
        }

    fun getColor(): Color =
        when (this) {
            DISCONNECTED -> Color(0xFF6A6A6A)
            CONNECTING -> Color(0xFFFFA500)
            CONNECTED -> Color(0xFF7AD67A)
            LOGGED_ON -> Color(0xFF4EC9B0)
            ERROR -> Color(0xFFFF6B6B)
        }
}
