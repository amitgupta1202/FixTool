package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.compare.EntrySource
import com.knapsack.fixtool.service.compare.GroupNode
import com.knapsack.fixtool.service.compare.GroupOverlay
import quickfix.Field
import quickfix.FieldMap
import quickfix.Message
import java.io.File

/**
 * **Venue-shaped repeating groups**, shared by every test that reads a message's group structure.
 *
 * Two kinds of wire that conformant fixtures never reach. A venue's encoder omits optional leading fields, so
 * an entry need not open with its group's delimiter (an underlying sent as `309,305` when the dictionary
 * starts the group with `311`). And a venue runs ahead of the loaded dictionary, so an entry can carry a tag
 * the dictionary does not define for that group (`Currency(15)` in an allocation, or `9110`, which the
 * dictionary has never heard of).
 *
 * Each [Fixture] states the structure both readings of a message must reach: the parse that builds the
 * `quickfix.Message` tree, and the overlay that indents the editor and bands the diff.
 */
internal object NestedGroupFixtures {
    /**
     * One message, and what it must read as.
     *
     * [shape] is the body in a small notation: `tag=value` is a field, `tag{[..][..]}` is a group with one
     * bracket per entry, and a group's count field is written beside it. Order inside an entry does not
     * matter (see [canonical]), the order of the entries does. [depths] is the indent of each row of
     * [editorRows], and [instances] the instance number the editor shows, by row, on each row that opens an
     * entry. Every other row shows none.
     */
    data class Fixture(
        val name: String,
        val wire: String,
        val shape: String,
        val depths: List<Int>,
        val instances: Map<Int, Int>? = null,
    ) {
        /** The rows an author sees in the editor: the wire less BeginString, which the editor never holds. */
        val editorRows: List<Pair<Int, String>> get() =
            FixMessageHelper.parseFixMessage(wire).filter { it.first != 8 }

        override fun toString(): String = name
    }

    /** An underlying that does not open with its delimiter, with a group nested in it. */
    val entryWithoutItsDelimiter =
        Fixture(
            name = "T1 an entry that does not open with its delimiter",
            wire =
                "8=FIX.4.4|35=AE|571=T1|711=1|309=XS0001|305=4|457=1|458=ALT1|459=8|810=99.5|75=20260101|" +
                    "552=1|54=1|453=1|448=P1|447=D|452=1|802=1|523=S1|803=1|1=ACC|10=000|",
            shape =
                "571=T1 711=1 711{[309=XS0001 305=4 457=1 457{[458=ALT1 459=8]} 810=99.5]} 75=20260101 " +
                    "552=1 552{[54=1 453=1 453{[448=P1 447=D 452=1 802=1 802{[523=S1 803=1]}]} 1=ACC]}",
            depths = listOf(0, 0, 0, 1, 1, 1, 2, 2, 1, 0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 1, 0),
            instances = mapOf(3 to 1, 6 to 1, 11 to 1, 13 to 1, 17 to 1),
        )

    /** Parties without their delimiter, in two sides: the shape that used to flatten the rest of the message. */
    val cascade =
        Fixture(
            name = "T2 the cascade",
            wire =
                "8=FIX.4.4|35=AE|571=T2|552=2|54=1|453=1|447=D|452=1|802=1|523=S1|803=1|1=ACC1|" +
                    "54=2|453=1|447=D|452=3|1=ACC2|32=5|10=000|",
            shape =
                "571=T2 552=2 552{[54=1 453=1 453{[447=D 452=1 802=1 802{[523=S1 803=1]}]} 1=ACC1]" +
                    "[54=2 453=1 453{[447=D 452=3]} 1=ACC2]} 32=5",
            depths = listOf(0, 0, 0, 1, 1, 2, 2, 2, 3, 3, 1, 1, 1, 2, 2, 1, 0, 0),
            instances = mapOf(3 to 1, 5 to 1, 8 to 1, 11 to 2, 13 to 1),
        )

