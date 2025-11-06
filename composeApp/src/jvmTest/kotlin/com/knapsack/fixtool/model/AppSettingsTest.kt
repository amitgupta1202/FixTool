package com.knapsack.fixtool.model

import com.knapsack.fixtool.service.AppSettingsService
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsTest {
    private lateinit var originalFile: File
    private lateinit var testFile: File

    @Before
    fun setup() {
        // Save reference to the real file and temporarily move it
        originalFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json")
        val backupFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json.backup")

        // Backup existing file if it exists
        if (originalFile.exists()) {
            originalFile.copyTo(backupFile, overwrite = true)
            originalFile.delete()
        }

        testFile = originalFile
    }

    @After
    fun cleanup() {
        // Clean up test data and restore original file
        if (testFile.exists()) {
            testFile.delete()
        }

        // Restore backup if it exists
        val backupFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json.backup")
        if (backupFile.exists()) {
            backupFile.copyTo(originalFile, overwrite = true)
            backupFile.delete()
        }
    }

    @Test
    fun testDefaultSettingsHasEmptyGridViewColumns() {
        // Given: Default settings
        val settings = AppSettings.default()

        // Then: gridViewColumns should have default columns (11, 131, 693)
        assertEquals(listOf(11, 131, 693), settings.gridViewColumns)
    }

    @Test
    fun testSaveAndLoadSettingsWithGridViewColumns() {
        // Given: Settings with grid view columns configured
        val service = AppSettingsService()
        val testColumns = listOf(35, 49, 56, 11, 55)
        val settings =
            AppSettings(
                defaultDataDictionary = "/path/to/dict.xml",
                gridViewColumns = testColumns,
            )

        // When: We save and reload the settings
        service.saveSettings(settings)
        val loadedSettings = service.loadSettings()

        // Then: Grid view columns should be preserved
        assertEquals(testColumns, loadedSettings.gridViewColumns)
        assertEquals("/path/to/dict.xml", loadedSettings.defaultDataDictionary)
    }

    @Test
    fun testSettingsCopyWithGridViewColumns() {
        // Given: Original settings with some columns
        val original =
            AppSettings(
                defaultDataDictionary = "/original/dict.xml",
                gridViewColumns = listOf(35, 49),
            )

        // When: We create a copy with different columns
        val modified =
            original.copy(
                gridViewColumns = listOf(35, 49, 56, 11),
            )

        // Then: The copy should have the new columns
        assertEquals(listOf(35, 49, 56, 11), modified.gridViewColumns)
        assertEquals("/original/dict.xml", modified.defaultDataDictionary)

        // And: Original should remain unchanged
        assertEquals(listOf(35, 49), original.gridViewColumns)
    }

    @Test
    fun testSerializationOfEmptyGridViewColumns() {
        // Given: Settings with empty grid view columns
        val service = AppSettingsService()
        val settings =
            AppSettings(
                defaultDataDictionary = "/path/to/dict.xml",
                gridViewColumns = emptyList(),
            )

        // When: We save and reload
        service.saveSettings(settings)
        val loadedSettings = service.loadSettings()

        // Then: Empty list should be preserved
        assertTrue(loadedSettings.gridViewColumns.isEmpty())
    }

    @Test
    fun testSerializationOfManyGridViewColumns() {
        // Given: Settings with many grid view columns
        val service = AppSettingsService()
        val manyColumns = (1..50).toList()
        val settings =
            AppSettings(
                defaultDataDictionary = "/path/to/dict.xml",
                gridViewColumns = manyColumns,
            )

        // When: We save and reload
        service.saveSettings(settings)
        val loadedSettings = service.loadSettings()

        // Then: All columns should be preserved in order
        assertEquals(manyColumns, loadedSettings.gridViewColumns)
    }

    @Test
    fun testGridViewColumnsWithDuplicates() {
        // Given: Settings with duplicate columns
        val service = AppSettingsService()
        val columnsWithDuplicates = listOf(35, 49, 35, 56, 49)
        val settings =
            AppSettings(
                defaultDataDictionary = "/path/to/dict.xml",
                gridViewColumns = columnsWithDuplicates,
            )

        // When: We save and reload
        service.saveSettings(settings)
        val loadedSettings = service.loadSettings()

        // Then: Duplicates should be preserved (validation happens in UI)
        assertEquals(columnsWithDuplicates, loadedSettings.gridViewColumns)
    }
}
