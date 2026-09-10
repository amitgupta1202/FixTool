package com.knapsack.fixtool.service.load

/**
 * **When each message of a set left the socket, and when the reply that answered it landed, per phase.**
 *
 * The one piece of new state a chain needs. Everything else a load report holds is an aggregate, and an
 * aggregate cannot be joined to the phase behind it: the p95 of phase 2's round trips and the p95 of phase
 * 1's are two numbers about two different sets of messages, and adding them produces a chain latency no
 * message ever had. What a chain is made of is per message, so this is per message.
 *
 * **Two primitive arrays per phase, indexed by the message index the whole set already counts in.** A
 * phase of a set renders `${'$'}{messageIndex}` from it, a capture is kept at it and a trigger carries it, so a
 * chain is joined by the number every part of the mechanism is already holding. The arrays are sized by
 * the same formula the capture table uses, one past the highest index any live phase will reach, and never
 * by its existence: a set can capture nothing and still chain, and a set can capture and never chain.
 *
 * **A sentinel and not nought**, because a `LongArray` zero-fills and nought is a time. See [NONE]: the
 * same trick [StampMatcher] plays with its own first and last send.
 *
 * **What makes it safe to read is not a lock.** Both writes happen on the I/O threads inside the writing
 * matcher's own monitor, one for the send stamp and one for the reply that matched it, and neither
 * allocates. The set reads them only after every phase has been joined, and a join publishes everything
 * the thread that ended had written. Nothing reads them while a phase is still filling them.
 *
 * **Only the phases of a chain get arrays.** A set of three paced phases and a single run allocate
 * nothing at all, which is what keeps the cost proportional to the feature rather than to every load run
 * there has ever been.
 */
class ChainTimes(
    /** One past the highest message index any live phase of the set will reach. */
    size: Int,
    /** The 0-based phases that take part in a chain. Every other phase gets no arrays and no writes. */
    phases: Set<Int>,
) {
    private val byPhase: Map<Int, Phase> = phases.associateWith { Phase(size.coerceAtLeast(1)) }

    /** Phase [index]'s times, 0-based, or null when that phase takes part in no chain. */
    operator fun get(index: Int): Phase? = byPhase[index]

    /** One phase's two arrays. Written by its matcher, read by the set once the phase has ended. */
    class Phase(
        size: Int,
    ) {
        private val sentMicros = LongArray(size) { NONE }
        private val answeredMicros = LongArray(size) { NONE }

        /** Message [messageIndex] left the socket. An index outside this set's range is dropped. */
        fun sent(messageIndex: Int, micros: Long) {
            if (messageIndex in sentMicros.indices) sentMicros[messageIndex] = micros
        }

        /** The reply that matched message [messageIndex] landed, inside the settle window. */
        fun answered(messageIndex: Int, micros: Long) {
            if (messageIndex in answeredMicros.indices) answeredMicros[messageIndex] = micros
        }

        /** When [messageIndex] left, or [NONE] when this phase never sent it. */
        fun sentAt(messageIndex: Int): Long = at(sentMicros, messageIndex)

        /** When [messageIndex] was answered, or [NONE] when it never was. */
        fun answeredAt(messageIndex: Int): Long = at(answeredMicros, messageIndex)

        private fun at(values: LongArray, messageIndex: Int): Long =
            if (messageIndex in values.indices) values[messageIndex] else NONE
    }

    companion object {
        /** No time at all, which nought cannot mean: a `LongArray` starts full of noughts. */
        const val NONE = Long.MIN_VALUE
    }
}
