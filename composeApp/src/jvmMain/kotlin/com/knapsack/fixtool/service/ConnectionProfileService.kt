package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Service for persisting and loading connection profiles
 */
class ConnectionProfileService(
    private val onError: ((String) -> Unit)? = null,
) {
    private val logger = NotifyingLogger(ConnectionProfileService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val profilesFile = File(System.getProperty("user.home"), ".fixtool/connection_profiles.json")

    init {
        // Ensure directory exists
        profilesFile.parentFile?.mkdirs()
    }

    @Serializable
    private data class ProfilesContainer(
        val profiles: List<FixConnectionProfile>,
    )

    /**
     * Loads all saved profiles from disk
     */
    fun loadProfiles(): List<FixConnectionProfile> =
        try {
            if (!profilesFile.exists()) {
                emptyList()
            } else {
                val content = profilesFile.readText()
                val container = json.decodeFromString<ProfilesContainer>(content)
                container.profiles
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to load connection profiles: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
            emptyList()
        }

    fun saveProfiles(profiles: List<FixConnectionProfile>) {
        try {
            val container = ProfilesContainer(profiles)
            val content = json.encodeToString(container)
            profilesFile.writeText(content)
        } catch (e: Exception) {
            val errorMsg = "Failed to save connection profiles: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
        }
    }

    fun saveProfile(profile: FixConnectionProfile): List<FixConnectionProfile> {
        val profiles = loadProfiles().toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == profile.id }

        if (existingIndex >= 0) {
            profiles[existingIndex] = profile
        } else {
            profiles.add(profile)
        }

        saveProfiles(profiles)
        return profiles
    }

    fun deleteProfile(profileId: String): List<FixConnectionProfile> {
        val profiles = loadProfiles().filterNot { it.id == profileId }
        saveProfiles(profiles)
        return profiles
    }
}
