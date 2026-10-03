package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A store whose file is there but could not be read does not save over it.
 *
 * Each of these stores loads the whole file, changes it in memory and writes the whole file back, and a load
 * that fails hands its caller an empty list or the defaults. So one merge-conflict marker in a committed
 * `connection_profiles.json` used to turn the next Save of one profile into a file holding that profile alone,
 * and a tabs/split toggle into an `app_settings.json` of defaults. The load still answers empty (see
 * [ErrorHandlingTest]), and the write is what is refused.
 */
class UnreadableStoreTest {
    private val dir = Files.createTempDirectory("unreadable-store").toFile()
    private val notices = mutableListOf<String>()

    /** What a half-resolved merge leaves behind: readable to a person, not to a JSON parser. */
    private val conflicted =
        """
        {
        <<<<<<< HEAD
            "profiles": [ { "id": "a", "name": "Mine", "config": {} } ]
        =======
            "profiles": [ { "id": "b", "name": "Theirs", "config": {} } ]
        >>>>>>> theirs
        }
        """.trimIndent()

    private fun profile(
        id: String,
        password: String = "",
    ) = FixConnectionProfile(
        id = id,
        name = "Profile $id",
        config = FixConnectionConfig(senderCompID = "SENDER", targetCompID = "TARGET", password = password),
    )

    private fun assertRefused(file: File) {
        assertTrue(
            notices.any { it.contains(file.name) && it.contains("will not overwrite") },
            "nothing told the user ${file.name} was left alone. Notices: $notices",
        )
    }

    // ---------------------------------------------------------------- connection profiles

    @Test
    fun `saving one profile does not replace a profiles file that could not be read`() {
        val file = File(dir, "connection_profiles.json").apply { writeText(conflicted) }
        val service = ConnectionProfileService(onError = { notices += it }, customPath = file.absolutePath)

        assertTrue(service.loadProfiles().isEmpty(), "a failed load still answers empty")
        val saved = service.saveProfile(profile("new"))

        assertTrue(saved.isFailure, "the save claimed to succeed")
        assertEquals(conflicted, file.readText(), "the unreadable file was written over")
        assertRefused(file)
    }

    @Test
    fun `once the profiles file reads again, a save keeps every profile in it`() {
        val file = File(dir, "connection_profiles.json").apply { writeText(conflicted) }
        val service = ConnectionProfileService(onError = { notices += it }, customPath = file.absolutePath)
        service.loadProfiles()
        file.writeText("""{ "profiles": [ { "id": "a", "name": "Mine", "config": {} } ] }""")

        service.saveProfile(profile("new")).getOrThrow()

        assertEquals(listOf("a", "new"), service.loadProfiles().map { it.id })
    }

    @Test
    fun `deleting a profile does not replace a profiles file that could not be read`() {
        val file = File(dir, "connection_profiles.json").apply { writeText(conflicted) }
        val service = ConnectionProfileService(onError = { notices += it }, customPath = file.absolutePath)

        assertTrue(service.deleteProfile("a").isFailure, "the delete claimed to succeed")
        assertEquals(conflicted, file.readText(), "the unreadable file was written over")
    }

    // ---------------------------------------------------------------- passwords

    @Test
    fun `a new password does not replace a secrets file that could not be read`() {
        val profiles = File(dir, "connection_profiles.json")
        val secrets = File(dir, "secrets.json").apply { writeText("""{ "passwords": { "a": "hunter2" """) }
        val service = ConnectionProfileService(onError = { notices += it }, customPath = profiles.absolutePath)
        service.saveProfiles(listOf(profile("a")))
        val before = secrets.readText()

        val saved = service.saveProfile(profile("b", password = "letmein"))

        assertTrue(saved.isFailure, "the save claimed to succeed")
        assertEquals(before, secrets.readText(), "the unreadable secrets file was written over")
        assertRefused(secrets)
    }

    // ---------------------------------------------------------------- saved messages

    @Test
    fun `saving one message does not replace a messages file that could not be read`() {
        val file = File(dir, "saved_messages.json").apply { writeText(conflicted) }
        val service = SavedMessagesService(onError = { notices += it }, customPath = file.absolutePath)
        val message = SavedFixMessage(id = "m", name = "New", fields = listOf(SavedFixField(tag = "35", value = "D")))

        assertTrue(service.loadMessagesForProfile("p").isEmpty(), "a failed load still answers empty")
        val saved = service.saveMessage("p", message)

        assertTrue(saved.isFailure, "the save claimed to succeed")
        assertEquals(conflicted, file.readText(), "the unreadable file was written over")
        assertRefused(file)
    }

    // ---------------------------------------------------------------- app settings

    /** The trigger in the wild: a tabs/split toggle persists the layout through the defaults a failed load gave. */
    @Test
    fun `settings that could not be read are not replaced by the defaults`() {
        val file = File(dir, "app_settings.json").apply { writeText("""{ "defaultDataDictionary": "/dicts/FIX44.xml", """) }
        val service = AppSettingsService(onError = { notices += it }, customSettingsDir = dir.absolutePath)
        val before = file.readText()

        val loaded = service.loadSettings()
        val saved = service.saveSettings(loaded.copy(defaultLayout = "split"))

        assertEquals(AppSettings.default(), loaded, "a failed load still answers the defaults")
        assertFalse(saved, "the save claimed to succeed")
        assertEquals(before, file.readText(), "the unreadable file was written over")
        assertRefused(file)
    }

    @Test
    fun `settings moved aside after a failed read can be saved again`() {
        val file = File(dir, "app_settings.json").apply { writeText("not json") }
        val service = AppSettingsService(onError = { notices += it }, customSettingsDir = dir.absolutePath)
        service.loadSettings()
        assertTrue(file.renameTo(File(dir, "app_settings.broken.json")))

        assertTrue(service.saveSettings(AppSettings.default().copy(defaultLayout = "split")))
        assertTrue(file.readText().contains("split"))
    }

    // ---------------------------------------------------------------- environments

    @Test
    fun `environments that could not be read are not replaced`() {
        val file = File(dir, "environments.json").apply { writeText(conflicted) }
        val environments = Environments(file)

        assertTrue(environments.load().isEmpty(), "a failed load still answers empty")
        val saved = environments.save(listOf(Environment(name = "UAT1", host = "uat.host")))

        assertFalse(saved, "the save claimed to succeed")
        assertEquals(conflicted, file.readText(), "the unreadable file was written over")
    }
}
