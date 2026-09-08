package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.ShorthandTemplateExpander
import com.knapsack.fixtool.service.ShorthandTemplateExpander.Generator
import quickfix.Message
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * **A load template read once, with every field sorted by when it has to be resolved.**
 *
 * Every `${…}` in a message takes the same road today: the shorthand expander rewrites `${uuid}` and
 * `${utcnow}` into Kotlin source, and anything that is not a bare variable name is handed to one shared,
 * non-thread-safe script engine under a lock, at 44 to 82 milliseconds a call. For a click that is
 * invisible. For a load run it is the whole budget: a burst of 4,000 with one generator per message would
 * spend four minutes preparing a message that should leave in under a second, and 500 a second allows two
 * milliseconds per message with everything queued behind one lock.
 *
 * So each field becomes one of three [Slot]s. A [Slot.Literal] is baked into the prototype. A
 * [Slot.PerMessage] is literal runs, variable names and generators, joined per message by string
 * substitution. A [Slot.Once] is anything else, evaluated once per lane at prepare time through the full
 * evaluator and frozen into that lane's prototype, and the report lists its tag under **fixed** so nobody
 * believes a `${out.D.11}` was re-read per message.
 *
 * What a load message legitimately needs to vary per message is an index, a seed, an id or a timestamp, and
 * those are exactly the per-message kinds. Everything a Kotlin expression reaches for is per lane by nature.
 */
