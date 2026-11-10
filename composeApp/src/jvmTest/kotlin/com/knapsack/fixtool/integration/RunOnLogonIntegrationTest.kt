package com.knapsack.fixtool.integration

import com.knapsack.fixtool.model.FixConnectionConfig
import com.knapsack.fixtool.model.FixConnectionProfile
import com.knapsack.fixtool.model.FixConnectionState
import com.knapsack.fixtool.model.FixMessageSession
import com.knapsack.fixtool.model.scenario.ExampleRow
import com.knapsack.fixtool.model.scenario.Examples
import com.knapsack.fixtool.model.scenario.Expectation
import com.knapsack.fixtool.model.scenario.FieldExpectation
import com.knapsack.fixtool.model.scenario.MatchPredicate
import com.knapsack.fixtool.model.scenario.Matcher
import com.knapsack.fixtool.model.scenario.RunSet
import com.knapsack.fixtool.model.scenario.RunSetStatus
import com.knapsack.fixtool.model.scenario.RunSource
import com.knapsack.fixtool.model.scenario.RunState
import com.knapsack.fixtool.model.scenario.RunTrigger
import com.knapsack.fixtool.model.scenario.Scenario
import com.knapsack.fixtool.model.scenario.ScenarioStep
import com.knapsack.fixtool.model.scenario.TagValue
import com.knapsack.fixtool.model.scenario.VariableSource
import com.knapsack.fixtool.viewmodel.FixMessageViewModel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A scenario that runs on every logon of its session**, against a counterparty that starts a handshake
 * straight after its Logon reply and disconnects a client that has not answered within a second.
 *
 * The handshake is [TestFixServer.Handshake]: a News whose Headline(148) is a fresh id, `REQ-<uuid>`, and an
 * answer expected back with the same id. The scenario is what answers it. Its Expect captures the id with
 * `bindAs`, and its Send echoes it. Its binding is left at ANY on purpose: a run started by a logon must bind
 * only that connection's messages whatever the scenario says, so a second connection whose answer echoed the
 * first connection's id would show a run that bound the previous connection's request.
 */
class RunOnLogonIntegrationTest {
    private lateinit var viewModel: FixMessageViewModel
    private lateinit var testDir: File
    private lateinit var server: TestFixServer

    // QuickFIX/J's session registry is static per JVM, so a SessionID reused across tests can collide.
    private val runId = System.nanoTime().toString().takeLast(8)
    private val client = "CLIENT$runId"

    @Before
    fun setup() {
        testDir =
            File.createTempFile("fixtool-run-on-logon", "").apply {
                delete()
                mkdirs()
            }
        viewModel = FixMessageViewModel(testSettingsDir = testDir.absolutePath)
        server = TestFixServer().apply { start() }
    }

    @After
    fun cleanup() {
        viewModel.requestScenarioStop()
        viewModel.disconnectAllSessions()
        awaitCondition(5_000) { viewModel.sessions.none { it.connectionState.value == FixConnectionState.LOGGED_ON } }
        server.stop()
        testDir.deleteRecursively()
    }

    /** Case 1. Nothing starts a scenario on logon today, so the counterparty waits a second and hangs up. */
    @Test
    fun `a scenario set to run on logon answers the handshake within the counterparty's window`() {
        val handshake = server.handshake()
        save(handshakeScenario(RunTrigger.LOGON))

        val (_, session) = connect()

        val challenge = awaitChallenge(handshake, 1)
        assertTrue(
            awaitCondition(5_000) { challenge.answers.isNotEmpty() || challenge.disconnectedForSilence },
            "the venue should hear an answer or give up on it",
        )
        assertTrue(
            challenge.answeredInTime,
            "the answer must echo ${challenge.requestId} within ${challenge.windowMs} ms: " +
                "answers=${challenge.answers}, disconnected=${challenge.disconnectedForSilence}",
        )
        println("run on logon: handshake answered in ${challenge.answers.first().second} ms")
        assertEquals(FixConnectionState.LOGGED_ON, session.connectionState.value)
        val run = awaitLogonRuns(1).single()
        assertEquals(RunState.PASSED, run.entries.single().state, "the run is recorded like any other: $run")
        assertEquals("Handshake on logon of $client", run.label, "run history says what started it")
        assertEquals(RunSource.Logon(client, listOf("handshake-$runId")), run.source, "and records it as a logon")
        val report = assertNotNull(viewModel.runRecordStore.readEntry(run.id, 1), "the run is recorded").result
        val trigger = report.steps.first()
        assertEquals("trigger", trigger.kind, "the report opens with what started it: ${report.steps}")
        assertTrue("logon of session '$client'" in trigger.detail.orEmpty(), "the report names the logon: $trigger")
    }

