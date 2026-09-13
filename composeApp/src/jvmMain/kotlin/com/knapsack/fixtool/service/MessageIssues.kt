package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter
import com.knapsack.fixtool.service.FixMessageHelper.toQuickFixMessage
import quickfix.DataDictionary
import quickfix.FieldException
import quickfix.IncorrectDataFormat
import quickfix.IncorrectTagValue
import quickfix.StringField
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * **One thing wrong with a message, and where it is.**
 *
 * The editor used to hold what was wrong as strings, and the strings carried their structure in their
 * wording: a `WARNING:` prefix was the severity, so a list with one warning in it was titled "warnings"
 * whatever else it held, and a header line such as "Cannot send message:" was counted as one of the problems
 * it introduced. A field is a field here and a severity is a severity, so neither can be misread.
 */
data class MessageIssue(
    val severity: Severity,
    val text: String,
    /** The tag the issue is about, or null when it is about the message as a whole. */
    val tag: Int? = null,
    /** The row that carries it, as an index into the fields the check was handed, or null when no row does. */
    val row: Int? = null,
    /** A required field the message does not carry: there is no row to point at, only one to add. */
    val missing: Boolean = false,
) {
    enum class Severity { ERROR, WARNING }
}

/**
 * **Everything wrong with a message, with a title when an action refused it.**
 *
 * @param headline what the problems are the reason for — "Cannot send message" — or null for a plain check
 */
data class IssueReport(
    val headline: String? = null,
    val issues: List<MessageIssue> = emptyList(),
) {
    val errors: Int get() = issues.count { it.severity == MessageIssue.Severity.ERROR }
    val warnings: Int get() = issues.count { it.severity == MessageIssue.Severity.WARNING }
    val isEmpty: Boolean get() = issues.isEmpty()

    /** Errors first, then warnings, each in the order of the rows they sit on; a problem said twice, once. */
    operator fun plus(other: IssueReport): IssueReport =
        IssueReport(
            headline = headline ?: other.headline,
            issues = (issues + other.issues).inReadingOrder(),
        )
}

/** Errors before warnings, each in row order with the rowless last; a problem said twice is said once. */
internal fun List<MessageIssue>.inReadingOrder(): List<MessageIssue> =
    distinctBy { it.tag to it.text }.sortedWith(compareBy({ it.severity.ordinal }, { it.row ?: Int.MAX_VALUE }))

/**
 * **Every problem in a message, not the first one.**
 *
 * The editor's Validate handed the message to QuickFIX/J's `DataDictionary.validate`, which throws on the
 * first problem it meets. So "1 error" meant *at least one*, and a badge counting them counted exceptions
 * rather than problems: five wrong fields read as one. This checks each field on its own against the same
 * dictionary, and then asks QuickFIX/J once more for whatever a field-by-field check cannot see (the shape
 * of a repeating group), keeping that answer only when it names something not already named.
 *
 * **The rules are QuickFIX/J's own**, not a second opinion. Whether a value is one the field allows is
 * `DataDictionary.isFieldValue`, and whether it has the field's format is the dictionary's own format check,
 * reached by reflection because QuickFIX/J keeps it private. A hand-written copy of that switch would be a
 * validator that could disagree with the one the counterparty's engine runs. [formatRuleAvailable] says
 * whether the reflection found it; a test holds it true, so a QuickFIX/J upgrade that renames it fails a
 * build rather than quietly dropping the format check.
 */
object MessageIssues {
    /** One field as a check sees it: the row it came from, its tag, and the value that will be sent. */
    data class Field(
        val row: Int,
        val tag: Int,
        val value: String,
    )

    /** QuickFIX/J's private format check: INT is an integer, PRICE a decimal, UTCTIMESTAMP a timestamp. */
    private val checkValidFormat: Method? =
        runCatching {
            DataDictionary::class.java.getDeclaredMethod("checkValidFormat", StringField::class.java).apply {
                isAccessible = true
            }
        }.getOrNull()

    /** Whether the format check could be reached. See the class comment. */
    val formatRuleAvailable: Boolean get() = checkValidFormat != null

    /** A value still to be written by an expression is judged by the value it resolves to, at send time. */
    private fun isExpression(value: String): Boolean = "\${" in value

