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
 * This is the storage primitive for the scenario directory store and for the whole-file stores a
 * workspace is kept in: profiles, secrets, saved messages, settings, environments and the workspace's
 * dictionary declaration.
 *
 * A file that is already there keeps its permissions. The rename would otherwise bring the temp file's,
 * and a `secrets.json` someone had made private would quietly stop being so at the next save.
 */
object AtomicFiles {
    @Suppress("SwallowedException")
    fun writeAtomically(file: File, content: String) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = File.createTempFile(file.name + ".", ".tmp", parent)
        try {
            tmp.writeText(content)
            keepPermissions(of = file, on = tmp)
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

    /** Copies [of]'s POSIX permissions onto [on], where there are any to copy and a filesystem that has them. */
    private fun keepPermissions(
        of: File,
        on: File,
    ) {
        if (of.exists()) {
            runCatching { Files.setPosixFilePermissions(on.toPath(), Files.getPosixFilePermissions(of.toPath())) }
        }
    }
}
