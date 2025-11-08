package com.knapsack.fixtool

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.knapsack.fixtool.ui.App
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JOptionPane
import javax.swing.JOptionPane.ERROR_MESSAGE

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
            JOptionPane.showMessageDialog(
                null,
                "An unexpected error occurred:\n${throwable.message ?: throwable.toString()}\n\nThe application may continue but might be in an unstable state.\nPlease check the logs for details.",
                "Unexpected Error",
                ERROR_MESSAGE,
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
            val focusRequester = remember { FocusRequester() }

            // Fix for Compose Multiplatform issue #4803: Window focus requires multiple clicks
            // When window gains focus, automatically request focus on compose content
            // This prevents users from having to click multiple times on toolbar buttons
            LaunchedEffect(Unit) {
                val window = Window.getWindows().firstOrNull()
                window?.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowActivated(ignored: WindowEvent?) {
                            try {
                                focusRequester.requestFocus()
                                // Additional delayed focus request for reliability on Windows/Mac
                                launch {
                                    delay(100)
                                    focusRequester.requestFocus()
                                }
                            } catch (e: IllegalStateException) {
                                // Ignore if focus requester is not yet initialized
                                logger.warn("Uncaught exception while focusing request", e)
                            }
                        }
                    },
                )
            }

            App(modifier = Modifier.focusRequester(focusRequester).focusable())
        }
    }
}