    /**
     * What is wrong with [fields], errors first.
     *
     * Nothing without a loaded dictionary: there is nothing to check against, and the caller that needs a
     * dictionary to proceed says so in its own words.
     */
    @Suppress("ReturnCount")
    fun check(
        fields: List<Field>,
        dictionary: FixDictionaryAdapter?,
    ): List<MessageIssue> {
        val dd = dictionary?.takeIf { it.isLoaded() }?.getDataDictionary() ?: return emptyList()
        val msgTypeField = fields.firstOrNull { it.tag == MSG_TYPE }
        if (msgTypeField == null || msgTypeField.value.isBlank()) {
            return listOf(
                MessageIssue(
                    MessageIssue.Severity.ERROR,
                    "MsgType(35) is missing, so nothing says what this message is",
                    tag = MSG_TYPE,
                    row = msgTypeField?.row,
                    missing = msgTypeField == null,
                ),
            )
        }
        val msgType = msgTypeField.value
        if (isExpression(msgType)) return emptyList()
        if (!runCatching { dd.isMsgType(msgType) }.getOrDefault(false)) {
            return listOf(
                MessageIssue(
                    MessageIssue.Severity.WARNING,
                    "35=$msgType is not a message type the loaded dictionary defines, " +
                        "so nothing else here can be checked",
                    tag = MSG_TYPE,
                    row = msgTypeField.row,
                ),
            )
        }
        val typeLabel = typeLabel(msgType, dictionary)
        val issues = mutableListOf<MessageIssue>()
        issues += unknownTags(fields, dictionary, typeLabel)
        fields.forEach { field -> valueIssue(field, dd, dictionary)?.let(issues::add) }
        issues += missingRequired(fields, dd, dictionary, msgType, typeLabel)
        structuralIssue(fields, dd, issues)?.let(issues::add)
        return issues.inReadingOrder()
    }

    /** Top-level tags the dictionary does not define for this message type. A warning: the message still goes. */
    private fun unknownTags(
        fields: List<Field>,
        dictionary: FixDictionaryAdapter,
        typeLabel: String,
    ): List<MessageIssue> =
        DictionaryLint.unknownTags(fields.map { it.tag to it.value }, dictionary).map { tag ->
            MessageIssue(
                MessageIssue.Severity.WARNING,
                "not defined for $typeLabel in the loaded dictionary, so it is sent as a plain top-level field " +
                    "the counterparty may reject",
                tag = tag,
                row = fields.firstOrNull { it.tag == tag }?.row,
            )
        }

    /** A value the field does not allow, or one without the field's format. Either is an error. */
    private fun valueIssue(
        field: Field,
        dd: DataDictionary,
        dictionary: FixDictionaryAdapter,
    ): MessageIssue? {
        val text =
            when {
                field.value.isBlank() || isExpression(field.value) -> null
                hasFormatProblem(dd, field) -> {
                    val type = runCatching { dd.getFieldType(field.tag) }.getOrNull()
                    "“${field.value}” is not ${typeWords(type?.name)}"
                }
                !isAllowedValue(dd, field) -> notAllowed(field, dictionary)
                else -> null
            }
        return text?.let { MessageIssue(MessageIssue.Severity.ERROR, it, tag = field.tag, row = field.row) }
    }

    /** "“X” is not a value Side allows (1 BUY, 2 SELL, 3 BUY_MINUS, 4 SELL_PLUS, …)". */
    private fun notAllowed(
        field: Field,
        dictionary: FixDictionaryAdapter,
    ): String {
        val choices = dictionary.declaredEnumValues(field.tag)
        val listed =
            choices.take(ENUM_CHOICES_SHOWN).joinToString(", ") { (value, description) -> "$value $description" } +
                if (choices.size > ENUM_CHOICES_SHOWN) ", …" else ""
        val name = dictionary.getFieldName(field.tag) ?: "this field"
        return "“${field.value}” is not a value $name allows" + if (listed.isNotBlank()) " ($listed)" else ""
    }

    private fun hasFormatProblem(
        dd: DataDictionary,
        field: Field,
    ): Boolean {
        val check = checkValidFormat ?: return false
        val outcome = runCatching { check.invoke(dd, StringField(field.tag, field.value)) }
        return (outcome.exceptionOrNull() as? InvocationTargetException)?.targetException is IncorrectDataFormat
    }

    /**
     * Body fields the message type requires and the message does not carry at its top level.
     *
     * Header and trailer fields are the session's to write, so they are never missing from a body. A field a
     * repeating group requires is left to [structuralIssue], because whether it is missing depends on which
     * entry it is missing from.
     */
    private fun missingRequired(
        fields: List<Field>,
        dd: DataDictionary,
        dictionary: FixDictionaryAdapter,
        msgType: String,
        typeLabel: String,
    ): List<MessageIssue> {
        val topLevel =
            FixStructure
                .walk(fields.map { it.tag to it.value }, dictionary)
                .filter { it.groupTag == null }
                .map { it.tag }
                .toSet()
        return dictionary
            .getAllFields()
            .map { it.first }
            .filter { tag ->
                tag !in topLevel &&
                    runCatching {
                        dd.isRequiredField(msgType, tag) && !dd.isHeaderField(tag) && !dd.isTrailerField(tag)
                    }.getOrDefault(false)
            }.map { tag ->
                MessageIssue(
                    MessageIssue.Severity.ERROR,
                    "${dictionary.getFieldName(tag) ?: "Tag $tag"}($tag) is required for $typeLabel",
                    tag = tag,
                    missing = true,
                )
            }
    }

