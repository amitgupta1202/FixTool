package com.knapsack.fixtool.service.compare

import com.knapsack.fixtool.service.ExpectationEvaluator
import com.knapsack.fixtool.service.FixMessageHelper
import com.knapsack.fixtool.service.compare.WirePaste.Verdict
import java.time.Instant

private const val SOH = FixMessageHelper.SOH
private const val CHECKSUM_MODULUS = 256

/** What a misread pipe costs the checksum: `'|'` is `0x7C`, SOH is `0x01`. It says how many. Never which. */
private const val PIPE_MINUS_SOH = 123

/**
 * **Where the message begins** — the first thing in the text that is the *start of a FIX field*.
 *
 * Not the first *segment* that parses as one, which is what this did first and which was wrong on the only
 * line anybody actually pastes: a log writes `20260714-08:12:31.018 OUT 8=FIX.4.4…`, and the prefix and the
 * BeginString sit in the **same segment**. Dropping the segment dropped `8=FIX.4.4` with it — the message lost
 * its BeginString, its arithmetic could no longer be checked, and the paste quietly downgraded from *verified*
 * to *unverifiable*. Caught by the test that pastes a real log line.
 */
private val FIELD_START = Regex("(?:^|[^0-9])([0-9]+)=")

/**
 * **Bytes FixTool did not watch arrive — read, and reported.**
 *
 * A paste is a log fragment, an email, a message from another environment. It is the one door through which
 * bytes reach the assertion engine without FixTool having seen them on a wire — and the right-hand column of
 * the diff is what **Accept actual** writes into a scenario. So a paste read wrongly is a value **no venue
 * sent**, written into an assertion, for ever.
 *
 * **The pipe is the whole problem, and this codebase has shipped it twice.** `58=filled|in full` is legal FIX —
 * `|` is an ordinary character inside a value, and `tools/fake-venue` sends one on purpose. A log that renders
 * SOH as `|` cannot be read back through: nothing in `58=filled|in full|60=…` says which pipe was the
 * delimiter. `GET /messages` once reported `58 = "filled"` and dropped the tail; the reconcile view once diffed
 * against the `|`-substituted display string. Both were the same mistake, and both were silent.
 *
 * So this **never guesses, and it never repairs**. The message's own arithmetic is the only thing that can
 * settle the question, and it settles it one way: `CheckSum(10)` is a sum over the bytes, so reading a pipe as
 * a delimiter when it was a character moves the sum by [PIPE_MINUS_SOH]. That says **how many** pipes were
 * misread. It never says **which** — and searching for the subset that makes the sum come out is guessing with
 * extra steps, which would eventually produce a message no venue sent, wearing a checksum that agrees with it.
 *
 * Three answers, and only the first two can be bound:
 *
 * - **[Verdict.READ]** — SOH-delimited (unambiguous by construction: SOH cannot occur inside a FIX value*), or
 *   `|`-rendered with the message's own `BodyLength(9)` and `CheckSum(10)` agreeing with the bytes as read.
 * - **[Verdict.UNVERIFIED]** — every segment read cleanly, but there is no `8=`/`10=` pair to check it against:
 *   a fragment. Bound, and *said*. `PASTED` provenance carries the doubt to the badge, and to disk.
 * - **[Verdict.REFUSED]** — the reading is **disproved**: a segment that is not `tag=value` (which is exactly
 *   what a pipe inside a value looks like), arithmetic that disagrees, or bytes past the `CheckSum(10)`
 *   trailer — fields the frame never counted, riding a lint that says the frame agrees. Nothing is bound.
 *
 * *the exception, stated because it will be met: a length-prefixed data field (`RawDataLength(95)`/`RawData(96)`,
 * `XmlDataLen(212)`) may legally contain SOH. FixTool's parser has never honoured the length prefix, so such a
 * message reads as junk segments and is refused by the rule above — the honest outcome, and better than the
 * silent misread it replaces.
 */
