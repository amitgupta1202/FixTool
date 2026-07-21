package com.knapsack.fixtool.ui.settings

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.MessageColorScheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings with every single field moved off its default.
 *
 * Shared by the draft and page tests, and required to differ from [AppSettings.default] in *every*
 * field — `SettingsPagesTest` asserts exactly that before using it, so a field added to `AppSettings`
 * and forgotten here is reported rather than silently untested.
 */
internal fun customisedSettings(): AppSettings =
    AppSettings(
        defaultDataDictionary = "/tmp/venue.xml",
        defaultTransportDictionary = "/tmp/FIXT11.xml",
        defaultFixVersion = FixVersion.FIX_5_0,
        useBundledDictionary = true,
        validateFieldsOutOfOrder = true,
        validateFieldsHaveValues = true,
        validateUserDefinedFields = true,
        validateIncomingMessage = true,
        sessionBufferSize = 5000,
        gridViewColumns = listOf(35, 55),
        hideProtocolTags = false,
        protocolTags = setOf(8, 9),
        messageColorScheme = MessageColorScheme.greenRed(),
        rejectionRules = emptyList(),
        defaultViewMode = "terminal",
        defaultLayout = "tabs",
        connectionProfilesPath = "/tmp/profiles.json",
        savedMessagesPath = "/tmp/saved.json",
        scenariosPath = "/tmp/scenarios",
        enableLatencyTracking = true,
        captureNetworkInterface = "en0",
        latencyCorrelationTags = listOf(11),
        latencyHistorySize = 500,
        latencyWarningThresholdMicros = 1_000L,
        latencyCriticalThresholdMicros = 2_000L,
        showLatencyColumn = false,
        autoSyncSessionToEditor = false,
        automationControlEnabled = true,
        automationControlPort = 9999,
    )

/**
 * The draft exists to make two classes of mistake unreachable, and these are the tests that say so.
 *
 * The first is a reset that forgets a field: the dialog this replaces re-listed all 27 settings by hand
 * inside Restore Defaults, and left two of them out. The second is a value quietly changed on its way to
 * disk: the same dialog coerced every out-of-range number in the Save handler, so asking for a buffer of
 * 50 stored 1000 without a word.
 */
class SettingsDraftTest {
    @Test
    fun `restore defaults leaves nothing behind`() {
        val draft = SettingsDraft(customisedSettings())

        draft.restoreDefaults()

        // Field by field is the point: comparing the whole value is what a hand-written reset could
        // never do, and the two fields below are the ones the old reset actually missed.
        assertEquals(AppSettings.default(), draft.value)
        assertFalse(draft.value.useBundledDictionary, "the dictionary mode must be reset like everything else")
        assertEquals(FixVersion.FIX_4_4, draft.value.defaultFixVersion)
    }

    @Test
    fun `restore defaults also clears a number that was being typed`() {
        val draft = SettingsDraft(customisedSettings())
        draft.type(NumberSetting.SESSION_BUFFER, "7")

        draft.restoreDefaults()

        assertEquals("1000", draft.textOf(NumberSetting.SESSION_BUFFER))
        assertNull(draft.errorOf(NumberSetting.SESSION_BUFFER))
    }

    @Test
    fun `a number below its range stops Save instead of being corrected`() {
        val draft = SettingsDraft(AppSettings.default())

        draft.type(NumberSetting.SESSION_BUFFER, "50")

        assertEquals("50", draft.textOf(NumberSetting.SESSION_BUFFER), "the typing stays as typed")
        assertEquals(1000, draft.value.sessionBufferSize, "and never reaches the value")
        assertNotNull(draft.errorOf(NumberSetting.SESSION_BUFFER))
        assertTrue(draft.problems.any { it.contains("at least 100") }, "problems were ${draft.problems}")
    }

    @Test
    fun `a number above its range stops Save too`() {
        val draft = SettingsDraft(AppSettings.default())

        draft.type(NumberSetting.CONTROL_PORT, "70000")

        assertEquals(8765, draft.value.automationControlPort)
        assertTrue(draft.problems.any { it.contains("at most 65535") }, "problems were ${draft.problems}")
    }

    @Test
    fun `an emptied field is allowed while typing and refused at Save`() {
        val draft = SettingsDraft(AppSettings.default())

        draft.type(NumberSetting.LATENCY_HISTORY, "")

        assertEquals("", draft.textOf(NumberSetting.LATENCY_HISTORY), "clearing a box to retype it must work")
        assertTrue(draft.problems.isNotEmpty())
    }

    @Test
    fun `a number in range reaches the value`() {
        val draft = SettingsDraft(AppSettings.default())

        draft.type(NumberSetting.SESSION_BUFFER, "2500")

        assertEquals(2500, draft.value.sessionBufferSize)
        assertTrue(draft.problems.isEmpty())
        assertTrue(draft.isDirty)
    }

    @Test
    fun `latency thresholds are typed in milliseconds and stored in microseconds`() {
        val draft = SettingsDraft(AppSettings.default())

        assertEquals("100", draft.textOf(NumberSetting.LATENCY_WARNING), "100ms is the default 100_000us")

        draft.type(NumberSetting.LATENCY_WARNING, "250")

        assertEquals(250_000L, draft.value.latencyWarningThresholdMicros)
    }

    @Test
    fun `a critical threshold at or below the warning threshold is refused`() {
        val draft = SettingsDraft(AppSettings.default().copy(enableLatencyTracking = true))

        draft.type(NumberSetting.LATENCY_CRITICAL, "100")

        assertTrue(
            draft.problems.any { it.contains("above the warning threshold") },
            "an unreachable warning band should be reported, problems were ${draft.problems}",
        )
    }

    @Test
    fun `thresholds are only compared while latency tracking is on`() {
        val draft = SettingsDraft(AppSettings.default().copy(enableLatencyTracking = false))

        draft.type(NumberSetting.LATENCY_CRITICAL, "100")

        assertTrue(draft.problems.isEmpty(), "problems were ${draft.problems}")
    }

    @Test
    fun `an untouched draft is not dirty and edits make it so`() {
        val draft = SettingsDraft(customisedSettings())

        assertFalse(draft.isDirty)

        draft.edit { copy(defaultLayout = "vertical") }

        assertTrue(draft.isDirty)
    }

    @Test
    fun `saving trims the ends of paths and leaves the middle alone`() {
        val draft =
            SettingsDraft(
                AppSettings.default().copy(scenariosPath = "  /Users/me/Application Support/scenarios  "),
            )

        assertEquals("/Users/me/Application Support/scenarios", draft.forSaving().scenariosPath)
    }

    @Test
    fun `switching to the bundled dictionary keeps the custom path to switch back to`() {
        val draft = SettingsDraft(AppSettings.default().copy(defaultDataDictionary = "/tmp/venue.xml"))

        draft.edit { copy(useBundledDictionary = true) }

        assertEquals("/tmp/venue.xml", draft.forSaving().defaultDataDictionary)
    }
}
