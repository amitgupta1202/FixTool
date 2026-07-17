package com.knapsack.fixtool.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.io.File

/**
 * The embedded terminal, opened from the toolbar's Terminal button. It injects [controlUrl] into the
 * shell's environment so a `claude` run inside drives this FixTool over MCP with no port to configure.
 * Working directory defaults to the user's home (override with FIXTOOL_TERMINAL_CWD).
 */
@Composable
fun TerminalWindow(
    controlUrl: String,
    controlToken: String?,
    onClose: () -> Unit,
) {
    val workingDir =
        remember {
            System.getenv("FIXTOOL_TERMINAL_CWD")?.ifBlank { null }?.let(::File)
                ?: File(System.getProperty("user.home"))
        }
    Window(
        onCloseRequest = onClose,
        title = "Terminal — FixTool",
        state = rememberWindowState(size = DpSize(1000.dp, 640.dp)),
    ) {
        TerminalPanel(
            workingDir = workingDir,
            controlUrl = controlUrl,
            controlToken = controlToken,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
