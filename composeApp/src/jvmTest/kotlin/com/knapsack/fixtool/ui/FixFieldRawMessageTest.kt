package com.knapsack.fixtool.ui

import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.ui.FixField.Companion.toRawMessage
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The editor's fields turned back into the raw string every editor and automation send hands on.
 *
 * It used to pipe-join its own answer, so a value carrying a literal `|` was split on the way out:
 * `58=Rejected|insufficient margin` reached the wire as `58=Rejected`, and `58=see ticket|44=0` sent a
 * phantom `44=0`. These pin that the raw reads back as the fields it was made from.
 */
class FixFieldRawMessageTest {
    @Test
    fun `a value carrying a pipe reads back as one field`() {
        val fields =
            listOf(
                FixField(tag = "35", value = "3"),
                FixField(tag = "58", value = "Rejected|insufficient margin"),
            )

        val parsed = FixMessageHelper.parseFixMessage(fields.toRawMessage())

        assertEquals(listOf(35 to "3", 58 to "Rejected|insufficient margin"), parsed)
    }

    @Test
    fun `a pipe in a value cannot add a field the author never wrote`() {
        val fields =
            listOf(
                FixField(tag = "35", value = "D"),
                FixField(tag = "58", value = "see ticket|44=0"),
                FixField(tag = "40", value = "1"),
            )

        val parsed = FixMessageHelper.parseFixMessage(fields.toRawMessage())

        assertEquals(listOf(35 to "D", 58 to "see ticket|44=0", 40 to "1"), parsed)
    }

    @Test
    fun `a message with no pipe in any value is still written with pipes`() {
        val fields = listOf(FixField(tag = "35", value = "D"), FixField(tag = "11", value = "ORD-1"))

        assertEquals("35=D|11=ORD-1|", fields.toRawMessage())
    }
}
