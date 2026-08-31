package com.knapsack.fixtool.model

/**
 * **An immutable list whose append shares its prefix instead of copying it.**
 *
 * Written for one shape of problem, which the order book has twice: a value is rebuilt by appending one
 * element, the rebuilt value must be immutable because it is published to other threads, and the thing
 * being appended to is the *newest* version every time.
 *
 * `List + element` is the obvious way to write that and it is quadratic. Assembling a trail of n events
 * copies 1+2+...+n elements, so an order taking 5,000 fills allocated **509MB** and spent 23ms building
 * its own history — under the book's lock, with every other session on that book waiting. Measured in
 * `OrderBookBenchmarkTest`, which pins the ratio rather than the figure.
 *
 * ## How it works
 *
 * Every list produced by [append] is a *view* onto shared growable storage: a length, and a reference to
 * an array it may read the first `length` slots of. Appending to the newest view writes one slot and
 * returns a view one longer — O(1), no copying. Every older view keeps its own smaller length, so it
 * still describes exactly the list it described before, and cannot see the element that was added after
 * it. That is what makes this immutable in the way that matters: **no view ever changes**.
 *
 * Appending to an view that is *not* the newest — a branch — cannot share, because the next slot is
 * already spoken for. That case copies the prefix into fresh storage and starts a new chain, so it is
 * O(n) and correct rather than fast. The order book never branches; it always appends to what it just
 * stored. Anything that does branch simply pays what `List + element` used to charge for everything.
 *
 * ## Threads
 *
 * Appends happen under the book's monitor; reads happen on the UI thread through a published snapshot.
 * The safety comes from a release/acquire pair rather than from the lock, because readers do not take
 * the lock:
 *
 * - the writer stores the element, then **volatile-writes** [Storage.length] — the release;
 * - the view is then published through `MutableStateFlow.value`, itself a volatile write;
 * - a reader acquires that reference, then **volatile-reads** [Storage.elements] before indexing.
 *
 * So every element a view is allowed to read was written before that view was published, and the array
 * a reader sees is at least as new as the one holding those elements. Growth replaces [Storage.elements]
 * before the length that admits the new slot is published, so a reader can never index past the end of
 * the array it observes.
 */
class AppendOnlyList<T> private constructor(
    private val storage: Storage,
    private val length: Int,
) : AbstractList<T>() {
    /**
     * The shared growable array behind a chain of views.
     *
     * Both fields are volatile and both are written only under the appending caller's own mutual
     * exclusion. [length] is the high-water mark of the chain — how many slots have been handed out —
     * and is what makes "am I the newest view?" answerable in O(1).
     */
    private class Storage(
        @Volatile @JvmField var elements: Array<Any?>,
        @Volatile @JvmField var length: Int,
    )

    override val size: Int get() = length

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T {
        if (index < 0 || index >= length) {
            throw IndexOutOfBoundsException("index $index is outside 0 until $length")
        }
        // Volatile read: pairs with the writer's publication of any growth, so the array observed here
        // is never smaller than the length this view is entitled to read.
        return storage.elements[index] as T
    }

    /**
     * This list with [value] on the end.
     *
     * O(1) when this is the newest view of its storage, which is the case the order book is in on every
     * message. O(n) for a branch, which nothing in this codebase does.
     */
    fun append(value: T): AppendOnlyList<T> {
        val storage = this.storage
        if (storage.length == length) {
            var elements = storage.elements
            if (length == elements.size) {
                elements = elements.copyOf(if (length == 0) INITIAL_CAPACITY else length * 2)
                storage.elements = elements
            }
            elements[length] = value
            // Release: everything above is visible to anyone who acquires a view built after this.
            storage.length = length + 1
            return AppendOnlyList(storage, length + 1)
        }
        // A branch: the next slot belongs to a longer view already. Copy this prefix and start again.
        val elements = arrayOfNulls<Any?>(maxOf(INITIAL_CAPACITY, (length + 1) * 2))
        System.arraycopy(storage.elements, 0, elements, 0, length)
        elements[length] = value
        return AppendOnlyList(Storage(elements, length + 1), length + 1)
    }

    companion object {
        private const val INITIAL_CAPACITY = 8

        fun <T> empty(): AppendOnlyList<T> = AppendOnlyList(Storage(arrayOfNulls(0), 0), 0)

        fun <T> of(value: T): AppendOnlyList<T> = empty<T>().append(value)

        fun <T> from(values: List<T>): AppendOnlyList<T> {
            if (values.isEmpty()) return empty()
            val elements = arrayOfNulls<Any?>(maxOf(INITIAL_CAPACITY, values.size))
            values.forEachIndexed { index, value -> elements[index] = value }
            return AppendOnlyList(Storage(elements, values.size), values.size)
        }
    }
}
