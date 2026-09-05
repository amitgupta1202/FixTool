package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadPhase
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * **What a load run leaves behind**: `loads/<id>/load.json`, the wire of every request that went unanswered,
 * and fifty specimen pairs. Never every message.
 *
 * A 300,000-message record would be the disk problem the memory store removes, moved one directory over.
 * The unanswered requests are the evidence anyone actually opens, and the specimens answer "what did a good
 * exchange look like". `load.json` is rewritten as the run progresses, so a poller and a reopened document
 * always find the counts so far, and a record that says `RUNNING` with no process running it is healed to
 * `STOPPED` on the first read that notices, exactly as the run set store does.
 */
class LoadRecordStore(
    customDir: String = "",
    private val onError: ((String) -> Unit)? = null,
    /** Is the run with this id still being run by a live process? The owner knows; the store cannot. */
    private val isLive: (String) -> Boolean = { true },
) {
    private val logger = NotifyingLogger(LoadRecordStore::class.java, onError)
    private val json = Json { prettyPrint = true }

    private val dir: File = if (customDir.isNotBlank()) File(customDir) else WorkspacePaths.current.loads

    val directory: File get() = dir

    fun directoryFor(id: String): File = File(dir, sanitize(id))

    /** A free id for a run about to start, `-2`, `-3`… when the one it wants is taken, with its directory made. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun reserve(id: String): String =
        try {
            var candidate = id
            var n = 1
            while (directoryFor(candidate).exists()) {
                n++
                candidate = "$id-$n"
            }
            if (!directoryFor(candidate).mkdirs()) logger.error("Could not create the load record directory ${directoryFor(candidate)}")
            candidate
        } catch (e: Exception) {
            logger.error("Could not reserve a load record directory for '$id': ${e.message}", e)
            id
        }

    /** Writes `load.json`. Called at start, on every progress tick, and once more at the end. */
    @Suppress("TooGenericExceptionCaught")
    fun write(report: LoadReport): Boolean =
        try {
            directoryFor(report.id).mkdirs()
            File(directoryFor(report.id), REPORT_FILE).writeText(json.encodeToString(JsonObject.serializer(), LoadReportCodec.toJson(report)))
            true
        } catch (e: Exception) {
            logger.error("Could not write load record '${report.id}': ${e.message}", e)
            false
        }

    /** The evidence files: one unanswered request per line, and specimen pairs as request then reply. */
    @Suppress("TooGenericExceptionCaught")
    fun writeEvidence(id: String, unmatched: List<StampMatcher.Unmatched>, specimens: List<StampMatcher.Specimen>): Boolean =
        try {
            val d = directoryFor(id).also { it.mkdirs() }
            File(d, UNMATCHED_FILE).writeText(unmatched.joinToString("") { it.wire.toRawFixMessage() + "\n" })
            File(d, SPECIMENS_FILE).writeText(
                specimens.joinToString("") { it.request.toRawFixMessage() + "\n" + it.reply.toRawFixMessage() + "\n" },
            )
            true
        } catch (e: Exception) {
            logger.error("Could not write load evidence for '$id': ${e.message}", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    fun read(id: String): LoadReport? =
        try {
            val file = File(directoryFor(id), REPORT_FILE).takeIf { it.isFile } ?: return null
            val report = LoadReportCodec.fromJson(Json.parseToJsonElement(file.readText()).jsonObject)
            if (report.status == LoadStatus.RUNNING && !isLive(report.id)) healInterrupted(report, file.lastModified()) else report
        } catch (e: Exception) {
            logger.error("Could not read load record '$id': ${e.message}", e)
            null
        }

    /** The unanswered requests' wire, one per line, as the document's reveal reads them. */
    fun unmatchedWire(id: String): List<String> =
        File(directoryFor(id), UNMATCHED_FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    /** Every record, newest first. */
    fun list(): List<LoadReport> =
        (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { read(it.name) }
            .sortedByDescending { it.startedAt }

    /** Keeps the [keep] most recent runs and deletes the rest. Same setting as the run records. */
    @Suppress("TooGenericExceptionCaught")
    fun prune(keep: Int) {
        if (keep <= 0) return
        try {
            list().drop(keep).forEach { directoryFor(it.id).deleteRecursively() }
        } catch (e: Exception) {
            logger.error("Could not prune the loads directory: ${e.message}", e)
        }
    }

    /**
     * A run that says RUNNING with nobody running it was interrupted. Stopped rather than failed, because
     * nothing is known about the venue, only that the process ended before it wrote its own verdict.
     */
    private fun healInterrupted(report: LoadReport, lastWrite: Long): LoadReport {
        val healed =
            report.copy(
                status = LoadStatus.STOPPED,
                phase = LoadPhase.DONE,
                finishedAt = report.finishedAt ?: lastWrite.takeIf { it > 0 } ?: report.startedAt,
                settleLeftMs = null,
                verdict = LoadReport.verdict(LoadStatus.STOPPED, report.replies, report.rate, report.tool, strictRate = false),
            )
        write(healed)
        return healed
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        const val REPORT_FILE = "load.json"
        const val UNMATCHED_FILE = "unmatched.fix"
        const val SPECIMENS_FILE = "specimens.fix"
    }
}
