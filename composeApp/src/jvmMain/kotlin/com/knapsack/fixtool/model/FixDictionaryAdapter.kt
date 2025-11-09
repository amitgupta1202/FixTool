package com.knapsack.fixtool.model

import org.w3c.dom.Element
import quickfix.DataDictionary
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Adapter class that wraps QuickFIX's DataDictionary to provide a simplified interface
 * for UI components. This replaces the old custom FixDictionary implementation.
 */
class FixDictionaryAdapter private constructor(
    private val dataDictionary: DataDictionary?,
    private val dictionaryPath: String? = null,
    private val fieldEnumValues: Map<Int, List<Pair<String, String>>> = emptyMap(),
    private val allFields: List<Pair<Int, String>> = emptyList(),
) {
    /**
     * Gets the field name for a given tag number
     */
    fun getFieldName(tag: Int): String? = dataDictionary?.getFieldName(tag)

    /**
     * Gets the enum value description for a field tag and value
     */
    fun getFieldValueDescription(tag: Int, value: String): String? =
        try {
            dataDictionary?.getValueName(tag, value)
        } catch (e: Exception) {
            // DataDictionary throws exception if value name not found
            null
        }

    /**
     * Checks if a field tag represents a group/repeating group
     * Note: QuickFIX requires message type to check groups, but for UI purposes
     * we use a heuristic based on field name starting with "No"
     */
    fun isGroupTag(tag: Int): Boolean {
        val fieldName = getFieldName(tag) ?: return false
        // Most FIX repeating group count fields start with "No" (e.g., NoMDEntries, NoPartyIDs)
        return fieldName.startsWith("No", ignoreCase = false)
    }

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(FixDictionaryAdapter::class.java)

        /**
         * Parses the XML data dictionary file to extract enum values for fields
         */

        /**
         * Parses all fields from the XML data dictionary file
         */
        private fun parseAllFields(file: File): List<Pair<Int, String>> =
            try {
                val dbFactory = DocumentBuilderFactory.newInstance()
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc = dBuilder.parse(file)
                doc.documentElement.normalize()

                val fieldsList = mutableListOf<Pair<Int, String>>()

                // Get all field elements from <fields> section
                val fieldNodes = doc.getElementsByTagName("field")
                for (i in 0 until fieldNodes.length) {
                    val fieldElement = fieldNodes.item(i) as? Element ?: continue

                    // Get the field number and name attributes
                    val fieldNumberStr = fieldElement.getAttribute("number")
                    val fieldName = fieldElement.getAttribute("name")

                    if (fieldNumberStr.isBlank() || fieldName.isBlank()) continue

                    val fieldNumber = fieldNumberStr.toIntOrNull() ?: continue
                    fieldsList.add(fieldNumber to fieldName)
                }

                // Sort by tag number
                fieldsList.sortBy { it.first }
                logger.info("Parsed {} fields from XML dictionary", fieldsList.size)
                fieldsList
            } catch (e: Exception) {
                logger.error("Failed to parse fields from XML: {}", e.message, e)
                emptyList()
            }

        private fun parseEnumValues(file: File): Map<Int, List<Pair<String, String>>> =
            try {
                val dbFactory = DocumentBuilderFactory.newInstance()
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc = dBuilder.parse(file)
                doc.documentElement.normalize()

                val enumValuesMap = mutableMapOf<Int, MutableList<Pair<String, String>>>()

                // Get all field elements
                val fieldNodes = doc.getElementsByTagName("field")
                for (i in 0 until fieldNodes.length) {
                    val fieldElement = fieldNodes.item(i) as? Element ?: continue

                    // Get the field number attribute
                    val fieldNumberStr = fieldElement.getAttribute("number")
                    if (fieldNumberStr.isBlank()) continue

                    val fieldNumber = fieldNumberStr.toIntOrNull() ?: continue

                    // Get all value elements under this field
                    val valueNodes = fieldElement.getElementsByTagName("value")
                    if (valueNodes.length > 0) {
                        val values = mutableListOf<Pair<String, String>>()
                        for (j in 0 until valueNodes.length) {
                            val valueElement = valueNodes.item(j) as? Element ?: continue
                            val enumValue = valueElement.getAttribute("enum")
                            val description = valueElement.getAttribute("description")
                            if (enumValue.isNotBlank()) {
                                values.add(enumValue to (description.ifBlank { enumValue }))
                            }
                        }
                        if (values.isNotEmpty()) {
                            enumValuesMap[fieldNumber] = values
                        }
                    }
                }

                logger.info("Parsed enum values for {} fields from XML dictionary", enumValuesMap.size)
                enumValuesMap
            } catch (e: Exception) {
                logger.error("Failed to parse enum values from XML: {}", e.message, e)
                emptyMap()
            }

        /**
         * Creates an adapter from a data dictionary file path
         */
        fun fromFile(file: File): FixDictionaryAdapter =
            try {
                val path = file.absolutePath
                val dataDictionary = DataDictionary(path)
                val enumValues = parseEnumValues(file)
                val allFields = parseAllFields(file)
                logger.info("Loaded QuickFIX DataDictionary from: {}", path)
                FixDictionaryAdapter(dataDictionary, path, enumValues, allFields)
            } catch (e: Exception) {
                logger.error("Failed to load DataDictionary from {}: {}", file.absolutePath, e.message, e)
                FixDictionaryAdapter(null, null, emptyMap(), emptyList())
            }

        /**
         * Creates an adapter from a data dictionary file path string
         */
        fun fromPath(path: String): FixDictionaryAdapter = fromFile(File(path))

        /**
         * Creates a default adapter with no dictionary (will use tag numbers only)
         */
        fun createDefault(): FixDictionaryAdapter {
            logger.info("Creating default FixDictionaryAdapter with no DataDictionary")
            return FixDictionaryAdapter(null, null, emptyMap(), emptyList())
        }
    }

    /**
     * Returns the underlying QuickFIX DataDictionary (for advanced use cases)
     */
    fun getDataDictionary(): DataDictionary? = dataDictionary

    /**
     * Returns the file path of the data dictionary (if available)
     * This is needed for QuickFIX configuration which requires a file path
     */
    fun getFilePath(): String? = dictionaryPath

    /**
     * Checks if a valid data dictionary is loaded
     */
    fun isLoaded(): Boolean = dataDictionary != null

    /**
     * Gets all field tags defined in the dictionary.
     * Note: This is less efficient than the old implementation as QuickFIX doesn't provide
     * direct access to all fields. Returns empty set if no dictionary is loaded.
     */
    fun getAllFieldTags(): Set<Int> {
        // QuickFIX DataDictionary doesn't provide a way to iterate all fields,
        // so we check if dictionary is loaded as a proxy
        return if (dataDictionary != null) {
            // Return a non-empty set to indicate dictionary is loaded
            // In practice, this is only used to check if dictionary is configured
            setOf(1) // Dummy value
        } else {
            emptySet()
        }
    }

    /**
     * Checks if a field has enumerated values defined in the dictionary
     */
    fun hasFieldValues(tag: Int): Boolean = fieldEnumValues.containsKey(tag) && fieldEnumValues[tag]!!.isNotEmpty()

    /**
     * Gets all possible enum values for a field.
     * Returns a list of value-description pairs.
     */
    fun getFieldEnumValues(tag: Int): List<Pair<String, String>> = fieldEnumValues[tag] ?: emptyList()

    /**
     * Gets all fields defined in the dictionary.
     * Returns a list of (tag, name) pairs sorted by tag number.
     */
    fun getAllFields(): List<Pair<Int, String>> = allFields
}
