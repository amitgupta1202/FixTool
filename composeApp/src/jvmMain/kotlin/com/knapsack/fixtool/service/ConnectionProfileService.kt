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
    customPath: String = "",
) {
    private val logger = NotifyingLogger(ConnectionProfileService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val profilesFile =
        if (customPath.isNotBlank()) {
            File(customPath)
        } else {
            File(System.getProperty("user.home"), ".fixtool/connection_profiles.json")
        }

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

    /**
     * Saves profiles to disk
     * @return true if save succeeded, false if failed
     */
    fun saveProfiles(profiles: List<FixConnectionProfile>): Boolean =
        try {
            val container = ProfilesContainer(profiles)
            val content = json.encodeToString(container)
            profilesFile.writeText(content)
            true
        } catch (e: Exception) {
            val errorMsg = "Failed to save connection profiles: ${e.message}"
            logger.error(errorMsg, e, notifyUser = true)
            false
        }

    /**
     * Saves a profile (creates new or updates existing)
     * @return Result with updated profile list on success, or failure with exception
     */
    fun saveProfile(profile: FixConnectionProfile): Result<List<FixConnectionProfile>> {
        val profiles = loadProfiles().toMutableList()
        val existingIndex = profiles.indexOfFirst { it.id == profile.id }

        if (existingIndex >= 0) {
            profiles[existingIndex] = profile
        } else {
            profiles.add(profile)
        }

        return if (saveProfiles(profiles)) {
            Result.success(profiles)
        } else {
            Result.failure(java.io.IOException("Failed to save profile"))
        }
    }

    /**
     * Deletes a profile by ID
     * @return Result with updated profile list on success, or failure with exception
     */
    fun deleteProfile(profileId: String): Result<List<FixConnectionProfile>> {
        val profiles = loadProfiles().filterNot { it.id == profileId }
        return if (saveProfiles(profiles)) {
            Result.success(profiles)
        } else {
            Result.failure(java.io.IOException("Failed to delete profile"))
        }
    }
}
