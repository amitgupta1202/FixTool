package com.knapsack.fixtool.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.knapsack.fixtool.model.AppSettings

/**
 * Every number Settings can edit, declared once — with the range that a value must land in and the
 * words used to say so when it does not.
 *
 * Declared rather than typed inline because the old dialog enforced these ranges at the last possible
 * moment and in silence: `coerceIn(100, 1_000_000)` on the way out of Save. Ask for a buffer of 50 and
 * it stored 1000, with nothing on screen between the asking and the storing. A range that is written
 * down can be shown to the person typing into it; one buried in a `copy()` argument cannot.
 */
enum class NumberSetting(
    val label: String,
    val range: LongRange,
    val unit: String,
    private val read: (AppSettings) -> Long,
    private val write: AppSettings.(Long) -> AppSettings,
) {
    SESSION_BUFFER(
        label = "Message buffer size",
        range = 100L..1_000_000L,
        unit = "messages per session",
        read = { it.sessionBufferSize.toLong() },
        write = { copy(sessionBufferSize = it.toInt()) },
    ),

    ORDER_BOOK_CAP(
        label = "Order book size",
        // Wider at the top than the message buffer, because the two are bounding different things: a
        // soak run that sends 50,000 orders through a session keeping 1,000 messages is the case this
        // setting exists for, and a cap that could not be raised past the scrollback would be the
        // derivation it was chosen instead of.
        range = 100L..1_000_000L,
        unit = "orders per counterparty",
        read = { it.orderBookCap.toLong() },
        write = { copy(orderBookCap = it.toInt()) },
    ),

    // Stored in microseconds, edited in milliseconds — the conversion belongs here, next to the range
    // it is expressed in, rather than being re-derived at every call site that touches the field.
    LATENCY_WARNING(
        label = "Warning threshold",
        range = 1L..600_000L,
        unit = "ms",
        read = { it.latencyWarningThresholdMicros / 1000 },
        write = { copy(latencyWarningThresholdMicros = it * 1000) },
    ),
    LATENCY_CRITICAL(
        label = "Critical threshold",
        range = 1L..600_000L,
        unit = "ms",
        read = { it.latencyCriticalThresholdMicros / 1000 },
        write = { copy(latencyCriticalThresholdMicros = it * 1000) },
    ),
    LATENCY_HISTORY(
        label = "History size",
        range = 100L..100_000L,
        unit = "samples",
        read = { it.latencyHistorySize.toLong() },
        write = { copy(latencyHistorySize = it.toInt()) },
    ),
    CONTROL_PORT(
        label = "Automation port",
        range = 1024L..65_535L,
        unit = "loopback only (127.0.0.1)",
        read = { it.automationControlPort.toLong() },
        write = { copy(automationControlPort = it.toInt()) },
    ),
    ;

    internal fun readFrom(settings: AppSettings): Long = read(settings)

    internal fun writeTo(settings: AppSettings, number: Long): AppSettings = settings.write(number)
}

/**
 * The settings being edited, as one value.
 *
 * The whole draft is a single [AppSettings]; nothing is copied out into a per-field `remember`. That is
 * the point. The dialog this replaces kept 27 separate state variables, and every one of them had to be
 * named again in Restore Defaults and a third time in Save — three lists, seventeen hundred lines apart,
 * that had to agree. They stopped agreeing: Restore Defaults quietly skipped `useBundledDictionary`, so
 * the control deciding which dictionary the app parses with survived a reset that claimed to undo
 * everything.
 *
 * Here restoring is one assignment and saving is one hand-off, so a newly added setting is carried by
 * both without being mentioned in either. There is no list left to fall out of step.
 */
@Stable
class SettingsDraft(val original: AppSettings) {
    var value by mutableStateOf(original)
        private set

    /**
     * Numbers as they are being typed.
     *
     * Text, not `Int`, because an `Int` cannot hold a field someone has just cleared in order to retype
     * it — and refusing the empty box is worse than allowing it, because the only way out of a field you
     * cannot empty is to know the arrow keys. A number leaves here for [value] the moment it parses and
     * fits its range; until then the typing is kept, and [errorOf] explains what it is waiting for.
     */
    private val typing = mutableStateMapOf<NumberSetting, String>()

    fun edit(change: AppSettings.() -> AppSettings) {
        value = value.change()
    }

    fun restoreDefaults() {
        value = AppSettings.default()
        typing.clear()
    }

    val isDirty: Boolean get() = value != original

    /**
     * The draft as it goes to disk.
     *
     * Only surrounding whitespace is removed, and only from the fields where it can be neither typed on
     * purpose nor meaningful: a path may well contain spaces, but never at its ends. Nothing else is
     * adjusted on the way out — a value that cannot be stored as asked stops Save instead, in [problems].
     */
    fun forSaving(): AppSettings =
        value.copy(
            defaultDataDictionary = value.defaultDataDictionary.trim(),
            defaultTransportDictionary = value.defaultTransportDictionary.trim(),
            connectionProfilesPath = value.connectionProfilesPath.trim(),
            savedMessagesPath = value.savedMessagesPath.trim(),
            scenariosPath = value.scenariosPath.trim(),
            captureNetworkInterface = value.captureNetworkInterface.trim(),
        )

    fun textOf(setting: NumberSetting): String = typing[setting] ?: setting.readFrom(value).toString()

    fun type(setting: NumberSetting, typed: String) {
        if (typed.any { !it.isDigit() }) return
        typing[setting] = typed
        typed.toLongOrNull()?.takeIf { it in setting.range }?.let { value = setting.writeTo(value, it) }
    }

    /** What is wrong with this field as it currently reads, or null while it reads fine. */
    fun errorOf(setting: NumberSetting): String? {
        val text = typing[setting] ?: return null
        val parsed = text.toLongOrNull()
        return when {
            text.isBlank() -> "${setting.label} is required"
            parsed == null -> "${setting.label} must be a whole number"
            parsed < setting.range.first -> "${setting.label} must be at least ${setting.range.first}"
            parsed > setting.range.last -> "${setting.label} must be at most ${setting.range.last}"
            else -> null
        }
    }

    /**
     * Everything standing between this draft and Save.
     *
     * Save is refused while this is non-empty rather than coercing the offending value to the nearest
     * legal one, because a setting silently changed on its way to disk is a setting the user believes
     * they configured.
     */
    val problems: List<String>
        get() {
            val perField = NumberSetting.entries.mapNotNull { errorOf(it) }
            // A critical threshold at or below the warning threshold makes the warning colour
            // unreachable — every sample fast enough to be merely a warning is already critical.
            val orderingIsWrong =
                perField.isEmpty() &&
                    value.enableLatencyTracking &&
                    value.latencyCriticalThresholdMicros <= value.latencyWarningThresholdMicros
            return if (orderingIsWrong) {
                perField + "Critical threshold must be above the warning threshold"
            } else {
                perField
            }
        }
}
