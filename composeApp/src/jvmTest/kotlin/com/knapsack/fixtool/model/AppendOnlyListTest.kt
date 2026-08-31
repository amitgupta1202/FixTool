package com.knapsack.fixtool.model

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The structure that replaced `List + element` in the order book, pinned on its own.**
 *
 * It exists to make appending cheap, but the property it must never lose is that an already-published
 * list does not change. The order book hands these to the UI through a `StateFlow` and to the fold that
 * decides an order's state; a view that grew after publication would make a snapshot disagree with
 * itself, which is a far worse bug than the quadratic it was written to remove.
 *
 * So the sharing — the whole point — is exactly what gets tested hardest here.
 */
class AppendOnlyListTest {
    @Test
    fun `an empty list is empty`() {
        val list = AppendOnlyList.empty<String>()
        assertEquals(0, list.size)
        assertEquals(emptyList(), list.toList())
    }

    @Test
    fun `appending builds the list in order`() {
        var list = AppendOnlyList.empty<Int>()
        repeat(100) { list = list.append(it) }

        assertEquals(100, list.size)
        assertEquals((0 until 100).toList(), list.toList())
        repeat(100) { assertEquals(it, list[it]) }
    }

    /**
     * **The immutability guarantee, stated directly.** Every intermediate view is kept and re-checked
     * after 500 further appends have grown (and repeatedly reallocated) the shared array underneath it.
     */
    @Test
    fun `an earlier view never sees anything appended after it`() {
        val snapshots = mutableListOf<AppendOnlyList<Int>>()
        var list = AppendOnlyList.empty<Int>()
        repeat(500) {
            list = list.append(it)
            snapshots += list
        }

        snapshots.forEachIndexed { index, snapshot ->
            assertEquals(index + 1, snapshot.size, "snapshot $index must still be its own length")
            assertEquals((0..index).toList(), snapshot.toList(), "and hold exactly its own elements")
        }
    }

    /**
     * A branch is the case that cannot share: two appends onto the same view. Both must be right, and
     * neither may see the other's element. This is the path the order book never takes, which is
     * precisely why it needs a test — nothing else would exercise it.
     */
    @Test
    fun `two appends onto the same view do not see each other`() {
        val base = AppendOnlyList.from(listOf("a", "b", "c"))
        val left = base.append("L")
        val right = base.append("R")

        assertEquals(listOf("a", "b", "c"), base.toList(), "the base is unchanged by either")
        assertEquals(listOf("a", "b", "c", "L"), left.toList())
        assertEquals(listOf("a", "b", "c", "R"), right.toList())

        // And a branch keeps working as a normal list afterwards.
        assertEquals(listOf("a", "b", "c", "R", "R2"), right.append("R2").toList())
        assertEquals(listOf("a", "b", "c", "L"), left.toList(), "still untouched")
    }

    @Test
    fun `reading outside the view is refused rather than reaching into shared storage`() {
        val base = AppendOnlyList.from(listOf(1, 2, 3))
        base.append(4) // grows the shared array past `base`'s length

        assertFailsWith<IndexOutOfBoundsException> { base[3] }
        assertFailsWith<IndexOutOfBoundsException> { base[-1] }
        assertEquals(3, base.size, "and the length still describes the view, not the storage")
    }

    @Test
    fun `it behaves as a List for equality, iteration and search`() {
        val list = AppendOnlyList.from(listOf("x", "y", "z"))

        assertEquals(listOf("x", "y", "z"), list, "must equal an ordinary List with the same contents")
        assertEquals(listOf("x", "y", "z").hashCode(), list.hashCode())
        assertEquals("y", list.first { it == "y" })
        assertEquals(1, list.indexOf("y"))
        assertTrue("z" in list)
        assertEquals(listOf("x", "y", "z"), list.map { it })
    }

    @Test
    fun `from and of round-trip`() {
        assertEquals(listOf(7), AppendOnlyList.of(7).toList())
        assertEquals(emptyList(), AppendOnlyList.from(emptyList<Int>()).toList())
        val many = (1..1_000).toList()
        assertEquals(many, AppendOnlyList.from(many).toList())
    }

    /**
     * **The memory-model claim, exercised.**
     *
     * Appends happen under the order book's monitor and reads happen on other threads with no lock at
     * all, so correctness rests on the release/acquire pair documented on the class. This drives that:
     * one writer appends under a lock and publishes each view to a volatile field; readers keep
     * acquiring the latest published view and fully materialising it.
     *
     * A torn read — a stale array, or an element not yet visible — surfaces as a null element or a
     * wrong value, and the assertion is that a reader NEVER sees one. It cannot prove the absence of a
     * race, but it reliably catches the version of this class that publishes the length before the
     * element.
     */
    @Test
    fun `a reader on another thread never sees a partial append`() {
        val lock = Any()
        // AtomicReference rather than a @Volatile local (Kotlin does not allow that annotation here);
        // get/set carry the same volatile read/write semantics, which is what the test is exercising.
        val published = java.util.concurrent.atomic.AtomicReference(AppendOnlyList.empty<Int>())
        val appends = 20_000
        val readers = 3
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(readers + 1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

        repeat(readers) {
            pool.submit {
                start.await()
                while (published.get().size < appends) {
                    val snapshot = published.get()
                    val seen = snapshot.toList()
                    if (seen.size != snapshot.size) failures += "size drifted mid-read"
                    seen.forEachIndexed { index, value ->
                        if (value != index) failures += "slot $index held $value"
                    }
                }
            }
        }
        pool.submit {
            start.await()
            repeat(appends) { i ->
                synchronized(lock) { published.set(published.get().append(i)) }
            }
        }

        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "workers must finish")
        assertEquals(emptyList(), failures.toList(), "no reader may observe a partial or stale append")
        assertEquals(appends, published.get().size)
    }
}
