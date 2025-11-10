# Suggested Additional Tests for SessionQualifier

**IMPORTANT**: All test data uses generic credentials. Never use production credentials in tests.

## Test Data Guidelines

Use these generic values in all tests:
- SenderCompID: `SENDER_CLIENT`, `BUYER_FIRM`, `SELLER_FIRM`
- TargetCompID: `TARGET_SERVER`, `EXCHANGE_TARGET`, `BROKER_TARGET`
- SessionQualifier: `DEV1`, `LOCAL`, `QA1`, `TEST`, `STAGING`
- Emails: `test@example.com`, `user@test.com`
- Hosts: `localhost`, `test.example.com`, `server.test.local`

---

## 🔴 HIGH PRIORITY - Critical Path Tests

### 1. FixConnectionManager Configuration Tests

```kotlin
// File: FixConnectionManagerTest.kt
class FixConnectionManagerTest {

    @Test
    fun testSessionSettingsIncludesSessionQualifier() {
        val config = FixConnectionConfig(
            senderCompID = "SENDER_CLIENT",
            targetCompID = "TARGET_SERVER",
            sessionQualifier = "DEV1",
            host = "localhost",
            port = "9876"
        )

        // Create manager and verify settings file contains SessionQualifier
        val settings = createSessionSettings(config)
        val sessionID = settings.sectionNames().first()

        assertEquals("DEV1", settings.getString(sessionID, "SessionQualifier"))
    }

    @Test
    fun testSessionSettingsOmitsEmptySessionQualifier() {
        val config = FixConnectionConfig(
            senderCompID = "SENDER_CLIENT",
            targetCompID = "TARGET_SERVER",
            sessionQualifier = "",
            host = "localhost",
            port = "9876"
        )

        val settings = createSessionSettings(config)
        val sessionID = settings.sectionNames().first()

        // Should not have SessionQualifier key when empty
        assertFalse(settings.isSetting(sessionID, "SessionQualifier"))
    }

    @Test
    fun testMultipleManagersWithSameCredentialsButDifferentQualifiers() {
        val config1 = FixConnectionConfig(
            senderCompID = "BUYER_FIRM",
            targetCompID = "EXCHANGE_TARGET",
            sessionQualifier = "DEV1",
            port = "9876"
        )

        val config2 = FixConnectionConfig(
            senderCompID = "BUYER_FIRM",
            targetCompID = "EXCHANGE_TARGET",
            sessionQualifier = "LOCAL",
            port = "9877"
        )

        val manager1 = FixConnectionManager(config1, mockService1, appSettings, dictionary)
        val manager2 = FixConnectionManager(config2, mockService2, appSettings, dictionary)

        // Both should create distinct sessions
        assertNotEquals(manager1.sessionID, manager2.sessionID)
    }
}
```

### 2. Store File Cleanup Tests

```kotlin
// Add to: FixConnectionManagerTest.kt
class StoreFileCleanupTest {

    @Test
    fun testClearStoreFilesWithQualifier() {
        val testDir = createTempDir()

        // Create mock store files
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.body").createNewFile()
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.header").createNewFile()
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-LOCAL.body").createNewFile()
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-LOCAL.header").createNewFile()

        val config = FixConnectionConfig(
            senderCompID = "BUYER_FIRM",
            targetCompID = "EXCHANGE_TARGET",
            sessionQualifier = "DEV1",
            fileStorePath = testDir.absolutePath,
            resetOnLogon = true
        )

        val manager = FixConnectionManager(config, mockService, appSettings, dictionary)
        manager.start()

        // DEV1 files should be deleted
        assertFalse(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.body").exists())
        assertFalse(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.header").exists())

        // LOCAL files should remain
        assertTrue(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-LOCAL.body").exists())
        assertTrue(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-LOCAL.header").exists())
    }

    @Test
    fun testClearStoreFilesWithoutQualifierDoesNotDeleteQualifiedFiles() {
        val testDir = createTempDir()

        // Create files with and without qualifiers
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET.body").createNewFile()
        File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.body").createNewFile()

        val config = FixConnectionConfig(
            senderCompID = "BUYER_FIRM",
            targetCompID = "EXCHANGE_TARGET",
            sessionQualifier = "",
            fileStorePath = testDir.absolutePath,
            resetOnLogon = true
        )

        val manager = FixConnectionManager(config, mockService, appSettings, dictionary)
        manager.start()

        // Non-qualified files should be deleted
        assertFalse(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET.body").exists())

        // Qualified files should remain
        assertTrue(File(testDir, "FIX.4.4-BUYER_FIRM-EXCHANGE_TARGET-DEV1.body").exists())
    }
}
```

## 🟡 MEDIUM PRIORITY - Edge Cases & Validation

### 3. SessionQualifier Validation Tests

