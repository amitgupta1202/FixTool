package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Service for persisting and loading application settings
 */
class AppSettingsService {
    companion object {
        private val logger = LoggerFactory.getLogger(AppSettingsService::class.java)
    }

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val settingsFile = File(System.getProperty("user.home"), ".fixtool/app_settings.json")

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
                val settings = json.decodeFromString<AppSettings>(content)
                logger.info("Settings loaded from: {}", settingsFile.absolutePath)
                settings
            }
        } catch (e: Exception) {
            logger.error("Failed to load settings: {}", e.message, e)
            AppSettings.default()
        }

    /**
     * Saves application settings to disk
     * @return true if save succeeded, false if failed
     */
    fun saveSettings(settings: AppSettings): Boolean {
        return try {
            val content = json.encodeToString(settings)
            settingsFile.writeText(content)
            logger.info("Settings saved to: {}", settingsFile.absolutePath)
            true
        } catch (e: Exception) {
            logger.error("Failed to save settings: {}", e.message, e)
            false
        }
    }
}