    /**
     * Allocations carrying two tags the dictionary does not define there: `15`, which the side defines, and
     * `9110`, which nothing defines. The later delimiter keeps the first of each in, and the second entry
     * then knows them.
     */
    val allocationsAheadOfTheDictionary =
        Fixture(
            name = "T3 allocations ahead of the dictionary",
            wire =
                "8=FIX.4.4|35=AE|571=T3|552=1|54=1|453=1|448=P1|447=D|452=1|1=ACC|15=EUR|78=3|" +
                    "79=A1|80=100|15=EUR|9110=1|79=A2|80=200|15=USD|9110=1|79=A3|80=300|9110=2|" +
                    "37=ORD1|11=CL1|32=600|10=000|",
            shape =
                "571=T3 552=1 552{[54=1 453=1 453{[448=P1 447=D 452=1]} 1=ACC 15=EUR 78=3 " +
                    "78{[79=A1 80=100 15=EUR 9110=1][79=A2 80=200 15=USD 9110=1][79=A3 80=300 9110=2]} " +
                    "37=ORD1 11=CL1]} 32=600",
            depths = listOf(0, 0, 0, 1, 1, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 0, 0),
            instances = mapOf(3 to 1, 5 to 1, 11 to 1, 15 to 2, 19 to 3),
        )

    /** An unknown tag in the only allocation of each side, followed by a field of the side. */
    val unknownTagInAnOnlyEntry =
        Fixture(
            name = "T4 an unknown tag in the only entry of a nested group",
            wire =
                "8=FIX.4.4|35=AE|571=T4|552=2|54=1|78=1|79=A1|80=100|9110=1|37=ORD1|" +
                    "54=2|78=1|79=A2|80=200|9110=2|37=ORD2|32=300|10=000|",
            shape =
                "571=T4 552=2 552{[54=1 78=1 78{[79=A1 80=100 9110=1]} 37=ORD1]" +
                    "[54=2 78=1 78{[79=A2 80=200 9110=2]} 37=ORD2]} 32=300",
            depths = listOf(0, 0, 0, 1, 1, 2, 2, 2, 1, 1, 1, 2, 2, 2, 1, 0, 0),
        )

    /** A currency in the allocation that its side already holds, with no further side ahead. */
    val tagTheEnclosingEntryHolds =
        Fixture(
            name = "T5 a tag the enclosing entry already holds",
            wire = "8=FIX.4.4|35=AE|571=T5|552=1|54=1|1=ACC|15=EUR|78=1|79=A1|80=100|15=EUR|37=ORD1|32=100|10=000|",
            shape = "571=T5 552=1 552{[54=1 1=ACC 15=EUR 78=1 78{[79=A1 80=100 15=EUR]} 37=ORD1]} 32=100",
            depths = listOf(0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 1, 0, 0),
        )

    /**
     * The guard: an overstated allocation count does not swallow the next side. The parse keeps the claim of
     * two by padding one empty allocation, which is the only `[]` any fixture expects.
     */
    val overstatedNestedCount =
        Fixture(
            name = "T7 an overstated nested count",
            wire = "8=FIX.4.4|35=AE|571=T7|552=2|54=1|78=2|79=A1|80=100|37=ORD1|54=2|78=1|79=A2|80=200|37=ORD2|10=000|",
            shape =
                "571=T7 552=2 552{[54=1 78=2 78{[79=A1 80=100][]} 37=ORD1]" +
                    "[54=2 78=1 78{[79=A2 80=200]} 37=ORD2]}",
            depths = listOf(0, 0, 0, 1, 1, 2, 2, 1, 1, 1, 2, 2, 1, 0),
        )

    /**
     * The guard for the enclosing entry: a repeat of `309` after a nested group opens the next underlying,
     * because the first one never saw its delimiter and so cannot claim to be the only one.
     */
    val nextEntryWithoutItsDelimiter =
        Fixture(
            name = "T8 the next entry opens without its delimiter after a nested group",
            wire = "8=FIX.4.4|35=AE|571=T8|711=2|309=XS1|305=4|457=1|458=ALT1|459=8|309=XS2|305=4|75=20260101|10=000|",
            shape = "571=T8 711=2 711{[309=XS1 305=4 457=1 457{[458=ALT1 459=8]}][309=XS2 305=4]} 75=20260101",
            depths = listOf(0, 0, 0, 1, 1, 1, 2, 2, 1, 1, 0, 0),
            instances = mapOf(3 to 1, 6 to 1, 8 to 2),
        )

    val all: List<Fixture> =
        listOf(
            entryWithoutItsDelimiter,
            cascade,
            allocationsAheadOfTheDictionary,
            unknownTagInAnOnlyEntry,
            tagTheEnclosingEntryHolds,
            overstatedNestedCount,
            nextEntryWithoutItsDelimiter,
        )

