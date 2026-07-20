package com.knapsack.fixtool.service

/**
 * One authored field of a scenario Send step — and the one thing a raw wire field is not: it can be
 * **excluded**, present in the scenario and absent from the message.
 *
 * This is the message editor's `FixField` at scenario scope. There, exclusion earned its keep because
 * the question an author actually asks is "does the venue still accept this without tag 9303?" — and
 * the honest way to ask it is to park the field, run, and put it back, not to delete it and retype it
 * from memory afterwards. A scenario is where that question gets asked repeatedly, so the affordance
 * belongs here more than it belonged there.
 */
data class SendField(
    val tag: Int,
    val value: String,
    val excluded: Boolean = false,
)

/**
 * The grammar of a Send step's `raw`, which is **authored** text rather than wire bytes.
 *
 * A Send is persisted as one string (`ScenarioStep.Send.raw`) — there is no field array in the JSON to
 * hang per-field metadata off, and inventing one would break the codec's additive, byte-identical
 * round-trip bargain. So exclusion is encoded in the string itself: a leading `#` on the tag.
 *
 *     35=D|11=${id}|#9303=1
 *
 * The marker is `#` and not `-` on purpose, and the reason is a safety property rather than taste.
 * [FixMessageHelper.parseFixMessage] — the wire parser every send-time path already funnels through —
 * reads a tag with `toIntOrNull()` and **drops** the field when that returns null. `-9303` parses as
 * the integer -9303 and would survive; `#9303` cannot parse and does not. So any path that forgets to
 * ask [wire] for the bytes fails by dropping an excluded field, never by putting one on the wire. The
 * feature's one hard invariant is enforced by the code that predates it.
 *
 * Two views, and every caller owes an answer to which one it wants:
 *  - **authored** ([parse]) — what the author sees and edits, excluded rows included.
 *  - **wire** ([wire]) — what the venue receives, excluded rows gone.
 */
object SendFields {
    /** Marks an excluded field. Non-numeric by design — see the class comment. */
    const val MARK: Char = '#'

    /** The authored fields, excluded rows and all. Inverse of [join]. */
    fun parse(raw: String): List<SendField> {
        val delimiter = FixMessageHelper.delimiterOf(raw)
        return raw
            .split(delimiter)
            .filter { it.isNotBlank() }
            .mapNotNull { field ->
                val parts = field.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val head = parts[0]
                val excluded = head.startsWith(MARK)
                // A `#` anywhere but the front of the tag is not a marker — and a value is never
                // inspected at all, so `58=order #5` keeps its hash.
                val tag = (if (excluded) head.drop(1) else head).toIntOrNull() ?: return@mapNotNull null
                SendField(tag, parts[1], excluded)
            }
    }

    /**
     * Authored fields back to a raw [parse] reads as the same fields — including which were excluded.
     *
     * Delimiter choice is [FixMessageHelper.joinFields]' rule, for its reason: a value carrying a
     * literal `|` would re-parse as a truncated field, so such a message is joined with SOH instead.
     */
    fun join(fields: List<SendField>): String {
        val delimiter = if (fields.any { it.value.contains('|') }) FixMessageHelper.SOH else '|'
        return fields.joinToString("") { f -> "${if (f.excluded) MARK else ""}${f.tag}=${f.value}$delimiter" }
    }

    /**
     * The bytes: [raw] with every excluded field removed.
     *
     * A raw with nothing excluded is returned **verbatim** rather than re-joined. Parse-then-join is
     * lossy at the edges (a malformed field is dropped, the delimiter may be re-picked), and the
     * overwhelming majority of Send steps have no exclusions at all — those must reach the venue as
     * the author wrote them, byte for byte, exactly as they did before this feature existed.
     */
    fun wire(raw: String): String {
        val fields = parse(raw)
        return if (fields.none { it.excluded }) raw else join(fields.filterNot { it.excluded })
    }
}
