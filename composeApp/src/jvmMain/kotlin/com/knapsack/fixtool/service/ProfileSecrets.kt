package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.UnreadableFileGuard
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
 *
 * The keystore and truststore passwords an SSL profile carries move with the logon password, for the
 * same reason. The control surface already treated all three as secrets, and a committed workspace
 * still carried two of them in clear text.
 */
class ProfileSecrets(
    private val file: File,
) {
    private val logger = LoggerFactory.getLogger(ProfileSecrets::class.java)

    /** A secrets file that could not be read is not saved over. See [UnreadableFileGuard]. */
    private val guard = UnreadableFileGuard(file)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /** The secrets a profile carries, each read off its config and put back on it the same way. */
    private enum class Kind(
        val of: (FixConnectionConfig) -> String,
        val put: (FixConnectionConfig, String) -> FixConnectionConfig,
    ) {
        LOGON({ it.password }, { config, value -> config.copy(password = value) }),
        KEY_STORE({ it.keyStorePassword }, { config, value -> config.copy(keyStorePassword = value) }),
        TRUST_STORE({ it.trustStorePassword }, { config, value -> config.copy(trustStorePassword = value) }),
    }

    /**
     * One map per [Kind], each by profile id.
     *
     * A file written before the store passwords moved here has `passwords` alone, and reads with the other two
     * empty. Empty maps are not written, so a workspace without SSL keeps a secrets file of the old shape.
     */
    @Serializable
    private data class Secrets(
        val passwords: Map<String, String> = emptyMap(),
        val keyStorePasswords: Map<String, String> = emptyMap(),
        val trustStorePasswords: Map<String, String> = emptyMap(),
    ) {
        fun of(kind: Kind): Map<String, String> =
            when (kind) {
                Kind.LOGON -> passwords
                Kind.KEY_STORE -> keyStorePasswords
                Kind.TRUST_STORE -> trustStorePasswords
            }

        fun with(
            kind: Kind,
            values: Map<String, String>,
        ): Secrets =
            when (kind) {
                Kind.LOGON -> copy(passwords = values)
                Kind.KEY_STORE -> copy(keyStorePasswords = values)
                Kind.TRUST_STORE -> copy(trustStorePasswords = values)
            }
    }

    private fun read(): Secrets =
        try {
            (if (file.exists()) json.decodeFromString<Secrets>(file.readText()) else Secrets())
                .also { guard.read(succeeded = true) }
        } catch (e: IOException) {
            guard.read(succeeded = false)
            logger.error("Could not read ${file.name}; treating it as empty", e)
            Secrets()
        } catch (e: SerializationException) {
            guard.read(succeeded = false)
            logger.error("${file.name} is not readable JSON; treating it as empty", e)
            Secrets()
        }

    /** False when nothing was written, including when [guard] refuses because the last read failed. */
    private fun write(secrets: Secrets): Boolean {
        guard.refusal()?.let { why ->
            logger.error(why)
            return false
        }
        return try {
            file.parentFile?.mkdirs()
            AtomicFiles.writeAtomically(file, json.encodeToString(secrets))
            true
        } catch (e: IOException) {
            logger.error("Could not write ${file.name}", e)
            false
        } catch (e: SerializationException) {
            logger.error("Could not serialise ${file.name}", e)
            false
        }
    }

    /** Puts each profile's passwords back on it, for the app to use as it always has. */
    fun applyTo(profiles: List<FixConnectionProfile>): List<FixConnectionProfile> {
        val secrets = read()
        return profiles.map { profile ->
            val config =
                Kind.entries.fold(profile.config) { config, kind ->
                    val stored = secrets.of(kind)[profile.id]
                    // A password already on the profile wins: that is either an unmigrated file being read for
                    // the first time, or a caller that has just set one, and both are the newer truth.
                    if (stored.isNullOrEmpty() || kind.of(config).isNotEmpty()) config else kind.put(config, stored)
                }
            if (config == profile.config) profile else profile.copy(config = config)
        }
    }

    /**
     * Records the passwords and returns the profiles without them, ready to be written.
     *
     * Only the profiles given are touched. A password for an id that is not in the list is left
     * alone, because saving one profile must not forget another's — and `saveProfile` reaches here
     * through a full list, while a caller in the future might not.
     *
     * Throws when the passwords could not be recorded, so the profiles are not written without them and a
     * new password is not lost. That includes a secrets file the read above could not make sense of: what it
     * answered is empty, and writing that back would forget every password in the file.
     */
    fun extractFrom(profiles: List<FixConnectionProfile>): List<FixConnectionProfile> {
        val existing = read()
        val updated =
            Kind.entries.fold(existing) { secrets, kind ->
                val values = secrets.of(kind).toMutableMap()
                profiles.forEach { profile ->
                    val password = kind.of(profile.config)
                    if (password.isEmpty()) {
                        values.remove(profile.id)
                    } else {
                        values[profile.id] = password
                    }
                }
                secrets.with(kind, values)
            }
        if (updated != existing && !write(updated)) {
            throw IOException(guard.refusal() ?: "could not write ${file.name}")
        }
        return profiles.map { profile ->
            profile.copy(config = Kind.entries.fold(profile.config) { config, kind -> kind.put(config, "") })
        }
    }

    /** Forgets one profile's passwords, for a profile that has been deleted. */
    fun forget(profileId: String) {
        val secrets = read()
        val forgotten = Kind.entries.fold(secrets) { left, kind -> left.with(kind, left.of(kind) - profileId) }
        if (forgotten != secrets) {
            write(forgotten)
        }
    }

    /** True when [profiles] still carry passwords inline, so the file wants rewriting once. */
    fun needsMigration(profiles: List<FixConnectionProfile>): Boolean =
        profiles.any { profile -> Kind.entries.any { kind -> kind.of(profile.config).isNotEmpty() } }
}
