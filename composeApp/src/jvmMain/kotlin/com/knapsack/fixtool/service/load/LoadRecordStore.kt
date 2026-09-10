package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.load.LoadRecord
import com.knapsack.fixtool.model.load.LoadReport
import com.knapsack.fixtool.model.load.LoadStage
import com.knapsack.fixtool.model.load.LoadStatus
import com.knapsack.fixtool.service.FixMessageHelper.toRawFixMessage
import com.knapsack.fixtool.service.WorkspacePaths
import com.knapsack.fixtool.util.NotifyingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    fun write(report: LoadReport): Boolean = write(LoadRecord.of(report))

    /** The same file, whether one phase or several. One shape on disk is the whole point — see [LoadRecord]. */
    @Suppress("TooGenericExceptionCaught")
    fun write(record: LoadRecord): Boolean =
        try {
            val dir = directoryFor(record.id).also { it.mkdirs() }
            replace(File(dir, REPORT_FILE), json.encodeToString(JsonObject.serializer(), LoadReportCodec.recordToJson(record)))
            true
        } catch (e: Exception) {
            logger.error("Could not write load record '${record.id}': ${e.message}", e)
            false
        }

    /**
     * **[text] into [file] whole, or not at all.**
     *
     * `writeText` empties the file and then fills it, and every reader of a load record is in this process
     * beside the writer: the poll behind `awaitLoad`, `GET /loads`, a document being reopened, and `prune`.
     * A read landing inside that window parses half a file and puts a notification in front of somebody
     * about a record nothing is wrong with. A set writes this file on every progress tick of every phase
     * it is running, so the window is as common as the phase count makes it.
     *
     * The bytes go to a temp file beside it and one rename puts them in place, which is a single step on
     * every filesystem this runs on. A reader either sees the record it saw before or the whole new one.
     *
     * **A rename that will not happen is not a failure of the write.** Windows renames through
     * `MoveFileEx`, which refuses while anything else holds the file open, and the JDK names only "a
     * different device" as `AtomicMoveNotSupportedException`: a sharing violation arrives as
     * `AccessDeniedException` and would reach [write]'s catch and put a notification in front of somebody
     * about a progress tick nothing was wrong with. So **every** failure of the rename falls back to the
     * write this replaced, said to the log and not to the user. That one tick is written the old way,
     * with the old window on it, which is by a distance the smaller of the two costs.
     */
    private fun replace(file: File, text: String) {
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        temp.writeText(text)
        val replace = StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, replace)
        } catch (e: IOException) {
            logger.debug("$file could not be renamed into place, so it is written where it stands: ${e.message}")
            file.writeText(text)
            temp.delete()
        }
    }

    /**
     * The evidence files: one unanswered request per line, and specimen pairs as request then reply.
     *
     * [evidence] names them, because a set writes several phases into one directory and nothing downstream
     * should have to reconstruct `02-unmatched.fix` from a phase number.
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    fun writeEvidence(
        id: String,
        evidence: LoadReport.Evidence,
        unmatched: List<StampMatcher.Unmatched>,
        specimens: List<StampMatcher.Specimen>,
        /** One entry per index that carries a captured value. Empty when the phase captured nothing. */
        captured: List<Pair<Int, List<Pair<String, String>>>> = emptyList(),
    ): Boolean =
        try {
            val d = directoryFor(id).also { it.mkdirs() }
            File(d, evidence.unmatched).writeText(unmatched.joinToString("") { it.wire.toRawFixMessage() + "\n" })
            File(d, evidence.specimens).writeText(
                specimens.joinToString("") { it.request.toRawFixMessage() + "\n" + it.reply.toRawFixMessage() + "\n" },
            )
            // Tab-separated, one line per index, because the question anybody opens this to answer is
            // "what did the venue say for 412", and grep answers it without a parser.
            evidence.captured?.let { name ->
                File(d, name).writeText(
                    captured.joinToString("") { (index, row) ->
                        index.toString() + row.joinToString("") { (n, v) -> "\t$n=$v" } + "\n"
                    },
                )
            }
            true
        } catch (e: Exception) {
            logger.error("Could not write load evidence for '$id': ${e.message}", e)
            false
        }

    /** The phase a single run is. Every surface but Compare wants this one. */
    fun read(id: String): LoadReport? = readRecord(id)?.only

    /**
     * The whole record, phases and all, in whichever shape it was written. What Compare reads.
     *
     * A record that says RUNNING with nobody running it is healed on the way out, phase by phase, and
     * written back once so every later reader finds the same answer.
     *
     * **Read through NIO and never `readText`**, because the write beside it is a rename. A `java.io` read
     * on Windows holds the file without sharing its deletion, and a rename onto a file held that way is
     * refused: the reader costs the writer the progress tick it was in the middle of, and somebody is
     * notified about a record nothing is wrong with. [Files.readString] shares it, so a read and a
     * replace pass each other. Every reader of a record file goes through here, [list] and [listRecords]
     * included.
     */
    @Suppress("TooGenericExceptionCaught")
    fun readRecord(id: String): LoadRecord? =
        try {
            val file = File(directoryFor(id), REPORT_FILE).takeIf { it.isFile } ?: return null
            val text = Files.readString(file.toPath())
            val record = LoadReportCodec.recordFromJson(Json.parseToJsonElement(text).jsonObject)
            if (record.status == LoadStatus.RUNNING && !isLive(record.id)) healInterrupted(record, file.lastModified()) else record
        } catch (e: Exception) {
            logger.error("Could not read load record '$id': ${e.message}", e)
            null
        }

    /**
     * The unanswered requests' wire, one per line, as the document's reveal reads them.
     *
     * [evidence] is the phase's own block. Null is a record written before the names were in the JSON, and
     * falls back to the bare name the one-phase writer used.
     */
    fun unmatchedWire(id: String, evidence: LoadReport.Evidence? = null): List<String> =
        File(directoryFor(id), evidence?.unmatched ?: UNMATCHED_FILE)
            .takeIf { it.isFile }
            ?.readLines()
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Every record, newest first. */
    fun list(): List<LoadReport> = listRecords().map { it.only }

    /** Every record whole, newest first. What Compare offers as the other run. */
    fun listRecords(): List<LoadRecord> =
        (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { readRecord(it.name) }
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
    private fun healInterrupted(record: LoadRecord, lastWrite: Long): LoadRecord {
        val healed =
            record.copy(
                finishedAt = record.finishedAt ?: lastWrite.takeIf { it > 0 } ?: record.startedAt,
                phases =
                    record.phases.map {
                        when (it.status) {
                            LoadStatus.RUNNING -> heal(it, lastWrite)
                            // A phase of a set that had not started when the process ended never will.
                            LoadStatus.PENDING ->
                                it.copy(status = LoadStatus.SKIPPED, note = "the set's process ended before this phase")
                            else -> it
                        }
                    },
            )
        write(healed)
        return healed
    }

    private fun heal(report: LoadReport, lastWrite: Long): LoadReport =
        report.copy(
            status = LoadStatus.STOPPED,
            stage = LoadStage.DONE,
            finishedAt = report.finishedAt ?: lastWrite.takeIf { it > 0 } ?: report.startedAt,
            settleLeftMs = null,
            verdict = LoadReport.verdict(LoadStatus.STOPPED, report.replies, report.rate, report.tool, strictRate = false),
        )

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        const val REPORT_FILE = "load.json"
        const val UNMATCHED_FILE = "unmatched.fix"
        const val SPECIMENS_FILE = "specimens.fix"

        /**
         * What the record is written as before it is moved into place.
         *
         * Beside the record rather than in the system temp directory, because a rename is only one step
         * when both paths are on the same filesystem, which is the whole point of writing it here.
         */
        const val TEMP_SUFFIX = ".writing"
    }
}
