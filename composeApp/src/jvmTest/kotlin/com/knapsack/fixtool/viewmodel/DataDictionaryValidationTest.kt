package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixVersion
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
    fun testNoDictionaryConfigured_WithBundledDisabled_FallsBackToBundled() {
        // Given: No data dictionary configured (empty path) AND bundled dictionary disabled
        val settings =
            AppSettings(
                defaultDataDictionary = "",
                useBundledDictionary = false,
            )
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        settingsService.saveSettings(settings)

        // When: ViewModel is initialized
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Then: Dictionary should still be valid (falls back to bundled for better UX)
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid (falls back to bundled)")

        // And: No error message should be set (fallback is silent)
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be null or empty when falling back to bundled. Got: $errorMessage",
        )
    }

    @Test
    fun testNoDictionaryConfigured_WithBundledEnabled_UsesDefault() {
        // Given: No data dictionary configured (empty path) but bundled dictionary enabled (default)
        val settings =
            AppSettings(
                defaultDataDictionary = "",
                useBundledDictionary = true,
                defaultFixVersion = FixVersion.FIX_4_4,
            )
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        settingsService.saveSettings(settings)

        // When: ViewModel is initialized
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Then: Dictionary should be valid (using bundled dictionary)
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid when using bundled dictionary")

        // And: No error message should be set
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be null or empty when using bundled dictionary. Got: $errorMessage",
        )
    }

    @Test
    fun testInvalidDictionaryPath_FallsBackToBundled() {
        // Given: An invalid data dictionary path with bundled disabled
        val invalidPath = "/non/existent/path/dictionary.xml"
        val settings =
            AppSettings(
                defaultDataDictionary = invalidPath,
                useBundledDictionary = false,
            )

        // When: Settings are saved and ViewModel is initialized
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        settingsService.saveSettings(settings)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Then: Dictionary should still be valid (falls back to bundled for better UX)
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid (falls back to bundled)")

        // And: No error message should be set (fallback is silent, only a warning is logged)
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be null or empty when falling back to bundled. Got: $errorMessage",
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
            val settings =
                AppSettings(
                    defaultDataDictionary = validPath,
                    useBundledDictionary = false,
                )
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
    fun testSaveSettings_WithInvalidPath_FallsBackToBundled() {
        // Given: ViewModel is initialized with bundled dictionary
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Clear any initial notifications
        viewModel.notifications.forEach { viewModel.dismissNotification(it.id) }

        // When: Settings are saved with an invalid path and bundled disabled
        val invalidPath = "/non/existent/path/dictionary.xml"
        val newSettings =
            AppSettings(
                defaultDataDictionary = invalidPath,
                useBundledDictionary = false,
            )
        viewModel.saveAppSettings(newSettings)

        // Then: Dictionary should still be valid (falls back to bundled for better UX)
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid (falls back to bundled)")

        // And: No error message should be set (fallback is silent)
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be null or empty when falling back to bundled. Got: $errorMessage",
        )
    }

    @Test
    fun testSaveSettings_WithValidPath_LoadsCustomDictionary() {
        // Given: ViewModel is initialized (uses bundled dictionary by default due to fallback)
        val invalidPath = "/non/existent/path/dictionary.xml"
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        val initialSettings =
            AppSettings(
                defaultDataDictionary = invalidPath,
                useBundledDictionary = false,
            )
        settingsService.saveSettings(initialSettings)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Verify initial state is valid (due to fallback to bundled)
        assertTrue(viewModel.isDictionaryValid.value, "Should start with valid dictionary (bundled fallback)")

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
            val newSettings =
                AppSettings(
                    defaultDataDictionary = validPath,
                    useBundledDictionary = false,
                )
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
    fun testSaveSettings_WithEmptyPath_AndBundledDisabled_FallsBackToBundled() {
        // Given: ViewModel is initialized
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Clear any initial notifications
        viewModel.notifications.forEach { viewModel.dismissNotification(it.id) }

        // When: Settings are saved with an empty path and bundled disabled
        val newSettings =
            AppSettings(
                defaultDataDictionary = "",
                useBundledDictionary = false,
            )
        viewModel.saveAppSettings(newSettings)

        // Then: Dictionary should still be valid (falls back to bundled for better UX)
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid (falls back to bundled)")

        // And: No error message should be set (fallback is silent)
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be null or empty when falling back to bundled. Got: $errorMessage",
        )
    }

    @Test
    fun testSaveSettings_SwitchToBundled_KeepsDictionaryValid() {
        // Given: ViewModel is initialized with invalid dictionary path (falls back to bundled)
        val invalidPath = "/non/existent/path/dictionary.xml"
        val settingsService =
            com.knapsack.fixtool.service
                .AppSettingsService(customSettingsDir = testDir.absolutePath)
        val initialSettings =
            AppSettings(
                defaultDataDictionary = invalidPath,
                useBundledDictionary = false,
            )
        settingsService.saveSettings(initialSettings)
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

        // Verify initial state is valid (due to fallback to bundled)
        assertTrue(viewModel.isDictionaryValid.value, "Should start with valid dictionary (bundled fallback)")

        // When: Settings are saved to use bundled dictionary
        val newSettings =
            AppSettings(
                defaultDataDictionary = "",
                useBundledDictionary = true,
                defaultFixVersion = FixVersion.FIX_4_4,
            )
        viewModel.saveAppSettings(newSettings)

        // Then: Dictionary should now be valid
        assertTrue(viewModel.isDictionaryValid.value, "Dictionary should be valid after switching to bundled")

        // And: Error message should be cleared
        val errorMessage = viewModel.dictionaryErrorMessage.value
        assertTrue(
            errorMessage == null || errorMessage.isEmpty(),
            "Error message should be cleared. Got: $errorMessage",
        )
    }

    @Test
    fun testBundledDictionary_DifferentVersions() {
        // Test that all bundled FIX versions can be loaded
        FixVersion.entries.forEach { version ->
            val settings =
                AppSettings(
                    useBundledDictionary = true,
                    defaultFixVersion = version,
                )
            val settingsService =
                com.knapsack.fixtool.service
                    .AppSettingsService(customSettingsDir = testDir.absolutePath)
            settingsService.saveSettings(settings)

            viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)

            assertTrue(
                viewModel.isDictionaryValid.value,
                "Bundled dictionary for ${version.displayName} should be valid",
            )

            val errorMessage = viewModel.dictionaryErrorMessage.value
            assertTrue(
                errorMessage == null || errorMessage.isEmpty(),
                "No error for ${version.displayName}. Got: $errorMessage",
            )
        }
    }
}