data class WirePaste(
    val verdict: Verdict,
    /** The fields, in the order pasted. **Empty when [Verdict.REFUSED]** — a disproved reading binds nothing. */
    val fields: List<Pair<Int, String>>,
    /** The bytes as read, SOH-delimited: what a reference or a golden is built from. Null when refused. */
    val wire: String?,
    /** What was ignored *before* the first field — a log's timestamp and level. Never silently eaten. */
    val ignoredPrefix: String?,
    /** The message's own `SendingTime(52)`: the moment a `~now` row is judged at. */
    val sendingTime: String?,
    val anchor: Instant?,
    /** The one line the sheet shows: **what was read**. */
    val lint: String,
    /** Why it cannot be bound, when it cannot. Null otherwise. */
    val why: String?,
    /** What a temporal row will do — said out loud, because an anchorless paste is not a broken paste. */
    val anchorNote: String,
) {
    val usable: Boolean get() = verdict != Verdict.REFUSED

    enum class Verdict {
        /** The reading is unambiguous, or the message's own arithmetic confirms it. */
        READ,

        /** Read cleanly — and there is nothing in these bytes that could confirm the delimiter. */
        UNVERIFIED,

        /** The reading is disproved. Nothing here may be bound, and nothing is guessed at. */
        REFUSED,
    }

    companion object {
        /** Read a paste. One message — capture's paste box reads one of these per line. */
        @Suppress("ReturnCount")
        fun read(raw: String): WirePaste {
            val text = raw.trim()
            if (text.isBlank()) return refused("There is nothing here to read.", "nothing pasted")

            val match =
                FIELD_START.find(text) ?: return refused(
                    "No FIX field could be read from this at all — a field is `tag=value`, and there is not one here.",
                    "nothing readable",
                )
            // Everything before the first field is a LOG PREFIX — `08:12:31.412 INFO [pool-1] IN: ` — skipped,
            // and *reported*. Refusing it would refuse every real log line ever pasted. An unreadable segment
            // once the message has begun is a different animal entirely: that is the pipe.
            val start = match.range.first + if (text[match.range.first].isDigit()) 0 else 1
            val prefix = text.take(start).ifBlank { null }
            val message = text.drop(start)

            val delimiter = FixMessageHelper.delimiterOf(message)
            val body = message.split(delimiter).filter { it.isNotBlank() }
            val unreadable = body.filter { it.asField() == null }
            val fields = body.mapNotNull { it.asField() }

            if (unreadable.isNotEmpty()) return pipeInAValue(unreadable, fields, delimiter, prefix)
            val messages = fields.count { it.first == 8 }
            if (messages > 1) {
                return refused(
                    "This paste carries $messages messages. The slot takes one, and which one is not FixTool's to " +
                        "choose — paste a single message, or use capture, which reads one per line.",
                    "$messages messages",
                    prefix,
                )
            }
            return arithmeticOf(fields, delimiter, prefix)
        }
    }
}

/**
 * **The pipe, caught.** `58=filled|in full` split on `|` leaves `in full` behind — a segment that is not a
 * field and cannot be one. `parseFixMessage` drops it **silently**, which is precisely what made this invisible
 * for as long as it was. Here it is the evidence, and it is quoted back.
 */
private fun pipeInAValue(
    unreadable: List<String>,
    fields: List<Pair<Int, String>>,
    delimiter: Char,
    prefix: String?,
): WirePaste {
    val quoted = unreadable.take(3).joinToString(", ") { "\"$it\"" }
    val why =
        if (delimiter == '|') {
            "Read ${fields.size} fields and then could not read $quoted — which is not a FIX field and cannot " +
                "be one. That is what a | inside a value looks like: 58=filled|in full is legal FIX, and " +
                "a log that renders SOH as | cannot be read back through it. Nothing in these characters says " +
                "which pipe was the delimiter, and FixTool will not guess — the value it guessed wrong would be " +
                "written into your assertion. Paste the raw bytes: a venue's message log keeps the SOH."
        } else {
            "Read ${fields.size} fields and then could not read $quoted. These bytes are SOH-delimited, so a " +
                "segment that is not `tag=value` means the message is damaged — or it carries a length-prefixed " +
                "data field (`RawData(96)`), whose length prefix FixTool's parser does not honour. Either way, " +
                "this is not the message the venue sent."
        }
    return refused(why, "unreadable: $quoted", prefix, delimiter)
}

/**
 * **The message checks its own reading.** `BodyLength(9)` counts the bytes and `CheckSum(10)` sums them, so a
 * pipe read as a delimiter when it was a character moves the sum and says so. It cannot say *where*, which is
 * why a disagreement is a refusal and never a repair.
 */
