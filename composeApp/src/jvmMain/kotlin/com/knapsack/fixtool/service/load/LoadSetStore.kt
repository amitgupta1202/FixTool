package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * **The saved load sets: one file each, under `load-sets/`, beside the records they produce.**
 *
 * The same shape as the scenario run set's store, and for the same reason: a build box selects by a name
 * in a checkout, not by a local star file, so `fixtool load --set rfq-round-trip` reads
 * `<home>/load-sets/rfq-round-trip.json` and a set checked in beside the code survives a fresh `--home`.
 *
 * A file that cannot be read is logged and skipped rather than throwing, so one hand-edited set does not
 * empty the list.
 */
class LoadSetStore(
    customDir: String = "",
    private val onError: ((String) -> Unit)? = null,
) {
    private val logger = NotifyingLogger(LoadSetStore::class.java, onError)
    private val prettyJson = Json { prettyPrint = true }

    private val dir: File = if (customDir.isNotBlank()) File(customDir) else WorkspacePaths.current.loadSets

    val directory: File get() = dir

    /** Every saved set, by name. */
    fun list(): List<LoadSet> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedBy { it.name.lowercase() }

    /** By file name first, then by the name inside, so a hand-renamed file still answers to its set. */
    fun load(name: String): LoadSet? =
        read(fileFor(name)) ?: list().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun fileFor(name: String): File = File(dir, "${LoadSet.slug(name)}.json")

    @Suppress("TooGenericExceptionCaught")
    fun save(set: LoadSet): Boolean =
        try {
            dir.mkdirs()
            AtomicFiles.writeAtomically(
                fileFor(set.name),
                prettyJson.encodeToString(JsonObject.serializer(), LoadSetCodec.toJson(set)),
            )
            true
        } catch (e: Exception) {
            logger.error("Could not save load set '${set.name}': ${e.message}", e)
            false
        }

    fun delete(name: String): Boolean = fileFor(name).takeIf { it.isFile }?.delete() ?: false

    @Suppress("TooGenericExceptionCaught")
    private fun read(file: File): LoadSet? =
        try {
            file.takeIf { it.isFile }?.let { LoadSetCodec.fromJson(Json.parseToJsonElement(it.readText()).jsonObject) }
        } catch (e: Exception) {
            logger.error("Could not read load set '${file.name}': ${e.message}", e)
            null
        }
}
