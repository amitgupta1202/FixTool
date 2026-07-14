package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.service.ScenarioReconcile
import com.knapsack.fixtool.service.wireView
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Where an entry begins** — asked of the dictionary, which knows, instead of guessed from the row order,
 * which mostly works.
 *
 * The delimiter field is the first field of every entry by definition of the FIX spec, so the boundaries
 * are not an inference at all: an entry starts at each occurrence of it, and nowhere else. That closes the
 * rotation trap by construction and — the case that actually bites daily — it brackets a group whose
 * entries are *shaped differently*, which period-detection cannot see at all.
 */
class GroupOverlayTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun message(vararg fields: Pair<Int, String>) = wireView(fields.toList())

    private fun party(id: String, role: String) =
        listOf(448 to id, 447 to "D", 452 to role)

    /** Three identical parties — the shape that fooled the window rule. */
    private fun threeParties() =
        message(
            35 to "8",
            453 to "3",
            *party("FIRMA", "1").toTypedArray(),
            *party("FIRMB", "4").toTypedArray(),
            *party("FIRMC", "7").toTypedArray(),
        )

    // ----- boundaries -----------------------------------------------------------------------------------

    /**
     * THE ROTATION TRAP, CLOSED BY CONSTRUCTION. In a three-party group the window `447,452,448` carries the
     * same tags in the same order as its neighbour, and it is not a party — it is the tail of one entry
     * welded to the head of the next. Swapping two of those tears every party apart and re-stitches it to a
     * stranger's role, from one click, with no value edited and the tag order byte-identical afterwards.
     *
     * An entry begins at the delimiter. A window that begins one row late is not an entry, and the overlay
     * cannot name it as one, whatever its tags look like.
     */
    @Test
    fun `an entry begins at the delimiter, so no entry can begin mid-entry`() {
        val overlay = GroupOverlay.of(threeParties(), dictionary)

        val parties = overlay.groups.single()
        assertEquals(453, parties.groupTag)
        assertEquals("NoPartyIDs", parties.name)
        assertEquals(1, parties.countRow, "the count is the group's own row, and belongs to no entry")
        assertEquals(listOf(2..4, 5..7, 8..10), parties.entries.map { it.rows })
        assertTrue(
            parties.entries.all { it.source == EntrySource.DICTIONARY },
            "these boundaries are known, not guessed",
        )
        // The rotation windows are 3..5 and 6..8. Nothing in the overlay is one of them.
        assertTrue(overlay.entries.none { it.rows == 3..5 || it.rows == 6..8 })
    }

    /**
     * **The case period-detection cannot see at all**, and the reason this is not merely tidier.
     *
     * `PartyIDSource(447)` is optional. A venue that sends it for one party and not the other produces
     * entries of *different shapes* — and a rule that looks for a repeating run of tags finds no repetition
     * to work with, so it brackets nothing: no bands, no entry drag, no entry arrows, on the message that
     * needs them just as much as any other. The dictionary does not care what shape an entry is; it knows
     * where the next one starts.
     */
    @Test
    fun `a group whose entries are shaped differently is still bracketed`() {
        val heterogeneous = message(35 to "8", 453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 452 to "4")

        val overlay = GroupOverlay.of(heterogeneous, dictionary)

        assertEquals(listOf(2..4, 5..6), overlay.entries.map { it.rows }, "two entries, one of them shorter")
        // ...and here is what the old answer was, on the same fields.
        val guessed = ScenarioReconcile.entryRegions(listOf(448, 447, 452, 448, 452))
        assertTrue(guessed.isEmpty(), "period-detection finds no entries here at all — which is why it is the fallback")
    }

    /**
     * A group's dictionary is scoped to its own fields, so the walk has to **descend** rather than scan
     * flat: `PartySubID(523)` is defined inside `NoPartySubIDs(802)` and not in the group above it, and a
     * flat scan would stop dead at 802 and read the rest of the message as top-level fields — which ends
     * the first party at its third row and leaves the second party unbracketed entirely.
     *
     * Against a **purpose-built dictionary**, because the one FixTool ships is a 537-line cut-down FIX44
     * with no nested group anywhere in it — so every bundled fixture would pass this test by never
     * reaching the code it is about. That is the "fixture dodged the hard case" pattern exactly, and a
     * venue's real dictionary (this tool runs against ten of them) nests groups as a matter of course.
     */
    @Test
    fun `a nested group is bracketed inside the entry that contains it`() {
        val nesting = dictionaryWithNestedParties()
        val nested =
            message(
                35 to "8",
                453 to "2",
                448 to "FIRMA",
                447 to "D",
                452 to "1",
                802 to "1",
                523 to "SUB-1",
                803 to "2",
                448 to "FIRMB",
                447 to "D",
                452 to "4",
            )

        val overlay = GroupOverlay.of(nested, nesting)

        val parties = overlay.groups.single()
        assertEquals(
            listOf(2..7, 8..10),
            parties.entries.map { it.rows },
            "the first party owns its sub-ids: a nested group does not end the entry that contains it",
        )
        val firstParty = parties.entries.first()
        val subs = firstParty.children.single()
        assertEquals(802, subs.groupTag)
        assertEquals(5, subs.countRow)
        assertEquals(listOf(6..7), subs.entries.map { it.rows })
        assertEquals(EntrySource.DICTIONARY, subs.entries.single().source)
        // ...and a row inside the nested entry belongs to the innermost entry that holds it.
        assertEquals(6..7, overlay.entryAt(6)!!.rows)
        assertEquals(2..7, overlay.entryAt(3)!!.rows)
        // ...while the sibling list a move is bounded by is the one the entry is actually in.
        assertEquals(1, overlay.siblingsOf(subs.entries.single()).size, "the sub-id entry has no sibling to swap with")
        assertEquals(2, overlay.siblingsOf(firstParty).size)
    }

    /** A dictionary that nests `NoPartySubIDs` inside `NoPartyIDs` — what a venue's real one does. */
    private fun dictionaryWithNestedParties(): FixDictionaryAdapter {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="ExecutionReport" msgtype="8" msgcat="app">
                  <group name="NoPartyIDs" required="N">
                    <field name="PartyID" required="N"/>
                    <field name="PartyIDSource" required="N"/>
                    <field name="PartyRole" required="N"/>
                    <group name="NoPartySubIDs" required="N">
                      <field name="PartySubID" required="N"/>
                      <field name="PartySubIDType" required="N"/>
                    </group>
                  </group>
                  <field name="Text" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="58" name="Text" type="STRING"/>
                <field number="447" name="PartyIDSource" type="CHAR">
                  <value enum="D" description="PROPRIETARY_CUSTOM_CODE"/>
                </field>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT">
                  <value enum="1" description="EXECUTING_FIRM"/>
                  <value enum="4" description="CLEARING_FIRM"/>
                </field>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="523" name="PartySubID" type="STRING"/>
                <field number="802" name="NoPartySubIDs" type="NUMINGROUP"/>
                <field number="803" name="PartySubIDType" type="INT">
                  <value enum="2" description="PERSON"/>
                </field>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-nested", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }

    /** A field outside every group belongs to no entry, and a move of it is bounded by nothing. */
    @Test
    fun `a top-level field is in no entry`() {
        val overlay = GroupOverlay.of(threeParties(), dictionary)

        assertNull(overlay.entryAt(0), "MsgType is not in a party")
        assertNull(overlay.entryAt(1), "and neither is the count")
    }

    // ----- the fallback ---------------------------------------------------------------------------------

    /**
     * A venue's custom group — anything a counterparty invented that this dictionary predates — is still a
     * repeating group to the author looking at it. Refusing to bracket it would withdraw the entry drag from
     * exactly the venues that need it most, so the old period-detection stays, as the fallback, and says so.
     */
    @Test
    fun `a group the dictionary has never heard of falls back to the guess, and admits it`() {
        val custom = message(35 to "8", 9001 to "A", 9002 to "1", 9001 to "B", 9002 to "2", 9001 to "C", 9002 to "3")

        val overlay = GroupOverlay.of(custom, dictionary)

        val guessed = overlay.groups.single()
        assertEquals(EntrySource.HEURISTIC, guessed.source, "the UI has to be able to badge this as a guess")
        assertEquals(listOf(1..2, 3..4, 5..6), guessed.entries.map { it.rows })
    }

    /** The two sources do not fight: a group the dictionary defines is not re-bracketed by the guess. */
    @Test
    fun `the guess only speaks where the dictionary is silent`() {
        val both =
            message(
                35 to "8",
                453 to "2",
                448 to "FIRMA",
                447 to "D",
                452 to "1",
                448 to "FIRMB",
                447 to "D",
                452 to "4",
                9001 to "A",
                9002 to "1",
                9001 to "B",
                9002 to "2",
            )

        val overlay = GroupOverlay.of(both, dictionary)

        assertEquals(
            listOf(EntrySource.DICTIONARY, EntrySource.HEURISTIC),
            overlay.groups.map { it.source },
        )
        assertEquals(listOf(2..4, 5..7), overlay.groups[0].entries.map { it.rows })
        assertEquals(listOf(8..9, 10..11), overlay.groups[1].entries.map { it.rows })
    }

    // ----- one overlay, both sides ----------------------------------------------------------------------

    /**
     * **The two sides can never disagree about where an entry starts.** That disagreement — one renderer
     * answering from one structure walk and the other from a different one — is the seam that produced the
     * defect the whole sequence model was built to end. The boundaries are a function of the tag sequence
     * and the dictionary, and a value reaches nothing but the label.
     */
    @Test
    fun `the expectation's entries and the message's entries are the same entries`() {
        val fields = listOf(35 to "8", 453 to "2", 448 to "FIRMA", 447 to "D", 452 to "1", 448 to "FIRMB", 447 to "D", 452 to "4")
        val expectation =
            Expectation(
                fields =
                    fields.map { (tag, value) ->
                        // Deliberately NOT all `exact`: a presence row knows its tag and not its value, and the
                        // boundaries must not care.
                        if (tag == 447) FieldExpectation(tag, Matcher.Presence) else FieldExpectation(tag, Matcher.Exact(value))
                    },
                messageType = "8",
            )

        val fromMessage = GroupOverlay.of(wireView(fields), dictionary)
        val fromExpectation = GroupOverlay.of(expectation, dictionary)

        assertEquals(fromMessage.entries.map { it.rows }, fromExpectation.entries.map { it.rows })
    }

    // ----- labels ---------------------------------------------------------------------------------------

    /** `FIRMA · 1 ExecutingFirm` — computed here, so the two sides cannot label the same entry differently. */
    @Test
    fun `an entry is labelled by its delimiter and the best identity the dictionary can add`() {
        val overlay = GroupOverlay.of(threeParties(), dictionary)

        assertEquals("FIRMA · 1 ExecutingFirm", overlay.entries[0].label)
        assertEquals("FIRMB · 4 ClearingFirm", overlay.entries[1].label)
    }

    /** A row that does not carry a value cannot name its entry, and a made-up label would be worse. */
    @Test
    fun `an entry whose delimiter has no value is labelled by what is left`() {
        val expectation =
            Expectation(
                fields =
                    listOf(
                        FieldExpectation(453, Matcher.Exact("1")),
                        FieldExpectation(448, Matcher.Presence),
                        FieldExpectation(452, Matcher.Exact("1")),
                    ),
                messageType = "8",
            )

        val overlay = GroupOverlay.of(expectation, dictionary)

        assertEquals("1 ExecutingFirm", overlay.entries.single().label)
    }
}
