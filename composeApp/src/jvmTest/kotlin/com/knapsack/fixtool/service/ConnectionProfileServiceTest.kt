package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConnectionProfileServiceTest {
    private lateinit var testProfilesFile: File
    private lateinit var testDir: File
    private lateinit var json: Json

    @Before
    fun setup() {
        // Create a temporary test directory
        testDir = File.createTempFile("test_fixtool", "")
        testDir.delete()
        testDir.mkdirs()
        testDir.deleteOnExit()

        testProfilesFile = File(testDir, "connection_profiles.json")

        json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }
    }

    @After
    fun cleanup() {
        testProfilesFile.delete()
        testDir.deleteRecursively()
    }

    @Test
    fun testSerializeProfileWithEmptySessionQualifier() {
        val profile =
            createTestProfile(
                id = "test-1",
                name = "Test Profile",
                sessionQualifier = "",
            )

        val container = ProfilesContainer(listOf(profile))
        val jsonString = json.encodeToString(container)
        testProfilesFile.writeText(jsonString)

        val loadedString = testProfilesFile.readText()
        val loadedContainer = json.decodeFromString<ProfilesContainer>(loadedString)

        assertEquals(1, loadedContainer.profiles.size)
        assertEquals("Test Profile", loadedContainer.profiles[0].name)
        assertEquals("", loadedContainer.profiles[0].config.sessionQualifier)
    }

    @Test
    fun testSerializeProfileWithSessionQualifier() {
        val profile =
            createTestProfile(
                id = "dev1",
                name = "DEV1-BuySide",
                sessionQualifier = "DEV1",
            )

        val container = ProfilesContainer(listOf(profile))
        val jsonString = json.encodeToString(container)
        testProfilesFile.writeText(jsonString)

        val loadedString = testProfilesFile.readText()
        val loadedContainer = json.decodeFromString<ProfilesContainer>(loadedString)

        assertEquals(1, loadedContainer.profiles.size)
        assertEquals("DEV1-BuySide", loadedContainer.profiles[0].name)
        assertEquals("DEV1", loadedContainer.profiles[0].config.sessionQualifier)
    }

    @Test
    fun testSerializeMultipleProfilesWithDifferentQualifiers() {
        val dev1Profile =
            createTestProfile(
                id = "dev1",
                name = "DEV1-BuySide",
                senderCompID = "SENDER_CLIENT",
                targetCompID = "TARGET_SERVER",
                sessionQualifier = "DEV1",
            )

        val localProfile =
            createTestProfile(
                id = "local",
                name = "Local-BuySide",
                senderCompID = "SENDER_CLIENT",
                targetCompID = "TARGET_SERVER",
                sessionQualifier = "LOCAL",
            )

        val qa1Profile =
            createTestProfile(
                id = "qa1",
                name = "QA1-BuySide",
                senderCompID = "SENDER_CLIENT",
                targetCompID = "TARGET_SERVER",
                sessionQualifier = "QA1",
            )

        val container = ProfilesContainer(listOf(dev1Profile, localProfile, qa1Profile))
        val jsonString = json.encodeToString(container)
        testProfilesFile.writeText(jsonString)

        val loadedString = testProfilesFile.readText()
        val loadedContainer = json.decodeFromString<ProfilesContainer>(loadedString)

        assertEquals(3, loadedContainer.profiles.size)

        val dev1Loaded = loadedContainer.profiles.find { it.id == "dev1" }
        assertNotNull(dev1Loaded)
        assertEquals("DEV1", dev1Loaded.config.sessionQualifier)

        val localLoaded = loadedContainer.profiles.find { it.id == "local" }
        assertNotNull(localLoaded)
        assertEquals("LOCAL", localLoaded.config.sessionQualifier)

        val qa1Loaded = loadedContainer.profiles.find { it.id == "qa1" }
        assertNotNull(qa1Loaded)
        assertEquals("QA1", qa1Loaded.config.sessionQualifier)
    }

    @Test
    fun testDeserializeLegacyProfileWithoutSessionQualifier() {
        // Simulate an old profile JSON without sessionQualifier field
        val legacyJson =
            """
            {
                "profiles": [
                    {
                        "id": "legacy-1",
                        "name": "Legacy Profile",
                        "config": {
                            "username": "testuser",
                            "senderCompID": "SENDER",
                            "targetCompID": "TARGET",
                            "password": "testpass",
                            "host": "localhost",
                            "port": "9876",
                            "beginString": "FIX.4.4",
                            "heartBtInt": "30",
                            "resetOnLogon": false,
                            "resetOnLogout": false,
                            "resetOnDisconnect": false,
                            "showHeartbeat": true,
                            "customParameters": {},
                            "logonFields": {}
                        },
                        "createdAt": 1234567890,
                        "lastUsedAt": 1234567890
                    }
                ]
            }
            """.trimIndent()

        testProfilesFile.writeText(legacyJson)

        val loadedContainer = json.decodeFromString<ProfilesContainer>(testProfilesFile.readText())

        assertEquals(1, loadedContainer.profiles.size)
        assertEquals("Legacy Profile", loadedContainer.profiles[0].name)
        // Should default to empty string when not present
        assertEquals("", loadedContainer.profiles[0].config.sessionQualifier)
    }

    @Test
    fun testProfilesWithSameSenderTargetButDifferentQualifiers() {
        val senderCompID = "common.sender@example.com"
        val targetCompID = "COMMON_TARGET"

        val profile1 =
            createTestProfile(
                id = "env1",
                name = "Environment 1",
                senderCompID = senderCompID,
                targetCompID = targetCompID,
                sessionQualifier = "ENV1",
            )

        val profile2 =
            createTestProfile(
                id = "env2",
                name = "Environment 2",
                senderCompID = senderCompID,
                targetCompID = targetCompID,
                sessionQualifier = "ENV2",
            )

        val container = ProfilesContainer(listOf(profile1, profile2))
        val jsonString = json.encodeToString(container)
        testProfilesFile.writeText(jsonString)

        val loadedContainer = json.decodeFromString<ProfilesContainer>(testProfilesFile.readText())

        assertEquals(2, loadedContainer.profiles.size)

        // Both profiles have same sender/target but different qualifiers
        loadedContainer.profiles.forEach { profile ->
            assertEquals(senderCompID, profile.config.senderCompID)
            assertEquals(targetCompID, profile.config.targetCompID)
        }

        val env1 = loadedContainer.profiles.find { it.id == "env1" }
        assertNotNull(env1)
        assertEquals("ENV1", env1.config.sessionQualifier)

        val env2 = loadedContainer.profiles.find { it.id == "env2" }
        assertNotNull(env2)
        assertEquals("ENV2", env2.config.sessionQualifier)
    }

    private fun createTestProfile(
        id: String,
        name: String,
        senderCompID: String = "SENDER",
        targetCompID: String = "TARGET",
        sessionQualifier: String = "",
    ): FixConnectionProfile =
        FixConnectionProfile(
            id = id,
            name = name,
            config =
                FixConnectionConfig(
                    senderCompID = senderCompID,
                    targetCompID = targetCompID,
                    sessionQualifier = sessionQualifier,
                    username = "testuser",
                    password = "testpass",
                    host = "localhost",
                    port = "9876",
                    beginString = "FIX.4.4",
                    heartBtInt = "30",
                ),
        )

    @Serializable
    private data class ProfilesContainer(
        val profiles: List<FixConnectionProfile>,
    )
}
