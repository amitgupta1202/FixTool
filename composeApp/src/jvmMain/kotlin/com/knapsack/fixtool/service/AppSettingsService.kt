package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import com.knapsack.fixtool.util.UnreadableFileGuard
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
            WorkspacePaths.home.appSettings
        }

    /** A settings file that could not be read is not saved over. See [UnreadableFileGuard]. */
    private val guard = UnreadableFileGuard(settingsFile)

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
                guard.read(succeeded = true)
                logger.info("Settings file not found, using defaults")
                AppSettings.default()
            } else {
                val content = settingsFile.readText()
                logger.debug("Loading settings from: {}", settingsFile.absolutePath)
                logger.debug("Settings content: {}", content)
                val settings = json.decodeFromString<AppSettings>(content)
                guard.read(succeeded = true)
                logger.info("Settings loaded successfully. Dictionary path: '{}'", settings.defaultDataDictionary)
                settings
            }
        } catch (e: Exception) {
            guard.read(succeeded = false)
            logger.error("Failed to load settings from ${settingsFile.absolutePath}: ${e.message}", e, notifyUser = true)
            logger.warn("Returning default settings due to load failure")
            AppSettings.default()
        }

    /**
     * Saves application settings to disk
     *
     * Refused while the settings file is one the last load could not read, because [settings] is then the
     * defaults that load answered with plus whatever changed since, and a tabs/split toggle is enough to save
     * them. The user is told which file and why.
     *
     * @return true if save succeeded, false if failed
     */
    fun saveSettings(settings: AppSettings): Boolean {
        guard.refusal()?.let { why ->
            logger.error(why, notifyUser = true)
            return false
        }
        return try {
            val content = json.encodeToString(settings)
            settingsFile.parentFile?.mkdirs()
            AtomicFiles.writeAtomically(settingsFile, content)
            logger.info("Settings saved to: {}. Dictionary path: '{}'", settingsFile.absolutePath, settings.defaultDataDictionary)
            true
        } catch (e: Exception) {
            logger.error("Failed to save settings: ${e.message}", e, notifyUser = true)
            false
        }
    }
}
