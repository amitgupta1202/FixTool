package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.FixVersion
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.ShorthandTemplateExpander
import com.knapsack.fixtool.service.ShorthandTemplateExpander.Generator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **The whole per-message cost of a load run lives in one class, so the class is tested in isolation.**
 *
 * A template is read once and every field is sorted: a literal is baked in, a variable or a generator is
 * rendered per message by string substitution, and anything the script engine would have to evaluate is
 * evaluated once per lane and frozen. The engine is never on the per-message path.
 */
class CompiledTemplateTest {
    private val dictionary = FixDictionaryAdapter.forVersion(FixVersion.FIX_4_4)

    private fun template(vararg fields: Pair<Int, String>) = LoadTemplate("t", listOf(35 to "D") + fields.toList())

    private fun lane(slot: Int) = Lane(slot = slot, sessionTitle = "LOADGEN [$slot]", senderCompID = "LOADGEN%02d".format(slot), qualifier = "")

    @Test
    fun `a field with no expression is a literal, a variable or a generator is per message, anything else is once`() {
        val compiled =
            CompiledTemplate.compile(
                template(
                    55 to "EUR/USD",
                    11 to "ORD-\${run}-\${messageIndex}",
                    60 to "\${utcnow}",
                    1 to "\${sessionSenderCompID}",
                    58 to "\${out.D.11}",
                    59 to "\${incoming[\"8\"].valueOfTag(37)}",
                    100 to "\${id = uuid}",
                    101 to "\${id2 = out.D.11}",
                    102 to "\${out.D.11}-\${messageIndex}",
                ),
            )

        assertEquals(listOf(11, 60, 1, 100), compiled.perMessageTags)
        assertEquals(listOf(35, 55, 58, 59, 101, 102), compiled.fixedTags)
        assertEquals(listOf(58, 59, 101, 102), compiled.onceTags, "a mixed field is frozen whole, because half a field cannot be")
    }

    @Test
    fun `two renders on one lane differ only in the per-message tags`() {
        val compiled = CompiledTemplate.compile(template(55 to "EUR/USD", 11 to "ORD-\${run}-\${messageIndex}", 60 to "\${utcnow}"))
        val proto = compiled.prepare(lane(7), mapOf("run" to "b7f2"), dictionary) { error("nothing here needs the engine") }

        val first = proto.render(1)
        val second = proto.render(2)

        assertEquals("ORD-b7f2-1", first.getString(11))
        assertEquals("ORD-b7f2-2", second.getString(11))
        assertEquals("EUR/USD", first.getString(55))
        assertEquals("EUR/USD", second.getString(55))
        assertEquals("D", first.header.getString(35))
        assertTrue(first.getString(60).matches(Regex("""\d{8}-\d{2}:\d{2}:\d{2}\.\d{3}""")), first.getString(60))
    }

    @Test
    fun `sessionIndex is the lane's profile slot, and a Once expression is evaluated once per lane`() {
        val compiled = CompiledTemplate.compile(template(11 to "ORD-\${sessionIndex}-\${messageIndex}", 1 to "\${sessionSenderCompID}", 58 to "\${out.D.11}"))
        var onceCalls = 0
        val resolveOnce: (String) -> String = { onceCalls++; "frozen-$onceCalls" }

        val lane3 = compiled.prepare(lane(3), emptyMap(), dictionary, resolveOnce = resolveOnce)
        val lane12 = compiled.prepare(lane(12), emptyMap(), dictionary, resolveOnce = resolveOnce)
        repeat(50) { lane3.render(it + 1) }

        assertEquals(2, onceCalls, "one evaluation per lane, none per message")
        assertEquals("ORD-3-1", lane3.render(1).getString(11))
        assertEquals("ORD-12-1", lane12.render(1).getString(11))
        assertEquals("LOADGEN03", lane3.render(1).getString(1))
        assertEquals("frozen-1", lane3.render(9).getString(58))
        assertEquals("frozen-2", lane12.render(9).getString(58))
    }

    @Test
    fun `an assignment keeps its value for a later field in the same message and mints afresh for the next`() {
        val compiled = CompiledTemplate.compile(template(11 to "\${id = uuid:8}", 58 to "ref-\${id}"))
        val proto = compiled.prepare(lane(1), emptyMap(), dictionary) { error("no engine") }

        val first = proto.render(1)
        val second = proto.render(2)

        assertEquals(8, first.getString(11).length)
        assertEquals("ref-${first.getString(11)}", first.getString(58))
        assertNotEquals(first.getString(11), second.getString(11))
        assertEquals(emptySet(), compiled.missingVariables(emptySet()), "the assignment defines the name it reads back")
    }

