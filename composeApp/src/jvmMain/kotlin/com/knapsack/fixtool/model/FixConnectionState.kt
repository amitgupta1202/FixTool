package com.knapsack.fixtool.model

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

    fun getColor(): androidx.compose.ui.graphics.Color =
        when (this) {
            DISCONNECTED ->
                androidx.compose.ui.graphics
                    .Color(0xFF6A6A6A)
            CONNECTING ->
                androidx.compose.ui.graphics
                    .Color(0xFFFFA500)
            CONNECTED ->
                androidx.compose.ui.graphics
                    .Color(0xFF4EC9B0)
            LOGGED_ON ->
                androidx.compose.ui.graphics
                    .Color(0xFF4EC9B0)
            ERROR ->
                androidx.compose.ui.graphics
                    .Color(0xFFCE9178)
        }
}