@Suppress("ReturnCount")
private fun arithmeticOf(fields: List<Pair<Int, String>>, delimiter: Char, prefix: String?): WirePaste {
    // The FIRST CheckSum(10): one message has one trailer, and it ends the frame. Asking for the last
    // let a concatenated second record's own trailer stretch the frame over both.
    val checksumAt = fields.indexOfFirst { it.first == 10 }
    val complete = fields.firstOrNull()?.first == 8 && checksumAt > 0
    val pipes = fields.count { it.second.contains('|') }

    // A fragment carries no arithmetic to be held to. With SOH that is fine — the delimiter was never in doubt.
    // With `|` it is the one case nothing can settle, and it says so rather than implying it was confirmed.
    if (!complete) return fragment(fields, delimiter, prefix, pipes)

    // The frame ends at its trailer. Fields past CheckSum(10) may parse cleanly — and the arithmetic below
    // counts nothing after the trailer, so binding them would carry bytes the venue never framed into the
    // slot under the frame's own "✓ agree" lint: a second record missing its 8=, concatenated onto the line.
    if (checksumAt != fields.lastIndex) return trailingBytesRefusal(fields, checksumAt, prefix, delimiter)

    val checksum = checksumOf(fields, checksumAt)
    if (checksum != null && checksum.first != checksum.second) {
        return checksumRefusal(checksum.first, checksum.second, prefix, delimiter)
    }
    val bodyLength = bodyLengthOf(fields, checksumAt)
    if (bodyLength != null && bodyLength.first != bodyLength.second) {
        return bodyLengthRefusal(bodyLength.first, bodyLength.second, prefix, delimiter)
    }

    val agreed =
        buildList {
            checksum?.let { add("CheckSum(10) ${"%03d".format(it.first)}") }
            bodyLength?.let { add("BodyLength(9) ${it.first}") }
        }.joinToString(" and ")
    val name = if (delimiter == SOH) "SOH" else "|"
    return bound(
        Verdict.READ,
        fields,
        prefix,
        "✓ ${fields.size} fields · $name delimiter · $agreed agree${pipeNote(pipes)}",
    )
}

private fun fragment(fields: List<Pair<Int, String>>, delimiter: Char, prefix: String?, pipes: Int): WirePaste {
    val soh = delimiter == SOH
    val lint =
        if (soh) {
            "✓ ${fields.size} fields · SOH delimiter — unambiguous${pipeNote(pipes)}"
        } else {
            "⚠ ${fields.size} fields · | delimiter · no CheckSum(10) — nothing in these bytes can confirm that " +
                "every | was a delimiter. If a value here contains one, it has been split, and there is no way " +
                "to tell."
        }
    return bound(if (soh) Verdict.READ else Verdict.UNVERIFIED, fields, prefix, lint)
}

/** `stated to computed`, or null where the message carries no checksum to be held to. */
private fun checksumOf(fields: List<Pair<Int, String>>, checksumAt: Int): Pair<Int, Int>? {
    val stated = fields[checksumAt].second.trim().toIntOrNull() ?: return null
    val bytes = fields.take(checksumAt).rebuild().toByteArray(Charsets.ISO_8859_1)
    return stated to (bytes.sumOf { it.toInt() and 0xFF } % CHECKSUM_MODULUS)
}

/** `stated to computed`, or null where the message carries no `BodyLength(9)` to be held to. */
private fun bodyLengthOf(fields: List<Pair<Int, String>>, checksumAt: Int): Pair<Int, Int>? {
    val lengthAt = fields.indexOfFirst { it.first == 9 }
    if (lengthAt !in 0 until checksumAt) return null
    val stated = fields[lengthAt].second.trim().toIntOrNull() ?: return null
    val body = fields.subList(lengthAt + 1, checksumAt).rebuild()
    return stated to body.toByteArray(Charsets.ISO_8859_1).size
}

