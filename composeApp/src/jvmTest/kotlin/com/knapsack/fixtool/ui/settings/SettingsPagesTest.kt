package com.knapsack.fixtool.ui.settings

import com.knapsack.fixtool.model.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPagesTest {
    private val pages = settingsPages()
    private val json = Json { encodeDefaults = true }

    /**
     * Every setting is on a page, and can therefore be changed without a text editor.
     *
     * `latencyCorrelationTags` was not. It was stored, it was read on every session, and the only way to
     * change it was to edit `~/.fixtool/app_settings.json` by hand and restart — which is to say that in
     * practice nobody changed it. Nothing in a compiler or a UI test notices a field that is merely never
     * drawn, so the check is made here: revert one field at a time and require some page to notice.
     */
    @Test
    fun `no setting is invisible`() {
        val customised = json.encodeToJsonElement(customisedSettings()).jsonObject
        val defaults = json.encodeToJsonElement(AppSettings.default()).jsonObject

        assertEquals(defaults.keys, customised.keys)
        customised.keys.forEach { field ->
            // Guards the fixture itself: a field left at its default here could not prove anything below.
            assertTrue(
                customised[field] != defaults[field],
                "customisedSettings() leaves $field at its default, so this test cannot see it",
            )

            val onlyThisReverted =
                json.decodeFromJsonElement<AppSettings>(JsonObject(customised + (field to defaults.getValue(field))))
            val noticed = pages.filter { it.owns(customisedSettings()) != it.owns(onlyThisReverted) }

            assertTrue(
                noticed.isNotEmpty(),
                "$field is on no settings page — it can only be changed by hand-editing app_settings.json",
            )
        }
    }

    /** Two pages claiming the same field would light both their edit dots for one change. */
    @Test
    fun `no setting is claimed by two pages`() {
        val customised = json.encodeToJsonElement(customisedSettings()).jsonObject
        val defaults = json.encodeToJsonElement(AppSettings.default()).jsonObject

        customised.keys.forEach { field ->
            val onlyThisReverted =
                json.decodeFromJsonElement<AppSettings>(JsonObject(customised + (field to defaults.getValue(field))))
            val noticed = pages.filter { it.owns(customisedSettings()) != it.owns(onlyThisReverted) }

            assertEquals(1, noticed.size, "$field is shown on ${noticed.map { it.title }}")
        }
    }

    @Test
    fun `a page marks itself edited only for its own fields`() {
        val draft = SettingsDraft(AppSettings.default())
        draft.edit { copy(automationControlEnabled = true) }

        val edited = pages.filter { it.isEdited(draft) }

        assertEquals(listOf("Developer"), edited.map { it.title })
    }

    @Test
    fun `searching finds the page holding a setting by its own name`() {
        assertEquals("Developer", pages.single { it.matchFor("MCP") != null }.title)
        assertEquals("Latency", pages.single { it.matchFor("correlation") != null }.title)
        assertEquals("Storage", pages.single { it.matchFor("scenarios directory") != null }.title)
        assertEquals("Tags", pages.single { it.matchFor("grid view columns") != null }.title)
    }

    /**
     * A term that genuinely belongs to two pages offers both, rather than guessing.
     *
     * "port" is the automation port and it is also the transport dictionary; the sidebar prints the term
     * each page matched on, so an answer that looks unrelated explains itself instead of confusing.
     */
    @Test
    fun `a term two pages answer offers both, each saying why`() {
        val answers = pages.filter { it.matchFor("port") != null }.associate { it.title to it.matchFor("port") }

        assertEquals(mapOf("Protocol" to "transport dictionary", "Developer" to "port"), answers)
    }

    @Test
    fun `searching says which setting answered`() {
        assertEquals("automation control", pages.single { it.id == "developer" }.matchFor("automation"))
    }

    @Test
    fun `a search nothing answers matches no page`() {
        assertTrue(pages.none { it.matchFor("kerning") != null })
    }

    @Test
    fun `an empty search matches nothing, so every page stays listed`() {
        assertTrue(pages.all { it.matchFor("") == null })
    }

    @Test
    fun `pages are uniquely identified and each says who owns it`() {
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        pages.forEach { page ->
            assertTrue(page.subtitle.isNotBlank(), "${page.title} has no owner sentence")
            assertTrue(page.contains.isNotEmpty(), "${page.title} lists no settings to search by")
        }
    }

    @Test
    fun `protocol comes first because a wrong dictionary misnames every other page`() {
        assertEquals("protocol", pages.first().id)
    }

    @Test
    fun `every number belongs to the page that shows it`() {
        // NumberSetting is the one registry Save enforces ranges from; a number declared there and shown
        // nowhere would be unreachable in exactly the way latencyCorrelationTags was.
        val draft = SettingsDraft(AppSettings.default())
        NumberSetting.entries.forEach { setting ->
            val before = pages.filter { it.isEdited(draft) }
            assertNull(before.firstOrNull(), "a fresh draft cannot be edited")

            draft.type(setting, (setting.range.first + 1).toString())
            assertNotNull(
                pages.firstOrNull { it.isEdited(draft) },
                "${setting.label} changes nothing any page shows",
            )
            draft.restoreDefaults()
        }
    }
}
