package com.knapsack.fixtool.viewmodel

import com.knapsack.fixtool.integration.TestFixServer
import com.knapsack.fixtool.integration.settled
import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.load.LoadMatch
import com.knapsack.fixtool.model.load.LoadPhaseSpec
import com.knapsack.fixtool.model.load.LoadPlan
import com.knapsack.fixtool.model.load.LoadSet
import com.knapsack.fixtool.model.load.LoadShape
import com.knapsack.fixtool.model.load.LoadTemplate
import com.knapsack.fixtool.model.load.OnFailure
import com.knapsack.fixtool.model.scenario.Lane
import com.knapsack.fixtool.service.load.LoadFixtures
import com.knapsack.fixtool.ui.FixField
import com.knapsack.fixtool.ui.ScenarioDoc
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The view model's half of a load run: the doors in, the record store, and the refusal when there are no lanes. */
class LoadRunViewModelTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = File.createTempFile("fixtool-load-vm", "").apply { delete(); mkdirs() }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private val template = LoadTemplate("NOS", listOf(35 to "D", 11 to "ORD-\${messageIndex}"))

    private fun plan(profileId: String) =
        LoadPlan(
            id = "x",
            label = "NOS ×10 on LOADGEN",
            template = template,
            profileId = profileId,
            profileName = "LOADGEN",
            shape = LoadShape.Burst(10),
            match = LoadMatch(11),
        )

    private fun set(vararg phases: LoadPlan) =
        LoadSet.Planned(
            id = "s",
            label = "one phase on LOADGEN",
            name = "solo-set",
            onFailure = OnFailure.STOP,
            seed = emptyMap(),
            storeAndLog = null,
            phases = phases.toList(),
        )

    /**
     * **A profile with nothing logged on is dialled, not refused.**
     *
     * Which sessions a load set needs is the one thing its name does not say, so running a saved one meant
     * opening the file, connecting them by hand, and learning one refusal at a time which had been missed.
     * `fixtool load --set` never had that problem — its host opens every lane the set names — and this is
     * the window's half of the same contract: it fires only where the run would have been refused.
     *
     * The refusal is still there for a venue that never answers, and it is the runner's own sentence, said
     * once the wait is over rather than before it began.
     */
    @Test
    fun `a profile with no lane logged on is brought up, and refused only if it never arrives`() {
        val profile =
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG{n}",
                        targetCompID = "V",
                        sessionCount = 3,
                        // Nothing is listening on it, which is the case this test is about.
                        host = "localhost",
                        port = "1",
                        socketConnectHost = "localhost",
                        autoReconnect = false,
                        fileStorePath = File(testDir, "lgstore").absolutePath,
                        fileLogPath = File(testDir, "lglog").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.loadLogonWaitMs = 300

        val started = assertNotNull(viewModel.startLoadRun(plan("lg")), "the run starts and dials rather than refusing")

        assertEquals(
            listOf(started.id),
            viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>().map { it.loadId },
            "the document opens over the run that is about to dial, not after it has logged on",
        )
        // Three panes that did not exist a moment ago: the profile's own lanes, opened by the run.
        assertTrue(
            awaitCondition(10_000) { viewModel.getProfileSessions("lg").size == 3 },
            "the run opens every lane of the profile it names: ${viewModel.sessions.map { it.title }}",
        )
        assertTrue(viewModel.notifications.any { "Connecting LOADGEN" in it.message }, "and says so")
        // And when the venue never answers, the sentence is the runner's, after the wait rather than before.
        assertTrue(
            awaitCondition(15_000) { viewModel.notifications.any { "no session of 'LOADGEN' reached LOGGED_ON" in it.message } },
            "the refusal survives auto-connect: ${viewModel.notifications.map { it.message }}",
        )
    }

    /**
     * **The row under a saved set's name: what it runs on, and what pressing it will connect.**
     *
     * A name no saved profile answers to is listed as needed all the same and never as something Run will
     * connect — the set's own refusal is what names that, and it opens the editor on the set.
     */
    @Test
    fun `a set says which profiles it needs and which of them Run would connect`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(id = "lg", name = "LOADGEN", config = FixConnectionConfig(senderCompID = "LG", targetCompID = "V")),
        )
        val set =
            LoadSet(
                name = "two-sided",
                label = "Two sided",
                phases =
                    listOf(
                        LoadPhaseSpec("Ask", "Quote request", "LOADGEN", listen = listOf("VENUE"), shape = LoadShape.Burst(10)),
                        LoadPhaseSpec("Parked", "Nothing", "GONE", shape = LoadShape.Burst(10), muted = true),
                    ),
            )

        val sessions = viewModel.loadSetSessions(set)

        assertEquals(listOf("LOADGEN", "VENUE"), sessions.needed, "the parked phase's profile is nobody's business")
        assertEquals(listOf("LOADGEN"), sessions.toConnect, "and a name nothing answers to is not something Run can connect")
        assertEquals("on LOADGEN, VENUE · Run connects LOADGEN", sessions.sentence)
    }

    /**
     * **A load run is not a fan-out, and a profile that opens one session is not its problem.**
     *
     * `fixtool load` has always issued from `sessionCount` lanes, one included, and a venue's load
     * accounts are one session each: the venue fans every reply out to every session of the organisation,
     * so a second lane buys duplicates. The window used to hand that profile fan-out's sentence and send
     * the author to the Sessions field, which fixes nothing. What is missing is a connection, and both
     * start paths now say so.
     */
    @Test
    fun `a one-session profile is refused for its lanes, never for its session count`() {
        val profile =
            FixConnectionProfile(id = "solo", name = "SOLO", config = FixConnectionConfig(senderCompID = "SOLO", targetCompID = "V"))
        viewModel.saveConnectionProfile(profile)

        val forLoad = assertNotNull(viewModel.loadLanes("solo") as? FixMessageViewModel.FanOutLanes.Unavailable)
        assertTrue("no session of 'SOLO' is logged on" in forLoad.why, forLoad.why)
        assertTrue("Run will connect it" in forLoad.why, "it asks for a connection, not a session count: ${forLoad.why}")
        assertEquals("solo", forLoad.couldConnect?.id, "and it carries the profile a connect would fix it with")

        // Fan-out is untouched: its whole point is many identities, so one session is the wrong profile.
        val forFanOut = assertNotNull(viewModel.fanOutLanes("solo") as? FixMessageViewModel.FanOutLanes.Unavailable)
        assertTrue("opens 1" in forFanOut.why, forFanOut.why)
        assertTrue("Sessions" in forFanOut.why && "{nn}" in forFanOut.why, forFanOut.why)
        assertNull(forFanOut.couldConnect, "a connect would not answer this one, and the row must not offer to")

        viewModel.loadLogonWaitMs = 200
        assertNotNull(viewModel.startLoadRun(plan("solo")), "a load run dials the one session it needs")
        assertTrue(
            viewModel.notifications.none { "Fan-out needs" in it.message },
            "the start path does not send a load down the fan-out path: ${viewModel.notifications.map { it.message }}",
        )
    }

    /**
     * **A group of one is lane 1, not lane 0.** A lane's slot is the lane's identity, and it is what a
     * template reads as `${sessionIndex}`, so it has to be a number the far end can tell the lanes apart
     * by. A single-session profile's session carries `profileSlot` 0 as a convention of its own, and taken
     * literally it made the window issue `58=LANE-0` from a session that had logged on as `SOLO1`, while
     * the same set run headless numbered that lane 1. Both paths answer 1 now.
     */
    @Test
    fun `a one-session profile's lane is slot 1, so sessionIndex is never 0`() {
        val venue = TestFixServer()
        venue.start()
        val runId = System.nanoTime().toString().takeLast(6)
        try {
            val profile =
                FixConnectionProfile(
                    name = "SOLO",
                    config =
                        FixConnectionConfig(
                            connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                            senderCompID = "SOLO{n}$runId",
                            targetCompID = "VENUE$runId",
                            sessionCount = 1,
                            host = "localhost",
                            port = venue.port.toString(),
                            socketConnectHost = "localhost",
                            beginString = "FIX.4.4",
                            autoReconnect = false,
                            resetOnLogon = true,
                            fileStorePath = File(testDir, "solostore").absolutePath,
                            fileLogPath = File(testDir, "sololog").absolutePath,
                        ),
                )
            viewModel.saveConnectionProfile(profile)
            viewModel.connectProfile(profile.id, profile)
            assertTrue(
                awaitCondition(25_000) {
                    viewModel.getProfileSessions(profile.id).any { it.connectionState.value == FixConnectionState.LOGGED_ON }
                },
                "the profile's one session should log on: " +
                    viewModel.sessions.joinToString { "${it.title}=${it.connectionState.value}" },
            )

            val available = assertNotNull(viewModel.loadLanes(profile.id) as? FixMessageViewModel.FanOutLanes.Available)
            val lane = assertNotNull(available.lanes.singleOrNull(), "one session is one lane: ${available.lanes}")
            assertEquals(1, lane.slot, "the group of one is lane 1, whatever slot the session itself carries")

            // The seed is what reaches a template, so this is the assertion that would have caught LANE-0.
            assertEquals("1", lane.seed()[Lane.SESSION_INDEX])
            assertEquals("SOLO1$runId", lane.senderCompID, "and it is the identity that same session logged on with")
        } finally {
            viewModel.disconnectAllSessions()
            venue.stop()
        }
    }

    /**
     * **The row promises the venue too, when the venue is one of ours.**
     *
     * A set names the profile that issues, never the venue it dials, so the row would have said "Run
     * connects RFQ Load Client" and then connected two things. A row that promises less than it does is
     * the same surprise as one that promises more.
     */
    @Test
    fun `the row names the far end it will bring up, without calling it something the set needs`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG",
                        targetCompID = "V",
                        host = "localhost",
                        socketConnectHost = "localhost",
                        port = "19998",
                    ),
            ),
        )
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "venue",
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "V",
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
                        port = "19998",
                        socketAcceptPort = "19998",
                    ),
            ),
        )
        val set =
            LoadSet(
                name = "one-sided",
                label = "One sided",
                phases = listOf(LoadPhaseSpec("Ask", "Quote request", "LOADGEN", shape = LoadShape.Burst(10))),
            )

        val sessions = viewModel.loadSetSessions(set)

        assertEquals(listOf("LOADGEN"), sessions.needed, "the set names one profile, and that is what it needs")
        assertEquals(listOf("LOADGEN", "VENUE"), sessions.toConnect, "and pressing it brings up both")
        assertEquals("on LOADGEN · Run connects LOADGEN, VENUE", sessions.sentence, "named, while they fit")
    }

    /**
     * **Named while they fit, counted when they do not.** What makes the line unreadable is its width, not
     * the number of profiles in it: two called LoadGen and RFQVenue name themselves comfortably, and two
     * called RFQ Load Client and RFQ Demo Venue run off the end of the menu and are ellipsised mid-name.
     * Found on screen, in the example workspace.
     */
    @Test
    fun `a sentence too wide for the menu counts the profiles instead of naming them`() {
        listOf("RFQ Load Client", "RFQ Demo Venue").forEach { name ->
            viewModel.saveConnectionProfile(
                FixConnectionProfile(id = name, name = name, config = FixConnectionConfig(senderCompID = "S", targetCompID = "T")),
            )
        }
        val set =
            LoadSet(
                name = "wide",
                label = "Wide",
                phases =
                    listOf(
                        LoadPhaseSpec(
                            "Ask",
                            "Quote request",
                            "RFQ Load Client",
                            listen = listOf("RFQ Demo Venue"),
                            shape = LoadShape.Burst(10),
                        ),
                    ),
            )

        assertEquals(
            "on 2 profiles · Run connects 2 profiles",
            viewModel.loadSetSessions(set).sentence,
        )
    }

    /**
     * **The room is measured against the whole sentence, in the width it will draw, and it is the long
     * half that gives way.**
     *
     * Shortening only the tail left the clause the shortening exists to produce — *Run connects 2
     * profiles* — as the half that got ellipsised off the end of the menu, and a list of profiles that
     * were all up was never measured at all. And a character count cannot tell `LoadGen, RFQVenue` from
     * `OMS_UAT_WEST, OMS_UAT_EAST`: the same length, and the second sixty percent wider, because a CompID
     * is capitals and underscores. Every shape below is one somebody's workspace really has.
     */
    @Test
    fun `each way of saying it gives way to a shorter one, whole sentence first`() {
        fun sentence(needed: List<String>, toConnect: List<String>) =
            FixMessageViewModel.LoadSetSessions(needed, toConnect).sentence

        // Mixed-case names are narrow, so both halves keep their names even at fifty-three characters.
        assertEquals(
            "on LoadGen, RFQVenue · Run connects LoadGen, RFQVenue",
            sentence(listOf("LoadGen", "RFQVenue"), listOf("LoadGen", "RFQVenue")),
        )
        // The same sentence in CompID case is four characters shorter and thirty dp wider, so the tail
        // gives way. This is the shape a character budget could not tell from the one above.
        assertEquals(
            "on OMS_UAT_WEST, OMS_UAT_EAST · Run connects 2 profiles",
            sentence(listOf("OMS_UAT_WEST", "OMS_UAT_EAST"), listOf("OMS_UAT_WEST", "OMS_UAT_EAST")),
        )
        // The example workspace's own row: one profile named, two connected, the venue among them.
        assertEquals(
            "on RFQ Load Client · Run connects 2 profiles",
            sentence(listOf("RFQ Load Client"), listOf("RFQ Load Client", "RFQ Demo Venue")),
        )
        // Nothing to connect was never measured at all before, so a long list of live profiles ran off
        // the end of the menu and was cut mid-name.
        assertEquals(
            "on 3 profiles",
            sentence(listOf("UAT_MDGATEWAY_WEST", "UAT_MDGATEWAY_EAST", "UAT_ORDER_ENTRY"), emptyList()),
        )
        // And when counting the tail is not enough on its own, the list gives way too.
        assertEquals(
            "on 3 profiles · Run connects 3 profiles",
            sentence(
                listOf("UAT_MDGATEWAY_WEST", "UAT_MDGATEWAY_EAST", "UAT_ORDER_ENTRY"),
                listOf("UAT_MDGATEWAY_WEST", "UAT_MDGATEWAY_EAST", "UAT_ORDER_ENTRY"),
            ),
        )
        assertEquals("every phase is parked", sentence(emptyList(), emptyList()))
    }

    /**
     * **The width is what decides, and it is measured on the whole sentence.**
     *
     * Two sentences of the same length, one of which draws inside the menu and one of which does not.
     * A budget that could not separate these is the one that shipped a row cut mid-name.
     */
    @Test
    fun `two sentences of one length are told apart by the width they draw`() {
        fun sentence(name: String) = FixMessageViewModel.LoadSetSessions(listOf(name, "Second"), listOf(name)).sentence

        // Sixteen characters, twice over: 280dp in mixed case and 304dp in CompID case, against 290dp
        // of room. The one number a character count has for both of them is 45.
        val narrow = sentence("Marketdatafeed_1")
        val wide = sentence("MARKETDATAFEED_1")
        assertEquals(59, narrow.length, "named, and 59 characters of it: $narrow")
        assertTrue(narrow.endsWith("Run connects Marketdatafeed_1"), "the narrow one keeps its name: $narrow")
        // The same fifty-nine characters, and this one gives its name up, because they draw 304dp.
        assertTrue(wide.endsWith("Run connects 1 profile"), "and the wide one gives it up: $wide")
    }

    /**
     * **What the run will dial, and in which order.**
     *
     * Acceptors first: an acceptor binds a port and an initiator dials one, so a two-sided set that opened
     * them the other way round would have its lanes dial a port nothing was listening on yet. Not a fact
     * the finished record carries, and staging it end to end would mean a venue with reply rules to prove
     * a thing about ordering — so it is pinned here, where it is decided.
     *
     * And the panes are named before they exist, because the claim is taken over them: a scenario must not
     * be able to start on a lane between the moment the run dials it and the moment it logs on.
     */
    @Test
    fun `the preflight dials acceptors first, and names the panes it is about to open`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config = FixConnectionConfig(senderCompID = "LG{nn}", targetCompID = "V", sessionCount = 3),
            ),
        )
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "venue",
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "V",
                        targetCompID = "LG",
                        socketAcceptPort = "9",
                        // An acceptor binds one port however this reads, so it is one pane, not four.
                        sessionCount = 4,
                    ),
            ),
        )

        val pre = viewModel.loadPreflight(issuing = listOf("lg"), listening = listOf("venue"))

        assertNull(pre.refusal)
        assertEquals(listOf("VENUE", "LOADGEN"), pre.bringUp.map { it.name }, "the port is bound before it is dialled")
        assertEquals(setOf("LOADGEN [1]", "LOADGEN [2]", "LOADGEN [3]", "VENUE"), pre.titles)
        assertEquals(listOf(emptyList()), pre.lanesByProfile.values.toList(), "an empty entry is 'this one is the run's to open'")
    }

    /**
     * **The far end comes up too, when the far end is one of ours.**
     *
     * A set names the profile that issues and never the venue it dials, because ordinarily that venue is
     * somebody else's server. The bundled examples are the other case: the lanes dial a FixTool acceptor on
     * loopback, and bringing up the client alone left five lanes dialling a port nothing had bound. Found
     * by running the RFQ Venue example's own load set against a box with nothing connected.
     */
    @Test
    fun `the preflight brings up the acceptor the lanes dial, when it is one of ours`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config =
                    FixConnectionConfig(
                        senderCompID = "LG{nn}",
                        targetCompID = "V",
                        sessionCount = 2,
                        host = "localhost",
                        socketConnectHost = "localhost",
                        port = "19999",
                    ),
            ),
        )
        viewModel.saveConnectionProfile(
            FixConnectionProfile(
                id = "venue",
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "V",
                        targetCompID = FixConnectionConfig.ANY_CLIENT,
                        port = "19999",
                        socketAcceptPort = "19999",
                    ),
            ),
        )

        val pre = viewModel.loadPreflight(issuing = listOf("lg"), listening = emptyList())

        assertEquals(
            listOf("VENUE", "LOADGEN"),
            pre.bringUp.map { it.name },
            "the venue is nowhere in the plan, and the lanes dial it: bind the port, then dial it",
        )
        assertEquals(setOf("VENUE", "LOADGEN [1]", "LOADGEN [2]"), pre.titles)
    }

    /**
     * **A venue bound and waiting for its first client is up, and is not dialled again.**
     *
     * A wildcard venue's own pane sits at CONNECTED and never reaches LOGGED_ON — its clients' panes are
     * the ones that log on. Asked "is a session logged on", such a venue read as down, so a run announced
     * "Connecting VENUE" for a venue it was not going to touch and the Run menu's row promised to connect
     * one that was already there. What decides is whether a connect would do anything at all.
     */
    @Test
    fun `a venue that is bound with no client yet is up, and neither dialled nor promised`() {
        // No TestFixServer here: the FixTool acceptor is the thing binding the port, and a second server
        // on it would be the reason it could not.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val runId = System.nanoTime().toString().takeLast(6)
        try {
            val lanes =
                FixConnectionProfile(
                    id = "lg",
                    name = "LOADGEN",
                    config =
                        FixConnectionConfig(
                            senderCompID = "UPLG$runId",
                            targetCompID = "UPV$runId",
                            host = "localhost",
                            socketConnectHost = "localhost",
                            port = port.toString(),
                        ),
                )
            val bound =
                FixConnectionProfile(
                    id = "venue",
                    name = "VENUE",
                    config =
                        FixConnectionConfig(
                            connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                            senderCompID = "UPV$runId",
                            targetCompID = FixConnectionConfig.ANY_CLIENT,
                            port = port.toString(),
                            socketAcceptPort = port.toString(),
                            beginString = "FIX.4.4",
                            fileStorePath = File(testDir, "upstore").absolutePath,
                            fileLogPath = File(testDir, "uplog").absolutePath,
                        ),
                )
            viewModel.saveConnectionProfile(lanes)
            viewModel.saveConnectionProfile(bound)
            viewModel.connectProfile(bound.id, bound)
            assertTrue(
                awaitCondition(20_000) {
                    viewModel.getProfileSessions(bound.id).any { it.connectionState.value == FixConnectionState.CONNECTED }
                },
                "the venue should bind its port and sit at CONNECTED: " +
                    viewModel.sessions.joinToString { "${it.title}=${it.connectionState.value}" },
            )
            assertTrue(
                viewModel.getProfileSessions(bound.id).none { it.connectionState.value == FixConnectionState.LOGGED_ON },
                "and it is deliberately still short of LOGGED_ON, with no client on it yet",
            )

            val pre = viewModel.loadPreflight(issuing = listOf("lg"), listening = emptyList())
            assertEquals(
                listOf("LOADGEN"),
                pre.bringUp.map { it.name },
                "the venue is already bound, so only the lanes are dialled",
            )

            val set =
                LoadSet(
                    name = "bound",
                    label = "Bound",
                    phases = listOf(LoadPhaseSpec("Ask", "Quote request", "LOADGEN", shape = LoadShape.Burst(10))),
                )
            assertEquals(
                "on LOADGEN · Run connects LOADGEN",
                viewModel.loadSetSessions(set).sentence,
                "and the row does not promise to connect it either",
            )
        } finally {
            viewModel.disconnectAllSessions()
        }
    }

    /** A name no saved profile answers to is the one thing a dial could not fix, so it is still refused. */
    @Test
    fun `the preflight refuses a listen profile nothing answers to`() {
        viewModel.saveConnectionProfile(
            FixConnectionProfile(id = "lg", name = "LOADGEN", config = FixConnectionConfig(senderCompID = "LG", targetCompID = "V")),
        )

        val pre = viewModel.loadPreflight(issuing = listOf("lg"), listening = listOf("GONE"))

        assertEquals("no saved connection profile named 'GONE' to listen on", pre.refusal)
        assertEquals(emptyList(), pre.bringUp)
    }

    @Test
    fun `Recent's load row opens the document over the record`() {
        val report = LoadFixtures.burstReport()
        viewModel.loadRecordStore.write(report)

        viewModel.openLoadRun(report.id)

        val doc = assertNotNull(viewModel.openDocuments.value.filterIsInstance<ScenarioDoc.LoadRunView>().singleOrNull())
        assertEquals(report.id, doc.loadId)
        assertEquals(listOf(report.id), viewModel.loadRecordStore.list().map { it.id })
    }

    @Test
    fun `the editor's fields become the dialog's template, and dismissing clears it`() {
        viewModel.requestLoadRun(listOf(FixField("35", "D"), FixField("11", "ORD-\${messageIndex}"), FixField("", "junk")))

        val t = assertNotNull(viewModel.loadDialogTemplate.value)
        assertEquals("D", t.msgType)
        assertEquals(listOf(35 to "D", 11 to "ORD-\${messageIndex}"), t.fields)
        assertEquals(LoadMatch(11), t.inferMatch())

        viewModel.dismissLoadDialog()
        assertNull(viewModel.loadDialogTemplate.value)
    }

    /**
     * **The far end is one of ours only when it is one of ours**: a loopback connect host, and a FixTool
     * acceptor listening on the port the lanes dial. The dialog's note and the profiles "Also listen on"
     * refuses to offer both hang off this, so it is worth pinning both answers.
     */
    @Test
    fun `the far end is the acceptor on the port the lanes dial, and nothing when the venue is elsewhere`() {
        val client =
            FixConnectionProfile(
                id = "lg",
                name = "LOADGEN",
                config = FixConnectionConfig(senderCompID = "LG{n}", targetCompID = "V", host = "localhost", port = "9", sessionCount = 3),
            )
        val venue =
            FixConnectionProfile(
                id = "venue",
                name = "VENUE",
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.ACCEPTOR,
                        senderCompID = "V",
                        targetCompID = "LG{n}",
                        socketAcceptPort = "9",
                    ),
            )
        viewModel.saveConnectionProfile(client)
        viewModel.saveConnectionProfile(venue)

        assertEquals("venue", viewModel.farEndProfile("lg")?.id)
        assertNotNull(viewModel.fanOutFarEndNotice("lg"))

        // The same acceptor, on a port nobody dials: not this run's far end.
        viewModel.saveConnectionProfile(venue.copy(config = venue.config.copy(socketAcceptPort = "10")))

        assertNull(viewModel.farEndProfile("lg"))
        assertNull(viewModel.fanOutFarEndNotice("lg"))
    }

    /** The editor's own panel toggle, so the dialog's "view in editor" is not a dead link. */
    @Test
    fun `bringing the editor forward opens the panel it may be hiding in`() {
        assertTrue(!viewModel.showMessageEditor.value)

        viewModel.bringEditorForward()

        assertTrue(viewModel.showMessageEditor.value)

        // Already open stays open: the raise is the point, not the toggle.
        viewModel.bringEditorForward()

        assertTrue(viewModel.showMessageEditor.value)
    }

    @Test
    fun `a stop aimed at a run that is not running is harmless`() {
        viewModel.stopLoadRun("nothing")
        assertTrue(!viewModel.isLoadRunning("nothing"))
    }

    /** Polled against a snapshot, because the sessions list is written on the view model's own dispatcher. */
    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (settled(predicate)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(100)
        }
    }
}
