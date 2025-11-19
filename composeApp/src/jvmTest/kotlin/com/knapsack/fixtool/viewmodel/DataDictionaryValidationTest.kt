package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.NotificationType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for data dictionary validation on app startup
 */
class DataDictionaryValidationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        // Create a temporary directory for test files (isolated from production)
        testDir =
            File.createTempFile("fixtool-test", "").apply {
                delete() // Delete the file
                mkdirs() // Create as directory
            }
    }

    @After
    fun cleanup() {
        // Clean up test directory and all files
        testDir.deleteRecursively()
    }

    // ========================================
    // Dictionary Validation Tests
    // ========================================

    @Test
    fun testNoDictionaryConfigured_ShowsError() {
        // Given: No data dictionary configured (empty path)
        // When: ViewModel is initialized
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Then: Dictionary should be invalid
        assertFalse(viewModel.isDictionaryValid.value, "Dictionary should be invalid when not configured")

        // And: Error message should indicate dictionary is not configured
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertNotNull(errorMessage, "Error message should not be null")
        assertTrue(
            errorMessage.contains("configure", ignoreCase = true) ||
                errorMessage.contains(
                    "not configured",
                    ignoreCase = true,
                ),
            "Error message should mention dictionary configuration. Got: $errorMessage",
        )

        // And: An error notification should be created
        val notifications = viewModel.notifications
        assertTrue(notifications.isNotEmpty(), "Should have at least one notification")
        val errorNotification = notifications.firstOrNull { it.type == NotificationType.ERROR }
        assertNotNull(errorNotification, "Should have an error notification")
        assertTrue(
            errorNotification.message.contains("data dictionary", ignoreCase = true),
            "Error notification should mention data dictionary. Got: ${errorNotification.message}",
        )
    }

    @Test
    fun testInvalidDictionaryPath_ShowsError() {
        // Given: An invalid data dictionary path
        val invalidPath = "/non/existent/path/dictionary.xml"
        val settings = AppSettings(defaultDataDictionary = invalidPath)

        // When: Settings are saved and ViewModel is initialized
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        settingsService.saveSettings(settings)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Then: Dictionary should be invalid
        assertFalse(viewModel.isDictionaryValid.value, "Dictionary should be invalid for non-existent file")

        // And: Error message should indicate file not found
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertNotNull(errorMessage, "Error message should not be null")
        assertTrue(
            errorMessage.contains("not found", ignoreCase = true),
            "Error message should mention file not found. Got: $errorMessage",
        )
        assertTrue(
            errorMessage.contains(invalidPath),
            "Error message should contain the invalid path. Got: $errorMessage",
        )

        // And: An error notification should be created
        val notifications = viewModel.notifications
        assertTrue(notifications.isNotEmpty(), "Should have at least one notification")
        val errorNotification = notifications.firstOrNull { it.type == NotificationType.ERROR }
        assertNotNull(errorNotification, "Should have an error notification")
        assertTrue(
            errorNotification.message.contains("not found", ignoreCase = true),
            "Error notification should mention file not found. Got: ${errorNotification.message}",
        )
    }

    @Test
    fun testValidDictionaryPath_NoError() {
        // Given: A valid data dictionary path (create a temporary test file)
        val tempDictionary = File.createTempFile("test-dictionary", ".xml")
        tempDictionary.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <fix type="FIX" major="4" minor="4">
                <header>
                    <field number="8" name="BeginString" type="STRING"/>
                    <field number="9" name="BodyLength" type="LENGTH"/>
                    <field number="35" name="MsgType" type="STRING"/>
                </header>
                <trailer>
                    <field number="10" name="CheckSum" type="STRING"/>
                </trailer>
                <fields>
                    <field number="11" name="ClOrdID" type="STRING"/>
                    <field number="55" name="Symbol" type="STRING"/>
                </fields>
            </fix>
            """.trimIndent(),
        )

        try {
            // When: Settings are saved with valid path and ViewModel is initialized
            val validPath = tempDictionary.absolutePath
            val settingsService =
                com.knapsack.fixtool.service
                    .AppSettingsService(customSettingsDir = testDir.absolutePath)
            val settings = AppSettings(defaultDataDictionary = validPath)
            settingsService.saveSettings(settings)
            viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

            // Then: Dictionary should be valid
            assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid for existing file")

            // And: No error message should be set
            val errorMessage = viewModel.dictionaryErrorMessage.value
            assertTrue(
                errorMessage == null || errorMessage.isEmpty(),
                "Error message should be null or empty for valid dictionary. Got: $errorMessage",
            )
        } finally {
            // Cleanup temp file
            tempDictionary.delete()
        }
    }

    @Test
    fun testSaveSettings_WithInvalidPath_UpdatesValidationState() {
        // Given: ViewModel is initialized with no dictionary
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Clear any initial notifications
        viewModel.notifications.forEach { viewModel.dismissNotification(it.id) }

        // When: Settings are saved with an invalid path
        val invalidPath = "/non/existent/path/dictionary.xml"
        val newSettings = AppSettings(defaultDataDictionary = invalidPath)
        viewModel.saveAppSettings(newSettings)

        // Then: Dictionary should be invalid
        assertFalse(viewModel.isDictionaryValid.value, "Dictionary should be invalid after saving invalid path")

        // And: Error message should be updated
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertNotNull(errorMessage, "Error message should be set")
        assertTrue(
            errorMessage.contains(invalidPath),
            "Error message should contain the invalid path. Got: $errorMessage",
        )

        // And: A new error notification should be created
        val notifications = viewModel.notifications
        val errorNotification = notifications.lastOrNull { it.type == NotificationType.ERROR }
        assertNotNull(errorNotification, "Should have a new error notification after saving invalid settings")
    }

    @Test
    fun testSaveSettings_WithValidPath_ClearsValidationError() {
        // Given: ViewModel is initialized with invalid dictionary
        val invalidPath = "/non/existent/path/dictionary.xml"
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        val initialSettings = AppSettings(defaultDataDictionary = invalidPath)
        settingsService.saveSettings(initialSettings)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Verify initial state is invalid
        assertFalse(viewModel.isDictionaryValid.value, "Should start with invalid dictionary")

        // Create a valid temporary dictionary file
        val tempDictionary = File.createTempFile("test-dictionary", ".xml")
        tempDictionary.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <fix type="FIX" major="4" minor="4">
                <header>
                    <field number="8" name="BeginString" type="STRING"/>
                </header>
                <trailer>
                    <field number="10" name="CheckSum" type="STRING"/>
                </trailer>
            </fix>
            """.trimIndent(),
        )

        try {
            // When: Settings are saved with a valid path
            val validPath = tempDictionary.absolutePath
            val newSettings = AppSettings(defaultDataDictionary = validPath)
            viewModel.saveAppSettings(newSettings)

            // Then: Dictionary should now be valid
            assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid after saving valid path")

            // And: Error message should be cleared
            val errorMessage = viewModel.dictionaryErrorMessage.value
            assertTrue(
                errorMessage == null || errorMessage.isEmpty(),
                "Error message should be cleared. Got: $errorMessage",
            )
        } finally {
            // Cleanup temp file
            tempDictionary.delete()
        }
    }

    @Test
    fun testSaveSettings_WithEmptyPath_ShowsConfigurationError() {
        // Given: ViewModel is initialized
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Clear any initial notifications
        viewModel.notifications.forEach { viewModel.dismissNotification(it.id) }

        // When: Settings are saved with an empty path
        val newSettings = AppSettings(defaultDataDictionary = "")
        viewModel.saveAppSettings(newSettings)

        // Then: Dictionary should be invalid
        assertFalse(viewModel.isDictionaryValid.value, "Dictionary should be invalid with empty path")

        // And: Error message should indicate not configured
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertNotNull(errorMessage, "Error message should be set")
        assertTrue(
            errorMessage.contains("configure", ignoreCase = true) ||
                errorMessage.contains(
                    "not configured",
                    ignoreCase = true,
                ),
            "Error message should mention configuration. Got: $errorMessage",
        )

        // And: An error notification should be created
        val errorNotification = viewModel.notifications.lastOrNull { it.type == NotificationType.ERROR }
        assertNotNull(errorNotification, "Should have an error notification")
        assertTrue(
            errorNotification.message.contains("configure", ignoreCase = true),
            "Error notification should mention configuration. Got: ${errorNotification.message}",
        )
    }
}
