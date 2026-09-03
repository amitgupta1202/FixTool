package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Passwords leave the file a workspace is shared as, and nothing else changes.
 *
 * The property under test is not secrecy — `secrets.json` sits in the same directory with the same
 * permissions — it is that the file you would commit, copy or attach to a ticket is not the file
 * with the credential in it.
 */
class ProfileSecretsTest {
    private val dir = Files.createTempDirectory("profile-secrets").toFile()
    private val profilesFile = File(dir, "connection_profiles.json")
    private val secretsFile = File(dir, "secrets.json")

    private fun service() = ConnectionProfileService(customPath = profilesFile.absolutePath)

    private fun profile(
        id: String,
        password: String,
    ) = FixConnectionProfile(
        id = id,
        name = "Profile $id",
        config = FixConnectionConfig(senderCompID = "SENDER", targetCompID = "TARGET", password = password),
    )

    @Test
    fun `a saved password is not in the profiles file, and is in the secrets file`() {
        service().saveProfiles(listOf(profile("a", "hunter2")))

        assertFalse(profilesFile.readText().contains("hunter2"), "the password is still in the shareable file")
        assertTrue(secretsFile.readText().contains("hunter2"), "the password did not reach secrets.json")
    }

    @Test
    fun `a loaded profile has its password back, so nothing else has to know`() {
        service().saveProfiles(listOf(profile("a", "hunter2")))

        val loaded = service().loadProfiles().single()
        assertEquals("hunter2", loaded.config.password)
    }

    @Test
    fun `a file written before the split is migrated the first time it is read`() {
        profilesFile.writeText(
            """
            {
                "profiles": [
                    {
                        "id": "legacy",
                        "name": "Legacy",
                        "config": { "senderCompID": "S", "targetCompID": "T", "password": "inline-secret" }
                    }
                ]
            }
            """.trimIndent(),
        )

        val loaded = service().loadProfiles()

        assertEquals("inline-secret", loaded.single().config.password, "the migration must not lose the password")
        assertFalse(profilesFile.readText().contains("inline-secret"), "the inline password survived migration")
        assertTrue(secretsFile.readText().contains("inline-secret"), "the migrated password is not in secrets.json")
    }

    @Test
    fun `saving one profile does not forget another's password`() {
        val service = service()
        service.saveProfiles(listOf(profile("a", "aaa"), profile("b", "bbb")))

        service.saveProfile(profile("a", "aaa-changed"))

        val byId = service.loadProfiles().associateBy { it.id }
        assertEquals("aaa-changed", byId.getValue("a").config.password)
        assertEquals("bbb", byId.getValue("b").config.password)
    }

    @Test
    fun `clearing a password removes it rather than leaving the old one to come back`() {
        val service = service()
        service.saveProfiles(listOf(profile("a", "hunter2")))

        service.saveProfiles(listOf(profile("a", "")))

        val cleared = service.loadProfiles().single()
        assertEquals("", cleared.config.password)
        assertFalse(secretsFile.readText().contains("hunter2"), "the cleared password is still on disk")
    }

    @Test
    fun `deleting a profile forgets its password`() {
        val service = service()
        service.saveProfiles(listOf(profile("a", "hunter2"), profile("b", "bbb")))

        service.deleteProfile("a")

        assertFalse(secretsFile.readText().contains("hunter2"), "a deleted profile left its password behind")
        val survivor = service.loadProfiles().single()
        assertEquals("bbb", survivor.config.password)
    }

    @Test
    fun `a workspace with no secrets file reads as one with no passwords`() {
        service().saveProfiles(listOf(profile("a", "hunter2")))
        secretsFile.delete()

        val withoutSecrets = service().loadProfiles().single()
        assertEquals("", withoutSecrets.config.password)
    }
}
