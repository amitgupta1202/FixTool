package com.knapsack.fixtool.model

import org.w3c.dom.Element
import quickfix.DataDictionary
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Adapter class that wraps QuickFIX's DataDictionary to provide a simplified interface
 * for UI components. Supports both FIX 4.x and FIX 5.0+ (with FIXT.1.1 transport layer).
 */
class FixDictionaryAdapter private constructor(
    private val dataDictionary: DataDictionary?,
    private val dictionaryPath: String? = null,
    private val fieldEnumValues: Map<Int, List<Pair<String, String>>> = emptyMap(),
    private val allFields: List<Pair<Int, String>> = emptyList(),
    /** The FIX version this dictionary represents */
    val fixVersion: FixVersion = FixVersion.DEFAULT,
    /** Transport dictionary for FIXT.1.1 sessions (FIX 5.0+), null for FIX 4.x */
    private val transportDictionary: DataDictionary? = null,
    private val transportDictionaryPath: String? = null,
) {
    /**
     * Gets the field name for a given tag number
     */
    fun getFieldName(tag: Int): String? {
        // First check the application dictionary
        val appName = dataDictionary?.getFieldName(tag)
        if (appName != null) return appName

        // For FIX 5.0+, also check transport dictionary for header/trailer fields
        return transportDictionary?.getFieldName(tag)
    }

    /**
     * Gets the enum value description for a field tag and value
     */
    fun getFieldValueDescription(tag: Int, value: String): String? =
        try {
            dataDictionary?.getValueName(tag, value)
                ?: transportDictionary?.getValueName(tag, value)
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

        /** Default bundled dictionary resource path */
        const val BUNDLED_FIX44_RESOURCE = "/dictionaries/FIX44.xml"

        /** Cache for loaded dictionaries to avoid repeated parsing */
        private val dictionaryCache = ConcurrentHashMap<String, FixDictionaryAdapter>()

        /** Cache for temp files created from resources */
        private val tempFileCache = ConcurrentHashMap<String, File>()

        /**
         * Clears the dictionary cache. Useful for testing or when dictionaries are updated.
         */
        fun clearCache() {
            dictionaryCache.clear()
            tempFileCache.values.forEach { it.delete() }
            tempFileCache.clear()
            logger.info("Dictionary cache cleared")
        }

        /**
         * Parses all fields from an InputStream
         */
        private fun parseAllFields(inputStream: InputStream): List<Pair<Int, String>> =
            try {
                val dbFactory = DocumentBuilderFactory.newInstance()
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc = dBuilder.parse(inputStream)
                doc.documentElement.normalize()

                val fieldsList = mutableListOf<Pair<Int, String>>()

                val fieldNodes = doc.getElementsByTagName("field")
                for (i in 0 until fieldNodes.length) {
                    val fieldElement = fieldNodes.item(i) as? Element ?: continue
                    val fieldNumberStr = fieldElement.getAttribute("number")
                    val fieldName = fieldElement.getAttribute("name")
                    if (fieldNumberStr.isBlank() || fieldName.isBlank()) continue
                    val fieldNumber = fieldNumberStr.toIntOrNull() ?: continue
                    fieldsList.add(fieldNumber to fieldName)
                }

                fieldsList.sortBy { it.first }
                logger.info("Parsed {} fields from XML dictionary stream", fieldsList.size)
                fieldsList
            } catch (e: Exception) {
                logger.error("Failed to parse fields from XML stream: {}", e.message, e)
                emptyList()
            }

        /**
         * Parses enum values from an InputStream
         */
        private fun parseEnumValues(inputStream: InputStream): Map<Int, List<Pair<String, String>>> =
            try {
                val dbFactory = DocumentBuilderFactory.newInstance()
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc = dBuilder.parse(inputStream)
                doc.documentElement.normalize()

                val enumValuesMap = mutableMapOf<Int, MutableList<Pair<String, String>>>()

                val fieldNodes = doc.getElementsByTagName("field")
                for (i in 0 until fieldNodes.length) {
                    val fieldElement = fieldNodes.item(i) as? Element ?: continue
                    val fieldNumberStr = fieldElement.getAttribute("number")
                    if (fieldNumberStr.isBlank()) continue
                    val fieldNumber = fieldNumberStr.toIntOrNull() ?: continue

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

                logger.info("Parsed enum values for {} fields from XML dictionary stream", enumValuesMap.size)
                enumValuesMap
            } catch (e: Exception) {
                logger.error("Failed to parse enum values from XML stream: {}", e.message, e)
                emptyMap()
            }

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
         * Detects the FIX version from a dictionary file by reading its header.
         * @param file The dictionary XML file
         * @return The detected FixVersion, or DEFAULT if detection fails
         */
        fun detectVersionFromFile(file: File): FixVersion =
            try {
                val dbFactory = DocumentBuilderFactory.newInstance()
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc = dBuilder.parse(file)
                doc.documentElement.normalize()

                val root = doc.documentElement
                val type = root.getAttribute("type")
                val major = root.getAttribute("major")
                val minor = root.getAttribute("minor")
                val servicepack = root.getAttribute("servicepack")

                logger.info("Detected dictionary attributes: type={}, major={}, minor={}, servicepack={}", type, major, minor, servicepack)

                when {
                    type == "FIXT" && major == "1" && minor == "1" -> {
                        // This is the FIXT.1.1 transport dictionary, not an application dictionary
                        // Default to FIX 5.0 SP2 for FIXT dictionaries
                        logger.info("Detected FIXT.1.1 transport dictionary")
                        FixVersion.FIX_5_0_SP2
                    }
                    type == "FIX" -> {
                        when ("$major.$minor") {
                            "4.0" -> FixVersion.FIX_4_0
                            "4.1" -> FixVersion.FIX_4_1
                            "4.2" -> FixVersion.FIX_4_2
                            "4.3" -> FixVersion.FIX_4_3
                            "4.4" -> FixVersion.FIX_4_4
                            "5.0" -> {
                                when (servicepack) {
                                    "0", "" -> FixVersion.FIX_5_0
                                    "1" -> FixVersion.FIX_5_0_SP1
                                    "2" -> FixVersion.FIX_5_0_SP2
                                    else -> {
                                        logger.warn("Unknown FIX 5.0 service pack: {}", servicepack)
                                        FixVersion.FIX_5_0_SP2
                                    }
                                }
                            }
                            else -> {
                                logger.warn("Unknown FIX version: {}.{}", major, minor)
                                FixVersion.DEFAULT
                            }
                        }
                    }
                    else -> {
                        logger.warn("Unknown dictionary type: {}", type)
                        FixVersion.DEFAULT
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to detect FIX version from file: {}", e.message, e)
                FixVersion.DEFAULT
            }

        /**
         * Creates an adapter from a data dictionary file path
         */
        fun fromFile(file: File): FixDictionaryAdapter {
            val cacheKey = file.absolutePath
            return dictionaryCache.getOrPut(cacheKey) {
                try {
                    val path = file.absolutePath
                    val dataDictionary = DataDictionary(path)
                    val enumValues = parseEnumValues(file)
                    val allFields = parseAllFields(file)
                    val version = detectVersionFromFile(file)
                    logger.info("Loaded QuickFIX DataDictionary from: {} (version: {})", path, version.displayName)
                    FixDictionaryAdapter(dataDictionary, path, enumValues, allFields, version)
                } catch (e: Exception) {
                    logger.error("Failed to load DataDictionary from {}: {}", file.absolutePath, e.message, e)
                    FixDictionaryAdapter(null, null, emptyMap(), emptyList())
                }
            }
        }

        /**
         * Creates an adapter from a data dictionary file path string
         */
        fun fromPath(path: String): FixDictionaryAdapter = fromFile(File(path))

        /**
         * Creates an adapter for a specific FIX version using bundled dictionaries.
         * For FIX 5.0+, this also loads the FIXT.1.1 transport dictionary.
         *
         * @param version The FIX version to load
         * @return A FixDictionaryAdapter configured for the specified version
         */
        fun forVersion(version: FixVersion): FixDictionaryAdapter {
            val cacheKey = "version:${version.name}"
            return dictionaryCache.getOrPut(cacheKey) {
                try {
                    // Load application dictionary
                    val appResourcePath = version.dictionaryResourcePath
                    val appTempFile = getOrCreateTempFile(appResourcePath)
                    val appDictionary = DataDictionary(appTempFile.absolutePath)

                    // Parse enum values and fields from application dictionary
                    val enumStream = FixDictionaryAdapter::class.java.getResourceAsStream(appResourcePath)!!
                    val enumValues = parseEnumValues(enumStream)
                    enumStream.close()

                    val fieldsStream = FixDictionaryAdapter::class.java.getResourceAsStream(appResourcePath)!!
                    val allFields = parseAllFields(fieldsStream)
                    fieldsStream.close()

                    // For FIX 5.0+, also load transport dictionary
                    val (transportDictionary, transportPath) =
                        if (version.isFix50Plus && version.transportDictionaryResourcePath != null) {
                            val transportTempFile = getOrCreateTempFile(version.transportDictionaryResourcePath)
                            val transportDict = DataDictionary(transportTempFile.absolutePath)
                            logger.info("Loaded FIXT.1.1 transport dictionary for {}", version.displayName)
                            transportDict to transportTempFile.absolutePath
                        } else {
                            null to null
                        }

                    logger.info("Created FixDictionaryAdapter for version: {}", version.displayName)
                    FixDictionaryAdapter(
                        dataDictionary = appDictionary,
                        dictionaryPath = appTempFile.absolutePath,
                        fieldEnumValues = enumValues,
                        allFields = allFields,
                        fixVersion = version,
                        transportDictionary = transportDictionary,
                        transportDictionaryPath = transportPath,
                    )
                } catch (e: Exception) {
                    logger.error("Failed to create FixDictionaryAdapter for version {}: {}", version.displayName, e.message, e)
                    FixDictionaryAdapter(null, null, emptyMap(), emptyList(), version)
                }
            }
        }

        /**
         * Creates an adapter from a classpath resource.
         * The resource is copied to a temp file since QuickFIX requires a file path.
         * @param resourcePath The resource path (e.g., "/dictionaries/FIX44.xml")
         */
        fun fromResource(resourcePath: String = BUNDLED_FIX44_RESOURCE): FixDictionaryAdapter {
            val cacheKey = "resource:$resourcePath"
            return dictionaryCache.getOrPut(cacheKey) {
                try {
                    val tempFile = getOrCreateTempFile(resourcePath)

                    // Parse enum values and all fields from resource (need fresh streams)
                    val enumStream = FixDictionaryAdapter::class.java.getResourceAsStream(resourcePath)!!
                    val enumValues = parseEnumValues(enumStream)
                    enumStream.close()

                    val fieldsStream = FixDictionaryAdapter::class.java.getResourceAsStream(resourcePath)!!
                    val allFields = parseAllFields(fieldsStream)
                    fieldsStream.close()

                    // Create DataDictionary from temp file
                    val dataDictionary = DataDictionary(tempFile.absolutePath)

                    // Detect version from the temp file
                    val version = detectVersionFromFile(tempFile)
                    logger.info("Loaded QuickFIX DataDictionary from resource: {} (version: {})", resourcePath, version.displayName)

                    FixDictionaryAdapter(dataDictionary, tempFile.absolutePath, enumValues, allFields, version)
                } catch (e: Exception) {
                    logger.error("Failed to load DataDictionary from resource {}: {}", resourcePath, e.message, e)
                    FixDictionaryAdapter(null, null, emptyMap(), emptyList())
                }
            }
        }

        /**
         * Gets or creates a temp file for a resource path.
         */
        private fun getOrCreateTempFile(resourcePath: String): File =
            tempFileCache.getOrPut(resourcePath) {
                val inputStream =
                    FixDictionaryAdapter::class.java.getResourceAsStream(resourcePath)
                        ?: throw IllegalArgumentException("Resource not found: $resourcePath")

                val tempFile = File.createTempFile("fix_dictionary_", ".xml")
                tempFile.deleteOnExit()
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }

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
     * Returns the transport dictionary for FIXT.1.1 sessions (FIX 5.0+)
     * Returns null for FIX 4.x versions
     */
    fun getTransportDictionary(): DataDictionary? = transportDictionary

    /**
     * Returns the file path of the transport dictionary (if available)
     * This is needed for QuickFIX configuration which requires a file path
     */
    fun getTransportFilePath(): String? = transportDictionaryPath

    /**
     * Checks if a valid data dictionary is loaded
     */
    fun isLoaded(): Boolean = dataDictionary != null

    /**
     * Checks if this dictionary is for a FIX 5.0+ version (uses FIXT.1.1 transport)
     */
    fun isFix50Plus(): Boolean = fixVersion.isFix50Plus

    /**
     * Gets the header tags for this dictionary's FIX version
     */
    fun getHeaderTags(): Set<Int> = FixVersion.getHeaderTags(fixVersion)

    /**
     * Gets the trailer tags for this dictionary's FIX version
     */
    fun getTrailerTags(): Set<Int> = FixVersion.getTrailerTags(fixVersion)

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
