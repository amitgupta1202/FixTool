package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixFields
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The gate skips only work it has proved empty.**
 *
 * [GroupOverlay.salvageable] answers the parse path's question without building an overlay when no count
 * row on the message could start a guess. That is a performance change to a *correctness* path: get the
 * proof wrong and a venue's custom repeating group silently collapses to its last instance again, which is
 * the data loss `ManualParseGroupSalvageTest` exists to prevent.
 *
 * So the superseded implementation is kept alive here (build the whole overlay, filter it to the
 * heuristic groups that carry a count row), and both are run over the same corpus. Every case a reader
 * might worry about is in it: the conformant message with no groups at all, the dictionary-defined group,
 * the venue's undefined one, the uncounted repeat, the count with no entries under it, and the malformed
 * wire where a defined count row is *not* followed by its delimiter, which is the one case the gate must
 * refuse to prove.
 */
class SalvageGateEquivalenceTest {
    /** What `FixMessageHelper.salvageableGroups` did before the gate: the whole overlay, then filtered. */
    private fun throughTheWholeOverlay(
        fields: List<Pair<Int, String?>>,
        messageType: String?,
        dictionary: FixDictionaryAdapter,
    ): List<Salvaged> =
        GroupOverlay
            .build(fields, messageType, dictionary)
            .groups
            .filter { it.source == EntrySource.HEURISTIC && it.countRow != null }
            .map { group -> Salvaged(group.groupTag, group.countRow!!, group.entries.map { it.rows }) }

    private fun bodyOf(raw: String): Pair<List<Pair<Int, String?>>, String> {
        val fields = FixFields.parse(raw)
        val msgType = fields.first { it.first == 35 }.second
        // The parse path hands the *body* over, header and trailer already taken out. 8/9/10 and 35 are
        // the header and trailer rows of every case below, so dropping them here reproduces that.
        val body = fields.filter { it.first !in setOf(8, 9, 10, 35) }
        return body.map { it.first to it.second as String? } to msgType
    }

    private fun bothAgree(name: String, raw: String, dictionary: FixDictionaryAdapter): List<Salvaged> {
        val (body, msgType) = bodyOf(raw)
        val before = throughTheWholeOverlay(body, msgType, dictionary)
        val after = GroupOverlay.salvageable(body, msgType, dictionary)
        assertEquals(before, after, "the gate changed the answer for: $name")
        return after
    }

    @Test
    fun `the gate and the whole overlay agree on every shape`() {
        val venue = venueDictionary()
        val standard = FixDictionaryAdapter.fromResource()

        // Nothing to rescue, and the gate never makes a guess to find that out.
        assertTrue(
            bothAgree(
                "an ExecutionReport with no group at all",
                "8=FIX.4.4|9=100|35=8|55=EURUSD|54=1|38=1000000|44=1.085|10=000|",
                venue,
            ).isEmpty(),
        )
        assertTrue(
            bothAgree(
                "a dictionary-defined group",
                "8=FIX.4.4|9=100|35=8|453=2|448=FIRMA|452=1|448=FIRMB|452=4|55=EURUSD|10=000|",
                venue,
            ).isEmpty(),
        )
        assertTrue(
            bothAgree(
                "a repeat with no count row above it",
                "8=FIX.4.4|9=100|35=8|9001=FIRMA|9002=1|9001=FIRMB|9002=4|10=000|",
                venue,
            ).isEmpty(),
        )
        assertTrue(
            bothAgree("a count of zero", "8=FIX.4.4|9=100|35=8|453=0|55=EURUSD|10=000|", venue).isEmpty(),
        )

        // Something to rescue: the gate must fall through to the guess, and reach the same groups.
        assertEquals(
            1,
            bothAgree(
                "the venue's own counted group",
                "8=FIX.4.4|9=100|35=8|9005=2|9001=FIRMA|9002=1|9001=FIRMB|9002=4|55=EURUSD|10=000|",
                venue,
            ).size,
            "the undefined group is still found, which is the whole point of the path",
        )
        bothAgree(
            "a defined count row whose delimiter does not follow it",
            "8=FIX.4.4|9=100|35=8|453=2|452=1|448=FIRMA|452=4|448=FIRMB|10=000|",
            venue,
        )
        bothAgree(
            "an undefined group beside a defined one",
            "8=FIX.4.4|9=100|35=8|453=2|448=FIRMA|452=1|448=FIRMB|452=4|9005=2|9001=X|9002=1|9001=Y|9002=2|10=000|",
            venue,
        )

        // The bundled FIX 4.4 dictionary, where market data is defined and nothing needs rescuing.
        assertTrue(
            bothAgree(
                "a market data snapshot on the standard dictionary",
                "8=FIX.4.4|9=200|35=W|262=MD-1|55=EUR/USD|268=2|269=0|270=1.0849|271=1000000|" +
                    "269=1|270=1.0851|271=1000000|10=000|",
                standard,
            ).isEmpty(),
        )
    }

    /** `NoPartyIDs` defined as a group, `NoVenueParties` known only as a NUMINGROUP field. */
    private fun venueDictionary(): FixDictionaryAdapter {
        val xml =
            """
            <fix major="4" minor="4">
              <header><field name="BeginString" required="Y"/><field name="MsgType" required="Y"/></header>
              <trailer><field name="CheckSum" required="Y"/></trailer>
              <messages>
                <message name="ExecutionReport" msgtype="8" msgcat="app">
                  <field name="Symbol" required="N"/>
                  <field name="Side" required="N"/>
                  <field name="OrderQty" required="N"/>
                  <field name="Price" required="N"/>
                  <group name="NoPartyIDs" required="N">
                    <field name="PartyID" required="N"/>
                    <field name="PartyRole" required="N"/>
                  </group>
                </message>
              </messages>
              <fields>
                <field number="8" name="BeginString" type="STRING"/>
                <field number="9" name="BodyLength" type="LENGTH"/>
                <field number="10" name="CheckSum" type="STRING"/>
                <field number="35" name="MsgType" type="STRING"/>
                <field number="38" name="OrderQty" type="QTY"/>
                <field number="44" name="Price" type="PRICE"/>
                <field number="54" name="Side" type="CHAR"/>
                <field number="55" name="Symbol" type="STRING"/>
                <field number="448" name="PartyID" type="STRING"/>
                <field number="452" name="PartyRole" type="INT"/>
                <field number="453" name="NoPartyIDs" type="NUMINGROUP"/>
                <field number="9005" name="NoVenueParties" type="NUMINGROUP"/>
              </fields>
            </fix>
            """.trimIndent()
        val file =
            File.createTempFile("fixtool-salvage-gate", ".xml").apply {
                deleteOnExit()
                writeText(xml)
            }
        return FixDictionaryAdapter.fromFile(file)
    }
}
