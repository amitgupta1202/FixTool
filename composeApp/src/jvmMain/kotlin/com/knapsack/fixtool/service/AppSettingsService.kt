package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Service for persisting and loading application settings
 * @param onError Optional callback for error notifications
 * @param customSettingsDir Optional custom directory path for settings file (for testing)
 */
class AppSettingsService(
    private val onError: ((String) -> Unit)? = null,
    customSettingsDir: String? = null,
) {
    private val logger = NotifyingLogger(AppSettingsService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true // Always encode all fields, even if they have default values
        }

    private val settingsFile =
        if (customSettingsDir != null) {
            File(customSettingsDir, "app_settings.json")
        } else {
            File(System.getProperty("user.home"), ".fixtool/app_settings.json")
        }

    init {
        // Ensure directory exists
        settingsFile.parentFile?.mkdirs()
    }

    /**
     * Loads application settings from disk
     */
    fun loadSettings(): AppSettings =
        try {
            if (!settingsFile.exists()) {
                logger.info("Settings file not found, using defaults")
                AppSettings.default()
            } else {
                val content = settingsFile.readText()
                logger.debug("Loading settings from: {}", settingsFile.absolutePath)
                logger.debug("Settings content: {}", content)
                val settings = json.decodeFromString<AppSettings>(content)
                logger.info("Settings loaded successfully. Dictionary path: '{}'", settings.defaultDataDictionary)
                settings
            }
        } catch (e: Exception) {
            logger.error("Failed to load settings from ${settingsFile.absolutePath}: ${e.message}", e, notifyUser = true)
            logger.warn("Returning default settings due to load failure")
            AppSettings.default()
        }

    /**
     * Saves application settings to disk
     * @return true if save succeeded, false if failed
     */
    fun saveSettings(settings: AppSettings): Boolean =
        try {
            val content = json.encodeToString(settings)
            settingsFile.writeText(content)
            logger.info("Settings saved to: {}. Dictionary path: '{}'", settingsFile.absolutePath, settings.defaultDataDictionary)
            true
        } catch (e: Exception) {
            logger.error("Failed to save settings: ${e.message}", e, notifyUser = true)
            false
        }
}
