package com.knapsack.fixtool.ui

import com.knapsack.fixtool.ui.FixField.Companion.resolveTemplates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FixFieldResolveTemplatesTest {
    @Test
    fun `seed variables resolve in field values`() {
        val fields =
            listOf(
                FixField(tag = "262", value = "MD-\${sessionIndex}"),
                FixField(tag = "58", value = "\${sessionSenderCompID}"),
            )

        val resolved =
            fields.resolveTemplates(
                seedVariables =
                    mapOf(
                        "sessionIndex" to "7",
                        "sessionSenderCompID" to "LOADGEN07",
                    ),
            )

        assertEquals("MD-7", resolved[0].value)
        assertEquals("LOADGEN07", resolved[1].value)
    }

    @Test
    fun `seed variables do not override variables assigned in earlier fields`() {
        val fields =
            listOf(
                FixField(tag = "11", value = "\${orderId = UUID.randomUUID()}"),
                FixField(tag = "41", value = "\${orderId}"),
            )

        val resolved = fields.resolveTemplates(seedVariables = mapOf("sessionIndex" to "1"))

        assertEquals(resolved[0].value, resolved[1].value, "Assigned variable should be reusable across fields")
        assertNotEquals("\${orderId}", resolved[1].value)
    }

    @Test
    fun `fields without expressions are returned as-is regardless of seeds`() {
        val fields = listOf(FixField(tag = "55", value = "EUR/USD"))

        val resolved = fields.resolveTemplates(seedVariables = mapOf("sessionIndex" to "1"))

        assertEquals("EUR/USD", resolved[0].value)
    }
}
