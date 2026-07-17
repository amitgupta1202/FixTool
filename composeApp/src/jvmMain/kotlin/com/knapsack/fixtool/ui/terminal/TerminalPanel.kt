package com.knapsack.fixtool.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import com.pty4j.PtyProcessBuilder
import org.slf4j.LoggerFactory
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.plaf.basic.BasicScrollBarUI

private val logger = LoggerFactory.getLogger("TerminalPanel")

/**
 * An embedded terminal, IntelliJ-style: a JediTerm widget (the same renderer IntelliJ uses) hosted in
 * a Compose [SwingPanel], backed by a real pty4j PTY. Because it's a real pseudo-terminal, the full
 * Claude Code TUI runs inside it — colors, alt-screen, slash commands and all.
 *
 * The point of [controlUrl]/[controlToken] is the seamless part of the loop: FixTool injects its own
 * running control endpoint into the shell's environment, so a `claude` launched here has its MCP
 * server auto-target *this* FixTool instance. QA never configures a port; they just run `claude` and
 * watch the app drive itself in the window above.
 */
@Composable
fun TerminalPanel(
    workingDir: File,
    controlUrl: String?,
    controlToken: String?,
    modifier: Modifier = Modifier,
) {
    val env = remember(controlUrl, controlToken) { buildTerminalEnv(controlUrl, controlToken) }

    // Hold the widget so we can tear the PTY down when this leaves composition (window closed),
    // rather than leaking a shell process per open/close.
    val widgetHolder = remember { arrayOfNulls<JediTermWidget>(1) }
    // Focus the terminal once, the first time it's laid out — not on every recomposition, so resizing
    // the docked pane doesn't keep yanking focus back mid-drag.
    val didInitialFocus = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { widgetHolder[0]?.close() }
                .onFailure { logger.warn("Failed to close terminal widget", it) }
        }
    }

    SwingPanel(
        background = Color(0xFF1E1E1E),
        modifier = modifier,
        // SwingPanel runs the factory on the AWT event thread, which is exactly where Swing components
        // (and JediTerm's start()) must be created.
        factory = {
            createTerminalWidget(workingDir, env).also { widgetHolder[0] = it }
        },
        // Compose owns top-level focus in the window; a click on the interop surface doesn't hand
        // keyboard focus to the embedded Swing component by itself (the JediTerm cursor stays hollow
        // and keystrokes never arrive). Pushing focus down to JediTerm's inner panel here bridges it.
        update = { widget ->
            if (!didInitialFocus[0]) {
                didInitialFocus[0] = true
                javax.swing.SwingUtilities.invokeLater { widget.terminalPanel.requestFocusInWindow() }
            }
        },
    )
}

/**
 * Spawns a login shell on a PTY and hands it to a fresh JediTerm widget. A login shell (`-l`) matters:
 * a GUI process inherits a stripped PATH, but the login shell re-sources the user's profile, so
 * `claude` (and whatever else lives on their PATH) resolves the same as in a normal terminal.
 */
private fun createTerminalWidget(
    workingDir: File,
    env: Map<String, String>,
): JediTermWidget {
    val widget = DarkJediTermWidget(DarkSettingsProvider())
    val shell = System.getenv("SHELL")?.ifBlank { null } ?: "/bin/zsh"
    val pty =
        PtyProcessBuilder(arrayOf(shell, "-l"))
            .setDirectory(workingDir.absolutePath)
            .setEnvironment(env)
            .setInitialColumns(DEFAULT_COLUMNS)
            .setInitialRows(DEFAULT_ROWS)
            .start()
    widget.ttyConnector = PtyTtyConnector(pty)
    widget.start()

    // Belt-and-braces focus: re-grab keyboard focus whenever the terminal surface is clicked, so the
    // embedded widget wins focus back from Compose's focus owner rather than staying inert.
    val termPanel = widget.terminalPanel
    termPanel.isFocusable = true
    termPanel.addMouseListener(
        object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                termPanel.requestFocusInWindow()
            }
        },
    )

    logger.info("Embedded terminal started: shell={} cwd={}", shell, workingDir.absolutePath)
    return widget
}

