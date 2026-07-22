package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.TagRole
import com.knapsack.fixtool.model.TagRoleOverlay
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VenueTagScan] — the list an author is shown when they go looking, which is the only way the overlay
 * ever gets written. See that object's KDoc for why this is a place you go rather than a prompt you get.
 */
class VenueTagScanTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var n = 0

    /** A venue dictionary: standard FIX 4.4 plus the venue's own tags, some id-shaped, some not. */
    private fun venueDictionary(rolesJson: String? = null): FixDictionaryAdapter {
        val stem = "venue-${n++}"
        val xml = temp.newFile("$stem.xml")
        xml.writeText(
            """
            <fix major="4" minor="4" type="FIX">
              <header><field name="BeginString" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="QuoteRequest" msgtype="R" msgcat="app">
                  <field name="QuoteReqID" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="48" name="SecurityID" type="STRING"/>
                <field number="131" name="QuoteReqID" type="STRING"/>
                <field number="820" name="TradeLinkID" type="STRING"/>
                <field number="20001" name="LegRefID" type="STRING"/>
                <field number="20071" name="SecondaryTradeLinkID" type="STRING"/>
                <field number="20050" name="Note1" type="STRING"/>
                <field number="20063" name="Cleared" type="BOOLEAN"/>
                <field number="20044" name="RoS" type="FLOAT"/>
              </fields>
            </fix>
            """.trimIndent(),
        )
        rolesJson?.let { temp.newFile("$stem.xml.roles.json").writeText(it) }
        return FixDictionaryAdapter.fromFile(xml)
    }

    private fun tags(cs: List<VenueTagScan.Candidate>) = cs.map { it.tag }

    @Test
    fun `the venue's own tags are offered as the likely questions`() {
        val scanned = scan()

        assertTrue(20001 in tags(scanned), "the venue's leg id must be offered")
        assertTrue(20071 in tags(scanned))
        assertEquals(
            VenueTagScan.Tier.IDENTIFIER,
            scanned.first { it.tag == 20001 }.tier,
            "an id-shaped tag nobody has answered for is the question this dialog exists to ask",
        )
        assertTrue(scanned.first { it.tag == 20001 }.custom, "the venue defines it beyond standard FIX")
    }

    /**
     * **Standard did not mean decided, and treating it that way cost real coverage.**
     *
     * `TradeLinkID(820)` is standard FIX 4.4 and is in none of FixTool's built-in sets, so capture seeded
     * it `Exact` and replayed a dead link id every run — while the one surface that could have declared it
     * refused to show it, because the scan offered only tags a dictionary added *beyond* standard FIX.
     */
    @Test
    fun `a standard tag no built-in set classifies is offered like any other`() {
        val candidate = scan().first { it.tag == 820 }

        assertEquals(VenueTagScan.Tier.IDENTIFIER, candidate.tier)
        assertTrue(!candidate.custom, "820 is standard FIX — offered because nothing answers for it, not because it is the venue's")
        assertTrue(candidate.builtIn.isEmpty())
    }

    /**
     * A tag FixTool answers for is shown *with* its answer rather than hidden: the author has to be able
     * to see that `QuoteReqID(131)` is already treated as client-minted before they can decide their venue
     * disagrees. What they cannot do is edit it away — the overlay only ever adds.
     */
    @Test
    fun `a tag FixTool already decides is shown with its answer`() {
        val candidate = scan().first { it.tag == 131 }

        assertEquals(VenueTagScan.Tier.BUILT_IN, candidate.tier)
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), candidate.builtIn)
    }

    /**
     * The highest-cost false positive there is: `SecurityID` echoes on every flow and its name ends in
     * `ID`, so it would sit at the top of the identifier tier — while minting a fresh value for it sends
     * the venue an instrument that does not exist. Answered for, with the reason on the row.
     */
    @Test
    fun `reference data is answered for, not offered as a correlation id`() {
        val candidate = scan().first { it.tag == 48 }

        assertEquals(VenueTagScan.Tier.BUILT_IN, candidate.tier)
        assertTrue(candidate.builtInReason?.contains("reference data") == true, candidate.builtInReason.orEmpty())
    }

    /**
     * The transport envelope is the one thing genuinely out of reach: the engine rewrites those fields on
     * every send whatever the sidecar says, so a control for them could not take effect.
     */
    @Test
    fun `the transport envelope is not offered, because a declaration there cannot take effect`() {
        assertTrue(8 !in tags(scan()), "BeginString is rewritten on send")
        assertTrue(10 !in tags(scan()), "CheckSum is rewritten on send")
    }

    /**
     * Tier used to be the first sort key, so the list read as several interleaved ascending runs and
     * finding `820` meant knowing which tier it had landed in first. The number is what the eye follows.
     */
    @Test
    fun `the list is in numeric order, whatever the tier`() {
        val scanned = tags(scan())

        assertEquals(scanned.sorted(), scanned, "tags must be in numeric order")
        assertTrue(scanned.containsAll(listOf(35, 48, 131, 820, 20001, 20044, 20050, 20063, 20071)))
    }

    @Test
    fun `a declared tag carries its role, so a wrong call can be revised`() {
        val declared = scan("""{"20050":"CLIENT_MINTED_ID"}""").first { it.tag == 20050 }

        assertEquals(VenueTagScan.Tier.DECLARED, declared.tier)
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), declared.roles)
    }

    @Test
    fun `no dictionary is an empty list, not a crash`() {
        assertEquals(emptyList(), VenueTagScan.scan(null))
        assertTrue(VenueTagScan.summary(null).isNotBlank())
    }

    /**
     * A bundled standard dictionary offers nothing — and the reason is not that it is standard, which is
     * no longer a filter anywhere else. It is extracted to a **temp file**, so a sidecar written beside it
     * would not survive a restart: an answer the author gives there is silently thrown away.
     */
    @Test
    fun `a bundled dictionary offers nothing, because a sidecar beside a temp file is lost`() {
        assertEquals(emptyList(), VenueTagScan.scan(FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)))
    }

    // ----- the write path -------------------------------------------------------------------------------

    @Test
    fun `roles round-trip through the sidecar`() {
        val xml = temp.newFile("roundtrip.xml")
        xml.writeText("<fix major=\"4\" minor=\"4\" type=\"FIX\"><fields/></fix>")

        val written =
            TagRoleOverlay.writeBeside(
                xml.absolutePath,
                mapOf(
                    20001 to setOf(TagRole.CLIENT_MINTED_ID),
                    117 to setOf(TagRole.CLIENT_MINTED_ID, TagRole.VENUE_MINTED_ID),
                ),
            )

        val read = TagRoleOverlay.read(written.readText())
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), read.rolesOf(20001))
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID, TagRole.VENUE_MINTED_ID), read.rolesOf(117))
    }

    /**
     * "Considered and decided it is nothing" and "never heard of it" produce identical behaviour, so the
     * file records one of them. A reader who sees `"20002": []` will believe the tool acts on it.
     */
    @Test
    fun `a tag with no roles is dropped rather than written empty`() {
        val xml = temp.newFile("empties.xml")
        xml.writeText("<fix major=\"4\" minor=\"4\" type=\"FIX\"><fields/></fix>")

        val written =
            TagRoleOverlay.writeBeside(
                xml.absolutePath,
                mapOf(20001 to setOf(TagRole.CLIENT_MINTED_ID), 20002 to emptySet()),
            )

        assertTrue("20002" !in written.readText(), written.readText())
        assertEquals(1, TagRoleOverlay.read(written.readText()).size)
    }

    private fun scan(rolesJson: String? = null) = VenueTagScan.scan(venueDictionary(rolesJson))
}