    /**
     * A TradeCaptureReport cut down to the groups above: underlyings with a nested alt-id group, and sides
     * with nested parties (and their sub-ids) and nested allocations. `Currency(15)` is a field of a side and
     * not of an allocation, and `9110` is not in the dictionary at all. `NoVenueAllocs(9005)` is known only
     * as a NUMINGROUP field, which is how a venue's own count reaches a dictionary before its group does.
     */
    val dictionary: FixDictionaryAdapter by lazy {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="TradeCaptureReport" msgtype="AE" msgcat="app">
                  <field name="TradeReportID" required="N"/>
                  <group name="NoUnderlyings" required="N">
                    <field name="UnderlyingSymbol" required="N"/>
                    <field name="UnderlyingSecurityID" required="N"/>
                    <field name="UnderlyingSecurityIDSource" required="N"/>
                    <group name="NoUnderlyingSecurityAltID" required="N">
                      <field name="UnderlyingSecurityAltID" required="N"/>
                      <field name="UnderlyingSecurityAltIDSource" required="N"/>
                    </group>
                    <field name="UnderlyingPx" required="N"/>
                  </group>
                  <field name="TradeDate" required="N"/>
                  <group name="NoSides" required="N">
                    <field name="Side" required="N"/>
                    <group name="NoPartyIDs" required="N">
                      <field name="PartyID" required="N"/>
                      <field name="PartyIDSource" required="N"/>
                      <field name="PartyRole" required="N"/>
                      <group name="NoPartySubIDs" required="N">
                        <field name="PartySubID" required="N"/>
                        <field name="PartySubIDType" required="N"/>
                      </group>
                    </group>
                    <field name="Account" required="N"/>
                    <field name="Currency" required="N"/>
                    <group name="NoAllocs" required="N">
                      <field name="AllocAccount" required="N"/>
                      <field name="AllocQty" required="N"/>
                    </group>
                    <field name="OrderID" required="N"/>
                    <field name="ClOrdID" required="N"/>
                  </group>
                  <field name="LastQty" required="N"/>
                </message>
              </messages>
              <fields>
                <field number="1" name="Account" type="STRING"/>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="11" name="ClOrdID" type="STRING"/>
                <field number="15" name="Currency" type="CURRENCY"/>
                <field number="32" name="LastQty" type="QTY"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="37" name="OrderID" type="STRING"/>
                <field number="54" name="Side" type="CHAR"/>
                <field number="75" name="TradeDate" type="LOCALMKTDATE"/>
                <field number="78" name="NoAllocs" type="NUMINGROUP"/>
                <field number="79" name="AllocAccount" type="STRING"/>
                <field number="80" name="AllocQty" type="QTY"/>
                <field number="305" name="UnderlyingSecurityIDSource" type="STRING"/>
                <field number="309" name="UnderlyingSecurityID" type="STRING"/>
                <field number="311" name="UnderlyingSymbol" type="STRING"/>
                <field number="447" name="PartyIDSource" type="CHAR"/>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="457" name="NoUnderlyingSecurityAltID" type="NUMINGROUP"/>
                <field number="458" name="UnderlyingSecurityAltID" type="STRING"/>
                <field number="459" name="UnderlyingSecurityAltIDSource" type="STRING"/>
                <field number="523" name="PartySubID" type="STRING"/>
                <field number="552" name="NoSides" type="NUMINGROUP"/>
                <field number="571" name="TradeReportID" type="STRING"/>
                <field number="711" name="NoUnderlyings" type="NUMINGROUP"/>
                <field number="802" name="NoPartySubIDs" type="NUMINGROUP"/>
                <field number="803" name="PartySubIDType" type="INT"/>
                <field number="810" name="UnderlyingPx" type="PRICE"/>
                <field number="9005" name="NoVenueAllocs" type="NUMINGROUP"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-nested-groups", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        FixDictionaryAdapter.fromFile(file)
    }

    // ----- one notation for the expected shape, the parsed tree and the overlay -------------------------

    /**
     * [notation] with every entry's contents sorted, so a shape compares equal however its fields were
     * ordered. The entries of a group keep their order, which is the part that has to be right.
     */
    fun canonical(notation: String): String = render(parse(notation.trim(), intArrayOf(0)))

    /** The body of a parsed message in the notation: fields, count fields, and each group's entries. */
    fun shapeOf(message: Message): String = render(nodesOf(message))

