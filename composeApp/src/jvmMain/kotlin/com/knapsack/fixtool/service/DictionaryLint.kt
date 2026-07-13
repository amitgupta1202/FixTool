package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter

/**
 * Flags tags a message carries that the **loaded dictionary does not define for that message type**.
 *
 * Why: message construction, group building, and assertion seeding all follow the loaded dictionary.
 * A tag the dictionary doesn't know (e.g. sending a standard-FIX44 grouped QuoteRequest while a
 * venue dialect that defines QuoteRequest flat is loaded) is silently sent as a plain top-level
 * field — and the only symptom is a cryptic counterparty reject. This lint names the mismatch
 * locally, at send/author time.
 */
object DictionaryLint {
    /** Tags not defined for the message's type in the loaded dictionary (top-level only; fields the
     *  walker placed inside a known group are by definition known). Empty when no dictionary. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    fun unknownTags(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): List<Int> {
        val dd = try {
            dictionary?.getDataDictionary()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val msgType = fields.firstOrNull { it.first == 35 }?.second ?: return emptyList()
        val knownType = try {
            dd.isMsgType(msgType)
        } catch (e: Exception) {
            false
        }
        if (!knownType) return emptyList()
        return FixStructure.walk(fields, dictionary)
            .filter { it.groupTag == null }
            .map { it.tag }
            .distinct()
            .filter { it != 35 }
            .filterNot { tag ->
                try {
                    dd.isHeaderField(tag) || dd.isTrailerField(tag) || dd.isMsgField(msgType, tag) || dd.isGroup(msgType, tag)
                } catch (e: Exception) {
                    true // benefit of the doubt: never flag on dictionary errors
                }
            }
    }

    /** The tag numbers a QuickFIX complaint names, e.g. "…, field=200" → {200}. */
    private val FIELD_IN_PROBLEM = Regex("""field=(\d+)""")

    /**
     * True when [problem] complains about nothing but tags [unknownTags] has already named — in which
     * case saying it again reads as two problems where there is one.
     *
     * Whole tag numbers, not substrings. `problem.contains("field=$tag")` looks equivalent and is not:
     * an unknown tag **20** matches a complaint about `field=200`, so the one real thing wrong with
     * the message was suppressed by a lint about an entirely different field, and the send reported
     * no problem at all. A complaint survives unless *every* tag it names is one we already named.
     */
    fun alreadyNamed(problem: String, unknownTags: List<Int>): Boolean {
        val named = FIELD_IN_PROBLEM.findAll(problem).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return named.isNotEmpty() && named.all { it in unknownTags }
    }

    /** Human warning, e.g. "tags not defined for QuoteRequest (R) in the loaded dictionary: 146 NoRelatedSym". */
    fun describe(tags: List<Int>, fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): String {
        val msgType = fields.firstOrNull { it.first == 35 }?.second ?: "?"
        val typeName = dictionary?.getFieldEnumValues(35)?.firstOrNull { it.first == msgType }?.second
        val typeLabel = if (typeName != null) "$typeName ($msgType)" else msgType
        val tagList = tags.joinToString(", ") { tag ->
            val name = dictionary?.getFieldName(tag)
            if (name != null) "$tag $name" else "$tag"
        }
        return "tags not defined for $typeLabel in the loaded dictionary: $tagList — " +
            "they are sent as plain top-level fields, which the counterparty may reject"
    }
}