    /**
     * A logon scenario with two live examples rows runs the one `triggerRow` names: the answer carries that
     * row's cell, and the record and report name the row the way a run of that row by hand does.
     */
    @Test
    fun `a logon runs the examples row the scenario names, and sends that row's value`() {
        val handshake = server.handshake()
        save(rowScenario(ExampleRow("first", mapOf("stream" to "ALPHA")), ExampleRow("second", mapOf("stream" to "BETA")), triggerRow = "second"))

        connect()

        val challenge = awaitChallenge(handshake, 1)
        assertTrue(
            awaitCondition(5_000) { challenge.answers.isNotEmpty() || challenge.disconnectedForSilence },
            "the venue should hear an answer or give up on it",
        )
        assertTrue(challenge.answeredInTime, "the row's run must answer in time: answers=${challenge.answers}")
        assertEquals(listOf("ack-BETA"), answerTexts(), "the answer must carry the second row's stream")
        val run = awaitLogonRuns(1).single()
        val entry = run.entries.single()
        assertEquals(RunState.PASSED, entry.state, "the row's run passes: $entry")
        assertEquals("second", entry.row?.name, "the record keeps the row the logon ran: $entry")
        assertEquals("Handshake [second]", run.nameOf(0), "the entry is named the way a run of that row by hand is")
        val report = assertNotNull(viewModel.runRecordStore.readEntry(run.id, 1), "the run is recorded").result
        val stream = report.variables.single { it.name == "stream" }
        assertEquals("BETA" to VariableSource.ROW, stream.value to stream.source, "the row seeded the scope: $stream")
        val trigger = report.steps.first()
        assertTrue("examples row 'second'" in trigger.detail.orEmpty(), "the trigger row names the row: $trigger")
    }

    /** One live row and no `triggerRow`: that row is the one a logon runs. */
    @Test
    fun `a logon runs the one live examples row when the scenario names none`() {
        val handshake = server.handshake()
        save(rowScenario(ExampleRow("only", mapOf("stream" to "GAMMA")), ExampleRow("parked", mapOf("stream" to "DELTA"), muted = true)))

        connect()

        val challenge = awaitChallenge(handshake, 1)
        assertTrue(
            awaitCondition(5_000) { challenge.answers.isNotEmpty() || challenge.disconnectedForSilence },
            "the venue should hear an answer or give up on it",
        )
        assertTrue(challenge.answeredInTime, "the row's run must answer in time: answers=${challenge.answers}")
        assertEquals(listOf("ack-GAMMA"), answerTexts(), "the answer must carry the live row's stream")
        val entry = awaitLogonRuns(1).single().entries.single()
        assertEquals(RunState.PASSED to "only", entry.state to entry.row?.name, "the live row ran and passed: $entry")
    }

    /**
     * A file with two live rows and no `triggerRow`, written past every save door, does not run at the logon:
     * the logon starts nothing, and the notification names the scenario and why.
     */
    @Test
    fun `a logon skips a scenario with several live rows and none named, by name`() {
        val handshake = server.handshake()
        save(rowScenario(ExampleRow("first", mapOf("stream" to "ALPHA")), ExampleRow("second", mapOf("stream" to "BETA"))))

        connect()

        val challenge = awaitChallenge(handshake, 1)
        assertTrue(awaitCondition(3_000) { challenge.disconnectedForSilence }, "nothing should answer, so the venue gives up")
        assertTrue(challenge.answers.isEmpty(), "no run should have answered: ${challenge.answers}")
        assertTrue(logonRuns().isEmpty(), "no run should have started: ${logonRuns().map { it.label }}")
        val skipped = viewModel.notifications.map { it.message }.filter { it.startsWith("run on logon skipped:") }
        assertTrue(skipped.isNotEmpty(), "the skip should be shown: ${viewModel.notifications.map { it.message }}")
        assertTrue("'Handshake'" in skipped.single(), "the skip should name the scenario: $skipped")
        assertTrue("must be told which" in skipped.single(), "the skip should say a logon needs telling which row: $skipped")
        assertTrue("'first', 'second'" in skipped.single(), "the skip should list the live rows: $skipped")
    }