    /**
     * The body as the overlay brackets [rows]: the groups the dictionary defines, nested, and every other row
     * as a field. Rows of the envelope (8, 35, 10) are left out, as [shapeOf] leaves out header and trailer.
     */
    fun shapeOf(
        overlay: GroupOverlay,
        rows: List<Pair<Int, String>>,
    ): String {
        val known = overlay.groups.filter { it.source == EntrySource.DICTIONARY }
        val body = nodesOf(rows, 0, rows.size - 1, known).filterNot { it is Leaf && it.tag in setOf(8, 35, 10) }
        return render(body)
    }

    /** Every `tag=value` in the parsed message, header, body, trailer and every entry of every group. */
    fun occurrencesIn(message: Message): List<String> =
        listOf<FieldMap>(message.header, message, message.trailer).flatMap { occurrencesIn(it) }.sorted()

    /** Every `tag=value` on the wire. */
    fun occurrencesOn(wire: String): List<String> =
        FixMessageHelper.parseFixMessage(wire).map { (tag, value) -> "$tag=$value" }.sorted()

    private fun occurrencesIn(map: FieldMap): List<String> {
        val out = mutableListOf<String>()
        val fields = map.iterator()
        while (fields.hasNext()) {
            // A field renders itself as `tag=value`, the value as it would go on the wire.
            out += (fields.next() as Field<*>).toString()
        }
        val groups = map.groupKeyIterator()
        while (groups.hasNext()) {
            map.getGroups(groups.next()).forEach { out += occurrencesIn(it) }
        }
        return out
    }

    private sealed interface Node

    private data class Leaf(
        val tag: Int,
        val value: String,
    ) : Node

    private data class Branch(
        val tag: Int,
        val entries: List<List<Node>>,
    ) : Node

    private fun render(nodes: List<Node>): String = nodes.map { render(it) }.sorted().joinToString(" ")

    private fun render(node: Node): String =
        when (node) {
            is Leaf -> "${node.tag}=${node.value}"
            is Branch -> "${node.tag}{" + node.entries.joinToString("") { "[" + render(it) + "]" } + "}"
        }

    private fun nodesOf(map: FieldMap): List<Node> {
        val out = mutableListOf<Node>()
        val fields = map.iterator()
        while (fields.hasNext()) {
            val field = fields.next() as Field<*>
            out += Leaf(field.tag, field.toString().substringAfter('='))
        }
        val groups = map.groupKeyIterator()
        while (groups.hasNext()) {
            val tag = groups.next()
            out += Branch(tag, map.getGroups(tag).map { nodesOf(it) })
        }
        return out
    }

    private fun nodesOf(
        rows: List<Pair<Int, String>>,
        from: Int,
        to: Int,
        groups: List<GroupNode>,
    ): List<Node> {
        val out = mutableListOf<Node>()
        var row = from
        while (row <= to) {
            out += Leaf(rows[row].first, rows[row].second)
            val group = groups.firstOrNull { it.countRow == row }
            if (group == null) {
                row++
            } else {
                val entries = group.entries.map { nodesOf(rows, it.rows.first, it.rows.last, it.children) }
                out += Branch(group.groupTag, entries)
                val lastEntry = group.entries.last()
                row = lastEntry.rows.last + 1
            }
        }
        return out
    }

    /** Reads the notation from [at], up to the `]` that closes the entry it is in, or to the end. */
    private fun parse(
        text: String,
        at: IntArray,
    ): List<Node> {
        val out = mutableListOf<Node>()
        while (at[0] < text.length && text[at[0]] != ']') {
            if (text[at[0]] == ' ') {
                at[0]++
                continue
            }
            val tagEnd = text.indexOfAny(charArrayOf('=', '{'), at[0])
            val tag = text.substring(at[0], tagEnd).toInt()
            if (text[tagEnd] == '=') {
                val valueEnd = text.indexOfAny(charArrayOf(' ', ']'), tagEnd).let { if (it < 0) text.length else it }
                out += Leaf(tag, text.substring(tagEnd + 1, valueEnd))
                at[0] = valueEnd
            } else {
                at[0] = tagEnd + 1
                val entries = mutableListOf<List<Node>>()
                while (text[at[0]] == '[') {
                    at[0]++
                    entries += parse(text, at)
                    at[0]++ // the closing ]
                }
                at[0]++ // the closing }
                out += Branch(tag, entries)
            }
        }
        return out
    }
}
