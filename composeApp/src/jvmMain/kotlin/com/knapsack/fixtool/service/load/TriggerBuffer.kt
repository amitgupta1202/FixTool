package com.knapsack.fixtool.service.load

import java.util.concurrent.LinkedBlockingQueue

/**
 * **Where one phase's triggers wait for it**: the message indices an earlier phase's replies released, in
 * the order they were released, until the phase filling it says nothing more is coming.
 *
 * One per phase, owned by the set and made before any phase starts, exactly as the capture table is. Made
 * that early because the thing that closes it and the thing that reads it are different phases on different
 * threads, and a buffer created when a phase starts is a buffer nobody could have closed if that phase never
 * did.
 *
 * **Unbounded, and never because a bounded one would block.** `LinkedBlockingQueue.offer` does not block on
 * a full queue: it returns false and the trigger is silently gone. The real reason is the thread that posts.
 * A trigger is released from a matched reply, which arrives on a MINA processor thread shared by every
 * session on that processor, so a post that waited would freeze reads, writes and SEND stamps for all of
 * them. Nor may it throw: `SocketStampFilter` swallows what comes back out of a stamp listener, so an
 * exception here is a trigger that vanishes with nothing said about it. [post] therefore never blocks,
 * never throws, and never refuses.
 *
 * **Closed on every exit path**, which is what removes the deadlock. Phases 1 and 2 paced with 3 reacting to
 * 2, and phase 1 failing, means phase 2 is skipped before its matcher is ever built, so nothing on the
 * reply path could ever close phase 3's buffer and phase 3 would wait for a trigger that cannot come. The
 * same is reachable with no failure at all, through a set somebody stopped. So the close belongs to the
 * phase's exit rather than to its replies, and the note says which exit it was.
 */
class TriggerBuffer(
    /** The phase that reads this one, 1-based. What a note about it names. */
    val phase: Int,
) {
    private val queue = LinkedBlockingQueue<Int>()

    @Volatile private var closedWith: String? = null

    /** True once nothing more will be posted. Indices already posted are still there to be read. */
    val closed: Boolean get() = closedWith != null

    /** Why nothing more is coming, or null while it is still open. */
    val note: String? get() = closedWith

    /** How many triggers are waiting to be read. For a progress line and for a test. */
    val waiting: Int get() = queue.count { it != END }

    /**
     * **Message [index] has been fired.** Called on a stamp thread, so it neither blocks nor throws.
     *
     * A post after the close is dropped rather than kept: the phase that would have issued for it has been
     * told there is nothing more coming, and one more index behind that promise is a message nobody is
     * waiting to send.
     */
    fun post(index: Int) {
        if (closed) return
        queue.offer(index)
    }

    /**
     * **Nothing more will be posted**, for the reason [reason] gives. The first close decides, because the
     * first reason is the true one: a phase skipped by a failure that is then closed again by the set
     * ending should still say the failure skipped it.
     */
    @Synchronized
    fun close(reason: String) {
        if (closedWith != null) return
        closedWith = reason
        // Wakes whoever is waiting. Put back by [next] rather than taken, so every reader sees it.
        queue.offer(END)
    }

    /**
     * The next index to issue for, or null once the buffer is closed and everything posted has been read.
     *
     * Blocks until one of those is true, which is what a reactive phase's issuing thread does between its
     * trigger's replies.
     */
    fun next(): Int? {
        val value = queue.take()
        if (value != END) return value
        queue.offer(END)
        return null
    }

    private companion object {
        /**
         * The end of the buffer, as a value in it, so a reader waiting on an empty queue is woken by the
         * close rather than left to poll for it. No message index can collide with it: a phase counts from
         * 1 and `indexFrom` is 1-based.
         */
        const val END = Int.MIN_VALUE
    }
}
