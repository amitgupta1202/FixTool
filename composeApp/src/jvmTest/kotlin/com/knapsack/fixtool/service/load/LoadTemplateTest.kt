package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.SavedFixField
import com.knapsack.fixtool.model.SavedFixMessage
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.humanDuration
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The template's own small facts: its type, the correlation it implies, and how it is read from its sources. */
class LoadTemplateTest {
    @Test
    fun `the match is inferred from the first standard correlation tag the message carries`() {
        assertEquals(LoadMatch(11), LoadTemplate("nos", listOf(35 to "D", 37 to "X", 11 to "A")).inferMatch())
        assertEquals(LoadMatch(131), LoadTemplate("rfq", listOf(35 to "R", 131 to "Q1", 55 to "EUR/USD")).inferMatch())
        assertEquals(LoadMatch(11), LoadTemplate("cancel", listOf(35 to "F", 41 to "orig", 11 to "new")).inferMatch())
        assertNull(LoadTemplate("hb", listOf(35 to "0")).inferMatch())
    }

    @Test
    fun `the message type is tag 35 or nothing`() {
        assertEquals("D", LoadTemplate("t", listOf(8 to "FIX.4.4", 35 to "D")).msgType)
        assertNull(LoadTemplate("t", listOf(55 to "EUR/USD")).msgType)
        assertNull(LoadTemplate("t", listOf(35 to " ")).msgType)
    }

    @Test
    fun `a saved message drops excluded rows and rows whose tag is not a number`() {
        val saved =
            SavedFixMessage(
                name = "NOS",
                fields =
                    listOf(
                        SavedFixField("35", "D"),
                        SavedFixField("11", "ORD-\${messageIndex}"),
                        SavedFixField("58", "muted", excluded = true),
                        SavedFixField("", "blank tag"),
                    ),
            )

        assertEquals(listOf(35 to "D", 11 to "ORD-\${messageIndex}"), LoadTemplates.of(saved).fields)
    }

    @Test
    fun `a raw line reads with pipes or SOH, and a file is its first non-blank line`() {
        assertEquals(listOf(8 to "FIX.4.4", 35 to "D", 11 to "A"), LoadTemplates.fromRaw("t", "8=FIX.4.4|35=D|11=A|").fields)
        assertEquals(listOf(35 to "D", 11 to "A"), LoadTemplates.fromRaw("t", "35=D11=A").fields)

        val file = File.createTempFile("nos", ".fix").apply { writeText("\n\n35=D|11=X|\n35=D|11=Y|\n"); deleteOnExit() }
        val template = assertNotNull(LoadTemplates.fromFile(file))
        assertEquals(listOf(35 to "D", 11 to "X"), template.fields)
        assertEquals(file.nameWithoutExtension, template.name)
    }

    @Test
    fun `shapes describe themselves the way the label needs`() {
        assertEquals("×4,000", LoadShape.Burst(4000).describe())
        assertEquals("500/s for 10m", LoadShape.Rate(500, 600_000).describe())
        assertEquals(300_000L, LoadShape.Rate(500, 600_000).requested)
        assertEquals("1h 5m", humanDuration(3_900_000))
        assertEquals("1m 30s", humanDuration(90_000))
        assertEquals("45s", humanDuration(45_000))
        assertEquals("0s", humanDuration(0))
    }
}