    @Test
    fun `a name nothing seeds is reported rather than left in the wire`() {
        val compiled = CompiledTemplate.compile(template(11 to "ORD-\${run}-\${messageIndex}", 58 to "\${desk}"))

        assertEquals(setOf("run", "desk"), compiled.missingVariables(emptySet()))
        assertEquals(setOf("desk"), compiled.missingVariables(setOf("run")))
        assertEquals(emptySet(), compiled.missingVariables(setOf("run", "desk") + Lane.SEED_NAMES))
    }

    @Test
    fun `a per-message header tag lands in the header, not the body`() {
        val compiled = CompiledTemplate.compile(template(50 to "\${sessionSenderCompID}", 11 to "\${messageIndex}"))
        val proto = compiled.prepare(lane(4), emptyMap(), dictionary) { error("no engine") }

        val message = proto.render(1)

        assertEquals("LOADGEN04", message.header.getString(50))
        assertTrue(!message.isSetField(50), "SenderSubID is a header field and must not be duplicated in the body")
        assertEquals("1", message.getString(11))
    }

    @Test
    fun `the generators render what the expander's Kotlin would have`() {
        val uuid = CompiledTemplate.generate(generatorOf("uuid"))
        val short = CompiledTemplate.generate(generatorOf("uuid:20"))
        val stamped = CompiledTemplate.generate(generatorOf("utcnow+1d:yyyyMMdd"))

        assertEquals(36, uuid.length)
        assertEquals(20, short.length)
        assertTrue(!short.contains("-"))
        assertTrue(stamped.matches(Regex("""\d{8}""")), stamped)
        val back = assertIs<Generator.Timestamp>(generatorOf("now-2h"))
        assertEquals("-", back.sign)
        assertEquals(2L, back.amount)
        assertEquals("h", back.unit)
        assertTrue(!back.utc)
    }

    private fun generatorOf(expression: String) =
        requireNotNull(ShorthandTemplateExpander.generatorOf(expression)) { "'$expression' should be a generator" }

    /**
     * **A captured name renders per message, and a missing one refuses the whole message.**
     *
     * Refuses rather than rendering `${'$'}{quoteId}`: a hit that names no quote is not a smaller test, it is a
     * different one, and the venue would answer it with a refusal that looked like the venue's fault.
     */
    @Test
    fun `a captured part renders per message, and a null lookup refuses the message`() {
        val compiled =
            CompiledTemplate.compile(
                template(11 to "H-\${messageIndex}", 117 to "\${quoteId}", 44 to "\${offer}"),
            )
        val prototype =
            compiled.prepare(
                lane = lane(1),
                seed = emptyMap(),
                dictionary = dictionary,
                lookups =
                    mapOf(
                        "quoteId" to { i: Int -> if (i == 2) null else "QID-$i" },
                        "offer" to { _: Int -> "1.09010" },
                    ),
            ) { it }

        val first = assertIs<CompiledTemplate.Rendered.Message>(prototype.renderOrRefuse(1))
        assertEquals("QID-1", first.message.getString(117))
        assertEquals("1.09010", first.message.getString(44))
        assertEquals("H-1", first.message.getString(11))

        val refused = assertIs<CompiledTemplate.Rendered.Unaddressable>(prototype.renderOrRefuse(2))
        assertEquals(2, refused.index)
        assertEquals("quoteId", refused.missing)
    }

    /** A captured name is not "unseeded": the set refused it if no earlier phase filled it. */
    @Test
    fun `a template reading a capture asks nothing of the seed, and says it reads the name`() {
        val compiled = CompiledTemplate.compile(template(117 to "\${quoteId}"))

        assertEquals(setOf("quoteId"), compiled.missingVariables(emptySet()), "with nothing to fill it, it is missing")
        assertEquals(setOf("quoteId"), compiled.variablesRead())
    }

    /**
     * A random price is a per-message generator like `${'$'}{uuid}`, which is what makes it usable in a load
     * template at all: a Kotlin expression would go through the script engine once per lane and freeze.
     */
    @Test
    fun `a random price and a seconds offset are per-message, and every message gets its own`() {
        val compiled = CompiledTemplate.compile(template(44 to "\${random:1.09000:1.09090:5}", 62 to "\${utcnow+30s}"))
        val prototype = compiled.prepare(lane(1), emptyMap(), dictionary) { it }

        assertEquals(listOf(44, 62), compiled.perMessageTags, "neither is frozen per lane")
        val prices = (1..200).map { prototype.render(it).getString(44) }
        assertTrue(prices.toSet().size > 10, "every message got the same price: ${prices.toSet()}")
        prices.forEach {
            assertEquals(5, it.substringAfter('.').length, it)
            assertTrue(it.toBigDecimal() in "1.09000".toBigDecimal().."1.09090".toBigDecimal(), it)
        }
    }
}
