package com.knapsack.fixtool.perf

import com.knapsack.fixtool.model.FixMessage
import com.knapsack.fixtool.service.FixMessageTemplate
import org.junit.Test
import kotlin.test.assertTrue

/**
 * **A cache in a singleton has to have a ceiling, or it is a leak with a helpful name.**
 *
 * `FixMessageTemplate` is an `object`, so anything it holds is held for the life of the process.
 * `extractedDataCache` keeps a message's extracted fields so one expansion does not re-walk the same
 * message once per expression in it — a good idea, implemented as a
 * `ConcurrentHashMap<quickfix.Message, ...>` with no eviction and no `clear()` anywhere in the
 * codebase.
 *
 * `quickfix.Message` does not override `equals`/`hashCode`, so every message was a distinct key and
 * nothing was ever replaced. A session running templates against its traffic pinned every message it
 * had ever expanded, and their field maps, permanently — long after the session's own ring buffer had
 * evicted them and nothing was left that could ask about them again.
 *
 * **Driven through the cache's real insertion path by reflection**, rather than through `evaluate`.
 * That is not squeamishness about reaching into internals: `evaluate` goes through the Kotlin scripting
 * engine, which costs tens of milliseconds per expression, and a leak test needs *thousands* of
 * distinct messages to say anything. Reflection reaches the exact `getOrPut` that does the caching and
 * skips only the script evaluation the leak has nothing to do with.
 */
class TemplateCacheBoundTest {
    private val extractMessageData =
        FixMessageTemplate::class.java
            .getDeclaredMethod("extractMessageData", String::class.java, Map::class.java)
            .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun cache(): Map<Any, Any> =
        FixMessageTemplate::class.java
            .getDeclaredField("extractedDataCache")
            .apply { isAccessible = true }
            .get(FixMessageTemplate) as Map<Any, Any>

    private fun drive(message: FixMessage) {
        extractMessageData.invoke(
            FixMessageTemplate,
            """${'$'}{incoming["8"].valueOfTag(11)}""",
            mapOf("8" to message),
        )
    }

    /**
     * Four thousand distinct messages against a ceiling of 256.
     *
     * Each is a distinct `quickfix.Message` object, so under identity keying with no eviction every one
     * of them stays resident. The assertion is a count rather than a heap figure because the count is
     * exact and a heap figure on a busy JVM is not.
     */
    @Test
    fun `the extracted-field cache stays bounded however many messages pass through it`() {
        val messages = Corpus.rfqFlow(8_000).filter { it.messageType == "8" }
        assertTrue(messages.size > 2_000, "the corpus must supply plenty of distinct messages")

        messages.forEach(::drive)

        val held = cache().size
        println("\n┌─ Template extracted-field cache")
        println("│  distinct messages driven through it: %,d".format(messages.size))
        println("│  entries retained:                    %,d".format(held))
        println("└─\n")

        assertTrue(
            held <= 256,
            "the cache holds $held entries after ${messages.size} distinct messages — an identity-keyed " +
                "cache with no ceiling retains every message it ever saw, in a process-lifetime singleton",
        )
    }

    /**
     * The cache still has to *work*, or bounding it would just be a slower way of being wrong: a message
     * asked about twice in a row must come back from the cache rather than be re-extracted, since that
     * is the entire reason it exists.
     */
    @Test
    fun `a message asked about twice is only extracted once`() {
        val message = Corpus.rfqFlow(5).first { it.messageType == "8" }

        drive(message)
        val afterFirst = cache().size
        drive(message)
        val afterSecond = cache().size

        assertTrue(afterFirst > 0, "the first ask must populate the cache")
        assertTrue(
            afterSecond == afterFirst,
            "asking again must hit the cache rather than add a second entry for the same message",
        )
    }
}
