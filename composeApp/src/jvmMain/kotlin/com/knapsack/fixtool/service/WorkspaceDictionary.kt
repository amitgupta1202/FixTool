package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The data dictionary a workspace was written against, named by the workspace itself.
 *
 * ### Why a workspace names one
 *
 * The dictionary used to belong to the installation alone, on the argument that it is the person at the
 * keyboard's and not the project's. That holds only for a dictionary nobody's work depends on, and no
 * workspace's work is like that. A dictionary decides where a repeating group starts, so it decides the wire
 * order of every message a venue builds, and a scenario's expectation rows are written in that order.
 *
 * Found on a demo machine: Settings named a venue's own FIX 4.4, whose QuoteRequest has no NoRelatedSym group.
 * The fixed-income platform relayed each request with every field flat and in tag order, so `131` came after
 * `38`, `48` and `54`, and three of its five scenarios failed on rows that had only moved. The platform was
 * right. The example was being read in a dictionary it was never written against, and nothing on the screen
 * said so.
 *
 * So a workspace may name its dictionary in [FILE], and while it is open that wins over Settings. A workspace
 * that names none gets the one in Settings, which is every workspace made before this existed.
 *
 * Exactly one of [fixVersion] and [path]: a declaration naming both is refused rather than read as either.
 */
@Serializable
data class WorkspaceDictionary(
    /** One of the dictionaries shipped inside FixTool. */
    val fixVersion: FixVersion? = null,
    /**
     * A dictionary file. Relative paths are the workspace's, so a workspace committed with its dictionary
     * beside it opens the same on every machine that checks it out.
     */
    val path: String? = null,
    /** FIXT.1.1 for a FIX 5.0+ [path], relative the same way. */
    val transportPath: String? = null,
) {
    /** Why this declaration cannot be used, or null when it can. */
    val problem: String?
        get() =
            when {
                fixVersion != null && !path.isNullOrBlank() -> "it names both a bundled version and a file"
                fixVersion == null && path.isNullOrBlank() -> "it names neither a bundled version nor a file"
                else -> null
            }

    companion object {
        /** The workspace's own settings, beside its profiles. */
        const val FILE = "workspace.json"

        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }

        /**
         * What a workspace's [FILE] holds.
         *
         * An object with one key today. Its own file rather than a field in `connection_profiles.json` because
         * the dictionary is not a property of any one profile, and a key nobody wrote means "nothing named",
         * which is how a workspace says "use Settings" after all.
         */
        @Serializable
        data class Contents(
            val dictionary: WorkspaceDictionary? = null,
        )

        /** Writes [dictionary] as [workspace]'s declaration, replacing whatever it named before. */
        fun write(
            workspace: File,
            dictionary: WorkspaceDictionary,
        ) {
            File(workspace, FILE).writeText(json.encodeToString(Contents.serializer(), Contents(dictionary)) + "\n")
        }

        internal fun parse(text: String): Contents = json.decodeFromString(Contents.serializer(), text)
    }
}

/** Which dictionary to load, and who chose it. */
sealed interface DictionaryChoice {
    val chosenBy: ChosenBy

    /** One of the dictionaries shipped inside FixTool. */
    data class Bundled(
        val version: FixVersion,
        override val chosenBy: ChosenBy,
    ) : DictionaryChoice

    /** A dictionary file, and FIXT.1.1 beside it when there is one. Neither is known to exist. */
    data class Files(
        val data: File,
        val transport: File?,
        override val chosenBy: ChosenBy,
    ) : DictionaryChoice

    /** What is loaded, in the words a reader looks for: a version for a bundled one, a file name otherwise. */
    val name: String
        get() =
            when (this) {
                is Bundled -> "the bundled ${version.displayName}"
                is Files -> data.name
            }