/** Bytes after the trailer are outside the frame — the message ended, and something else followed it. */
private fun trailingBytesRefusal(
    fields: List<Pair<Int, String>>,
    checksumAt: Int,
    prefix: String?,
    delimiter: Char,
): WirePaste {
    val trailing = fields.drop(checksumAt + 1)
    val quoted = trailing.take(3).joinToString(", ") { (tag, value) -> "\"$tag=$value\"" }
    return refused(
        "CheckSum(10) is the trailer — the message ends there — and ${trailing.size} more field(s) follow it: " +
            "$quoted. The frame's arithmetic counts nothing past the trailer, so these bytes are outside the " +
            "message: a second record (missing its 8=) concatenated onto the line, or a copy that caught the " +
            "start of the next one. Binding them would write fields the venue never framed into an assertion, " +
            "under a checksum that never counted them. Paste one framed message.",
        "${trailing.size} field(s) after CheckSum(10)",
        prefix,
        delimiter,
    )
}

private fun checksumRefusal(stated: Int, computed: Int, prefix: String?, delimiter: Char): WirePaste =
    refused(
        "The message's own CheckSum(10) says ${"%03d".format(stated)}, and these bytes as read sum to " +
            "${"%03d".format(computed)} — the reading is disproved by the message itself. With a | " +
            "delimiter that is what a pipe inside a value does — each one read as a delimiter moves the sum by " +
            "$PIPE_MINUS_SOH — and the checksum says how many were misread, never which, so there is nothing " +
            "here to repair without guessing. Paste the raw SOH bytes.",
        "checksum ${"%03d".format(stated)} ≠ ${"%03d".format(computed)}",
        prefix,
        delimiter,
    )

private fun bodyLengthRefusal(stated: Int, computed: Int, prefix: String?, delimiter: Char): WirePaste =
    refused(
        "The message's own BodyLength(9) says $stated bytes, and these bytes as read are $computed. The reading " +
            "is disproved by the message itself — a field has been lost or gained somewhere between here and " +
            "the venue. Paste the raw SOH bytes.",
        "body length $stated ≠ $computed",
        prefix,
        delimiter,
    )

/** The good news, when there is any: the pipe that would have shredded this message stayed inside its value. */
private fun pipeNote(pipes: Int): String =
    when (pipes) {
        0 -> ""
        1 -> " · one value contains a |, and it is kept — these bytes say where the fields end"
        else -> " · $pipes values contain a |, and they are kept — these bytes say where the fields end"
    }

private fun List<Pair<Int, String>>.rebuild(): String = joinToString("") { (tag, value) -> "$tag=$value$SOH" }

private fun bound(
    verdict: Verdict,
    fields: List<Pair<Int, String>>,
    prefix: String?,
    lint: String,
): WirePaste {
    val sendingTime = fields.firstOrNull { it.first == 52 }?.second
    val anchor = sendingTime?.let { ExpectationEvaluator.parseTimestamp(it) }
    return WirePaste(
        verdict = verdict,
        fields = fields,
        wire = fields.rebuild(),
        ignoredPrefix = prefix,
        sendingTime = sendingTime,
        anchor = anchor,
        lint = lint,
        why = null,
        anchorNote = anchorNote(sendingTime, anchor),
    )
}

/** An anchorless paste is not a broken paste. It simply has no moment, and the rows say so instead of failing. */
private fun anchorNote(sendingTime: String?, anchor: Instant?): String =
    when {
        anchor != null -> "temporals anchor to SendingTime(52) $sendingTime — not to the clock"
        sendingTime != null ->
            "SendingTime(52) is `$sendingTime`, which is not a timestamp this build can read — `~now` rows are " +
                "left unjudged, not failed"
        else ->
            "no SendingTime(52) — `~now` rows are left unjudged, not failed. A paste has no moment of its own, " +
                "and judging one against the clock would manufacture a red describing nothing but how long ago " +
                "it was pasted"
    }

private fun refused(why: String, short: String, prefix: String? = null, delimiter: Char? = null): WirePaste {
    val read = delimiter?.let { " · ${if (it == SOH) "SOH" else "|"} delimiter" }.orEmpty()
    return WirePaste(
        verdict = Verdict.REFUSED,
        fields = emptyList(),
        wire = null,
        ignoredPrefix = prefix,
        sendingTime = null,
        anchor = null,
        lint = "✗ $short$read",
        why = why,
        anchorNote = "",
    )
}

/** `tag=value`, or nothing. A segment that is not a field is not *nearly* a field. */
private fun String.asField(): Pair<Int, String>? {
    val parts = split('=', limit = 2)
    if (parts.size != 2) return null
    val tag = parts[0].trim().toIntOrNull() ?: return null
    return if (tag > 0) tag to parts[1] else null
}
