package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces the grouped-send flattening seen live: a QuoteRequest body with a NoRelatedSym(146)
 * group sent as raw pipe text must serialize with the group intact, not as sorted top-level fields.
 */
class GroupedSendConstructionTest {
    @Test
    fun `manual construction preserves repeating groups`() {
        val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)
        val raw = "35=R|131=QR-1|146=1|55=EUR/USD|54=1|38=250000|"

        val message = raw.toQuickFixMessageManual(dictionary)
        val wire = message.toString().replace('', '|')
        println("WIRE: $wire")

        assertEquals(1, message.getGroupCount(146), "NoRelatedSym must be a real group")
        val entry = message.getGroups(146).first()
        assertEquals("EUR/USD", entry.getString(55))
        assertEquals("250000", entry.getString(38))
        assertTrue(!message.isSetField(55), "Symbol must NOT be a top-level body field")
    }
}
