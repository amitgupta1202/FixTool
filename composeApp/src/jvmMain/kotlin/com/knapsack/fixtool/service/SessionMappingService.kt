package com.knapsack.fixtool.service

import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A named answer to "which sessions do this scenario's session names mean today" — e.g. `QA` =
 * `{dev-buyside → qa-buyside, dev-sellside → qa-sellside}`. Applied at run time via
 * [com.knapsack.fixtool.model.scenario.withSessions]; the scenario file never changes, which is the
 * point: one recorded flow, every environment that hosts it. A mapping is not tied to any scenario —
 * every scenario recorded against the dev pair runs on QA through the same one.
 */
@Serializable
data class SessionMapping(
    val id: String,
    val name: String,
    /** from-session (as the scenario says it) → to-session (as this environment names it). */
    val map: Map<String, String>,
)

/**
 * Persists [SessionMapping]s (and each scenario's last-used one) in the app-local store, NOT the
 * scenarios directory: scenarios are shared, git-friendly documents, and which environment one person
 * pointed them at last is neither.
 */
class SessionMappingService(
    private val onError: ((String) -> Unit)? = null,
    customPath: String = "",
) {
    private val logger = NotifyingLogger(SessionMappingService::class.java, onError)

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val file =
        if (customPath.isNotBlank()) {
            File(customPath)
        } else {
            File(System.getProperty("user.home"), ".fixtool/session_mappings.json")
        }

    init {
        file.parentFile?.mkdirs()
    }

    @Serializable
    private data class Container(
        val mappings: List<SessionMapping> = emptyList(),
        /** scenario id → the [SessionMapping.id] its last remapped run used. */
        val lastUsed: Map<String, String> = emptyMap(),
    )

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun read(): Container =
        try {
            if (!file.exists()) Container() else json.decodeFromString<Container>(file.readText())
        } catch (e: Exception) {
            logger.error("Failed to load session mappings: ${e.message}", e, notifyUser = true)
            Container()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun write(container: Container): Boolean =
        try {
            file.writeText(json.encodeToString(container))
            true
        } catch (e: Exception) {
            logger.error("Failed to save session mappings: ${e.message}", e, notifyUser = true)
            false
        }

    fun loadMappings(): List<SessionMapping> = read().mappings

    /** Creates or updates (by id) a mapping. @return the updated list, or null when the write failed. */
    fun saveMapping(mapping: SessionMapping): List<SessionMapping>? {
        val container = read()
        val mappings = container.mappings.toMutableList()
        val existing = mappings.indexOfFirst { it.id == mapping.id }
        if (existing >= 0) mappings[existing] = mapping else mappings.add(mapping)
        return if (write(container.copy(mappings = mappings))) mappings else null
    }

    /** @return the updated list, or null when the write failed. */
    fun deleteMapping(id: String): List<SessionMapping>? {
        val container = read()
        val mappings = container.mappings.filterNot { it.id == id }
        // A lastUsed entry pointing at a deleted mapping would preselect nothing forever; drop those too.
        val lastUsed = container.lastUsed.filterValues { it != id }
        return if (write(Container(mappings, lastUsed))) mappings else null
    }

    /** scenario id → the mapping id its last remapped run used. */
    fun loadLastUsed(): Map<String, String> = read().lastUsed

    /** @return the updated last-used map, or null when the write failed. */
    fun noteUsed(scenarioId: String, mappingId: String): Map<String, String>? {
        val container = read()
        val lastUsed = container.lastUsed + (scenarioId to mappingId)
        return if (write(container.copy(lastUsed = lastUsed))) lastUsed else null
    }
}
