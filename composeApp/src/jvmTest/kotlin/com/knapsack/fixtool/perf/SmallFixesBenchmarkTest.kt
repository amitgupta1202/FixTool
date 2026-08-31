package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.compiled
import com.knapsack.fixtool.service.ConversationRows
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **The last of the audit's findings: small, real, and each one a thing done per use that could be done
 * once.**
 *
 * None of these is a headline. They are here because they were on the list, because each is cheap to
 * fix and impossible to regress silently, and because "small" is a claim that should be measured rather
 * than assumed — one of them turned out to be worth 40x.
 */
class SmallFixesBenchmarkTest {
    /**
     * **A regex matcher compiles its pattern once, not once per evaluation.**
     *
     * `Matcher.Regex.compiled()` called `kotlin.text.Regex(pattern)` — that is `Pattern.compile` — every
     * time. The evaluator asks per row evaluated, so a scenario with regex rows replayed across a run
     * set recompiled the same handful of patterns thousands of times.
     */
    @Test
    fun `a regex matcher compiles its pattern once`() {
        val matcher = Matcher.Regex("""^EX-\d{4,8}-[A-Z]{2,4}$""")

        val result =
            Bench.measure("Matcher.Regex.compiled()", ops = 20_000) { matcher.compiled() }
        println("\n┌─ Getting a matcher's compiled pattern\n│  " + result.render() + "\n└─\n")

        assertEquals(0L, result.bytesPerOp, "a memoised pattern must allocate nothing to hand back")
        assertSame(
            matcher.compiled(),
            matcher.compiled(),
            "and must be the same instance each time, not an equal one",
        )
    }

    /** The memo must not change what the matcher means, including for a pattern that does not compile. */
    @Test
    fun `memoising the pattern does not change what compiles`() {
        assertTrue(Matcher.Regex("[0-9]+").compiled() != null, "a valid pattern still compiles")
        assertEquals(null, Matcher.Regex("[unclosed").compiled(), "an invalid one still reports null")
        assertEquals(
            Matcher.Regex("abc"),
            Matcher.Regex("abc"),
            "two matchers with the same pattern must still be equal — the memo is not part of identity",
        )
        assertEquals(
            Matcher.Regex("abc").hashCode(),
            Matcher.Regex("abc").hashCode(),
            "and must still hash alike, or a codec round-trip stops round-tripping",
        )
    }

    /**
     * **The ungrouped render list stops materialising a wrapper per message.**
     *
     * Every call site wrote `messages.indices.map { Row.Message(it) }` — one object per message in the
     * retained window, rebuilt on every 100ms drain cycle, to say "row i is message i". The LazyColumn
     * above it reads a few dozen of them.
     */
    @Test
    fun `the ungrouped render list is a view, not a copy`() {
        val comparison =
            Bench.compare(
                "The render list for 1,000 ungrouped messages",
                ops = 2_000,
                before =
                    "materialise one Row per message" to {
                        (0 until 1_000).map { ConversationRows.Row.Message(it) }
                    },
                after =
                    "identityRows(1_000)" to {
                        ConversationRows.identityRows(1_000)
                    },
            )

        assertTrue(
            comparison.allocationFactor > 100.0,
            "a view must not allocate per message; got ${comparison.allocationFactor}x less",
        )
    }

    /** And it has to behave as the list it replaced, because everything above it treats it as one. */
    @Test
    fun `the view behaves as the list it replaced`() {
        val view = ConversationRows.identityRows(5)
        val materialised = (0 until 5).map { ConversationRows.Row.Message(it) }

        assertEquals(materialised, view, "must equal the list it replaced, element for element")
        assertEquals(materialised.size, view.size)
        assertEquals(materialised, view.toList(), "and materialise back to the same thing")
        assertEquals(ConversationRows.Row.Message(3), view[3])
        assertEquals(3, view.indexOf(ConversationRows.Row.Message(3)))
        assertEquals(emptyList(), ConversationRows.identityRows(0).toList())
        assertTrue(
            runCatching { view[5] }.exceptionOrNull() is IndexOutOfBoundsException,
            "and must refuse a row past the end rather than inventing one",
        )
    }
}
