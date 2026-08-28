package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.scenario.ExampleRow
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.model.scenario.RunEntry
import com.knapsack.fixtool.model.scenario.RunPolicy
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
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
 * **The runs directory: one folder per set, one file per entry, written as each entry lands.**
 *
 * ```
 * ~/.fixtool/runs/
 *   2026-08-28T09-36-02-nightly/
 *     set.json                 the set: label, source, policy, entries and their states
 *     01-smoke-nos.json        one file per entry, written the moment the entry lands
 *     02-book-a-trade.json
 * ```
 *
 * Written **as it is produced**, not at the end: the run this exists for is the overnight suite, and a
 * suite that only writes its evidence on completion has nothing to say about the run that was killed at
 * entry nine. Headless writes the same directory under its own `--home`, so `fixtool run --set nightly`
 * on a build box leaves the records a click would.
 *
 * Retention is a directory, not a tab. Closing a tab releases nothing here; [prune] keeps the most recent
 * sets and drops the rest, oldest first.
 */
class RunRecordStore(
    customDir: String = "",
    private val onError: ((String) -> Unit)? = null,
) {
    private val logger = NotifyingLogger(RunRecordStore::class.java, onError)
    private val prettyJson =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val dir: File =
        if (customDir.isNotBlank()) File(customDir) else File(System.getProperty("user.home"), ".fixtool/runs")

    /** Where run records live. One directory per set. */
    val directory: File get() = dir

    fun directoryFor(setId: String): File = File(dir, sanitize(setId))

    /** Creates the set's directory and writes its first `set.json`. */
    @Suppress("TooGenericExceptionCaught")
    fun begin(set: RunSet): Boolean =
        try {
            directoryFor(set.id).mkdirs()
            writeSet(set)
        } catch (e: Exception) {
            logger.error("Could not start run record directory for '${set.id}': ${e.message}", e)
            false
        }

    /** Rewrites `set.json`. Called after every entry, so a reader always sees the progress so far. */
    @Suppress("TooGenericExceptionCaught")
    fun writeSet(set: RunSet): Boolean =
        try {
            AtomicFiles.writeAtomically(
                File(directoryFor(set.id), SET_FILE),
                prettyJson.encodeToString(JsonObject.serializer(), RunSetCodec.toJson(set)),
            )
            true
        } catch (e: Exception) {
            logger.error("Could not write set.json for '${set.id}': ${e.message}", e)
            false
        }

    /** Writes one entry's record; returns the file name to put on the entry, or null if it could not. */
    @Suppress("TooGenericExceptionCaught")
    fun write(record: RunRecord): String? =
        try {
            val name = "%02d-%s.json".format(record.entry, slugify(record.scenarioName))
            AtomicFiles.writeAtomically(
                File(directoryFor(record.setId), name),
                prettyJson.encodeToString(JsonObject.serializer(), RunRecordCodec.toJson(record)),
            )
            name
        } catch (e: Exception) {
            logger.error("Could not write run record ${record.entry} of '${record.setId}': ${e.message}", e)
            null
        }

    /** Every set on disk, newest first — what "Recent runs" lists. */
    fun listSets(): List<RunSet> =
        (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { readSet(it.name) }
            .sortedByDescending { it.startedAt }

    @Suppress("TooGenericExceptionCaught")
    fun readSet(setId: String): RunSet? =
        try {
            File(directoryFor(setId), SET_FILE).takeIf { it.isFile }?.let {
                RunSetCodec.fromJson(Json.parseToJsonElement(it.readText()).jsonObject)
            }
        } catch (e: Exception) {
            logger.error("Could not read set '$setId': ${e.message}", e)
            null
        }

    /** One entry's record, by its 1-based position in the set. */
    @Suppress("TooGenericExceptionCaught")
    fun readEntry(setId: String, entry: Int): RunRecord? =
        try {
            val named = readSet(setId)?.entries?.getOrNull(entry - 1)?.record
            val file =
                named?.let { File(directoryFor(setId), it) }
                    ?: directoryFor(setId).listFiles { f -> f.name.startsWith("%02d-".format(entry)) }?.firstOrNull()
            file?.takeIf { it.isFile }?.let { RunRecordCodec.fromJson(Json.parseToJsonElement(it.readText()).jsonObject) }
        } catch (e: Exception) {
            logger.error("Could not read entry $entry of set '$setId': ${e.message}", e)
            null
        }

    /**
     * Keeps the [keep] most recent sets and deletes the rest.
     *
     * Twenty sets of twelve entries of five thousand messages is real disk, so the count is a setting and
     * a set's own size is visible where it is listed. Ordering is by the set's own `startedAt` rather than
     * the directory's mtime: a set whose `set.json` is rewritten as it progresses would otherwise look
     * like the newest thing on disk while it ran.
     */
    @Suppress("TooGenericExceptionCaught")
    fun prune(keep: Int) {
        if (keep <= 0) return
        try {
            listSets().drop(keep).forEach { directoryFor(it.id).deleteRecursively() }
        } catch (e: Exception) {
            logger.error("Could not prune the runs directory: ${e.message}", e)
        }
    }

    /**
     * **After a fan-out, keep what a reader will actually open.**
     *
     * Fifty lanes of order flow is fifty copies of the same three messages, and keeping every one whole is
     * the wrong default at that scale. So: **failed lanes keep their records entire**, because they are
     * what the run is for; **the first passing lane is kept as a reference specimen**, because "what does a
     * good one look like" is the next question; and the rest keep their report, their timing and their
     * counts while their messages are emptied — with `dropped` set, so the record says so rather than
     * looking like a lane that saw nothing.
     *
     * After the set completes, never during: a lane's verdict is not known until it has one, and a record
     * trimmed on a guess would be the evidence for the failure nobody kept.
     */
    @Suppress("TooGenericExceptionCaught")
    fun trimToSpecimens(set: RunSet): Int {
        var trimmed = 0
        try {
            var referenceKept = false
            set.entries.forEachIndexed { i, entry ->
                val keepWhole = entry.state != RunState.PASSED || !referenceKept
                if (entry.state == RunState.PASSED && !referenceKept) referenceKept = true
                if (keepWhole) return@forEachIndexed
                val record = readEntry(set.id, i + 1) ?: return@forEachIndexed
                if (record.messages.isEmpty()) return@forEachIndexed
                write(record.copy(messages = emptyList(), bound = emptyMap(), dropped = record.dropped + record.messages.size))
                trimmed++
            }
        } catch (e: Exception) {
            logger.error("Could not trim the records of '${set.id}': ${e.message}", e)
        }
        return trimmed
    }

    private fun slugify(name: String): String {
        val slug =
            buildString { for (c in name.lowercase()) append(if (c in 'a'..'z' || c in '0'..'9') c else '-') }
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(60)
        return slug.ifBlank { "scenario" }
    }

    /** Ids reach the filesystem, so they are kept to a filesystem-safe charset — no traversal, no surprises. */
    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private companion object {
        const val SET_FILE = "set.json"
    }
}

/** `set.json`: what the set was asked to do, and how far it got. */
object RunSetCodec {
    fun toJson(set: RunSet): JsonObject =
        buildJsonObject {
            put("id", set.id)
            put("label", set.label)
            put("source", sourceToJson(set.source))
            put("startedAt", set.startedAt)
            set.finishedAt?.let { put("finishedAt", it) }
            put("status", set.status.name.lowercase())
            put(
                "policy",
                buildJsonObject {
                    put("stopOnFirstFailure", set.policy.stopOnFirstFailure)
                    put("pauseBetweenMs", set.policy.pauseBetweenMs)
                    put("isolateIterations", set.policy.isolateIterations)
                    put("concurrency", set.policy.concurrency)
                },
            )
            put(
                "entries",
                buildJsonArray {
                    set.entries.forEachIndexed { i, e ->
                        add(
                            buildJsonObject {
                                put("n", i + 1)
                                put("scenarioId", e.scenarioId)
                                put("scenario", e.scenarioName)
                                put("iteration", e.iteration)
                                // The row's name and its cells: a record has to say what the run was given,
                                // or "row 3 failed" is a sentence nobody can act on a week later.
                                e.lane?.let { lane ->
                                    put(
                                        "lane",
                                        buildJsonObject {
                                            put("slot", lane.slot)
                                            put("session", lane.sessionTitle)
                                            put("senderCompID", lane.senderCompID)
                                            put("qualifier", lane.qualifier)
                                        },
                                    )
                                }
                                e.row?.let { row ->
                                    put(
                                        "row",
                                        buildJsonObject {
                                            put("name", row.name)
                                            put("values", buildJsonObject { row.values.forEach { (k, v) -> put(k, v) } })
                                        },
                                    )
                                }
                                put("state", e.state.name)
                                e.durationMs?.let { put("durationMs", it) }
                                e.record?.let { put("record", it) }
                                e.note?.let { put("note", it) }
                                if (e.sessionMap.isNotEmpty()) {
                                    put("sessions", buildJsonObject { e.sessionMap.forEach { (k, v) -> put(k, v) } })
                                }
                            },
                        )
                    }
                },
            )
        }

    fun fromJson(obj: JsonObject): RunSet =
        RunSet(
            id = obj["id"]!!.jsonPrimitive.content,
            label = obj["label"]?.jsonPrimitive?.content.orEmpty(),
            source = sourceFromJson(obj["source"]?.jsonObject),
            entries =
                obj["entries"]?.jsonArray.orEmpty().map { it.jsonObject }.map { e ->
                    RunEntry(
                        scenarioId = e["scenarioId"]?.jsonPrimitive?.content.orEmpty(),
                        scenarioName = e["scenario"]?.jsonPrimitive?.content.orEmpty(),
                        iteration = e["iteration"]?.jsonPrimitive?.intOrNull ?: 1,
                        lane =
                            e["lane"]?.jsonObject?.let { lane ->
                                Lane(
                                    slot = lane["slot"]?.jsonPrimitive?.intOrNull ?: 0,
                                    sessionTitle = lane["session"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    senderCompID = lane["senderCompID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    qualifier = lane["qualifier"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                )
                            },
                        row =
                            e["row"]?.jsonObject?.let { row ->
                                ExampleRow(
                                    name = row["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    values = row["values"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.content },
                                )
                            },
                        sessionMap = e["sessions"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.content },
                        state =
                            e["state"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.let { name -> RunState.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                                ?: RunState.PENDING,
                        durationMs = e["durationMs"]?.jsonPrimitive?.longOrNull,
                        record = e["record"]?.jsonPrimitive?.contentOrNull,
                        note = e["note"]?.jsonPrimitive?.contentOrNull,
                    )
                },
            policy =
                obj["policy"]?.jsonObject?.let { p ->
                    RunPolicy(
                        stopOnFirstFailure = p["stopOnFirstFailure"]?.jsonPrimitive?.booleanOrNull ?: false,
                        pauseBetweenMs = p["pauseBetweenMs"]?.jsonPrimitive?.longOrNull ?: 0,
                        isolateIterations = p["isolateIterations"]?.jsonPrimitive?.booleanOrNull ?: true,
                        concurrency = p["concurrency"]?.jsonPrimitive?.intOrNull ?: 1,
                    )
                } ?: RunPolicy(),
            startedAt = obj["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            finishedAt = obj["finishedAt"]?.jsonPrimitive?.longOrNull,
            status =
                obj["status"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { name -> RunSetStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                    ?: RunSetStatus.RUNNING,
        )

    private fun sourceToJson(source: RunSource): JsonObject =
        buildJsonObject {
            when (source) {
                is RunSource.Saved -> {
                    put("type", "saved")
                    put("name", source.setName)
                }
                is RunSource.Favourites -> put("type", "favourites")
                is RunSource.Filtered -> {
                    put("type", "filtered")
                    put("text", source.text)
                }
                is RunSource.Selected -> {
                    put("type", "selected")
                    put("ids", buildJsonArray { source.ids.forEach { add(it) } })
                }
                is RunSource.Repeat -> {
                    put("type", "repeat")
                    put("scenarioId", source.scenarioId)
                    put("times", source.times)
                }
                is RunSource.Examples -> {
                    put("type", "examples")
                    put("scenarioId", source.scenarioId)
                }
                is RunSource.FanOut -> {
                    put("type", "fanOut")
                    put("scenarioId", source.scenarioId)
                    put("profileId", source.profileId)
                }
            }
        }

    private fun sourceFromJson(obj: JsonObject?): RunSource =
        when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
            "saved" -> RunSource.Saved(obj["name"]?.jsonPrimitive?.content.orEmpty())
            "favourites" -> RunSource.Favourites
            "filtered" -> RunSource.Filtered(obj["text"]?.jsonPrimitive?.content.orEmpty())
            "repeat" ->
                RunSource.Repeat(
                    obj["scenarioId"]?.jsonPrimitive?.content.orEmpty(),
                    obj["times"]?.jsonPrimitive?.intOrNull ?: 1,
                )
            "examples" -> RunSource.Examples(obj["scenarioId"]?.jsonPrimitive?.content.orEmpty())
            "fanout" ->
                RunSource.FanOut(
                    obj["scenarioId"]?.jsonPrimitive?.content.orEmpty(),
                    obj["profileId"]?.jsonPrimitive?.content.orEmpty(),
                )
            // Selected is the fallback: an unknown source still lists the scenarios it ran, which is the
            // part a reader of an old record needs. The provenance of the click is not worth a refusal.
            else ->
                RunSource.Selected(
                    obj
                        ?.get("ids")
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonPrimitive.content },
                )
        }
}