    /**
     * QuickFIX/J's own verdict, kept only when it names something the checks above did not.
     *
     * Fields still carrying an expression are left out of the message it judges, and a complaint about one of
     * their tags is dropped, because the value it would judge is not the value that will be sent.
     */
    private fun structuralIssue(
        fields: List<Field>,
        dd: DataDictionary,
        found: List<MessageIssue>,
    ): MessageIssue? {
        val expressionTags = fields.filter { isExpression(it.value) }.map { it.tag }.toSet()
        val judged = fields.filterNot { isExpression(it.value) || it.value.isBlank() }
        val raw = judged.joinToString("|") { "${it.tag}=${it.value}" } + "|"
        val problem =
            runCatching { dd.validate(raw.toQuickFixMessage(dd, validate = false), true) }.exceptionOrNull()
                ?: return null
        val tag =
            when (problem) {
                is FieldException -> problem.field
                is IncorrectTagValue -> problem.field
                is IncorrectDataFormat -> problem.field
                else ->
                    FIELD_IN_PROBLEM
                        .find(problem.message.orEmpty())
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
            }?.takeIf { it > 0 }
        if (tag != null && (tag in expressionTags || found.any { it.tag == tag })) return null
        return MessageIssue(
            MessageIssue.Severity.ERROR,
            problem.message ?: problem.javaClass.simpleName,
            tag = tag,
            row = tag?.let { t -> fields.firstOrNull { it.tag == t }?.row },
        )
    }

    /**
     * The problems an action has already reported as lines of text, as issues.
     *
     * The send and apply paths report in sentences, and some of them are read back by tests and by the control
     * surface in exactly those words, so the sentences stay and are read here: a line ending in a colon is the
     * headline the rest are the reason for, `WARNING:` is a warning, and `Field 11: …` is about tag 11.
     */
    fun fromLines(lines: List<String>): IssueReport {
        var headline: String? = null
        val issues =
            lines.mapIndexedNotNull { index, raw ->
                val line = raw.removePrefix("❌").trim()
                when {
                    index == 0 && line.endsWith(":") -> {
                        headline = line.removeSuffix(":").replace(" - ", ": ")
                        null
                    }
                    line.startsWith("WARNING:") ->
                        MessageIssue(MessageIssue.Severity.WARNING, line.removePrefix("WARNING:").trim())
                    else -> {
                        val field = FIELD_PREFIX.find(line)
                        MessageIssue(
                            MessageIssue.Severity.ERROR,
                            field?.let { line.substring(it.range.last + 1).trim() } ?: line,
                            tag =
                                field?.groupValues?.get(1)?.toIntOrNull() ?: FIELD_IN_PROBLEM
                                    .find(line)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toIntOrNull(),
                        )
                    }
                }
            }
        return IssueReport(headline, issues)
    }

    private const val MSG_TYPE = 35
    private const val ENUM_CHOICES_SHOWN = 4
    private val FIELD_IN_PROBLEM = Regex("""field=(\d+)""")
    private val FIELD_PREFIX = Regex("""^Field (\d+):""")
}

/**
 * `DataDictionary.isFieldValue`, which splits a multiple-value field itself. A field with no list allows anything.
 */
private fun isAllowedValue(
    dd: DataDictionary,
    field: MessageIssues.Field,
): Boolean =
    runCatching { !dd.hasFieldValue(field.tag) || dd.isFieldValue(field.tag, field.value) }.getOrDefault(true)

/** "NewOrderSingle (D)" where the dictionary names the type, the bare code where it does not. */
private fun typeLabel(
    msgType: String,
    dictionary: FixDictionaryAdapter,
): String =
    dictionary
        .getFieldEnumValues(35)
        .firstOrNull { it.first == msgType }
        ?.second
        ?.let { "$it ($msgType)" } ?: msgType

/** A field type as a sentence can end with it: "a price", "a UTC timestamp (yyyyMMdd-HH:mm:ss)". */
private fun typeWords(type: String?): String =
    when (type) {
        "INT", "LENGTH", "SEQNUM", "NUMINGROUP", "TAGNUM", "DAYOFMONTH" -> "a whole number"
        "QTY" -> "a quantity"
        "PRICE", "PRICEOFFSET" -> "a price"
        "AMT" -> "an amount"
        "FLOAT", "PERCENTAGE" -> "a number"
        "BOOLEAN" -> "Y or N"
        "CHAR" -> "a single character"
        "UTCTIMESTAMP", "TIME" -> "a UTC timestamp (yyyyMMdd-HH:mm:ss)"
        "UTCDATEONLY", "UTCDATE" -> "a UTC date (yyyyMMdd)"
        "UTCTIMEONLY" -> "a UTC time (HH:mm:ss)"
        null -> "in this field's format"
        else -> "a ${type.lowercase()}"
    }
