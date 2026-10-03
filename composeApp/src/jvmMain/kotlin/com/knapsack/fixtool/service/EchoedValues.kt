package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixFields

/**
 * **A value a counterparty sent, kept as data on its way through a reply template.**
 *
 * A reply is built by passes over text. The references are filled in, anything still wearing `${...}` is
 * evaluated as Kotlin, and the text is split on `|` into fields. A value pasted in by the first pass used to
 * be read by the later two as if the author had written it, so a ClOrdID of `${...}` ran on the tester's
 * machine and a ClOrdID carrying a `|` added fields to FixTool's own reply.
 *
 * [seal] hides the characters a later pass acts on (`$`, `|`, SOH, and the escape itself) behind an escape
 * no pass reads. [raw] undoes it once the passes are done. A value that carried a `|` comes out of [raw]
 * SOH-delimited, the form [FixFields.delimiterOf] reads back exactly, as [FixMessageHelper.joinFields]
 * writes it. A value that carried SOH cannot be written into a raw message at all, so [raw] refuses it, and
 * the step is lost and said so like any other step that cannot be built.
 *
 * [number] is the other half. Inside an author's expression a value is spliced into Kotlin source, where no
 * escape can make text inert, so only a plain number goes in there.
 */
internal object EchoedValues {
    /** A private-use character: nothing a template pass matches on, and nothing a FIX value means. */
    private const val ESCAPE = ''
    private const val MARK_ESCAPE = 'e'
    private const val MARK_DOLLAR = 'd'
    private const val MARK_PIPE = 'p'
    private const val MARK_SOH = 's'

    /** The characters a later pass acts on, and the escape that hides them. */
    private val SEALED = setOf('$', '|', FixFields.SOH, ESCAPE)

    private val NUMBER = Regex("-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)")

    /** Longest stretch of a refused value quoted back in the refusal, so a notification stays one line. */
    private const val QUOTED_LENGTH = 40

    /** [value] with every character a later pass could act on hidden. The common value comes back as itself. */
    fun seal(value: String): String {
        if (value.none { it in SEALED }) return value
        return buildString(value.length + 4) {
            value.forEach { c ->
                when (c) {
                    ESCAPE -> append(ESCAPE).append(MARK_ESCAPE)
                    '$' -> append(ESCAPE).append(MARK_DOLLAR)
                    '|' -> append(ESCAPE).append(MARK_PIPE)
                    FixFields.SOH -> append(ESCAPE).append(MARK_SOH)
                    else -> append(c)
                }
            }
        }
    }

    /**
     * [value] for use inside an author's expression, where it becomes Kotlin source.
     *
     * A plain number goes in as written, which is what makes `${req.38 / 2}` half the order. Anything else
     * would be code, so it refuses, naming [reference]. Nothing that worked before is lost: a value that is
     * not a number never compiled as one.
     */
    fun number(value: String?, reference: String): String {
        if (value == null) error("$reference: there is no value to read, and an expression reads it")
        if (NUMBER.matches(value)) return value
        val quoted = if (value.length > QUOTED_LENGTH) value.take(QUOTED_LENGTH) + "…" else value
        error("$reference is '$quoted', which is not a number, and only a number can go inside an expression")
    }

    /**
     * [text] as a raw FIX message, every sealed value opened.
     *
     * Exactly [text] when nothing in it was sealed, which is every reply whose values carry none of the four
     * characters. Pipe-delimited as written when only a `$` or the escape was sealed. SOH-delimited when a
     * value carried a `|`, because that is the one form in which a `|` inside a value reads back as itself.
     */
    fun raw(text: String): String {
        if (ESCAPE !in text) return text
        val sealedDelimiter = "$ESCAPE$MARK_PIPE" in text || "$ESCAPE$MARK_SOH" in text
        if (!sealedDelimiter) return open(text)
        return FixMessageHelper.joinFields(FixFields.parse(text).map { (tag, value) -> tag to open(value) })
    }

    private fun open(text: String): String =
        buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val mark = if (c == ESCAPE) text.getOrNull(i + 1) else null
                when (mark) {
                    MARK_ESCAPE -> append(ESCAPE)
                    MARK_DOLLAR -> append('$')
                    MARK_PIPE -> append('|')
                    MARK_SOH -> error("a value the counterparty sent carries SOH, and a reply cannot carry it back")
                    else -> {
                        append(c)
                        i++
                        continue
                    }
                }
                i += 2
            }
        }
}
