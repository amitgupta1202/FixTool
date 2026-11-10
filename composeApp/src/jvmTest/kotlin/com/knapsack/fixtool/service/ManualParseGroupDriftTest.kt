package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.NestedGroupFixtures.Fixture
import com.knapsack.fixtool.service.compare.GroupOverlay
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A venue's repeating groups survive the manual parse whole**, when its encoder leaves out an entry's
 * leading fields and when it runs ahead of the loaded dictionary.
 *
 * The parse builds the tree the grid and the detail pane draw, and the send path serialises. It used to end an
 * entry, and its group with it, at the first tag the group's dictionary does not define. Inside a nested
 * group that was the worst place to stop: the count was padded with empty entries, the enclosing entry saw
 * a repeat and opened a phantom entry, and the rest of the message was set flat on the body, where each
 * repeat overwrote the last. Three allocations in a side, each carrying a tag the dictionary defines only
 * for the side, came out as a phantom second side, two empty allocations and five fields lost.
 *
 * Every case checks the whole tree against the shape it must have, and that every field on the wire is in
 * the tree exactly once. See [NestedGroupFixtures] for the messages and the notation.
 */
class ManualParseGroupDriftTest {
    private fun parse(fixture: Fixture) = fixture.wire.toQuickFixMessageManual(NestedGroupFixtures.dictionary)

    private fun assertReadsAsItShould(fixture: Fixture) {
        val message = parse(fixture)

        assertEquals(
            NestedGroupFixtures.canonical(fixture.shape),
            NestedGroupFixtures.shapeOf(message),
            "${fixture.name}: the parsed tree",
        )
        assertEquals(
            NestedGroupFixtures.occurrencesOn(fixture.wire),
            NestedGroupFixtures.occurrencesIn(message),
            "${fixture.name}: every field on the wire is in the tree exactly once, none lost and none invented",
        )
    }

    @Test
    fun `an entry that does not open with its delimiter keeps its nested group`() {
        assertReadsAsItShould(NestedGroupFixtures.entryWithoutItsDelimiter)
    }

    @Test
    fun `parties without their delimiter in two sides read as two sides`() {
        assertReadsAsItShould(NestedGroupFixtures.cascade)
    }

    /** The real break: allocations carrying a tag the side defines and one nothing defines. */
    @Test
    fun `allocations carrying tags the dictionary lacks there stay in their side`() {
        val fixture = NestedGroupFixtures.allocationsAheadOfTheDictionary
        assertReadsAsItShould(fixture)

        val message = parse(fixture)
        val side = message.getGroups(552).single()
        val allocations = side.getGroups(78)
        assertEquals(listOf("A1", "A2", "A3"), allocations.map { it.getString(79) }, "three allocations, none empty")
        assertEquals(listOf("EUR", "USD"), allocations.take(2).map { it.getString(15) })
        assertFalse(allocations[2].isSetField(15), "the third allocation carried no currency")
        assertEquals("EUR", side.getString(15), "the side keeps its own currency")
        assertEquals("ORD1", side.getString(37), "and the fields after its allocations")
        assertFalse(message.isSetField(79), "no allocation field is set flat on the body")
        assertEquals("600", message.getString(32))
    }

    @Test
    fun `an unknown tag in the only entry of a nested group leaves the enclosing entry going on`() {
        val fixture = NestedGroupFixtures.unknownTagInAnOnlyEntry
        assertReadsAsItShould(fixture)

        val sides = parse(fixture).getGroups(552)
        assertEquals(listOf("ORD1", "ORD2"), sides.map { it.getString(37) })
        assertEquals(listOf("1", "2"), sides.map { it.getGroups(78).single().getString(9110) })
    }

    @Test
    fun `a tag the enclosing entry already holds stays in the nested entry when no next entry follows`() {
        val fixture = NestedGroupFixtures.tagTheEnclosingEntryHolds
        assertReadsAsItShould(fixture)

        val side = parse(fixture).getGroups(552).single()
        assertEquals("EUR", side.getGroups(78).single().getString(15))
        assertEquals("ORD1", side.getString(37), "no phantom second side")
    }

