package com.knapsack.fixtool.perf

import java.lang.management.ManagementFactory

/**
 * **A benchmark harness that measures the thing that does not flake.**
 *
 * Wall-clock timing on a laptop under a Gradle daemon is worth reporting and worth nothing as an
 * assertion: a GC pause, a background build, or a thermal throttle moves it by more than most of the
 * fixes in this codebase are worth. Every timing-based perf test either flakes or is asserted so
 * loosely it would pass with the regression back in.
 *
 * So the number this harness *pins* is *bytes allocated per operation*, read off
 * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`. It is a count, not a duration: it does
 * not care how busy the machine is, it varies by well under a percent run to run, and it is the
 * quantity nearly every finding in the audit is actually about — work proportional to the whole
 * buffer, done per message. A change that stops copying a 1,000-element list per tick shows up here
 * as an exact drop and shows up in a stopwatch as noise.
 *
 * Time is still measured and still printed, because allocation is not the whole story (a recompiled
 * regex costs CPU without allocating much), but it is reported for the reader rather than asserted.
 *
 * Use [compare] to state a fix as what it is: two implementations, same workload, one number each.
 */
object Bench {
    private val threadBean =
        ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    /** True when this JVM can account allocation per thread. HotSpot and OpenJDK can; pins skip if not. */
    val allocationMeasurable: Boolean =
        threadBean?.isThreadAllocatedMemorySupported == true &&
            threadBean.isThreadAllocatedMemoryEnabled

    private fun allocatedBytes(): Long =
        threadBean?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L

    /**
     * Bytes allocated by **every live thread**, for work that does not happen on the calling one.
     *
     * Compose is the reason this exists. A `runComposeUiTest` block drives composition, layout and the
     * frame clock on threads of their own, so the calling thread's allocation counter reports the cost
     * of `waitForIdle()` — a few kilobytes of nothing — while the work under test allocates megabytes
     * next door. The first version of the grid benchmark measured exactly that and reported a
     * reassuring flat line for code that was provably O(N).
     *
     * The trade is noise: anything else running in the JVM lands in this figure too. That is tolerable
     * for a comparison of two arms measured back to back in the same process, and not tolerable as an
     * absolute. Prefer [measure]'s per-thread default wherever the work is on the calling thread.
     */
    private fun allocatedBytesAllThreads(): Long {
        val bean = threadBean ?: return -1L
        val ids = bean.allThreadIds
        val perThread = bean.getThreadAllocatedBytes(ids) ?: return -1L
        var total = 0L
        for (bytes in perThread) if (bytes > 0) total += bytes
        return total
    }

    /**
     * One measured implementation: what it allocated and how long it took, per operation.
     *
     * [bytesPerOp] is the assertable figure. [nanosPerOp] is the median of the measured rounds, which
     * resists the single long pause that would wreck a mean.
     */
    data class Result(
        val name: String,
        val bytesPerOp: Long,
        val nanosPerOp: Long,
        val ops: Int,
    ) {
        fun render(): String =
            "%-46s %10s B/op %12s ns/op".format(
                name,
                thousands(bytesPerOp),
                thousands(nanosPerOp),
            )
    }

    /**
     * Measures [block] run [ops] times per round, after [warmupRounds] discarded rounds.
     *
     * The warm-up is not politeness — it is what makes the numbers mean anything. The first run of any
     * of this code is dominated by class loading, and JIT has not yet compiled the loop being measured,
     * so an unwarmed figure describes the interpreter rather than the shipped app.
     *
     * [block] must return something: the value is fed to a consumer the JIT cannot see through, so a
     * computation whose result is discarded is not optimised away entirely. That is the difference
     * between measuring a parse and measuring an empty loop.
     */
    fun measure(
        name: String,
        ops: Int = 100,
        warmupRounds: Int = 3,
        rounds: Int = 7,
        /**
         * Count allocation across every thread, not just this one.
         *
         * Set it when the work under test runs somewhere else — Compose composition and layout do. See
         * [allocatedBytesAllThreads] for what it costs in noise.
         */
        allThreads: Boolean = false,
        block: () -> Any?,
    ): Result {
        val sample: () -> Long = if (allThreads) ::allocatedBytesAllThreads else ::allocatedBytes
        repeat(warmupRounds) { repeat(ops) { consume(block()) } }

        val times = LongArray(rounds)
        val allocs = LongArray(rounds)
        repeat(rounds) { round ->
            System.gc()
            val bytes0 = sample()
            val t0 = System.nanoTime()
            repeat(ops) { consume(block()) }
            times[round] = System.nanoTime() - t0
            allocs[round] = sample() - bytes0
        }
        times.sort()
        allocs.sort()
        return Result(
            name = name,
            bytesPerOp = allocs[rounds / 2] / ops,
            nanosPerOp = times[rounds / 2] / ops,
            ops = ops,
        )
    }

    /**
     * **The shape every fix in the audit is reported in**: the old way and the new way, same workload,
     * printed side by side with the factor between them.
     *
     * Keeping the superseded implementation alive in the test is deliberate. A benchmark that measures
     * only the new code can say "this is fast" and cannot say "this is faster than what it replaced" —
     * and the second claim is the one a reader wants and the one that rots silently when someone
     * reintroduces the old shape somewhere else.
     */
    fun compare(
        title: String,
        ops: Int = 100,
        before: Pair<String, () -> Any?>,
        after: Pair<String, () -> Any?>,
    ): Comparison {
        val b = measure(before.first, ops = ops, block = before.second)
        val a = measure(after.first, ops = ops, block = after.second)
        val comparison = Comparison(title, b, a)
        println(comparison.render())
        return comparison
    }

    data class Comparison(
        val title: String,
        val before: Result,
        val after: Result,
    ) {
        /** How many times less the fixed path allocates. 1.0 means no change; below 1.0 is a regression. */
        val allocationFactor: Double
            get() = if (after.bytesPerOp <= 0) Double.MAX_VALUE else before.bytesPerOp.toDouble() / after.bytesPerOp

        val timeFactor: Double
            get() = if (after.nanosPerOp <= 0) Double.MAX_VALUE else before.nanosPerOp.toDouble() / after.nanosPerOp

        fun render(): String =
            buildString {
                append('\n')
                append("┌─ ").append(title).append('\n')
                append("│  ").append(before.render()).append('\n')
                append("│  ").append(after.render()).append('\n')
                append("└─ allocation %.1f× less · time %.1f× faster (%d ops/round)".format(
                    allocationFactor,
                    timeFactor,
                    before.ops,
                ))
                append('\n')
            }
    }

    /** Keeps the JIT from deleting the work being measured. */
    @Suppress("unused")
    private var sink: Any? = null

    private fun consume(value: Any?) {
        if (value != null && value.hashCode() == Int.MIN_VALUE) sink = value
    }

    private fun thousands(n: Long): String = "%,d".format(n)
}
