package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AcceptorResponseRule
import com.knapsack.fixtool.service.load.LoadSetStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **Writes a bundled example from the presets it ships**, so a bundle is regenerated and never hand-edited.
 *
 * A venue's rules live twice: in its preset, which the tests read, and in the example's `connection_profiles.json`,
 * which a user opens. `ExampleWorkspacesTest` pins the two equal, and until this existed the only way to make them
 * equal again after a preset changed was to edit a few hundred lines of JSON by hand.
 *
 * Skipped unless asked for, because a test run must never rewrite the source tree:
 *
 * ```
 * ./gradlew :composeApp:jvmTest --tests '*ExampleBundleGenerator' -Dfixtool.regenerate=rfq-venue
 * ```
 *
 * The JSON is written the way the bundles already are — pretty-printed, defaults left out — so a regeneration of an
 * unchanged preset changes no byte, and a diff of a changed one shows only what the preset changed.
 */
class ExampleBundleGenerator {
    private val requested: Set<String> =
        System
            .getProperty("fixtool.regenerate")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private val json = Json { prettyPrint = true }

    /** The venue profile of each example whose rules are exactly one preset's, as the preset menu inserts them. */
    private val venues =
        mapOf(
            ExampleWorkspaces.FX_VENUE to ("demo-profile-venue" to FxVenuePreset.ID),
            ExampleWorkspaces.RFQ_VENUE to ("rfq-profile-venue" to RfqVenuePreset.ID),
            ExampleWorkspaces.EQUITY_VENUE to ("equity-profile-venue" to EquityVenuePreset.ID),
            ExampleWorkspaces.CRYPTO_VENUE to ("crypto-profile-venue" to CryptoVenuePreset.ID),
        )

    @Test
    fun regenerate() {
        assumeTrue("set -Dfixtool.regenerate=<example id> to rewrite a bundle", requested.isNotEmpty())
        requested.forEach { id ->
            if (id == ExampleWorkspaces.FI_RFQ_VENUE) return@forEach writeFiRfqPlatform()
            val (profileId, presetId) =
                requireNotNull(
                    venues[id],
                ) { "no generator for '$id'; known: ${venues.keys + ExampleWorkspaces.FI_RFQ_VENUE}" }
            refreshVenueRules(id, profileId, presetId)
        }
    }

    /**
     * Writes the whole fixed-income platform from [FiRfqPlatformBundle], every file through the store the app reads it
     * with. The scenarios and load sets are emptied first, so a scenario renamed or removed there leaves no file behind.
     */
    private fun writeFiRfqPlatform() {
        val root = bundleFile(ExampleWorkspaces.FI_RFQ_VENUE, "manifest.json").parentFile
        val scenarioDir = File(root, "scenarios").apply { listFiles().orEmpty().forEach { it.delete() } }
        val loadSetDir = File(root, "load-sets").apply { listFiles().orEmpty().forEach { it.delete() } }
        val templates = File(root, "saved_messages.json").apply { delete() }

        ConnectionProfileService(customPath = File(root, "connection_profiles.json").absolutePath)
            .saveProfiles(FiRfqPlatformBundle.profiles)
            .also { require(it) { "the profiles were not written" } }
        val messages = SavedMessagesService(customPath = templates.absolutePath)
        FiRfqPlatformBundle.templates.forEach { messages.saveMessage(it.userTags.first(), it).getOrThrow() }
        val scenarios = ScenarioService(customDir = scenarioDir.absolutePath)
        FiRfqPlatformBundle.scenarios.forEach { require(scenarios.save(it)) { "${it.id} was not written" } }
        val loadSets = LoadSetStore(loadSetDir.absolutePath)
        FiRfqPlatformBundle.loadSets.forEach { require(loadSets.save(it)) { "${it.name} was not written" } }

        // In the order the bundle declares them, which is the order a reader meets them in.
        val written = scenarioDir.listFiles().orEmpty().associateBy { ScenarioCodec.fromJson(json.parseToJsonElement(it.readText()).jsonObject).id }
        val scenarioFiles = FiRfqPlatformBundle.scenarios.map { written.getValue(it.id) }
        val manifest =
            ExampleWorkspaces.Example(
                id = ExampleWorkspaces.FI_RFQ_VENUE,
                displayName = FiRfqPlatformBundle.DISPLAY_NAME,
                summary = FiRfqPlatformBundle.SUMMARY,
                defaultWorkspaceName = "Fixed Income RFQ",
                files =
                    listOf("connection_profiles.json", "saved_messages.json") +
                        scenarioFiles.map { "scenarios/${it.name}" } +
                        FiRfqPlatformBundle.loadSets.map { "load-sets/${loadSets.fileFor(it.name).name}" },
            )
        File(root, "manifest.json").writeText(json.encodeToString(ExampleWorkspaces.Example.serializer(), manifest) + "\n")
        println("regenerated ${root.path}")
    }

    /** Replaces one venue profile's rules with its preset's, leaving every other byte of the file as it was. */
    private fun refreshVenueRules(exampleId: String, profileId: String, presetId: String) {
        val file = bundleFile(exampleId, "connection_profiles.json")
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val preset = requireNotNull(AcceptorPresets.byId(presetId)) { "no preset '$presetId'" }
        val rules = json.encodeToJsonElement(ListSerializer(AcceptorResponseRule.serializer()), AcceptorPresets.insert(emptyList(), preset).rules)
        val profiles =
            root.getValue("profiles").jsonArray.map { element ->
                val profile = element.jsonObject
                if (profile.getValue("id").jsonPrimitive.content != profileId) return@map profile
                val config = JsonObject(profile.getValue("config").jsonObject + ("acceptorResponseRules" to rules))
                JsonObject(profile + ("config" to config))
            }
        require(profiles.any { it.getValue("id").jsonPrimitive.content == profileId }) { "$exampleId has no profile '$profileId'" }
        write(file, JsonObject(root + ("profiles" to JsonArray(profiles))))
    }

    private fun write(file: File, content: JsonObject) {
        file.writeText(json.encodeToString(JsonObject.serializer(), content) + if (file.readText().endsWith("\n")) "\n" else "")
        println("regenerated ${file.path}")
    }

    /** The bundle in the source tree, not the copy on the test classpath: this rewrites what gets committed. */
    private fun bundleFile(exampleId: String, relative: String): File {
        val file = File("src/jvmMain/resources/examples/$exampleId/$relative")
        require(file.isFile) { "${file.absolutePath} does not exist; run from the composeApp module" }
        return file
    }
}