/** System environment plus the terminal-specific overrides FixTool injects. */
private fun buildTerminalEnv(
    controlUrl: String?,
    controlToken: String?,
): Map<String, String> {
    val env = HashMap(System.getenv())
    // JediTerm is a 256-color xterm; tell programs so they emit the right escape codes.
    env["TERM"] = "xterm-256color"
    // The auto-wire: point the MCP server (which reads these) at this running FixTool.
    controlUrl?.let { env["FIXTOOL_CONTROL_URL"] = it }
    controlToken?.let { env["FIXTOOL_CONTROL_TOKEN"] = it }
    return env
}

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * JediTerm ships a white background; this makes the embedded terminal match the app's dark surface
 * (0xFF1E1E1E). ANSI-coloured text — the shell prompt, TUIs like Claude Code — keeps its own colours;
 * only the default background and un-coloured foreground change.
 */
private class DarkSettingsProvider : DefaultSettingsProvider() {
    override fun getDefaultBackground(): TerminalColor = TerminalColor(0x1E, 0x1E, 0x1E)

    override fun getDefaultForeground(): TerminalColor = TerminalColor(0xD4, 0xD4, 0xD4)
}

/**
 * JediTerm's scrollbar is a plain Swing [JScrollBar] that renders light; this hands it a dark UI so it
 * matches the app instead of showing a white track against the dark terminal.
 */
private class DarkJediTermWidget(settings: SettingsProvider) : JediTermWidget(settings) {
    override fun createScrollBar(): JScrollBar =
        super.createScrollBar().apply {
            setUI(DarkScrollBarUI())
            background = java.awt.Color(0x1E, 0x1E, 0x1E)
        }
}

// Match the app's Compose scrollbar (WindowChrome.kt): a thin, rounded (pill) thumb in the app's
// scrollbar grey, no visible track, no arrows. ~half the default Swing width.
private const val SCROLLBAR_WIDTH_PX = 10
private const val SCROLLBAR_THUMB_INSET_PX = 1
private val SCROLLBAR_THUMB = java.awt.Color(0x6A, 0x6A, 0x6A) // AppTheme.Colors.scrollbar
private val SCROLLBAR_THUMB_HOVER = java.awt.Color(0x8A, 0x8A, 0x8A) // AppTheme.Colors.scrollbarHover

/** A thin, app-matching scrollbar UI: rounded grey thumb, invisible dark track, no arrow buttons. */
private class DarkScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        thumbColor = SCROLLBAR_THUMB
        trackColor = java.awt.Color(0x1E, 0x1E, 0x1E)
    }

    override fun getPreferredSize(c: JComponent): Dimension {
        val base = super.getPreferredSize(c)
        return if (scrollbar.orientation == JScrollBar.VERTICAL) {
            Dimension(SCROLLBAR_WIDTH_PX, base.height)
        } else {
            Dimension(base.width, SCROLLBAR_WIDTH_PX)
        }
    }

    override fun paintThumb(
        g: Graphics,
        c: JComponent,
        thumbBounds: Rectangle,
    ) {
        if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isThumbRollover) SCROLLBAR_THUMB_HOVER else SCROLLBAR_THUMB
            val x = thumbBounds.x + SCROLLBAR_THUMB_INSET_PX
            val y = thumbBounds.y + SCROLLBAR_THUMB_INSET_PX
            val w = (thumbBounds.width - SCROLLBAR_THUMB_INSET_PX * 2).coerceAtLeast(0)
            val h = (thumbBounds.height - SCROLLBAR_THUMB_INSET_PX * 2).coerceAtLeast(0)
            g2.fillRoundRect(x, y, w, h, w, w)
        } finally {
            g2.dispose()
        }
    }

    override fun createDecreaseButton(orientation: Int): JButton = hiddenButton()

    override fun createIncreaseButton(orientation: Int): JButton = hiddenButton()

    private fun hiddenButton(): JButton =
        JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
        }
}