    /**
     * Case 2. Every logon runs it once, the first connect, and three manual reconnects made 10 ms after
     * the disconnect, and each answer echoes that connection's own id, never the previous one's.
     */
    @Test
    fun `every logon runs it exactly once, and each answer echoes that connection's own id`() {
        val handshake = server.handshake()
        save(handshakeScenario(RunTrigger.LOGON))
        val (profile, session) = connect()

        for (connection in 1..4) {
            val challenge = awaitChallenge(handshake, connection)
            assertTrue(
                awaitCondition(5_000) { challenge.answers.isNotEmpty() || challenge.disconnectedForSilence },
                "connection $connection: the venue should hear an answer or give up on it",
            )
            awaitLogonRuns(connection)
            if (connection < 4) {
                session.disconnect()
                Thread.sleep(10)
                viewModel.connectProfile(profile.id, profile)
            }
        }
        // Long enough for a second run on any logon to have answered too.
        Thread.sleep(1_500)

        assertEquals(4, handshake.challenges.size, "one handshake per logon")
        val answeredIn = handshake.challenges.map { it.answers.firstOrNull()?.second }
        println("run on logon: answers in ms per logon $answeredIn")
        handshake.challenges.forEach { challenge ->
            assertEquals(
                listOf(challenge.requestId),
                challenge.answers.map { it.first },
                "connection ${challenge.connection} must be answered once, with its own id",
            )
            assertTrue(
                challenge.answeredInTime,
                "connection ${challenge.connection} answered too late: ${challenge.answers}",
            )
        }
        assertEquals(4, logonRuns().size, "exactly one run per logon: ${logonRuns().map { it.label }}")
    }

    /** Case 3. A scenario run by hand stays run by hand. */
    @Test
    fun `with the trigger off a logon starts nothing`() {
        val handshake = server.handshake()
        save(handshakeScenario(RunTrigger.MANUAL))

        connect()

        val challenge = awaitChallenge(handshake, 1)
        assertTrue(
            awaitCondition(3_000) { challenge.disconnectedForSilence },
            "nothing should answer, so the venue gives up",
        )
        assertTrue(challenge.answers.isEmpty(), "no run should have answered: ${challenge.answers}")
        assertTrue(logonRuns().isEmpty(), "no run should have started: ${logonRuns().map { it.label }}")
        assertEquals(null, viewModel.activeRunSet.value, "no run should have started")
    }

    /**
     * Case 4. The session drops while the run is waiting for a step it would otherwise wait 15 s for. The run
     * ends with the reason within about a second, and the logon that auto-reconnect makes next runs it again,
     * and that run passes.
     */
    @Test
    fun `a run whose session drops ends with the reason at once, and the next logon's run passes`() {
        val handshake =
            server.handshake { connection ->
                if (connection == 1) TestFixServer.AfterAnswer.DROP else TestFixServer.AfterAnswer.FOLLOW_UP
            }
        save(handshakeScenario(RunTrigger.LOGON, followUpTimeoutMs = 15_000))
        connect(autoReconnect = true)

        val first = awaitChallenge(handshake, 1)
        assertTrue(awaitCondition(5_000) { first.answers.isNotEmpty() }, "the first connection should be answered")
        val droppedAt = System.currentTimeMillis()
        val firstRun = awaitLogonRuns(1).single()
        val endedIn = System.currentTimeMillis() - droppedAt
        val firstEntry = firstRun.entries.single()
        assertEquals(RunState.FAILED, firstEntry.state, "a run cut off by its session is not passed: $firstEntry")
        val report = assertNotNull(viewModel.runRecordStore.readEntry(firstRun.id, 1), "the run is recorded").result
        val reason = report.steps.last()
        assertEquals("interrupted", reason.kind, "the run should end on its interruption: ${report.steps}")
        assertTrue("disconnected" in reason.detail.orEmpty(), "the run should say its session disconnected: $reason")
        assertTrue(endedIn < 2_000, "the run should end promptly, not wait out its 15 s step: ended after $endedIn ms")
        println("run on logon: the dropped connection's run was recorded as ended $endedIn ms after the drop")

        val second = awaitChallenge(handshake, 2, timeoutMs = 15_000)
        assertTrue(awaitCondition(5_000) { second.answers.isNotEmpty() }, "auto-reconnect's logon should be answered")
        assertTrue(second.answeredInTime, "the next logon's answer must echo its own id in time: ${second.answers}")
        val secondRun = awaitLogonRuns(2).first { it.id != firstRun.id }
        assertEquals(RunState.PASSED, secondRun.entries.single().state, "the next logon's run passes: $secondRun")
    }

