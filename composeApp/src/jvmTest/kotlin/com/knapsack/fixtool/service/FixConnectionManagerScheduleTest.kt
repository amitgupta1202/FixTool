package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixDictionaryAdapter
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.DefaultSessionSchedule
import java.io.File
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A profile with no schedule is one session for as long as it is connected, midnight included.**
 *
 * The manager wrote StartTime=00:00:00 and EndTime=00:00:00 for a profile that set neither. QuickFIX/J does
 * not read equal times as "always on": it reads them as a daily window of 24 hours that closes and reopens
 * at 00:00 UTC. Every session logged on across midnight UTC was logged out with "Session reset" and had both
 * sequence numbers set back to 1. FixTool against FixTool hid it, because both ends reset together.
 *
 * A schedule the profile does give, in its own fields or in a custom parameter, is still a schedule.
 */
class FixConnectionManagerScheduleTest {
    private lateinit var home: File

    @Before
    fun setUp() {
        home = File(System.getProperty("java.io.tmpdir"), "fixtool-schedule-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun config(
        startTime: String = "",
        endTime: String = "",
        customParameters: Map<String, String> = emptyMap(),
    ) = FixConnectionConfig(
        senderCompID = "CLI",
        targetCompID = "SRV",
        host = "localhost",
        port = "1",
        autoReconnect = false,
        resetOnLogon = true,
        fileStorePath = File(home, "store").absolutePath,
        fileLogPath = File(home, "log").absolutePath,
        startTime = startTime,
        endTime = endTime,
        customParameters = customParameters,
    )

    private fun venue() =
        config().copy(
            connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
            senderCompID = "VENUE",
            targetCompID = FixConnectionConfig.ANY_CLIENT,
        )

    /** The schedule QuickFIX/J builds for the one session (or, for a venue, the template) the settings name. */
    private fun schedule(config: FixConnectionConfig): DefaultSessionSchedule {
        val dictionary = FixDictionaryAdapter.createDefault()
        val service =
            QuickFixService(
                config = config,
                dictionary = dictionary,
                onMessageReceived = {},
                onStateChanged = {},
            )
        val settings = FixConnectionManager(config, service, AppSettings(), dictionary).createSessionSettings()
        return DefaultSessionSchedule(settings, settings.sectionIterator().next())
    }

    private fun utc(instant: String): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = Instant.parse(instant).toEpochMilli() }

    @Test
    fun `a profile with no schedule is one session across midnight UTC`() {
        val schedule = schedule(config())

        assertTrue(
            schedule.isSameSession(utc("2026-10-14T23:59:59Z"), utc("2026-10-15T00:00:01Z")),
            "a profile that sets no schedule must not be reset at midnight UTC: $schedule",
        )
    }

    @Test
    fun `a profile with start and end times keeps its daily window`() {
        val schedule = schedule(config(startTime = "08:00:00", endTime = "17:00:00"))

        assertTrue(schedule.isSameSession(utc("2026-10-14T08:00:01Z"), utc("2026-10-14T16:59:59Z")))
        assertFalse(
            schedule.isSameSession(utc("2026-10-14T16:59:59Z"), utc("2026-10-15T08:00:01Z")),
            "08:00 to 17:00 is a new session every day",
        )
    }

    /** Custom parameters are written last, in [SESSION], so a schedule given there overrides the defaults. */
    @Test
    fun `a schedule given as custom parameters is still a schedule`() {
        val schedule = schedule(config(customParameters = mapOf("StartTime" to "08:00:00", "EndTime" to "17:00:00")))

        assertTrue(schedule.isSameSession(utc("2026-10-14T08:00:01Z"), utc("2026-10-14T16:59:59Z")))
        assertFalse(
            schedule.isSameSession(utc("2026-10-14T16:59:59Z"), utc("2026-10-15T08:00:01Z")),
            "a custom StartTime and EndTime must not be overridden by a non-stop default",
        )
    }

    /** A venue's [SESSION] is the template every client's session is created from, so it carries the same answer. */
    @Test
    fun `a venue with no schedule is one session across midnight UTC`() {
        val schedule = schedule(venue())

        assertTrue(
            schedule.isSameSession(utc("2026-10-14T23:59:59Z"), utc("2026-10-15T00:00:01Z")),
            "a venue that sets no schedule must not reset its clients at midnight UTC: $schedule",
        )
    }
}
