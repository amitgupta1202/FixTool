package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A repeating group the dictionary does not define must survive the manual parse whole.
 *
 * It used to collapse: `processFields` set each instance's tags flat onto the message body, a
 * FieldMap keeps one value per tag, and an N-instance group arrived as its last instance with no
 * diagnostic — in the detail pane, and worse, on the wire, because the send path constructs its
 * outgoing message through this same parser. These tests pin the rescue and its boundaries.
 */
class ManualParseGroupSalvageTest {
    /** The venue's custom counted group arrives as N instances, and leaves as N instances. */
    @Test
    fun `an undefined counted group keeps every instance`() {
        val raw = "8=FIX.4.4|35=8|9005=2|9001=FIRMA|9002=1|9001=FIRMB|9002=4|55=EURUSD|10=000|"

        val msg = raw.toQuickFixMessageManual(dictionary())

        assertEquals(2, msg.getGroupCount(9005), "both instances of the undefined group survive")
        val first = msg.getGroups(9005)[0]
        val second = msg.getGroups(9005)[1]
        assertEquals("FIRMA", first.getString(9001), "the first instance used to be silently overwritten")
        assertEquals("1", first.getString(9002))
        assertEquals("FIRMB", second.getString(9001))
        assertEquals("4", second.getString(9002))
        assertEquals("EURUSD", msg.getString(55), "the field after the group stays at top level")
    }

    /** The wire's count claim survives even when it disagrees with the entries found. */
    @Test
    fun `the count the venue sent wins over the count of entries detected`() {
        val raw = "8=FIX.4.4|35=8|9005=3|9001=FIRMA|9002=1|9001=FIRMB|9002=4|10=000|"

        val msg = raw.toQuickFixMessageManual(dictionary())

        assertEquals(2, msg.getGroupCount(9005), "two entries were on the wire")
        assertEquals("3", msg.getString(9005), "and the venue's claim of three is what renders — the mismatch is the finding")
    }

    /** A group the dictionary does define parses exactly as before — the rescue never touches it. */
    @Test
    fun `a dictionary-defined group is untouched by the salvage`() {
        val raw = "8=FIX.4.4|35=8|453=2|448=FIRMA|452=1|448=FIRMB|452=4|55=EURUSD|10=000|"

        val msg = raw.toQuickFixMessageManual(dictionary())

        assertEquals(2, msg.getGroupCount(453))
        assertEquals("FIRMA", msg.getGroups(453)[0].getString(448))
        assertEquals("FIRMB", msg.getGroups(453)[1].getString(448))
        assertEquals("EURUSD", msg.getString(55))
    }

    /**
     * A repeated tag with no count row above it stays lossy — pinned, not fixed. `addGroup` files a
     * group under its count tag; a repeat without one has no tag to file under, and inventing a
     * count field would put a field on the message the venue never sent. This is also the shape the
     * FIX spec does not permit, so honesty about the loss beats a fabricated structure.
     */
    @Test
    fun `an uncounted repeat still collapses to its last value`() {
        val raw = "8=FIX.4.4|35=8|9001=FIRMA|9002=1|9001=FIRMB|9002=4|10=000|"

        val msg = raw.toQuickFixMessageManual(dictionary())

        assertEquals("FIRMB", msg.getString(9001))
        assertEquals("4", msg.getString(9002))
    }

    /**
     * A dictionary that defines `NoPartyIDs` but has never heard of the venue's `9005` group —
     * except as a NUMINGROUP-typed field, which is exactly how a custom count field reaches a real
     * dictionary's `<fields>` section before anyone writes the group definition.
     */
    private fun dictionary(): FixDictionaryAdapter {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="ExecutionReport" msgtype="8" msgcat="app">
                  <field name="Symbol" required="N"/>
                  <group name="NoPartyIDs" required="N">
                    <field name="PartyID" required="N"/>
                    <field name="PartyRole" required="N"/>
                  </group>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="55" name="Symbol" type="STRING"/>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="9005" name="NoVenueParties" type="NUMINGROUP"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-salvage", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }
}
