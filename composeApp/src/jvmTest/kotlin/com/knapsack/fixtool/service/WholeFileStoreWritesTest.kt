package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The files a workspace is stored in are replaced whole, never rewritten in place.
 *
 * A plain `writeText` truncates the file and then writes it, so a crash, a full disk or a killed process in
 * between left a profiles file holding part of its profiles, which the next start could not read. Each store
 * now writes through `AtomicFiles`: a sibling temp file renamed over the old one, so the file on disk is the
 * old one or the new one and never a mix.
 *
 * Seen from outside, a rename leaves the old file's bytes where they were. A hard link taken before the save
 * still reads the old content afterwards, which an in-place rewrite cannot do.
 */
class WholeFileStoreWritesTest {
    private val dir = Files.createTempDirectory("whole-file-writes").toFile()

    private fun profile(password: String = "") =
        FixConnectionProfile(
            id = "a",
            name = "Profile a",
            config = FixConnectionConfig(senderCompID = "SENDER", targetCompID = "TARGET", password = password),
        )

    /** Saves twice through [save], and checks the second save replaced [file] rather than rewriting it. */
    private fun assertReplacedWhole(
        file: File,
        save: (Int) -> Unit,
    ) {
        save(1)
        val before = file.readText()
        val link = File(dir, "${file.name}.before").toPath()
        Files.createLink(link, file.toPath())

        save(2)

        assertNotEquals(before, file.readText(), "the fixture's second save changed nothing")
        assertEquals(before, Files.readString(link), "${file.name} was rewritten in place, not replaced")
    }

    @Test
    fun `the profiles file is replaced whole`() {
        val file = File(dir, "connection_profiles.json")
        val service = ConnectionProfileService(customPath = file.absolutePath)
        assertReplacedWhole(file) { n -> service.saveProfiles(listOf(profile().copy(name = "Profile $n"))) }
    }

    @Test
    fun `the secrets file is replaced whole`() {
        val service = ConnectionProfileService(customPath = File(dir, "connection_profiles.json").absolutePath)
        assertReplacedWhole(File(dir, "secrets.json")) { n -> service.saveProfiles(listOf(profile(password = "pw$n"))) }
    }

    @Test
    fun `the saved messages file is replaced whole`() {
        val file = File(dir, "saved_messages.json")
        val service = SavedMessagesService(customPath = file.absolutePath)
        val fields = listOf(SavedFixField("35", "D"))
        assertReplacedWhole(file) { n -> service.saveMessage("p", SavedFixMessage(id = "m", name = "Message $n", fields = fields)) }
    }

    @Test
    fun `the settings file is replaced whole`() {
        val service = AppSettingsService(customSettingsDir = dir.absolutePath)
        assertReplacedWhole(File(dir, "app_settings.json")) { n ->
            service.saveSettings(AppSettings.default().copy(defaultDataDictionary = "/dicts/$n.xml"))
        }
    }

    @Test
    fun `the environments file is replaced whole`() {
        val file = File(dir, "environments.json")
        val environments = Environments(file)
        assertReplacedWhole(file) { n -> environments.save(listOf(Environment(name = "UAT$n", host = "uat.host"))) }
    }

    @Test
    fun `the workspace dictionary declaration is replaced whole`() {
        val versions = listOf(FixVersion.FIX_4_2, FixVersion.FIX_4_4)
        assertReplacedWhole(File(dir, WorkspaceDictionary.FILE)) { n ->
            WorkspaceDictionary.write(dir, WorkspaceDictionary(fixVersion = versions[n - 1]))
        }
    }

    /** A rename brings the temp file's permissions with it, so a secrets file made private would quietly stop being. */
    @Test
    fun `a secrets file someone made private stays private after a save`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val secrets = File(dir, "secrets.json").toPath()
        val service = ConnectionProfileService(customPath = File(dir, "connection_profiles.json").absolutePath)
        service.saveProfiles(listOf(profile(password = "pw1")))
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-------"))

        service.saveProfiles(listOf(profile(password = "pw2")))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets)))
    }
}
