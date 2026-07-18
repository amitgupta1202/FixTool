package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.FixDictionaryAdapter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor's indentation, pinned case by case — the safety net for converging every surface onto
 * one grouping algorithm.
 *
 * The editor derives its indents from [com.knapsack.fixtool.service.compare.GroupOverlay], the
 * dictionary-driven structure the reconcile diff bands. The state machine it replaced guessed each
 * group's shape from its first instance, and each test here that says "used to" names a way that
 * guess came out wrong on ordinary messages: a one-instance group is the common case in a
 * NewOrderSingle, an optional field present in only some instances is what optional means, and a
 * field named `Not…` is not a count.
 */
class MessageEditorIndentTest {
    private fun fields(vararg pairs: Pair<String, String>): List<FixField> = pairs.map { FixField(tag = it.first, value = it.second) }

    private fun indentsOf(dictionary: FixDictionaryAdapter, vararg pairs: Pair<String, String>): Pair<List<Int>, List<Int?>> =
        calculateIndentLevels(fields(*pairs), dictionary)

    /** A group ends where the dictionary says its fields end — a one-instance group included. */
    @Test
    fun `fields after a single-instance group are not part of it`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "453" to "1", "448" to "FIRMA", "447" to "D", "452" to "1",
                "55" to "EURUSD", "38" to "100", "10" to "123",
            )

        // The state machine this replaced never finished "learning" a one-instance group, so
        // Symbol and OrderQty rendered inside the party until the checksum reset it.
        assertEquals(listOf(0, 0, 1, 1, 1, 0, 0, 0), indents)
        assertEquals(listOf(null, null, 1, null, null, null, null, null), instances)
    }

    /** An optional field present in only one instance does not truncate the group. */
    @Test
    fun `instances of different shapes stay in the group`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "453" to "2",
                "448" to "FIRMA", "452" to "1",
                "448" to "FIRMB", "447" to "D", "452" to "4",
                "55" to "EURUSD", "10" to "123",
            )

        // Used to learn "an entry is 2 fields" from the first instance and evict 447 and 452 of
        // the second to top level. The delimiter says where entries start; shape is not consulted.
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1, 0, 0), indents)
        assertEquals(listOf(null, null, 1, null, 2, null, null, null, null), instances)
    }

    /** Nesting comes from the dictionary's own scoped group definitions, not from guessing. */
    @Test
    fun `a nested group indents one level deeper and numbers its own instances`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "453" to "2",
                "448" to "FIRMA", "452" to "1", "802" to "2", "523" to "S1", "523" to "S2",
                "448" to "FIRMB", "452" to "4",
                "55" to "EURUSD", "10" to "123",
            )

        // 802 (the nested count) sits at the party's level; each sub-id entry one deeper; the
        // second party returns to level 1; Symbol leaves the group entirely.
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 2, 1, 1, 0, 0), indents)
        assertEquals(listOf(null, null, 1, null, null, 1, 2, 2, null, null, null), instances)
    }

    /** Two groups side by side are siblings, whatever the first one's instance count. */
    @Test
    fun `a sibling group after a single-instance group is not nested under it`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "453" to "1", "448" to "FIRMA", "452" to "1",
                "78" to "2", "79" to "ACC1", "80" to "10", "79" to "ACC2", "80" to "20",
                "10" to "123",
            )

        // Used to leave the one-instance party open on the stack, so NoAllocs rendered inside it
        // one level too deep. Both groups are top-level; only their own entries indent.
        assertEquals(listOf(0, 0, 1, 1, 0, 1, 1, 1, 1, 0), indents)
        assertEquals(listOf(null, null, 1, null, null, 1, null, 2, null, null), instances)
    }

    /** A field whose name starts with "No" but whose type says otherwise is not a group. */
    @Test
    fun `NotAffectedOrderID with a numeric value opens no phantom group`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "1371" to "12345", "55" to "EURUSD", "38" to "100", "10" to "123",
            )

        // Used to read 1371=12345 as "a group of 12345 entries follows" and indent the rest of
        // the message under it. The STRING type in the dictionary vetoes the name.
        assertEquals(listOf(0, 0, 0, 0, 0), indents)
        assertEquals(listOf<Int?>(null, null, null, null, null), instances)
    }

    /** A group the dictionary has never heard of still brackets, from the period-detection guess. */
    @Test
    fun `an unknown repeating group falls back to the heuristic`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "9001" to "A", "9002" to "1", "9001" to "B", "9002" to "2", "9001" to "C", "9002" to "3",
                "10" to "123",
            )

        assertEquals(listOf(0, 1, 1, 1, 1, 1, 1, 0), indents)
        assertEquals(listOf(null, 1, null, 2, null, 3, null, null), instances)
    }

    /** An unknown tag appearing once is just a field — nothing repeats, nothing brackets. */
    @Test
    fun `an unknown single-instance group renders flat rather than guessing`() {
        val (indents, _) =
            indentsOf(
                dictionary(),
                "35" to "D", "9005" to "1", "9001" to "A", "9002" to "1", "55" to "EURUSD", "10" to "123",
            )

        assertEquals(listOf(0, 0, 0, 0, 0, 0), indents)
    }

    /** A row the author is mid-way through typing keeps its place instead of jumping to 0. */
    @Test
    fun `a blank row inherits the indent above it and does not split the entry`() {
        val (indents, instances) =
            indentsOf(
                dictionary(),
                "35" to "D", "453" to "2",
                "448" to "FIRMA", "" to "", "452" to "1",
                "448" to "FIRMB", "452" to "4",
                "10" to "123",
            )

        // The blank row shows at the party's level, and 452 after it is still FIRMA's role —
        // the walk never saw the blank, so the entry is whole.
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1, 0), indents)
        assertEquals(listOf(null, null, 1, null, null, 2, null, null), instances)
    }

    /**
     * A dictionary with the standard shapes the cases above need: nested parties, a sibling allocs
     * group, and `NotAffectedOrderID` — a real FIX 5.0 field whose name begins with "No" and whose
     * STRING type is what keeps it from being read as a count.
     */
    private fun dictionary(): FixDictionaryAdapter {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="NewOrderSingle" msgtype="D" msgcat="app">
                  <field name="Symbol" required="N"/>
                  <field name="OrderQty" required="N"/>
                  <field name="NotAffectedOrderID" required="N"/>
                  <group name="NoPartyIDs" required="N">
                    <field name="PartyID" required="N"/>
                    <field name="PartyIDSource" required="N"/>
                    <field name="PartyRole" required="N"/>
                    <group name="NoPartySubIDs" required="N">
                      <field name="PartySubID" required="N"/>
                    </group>
                  </group>
                  <group name="NoAllocs" required="N">
                    <field name="AllocAccount" required="N"/>
                    <field name="AllocQty" required="N"/>
                  </group>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="38" name="OrderQty" type="QTY"/>
                <field number="55" name="Symbol" type="STRING"/>
                <field number="78" name="NoAllocs" type="NUMINGROUP"/>
                <field number="79" name="AllocAccount" type="STRING"/>
                <field number="80" name="AllocQty" type="QTY"/>
                <field number="447" name="PartyIDSource" type="CHAR"/>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="523" name="PartySubID" type="STRING"/>
                <field number="802" name="NoPartySubIDs" type="NUMINGROUP"/>
                <field number="1371" name="NotAffectedOrderID" type="STRING"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-editor-indent", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }
}
