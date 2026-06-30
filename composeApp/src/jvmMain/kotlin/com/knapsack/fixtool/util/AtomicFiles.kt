package com.knapsack.fixtool.util

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file writes. Writes to a sibling temp file and then atomically renames it over the
 * target, so a crash or interrupted write never leaves a half-written (corrupt) file — the target
 * is either the old content or the new content, never a truncated mix.
 *
 * This is the storage primitive for the scenario directory store; it also fixes the
 * truncate-in-place gap in the existing whole-file stores (`SavedMessagesService`,
 * `ConnectionProfileService`), which currently use a plain `writeText`.
 */
object AtomicFiles {
    @Suppress("SwallowedException")
    fun writeAtomically(file: File, content: String) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = File.createTempFile(file.name + ".", ".tmp", parent)
        try {
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Some filesystems (e.g. across mount points) can't do an atomic rename; fall back
                // to a best-effort replace, which is still safer than truncate-in-place.
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