    companion object {
        /**
         * The dictionary [workspace] means, and otherwise the one [settings] name.
         *
         * In order: the workspace's own [WorkspaceDictionary.FILE]; for a copy of a bundled example that has
         * none, the example's; then Settings. The example step is for the copies laid down before examples
         * named a dictionary. Those folders are never written over, so without it the fix would not reach the
         * one machine the defect was found on. Read, not written: a file a user never asked for does not
         * appear in their workspace.
         *
         * A [WorkspaceDictionary.FILE] that exists but names no dictionary is Settings, even in an example copy.
         * That is the way to say "use mine" to an example.
         */
        fun resolve(
            settings: AppSettings,
            workspace: File?,
        ): DictionaryChoice {
            val named = workspace?.let(::namedBy)
            val dictionary = named?.dictionary
            if (named == null || dictionary == null || named.problem != null) {
                return fromSettings(settings, named?.problem)
            }
            val version = dictionary.fixVersion
            return if (version != null) {
                Bundled(version, named.chosenBy)
            } else {
                Files(
                    data = named.against(dictionary.path.orEmpty()),
                    transport = dictionary.transportPath?.takeIf { it.isNotBlank() }?.let(named::against),
                    chosenBy = named.chosenBy,
                )
            }
        }

        /** What a workspace names and where it was named, or why it could not be read. */
        private class Named(
            val workspace: File,
            val dictionary: WorkspaceDictionary?,
            val chosenBy: ChosenBy,
            val unreadable: String? = null,
        ) {
            /** Why this cannot be used, or null when it can. A file that names no dictionary is not a problem. */
            val problem: String?
                get() = unreadable ?: dictionary?.problem?.let { "${chosenBy.describe()} cannot be used: $it" }

            fun against(path: String): File = File(path).takeIf { it.isAbsolute } ?: File(workspace, path)
        }

        /** The workspace's own declaration, else its example's; null when neither exists. */
        private fun namedBy(workspace: File): Named? {
            val declared = File(workspace, WorkspaceDictionary.FILE)
            if (declared.isFile) {
                val chosenBy = ChosenBy.Workspace(declared)
                return runCatching { WorkspaceDictionary.parse(declared.readText()).dictionary }
                    .fold(
                        onSuccess = { Named(workspace, it, chosenBy) },
                        onFailure = { e ->
                            val why = "${declared.path} could not be read (${e.message})"
                            Named(workspace, null, chosenBy, unreadable = why)
                        },
                    )
            }
            val example = ExampleWorkspaces.exampleAt(workspace) ?: return null
            return Named(workspace, ExampleWorkspaces.dictionaryOf(example.id), ChosenBy.Example(example.displayName))
        }

        /** The same rule the app and `fixtool run` always applied: a blank path, or the box ticked, is bundled. */
        private fun fromSettings(
            settings: AppSettings,
            because: String? = null,
        ): DictionaryChoice {
            val chosenBy = ChosenBy.Settings(because)
            return if (settings.useBundledDictionary || settings.defaultDataDictionary.isBlank()) {
                Bundled(settings.defaultFixVersion, chosenBy)
            } else {
                Files(
                    data = File(settings.defaultDataDictionary),
                    transport = settings.defaultTransportDictionary.takeIf { it.isNotBlank() }?.let(::File),
                    chosenBy = chosenBy,
                )
            }
        }
    }
}

/** Who decided which dictionary is loaded. */
sealed interface ChosenBy {
    /** Settings -> Protocol, because the workspace named none — or named one that could not be used, [because]. */
    data class Settings(
        val because: String? = null,
    ) : ChosenBy

    /** The open workspace's own [WorkspaceDictionary.FILE]. */
    data class Workspace(
        val file: File,
    ) : ChosenBy

    /** The bundled example the open workspace is a copy of, for a copy made before examples named a dictionary. */
    data class Example(
        val displayName: String,
    ) : ChosenBy

    /** Where the choice was made, completing "… names it". */
    fun describe(): String =
        when (this) {
            is Settings -> "Settings -> Protocol"
            is Workspace -> "this workspace's ${file.name}"
            is Example -> "the $displayName example this workspace is a copy of"
        }
}
