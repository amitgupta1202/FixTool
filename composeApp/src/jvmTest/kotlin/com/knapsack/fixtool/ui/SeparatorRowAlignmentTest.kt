package com.knapsack.fixtool.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.knapsack.fixtool.model.AppMessage
import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.model.Separator
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import quickfix.Message
import quickfix.field.ClOrdID
import quickfix.field.MsgType
import quickfix.field.OrderQty
import quickfix.field.Price
import quickfix.field.SenderCompID
import quickfix.field.Side
import quickfix.field.Symbol
import quickfix.field.TargetCompID
import java.io.File
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **Issue #25 — a blank row must be exactly as tall as a populated one.**
 *
 * The reported defect: separator ("blank") rows in the grid were a slightly different height from
 * message rows, so using blanks to align groups of messages left everything a few pixels out.
 *
 * The fix pins both to `24.dp` ([HierarchicalGridView]'s separator branch says "match
 * MessageSummaryRow dimensions exactly"), but nothing held that claim down — two literals in two
 * places drift the moment one is edited, and a width/column test passes happily while they do.
 *
 * This measures it instead of trusting the literals. Rows are laid out in one `LazyColumn`, so with
 * a separator between message 2 and message 3 the *pitch* between consecutive message rows is the
 * whole story:
 *
 * ```
 *   row1        ── pitch(1→2) = message row height
 *   row2
 *   ─separator─ ── pitch(2→3) = message row height + separator height
 *   row3
 *   row4        ── pitch(3→4) = message row height   (proves we are back in step)
 * ```
 *
 * If the blank is the same height as a populated row, `pitch(2→3)` is exactly twice `pitch(1→2)`.
 * That is #25's acceptance criterion — *"rows are perfectly aligned when mixed between blank and
 * populated"* — stated as an equation rather than as a pixel count, so it survives any future change
 * to the row height itself and only fails if the two stop agreeing.
 */
class SeparatorRowAlignmentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var testDictionaryFile: File
    private lateinit var dictionary: FixDictionaryAdapter

    @Before
    fun setup() {
        testDictionaryFile = File.createTempFile("separator_align_dict", ".xml")
        testDictionaryFile.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="4">
    <header>
        <field name="BeginString" required="Y"/>
        <field name="BodyLength" required="Y"/>
        <field name="MsgType" required="Y"/>
        <field name="SenderCompID" required="Y"/>
        <field name="TargetCompID" required="Y"/>
        <field name="MsgSeqNum" required="Y"/>
        <field name="SendingTime" required="Y"/>
    </header>
    <trailer>
        <field name="CheckSum" required="Y"/>
    </trailer>
    <messages>
        <message name="NewOrderSingle" msgtype="D" msgcat="app">
            <field name="ClOrdID" required="Y"/>
            <field name="Symbol" required="Y"/>
            <field name="Side" required="Y"/>
            <field name="OrderQty" required="Y"/>
            <field name="Price" required="N"/>
        </message>
    </messages>
    <fields>
        <field name="BeginString" number="8" type="STRING"/>
        <field name="BodyLength" number="9" type="LENGTH"/>
        <field name="MsgType" number="35" type="STRING">
            <value enum="D" description="NewOrderSingle"/>
        </field>
        <field name="MsgSeqNum" number="34" type="SEQNUM"/>
        <field name="SenderCompID" number="49" type="STRING"/>
        <field name="TargetCompID" number="56" type="STRING"/>
        <field name="SendingTime" number="52" type="UTCTIMESTAMP"/>
        <field name="CheckSum" number="10" type="STRING"/>
        <field name="ClOrdID" number="11" type="STRING"/>
        <field name="Symbol" number="55" type="STRING"/>
        <field name="Side" number="54" type="CHAR">
            <value enum="1" description="Buy"/>
            <value enum="2" description="Sell"/>
        </field>
        <field name="OrderQty" number="38" type="QTY"/>
        <field name="Price" number="44" type="PRICE"/>
    </fields>
</fix>""",
        )
        dictionary = FixDictionaryAdapter.fromFile(testDictionaryFile)
    }

    @After
    fun cleanup() {
        if (testDictionaryFile.exists()) testDictionaryFile.delete()
    }

    /** ClOrdID is rendered as its own grid column, which is what gives each row a unique anchor. */
    private fun order(clOrdId: String): FixMessage {
        val msg = Message()
        msg.header.setField(MsgType("D"))
        msg.header.setField(SenderCompID("SENDER"))
        msg.header.setField(TargetCompID("TARGET"))
        msg.setField(ClOrdID(clOrdId))
        msg.setField(Symbol("EUR/USD"))
        msg.setField(Side('1'))
        msg.setField(OrderQty(1000.0))
        msg.setField(Price(1.25))
        return FixMessage(
            timestamp = LocalDateTime.now(),
            direction = FixMessage.Direction.INCOMING,
            rawMessage = msg.toString(),
            messageType = "D",
            quickfixMessage = msg,
        )
    }

    private fun topOf(text: String): Float =
        composeTestRule
            .onNodeWithText(text)
            .getUnclippedBoundsInRoot()
            .top.value

    @Test
    fun `a blank row is exactly as tall as a populated row`() {
        val messages: List<AppMessage> =
            listOf(
                order("ALIGN-1"),
                order("ALIGN-2"),
                Separator(timestamp = LocalDateTime.now()),
                order("ALIGN-3"),
                order("ALIGN-4"),
            )

        composeTestRule.setContent {
            HierarchicalGridView(
                messages = messages,
                dictionary = dictionary,
                hideProtocolTags = true,
                gridViewColumns = listOf(11),
            )
        }
        composeTestRule.waitForIdle()

        val row1 = topOf("ALIGN-1")
        val row2 = topOf("ALIGN-2")
        val row3 = topOf("ALIGN-3")
        val row4 = topOf("ALIGN-4")

        val rowPitch = row2 - row1
        val acrossSeparator = row3 - row2
        val afterSeparator = row4 - row3
        val separatorHeight = acrossSeparator - rowPitch

        // Sanity: rows are stacked downwards and actually have height, so a silent
        // zero-height/empty-render does not read as a pass.
        assertTrue(rowPitch > 0f, "message rows must stack downwards, got a pitch of $rowPitch")

        assertTrue(
            abs(separatorHeight - rowPitch) < 0.5f,
            "issue #25: a blank row must match a populated row. " +
                "Message row pitch = $rowPitch, blank row height = $separatorHeight " +
                "(measured across the separator: $acrossSeparator).",
        )

        // And the rows below the blank are back in step — the alignment the issue actually asked for.
        assertTrue(
            abs(afterSeparator - rowPitch) < 0.5f,
            "rows after a blank must resume the original pitch: expected $rowPitch, got $afterSeparator",
        )
    }
}
