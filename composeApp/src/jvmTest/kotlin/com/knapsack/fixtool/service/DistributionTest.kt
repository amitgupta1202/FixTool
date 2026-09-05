package com.knapsack.fixtool.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The distribution grew min, p99 and mean for the load run. They are computed from the same sorted
 * samples as p50, p95 and max, written into the JSON, and read back as zeros from a record written before
 * they existed rather than turning that record unreadable.
 */
class DistributionTest {
    @Test
    fun `all six numbers come from one sorted pass over primitive samples`() {
        val d = assertNotNull(RunSetStats.of(longArrayOf(9, 1, 3, 7, 5).also { it.sort() }))

        assertEquals(1L, d.min)
        assertEquals(5L, d.p50)
        assertEquals(9L, d.p95)
        assertEquals(9L, d.p99)
        assertEquals(9L, d.max)
        assertEquals(5L, d.mean)
        assertEquals(5, d.samples)
        assertNull(RunSetStats.of(LongArray(0)))
    }

    @Test
    fun `the JSON carries the six and reads a record from before they existed`() {
        val stats = RunSetStats.Stats(replyLatency = RunSetStats.Distribution(p50 = 20, p95 = 400, max = 400, samples = 4, min = 10, p99 = 400, mean = 115), wallClock = null)

        val json = RunSetStats.toJson(stats)
        val reply = json["replyLatency"]!!.jsonObject
        assertEquals(10L, reply["min"]!!.jsonPrimitive.long)
        assertEquals(400L, reply["p99"]!!.jsonPrimitive.long)
        assertEquals(115L, reply["mean"]!!.jsonPrimitive.long)

        val old =
            buildJsonObject {
                put(
                    "replyLatency",
                    buildJsonObject {
                        put("p50", 20)
                        put("p95", 400)
                        put("max", 400)
                        put("samples", 4)
                    },
                )
            }
        val back = assertNotNull(RunSetStats.fromJson(old)).replyLatency
        assertEquals(RunSetStats.Distribution(p50 = 20, p95 = 400, max = 400, samples = 4), back)
        assertEquals(0L, assertNotNull(back).min)
    }
}