    /** Case 5. Disconnecting is not a logon, and starts nothing. */
    @Test
    fun `a disconnect the user asks for starts no run`() {
        val handshake = server.handshake()
        save(handshakeScenario(RunTrigger.LOGON))
        val (_, session) = connect()
        val challenge = awaitChallenge(handshake, 1)
        assertTrue(awaitCondition(5_000) { challenge.answeredInTime }, "the logon's run should answer")
        awaitLogonRuns(1)

        session.disconnect()
        assertTrue(
            awaitCondition(5_000) { session.connectionState.value == FixConnectionState.DISCONNECTED },
            "the session should disconnect (state=${session.connectionState.value})",
        )
        Thread.sleep(1_500)

        assertEquals(1, logonRuns().size, "the disconnect started a run: ${logonRuns().map { it.label }}")
        assertTrue(viewModel.busySessions.value.isEmpty(), "nothing should be running: ${viewModel.busySessions.value}")
        assertEquals(1, handshake.challenges.size)
    }

    /** Turning the setting off stops the next logon's run. */
    @Test
    fun `turning the trigger off stops future runs`() {
        val handshake = server.handshake()
        val scenario = handshakeScenario(RunTrigger.LOGON)
        save(scenario)
        val (profile, session) = connect()
        assertTrue(awaitCondition(5_000) { awaitChallenge(handshake, 1).answeredInTime }, "the first logon runs it")
        awaitLogonRuns(1)

        save(scenario.copy(trigger = RunTrigger.MANUAL))
        session.disconnect()
        Thread.sleep(10)
        viewModel.connectProfile(profile.id, profile)

        val second = awaitChallenge(handshake, 2)
        assertTrue(awaitCondition(3_000) { second.disconnectedForSilence }, "nothing should answer the second logon")
        assertTrue(second.answers.isEmpty(), "the second logon should start nothing: ${second.answers}")
        assertEquals(1, logonRuns().size)
    }

    /**
     * When another run holds the session at the logon, the triggered run does not start, and it says so rather
     * than being dropped silently: a notification and a log line, "run on logon skipped: <reason>".
     */
    @Test
    fun `a logon while another run holds the session is skipped, and says so`() {
        val handshake = server.handshake()
        save(handshakeScenario(RunTrigger.LOGON))
        val (profile, session) = connect()
        assertTrue(awaitCondition(5_000) { awaitChallenge(handshake, 1).answeredInTime }, "the first logon runs it")
        awaitLogonRuns(1)

        // A run by hand that holds the session for 8 s, waiting for a message that never comes.
        val waiting =
            Scenario(
                id = "waiter-$runId",
                name = "Waiter",
                steps =
                    listOf(
                        ScenarioStep.Wait(
                            session = client,
                            match = MatchPredicate(messageType = "B", fields = listOf(TagValue(58, "never"))),
                            timeoutMs = 8_000,
                        ),
                    ),
            )
        val manual = Thread { viewModel.runScenarioBlocking(waiting) }.apply { start() }
        assertTrue(
            awaitCondition(5_000) { client in viewModel.busySessions.value },
            "the waiting run should hold $client",
        )
        session.disconnect()
        Thread.sleep(10)
        viewModel.connectProfile(profile.id, profile)

        val second = awaitChallenge(handshake, 2)
        assertTrue(
            awaitCondition(3_000) { second.disconnectedForSilence },
            "nothing should answer while the slot is held",
        )
        assertTrue(second.answers.isEmpty(), "the second logon should start nothing: ${second.answers}")
        val skipped = viewModel.notifications.map { it.message }.filter { it.startsWith("run on logon skipped:") }
        assertTrue(skipped.isNotEmpty(), "the skip should be shown: ${viewModel.notifications.map { it.message }}")
        assertTrue("Waiter" in skipped.first(), "the skip should name what holds the session: $skipped")
        viewModel.requestScenarioStop()
        manual.join(15_000)
        assertEquals(1, logonRuns().size, "the skipped logon must not have recorded a run")
    }

    // ------------------------------------------------------------------ helpers

    private fun TestFixServer.handshake(
        afterAnswer: (Int) -> TestFixServer.AfterAnswer = { TestFixServer.AfterAnswer.NOTHING },
    ): TestFixServer.Handshake =
        TestFixServer.Handshake(windowMs = 1_000, afterAnswer = afterAnswer).also { afterLogon = it }

