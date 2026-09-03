package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * The workspace's environments, and the one-off that proposes them from the profiles already there.
 *
 * Purely additive. A workspace with no `environments.json` behaves exactly as it did: a profile is
 * connected as itself, and nothing in the UI mentions environments. The moment one exists, Quick
 * Connect starts asking which one — and a profile can still be connected as itself, because a saved
 * profile that already names its own host is not wrong, it is just an environment nobody extracted.
 */
class Environments(
    private val file: File,
) {
    private val logger = LoggerFactory.getLogger(Environments::class.java)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @Serializable
    private data class Container(
        val environments: List<Environment> = emptyList(),
    )

    fun load(): List<Environment> =
        try {
            if (file.exists()) json.decodeFromString<Container>(file.readText()).environments else emptyList()
        } catch (e: IOException) {
            logger.error("Could not read ${file.name}", e)
            emptyList()
        } catch (e: SerializationException) {
            logger.error("${file.name} is not readable JSON", e)
            emptyList()
        }

    fun save(environments: List<Environment>): Boolean =
        try {
            file.parentFile?.mkdirs()
            val ordered = environments.sortedBy { it.name.lowercase() }
            file.writeText(json.encodeToString(Container(ordered)))
            true
        } catch (e: IOException) {
            logger.error("Could not write ${file.name}", e)
            false
        } catch (e: SerializationException) {
            logger.error("Could not serialise ${file.name}", e)
            false
        }

    companion object {
        /**
         * What a set of profiles would look like split into counterparties and environments.
         *
         * Nothing is written and nothing is decided: this returns a [Proposal] for a person to accept,
         * because the split is a guess about intent. `UAT1-BuySide` and `QA1-BuySide` are *probably*
         * one counterparty in two environments, and `Local-BuySide-Persistent` probably is not — only
         * the person who named them knows.
         *
         * The guess is made on the **names**, not the endpoints, and deliberately so: two environments
         * that happen to share a host today are still two environments, and a desk that has already
         * agreed what its environments are called has encoded that agreement in the profile names.
         */
        fun propose(profiles: List<FixConnectionProfile>): Proposal {
            val split = profiles.mapNotNull { profile -> splitName(profile.name)?.let { parts -> parts to profile } }
            val byEnvironment = split.groupBy({ it.first.first }, { it.second })

            // An environment prefix seen once is not an environment, it is a profile with a hyphen in
            // its name. Two is the smallest number that makes the prefix mean anything.
            val realEnvironments = byEnvironment.filterValues { it.size >= MIN_PROFILES_PER_ENVIRONMENT }

            val counterparties =
                split
                    .filter { (parts, _) -> parts.first in realEnvironments }
                    .groupBy({ it.first.second }, { it.second })

            return Proposal(
                environments =
                    realEnvironments
                        .map { (name, group) -> Environment.of(name, group.first().config) }
                        .sortedBy { environment -> environment.name.lowercase() },
                counterparties = counterparties.keys.sorted(),
                replaces =
                    realEnvironments.values
                        .flatten()
                        .map { it.name }
                        .sorted(),
            )
        }

        /**
         * What [propose] found, for a dialog to show before anything is written.
         *
         * [replaces] is the honest part: these are the profiles the split would speak for, and a user
         * deciding whether to accept needs to see them by name rather than as a count.
         */
        data class Proposal(
            val environments: List<Environment>,
            val counterparties: List<String>,
            val replaces: List<String>,
        ) {
            val isWorthDoing: Boolean
                get() = environments.size >= MIN_ENVIRONMENTS && counterparties.isNotEmpty()
        }

        private const val MIN_PROFILES_PER_ENVIRONMENT = 2
        private const val MIN_ENVIRONMENTS = 2

        /**
         * `UAT1-BuySide` -> `UAT1` and `BuySide`; anything without a hyphen -> null.
         *
         * Only the FIRST hyphen splits, so `Local-BuySide-Persistent` is the counterparty
         * `BuySide-Persistent` in `Local` rather than a third thing — which is right, because that is
         * how the name reads to the person who wrote it.
         */
        private fun splitName(name: String): Pair<String, String>? {
            val at = name.indexOf('-')
            if (at <= 0 || at == name.lastIndex) {
                return null
            }
            return name.substring(0, at) to name.substring(at + 1)
        }
    }
}
