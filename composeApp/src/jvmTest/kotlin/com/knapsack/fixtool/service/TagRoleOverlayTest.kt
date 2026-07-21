package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.ScenarioStep
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A venue's proprietary correlation ids, declared beside its own dictionary.**
 *
 * The constraint this exists to honour: FixTool is one tool across a dozen venues, so a proprietary tag
 * must never enter its source — hardcoding BrokerTec's `LegQuoteReqID(20013)` in `ID_TAGS` is a claim
 * about every other venue's 20013. The declaration travels with the dialect it describes instead.
 */
class TagRoleOverlayTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var fixtureCount = 0

    /**
     * A tiny but real dictionary carrying one venue tag, plus the sidecar that says what it is.
     *
     * Each call gets its own filename because [FixDictionaryAdapter] caches by absolute path — two
     * fixtures sharing one name in a single test would silently hand back the first one's overlay, and
     * the test would be asserting against a dictionary it did not build.
     */
    private fun dictionaryWithRoles(rolesJson: String?): FixDictionaryAdapter {
        val stem = "venue-dictionary-${fixtureCount++}"
        val xml = temp.newFile("$stem.xml")
        xml.writeText(
            """
            <fix major="4" minor="4" type="FIX">
              <header><field name="BeginString" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="QuoteRequest" msgtype="R" msgcat="app">
                  <field name="QuoteReqID" required="N"/>
                  <field name="LegQuoteReqID" required="N"/>
                  <field name="VenueQuoteHandle" required="N"/>
                </message>
                <message name="Quote" msgtype="S" msgcat="app">
                  <field name="QuoteReqID" required="N"/>
                  <field name="LegQuoteReqID" required="N"/>
                  <field name="VenueQuoteHandle" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="131" name="QuoteReqID" type="STRING"/>
                <field number="20013" name="LegQuoteReqID" type="STRING"/>
                <field number="20040" name="VenueQuoteHandle" type="STRING"/>
              </fields>
            </fix>
            """.trimIndent(),
        )
        rolesJson?.let { temp.newFile("$stem.xml.roles.json").writeText(it) }
        return FixDictionaryAdapter.fromFile(xml)
    }

    private fun candidate(direction: FixMessage.Direction, type: String, vararg fields: Pair<Int, String>) =
        ScenarioCapture.Candidate(
            session = "Venue",
            direction = direction,
            messageType = type,
            wire = fields.joinToString("") { "${it.first}=${it.second}" },
            timestamp = LocalDateTime.of(2026, 7, 21, 12, 0, 0),
            fields = fields.toList(),
            source = null,
        )

    /** QuoteRequest out carrying a leg id, Quote back echoing it — the flow the overlay is for. */
    private fun rfq() =
        listOf(
            candidate(FixMessage.Direction.OUTGOING, "R", 35 to "R", 131 to "QR-1", 20013 to "LEG-A7F3C201"),
            candidate(FixMessage.Direction.INCOMING, "S", 35 to "S", 131 to "QR-1", 20013 to "LEG-A7F3C201"),
        )

    private fun capture(d: FixDictionaryAdapter) =
        ScenarioCapture.captureFrom("x", "x", null, rfq(), d)

    // ----- parsing --------------------------------------------------------------------------------------

    @Test
    fun `a role is read from the sidecar, single value or list`() {
        val overlay = TagRoleOverlay.read("""{"20013":"CLIENT_MINTED_ID","117":["CLIENT_MINTED_ID","VENUE_MINTED_ID"]}""")
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), overlay.rolesOf(20013))
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID, TagRole.VENUE_MINTED_ID), overlay.rolesOf(117))
    }

    /**
     * One typo must not discard a venue's whole declaration. The failure that guards against is silent and
     * total: capture would go back to replaying every declared id verbatim, with nothing on screen to say
     * the file had stopped being read.
     */
    @Test
    fun `a malformed entry is skipped, not fatal to the rest`() {
        val overlay = TagRoleOverlay.read("""{"20013":"CLIENT_MINTED_ID","oops":"CLIENT_MINTED_ID","20040":"NOT_A_ROLE"}""")
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), overlay.rolesOf(20013))
        assertEquals(emptySet(), overlay.rolesOf(20040))
        assertEquals(1, overlay.size)
    }

    @Test
    fun `unreadable json is the absence of an answer, not a crash`() {
        assertTrue(TagRoleOverlay.read("{ this is not json").isEmpty)
        assertTrue(TagRoleOverlay.beside(null).isEmpty)
        assertTrue(TagRoleOverlay.beside("/no/such/dictionary.xml").isEmpty)
    }

    // ----- what it changes about a capture --------------------------------------------------------------

    /** Without the sidecar the venue's id is a literal: re-sent verbatim, and its echo unrecognised. */
    @Test
    fun `an undeclared venue id replays verbatim and its echo is asserted exactly`() {
        val scenario = capture(dictionaryWithRoles(null))

        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        assertTrue(send.raw.contains("20013=LEG-A7F3C201"), send.raw)

        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val row = expect.expectation.fields.single { it.tag == 20013 }
        assertEquals(Matcher.Exact("LEG-A7F3C201"), row.matcher)
        assertNull(expect.match?.fields?.firstOrNull { it.tag == 20013 })
    }

    /** Declared, it gets exactly what a standard correlation id gets — mint, reference, bind constraint. */
    @Test
    fun `a declared venue id is minted fresh, echo-checked and bound`() {
        val scenario = capture(dictionaryWithRoles("""{"20013":"CLIENT_MINTED_ID"}"""))

        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()
        // Named from the venue's own dictionary, not `tag20013` — the same mint rule every path shares.
        assertTrue(send.raw.contains("20013=\${legQuoteReqID = uuid:20}"), send.raw)

        val expect = scenario.steps.filterIsInstance<ScenarioStep.Expect>().single()
        val row = expect.expectation.fields.single { it.tag == 20013 }
        assertEquals(Matcher.Reference("\${legQuoteReqID}"), row.matcher)
        // …and the step binds to THIS run's reply, not merely the first Quote on a busy session.
        val binds = expect.match?.fields ?: emptyList()
        assertEquals("\${legQuoteReqID}", binds.single { it.tag == 20013 }.value)
    }

    @Test
    fun `a venue-minted id is asserted for presence rather than its value`() {
        val d = dictionaryWithRoles("""{"20040":"VENUE_MINTED_ID"}""")
        val seeded = ExpectationSeeder.seedDetailed(listOf(35 to "S", 20040 to "VENUE-HANDLE-77"), d)

        assertEquals(Matcher.Presence, seeded.single { it.field.tag == 20040 }.field.matcher)
    }

    @Test
    fun `a declared lifetime is sent into the future and asserted for presence`() {
        val d = dictionaryWithRoles("""{"20040":"LIFETIME"}""")

        val sent =
            ScenarioCapture.captureFrom(
                "x",
                "x",
                null,
                listOf(candidate(FixMessage.Direction.OUTGOING, "R", 35 to "R", 20040 to "20260101-00:00:00")),
                d,
            )
        val send = sent.steps.filterIsInstance<ScenarioStep.Send>().single()
        assertTrue(send.raw.contains("20040=\${utcnow+5min}"), send.raw)

        val seeded = ExpectationSeeder.seedDetailed(listOf(35 to "S", 20040 to "20260101-00:00:00"), d)
        assertEquals(Matcher.Presence, seeded.single { it.field.tag == 20040 }.field.matcher)
    }

    /**
     * A tag the overlay speaks for is **answered**, even where the dictionary XML has no name for it —
     * warning about it anyway would train the author to ignore the warning, which is the one thing it
     * cannot afford. Note the tag here is absent from the dictionary's `<fields>` on purpose: a tag the
     * dictionary *names* was never in this warning's scope (it is about what cannot be typed), so the
     * overlay's effect is only visible on one the dictionary has never heard of.
     */
    @Test
    fun `a declared tag is answered even when the dictionary cannot name it`() {
        val unknownTag = listOf(candidate(FixMessage.Direction.OUTGOING, "R", 35 to "R", 20099 to "SOMETHING"))

        assertTrue(20099 in ScenarioCapture.unclassifiedTags(unknownTag, dictionaryWithRoles(null)))

        val declared = dictionaryWithRoles("""{"20099":"CLIENT_MINTED_ID"}""")
        assertTrue(20099 !in ScenarioCapture.unclassifiedTags(unknownTag, declared))
        assertNull(ScenarioCapture.captureRisk(unknownTag, declared))
    }

    /**
     * The standard sets are FixTool's, and an overlay only ever adds. A venue that forgets to declare
     * ClOrdID must not thereby lose it.
     */
    @Test
    fun `the built-in standard roles survive an overlay that does not mention them`() {
        val scenario = capture(dictionaryWithRoles("""{"20013":"CLIENT_MINTED_ID"}"""))
        val send = scenario.steps.filterIsInstance<ScenarioStep.Send>().single()

        assertTrue(send.raw.contains("131=\${quoteReqID = uuid:20}"), send.raw)
    }
}
