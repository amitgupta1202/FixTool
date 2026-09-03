package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.Environment
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An environment is where a counterparty is, and a connection is one times the other.
 *
 * The bug this exists to make impossible is in this repo's own saved profiles: `UAT1-BuySide` and
 * `UAT1-SellSide` carried the session qualifier `QA1`, so UAT1 and QA1 shared a QuickFIX/J
 * sequence-number store. Nobody typed that on purpose — it survived because the qualifier is a free
 * text field a human fills in three times.
 */
class EnvironmentsTest {
    private fun profile(
        name: String,
        host: String,
        port: String,
        qualifier: String = "",
        ssl: Boolean = false,
    ) = FixConnectionProfile(
        id = name,
        name = name,
        config =
            FixConnectionConfig(
                senderCompID = name.substringAfter('-'),
                targetCompID = "VENUE",
                host = host,
                socketConnectHost = host,
                port = port,
                sessionQualifier = qualifier,
                useSSL = ssl,
            ),
    )

    // ---------------------------------------------------------------- applying

    @Test
    fun `an environment moves the endpoint and nothing else`() {
        val config = profile("UAT1-BuySide", "old.host", "1234").config.copy(senderCompID = "ME", targetCompID = "THEM")
        val applied = Environment(name = "QA1", host = "qa.host", port = "9999").applyTo(config)

        assertEquals("qa.host", applied.host)
        assertEquals("9999", applied.port)
        assertEquals("ME", applied.senderCompID, "the counterparty's identity is not the environment's business")
        assertEquals("THEM", applied.targetCompID)
    }

    @Test
    fun `the session qualifier is the environment's name, so two environments cannot share a store`() {
        val config = profile("UAT1-BuySide", "h", "1", qualifier = "QA1").config
        assertEquals("UAT1", Environment(name = "UAT1", host = "uat.host").applyTo(config).sessionQualifier)
    }

    @Test
    fun `the dialled host follows the named host, so the two cannot disagree`() {
        val config = profile("X-Y", "old.host", "1").config
        val applied = Environment(name = "E", host = "new.host").applyTo(config)
        assertEquals("new.host", applied.host)
        assertEquals("new.host", applied.socketConnectHost)
    }

    @Test
    fun `a blank override leaves the profile's own value alone`() {
        val config = profile("X-Y", "keep.host", "4321", ssl = true).config
        val applied = Environment(name = "E").applyTo(config)

        assertEquals("keep.host", applied.host)
        assertEquals("4321", applied.port)
        assertTrue(applied.useSSL)
        assertEquals("E", applied.sessionQualifier, "the qualifier is the one thing an environment always sets")
    }

    @Test
    fun `TLS off is an override, not an absence`() {
        val config = profile("X-Y", "h", "1", ssl = true).config
        assertFalse(Environment(name = "E", useSSL = false).applyTo(config).useSSL)
        assertTrue(Environment(name = "E", useSSL = null).applyTo(config).useSSL)
    }

    // ---------------------------------------------------------------- proposing

    private val desk =
        listOf(
            profile("UAT1-BuySide", "fix.uat.example.com", "443", ssl = true),
            profile("UAT1-SellSide", "fix.uat.example.com", "443", ssl = true),
            profile("QA1-BuySide", "fix.qa.example.com", "443", ssl = true),
            profile("QA1-SellSide", "fix.qa.example.com", "443", ssl = true),
            profile("DEV1-BuySide", "fix.dev.example.com", "443", ssl = true),
            profile("DEV1-SellSide", "fix.dev.example.com", "443", ssl = true),
        )

    @Test
    fun `a desk's three-by-two grid of profiles is three environments and two counterparties`() {
        val proposal = Environments.propose(desk)

        assertEquals(listOf("DEV1", "QA1", "UAT1"), proposal.environments.map { it.name })
        assertEquals(listOf("BuySide", "SellSide"), proposal.counterparties)
        assertEquals(6, proposal.replaces.size)
        assertTrue(proposal.isWorthDoing)
    }

    @Test
    fun `each proposed environment takes the endpoint its own profiles agree on`() {
        val byName = Environments.propose(desk).environments.associateBy { it.name }
        assertEquals("fix.uat.example.com", byName.getValue("UAT1").host)
        assertEquals("fix.qa.example.com", byName.getValue("QA1").host)
        assertEquals("443", byName.getValue("DEV1").port)
        assertEquals(true, byName.getValue("DEV1").useSSL)
    }

    @Test
    fun `a prefix seen once is a hyphen in a name, not an environment`() {
        val proposal = Environments.propose(desk + profile("Scratch-Thing", "localhost", "1"))
        assertFalse(proposal.environments.any { it.name == "Scratch" })
        assertFalse(proposal.replaces.contains("Scratch-Thing"))
    }

    @Test
    fun `only the first hyphen splits, so a counterparty may have one in its name`() {
        val proposal =
            Environments.propose(
                listOf(
                    profile("Local-BuySide-Persistent", "localhost", "1"),
                    profile("Local-SellSide-Persistent", "localhost", "1"),
                    profile("Remote-BuySide-Persistent", "r", "1"),
                    profile("Remote-SellSide-Persistent", "r", "1"),
                ),
            )
        assertEquals(listOf("Local", "Remote"), proposal.environments.map { it.name })
        assertEquals(listOf("BuySide-Persistent", "SellSide-Persistent"), proposal.counterparties)
    }

    @Test
    fun `a profile with no hyphen is left out of the split entirely`() {
        val proposal = Environments.propose(desk + profile("Standalone", "localhost", "1"))
        assertFalse(proposal.replaces.contains("Standalone"))
    }

    @Test
    fun `one environment is not worth splitting for`() {
        val proposal = Environments.propose(desk.filter { it.name.startsWith("UAT1") })
        assertFalse(proposal.isWorthDoing, "a single environment is just the profiles you already have")
    }

    @Test
    fun `no profiles proposes nothing rather than failing`() {
        val proposal = Environments.propose(emptyList())
        assertEquals(emptyList(), proposal.environments)
        assertFalse(proposal.isWorthDoing)
    }

    // ---------------------------------------------------------------- storing

    @Test
    fun `environments round-trip through the workspace, in name order`() {
        val file = File(Files.createTempDirectory("environments").toFile(), "environments.json")
        val store = Environments(file)

        store.save(listOf(Environment("UAT1", host = "u"), Environment("DEV1", host = "d")))

        assertEquals(listOf("DEV1", "UAT1"), store.load().map { it.name })
        assertEquals("u", store.load().first { it.name == "UAT1" }.host)
    }

    @Test
    fun `a workspace with no environments file has no environments`() {
        val file = File(Files.createTempDirectory("environments-absent").toFile(), "environments.json")
        assertEquals(emptyList(), Environments(file).load())
    }
}
