package com.knapsack.fixtool.service

import org.slf4j.LoggerFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Back to the shipped example, for a copy that has been broken.
 *
 * The example exists to be something that works, so getting back to working is the one operation it
 * specifically needs. The demo this replaced had it for free: Stop deleted everything and Start
 * reinstalled the shipped state. Making Open idempotent took that away, so it comes back here rather
 * than as "quit and delete a folder in Finder", which is a poor answer for exactly the person an
 * example is for.
 *
 * Its own object rather than another method on [ExampleWorkspaces], which already lists examples,
 * copies them out, makes empty workspaces and reads the bundle. Resetting is a different job.
 */
object ExampleReset {
    private val logger = LoggerFactory.getLogger(ExampleReset::class.java)

    /** Sortable and filesystem-safe, so the folders a reset leaves behind read in order. */
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss")

    /**
     * Renames the copy at [target] out of the way and lays down [exampleId] in its place.
     *
     * **Nothing is deleted.** The current copy is renamed, not removed, because "reset" must not be a
     * button that silently destroys an afternoon's rule edits, and someone who wanted one thing out of
     * the old copy can go and get it. The cost is a folder left behind, which is the right way round
     * for that trade, and it is why Reset needs no confirmation.
     */
    fun run(
        exampleId: String,
        target: File,
        now: Long = System.currentTimeMillis(),
    ): Result<Outcome> =
        runCatching {
            requireNotNull(ExampleWorkspaces.byId(exampleId)) { "no bundled example '$exampleId'" }
            val location = requireNotNull(target.parentFile) { "'${target.absolutePath}' has no parent" }
            val movedAside = moveAside(target, location, now)
            val fresh = ExampleWorkspaces.open(exampleId, target.name, location, now).getOrThrow()
            logger.info("Reset {} to the shipped {}; previous copy at {}", target, exampleId, movedAside)
            Outcome(workspace = fresh, movedAside = movedAside)
        }

    /** Renames [target] out of the way, or returns null when there was nothing there to keep. */
    private fun moveAside(
        target: File,
        location: File,
        now: Long,
    ): File? {
        if (!target.isDirectory || target.listFiles().orEmpty().isEmpty()) {
            return null
        }
        val aside = File(location, "${target.name}-before-reset-${stamp.format(Date(now))}")
        require(target.renameTo(aside)) { "could not move ${target.name} aside" }
        return aside
    }

    /** What a reset did: the fresh workspace, and where the old one went if there was one. */
    data class Outcome(
        val workspace: File,
        val movedAside: File?,
    )
}
