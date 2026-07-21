package com.knapsack.fixtool.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The embedded terminal picks its shell per platform. This is the regression the tests exist for: on
 * Windows the command line used to be the Unix default `/bin/zsh -l`, a path that doesn't exist there,
 * and pty4j reported the failed spawn only as "Couldn't create PTY" — the terminal never opened.
 */
class TerminalCommandTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    // -------------------------------------------------- Windows

    @Test
    fun `windows never falls back to a unix shell path`() {
        val command =
            terminalCommand(
                osName = "Windows 11",
                env = env(),
                onPath = { false },
            )

        assertEquals(listOf("cmd.exe"), command.toList(), "with nothing on PATH, COMSPEC's default is the floor")
    }

    @Test
    fun `windows prefers powershell when it is on PATH`() {
        val command =
            terminalCommand(
                osName = "Windows 10",
                env = env("COMSPEC" to "C:\\Windows\\System32\\cmd.exe"),
                onPath = { it == "powershell.exe" },
            )

        assertEquals(listOf("powershell.exe", "-NoLogo"), command.toList())
    }

    @Test
    fun `windows prefers pwsh over windows powershell when both are present`() {
        val command =
            terminalCommand(
                osName = "Windows 11",
                env = env(),
                onPath = { true },
            )

        assertEquals(listOf("pwsh.exe", "-NoLogo"), command.toList())
    }

    @Test
    fun `windows honours COMSPEC when no powershell is found`() {
        val command =
            terminalCommand(
                osName = "Windows Server 2019",
                env = env("COMSPEC" to "D:\\alt\\cmd.exe"),
                onPath = { false },
            )

        assertEquals(listOf("D:\\alt\\cmd.exe"), command.toList())
    }

    @Test
    fun `windows ignores SHELL, which is a unix path when a POSIX layer sets it`() {
        val command =
            terminalCommand(
                osName = "Windows 11",
                env = env("SHELL" to "/bin/zsh"),
                onPath = { false },
            )

        assertEquals(listOf("cmd.exe"), command.toList())
    }

    // -------------------------------------------------- Unix

    @Test
    fun `unix uses the user's SHELL`() {
        val command =
            terminalCommand(
                osName = "Mac OS X",
                env = env("SHELL" to "/opt/homebrew/bin/fish"),
                onPath = { false },
            )

        assertEquals(listOf("/opt/homebrew/bin/fish", "-l"), command.toList())
    }

    @Test
    fun `macOS falls back to zsh and linux to bash`() {
        assertEquals(
            listOf("/bin/zsh", "-l"),
            terminalCommand(osName = "Mac OS X", env = env(), onPath = { false }).toList(),
        )
        assertEquals(
            listOf("/bin/bash", "-i"),
            terminalCommand(osName = "Linux", env = env(), onPath = { false }).toList(),
            "zsh is not reliably installed on Linux; bash is",
        )
    }

    @Test
    fun `macOS gets a login shell, matching Terminal app and a launchd-stripped PATH`() {
        assertEquals(
            listOf("/bin/zsh", "-l"),
            terminalCommand(osName = "Mac OS X", env = env("SHELL" to "/bin/zsh"), onPath = { false }).toList(),
        )
    }

    @Test
    fun `linux gets an interactive non-login shell, so bashrc is read`() {
        // bash reads ~/.bashrc only for NON-login shells. Under `-l` a PATH set by nvm/pyenv or a
        // ~/.local/bin installer — all of which write to ~/.bashrc — is silently missing, and `claude`
        // is then not on PATH in the embedded terminal. gnome-terminal/Konsole run `-i` for this reason.
        assertEquals(
            listOf("/bin/bash", "-i"),
            terminalCommand(osName = "Linux", env = env("SHELL" to "/bin/bash"), onPath = { false }).toList(),
        )
        assertEquals(
            listOf("/usr/bin/fish", "-i"),
            terminalCommand(osName = "Linux", env = env("SHELL" to "/usr/bin/fish"), onPath = { false }).toList(),
        )
    }

    @Test
    fun `a blank SHELL is treated as unset`() {
        val command =
            terminalCommand(
                osName = "Mac OS X",
                env = env("SHELL" to "   "),
                onPath = { false },
            )

        assertEquals(listOf("/bin/zsh", "-l"), command.toList())
    }
}
