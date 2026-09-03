package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.util.AtomicFiles
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * **A saved run set: a named file, like everything else the app keeps.**
 *
 * The first draft of this feature proposed waking `Scenario.userTags` for a `--tag nightly` selector.
 * Review found that field is not asleep — it is the per-profile scenario filter and part of the
 * `GET /scenarios` payload, so a `nightly` tag would hide the scenario from every profile-filtered
 * listing. Selection is a file instead, and the file is the thing CI names: `fixtool run --set nightly`,
 * because a build box selects by a name in a checkout, not by a local star file.
 *
 * ```jsonc
 * // ~/.fixtool/sets/nightly.json
 * { "name": "nightly",
 *   "entries": [ { "scenario": "smoke-nos" }, { "scenario": "book-a-trade", "repeat": 3 } ],
 *   "policy": { "stopOnFirstFailure": false, "pauseBetweenMs": 0 } }
 * ```
 *
 * An entry names a scenario **by id or by name**, and resolution tries the id first. A name is what an
 * author writes by hand and what survives a scenario being re-saved; an id is what the UI has to hand.
 * A name that matches nothing is not a failure of the file — it is one entry that cannot run, and it is
 * reported that way when the set is planned.
 */
class RunSetStore(
    customDir: String = "",
    private val onError: ((String) -> Unit)? = null,
) {
    private val logger = NotifyingLogger(RunSetStore::class.java, onError)
    private val prettyJson =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val dir: File =
        if (customDir.isNotBlank()) File(customDir) else WorkspacePaths.current.sets

    val directory: File get() = dir

    /** Every saved set, by name. */
    fun list(): List<SavedRunSet> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedBy { it.name.lowercase() }

    fun load(name: String): SavedRunSet? = read(fileFor(name)) ?: list().firstOrNull { it.name.equals(name, ignoreCase = true) }

    @Suppress("TooGenericExceptionCaught")
    fun save(set: SavedRunSet): Boolean =
        try {
            AtomicFiles.writeAtomically(
                fileFor(set.name),
                prettyJson.encodeToString(JsonObject.serializer(), SavedRunSetCodec.toJson(set)),
            )
            true
        } catch (e: Exception) {
            logger.error("Could not save run set '${set.name}': ${e.message}", e)
            false
        }

    fun delete(name: String): Boolean = fileFor(name).takeIf { it.isFile }?.delete() ?: false

    @Suppress("TooGenericExceptionCaught")
    private fun read(file: File): SavedRunSet? =
        try {
            file.takeIf { it.isFile }?.let { SavedRunSetCodec.fromJson(Json.parseToJsonElement(it.readText()).jsonObject) }
        } catch (e: Exception) {
            logger.error("Could not read run set '${file.name}': ${e.message}", e)
            null
        }

    private fun fileFor(name: String) = File(dir, "${slugify(name)}.json")

    private fun slugify(name: String): String {
        val slug =
            buildString { for (c in name.lowercase()) append(if (c in 'a'..'z' || c in '0'..'9') c else '-') }
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(60)
        return slug.ifBlank { "set" }
    }
}

/** A set as it is saved: what to run, how many times each, and how to run it. */
data class SavedRunSet(
    val name: String,
    val entries: List<SavedRunEntry>,
    val policy: RunPolicy = RunPolicy(),
) {
    /**
     * **Turns a saved set into a runnable one**, resolving each entry against the scenarios on disk.
     *
     * Unresolved names come back beside the plan rather than throwing: a set of twelve with one deleted
     * scenario should run the eleven and say which one it could not find, because the alternative is a
     * nightly suite that stops existing the day somebody renames a file.
     */
    fun plan(scenarios: List<Scenario>, now: Long): Planned {
        val byId = scenarios.associateBy { it.id }
        val byName = scenarios.associateBy { it.name.lowercase() }
        val planned = mutableListOf<RunEntry>()
        val missing = mutableListOf<String>()
        entries.forEach { entry ->
            val scenario = byId[entry.scenario] ?: byName[entry.scenario.lowercase()]
            if (scenario == null) {
                missing += entry.scenario
                return@forEach
            }
            val times = entry.repeat.coerceAtLeast(1)
            (1..times).forEach { i -> planned += RunEntry(scenario.id, scenario.name, iteration = i) }
        }
        val label = if (planned.size == 1) name else "$name — ${planned.size} runs"
        return Planned(
            RunSet(
                id = RunSets.id(now, name),
                label = label,
                source = RunSource.Saved(name),
                entries = planned,
                policy = policy,
            ),
            missing,
        )
    }

    /** The set as it will run, and the entry names nothing on disk answered to. */
    data class Planned(
        val set: RunSet,
        val missing: List<String>,
    )
}

/** One line of a saved set: a scenario by id or name, optionally repeated. */
data class SavedRunEntry(
    val scenario: String,
    val repeat: Int = 1,
)

object SavedRunSetCodec {
    fun toJson(set: SavedRunSet): JsonObject =
        buildJsonObject {
            put("name", set.name)
            put(
                "entries",
                buildJsonArray {
                    set.entries.forEach { e ->
                        add(
                            buildJsonObject {
                                put("scenario", e.scenario)
                                // Default-omitting, the same bargain the scenario format keeps: a set that
                                // never repeated anything does not grow the key.
                                if (e.repeat != 1) put("repeat", e.repeat)
                            },
                        )
                    }
                },
            )
            put(
                "policy",
                buildJsonObject {
                    put("stopOnFirstFailure", set.policy.stopOnFirstFailure)
                    put("pauseBetweenMs", set.policy.pauseBetweenMs)
                    put("isolateIterations", set.policy.isolateIterations)
                },
            )
        }

    fun fromJson(obj: JsonObject): SavedRunSet =
        SavedRunSet(
            name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
            entries =
                obj["entries"]?.jsonArray.orEmpty().map { it.jsonObject }.map { e ->
                    SavedRunEntry(
                        scenario = e["scenario"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        repeat = e["repeat"]?.jsonPrimitive?.intOrNull ?: 1,
                    )
                },
            policy =
                obj["policy"]?.jsonObject?.let { p ->
                    RunPolicy(
                        stopOnFirstFailure = p["stopOnFirstFailure"]?.jsonPrimitive?.booleanOrNull ?: false,
                        pauseBetweenMs = p["pauseBetweenMs"]?.jsonPrimitive?.longOrNull ?: 0,
                        isolateIterations = p["isolateIterations"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                } ?: RunPolicy(),
        )
}
