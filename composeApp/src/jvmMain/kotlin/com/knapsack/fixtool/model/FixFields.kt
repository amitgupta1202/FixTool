package com.knapsack.fixtool.model

/**
 * **The one parse from a raw FIX string to tag/value pairs**, in the model layer so that
 * [FixMessage] can cache its own answer.
 *
 * It used to live in `FixMessageHelper`, which is in the service layer and therefore below the model
 * — so a message could not memoise its own fields without inverting the dependency. That is the whole
 * reason the parse ran three times per message per grouped rebuild: not because anyone wanted it to,
 * but because the only place with an answer to cache it on could not see the function that produced it.
 * `FixMessageHelper` still owns the *policy* (which string to read, what null means) and delegates the
 * mechanics here, so there is still exactly one parser.
 *
 * **It is allocation-lean on purpose.** The previous implementation was
 * `split(delimiter).filter { it.isNotBlank() }.mapNotNull { it.split('=', limit = 2) ... }`, which for
 * an n-field message allocates an intermediate list of n substrings, a second filtered list, a
 * two-element list *per field*, and a tag substring per field — before any of the pairs that are the
 * actual result. This scans once and allocates the value substring and the pair, and nothing else on
 * the common path. Caching made the parse rare; this made the remaining ones cheap.
 */
object FixFields {
    /** The FIX field delimiter. `|` is the display substitution for it, and only ever that. */
    const val SOH: Char = '\u0001'

    /**
     * **The one delimiter decider.**
     *
     * Not a guess: a wire string always contains SOH, and a display or editor string never does, because
     * producing one replaces every SOH with `|`. What it *cannot* tell you is whether a `|` in a
     * pipe-rendered string was a delimiter or a character inside a value — nothing can, from the string
     * alone.
     */
    fun delimiterOf(raw: String): Char = if (raw.contains(SOH)) SOH else '|'

    /** Parses with the delimiter [delimiterOf] reads off the string. */
    fun parse(raw: String): List<Pair<Int, String>> = parse(raw, delimiterOf(raw))

    /**
     * Parses with a **known** delimiter — no guessing, so a `|` inside a value stays inside it.
     *
     * Behaviourally identical to the split-based version it replaced, including the awkward cases: a
     * segment with no `=` is dropped, a segment whose tag is not an integer is dropped, and a blank
     * segment is dropped (it has no `=`, so it falls out of the same test).
     */
    fun parse(raw: String, delimiter: Char): List<Pair<Int, String>> {
        val length = raw.length
        if (length == 0) return emptyList()
        // Most FIX fields run around a dozen characters; this over-allocates slightly rather than
        // growing the backing array two or three times on the way up.
        val out = ArrayList<Pair<Int, String>>(length / 10 + 4)
        var start = 0
        while (start < length) {
            var end = raw.indexOf(delimiter, start)
            if (end < 0) end = length
            if (end > start) {
                val equals = raw.indexOf('=', start)
                if (equals in (start + 1) until end) {
                    val tag = tagOf(raw, start, equals)
                    if (tag >= 0) out.add(tag to raw.substring(equals + 1, end))
                }
            }
            start = end + 1
        }
        return out
    }

    /**
     * The tag in `raw[from until until]`, or -1 if that is not one.
     *
     * The digit loop is the fast path and covers every tag any venue has ever sent. Anything else —
     * a sign, whitespace, an overflow — falls through to the library parse, so the result is exactly
     * what `String.toIntOrNull()` would have said about the same substring. Being *identical* to the
     * old behaviour matters more here than being fast: this decides whether a field exists at all.
     */
    private fun tagOf(raw: String, from: Int, until: Int): Int {
        var value = 0
        for (i in from until until) {
            val c = raw[i]
            if (c < '0' || c > '9') return raw.substring(from, until).toIntOrNull() ?: -1
            value = value * 10 + (c - '0')
            if (value > MAX_PLAIN_TAG) return raw.substring(from, until).toIntOrNull() ?: -1
        }
        return value
    }

    /** Above this the digit loop hands over rather than risk overflowing an Int. Real tags are far below. */
    private const val MAX_PLAIN_TAG = 9_999_999
}
