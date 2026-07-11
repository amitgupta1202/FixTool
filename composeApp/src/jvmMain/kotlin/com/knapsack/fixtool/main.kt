package com.knapsack.fixtool

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.knapsack.fixtool.control.ControlServerLauncher
import com.knapsack.fixtool.ui.App
import com.knapsack.fixtool.ui.ScenarioWorkbenchWindow
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

    // Track if we're closing to avoid duplicate cleanup. Declared outside application {} so the
    // close handler keeps one instance across recompositions of the application scope.
    var isClosing = false

    application {
        // Compose state so secondary windows (the Scenarios workbench) can react to viewmodel flows.
        var viewModelRef by remember {
            mutableStateOf<com.knapsack.fixtool.viewmodel.FixMessageViewModel?>(null)
        }

        Window(
            onCloseRequest = {
                if (!isClosing) {
                    isClosing = true
                    logger.info("Window close requested, disconnecting all sessions...")

                    // Disconnect all sessions synchronously before exit
                    try {
                        ControlServerLauncher.stop()
                        viewModelRef?.disconnectAllSessions()
                        // Give logout messages time to be sent
                        Thread.sleep(1000)
                    } catch (e: Exception) {
                        logger.error("Error during disconnect on close", e)
                    }
                }
                exitApplication()
            },
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

            App(
                modifier = Modifier.focusRequester(focusRequester).focusable(),
                onViewModelCreated = { viewModel ->
                    viewModelRef = viewModel
                    // Automation control surface: env var FIXTOOL_CONTROL_PORT overrides, otherwise
                    // driven by the Settings toggle. Apply the initial state and react to changes.
                    val windowProvider = { java.awt.Window.getWindows().firstOrNull() }
                    val settings = viewModel.appSettings
                    ControlServerLauncher.apply(
                        viewModel,
                        windowProvider,
                        settings.automationControlEnabled,
                        settings.automationControlPort,
                    )
                    viewModel.automationControlHook = { enabled, port ->
                        ControlServerLauncher.apply(viewModel, windowProvider, enabled, port)
                    }
                },
            )
        }

        // Scenarios workbench: a real (non-modal) window so the live session view stays usable
        // while capturing/editing. Bound to the same flow the toolbar and fixtool_panel toggle.
        viewModelRef?.let { viewModel ->
            val showScenarios by viewModel.showScenariosDialog.collectAsState()
            if (showScenarios) {
                ScenarioWorkbenchWindow(
                    viewModel = viewModel,
                    onClose = { viewModel.toggleScenariosDialog() },
                )
            }
        }
    }
}
