package com.knapsack.fixtool.ui

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The parts of the native dialogs that can be tested at all.
 *
 * The dialogs themselves are the operating system's own windows and no Compose or Robot test can
 * drive them, so what is pinned here is everything decided before the dialog opens and after it
 * closes: where it starts, what name it proposes, and what the file is called once it comes back.
 */
class NativeFileDialogsTest {
    private val existingDirectory = Files.createTempDirectory("native-dialogs").toFile()
    private val existingFile = File(existingDirectory, "profiles.json").apply { writeText("{}") }

    @Test
    fun `a path naming a file starts the dialog in the folder holding it`() {
        assertEquals(existingDirectory, dialogStartDirectory(existingFile.absolutePath, namesDirectory = false))
    }

    @Test
    fun `a path naming a folder starts the dialog in that folder`() {
        assertEquals(existingDirectory, dialogStartDirectory(existingDirectory.absolutePath, namesDirectory = true))
    }

    @Test
    fun `an empty path leaves the starting folder to the operating system`() {
        assertNull(dialogStartDirectory("", namesDirectory = false))
        assertNull(dialogStartDirectory("   ", namesDirectory = true))
    }

    @Test
    fun `a path that does not exist yet leaves the starting folder to the operating system`() {
        assertNull(dialogStartDirectory(File(existingDirectory, "nowhere/profiles.json").absolutePath, namesDirectory = false))
        assertNull(dialogStartDirectory(File(existingDirectory, "nowhere").absolutePath, namesDirectory = true))
    }

    @Test
    fun `a placeholder path with a tilde is not a folder anyone can open`() {
        assertNull(dialogStartDirectory("~/.fixtool/connection_profiles.json", namesDirectory = false))
    }

    @Test
    fun `a name splits into the stem and extension the saver asks for separately`() {
        assertEquals("connection_profiles" to "json", saveNameAndExtension("connection_profiles.json"))
    }

    @Test
    fun `only the last dot separates the extension`() {
        assertEquals("session.2026-09-03" to "fix", saveNameAndExtension("session.2026-09-03.fix"))
    }

    @Test
    fun `a name carrying no extension takes the fallback`() {
        assertEquals("capture" to "fix", saveNameAndExtension("capture", fallbackExtension = "fix"))
    }

    @Test
    fun `an empty name still proposes something saveable`() {
        assertEquals("untitled" to "json", saveNameAndExtension("", fallbackExtension = "json"))
    }

    @Test
    fun `a leading dot is part of the name, not an extension`() {
        assertEquals(".gitignore" to "json", saveNameAndExtension(".gitignore", fallbackExtension = "json"))
    }

    @Test
    fun `a trailing dot is not an extension either`() {
        assertEquals("capture" to "fix", saveNameAndExtension("capture.", fallbackExtension = "fix"))
    }

    @Test
    fun `a saved file with no known extension gets the default`() {
        assertEquals(
            File(existingDirectory, "capture.fix"),
            withDefaultExtension(File(existingDirectory, "capture"), allowed = setOf("fix", "txt"), default = "fix"),
        )
    }

    @Test
    fun `an extension the user chose is left alone`() {
        val typed = File(existingDirectory, "capture.txt")
        assertEquals(typed, withDefaultExtension(typed, allowed = setOf("fix", "txt"), default = "fix"))
    }

    @Test
    fun `the extension check ignores case, the way the filesystem does`() {
        val typed = File(existingDirectory, "capture.FIX")
        assertEquals(typed, withDefaultExtension(typed, allowed = setOf("fix", "txt"), default = "fix"))
    }

    @Test
    fun `an unrelated extension is kept and the default appended`() {
        assertEquals(
            File(existingDirectory, "capture.log.fix"),
            withDefaultExtension(File(existingDirectory, "capture.log"), allowed = setOf("fix", "txt"), default = "fix"),
        )
    }
}
