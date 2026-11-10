package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.FixDictionaryAdapter

/**
 * Flags tags a message carries **where the loaded dictionary does not define them**: for the message type at
 * the top level, and for the group whose entry holds them inside one.
 *
 * Why: message construction, group building, and assertion seeding all follow the loaded dictionary.
 * A tag the dictionary doesn't know (e.g. sending a standard-FIX44 grouped QuoteRequest while a
 * venue dialect that defines QuoteRequest flat is loaded) is sent as a plain top-level field, and the only
 * symptom is a cryptic counterparty reject. A tag a venue sends in an entry its group does not define is
 * kept in that entry, and is the same mismatch one level down. This lint names both locally, at send and
 * author time, and says where each one goes, read from the walk the send builds the message by
 * (see [FixStructure]).
 */
object DictionaryLint {
    /** One field the dictionary does not define where it sits: its row, its tag, and its group (null at top level). */
    data class Undefined(
        val row: Int,
        val tag: Int,
        val group: Int?,
    )

    /**
     * Every field of [fields] the dictionary does not define where it is sent, in row order. Empty when there
     * is no dictionary, or the message type is not one it defines. [counts] is passed to [FixStructure.walk].
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    internal fun undefined(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
        counts: GroupWalk.Counts = GroupWalk.Counts.POSITIVE,
    ): List<Undefined> {
        val dd =
            try {
                dictionary?.getDataDictionary()
            } catch (e: Exception) {
                null
            } ?: return emptyList()
        val msgType = fields.firstOrNull { it.first == 35 }?.second ?: return emptyList()
        val knownType =
            try {
                dd.isMsgType(msgType)
            } catch (e: Exception) {
                false
            }
        if (!knownType) return emptyList()
        return FixStructure
            .walk(fields, dictionary, counts)
            .filter { !it.defined && it.tag != 35 }
            .map { Undefined(it.row, it.tag, it.inGroup) }
    }

    /** The tags [undefined] names, each once, in the order they first appear. */
    fun unknownTags(
        fields: List<Pair<Int, String>>,
        dictionary: FixDictionaryAdapter?,
    ): List<Int> = undefined(fields, dictionary).map { it.tag }.distinct()

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

    /**
     * Human warning, e.g. "tags not defined for QuoteRequest (R) in the loaded dictionary: 146 NoRelatedSym at
     * the top level. They are sent as written, which the counterparty may reject". Each tag is named with where
     * it goes, once per place.
     */
    fun describe(tags: List<Int>, fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter?): String {
        val msgType = fields.firstOrNull { it.first == 35 }?.second ?: "?"
        val typeName = dictionary?.getFieldEnumValues(35)?.firstOrNull { it.first == msgType }?.second
        val typeLabel = if (typeName != null) "$typeName ($msgType)" else msgType
        val places = undefined(fields, dictionary).distinctBy { it.tag to it.group }.groupBy { it.tag }
        val tagList =
            tags
                .flatMap { tag ->
                    val named = nameOf(tag, dictionary)
                    places[tag]?.map { "$named ${where(it.group, dictionary)}" } ?: listOf(named)
                }.joinToString(", ")
        return "tags not defined for $typeLabel in the loaded dictionary: $tagList. " +
            "They are sent as written, which the counterparty may reject"
    }

    /** "at the top level", or "in NoAllocs (78)". */
    fun where(
        group: Int?,
        dictionary: FixDictionaryAdapter?,
    ): String {
        if (group == null) return "at the top level"
        val name = dictionary?.getFieldName(group)
        return if (name != null) "in $name ($group)" else "in group $group"
    }

    private fun nameOf(
        tag: Int,
        dictionary: FixDictionaryAdapter?,
    ): String {
        val name = dictionary?.getFieldName(tag)
        return if (name != null) "$tag $name" else "$tag"
    }
}
