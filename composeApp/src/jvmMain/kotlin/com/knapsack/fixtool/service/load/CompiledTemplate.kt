package com.knapsack.fixtool.service.load

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessageManual
import com.knapsack.fixtool.service.ShorthandTemplateExpander
import com.knapsack.fixtool.service.ShorthandTemplateExpander.Generator
import quickfix.Message
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

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
     * **One lane's prototype**: the `Once` slots evaluated through [resolveOnce], the literals in place, and
     * the per-message slots left to [LanePrototype.render].
     *
     * The lane's four names win over a seed of the same name, because the lane's identity is the whole
     * reason `${sessionIndex}` exists and a seed that collides with it is a mistake, not an override.
     */
    fun prepare(
        lane: Lane,
        seed: Map<String, String>,
        dictionary: FixDictionaryAdapter,
        resolveOnce: (template: String) -> String,
    ): LanePrototype {
        val laneScope = seed + lane.seed()
        val placeholderScope = HashMap(laneScope).also { it[MESSAGE_INDEX] = "0" }
        val resolved =
            slots.map { slot ->
                slot.tag to
                    when (slot) {
                        is Slot.Literal -> slot.value
                        is Slot.Once -> resolveOnce(slot.template)
                        is Slot.PerMessage -> renderParts(slot.parts, placeholderScope)
                    }
            }
        val raw = resolved.joinToString("|") { "${it.first}=${it.second}" } + "|"
        val prototype = if (dictionary.getDataDictionary() != null) raw.toQuickFixMessageManual(dictionary) else raw.toQuickFixMessage()
        val perMessage = slots.filterIsInstance<Slot.PerMessage>()
        val headerTags = perMessage.map { it.tag }.filter { prototype.header.isSetField(it) }.toSet()
        return LanePrototype(prototype, perMessage, laneScope, headerTags)
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
    ) {
        /** [messageIndex] is 1-based, so `${messageIndex}` counts the way "4,000 issued" counts. */
        fun render(messageIndex: Int): Message {
            val message = prototype.clone() as Message
            val scope = HashMap(laneScope)
            scope[MESSAGE_INDEX] = messageIndex.toString()
            for (slot in perMessage) {
                val value = renderParts(slot.parts, scope)
                if (slot.tag in headerTags) message.header.setString(slot.tag, value) else message.setString(slot.tag, value)
            }
            return message
        }
    }

    companion object {
        const val MESSAGE_INDEX = "messageIndex"

        private val EXPRESSION = """\$\{([^}]+)}""".toRegex()
        private val VARIABLE = """^[a-zA-Z_][a-zA-Z0-9_]*$""".toRegex()
        private val ASSIGNMENT = """^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(.+)$""".toRegex()

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

        private fun renderParts(parts: List<Part>, scope: MutableMap<String, String>): String {
            if (parts.size == 1) return renderPart(parts[0], scope)
            val sb = StringBuilder()
            for (part in parts) sb.append(renderPart(part, scope))
            return sb.toString()
        }

        private fun renderPart(part: Part, scope: MutableMap<String, String>): String =
            when (part) {
                is Part.Text -> part.text
                is Part.Variable -> scope[part.name] ?: "\${${part.name}}"
                is Part.Generated -> generate(part.generator)
                is Part.Assign -> renderPart(part.value, scope).also { scope[part.name] = it }
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
            }

        @Suppress("CyclomaticComplexMethod")
        private fun shifted(now: LocalDateTime, g: Generator.Timestamp): LocalDateTime {
            val amount = g.amount ?: return now
            val unit = g.unit?.lowercase() ?: return now
            val plus = g.sign != "-"
            return when (unit) {
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
