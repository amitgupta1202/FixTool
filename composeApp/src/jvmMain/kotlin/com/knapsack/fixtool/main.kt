package com.knapsack.fixtool

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.knapsack.fixtool.ui.App
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    // Create log directory before any logger is instantiated
    // This prevents logback initialization failures on first run
    val logDir = File(System.getProperty("user.home"), ".fixtool/logs")
    logDir.mkdirs()

    // Set up global uncaught exception handler
    val logger = LoggerFactory.getLogger("GlobalExceptionHandler")
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        logger.error("Uncaught exception in thread ${thread.name}", throwable)
        // Try to show error dialog if possible
        try {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "An unexpected error occurred:\n${throwable.message ?: throwable.toString()}\n\nThe application may continue but might be in an unstable state.\nPlease check the logs for details.",
                "Unexpected Error",
                javax.swing.JOptionPane.ERROR_MESSAGE,
            )
        } catch (e: Exception) {
            // If showing dialog fails, just log it
            logger.error("Failed to show error dialog", e)
        }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FixTool - FiX Message Viewer",
            state = WindowState(size = DpSize(1920.dp, 1080.dp)),
            resizable = true,
        ) {
            App()
        }
    }
}
