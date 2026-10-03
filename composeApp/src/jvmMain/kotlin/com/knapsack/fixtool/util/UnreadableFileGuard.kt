package com.knapsack.fixtool.util

import java.io.File

/**
 * Keeps a whole-file store from saving over a file it could not read.
 *
 * Such a store loads the whole file, changes it in memory and writes the whole file back. A load that fails
 * hands its caller an empty list or the defaults, and the next write then replaced everything in the file with
 * that: one profile where there were twenty, default Settings where there was a tuned set. A merge-conflict
 * marker in a committed workspace was enough, or an enum value written by a newer build.
 *
 * So the store reports every read here and asks before every write. A file whose last read failed is not
 * written until a read succeeds, or until the file is gone and there is nothing left in it to lose.
 */
class UnreadableFileGuard(
    private val file: File,
) {
    @Volatile
    private var unreadable = false

    /** Records how the latest read of the file went. A file that is not there reads successfully, as nothing. */
    fun read(succeeded: Boolean) {
        unreadable = !succeeded
    }

    /** Why the file must not be written now, for the user to be told, or null when it may be. */
    fun refusal(): String? =
        if (unreadable && file.exists()) {
            "FixTool will not overwrite ${file.absolutePath}: it could not be read, and saving over it would lose " +
                "what it holds. Fix the file or move it aside."
        } else {
            null
        }
}
