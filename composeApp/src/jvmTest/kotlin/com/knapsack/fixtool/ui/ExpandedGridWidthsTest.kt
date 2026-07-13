package com.knapsack.fixtool.ui

import androidx.compose.ui.unit.dp
import com.knapsack.fixtool.model.FixDictionaryAdapter
import org.junit.Test
import quickfix.Group
import quickfix.Message
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expanded grid indents a row's tag number inside a fixed-width Tag cell, one step per level of
 * group nesting. Auto-fitting that column on text alone clipped nested tags — three levels down,
 * 671 rendered as "67" (issue #37).
 */
class ExpandedGridWidthsTest {
    private val dictionary = FixDictionaryAdapter.fromResource()

    /** Width a cell needs to render [text] whole at [indentLevel]: indent + glyphs + padding. */
    private fun renderWidth(
        text: String,
        indentLevel: Int = 0,
    ) = (indentLevel * EXPANDED_GRID_INDENT_STEP + text.length * 7 + 16).dp

    /** A QuoteResponse with NoLegAllocs nested inside NoLegs — tags 671/673 sit three levels down. */
    private fun messageWithNestedGroups(): Message {
        val alloc =
            Group(670, 671).apply {
                setString(671, "Fabim01") // LegAllocAccount
                setString(673, "100000") // LegAllocQty
            }
        val leg =
            Group(555, 600).apply {
                setString(600, "REPO") // LegSymbol
                addGroup(alloc)
            }
        return Message().apply {
            header.setString(35, "S")
            addGroup(leg)
        }
    }

    @Test
    fun `tag column fits tags nested three levels deep`() {
        val widths = calculateExpandedGridWidths(messageWithNestedGroups(), dictionary)

        // 671/673 are fields of a NoLegAllocs instance inside a NoLegs instance: indent level 2.
        assertEquals(renderWidth("671", indentLevel = 2), widths["Tag"])
    }

    @Test
    fun `tag column widens with nesting depth rather than tag length alone`() {
        val nested = calculateExpandedGridWidths(messageWithNestedGroups(), dictionary)

        val flat =
            calculateExpandedGridWidths(
                Message().apply {
                    header.setString(35, "S")
                    setString(671, "Fabim01")
                },
                dictionary,
            )

        assertTrue(
            nested["Tag"]!! > flat["Tag"]!!,
            "same tag deeper in the tree needs a wider cell: nested=${nested["Tag"]} flat=${flat["Tag"]}",
        )
    }

    @Test
    fun `tag description column fits the instance count a group header renders`() {
        val widths = calculateExpandedGridWidths(messageWithNestedGroups(), dictionary)

        // A group header reads "NoLegAllocs (1 instances)", not the bare field name.
        val header = "${dictionary.getFieldName(670) ?: "670"} (1 instances)"
        assertTrue(
            widths["TagDescription"]!! >= renderWidth(header),
            "TagDescription=${widths["TagDescription"]} truncates the group header \"$header\"",
        )
    }

    @Test
    fun `columns never fall below the minimum width`() {
        val widths = calculateExpandedGridWidths(Message().apply { header.setString(35, "0") }, dictionary)

        listOf("Tag", "TagDescription", "Value", "ValueDescription").forEach { column ->
            assertTrue(widths[column]!! >= 50.dp, "$column collapsed to ${widths[column]}")
        }
    }
}
