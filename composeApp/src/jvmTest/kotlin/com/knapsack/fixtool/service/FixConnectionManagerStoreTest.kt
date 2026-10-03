package com.knapsack.fixtool.service

import com.knapsack.fixtool.model.AppSettings
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionConfig.MessageLogKind
import com.knapsack.fixtool.model.FixConnectionConfig.MessageStoreKind
import com.knapsack.fixtool.model.FixDictionaryAdapter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import quickfix.FileLogFactory
import quickfix.FileStoreFactory
import quickfix.MemoryStoreFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The store and the log are a profile's choice, and the choice is honoured before a socket opens.**
 *
 * Both used to be file factories unconditionally. For interactive use that is the right default. For a
 * load or soak run it is the dominant per-message cost on the issue path, and a store that grows for
 * the length of the run. So the profile says which, the manager builds what it says, and a memory store
 * without Reset on Logon is refused in the config's own words rather than quietly fixed.
 */
class FixConnectionManagerStoreTest {
    private lateinit var home: File

    @Before
    fun setUp() {
        home = File(System.getProperty("java.io.tmpdir"), "fixtool-store-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun config(store: MessageStoreKind, log: MessageLogKind, resetOnLogon: Boolean = true) =
        FixConnectionConfig(
            senderCompID = "CLI",
            targetCompID = "SRV",
            host = "localhost",
            port = "1",
            autoReconnect = false,
            resetOnLogon = resetOnLogon,
            fileStorePath = File(home, "store").absolutePath,
            fileLogPath = File(home, "log").absolutePath,
            messageStore = store,
            messageLog = log,
        )

    private fun manager(config: FixConnectionConfig): FixConnectionManager {
        val dictionary = FixDictionaryAdapter.createDefault()
        val service =
            QuickFixService(
                config = config,
                dictionary = dictionary,
                onMessageReceived = {},
                onStateChanged = {},
            )
        return FixConnectionManager(config, service, AppSettings(), dictionary)
    }

    private fun field(manager: FixConnectionManager, name: String): Any {
        val declared = FixConnectionManager::class.java.getDeclaredField(name)
        declared.isAccessible = true
        return declared.get(manager)
    }

    @Test
    fun `the default is what it always was, file store and file log`() {
        val manager = manager(config(MessageStoreKind.FILE, MessageLogKind.FILE))

        assertIs<FileStoreFactory>(field(manager, "messageStoreFactory"))
        assertIs<FileLogFactory>(field(manager, "logFactory"))
        assertTrue(File(home, "store").isDirectory, "the file store needs its directory")
        assertTrue(File(home, "log").isDirectory, "the file log needs its directory")
    }

    @Test
    fun `a memory store and no log build the heap factories and touch nothing on disk`() {
        val manager = manager(config(MessageStoreKind.MEMORY, MessageLogKind.NONE))

        assertIs<MemoryStoreFactory>(field(manager, "messageStoreFactory"))
        assertEquals(NoopLogFactory, field(manager, "logFactory"))
        assertFalse(File(home, "store").exists(), "a memory store must not create store/")
        assertFalse(File(home, "log").exists(), "no log must not create log/")
    }

    @Test
    fun `a memory store without Reset on Logon is refused before anything is built`() {
        val config = config(MessageStoreKind.MEMORY, MessageLogKind.FILE, resetOnLogon = false)

        val refused = assertFailsWith<IllegalArgumentException> { manager(config) }

        assertEquals(config.storeProblem(), refused.message)
        assertTrue(refused.message.orEmpty().contains("Reset on Logon"), refused.message)
        assertFalse(File(home, "log").exists(), "refused means nothing was made: ${home.list()?.toList()}")
    }

    @Test
    fun `a file store never has a store problem, whatever Reset on Logon says`() {
        assertNull(config(MessageStoreKind.FILE, MessageLogKind.NONE, resetOnLogon = false).storeProblem())
        assertNull(config(MessageStoreKind.MEMORY, MessageLogKind.NONE, resetOnLogon = true).storeProblem())
    }

    // ---------------------------------------------------------------- Reset on Logon

    /** The five files QuickFIX/J's FileStore keeps for the session [name] names. */
    private fun storeFiles(name: String) = listOf("body", "header", "senderseqnums", "targetseqnums", "session").map { "$name.$it" }

    private fun seedStore(vararg sessions: String) {
        val store = File(home, "store").apply { mkdirs() }
        sessions.flatMap(::storeFiles).forEach { File(store, it).writeText("1") }
    }

    private fun storeLeft(): Set<String> = File(home, "store").list().orEmpty().toSet()

    private fun clearStoreFiles(manager: FixConnectionManager) {
        val method = FixConnectionManager::class.java.getDeclaredMethod("clearStoreFiles")
        method.isAccessible = true
        method.invoke(manager)
    }

    /**
     * The store directory is one per workspace, shared by every profile in it, so a reset clears the files of
     * this profile's own sessions by QuickFIX/J's exact names. A venue's sessions are named for clients it
     * has not met, so for a venue that is its own BeginString and CompID with any client after them.
     */
    @Test
    fun `a venue's reset clears its own clients' store files and no other profile's`() {
        seedStore("FIX.4.4-VENUE-CLIENT1", "FIX.4.4-VENUE-CLIENT2", "FIX.4.4-CLIENT-VENUE_UAT", "FIX.4.4-VENUE_UAT-CLIENT")
        val venue =
            config(MessageStoreKind.FILE, MessageLogKind.NONE).copy(
                connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                senderCompID = "VENUE",
                targetCompID = FixConnectionConfig.ANY_CLIENT,
            )

        clearStoreFiles(manager(venue))

        assertEquals(
            (storeFiles("FIX.4.4-CLIENT-VENUE_UAT") + storeFiles("FIX.4.4-VENUE_UAT-CLIENT")).toSet(),
            storeLeft(),
            "the venue's reset reached files that are not its sessions'",
        )
    }

    @Test
    fun `an initiator's reset clears its one session's store files and not a lookalike's`() {
        seedStore("FIX.4.4-CLIENT-LSE", "FIX.4.4-CLIENTX-LSEG", "FIX.4.4-CLIENT-LSE-UAT")
        val initiator = config(MessageStoreKind.FILE, MessageLogKind.NONE).copy(senderCompID = "CLIENT", targetCompID = "LSE")

        clearStoreFiles(manager(initiator))

        assertEquals(
            (storeFiles("FIX.4.4-CLIENTX-LSEG") + storeFiles("FIX.4.4-CLIENT-LSE-UAT")).toSet(),
            storeLeft(),
            "the reset of CLIENT to LSE reached another profile's files",
        )
    }

    /**
     * Every profile file written before these fields existed must read exactly as it did, and a file
     * that names them must read what it names.
     */
    @Test
    fun `a profile written before the fields existed reads as file and file`() {
        val json = Json { ignoreUnknownKeys = true }

        val old = json.decodeFromString<FixConnectionConfig>("""{"senderCompID":"A","targetCompID":"B"}""")
        assertEquals(MessageStoreKind.FILE, old.messageStore)
        assertEquals(MessageLogKind.FILE, old.messageLog)

        val written = json.encodeToString(config(MessageStoreKind.MEMORY, MessageLogKind.NONE))
        val back = json.decodeFromString<FixConnectionConfig>(written)
        assertEquals(MessageStoreKind.MEMORY, back.messageStore)
        assertEquals(MessageLogKind.NONE, back.messageLog)
    }
}