```kotlin
// Add to: ConnectionPanelTest.kt or create new ValidationTest.kt
class SessionQualifierValidationTest {

    @Test
    fun testSessionQualifierWithSpecialCharacters() {
        val profile = createTestProfile(
            sessionQualifier = "ENV-1_TEST.2025"
        )

        // Should accept alphanumeric, hyphens, underscores, dots
        assertEquals("ENV-1_TEST.2025", profile.config.sessionQualifier)
    }

    @Test
    fun testSessionQualifierMaxLength() {
        val longQualifier = "A".repeat(255)
        val profile = createTestProfile(
            sessionQualifier = longQualifier
        )

        // Should accept long qualifiers
        assertEquals(longQualifier, profile.config.sessionQualifier)
    }

    @Test
    fun testSessionQualifierCaseSensitivity() {
        val profile1 = createTestProfile(
            id = "p1",
            sessionQualifier = "dev1"
        )
        val profile2 = createTestProfile(
            id = "p2",
            sessionQualifier = "DEV1"
        )

        // "dev1" and "DEV1" should be treated as different qualifiers
        assertNotEquals(profile1.config.sessionQualifier, profile2.config.sessionQualifier)
    }
}
```

### 4. Profile Migration Tests

```kotlin
// Add to: ConnectionProfileServiceTest.kt
class ProfileMigrationTest {

    @Test
    fun testUpgradeMultipleProfilesFromNoQualifierToQualified() {
        // Simulate upgrade scenario: user has multiple profiles with same credentials
        val legacyJson = """
            {
                "profiles": [
                    {
                        "id": "dev1",
                        "name": "DEV1-BuySide",
                        "config": {
                            "senderCompID": "BUYER_FIRM",
                            "targetCompID": "EXCHANGE_TARGET",
                            "port": "4440"
                        }
                    },
                    {
                        "id": "local",
                        "name": "Local-BuySide",
                        "config": {
                            "senderCompID": "BUYER_FIRM",
                            "targetCompID": "EXCHANGE_TARGET",
                            "port": "9191"
                        }
                    }
                ]
            }
        """.trimIndent()

        testProfilesFile.writeText(legacyJson)
        val loadedContainer = json.decodeFromString<ProfilesContainer>(testProfilesFile.readText())

        // Both profiles load successfully without sessionQualifier
        assertEquals(2, loadedContainer.profiles.size)
        assertEquals("", loadedContainer.profiles[0].config.sessionQualifier)
        assertEquals("", loadedContainer.profiles[1].config.sessionQualifier)

        // Now add qualifiers and save
        val upgraded = loadedContainer.profiles.map { profile ->
            when (profile.id) {
                "dev1" -> profile.copy(config = profile.config.copy(sessionQualifier = "DEV1"))
                "local" -> profile.copy(config = profile.config.copy(sessionQualifier = "LOCAL"))
                else -> profile
            }
        }

        val upgradedContainer = ProfilesContainer(upgraded)
        testProfilesFile.writeText(json.encodeToString(upgradedContainer))

        // Reload and verify upgrade worked
        val reloadedContainer = json.decodeFromString<ProfilesContainer>(testProfilesFile.readText())
        assertEquals("DEV1", reloadedContainer.profiles.find { it.id == "dev1" }?.config?.sessionQualifier)
        assertEquals("LOCAL", reloadedContainer.profiles.find { it.id == "local" }?.config?.sessionQualifier)
    }
}
```

## 🟢 LOW PRIORITY - Integration Tests

### 5. ViewModel Integration Tests

```kotlin
// Add to: MessageEditorIntegrationTest.kt
class SessionQualifierViewModelTest {

    @Test
    fun testViewModelHandlesMultipleSessionsWithSameCredentials() {
        val viewModel = FixMessageViewModel()

        val dev1Profile = FixConnectionProfile(
            id = "dev1",
            name = "DEV1",
            config = FixConnectionConfig(
                senderCompID = "BUYER_FIRM",
                targetCompID = "EXCHANGE_TARGET",
                sessionQualifier = "DEV1",
                port = "4440"
            )
        )

        val localProfile = FixConnectionProfile(
            id = "local",
            name = "LOCAL",
            config = FixConnectionConfig(
                senderCompID = "BUYER_FIRM",
                targetCompID = "EXCHANGE_TARGET",
                sessionQualifier = "LOCAL",
                port = "9191"
            )
        )

        // Connect both profiles
        viewModel.connectProfile(dev1Profile.id, dev1Profile)
        viewModel.connectProfile(localProfile.id, localProfile)

        // Both should create separate sessions
        assertEquals(2, viewModel.sessions.size)

        // Switch between sessions
        viewModel.setActiveSession(0) // DEV1
        assertEquals("DEV1", viewModel.activeSession?.title)

        viewModel.setActiveSession(1) // LOCAL
        assertEquals("LOCAL", viewModel.activeSession?.title)
    }
}
```

---

## Implementation Priority

1. ✅ **HIGH**: FixConnectionManager tests (#1, #2)
2. ⚠️ **MEDIUM**: Validation and migration tests (#3, #4)
3. 📋 **LOW**: Integration tests (#5)
