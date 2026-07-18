package com.knapsack.fixtool.ui

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.parseFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.compare.GroupOverlay
import com.knapsack.fixtool.service.groupCountSafe
import quickfix.Field
import quickfix.FieldMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The two derivations must agree.** This is what makes "one indentation algorithm" a claim the
 * build checks rather than a thing someone remembers.
 *
 * The app reads a message two ways, and both are legitimate. A surface holding a flat list of wire
 * rows — the editor, the reconcile diff — asks [GroupOverlay.depthAt], which counts the entries a
 * row falls inside. A surface holding `quickfix.Message`'s parsed tree — the detail pane, the
 * expanded grid — carries an accumulator down the `FieldMap`, deepening only when it descends into
 * a group *instance*, because a parsed tree hands it the nesting for free and flattening it back
 * out to re-derive what it already knows would be work for nothing.
 *
 * Two derivations, one answer. [treeDepths] below is the tree convention written out exactly as the
 * renderers apply it — group header at the current level, instances and their fields one deeper —
 * so if either side's rule is ever changed alone, these comparisons fail and say so.
 */
class IndentationEquivalenceTest {
    /** The flat surfaces' answer: tag occurrence in wire order, to the depth the overlay gives it. */
    private fun overlayDepths(raw: String, dictionary: FixDictionaryAdapter): List<Pair<Int, Int>> {
        val fields = parseFixMessage(raw).filter { it.first != 8 && it.first != 10 }
        val overlay =
            GroupOverlay.build(
                fields.map { it.first to it.second },
                fields.firstOrNull { it.first == 35 }?.second,
                dictionary,
            )
        return fields.indices.map { fields[it].first to overlay.depthAt(it) }
    }

    /**
     * The tree surfaces' answer, by the convention `renderFieldMap` and `collectFieldWidths` share:
     * a row is drawn at its own level, and only descending into a group instance deepens it.
     */
    private fun treeDepths(fieldMap: FieldMap, level: Int = 0): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val iterator = fieldMap.iterator()
        while (iterator.hasNext()) {
            val tag = (iterator.next() as Field<*>).tag
            out += tag to level
            val count = fieldMap.groupCountSafe(tag)
            for (i in 1..count) {
                out += treeDepths(fieldMap.getGroup(i, tag), level + 1)
            }
        }
        return out
    }

    private fun assertAgree(raw: String, dictionary: FixDictionaryAdapter) {
        val message = raw.toQuickFixMessageManual(dictionary)
        val fromTree = treeDepths(message).sortedBy { it.first }
        val fromOverlay = overlayDepths(raw, dictionary).filter { it.first != 35 }.sortedBy { it.first }

        assertTrue(fromOverlay.isNotEmpty(), "the fixture must actually produce rows, or this proves nothing")
        assertEquals(
            fromOverlay,
            fromTree,
            "the parsed tree and the wire overlay disagree about how deep these tags sit — " +
                "one derivation has been changed without the other",
        )
    }

    /** A group's count row sits with the fields around it; its entries sit one deeper. */
    @Test
    fun `a flat group indents the same both ways`() {
        assertAgree("8=FIX.4.4|35=D|453=2|448=FIRMA|452=1|448=FIRMB|452=4|10=000|", dictionary())
    }

    /** A nested group's count row lines up with its parent's fields, and its own entries one deeper again. */
    @Test
    fun `a nested group indents the same both ways`() {
        assertAgree(
            "8=FIX.4.4|35=D|453=2|448=FIRMA|452=1|802=2|523=S1|523=S2|448=FIRMB|452=4|10=000|",
            dictionary(),
        )
    }

    /** Two groups side by side are both top-level, whichever derivation is asked. */
    @Test
    fun `sibling groups indent the same both ways`() {
        assertAgree(
            "8=FIX.4.4|35=D|453=1|448=FIRMA|452=1|78=2|79=ACC1|80=10|79=ACC2|80=20|10=000|",
            dictionary(),
        )
    }

    /** A one-instance group is the case the editor's old state machine got wrong; both agree now. */
    @Test
    fun `a single-instance group followed by body fields indents the same both ways`() {
        assertAgree("8=FIX.4.4|35=D|453=1|448=FIRMA|452=1|55=EURUSD|38=100|10=000|", dictionary())
    }

    /** And the shared step arithmetic turns those depths into the edges each surface draws. */
    @Test
    fun `FixIndent steps from the base by the surface's own step`() {
        assertEquals(8, FixIndent.startValue(0, FixIndent.DETAIL_STEP, FixIndent.DETAIL_BASE))
        assertEquals(16, FixIndent.startValue(1, FixIndent.DETAIL_STEP, FixIndent.DETAIL_BASE))
        assertEquals(24, FixIndent.startValue(2, FixIndent.DETAIL_STEP, FixIndent.DETAIL_BASE))

        assertEquals(0, FixIndent.startValue(0, FixIndent.DIFF_STEP))
        assertEquals(20, FixIndent.startValue(2, FixIndent.DIFF_STEP))

        assertEquals(32, FixIndent.startValue(2, FixIndent.GRID_STEP))
        assertEquals(FixIndent.GRID_STEP, EXPANDED_GRID_INDENT_STEP, "the grid's alias must not drift from the shared value")
    }

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
                  <group name="NoPartyIDs" required="N">
                    <field name="PartyID" required="N"/>
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
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="523" name="PartySubID" type="STRING"/>
                <field number="802" name="NoPartySubIDs" type="NUMINGROUP"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-equivalence", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }
}
