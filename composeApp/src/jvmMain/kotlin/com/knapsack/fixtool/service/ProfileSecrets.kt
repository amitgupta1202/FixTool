package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Passwords, kept out of the file a workspace is meant to be shared as.
 *
 * `connection_profiles.json` is the interesting half of a workspace: the CompIDs, the hosts, the
 * acceptor rules, the things worth committing beside the code they test. It also held the logon
 * password in plain text, which made the whole file unshareable — you could not put a workspace in a
 * repository, hand one to a colleague, or attach one to a ticket without handing over a credential
 * too. In practice that means nobody shares one, and the feature is theoretical.
 *
 * So the passwords live in a sibling `secrets.json` and nothing else moves. This is **not**
 * encryption and does not pretend to be: the file sits in the same directory with the same
 * permissions, and anything that can read one can read the other. What it buys is that the file you
 * would copy, commit or send is not the file with the password in it — a separation of what is shared
 * from what is secret, which is the actual failure mode.
 */
class ProfileSecrets(
    private val file: File,
) {
    private val logger = LoggerFactory.getLogger(ProfileSecrets::class.java)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @Serializable
    private data class Secrets(
        val passwords: Map<String, String> = emptyMap(),
    )

    private fun read(): Secrets =
        try {
            if (file.exists()) json.decodeFromString<Secrets>(file.readText()) else Secrets()
        } catch (e: IOException) {
            logger.error("Could not read ${file.name}; treating it as empty", e)
            Secrets()
        } catch (e: SerializationException) {
            logger.error("${file.name} is not readable JSON; treating it as empty", e)
            Secrets()
        }

    private fun write(secrets: Secrets): Boolean =
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(secrets))
            true
        } catch (e: IOException) {
            logger.error("Could not write ${file.name}", e)
            false
        } catch (e: SerializationException) {
            logger.error("Could not serialise ${file.name}", e)
            false
        }

    /** Puts each profile's password back on it, for the app to use as it always has. */
    fun applyTo(profiles: List<FixConnectionProfile>): List<FixConnectionProfile> {
        val passwords = read().passwords
        if (passwords.isEmpty()) {
            return profiles
        }
        return profiles.map { profile ->
            val password = passwords[profile.id]
            // A password already on the profile wins: that is either an unmigrated file being read for
            // the first time, or a caller that has just set one, and both are the newer truth.
            if (password.isNullOrEmpty() || profile.config.password.isNotEmpty()) {
                profile
            } else {
                profile.copy(config = profile.config.copy(password = password))
            }
        }
    }

    /**
     * Records the passwords and returns the profiles without them, ready to be written.
     *
     * Only the profiles given are touched. A password for an id that is not in the list is left
     * alone, because saving one profile must not forget another's — and `saveProfile` reaches here
     * through a full list, while a caller in the future might not.
     */
    fun extractFrom(profiles: List<FixConnectionProfile>): List<FixConnectionProfile> {
        val existing = read().passwords
        val updated = existing.toMutableMap()
        profiles.forEach { profile ->
            val password = profile.config.password
            if (password.isEmpty()) {
                updated.remove(profile.id)
            } else {
                updated[profile.id] = password
            }
        }
        if (updated != existing) {
            write(Secrets(updated))
        }
        return profiles.map { it.copy(config = it.config.copy(password = "")) }
    }

    /** Forgets one profile's password, for a profile that has been deleted. */
    fun forget(profileId: String) {
        val secrets = read()
        if (secrets.passwords.containsKey(profileId)) {
            write(secrets.copy(passwords = secrets.passwords - profileId))
        }
    }

    /** True when [profiles] still carry passwords inline, so the file wants rewriting once. */
    fun needsMigration(profiles: List<FixConnectionProfile>): Boolean = profiles.any { it.config.password.isNotEmpty() }
}
