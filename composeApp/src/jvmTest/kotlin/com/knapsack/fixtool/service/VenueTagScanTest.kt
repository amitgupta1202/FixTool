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
    fun `the venue's own tags are offered, standard FIX is not`() {
        val scanned = scan()

        assertTrue(20001 in tags(scanned), "the venue's leg id must be offered")
        assertTrue(20071 in tags(scanned))
        // Standard FIX 4.4 is FixTool's own business — QuoteReqID(131) is already a correlation id, and
        // BeginString(8) is transport. Neither is a question for the author.
        assertTrue(131 !in tags(scanned))
        assertTrue(8 !in tags(scanned))
    }

    /**
     * The highest-cost false positive there is: `SecurityID` echoes on every flow and its name ends in
     * `ID`, and minting a fresh value for it sends the venue an instrument that does not exist.
     */
    @Test
    fun `reference data is never offered as a correlation id`() {
        assertTrue(48 !in tags(scan()))
    }

    @Test
    fun `id-shaped names sort above the rest, and nothing is hidden`() {
        val scanned = scan()

        val identifiers = scanned.filter { it.tier == VenueTagScan.Tier.IDENTIFIER }.map { it.tag }
        assertEquals(listOf(20001, 20071), identifiers)
        // …and the venue's other additions are still in the list, below them — a tier is an ordering, not
        // a filter, because a correlation id whose name does not end in ID must stay reachable.
        val others = scanned.filter { it.tier == VenueTagScan.Tier.OTHER }.map { it.tag }
        assertTrue(others.containsAll(listOf(20044, 20050, 20063)), others.toString())
        assertTrue(scanned.indexOfFirst { it.tag == 20001 } < scanned.indexOfFirst { it.tag == 20050 })
    }

    @Test
    fun `a declared tag sorts first and carries its role, so a wrong call can be revised`() {
        val scanned = scan("""{"20050":"CLIENT_MINTED_ID"}""")

        assertEquals(20050, scanned.first().tag)
        assertEquals(VenueTagScan.Tier.DECLARED, scanned.first().tier)
        assertEquals(setOf(TagRole.CLIENT_MINTED_ID), scanned.first().roles)
    }

    @Test
    fun `no dictionary is an empty list, not a crash`() {
        assertEquals(emptyList(), VenueTagScan.scan(null))
        assertTrue(VenueTagScan.summary(null).isNotBlank())
    }

    /** A bundled standard dictionary adds nothing of its own, so there is nothing to ask about. */
    @Test
    fun `a standard dictionary offers nothing`() {
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
