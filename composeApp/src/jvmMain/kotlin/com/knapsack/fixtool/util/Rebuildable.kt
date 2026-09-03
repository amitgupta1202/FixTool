package com.knapsack.fixtool.util

import kotlin.reflect.KProperty

/**
 * `by lazy`, except it can be told to forget.
 *
 * The stores that read a workspace were `by lazy` vals, which is right until a workspace can be
 * opened: after that the instance is pinned to whichever directory was current the first time
 * anything touched it. This keeps the create-once-on-first-use behaviour and adds [reset], so
 * opening a workspace can drop every store at once without a single call site changing.
 *
 * Not thread-safe on purpose, matching `LazyThreadSafetyMode.NONE`: these are read from the UI
 * thread, and a lock here would be a lock on every profile read.
 */
class Rebuildable<T>(
    private val make: () -> T,
) {
    private var value: T? = null

    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = value ?: make().also { value = it }

    /** Drops the instance. The next read builds a new one against whatever is current by then. */
    fun reset() {
        value = null
    }
}
