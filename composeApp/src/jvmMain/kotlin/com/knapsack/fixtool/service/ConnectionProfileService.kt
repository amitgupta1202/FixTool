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
            WorkspacePaths.current.connectionProfiles
        }

    /**
     * Beside the profiles, whatever directory those turned out to be in.
     *
     * Derived from [profilesFile] rather than from [WorkspacePaths], because a caller that pointed the
     * profiles somewhere specific means the passwords for those profiles, not the ones belonging to
     * whichever workspace happens to be open.
     */
    private val secrets = ProfileSecrets(File(profilesFile.parentFile ?: File("."), "secrets.json"))

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
                val profiles = secrets.applyTo(container.profiles)
                // A file written before the split still has its passwords inline. Rewriting it here
                // rather than waiting for the next save means the credential stops being in the
                // shareable file at the first opportunity, and the user never had to do anything.
                if (secrets.needsMigration(container.profiles)) {
                    logger.info("Moving ${profilesFile.name} passwords into secrets.json")
                    saveProfiles(profiles)
                }
                profiles
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
            val container = ProfilesContainer(secrets.extractFrom(profiles))
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
        secrets.forget(profileId)
        return if (saveProfiles(profiles)) {
            Result.success(profiles)
        } else {
            Result.failure(java.io.IOException("Failed to delete profile"))
        }
    }
}
