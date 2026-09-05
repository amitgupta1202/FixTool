package com.knapsack.fixtool.service

/**
 * **Reads one tag out of a FIX message string without parsing it.**
 *
 * Two readers run on the MINA I/O thread for every message on the wire: the latency tracker and the load
 * run's matcher. Each wants one or two tag values and nothing else, and a full parse per message would be
 * most of the work either one does. This is a scan, not a regex, and it accepts SOH or `|` as the delimiter
 * so the same code reads the bytes as sent and the form the tool shows.
 */
object WireTags {
    /** The FIX field delimiter as sent. */
    const val SOH: Char = '\u0001'

    /** The value of [tag] in [message], or null when absent or empty. */
    fun tagValue(message: String, tag: Int): String? {
        val needle = "$tag="
        var from = 0
        while (true) {
            val at = message.indexOf(needle, from)
            if (at < 0) return null
            val atFieldStart = at == 0 || message[at - 1] == SOH || message[at - 1] == '|'
            if (atFieldStart) {
                val valueStart = at + needle.length
                var end = valueStart
                while (end < message.length && message[end] != SOH && message[end] != '|') end++
                return if (end > valueStart) message.substring(valueStart, end) else null
            }
            from = at + 1
        }
    }

    /** The message type, which every FIX message carries as tag 35. */
    fun msgType(message: String): String? = tagValue(message, MSG_TYPE)

    /** Session-level message types: nothing a load run issues or matches, so the matcher skips them by name. */
    fun isAdmin(msgType: String): Boolean = msgType in ADMIN_TYPES

    private const val MSG_TYPE = 35
    private val ADMIN_TYPES = setOf("0", "1", "2", "3", "4", "5", "A")
}
