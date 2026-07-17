package com.knapsack.fixtool.ui.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Bridges JediTerm (the terminal renderer) to a pty4j pseudo-terminal.
 *
 * [ProcessTtyConnector] already wires read/write/waitFor to a [java.lang.Process]'s streams, and a
 * [PtyProcess] *is* a Process — so the only things left to supply are a name and the one operation a
 * pipe can't do: telling the kernel the window changed size, which pty4j does via [PtyProcess.setWinSize].
 * Without that, full-screen TUIs like the Claude Code CLI would render at a fixed 80x24 and never reflow.
 */
class PtyTtyConnector(
    private val pty: PtyProcess,
    charset: Charset = StandardCharsets.UTF_8,
) : ProcessTtyConnector(pty, charset) {
    override fun getName(): String = "fixtool-terminal"

    override fun isConnected(): Boolean = pty.isAlive

    override fun resize(termSize: TermSize) {
        if (pty.isAlive) {
            pty.winSize = WinSize(termSize.columns, termSize.rows)
        }
    }
}
