package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Directory store for [Scenario]s — one JSON file per scenario at `<dir>/<id>.json` (default
 * `~/.fixtool/scenarios`). Chosen over a single collection file so scenarios stay diffable/PR-able,
 * one edit rewrites one small file, and a corrupt write loses one scenario rather than all of them.
 * Writes are crash-safe via [AtomicFiles].
 */
class ScenarioService(
    private val onError: ((String) -> Unit)? = null,
    customDir: String = "",
) {
    private val logger = NotifyingLogger(ScenarioService::class.java, onError)
    private val lock = Any()
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val dir: File =
        if (customDir.isNotBlank()) {
            File(customDir)
        } else {
            File(System.getProperty("user.home"), ".fixtool/scenarios")
        }

    /** All saved scenarios, sorted by name; unreadable files are skipped, not fatal. */
    fun list(): List<Scenario> =
        synchronized(lock) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
            files.mapNotNull { loadFile(it) }.sortedBy { it.name.lowercase() }
        }

    /** Scenarios visible for a profile: those with no profile tag (global) or tagged with it. */
    fun listForProfile(profileId: String?): List<Scenario> =
        list().filter { profileId == null || it.userTags.isEmpty() || profileId in it.userTags }

    fun load(id: String): Scenario? = synchronized(lock) { loadFile(fileFor(id)) }

    @Suppress("TooGenericExceptionCaught")
    fun save(scenario: Scenario): Boolean =
        synchronized(lock) {
            try {
                val content = prettyJson.encodeToString(JsonObject.serializer(), ScenarioCodec.toJson(scenario))
                AtomicFiles.writeAtomically(fileFor(scenario.id), content)
                true
            } catch (e: Exception) {
                logger.error("Failed to save scenario ${scenario.id}: ${e.message}", e, notifyUser = true)
                false
            }
        }

    fun delete(id: String): Boolean =
        synchronized(lock) {
            val file = fileFor(id)
            if (file.exists()) file.delete() else false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun loadFile(file: File): Scenario? =
        try {
            ScenarioCodec.fromJson(Json.parseToJsonElement(file.readText()).jsonObject)
        } catch (e: Exception) {
            logger.error("Failed to load scenario from ${file.name}: ${e.message}", e, notifyUser = false)
            null
        }

    private fun fileFor(id: String): File = File(dir, sanitize(id) + ".json")

    /** Keep ids to a filesystem-safe charset to prevent path traversal / odd filenames. */
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
