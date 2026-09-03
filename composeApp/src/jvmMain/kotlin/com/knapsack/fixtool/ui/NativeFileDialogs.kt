package com.knapsack.fixtool.ui

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.awt.KeyboardFocusManager
import java.io.File

/**
 * The three file dialogs this app needs, each one the operating system's own.
 *
 * These replace `JFileChooser`, which is Swing drawing its own idea of a file browser: no sidebar of
 * the places a user actually keeps things, no Recents, no iCloud, no Cmd-Shift-G to type a path, and
 * a look that belongs to no platform. FileKit dispatches to NSOpenPanel on macOS, IFileOpenDialog on
 * Windows and the XDG desktop portal on Linux, dropping back to AWT and Swing where no portal answers.
 *
 * All three suspend, because a native dialog is modal and has to be driven off the UI thread. Call
 * them from `rememberCoroutineScope().launch`, and read a null back as "the user cancelled".
 */
private fun dialogSettings() =
    FileKitDialogSettings(
        // The dialog is modal to the window that asked for it, so the app cannot be left with a
        // sheet attached to a window nobody is looking at. AWT knows which window that is; Compose
        // does not hand it to us.
        parentWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow,
    )

/** Picks an existing file. [extensions] are bare, without the dot; empty means show everything. */
suspend fun chooseFileToOpen(
    title: String,
    extensions: Set<String> = emptySet(),
    startIn: File? = null,
): File? =
    FileKit
        .openFilePicker(
            type = FileKitType.File(extensions.takeIf { it.isNotEmpty() }),
            title = title,
            directory = startIn?.let(::PlatformFile),
            dialogSettings = dialogSettings(),
        )?.file

/**
 * Picks where to write a file. [suggestedName] is the stem, without the dot or [extension] — the
 * platform joins the two itself, and only Windows then hides the extension it just appended.
 */
suspend fun chooseFileToSave(
    suggestedName: String,
    extension: String? = null,
    startIn: File? = null,
): File? =
    FileKit
        .openFileSaver(
            suggestedName = suggestedName,
            extension = extension,
            directory = startIn?.let(::PlatformFile),
            dialogSettings = dialogSettings(),
        )?.file

/** Picks a folder. */
suspend fun chooseDirectory(
    title: String,
    startIn: File? = null,
): File? =
    FileKit
        .openDirectoryPicker(
            title = title,
            directory = startIn?.let(::PlatformFile),
            dialogSettings = dialogSettings(),
        )?.file

/**
 * Where a dialog should open, given the path a field is already holding.
 *
 * A native dialog takes a folder, never a file, so a path naming a file starts the dialog in its
 * parent. A path that does not exist yet is no help at all — the operating system remembers where
 * the user was last, and that is a better guess than a folder that isn't there.
 */
fun dialogStartDirectory(
    path: String,
    namesDirectory: Boolean,
): File? {
    if (path.isBlank()) {
        return null
    }
    val named = File(path)
    val folder = if (namesDirectory) named else named.parentFile
    return folder?.takeIf { it.isDirectory }
}

/**
 * Splits a file name into the two halves [chooseFileToSave] wants.
 *
 * [fallbackExtension] covers a name that carries none, so the save dialog still proposes a usable
 * file rather than an extensionless one the user has to finish by hand.
 */
fun saveNameAndExtension(
    fileName: String,
    fallbackExtension: String? = null,
): Pair<String, String?> {
    if (fileName.isBlank()) {
        return "untitled" to fallbackExtension
    }
    // Only a dot with something on both sides of it separates an extension. A leading dot is part of
    // the name — `.gitignore` is a file called that, not an extension called `gitignore`.
    val dot = fileName.lastIndexOf('.')
    val extension = if (dot > 0) fileName.substring(dot + 1) else ""
    return when {
        extension.isBlank() -> fileName.trimEnd('.') to fallbackExtension
        else -> fileName.substring(0, dot) to extension
    }
}

/**
 * Gives a saved file an extension when the name that came back from the dialog has none of the
 * [allowed] ones.
 *
 * The dialog proposes a name but does not enforce it: a user is free to type `capture` over the
 * suggestion and press Save, and the file that comes back is then `capture` with nothing to say what
 * is in it. Anything the user did choose deliberately, `.txt` included, is left exactly as typed.
 */
fun withDefaultExtension(
    file: File,
    allowed: Set<String>,
    default: String,
): File =
    if (allowed.any { file.name.endsWith(".$it", ignoreCase = true) }) {
        file
    } else {
        File(file.absolutePath + ".$default")
    }
