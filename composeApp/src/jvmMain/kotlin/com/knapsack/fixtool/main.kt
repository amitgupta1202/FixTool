package com.knapsack.fixtool

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.knapsack.fixtool.control.ControlServerLauncher
import com.knapsack.fixtool.headless.HeadlessRun
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.ui.App
import com.knapsack.fixtool.ui.diff.DiffViewerWindow
import com.knapsack.fixtool.ui.diff.DiffWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import javax.swing.JOptionPane.ERROR_MESSAGE
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Create log directory before any logger is instantiated
    // This prevents logback initialization failures on first run
    val logDir = WorkspacePaths.home.logs
    logDir.mkdirs()

    // `fixtool run <scenario>` never opens a window. The branch is here, before anything Compose or
    // AWT touches, so a headless box needs no display and a CI step gets a real exit code — the whole
    // point of the entrypoint. Everything below is the GUI, unchanged.
    if (HeadlessRun.handles(args)) {
        // System.out carries the report and System.err the progress, so `fixtool run … > report.txt`
        // keeps the two apart the way any other command-line tool would.
        exitProcess(HeadlessRun.execute(args, System.out, System.err))
    }

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
        // Compose state, because the diff windows (Phase 6) are siblings of the main window down below and must
        // recompose off the ViewModel's `openDiffWindows` flow. This reverses `271b34f`'s "there is no second
        // window" — there is again, and it is the diff (F2).
        var viewModelRef by remember { mutableStateOf<com.knapsack.fixtool.viewmodel.FixMessageViewModel?>(null) }
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
            title = com.knapsack.fixtool.control.ControlServer.MAIN_WINDOW_TITLE,
            // rememberWindowState, not WindowState. A bare WindowState is a NEW state object on every
            // recomposition of the application scope, and Compose then applies it to the live window: the
            // size snaps back to exactly 1920x1080 and the position re-cascades, taking the user's own resize
            // with it. What used to recompose this scope was the Scenarios workbench window, which is gone —
            // but the bug was never really about that window, and the next thing composed out here would
            // bring it straight back.
            state = rememberWindowState(size = DpSize(1920.dp, 1080.dp)),
            resizable = true,
        ) {
            val focusRequester = remember { FocusRequester() }

            // Fix for Compose Multiplatform issue #4803: Window focus requires multiple clicks
            // When window gains focus, automatically request focus on compose content
            // This prevents users from having to click multiple times on toolbar buttons
            LaunchedEffect(Unit) {
                // The main window BY TITLE, not `firstOrNull()` — once the diff opens in its own window,
                // `getWindows()` has no defined order and the focus listener would attach to the wrong one.
                val window =
                    Window.getWindows().firstOrNull {
                        (it as? java.awt.Frame)?.title == com.knapsack.fixtool.control.ControlServer.MAIN_WINDOW_TITLE
                    } ?: Window.getWindows().firstOrNull()
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
                    // ALL showing windows, not the first — the control surface picks by title (see
                    // ControlServer.selectWindow), so `?window=main|diff` is deterministic once a diff window
                    // is on screen. `getWindows()` order is undefined; `firstOrNull()` was only accidentally right.
                    val windowProvider = { java.awt.Window.getWindows().toList() }
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

        // The diff windows — siblings of the main window, so closing one never touches the app, and each is
        // task-scoped: it opens on one step, does one job, and closes back into the grid it is about (§1c).
        // `key(id)` keeps each window's own composition (and its rememberWindowState) stable across the list
        // changing under it.
        viewModelRef?.let { viewModel ->
            val diffWindows by viewModel.openDiffWindows.collectAsState()
            diffWindows.forEach { state ->
                key(state.id) {
                    DiffWindow(viewModel, state, onClose = { viewModel.requestCloseDiffWindow(state.id) })
                }
            }
            // The plain diff viewers — a second, scenario-less window subject (Phase 7). Same mechanism: one
            // composition per id, its own rememberWindowState. Distinct titles keep `?window=diff:` deterministic.
            val diffViewers by viewModel.openDiffViewers.collectAsState()
            diffViewers.forEach { state ->
                key(state.id) {
                    DiffViewerWindow(viewModel, state, onClose = { viewModel.requestCloseDiffViewer(state.id) })
                }
            }
        }

    }
}