    /**
     * Answers the venue's handshake: binds this connection's request, captures its id and echoes it back.
     * With [followUpTimeoutMs] it then waits that long for the venue's follow-up News.
     */
    private fun handshakeScenario(trigger: RunTrigger, followUpTimeoutMs: Long? = null): Scenario =
        Scenario(
            id = "handshake-$runId",
            name = "Handshake",
            steps =
                listOfNotNull(
                    ScenarioStep.Expect(
                        session = client,
                        match = MatchPredicate(messageType = "B", fields = listOf(TagValue(58, "handshake"))),
                        timeoutMs = 5_000,
                        expectation =
                            Expectation(
                                fields = listOf(FieldExpectation(148, Matcher.Presence, bindAs = "reqId")),
                                messageType = "B",
                            ),
                    ),
                    ScenarioStep.Send("35=B|148=\${reqId}|33=1|58=ack|", session = client),
                    followUpTimeoutMs?.let { timeout ->
                        ScenarioStep.Expect(
                            session = client,
                            match = MatchPredicate(messageType = "B", fields = listOf(TagValue(58, "followup"))),
                            timeoutMs = timeout,
                            expectation =
                                Expectation(
                                    fields = listOf(FieldExpectation(148, Matcher.Presence)),
                                    messageType = "B",
                                ),
                        )
                    },
                ),
            trigger = trigger,
        )

    /** [handshakeScenario] on logon, with an examples table whose `stream` column the answer's text carries. */
    private fun rowScenario(vararg rows: ExampleRow, triggerRow: String? = null): Scenario {
        val base = handshakeScenario(RunTrigger.LOGON)
        return base.copy(
            steps = base.steps.map { if (it is ScenarioStep.Send) it.copy(raw = "35=B|148=\${reqId}|33=1|58=ack-\${stream}|") else it },
            examples = Examples(listOf("stream"), rows.toList()),
            triggerRow = triggerRow,
        )
    }

    /** The Text(58) of every News the venue received from the client, in arrival order. */
    private fun answerTexts(): List<String> =
        server.applicationMessages
            .filter { TestFixServer.fieldValue(it, 35) == "B" }
            .mapNotNull { TestFixServer.fieldValue(it, 58) }

    private fun save(scenario: Scenario) {
        assertTrue(viewModel.scenarioService.save(scenario), "the scenario should save")
    }

    private fun connect(autoReconnect: Boolean = false): Pair<FixConnectionProfile, FixMessageSession> {
        val profile =
            FixConnectionProfile(
                name = client,
                config =
                    FixConnectionConfig(
                        connectionType = FixConnectionConfig.ConnectionType.INITIATOR,
                        senderCompID = client,
                        targetCompID = "VENUE$runId",
                        host = "localhost",
                        port = server.port.toString(),
                        socketConnectHost = "localhost",
                        beginString = "FIX.4.4",
                        autoReconnect = autoReconnect,
                        reconnectInterval = "1",
                        resetOnLogon = true,
                        fileStorePath = File(testDir, "store").absolutePath,
                        fileLogPath = File(testDir, "log").absolutePath,
                    ),
            )
        viewModel.saveConnectionProfile(profile)
        viewModel.connectProfile(profile.id, profile)
        return profile to viewModel.sessions.first { it.title == client }
    }

    private fun awaitChallenge(
        handshake: TestFixServer.Handshake,
        connection: Int,
        timeoutMs: Long = 10_000,
    ): TestFixServer.Challenge {
        assertTrue(
            awaitCondition(timeoutMs) { handshake.challenges.size >= connection },
            "logon $connection should reach the venue (challenges=${handshake.challenges.size})",
        )
        return handshake.challenges[connection - 1]
    }

    /** The runs a logon started, as run history records them, newest first. */
    private fun logonRuns(): List<RunSet> {
        viewModel.refreshRunConfigurations()
        return viewModel.runConfigurations.value.setRecords
            .filter { "on logon of" in it.label }
    }

    /** Waits for [count] logon runs to have finished, and returns them. */
    private fun awaitLogonRuns(count: Int): List<RunSet> {
        assertTrue(
            awaitCondition(10_000) {
                val finished = logonRuns().count { it.status != RunSetStatus.RUNNING }
                finished >= count && viewModel.busySessions.value.isEmpty()
            },
            "$count run(s) started by a logon should have finished: ${logonRuns().map { "${it.label} ${it.status}" }}",
        )
        return assertNotNull(logonRuns().takeIf { it.size >= count })
    }

    private fun awaitCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (runCatching(predicate).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(10)
        }
    }
}