    /** The guard: the count the venue overstated stays visible, and the next side is not swallowed. */
    @Test
    fun `an overstated nested count pads its own group and nothing else`() {
        val fixture = NestedGroupFixtures.overstatedNestedCount
        assertReadsAsItShould(fixture)

        val (first, second) = parse(fixture).getGroups(552)
        assertEquals("2", first.getString(78), "the claim of two is what renders")
        assertEquals(2, first.getGroups(78).size)
        assertTrue(first.getGroups(78)[1].isEmpty, "the second allocation is the one documented pad")
        assertEquals("ORD1", first.getString(37))
        assertEquals("A2", second.getGroups(78).single().getString(79))
    }

    @Test
    fun `a repeated field after a nested group opens the next entry of the enclosing group`() {
        val fixture = NestedGroupFixtures.nextEntryWithoutItsDelimiter
        assertReadsAsItShould(fixture)

        val underlyings = parse(fixture).getGroups(711)
        assertEquals(listOf("XS1", "XS2"), underlyings.map { it.getString(309) })
        assertEquals(1, underlyings[0].getGroups(457).size)
    }

    /**
     * The guard on an unknown tag: one that looks like a count starts a venue's own group, so it is not kept
     * in the allocation it follows, even with a field of the side ahead. Kept, its entries would have read as
     * repeats in the allocation and opened phantom allocations, and the salvage would have had nothing left
     * to rescue.
     */
    @Test
    fun `a venue's own counted group after an allocation is left for the salvage to rescue`() {
        val wire =
            "8=FIX.4.4|35=AE|571=G1|552=1|54=1|78=1|79=A1|80=100|" +
                "9005=2|9001=X|9002=1|9001=Y|9002=2|37=ORD1|10=000|"
        val message = wire.toQuickFixMessageManual(NestedGroupFixtures.dictionary)

        assertEquals(
            NestedGroupFixtures.canonical(
                "571=G1 552=1 552{[54=1 78=1 78{[79=A1 80=100]}]} 9005=2 9005{[9001=X 9002=1][9001=Y 9002=2]} 37=ORD1",
            ),
            NestedGroupFixtures.shapeOf(message),
        )
        assertOverlayDepths(wire, NestedGroupFixtures.dictionary, listOf(0, 0, 0, 1, 1, 2, 2, 0, 1, 1, 1, 1, 0, 0))
    }

    /**
     * The guard on a later delimiter: it proves the group goes on only when nothing outside the group could
     * hold it. Here the parties' first field is also a field of the message, so the `58` after `55` is the
     * message's, and the unknown tag before it ends the party rather than starting a phantom second one.
     */
    @Test
    fun `a later delimiter that is also a field of the message keeps no foreign tag in the entry`() {
        val wire = "8=FIX.4.4|35=8|453=1|58=a|448=X|9110=1|55=EUR|58=late|10=000|"
        val dictionary = dictionaryWithTextFirstInParties()
        val message = wire.toQuickFixMessageManual(dictionary)

        assertEquals(
            NestedGroupFixtures.canonical("453=1 453{[58=a 448=X]} 9110=1 55=EUR 58=late"),
            NestedGroupFixtures.shapeOf(message),
        )
        assertOverlayDepths(wire, dictionary, listOf(0, 0, 1, 1, 0, 0, 0, 0))
    }

    /** The editor's indent for every row of [wire] but BeginString, from the overlay over the same rows. */
    private fun assertOverlayDepths(
        wire: String,
        dictionary: FixDictionaryAdapter,
        depths: List<Int>,
    ) {
        val rows = FixMessageHelper.parseFixMessage(wire).filter { it.first != 8 }
        val overlay = GroupOverlay.build(rows, rows.first { it.first == 35 }.second, dictionary)
        assertEquals(depths, rows.indices.map { overlay.depthAt(it) }, "the overlay brackets as the parse reads")
    }

    /** `NoPartyIDs` delimited by `Text(58)`, which the message also carries at its top level. */
    private fun dictionaryWithTextFirstInParties(): FixDictionaryAdapter {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="ExecutionReport" msgtype="8" msgcat="app">
                  <group name="NoPartyIDs" required="N">
                    <field name="Text" required="N"/>
                    <field name="PartyID" required="N"/>
                  </group>
                  <field name="Symbol" required="N"/>
                  <field name="Text" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="55" name="Symbol" type="STRING"/>
                <field number="58" name="Text" type="STRING"/>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-text-first", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }
}
