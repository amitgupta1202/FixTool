package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixFields
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.service.SavedMessagesService
import java.io.File

/**
 * **Where a load run's template comes from**: a file in a checkout, or a saved message by id or name.
 *
 * Saved messages are kept per profile, so a name is looked up under the issuing profile first, where the
 * template for that venue almost always lives, and under every other profile after that. A build box has
 * files rather than a saved-message store, which is why a path wins over a name.
 */
object LoadTemplates {
    fun resolve(
        nameOrPath: String,
        profileId: String?,
        savedMessages: SavedMessagesService,
        profiles: List<FixConnectionProfile>,
    ): LoadTemplate? {
        val file = File(nameOrPath)
        if (file.isFile) return fromFile(file)
        val ordered = listOfNotNull(profileId) + profiles.map { it.id }.filter { it != profileId }
        for (pid in ordered) {
            val found =
                savedMessages
                    .loadMessagesForProfile(pid)
                    .firstOrNull { it.id == nameOrPath || it.name.equals(nameOrPath, ignoreCase = true) }
            if (found != null) return of(found)
        }
        return null
    }

    /** The saved message's fields, minus the ones the editor has ticked off, minus anything that is not a tag. */
    fun of(saved: SavedFixMessage): LoadTemplate =
        LoadTemplate(
            name = saved.name,
            fields = saved.fields.filterNot { it.excluded }.mapNotNull { f -> f.tag.trim().toIntOrNull()?.let { it to f.value } },
        )

    /** The first non-blank line of [file], SOH or `|` delimited. Null when the file holds nothing. */
    fun fromFile(file: File): LoadTemplate? {
        val line = file.readLines().firstOrNull { it.isNotBlank() } ?: return null
        return fromRaw(file.nameWithoutExtension, line)
    }

    fun fromRaw(name: String, raw: String): LoadTemplate = LoadTemplate(name, FixFields.parse(raw.trim()))
}
