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
        // (and JediTerm's start()) must be created. Spawning a PTY can fail for reasons outside our
        // control (no shell on PATH, winpty natives blocked); an exception thrown out of the factory
        // leaves an unexplained blank rectangle, so failures become a readable panel instead.
        factory = {
            runCatching { createTerminalWidget(workingDir, env).also { widgetHolder[0] = it } }
                .getOrElse { error ->
                    logger.error("Failed to start embedded terminal", error)
                    terminalFailurePanel(error)
                }
        },
        // Compose owns top-level focus in the window; a click on the interop surface doesn't hand
        // keyboard focus to the embedded Swing component by itself (the JediTerm cursor stays hollow
        // and keystrokes never arrive). Pushing focus down to JediTerm's inner panel here bridges it.
        update = { component ->
            val widget = component as? JediTermWidget
            if (widget != null && !didInitialFocus[0]) {
                didInitialFocus[0] = true
                javax.swing.SwingUtilities.invokeLater { widget.terminalPanel.requestFocusInWindow() }
            }
        },
    )
}

/**
 * Spawns a shell on a PTY and hands it to a fresh JediTerm widget.
 *
 * On Windows the PTY comes from ConPTY, the OS's own pseudo-console, rather than pty4j's bundled
 * winpty shim: ConPTY is what modern Windows terminals use and it speaks VT sequences natively, which
 * is what a full-screen TUI like the Claude Code CLI needs. pty4j falls back to winpty by itself if
 * ConPTY's native library won't load (older Windows), and [setWindowsAnsiColorEnabled] is what makes
 * colour survive that fallback.
 */
private fun createTerminalWidget(
    workingDir: File,
    env: Map<String, String>,
): JediTermWidget {
    val widget = DarkJediTermWidget(DarkSettingsProvider())
    val command = terminalCommand()
    val pty =
        PtyProcessBuilder(command)
            .setDirectory(workingDir.absolutePath)
            .setEnvironment(env)
            .setInitialColumns(DEFAULT_COLUMNS)
            .setInitialRows(DEFAULT_ROWS)
            .setUseWinConPty(true)
            .setWindowsAnsiColorEnabled(true)
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

    logger.info("Embedded terminal started: command={} cwd={}", command.joinToString(" "), workingDir.absolutePath)
    return widget
}

/**
 * The shell command line to spawn, per platform. The goal throughout is that `claude` resolves here
 * exactly as it does in the user's normal terminal — which means imitating what that platform's own
 * terminal does, not applying one rule everywhere.
 *
 * **macOS** gets a *login* shell (`-l`), because that's what Terminal.app and iTerm run, and because
 * a GUI app launched by launchd inherits a stripped PATH that never saw `~/.zprofile`. zsh reads
 * `~/.zshrc` for every interactive shell regardless of login status, so `-l` costs nothing there.
 *
 * **Linux** gets an *interactive, non-login* shell (`-i`) instead — what gnome-terminal and Konsole
 * run. This is not cosmetic: bash reads `~/.bashrc` only for non-login shells, so `bash -l` silently
 * misses PATH set by nvm/pyenv/`~/.local/bin` installers, which all write to `~/.bashrc`. Adding `-i`
 * to `-l` does not help — bash's login/non-login split governs `~/.bashrc`, not interactivity. The
 * profile side is still covered because the desktop session sources `~/.profile` before launching us,
 * so it arrives in the inherited environment. Verified both ways on a real PTY.
 *
 * **Windows** has neither `SHELL` nor `-l`. Handing it the Unix default (`/bin/zsh -l`) is a path that
 * doesn't exist, and pty4j reports the failed spawn as the opaque "Couldn't create PTY" — so the
 * terminal never opened on Windows at all. PowerShell is preferred because it's the shell Windows
 * users actually live in and it handles VT output; `COMSPEC` (normally `cmd.exe`) is the floor, since
 * it's guaranteed present. `-NoLogo` just suppresses the copyright banner.
 *
 * The parameters exist to be substituted in tests; production always calls this with no arguments.
 */
internal fun terminalCommand(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = { System.getenv(it) },
    onPath: (String) -> Boolean = { isOnPath(it, env("PATH") ?: env("Path")) },
): Array<String> {
    if (osName.lowercase().startsWith("windows")) {
        val powerShell = listOf("pwsh.exe", "powershell.exe").firstOrNull(onPath)
        if (powerShell != null) return arrayOf(powerShell, "-NoLogo")
        return arrayOf(env("COMSPEC")?.ifBlank { null } ?: "cmd.exe")
    }
    val isMac = osName.lowercase().contains("mac")
    // zsh is the macOS default; elsewhere bash is the one that's reliably installed.
    val fallback = if (isMac) "/bin/zsh" else "/bin/bash"
    return arrayOf(env("SHELL")?.ifBlank { null } ?: fallback, if (isMac) "-l" else "-i")
}

/**
 * Whether [executable] resolves against a PATH-style search list. Named with its extension by the
 * caller, so there's no PATHEXT guessing to do.
 */
private fun isOnPath(
    executable: String,
    path: String?,
): Boolean =
    path
        ?.split(File.pathSeparatorChar)
        ?.any { dir ->
            dir.isNotBlank() && File(dir, executable).let { it.isFile && it.canExecute() }
        }
        ?: false

/**
 * What the terminal pane shows when the PTY can't be created — the reason, in words, instead of an
 * empty black rectangle the user has to read the log file to explain.
 */
private fun terminalFailurePanel(error: Throwable): JComponent {
    // The whole cause chain, not just the top frame: pty4j's own message is the uninformative
    // "Couldn't create PTY", and the cause underneath it is the part that names what actually failed.
    val reason =
        generateSequence(error) { it.cause }
            .joinToString(": ") { it.message ?: it::class.java.simpleName }
    return javax.swing.JTextArea("The terminal could not start.\n\n$reason\n").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = java.awt.Color(0x1E, 0x1E, 0x1E)
        foreground = java.awt.Color(0xD4, 0xD4, 0xD4)
        border = javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12)
    }
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