class CompiledTemplate private constructor(
    val msgType: String,
    private val slots: List<Slot>,
) {
    sealed interface Slot {
        val tag: Int

        data class Literal(
            override val tag: Int,
            val value: String,
        ) : Slot

        data class PerMessage(
            override val tag: Int,
            val parts: List<Part>,
        ) : Slot

        data class Once(
            override val tag: Int,
            val template: String,
        ) : Slot
    }

    /** One piece of a per-message field. */
    sealed interface Part {
        data class Text(
            val text: String,
        ) : Part

        data class Variable(
            val name: String,
        ) : Part

        data class Generated(
            val generator: Generator,
        ) : Part

        /** `${id = uuid}`: a generator or variable whose value is also kept, for a later `${id}` in the same message. */
        data class Assign(
            val name: String,
            val value: Part,
        ) : Part

        /**
         * A name an earlier phase of a set captured, looked up by this message's own index.
         *
         * Its own kind rather than a [Variable] with a scope entry, because a capture can be **missing** —
         * the venue answered without the tag, or never answered — and a missing capture must not render as
         * `${'$'}{quoteId}` on the wire. See [Rendered.Unaddressable].
         */
        data class Captured(
            val name: String,
        ) : Part
    }

    /**
     * **What rendering one message came back as.**
     *
     * A message, or the name that was not there. A load run's bar is "every requested message answered", so
     * a message the tool could not build is a hole in the proof rather than a smaller proof, and the run has
     * to be able to say which index and which name rather than putting a literal `${'$'}{quoteId}` on the wire.
     */
    sealed interface Rendered {
        val index: Int

        data class Message(
            override val index: Int,
            val message: quickfix.Message,
        ) : Rendered

        data class Unaddressable(
            override val index: Int,
            val missing: String,
        ) : Rendered
    }

    /** The tags rendered afresh for every message. */
    val perMessageTags: List<Int> get() = slots.filterIsInstance<Slot.PerMessage>().map { it.tag }

    /** The tags that never change within a lane: literals, and expressions evaluated once per lane. */
    val fixedTags: List<Int> get() = slots.filter { it !is Slot.PerMessage }.map { it.tag }

    /** The fixed tags that reached the script engine, so a report can say which. */
    val onceTags: List<Int> get() = slots.filterIsInstance<Slot.Once>().map { it.tag }

    /**
     * The variable names the per-message fields read that nothing will seed, given what a run does seed.
     *
     * The evaluator's rule for an unknown name is to leave `${name}` in the wire, which for a load run is
     * four thousand orders with a literal `${run}` in their ClOrdID. Refused at plan time instead.
     */
    fun missingVariables(known: Set<String>): Set<String> {
        val defined = known.toMutableSet()
        defined += MESSAGE_INDEX
        val missing = linkedSetOf<String>()
        for (slot in slots) {
            if (slot !is Slot.PerMessage) continue
            for (part in slot.parts) {
                when (part) {
                    is Part.Variable -> if (part.name !in defined) missing += part.name
                    // A captured part is already covered: the set refused it if no earlier phase filled it.
                    is Part.Captured -> Unit
                    is Part.Assign -> {
                        (part.value as? Part.Variable)?.let { if (it.name !in defined) missing += it.name }
                        defined += part.name
                    }
                    else -> Unit
                }
            }
        }
        return missing
    }

    /**
     * Every variable name the per-message fields read, whether or not anything seeds them.
     *
     * [missingVariables] answers "what is not covered"; this answers "what does this message read", which
     * is what a phase editor prints so an author can see which of the set's names it depends on.
     */
    fun variablesRead(): Set<String> {
        val read = linkedSetOf<String>()
        val parts = slots.filterIsInstance<Slot.PerMessage>().flatMap { it.parts }
        for (part in parts) {
            when (part) {
                is Part.Variable -> read += part.name
                is Part.Assign -> (part.value as? Part.Variable)?.let { read += it.name }
                else -> Unit
            }
        }
        return read
    }

    /**
     * **One lane's prototype**: the `Once` slots evaluated through [resolveOnce], the literals in place, and
     * the per-message slots left to [LanePrototype.render].
     *
     * The lane's four names win over a seed of the same name, because the lane's identity is the whole
     * reason `${sessionIndex}` exists and a seed that collides with it is a mistake, not an override.
     */
    @Suppress("LongParameterList")
    fun prepare(
        lane: Lane,
        seed: Map<String, String>,
        dictionary: FixDictionaryAdapter,
        /**
         * Names an earlier phase of a set captured, each a per-message lookup by index.
         *
         * A name here wins over the seed and over the lane, because a set's author who captured `quoteId`
         * and also seeded it has been refused before this is reached.
         */
        lookups: Map<String, (Int) -> String?> = emptyMap(),
        resolveOnce: (template: String) -> String,
    ): LanePrototype {
        val laneScope = seed + lane.seed()
        val placeholderScope = HashMap(laneScope).also { it[MESSAGE_INDEX] = "0" }
        // A captured name has no value to bake in, so the prototype carries a placeholder for its tag and
        // every render replaces it. Anything else is exactly what it was.
        val captured = lookups.keys
        val resolved =
            slots.map { slot ->
                slot.tag to
                    when (slot) {
                        is Slot.Literal -> slot.value
                        is Slot.Once -> resolveOnce(slot.template)
                        is Slot.PerMessage -> renderParts(slot.parts.map { asCaptured(it, captured) }, placeholderScope)
                    }
            }
        val raw = resolved.joinToString("|") { "${it.first}=${it.second}" } + "|"
        val prototype = if (dictionary.getDataDictionary() != null) raw.toQuickFixMessageManual(dictionary) else raw.toQuickFixMessage()
        val perMessage =
            slots
                .filterIsInstance<Slot.PerMessage>()
                .map { slot -> slot.copy(parts = slot.parts.map { asCaptured(it, captured) }) }
        val headerTags = perMessage.map { it.tag }.filter { prototype.header.isSetField(it) }.toSet()
        val names = linkedSetOf<String>()
        perMessage.forEach { slot -> slot.parts.forEach { collectCaptured(it, names) } }
        return LanePrototype(prototype, perMessage, laneScope, headerTags, lookups, names.toList())
    }

    /**
     * A lane's message, ready to clone. Rendering one message is a clone, one `setString` per per-message
     * tag, and nothing else: no parse, no validation, no engine.
     */
    class LanePrototype internal constructor(
        private val prototype: Message,
        private val perMessage: List<Slot.PerMessage>,
        private val laneScope: Map<String, String>,
        private val headerTags: Set<Int>,
        private val lookups: Map<String, (Int) -> String?> = emptyMap(),
        /**
         * Every captured name this message reads, in reading order, whether bare or behind a `${'$'}{id = …}`.
         *
         * Held as names rather than discovered while rendering, so one message asks the capture table
         * once per name: the table is shared across a set's lanes, and the refusal below is decided
         * before a single tag is written rather than half way down the message.
         */
        private val capturedNames: List<String> = emptyList(),
    ) {
        /** [messageIndex] is 1-based, so `${messageIndex}` counts the way "4,000 issued" counts. */
        fun render(messageIndex: Int): Message =
            (renderOrRefuse(messageIndex) as? Rendered.Message)?.message
                ?: error("message $messageIndex cannot be addressed")

        /**
         * The message, or the captured name that was not there.
         *
         * The whole message is refused on one missing name rather than sent with a hole in it: a hit that
         * names no quote is not a smaller test, it is a different one, and the venue would answer it with a
         * refusal that looked like the venue's fault.
         */
        fun renderOrRefuse(messageIndex: Int): Rendered {
            val captured = HashMap<String, String>(capturedNames.size)
            for (name in capturedNames) {
                val value =
                    lookups[name]?.invoke(messageIndex) ?: return Rendered.Unaddressable(messageIndex, name)
                captured[name] = value
            }
            val message = prototype.clone() as Message
            val scope = HashMap(laneScope)
            scope[MESSAGE_INDEX] = messageIndex.toString()
            for (slot in perMessage) {
                val value = renderParts(slot.parts, scope, captured)
                if (slot.tag in headerTags) message.header.setString(slot.tag, value) else message.setString(slot.tag, value)
            }
            return Rendered.Message(messageIndex, message)
        }
    }

    @Suppress("TooManyFunctions")
    companion object {
        const val MESSAGE_INDEX = "messageIndex"

        private const val EXPRESSION_OPENER = "\${"

        private val EXPRESSION = """\$\{([^}]+)}""".toRegex()
        private val VARIABLE = """^[a-zA-Z_][a-zA-Z0-9_]*$""".toRegex()
        private val ASSIGNMENT = """^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$""".toRegex()

        /**
         * Whether [name] is a name a `${'$'}{…}` can read: the renderer's own rule, asked from outside.
         *
         * A capture becomes such a name in a later phase, so the set validates against this rather than
         * against a second copy of the regex that could drift from it.
         */
        fun isVariableName(name: String): Boolean = VARIABLE.matches(name)

        fun compile(template: LoadTemplate): CompiledTemplate {
            val msgType = requireNotNull(template.msgType) { "a load template needs a message type (tag 35)" }
            return CompiledTemplate(msgType, template.fields.map { (tag, value) -> classify(tag, value) })
        }

        /**
         * One field, sorted. A field with no expression is a literal. A field whose every expression is a
         * variable, a generator or an assignment of one is per message. Anything else is once per lane,
         * whole, because half a field cannot be frozen.
         */
        private fun classify(tag: Int, value: String): Slot {
            if (!EXPRESSION.containsMatchIn(value)) return Slot.Literal(tag, value)
            val parts = mutableListOf<Part>()
            var cursor = 0
            for (match in EXPRESSION.findAll(value)) {
                if (match.range.first > cursor) parts += Part.Text(value.substring(cursor, match.range.first))
                parts += partOf(match.groupValues[1].trim()) ?: return Slot.Once(tag, value)
                cursor = match.range.last + 1
            }
            if (cursor < value.length) parts += Part.Text(value.substring(cursor))
            return Slot.PerMessage(tag, parts)
        }

        private fun partOf(expression: String): Part? {
            ASSIGNMENT.matchEntire(expression)?.let { m ->
                val rhs = simplePart(m.groupValues[2].trim()) ?: return null
                return Part.Assign(m.groupValues[1], rhs)
            }
            return simplePart(expression)
        }

        /**
         * Generators before variables: `uuid`, `now` and `utcnow` are bare names too, and the evaluator
         * gives them the same precedence by expanding shorthand before it looks anything up.
         */
        private fun simplePart(expression: String): Part? {
            ShorthandTemplateExpander.generatorOf(expression)?.let { return Part.Generated(it) }
            return if (VARIABLE.matches(expression)) Part.Variable(expression) else null
        }

        /** A [Part.Variable] whose name a later phase reads from a capture is a [Part.Captured]. */
        private fun asCaptured(part: Part, captured: Set<String>): Part =
            when {
                part is Part.Variable && part.name in captured -> Part.Captured(part.name)
                part is Part.Assign && part.value is Part.Variable && (part.value as Part.Variable).name in captured ->
                    Part.Assign(part.name, Part.Captured((part.value as Part.Variable).name))
                else -> part
            }

        /**
         * Every captured name [part] reads, added to [into] in reading order.
         *
         * Recurses into an [Part.Assign], because `${'$'}{id = quoteId}` reads the capture exactly as
         * `${'$'}{quoteId}` does. A refusal that only looked at the top level rendered the assigned form as
         * the literal `${'$'}{quoteId}` and put it on the wire.
         */
        private fun collectCaptured(part: Part, into: MutableSet<String>) {
            when (part) {
                is Part.Captured -> into += part.name
                is Part.Assign -> collectCaptured(part.value, into)
                else -> Unit
            }
        }

        private fun renderParts(
            parts: List<Part>,
            scope: MutableMap<String, String>,
            captured: Map<String, String> = emptyMap(),
        ): String {
            if (parts.size == 1) return renderPart(parts[0], scope, captured)
            val sb = StringBuilder()
            for (part in parts) sb.append(renderPart(part, scope, captured))
            return sb.toString()
        }

        private fun renderPart(
            part: Part,
            scope: MutableMap<String, String>,
            captured: Map<String, String> = emptyMap(),
        ): String =
            when (part) {
                is Part.Text -> part.text
                is Part.Variable -> scope[part.name] ?: "\${${part.name}}"
                is Part.Generated -> generate(part.generator)
                // Already resolved for the whole message, or the message was refused. The placeholder is
                // reached only when the prototype is being built, where a placeholder is what is wanted.
                is Part.Captured -> captured[part.name] ?: "\${${part.name}}"
                is Part.Assign -> renderPart(part.value, scope, captured).also { scope[part.name] = it }
            }

        /**
         * **Renders the generators in [value] natively, once.** Anything else is left exactly as it stands.
         *
         * What a load set's seed goes through at start: `${'$'}{uuid:4}` becomes four hex characters, a
         * literal stays itself, and a `${'$'}{out.D.11}` that a seed has no business carrying is left alone
         * rather than silently emptied. The rendered map is what every phase carries and what every report
         * records, so the file says `${'$'}{uuid:4}` and the record says `b7f2`.
         */
        fun renderGenerators(value: String): String =
            if (!value.contains(EXPRESSION_OPENER)) {
                value
            } else {
                EXPRESSION.replace(value) { match ->
                    val generator = ShorthandTemplateExpander.generatorOf(match.groupValues[1].trim())
                    if (generator == null) match.value else generate(generator)
                }
            }

        /** The shorthand generators, rendered natively: what the expander's Kotlin would have produced. */
        fun generate(generator: Generator): String =
            when (generator) {
                is Generator.Uuid -> {
                    val id = UUID.randomUUID().toString()
                    generator.length?.let { id.replace("-", "").take(it) } ?: id
                }
                is Generator.Timestamp -> {
                    val now = if (generator.utc) LocalDateTime.now(ZoneOffset.UTC) else LocalDateTime.now()
                    shifted(now, generator).format(formatter(generator.pattern))
                }
                is Generator.Random -> random(generator)
            }

        /**
         * **A whole number of ticks above the low bound**, uniform over the closed range.
         *
         * The bounds arrive already at the generator's decimals, so the draw is over the width plus one
         * tick and the offset is *floored* onto a tick: both bounds are answers, and no answer leaves the
         * band. Rounding a draw from the bare width did neither, and `random:0.15:2.85:0` quoted 0 and 3.
         *
         * `ThreadLocalRandom` because a burst renders on one thread per lane, and `BigDecimal` because the
         * value goes on a wire as a price: the quantisation is the point, not a rounding of a double.
         */
        private fun random(g: Generator.Random): String =
            BigDecimal
                .valueOf(ThreadLocalRandom.current().nextDouble())
                .multiply(g.drawSpan())
                .setScale(g.decimals, RoundingMode.FLOOR)
                .add(g.min)
                .toPlainString()

        @Suppress("CyclomaticComplexMethod")
        private fun shifted(now: LocalDateTime, g: Generator.Timestamp): LocalDateTime {
            val amount = g.amount ?: return now
            val unit = g.unit?.lowercase() ?: return now
            val plus = g.sign != "-"
            return when (unit) {
                "s" -> if (plus) now.plusSeconds(amount) else now.minusSeconds(amount)
                "min" -> if (plus) now.plusMinutes(amount) else now.minusMinutes(amount)
                "h" -> if (plus) now.plusHours(amount) else now.minusHours(amount)
                "d" -> if (plus) now.plusDays(amount) else now.minusDays(amount)
                "w" -> if (plus) now.plusWeeks(amount) else now.minusWeeks(amount)
                "m" -> if (plus) now.plusMonths(amount) else now.minusMonths(amount)
                "y" -> if (plus) now.plusYears(amount) else now.minusYears(amount)
                else -> now
            }
        }

        /** Formatters are immutable and cost real work to build, so each pattern is built once per process. */
        private val formatters = java.util.concurrent.ConcurrentHashMap<String, DateTimeFormatter>()

        private fun formatter(pattern: String?): DateTimeFormatter =
            formatters.computeIfAbsent(pattern ?: ShorthandTemplateExpander.DEFAULT_TIMESTAMP_PATTERN) { DateTimeFormatter.ofPattern(it) }
    }
}
