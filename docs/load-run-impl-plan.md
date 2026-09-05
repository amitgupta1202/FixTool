# Load runs and the store under them, implementation plan

Companion to the design note [`docs/mockups/load-run.html`](mockups/load-run.html), which holds the
mockups, the mechanism diagrams and the reasoning. This is the task-level breakdown for issues
[#42](https://github.com/amitgupta1202/FixTool/issues/42) (selectable message store and log) and
[#43](https://github.com/amitgupta1202/FixTool/issues/43) (load run mode): what lands in each step,
which files it touches, the new types and their signatures, the tests, and the exit criteria that
gate the next step.

**Status: delivered, 2026-09-05.** All four steps landed on `main` the same day the plan was written,
each as the commits it describes. Two things in the plan were not built as written and are noted where they
apply: rendering happens on the pacer's thread rather than through a render-ahead producer (2.4), because a
render is a clone and a `setString` and the lag spans would say if that ever changed, and the live
verification with the `verify` skill (3.5) was left for a session with the app running.

Work lands as commits straight to `main`, as this repo does. A step is several commits, not one.
The one rule: `main` builds, its tests are green and the app is usable after every commit, so any
step can pause at any commit boundary. Sizing is relative (S about half a day, M one to two days,
L three to five days), assuming familiarity with the codebase.

```
Step 1 (#42)              Step 2 (#43)                    Step 3 (#43)              Step 4 (#43)
store + log fields ─────► engine + `fixtool load` ──────► dialog + document ──────► POST /load + MCP
NoopLogFactory            compiled template, pacer,        editor Load button,       job routes,
panel rows, refusal       stamp matcher, report,           Run menu item, Recent     fixtool_load
                          loads/ record, exit codes
```

---

## Decisions this plan implements

All from the design note. Listed here so a commit can cite the number.

| # | Decision | Lands in |
|---|----------|----------|
| D1 | Two enum fields on the profile, `messageStore` (FILE or MEMORY) and `messageLog` (FILE or NONE), defaults FILE. Additive on disk. | 1.1 |
| D2 | Memory store without Reset on Logon is **refused** with a named reason, never silently fixed. | 1.1, 2.7, 3.2 |
| D3 | The factories are chosen once in `FixConnectionManager` and passed to `VenueSessionProvider` unchanged. | 1.1 |
| D4 | A per-run override of D1 for the sessions a load run opens, never written back to the profile. | 2.6, 3.2 |
| D5 | The matcher reads **socket stamps** from every participating session, one object for the whole run. Not the pane, not `fromApp`. | 2.3 |
| D6 | Per-message template resolution is string substitution over a compiled template. Kotlin expressions are evaluated once per lane at prepare time. | 2.2 |
| D7 | `${sessionIndex}` is the profile slot, as `Lane.seed()` defines it. | 2.2 |
| D8 | "Issued" is three numbers: requested, handed to the engine, left the socket. Completeness is judged over the last. | 2.3, 2.5 |
| D9 | The pacer never skips a scheduled message. It runs late and reports lag spans. | 2.4 |
| D10 | The record keeps the report, the wire of every unmatched request (capped at 1,000 plus a count), and 50 specimen pairs. Never every message. | 2.5 |
| D11 | Three separate verdicts: completeness, rate, tool. Exit 1 on unmatched or tool-limited, `--strict-rate` promotes a shortfall. Exit 2 when the run could not start. | 2.5, 2.7 |
| D12 | A load run is its own record type under `loads/`, not a `RunSet`. Recent lists both by time. | 2.5, 3.4 |
| D13 | Two doors, one dialog: the editor's Load button and the rail's "Load run…". | 3.2, 3.3 |
| D14 | The correlation tag is inferred from the template when not given: the first standard correlation tag it carries, both sides. | 2.1 |

---

## Step 1, #42: selectable message store and log

**Goal:** a profile can say "memory store" and "no message log", the venue path honours it, the
panel exposes it, the control surface passes it through. No load code. Useful alone for a soak run
and for repeatable sequence numbers.

### 1.1 · Model and manager (S)

| Item | File | Change |
|---|---|---|
| Enums | `model/FixConnectionConfig.kt` | `enum class MessageStoreKind { FILE, MEMORY }`, `enum class MessageLogKind { FILE, NONE }`. Two fields on the config with FILE defaults: `val messageStore: MessageStoreKind = MessageStoreKind.FILE`, `val messageLog: MessageLogKind = MessageLogKind.FILE`. `@Serializable` already, `ignoreUnknownKeys` already on, so old files read unchanged and new files carry the two keys. |
| Refusal | `model/FixConnectionConfig.kt` | `fun storeProblem(): String?` returning the sentence when `messageStore == MEMORY && !resetOnLogon`: *"Memory store, and Reset on Logon is off: the next logon will start at 1 while the venue expects the number it last saw. Turn Reset on Logon on, or keep the file store."* One place owns the words. The manager, the panel, the CLI and the dialog all call it. |
| No-op log | `service/NoopLogFactory.kt` (new) | `object NoopLogFactory : LogFactory` whose `create(SessionID)` returns a `Log` with empty `onIncoming`, `onOutgoing`, `onEvent`, `onErrorEvent`, `clear`. QuickFIX/J ships no null log. |
| Factory choice | `service/FixConnectionManager.kt` | In `init`: `messageStoreFactory = when (config.messageStore) { FILE -> FileStoreFactory(settings); MEMORY -> MemoryStoreFactory() }`, `logFactory = when (config.messageLog) { FILE -> FileLogFactory(settings); NONE -> NoopLogFactory }`. `init` throws `IllegalArgumentException(config.storeProblem())` when the problem is non-null, so no socket is ever opened on a refused config. `start()` skips `clearStoreFiles()` on MEMORY. The two `mkdirs()` calls become conditional on the kind that needs the directory. `VenueSessionProvider` already takes both factories as parameters. Nothing changes there. |
| Session | `model/FixMessageSession.kt` | `connect()` catches the manager's `IllegalArgumentException` and surfaces it as `ERROR` with the message through `onError`, like any other connect failure. |

Tests, `jvmTest/service/FixConnectionManagerStoreTest.kt` (new):

- FILE/FILE builds `FileStoreFactory` and `FileLogFactory` (read the private fields by reflection or expose `internal val` for the test, as the repo does elsewhere).
- MEMORY/NONE builds `MemoryStoreFactory` and `NoopLogFactory`, and creates no `store/` or `log/` directory under a temp workspace.
- MEMORY with `resetOnLogon = false` throws before `start()`, with `storeProblem()`'s text.
- `FixConnectionConfig` round trip: a JSON written before the fields existed decodes with FILE/FILE. A JSON with the fields decodes to what it says.

### 1.2 · Connection panel (S)

| Item | File | Change |
|---|---|---|
| Two radio rows | `ui/ConnectionPanel.kt` | A "Store and log" label under "Reset Options" in Advanced Settings, then two rows of two radios each: Message store File / Memory, Message log File / None. Same `remember` state pattern as `resetOnLogon` (load at the profile-load site near line 174, save near 341, clone near 432, the second save near 1779). |
| Hint line | `ui/ConnectionPanel.kt` | One line under the rows that reads the three choices together. Amber with `storeProblem()`'s text when it is non-null. Otherwise a plain sentence: file store and log under `store/` and `log/`, or memory store with sequence numbers starting at 1 and nothing written. Mockup A in the design note. |
| Save gate | `ui/ConnectionPanel.kt` | Save stays enabled. The refusal happens at connect time with the same sentence, so the user can save a half-edited profile and fix it next. |

Tests, `jvmTest/ui/ConnectionPanelStoreTest.kt` (new, Compose UI test as the existing panel tests do): the radios reflect a loaded profile, toggling Memory with Reset on Logon off shows the amber hint, saving writes the two fields.

### 1.3 · Control surface and docs (S)

| Item | File | Change |
|---|---|---|
| Passthrough | `control/ControlServer.kt` | Nothing to code: `upsertProfile` merges per top-level key through `FixConnectionConfig.serializer()`, so `"messageStore":"MEMORY"` already applies. Add the two keys to the `GET /profiles?profile=` example and to `acceptorProblems`-style warnings: a POST that produces a non-null `storeProblem()` returns it under `warnings`. |
| Docs | `docs/AUTOMATION.md` | The two fields in the profile config table with their values and the refusal rule. |
| Help | `composeApp/src/jvmMain/resources/help.html` | Three sentences under Managing Connections, after Reset Options: what each choice does, when to pick Memory, and that Memory needs Reset on Logon. |
| Changelog | `CHANGELOG.md` | An Added entry under Unreleased, written for the reader: a soak run no longer grows a store and a log without bound, and a memory store makes a run repeatable. |

### Exit criteria for Step 1

- A profile saved with MEMORY/NONE connects to the loopback venue, exchanges a round trip that lands in the pane and in a run record, and leaves nothing under `store/` or `log/`.
- A venue profile on MEMORY creates its per-client sessions without store files.
- MEMORY without Reset on Logon is refused at connect with the one sentence, in the panel and through `POST /connect`.
- Every existing `connection_profiles.json` loads unchanged.

---

## Step 2, #43: the engine and the command

**Goal:** `fixtool load` works end to end against the loopback venue, writes the record, prints the
summary, exits with the right code. No screen yet. This is the large step and it is the one the
acceptance criteria in #43 are tested against.

Everything new lives under `service/load/` and `headless/`, plus three small hooks on existing
classes (2.0). The pure parts have no Compose and no QuickFIX/J socket in their tests.

### 2.0 · Hooks on existing classes (S)

| Item | File | Change |
|---|---|---|
| Stamp listeners | `model/FixMessageSession.kt` | `fun addStampListener(listener: (SocketStamp) -> Unit): AutoCloseable`. A `CopyOnWriteArrayList`. `onSocketStamp` becomes: feed the latency tracker if any, then each listener. Cost with no listener: one empty-list check, on the MINA I/O thread. |
| Prepared send | `service/QuickFixService.kt` | `SessionEndpoint.sendPrepared(message: quickfix.Message): Boolean`, implemented on `BoundEndpoint` as `withSession(resolve()) { _, id -> Session.sendToTarget(message, id) }`. No lint, no validation, no re-parse. `toApp` still fires, so the pane still sees the outgoing message. |
| Prepared send | `model/FixMessageSession.kt` | `fun sendPrepared(message: quickfix.Message): Boolean = endpoint?.sendPrepared(message) ?: false`. Deliberately **not** touching `_recentlySentMessageTimestamp` and **not** launching the three-second highlight coroutine: one coroutine per message at 500/s is the kind of cost this path exists to avoid. |
| Distribution | `service/RunSetStats.kt` | `Distribution` gains `min: Long`, `p99: Long`, `mean: Long`. `distribution(samples)` computes all six. `toJson` writes them, `fromJson` reads them with `?: 0` fallbacks for records written before. `describe()` unchanged. |
| Wire tag scan | `service/WireTags.kt` (new) | `fun tagValue(wire: String, tag: Int): String?`, lifted verbatim from `LatencyTrackingService.tagValue`, which becomes a call to it. The matcher needs the same scan on the same thread. |

Tests: `FixMessageSessionStampListenerTest` (a listener receives what `onSocketStamp` is fed and stops after `close()`), `RunSetStatsTest` extended for the three new fields and the old-JSON fallback, `WireTagsTest` moved from the latency test's cases.

### 2.1 · The plan and the template source (M)

| Item | File | Change |
|---|---|---|
| Plan | `model/load/LoadPlan.kt` (new) | `data class LoadPlan(id, label, template: LoadTemplate, profileId, listenProfileIds: List<String>, shape: LoadShape, match: LoadMatch, settleMs: Long, seed: Map<String, String>, storeAndLog: StoreAndLogOverride?, strictRate: Boolean)`. `sealed interface LoadShape { data class Burst(val count: Int); data class Rate(val perSecond: Int, val forMs: Long) }`. `data class LoadMatch(val requestTag: Int, val replyTag: Int = requestTag, val replyType: String? = null)`. `data class StoreAndLogOverride(val store: MessageStoreKind, val log: MessageLogKind)`. |
| Template | `model/load/LoadTemplate.kt` (new) | `data class LoadTemplate(val name: String, val fields: List<Pair<Int, String>>)` with `val msgType: String get()`. `fun inferMatch(): LoadMatch?` (D14): the first tag in `Minting.STANDARD_CORRELATION_TAGS` order that the fields carry. Null when none, which the CLI and the dialog turn into a refusal naming the tags it looked for. |
| Source | `service/load/LoadTemplates.kt` (new) | `fun resolve(name: String, profileId: String?, savedMessages: SavedMessagesService, profiles: List<FixConnectionProfile>): LoadTemplate?`. A file path first (one message, `|` or SOH), then a saved message by id or name under `profileId`, then under every profile. Excluded fields are dropped. |
| Id | `service/load/LoadIds.kt` or reuse | `RunSets.id(now, label)` already gives `20260905-140211-nos-eur-usd-1m`. Reuse it. |

Tests: `LoadTemplateTest` (inference picks 11 over 37 when both present, none when neither), `LoadTemplatesTest` (file, name under the profile, name elsewhere, excluded field dropped).

### 2.2 · The compiled template (M)

The load run's whole per-message cost lives here, so this is a pure class with a benchmark.

| Item | File | Change |
|---|---|---|
| Compile | `service/load/CompiledTemplate.kt` (new) | `class CompiledTemplate private constructor(val slots: List<Slot>)`. `sealed interface Slot { val tag: Int }` with `Literal(tag, value)`, `Variable(tag, parts: List<Part>)` where a `Part` is a literal run or a variable name, `Generator(tag, gen: Gen)` for `uuid`, `uuid:N`, `now`, `utcnow` with pattern and offset, and `Once(tag, expression)` for everything else. `companion fun compile(fields: List<Pair<Int, String>>, dictionary: FixDictionaryAdapter): CompiledTemplate`. Classification reuses `ShorthandTemplateExpander`'s own regexes for the generator forms so `${now+5min}` means the same thing here as in a scenario. A field with a mix, say `ORD-${run}-${uuid:8}`, compiles to a `Variable` whose parts may include generator parts. |
| Reporting | same | `val perMessageTags: List<Int>`, `val fixedTags: List<Int>`. `Once` tags are **fixed**. |
| Prepare | same | `fun prepare(lane: Lane, seed: Map<String, String>, resolveOnce: (String) -> String): LanePrototype`. Evaluates every `Once` slot through `resolveOnce`, which the runner backs with `FixMessageTemplate.evaluate` under its existing lock. Builds one `quickfix.Message` prototype for the lane with the fixed tags set and the per-message tags absent. |
| Render | same | `class LanePrototype(private val prototype: quickfix.Message, private val perMessage: List<Slot>, private val scope: Map<String, String>)` with `fun render(messageIndex: Int, clock: () -> Long): quickfix.Message`: clone the prototype, `setString` each per-message tag from a `StringBuilder` join. Header fields are left to QuickFIX/J at send. |

Tests, `CompiledTemplateTest`:

- Each kind classifies as intended, including the mixed field and a `${id = uuid}` assignment (per message when the right side is a generator or a variable, `Once` otherwise).
- `${sessionIndex}` renders the lane's slot, not a list position (D7).
- Two renders differ only in the per-message tags. Two lanes differ only in the lane variables and the `Once` results.
- `perMessageTags` and `fixedTags` partition the template's tags.

Benchmark, `perf/CompiledTemplateBenchmarkTest.kt` using `Bench`: a template with `${utcnow}` in every field renders 10,000 messages. The pinned number is bytes per render, as the harness does. Time is printed, and the test asserts it stays under one second on the CI floor only as a sanity guard.

### 2.3 · The stamp matcher (M)

| Item | File | Change |
|---|---|---|
| Matcher | `service/load/StampMatcher.kt` (new) | `class StampMatcher(private val match: LoadMatch, private val requestType: String, private val issuingSessions: Set<SessionID>)`. `fun onStamp(stamp: SocketStamp)`, safe from any thread. On `SEND` from an issuing session whose `35` is `requestType` and which carries `match.requestTag`: put into `pending: ConcurrentHashMap<String, Pending(sendMicros, laneSlot, wire)>`, count `leftSocket`. On `RECEIVE` from **any** participating session whose `35` is not admin (`0 1 2 3 4 5 A`) and, when `replyType` is set, equals it, and which carries `match.replyTag`: `pending.remove(id)` gives a match with `rtt = micros - sendMicros` and the lane the request left on. A second arrival for an id already matched counts a duplicate, tracked in a bounded `matchedIds` set. After `closeSettle()`, an arrival that would have matched counts `late` instead. |
| Samples | same | Round trips in a growable `LongArray` with a parallel `IntArray` of the second each landed in, so per-second buckets and the distribution are computed once at the end. No `LatencyStatsAccumulator`, whose ring of 10,000 would make the percentiles of a 300,000-message run wrong. |
| Specimens | same | The first 50 matched pairs keep both wires. Nothing else keeps a reply's wire. |
| Snapshot | same | `fun snapshot(): MatchCounts(leftSocket, matched, unmatchedNow = pending.size, duplicates, late, lastMatchedMicros)` for progress. `fun finish(): MatchResult(counts, roundTripsMicros: LongArray, perSecond: List<SecondBucket>, unmatched: List<Unmatched(id, laneSlot, sentMicros, wire)>, specimens)`. |

Memory note for the record: `pending` holds the request wire until it is matched, so the working set is bounded by the number outstanding at once, which for a rate run is rate times drain time. It is the evidence file for unmatched requests (D10), so it stays.

Tests, `StampMatcherTest`, fed synthetic `SocketStamp`s with two `SessionID`s:

- A request on session A answered on session B matches, once (the cross-session acceptance criterion).
- The same id arriving twice is one match and one duplicate.
- A heartbeat and a Logon on the reply side never match anything.
- With `replyType = "8"`, a `35=j` carrying the id does not match.
- A send is still pending after any amount of time until `closeSettle()`, and a reply after `closeSettle()` is late, not matched.
- 100,000 sends and 100,000 replies give exactly 100,000 matched with the right percentiles for a known distribution (exactness criterion).
- A `SEND` from a listen-only session is not counted as issued.

### 2.4 · The pacer (M)

| Item | File | Change |
|---|---|---|
| Pacer | `service/load/Pacer.kt` (new) | `class Pacer(private val shape: LoadShape, private val lanes: Int, private val clock: Clock)` where `Clock` is `nanoTime` plus `parkNanos`, replaceable in tests. `fun run(issue: (laneIndex: Int, messageIndex: Int) -> Boolean, cancelled: () -> Boolean): IssueStats`. Burst: message `i` goes to lane `i % lanes` as soon as the previous returned. Rate: `scheduled(i) = t0 + i * 1_000_000_000L / perSecond`, park until 200µs before, spin to the mark, issue, record `lag = now - scheduled`. Never skips (D9). |
| Stats | same | `data class IssueStats(requested, handedToEngine, issueFailures, firstIssueNanos, lastIssueNanos, perSecondIssued: IntArray, maxLagNanos, shortfalls: List<Shortfall(fromSecond, toSecond, minPerSecond, behind)>)`. A shortfall span is consecutive seconds where issued fell below `perSecond` by more than 2 percent. The tolerance is a constant and is written into the report as `tolerance`. |
| Ahead | not built | Rendering happens on the pacer's thread, per message: a clone and a `setString` or two, far inside a two-millisecond budget. A render-ahead producer stays the follow-on if a template ever makes it otherwise, and the pacer's lag spans would say so. |

Tests, `PacerTest` with a fake clock: a burst of 100 over 4 lanes issues 25 per lane in round-robin order. A rate of 1,000/s for 3 seconds schedules 3,000 with the right spacing. A clock that stalls 1.5s mid-run produces one shortfall span with the right bounds and no skipped index. `cancelled()` returning true stops after the current message and reports what was issued.

### 2.5 · The report, the record and the verdict (M)

| Item | File | Change |
|---|---|---|
| Report | `model/load/LoadReport.kt` (new) | The JSON in the design note as a data class: `id, template(name, msgType, perMessageTags), profile, lanes, listen, shape, match, settleMs, seed, storeAndLog, issue(requested, handedToEngine, leftSocket, firstSendAt, lastSendAt, spanMs, achievedPerSecond, prepareMs), rate?(requestedPerSecond, heldForMs, shortfalls, maxLagMs, tolerance), replies(matched, unmatched, duplicates, late, lastMatchedAt), timing(elapsedMs, drainMs), roundTrip: Distribution?, perSecond: List<SecondBucket>, tool(discarded, neverLeftSocket, issueFailures), unmatched: List<Unmatched>, unmatchedTruncated: Int, verdict(completeness, rate, tool, exitCode)`. |
| Verdict | same | `object LoadVerdicts { fun of(report parts, strictRate): Verdict }`. Completeness `COMPLETE | UNMATCHED`. Rate `N_A | HELD | SHORTFALL`. Tool `CLEAN | LIMITED` when `discarded > 0 || neverLeftSocket > 0 || issueFailures > 0`. Exit 1 when `UNMATCHED || LIMITED || (strictRate && SHORTFALL)`, else 0 (D11). |
| Codec | `service/load/LoadReportCodec.kt` (new) | `toJson(report): JsonObject`, `fromJson(obj): LoadReport`. `roundTrip` through `RunSetStats`'s distribution JSON. `unmatched` capped at 1,000 in the JSON with `unmatchedTruncated` carrying the rest (D10). |
| JUnit | same | `toJUnitXml(report): String`: one `<testsuite name="load: <label>" tests="3">` with cases `completeness` (failure message lists up to 20 ids and the count), `rate` (`<skipped/>` for a burst, failure only under strict rate, otherwise the shortfall in `<system-out>`), `tool` (failure names which counter). Reuses `ScenarioReport`'s `esc` and `timeAttr`, made `internal`. |
| Store | `service/load/LoadRecordStore.kt` (new) | Mirrors `RunRecordStore`'s shape over `WorkspacePaths.current.loads` (new `val loads: File get() = File(root, "loads")`): `reserve(id)`, `begin(plan)` writes a `load.json` with `status: running` so a poller has something to fetch, `finish(report, unmatchedWire: Sequence<String>, specimens)` writes `load.json`, `unmatched.fix`, `specimens.fix`, `list(): List<LoadSummary>` newest first, `read(id): LoadReport?`, `prune(keep)` using `AppSettings.runRecordsKept`. Interrupted runs heal to `status: stopped` on read, as `healInterrupted` does. |

Tests: `LoadReportCodecTest` (round trip, truncation, old-JSON tolerance), `LoadVerdictsTest` (every cell of the exit-code table), `LoadJUnitTest` (three cases, skipped rate on burst, agreement between exit code and `failures=`), `LoadRecordStoreTest` (begin then finish, prune keeps the newest N, a `running` record from a dead process reads as stopped).

### 2.6 · The runner (L)

| Item | File | Change |
|---|---|---|
| Host | `service/load/LoadHost.kt` (new) | `interface LoadHost { fun openLanes(profileId, override: StoreAndLogOverride?): List<LoadLane>; fun openListeners(profileIds, override): List<LoadLane>; fun resolveOnce(expression, scope, lane): String; fun discardedNow(): Long; fun disconnectAll(); fun now(): Long }`. `class LoadLane(val lane: Lane, val session: FixMessageSession) { val sessionId: SessionID? get() = session.endpoint?.sessionId; fun send(m: quickfix.Message) = session.sendPrepared(m) }`. |
| Headless host | `headless/HeadlessLoadHost.kt` (new) | Over `HeadlessScenarioHost`: `openLanes` applies the override as `profile.config.copy(messageStore = …, messageLog = …)` before `openSlot`, then `openLanes(profile)` as fan-out does. Listeners open slot 1 (or the single session) of each named profile. `resolveOnce` calls `FixMessageTemplate.evaluate` with the lane's scope. |
| Runner | `service/load/LoadRunner.kt` (new) | `class LoadRunner(private val host: LoadHost) { fun run(plan: LoadPlan, cancelled: () -> Boolean, onProgress: (LoadProgress) -> Unit): LoadOutcome }`. In order: open lanes and listeners, refuse (exit 2) when no lane logged on. Snapshot `discarded`. `CompiledTemplate.compile`, then `prepare` per lane, timing `prepareMs`. Build the `StampMatcher` over the issuing lanes' `SessionID`s and register `addStampListener` on every lane and listener. Start the `PreparedQueue` producer and the `Pacer`. On the pacer's return, mark `lastSendAt`, then **settle**: poll `snapshot()` every 100ms until `unmatchedNow == 0` or `settleMs` elapsed or cancelled, emitting progress. `closeSettle()`. Read `discarded` delta. `disconnectAll()` under a short grace so late replies during logout are counted. Assemble the report and the verdict. |
| Progress | `model/load/LoadProgress.kt` (new) | `data class LoadProgress(state: PREPARING | ISSUING | SETTLING | DONE, issued, matched, pendingNow, duplicates, late, settleLeftMs, roundTripSoFar: Distribution?, discarded)`. What the CLI prints and what the document later draws. |

Tests, `LoadRunnerTest` with a fake `LoadHost` whose lanes echo a reply stamp after a fake delay: a clean burst finishes settle early and reports `COMPLETE`. One lane that swallows one id reports `UNMATCHED 1` with that id, and settle runs its full length. A host reporting `discarded > 0` yields `tool LIMITED` and exit 1. Cancellation mid-issue yields a stopped report with the counts so far.

### 2.7 · The command (M)

| Item | File | Change |
|---|---|---|
| Verb | `headless/HeadlessRun.kt` | `handles()` accepts `"load"`, `execute()` dispatches to `HeadlessLoad.execute(args.drop(1), out, err)`. The usage text gains one line pointing at `fixtool load --help`. |
| Command | `headless/HeadlessLoad.kt` (new) | `Options.parse` for the flags in the design note: `--profile`, `--count`, `--rate <r>/s --for <d>`, `--settle`, `--listen` (repeatable), `--match <req>=<rep>`, `--reply-type`, `--set k=v` (repeatable), `--store`, `--log`, `--strict-rate`, `--json`, `--junit`, `--home`. Unknown flags refused, as `fixtool run` does. `execute`: `WorkspacePaths.use(home)`, resolve the template (2.1), infer the match when absent or refuse, check `storeProblem()` on the effective config (D2, exit 2), build the plan, run, print progress on `err`, print the summary block on `out`, write files, `store.prune(settings.runRecordsKept)`, return the verdict's exit code. The summary block is the transcript in Mockup E. |
| Duration parse | `headless/HeadlessRun.kt` | `parseDuration` becomes `internal` and learns `m` for minutes, so `--for 10m` and `--settle 60s` share it. |

Tests, `jvmTest/headless/HeadlessLoadTest.kt`: option parsing for every flag and every refusal, the summary block for a fixed report, exit codes for a fixed verdict.

### 2.8 · The integration test and the docs (M)

| Item | File | Change |
|---|---|---|
| Venue | `jvmTest/integration/TestFixServer.kt` | An `answer: (request: String) -> List<String>` policy, default empty so every existing test is unchanged. A helper `executionReportFor(request, ordStatus = "0")` that echoes `11` and mints `37` and `17`. The policy can return two reports (duplicate), none (unmatched), or one after a `Thread.sleep` (latency). |
| Test | `jvmTest/integration/HeadlessLoadIntegrationTest.kt` (new) | Profiles as `HeadlessRunIntegrationTest` writes them, with `sessionCount: 5` and MEMORY/NONE. Cases: a burst of 200 answered in full exits 0, `load.json` says `matched 200`, `specimens.fix` has 50 pairs and `unmatched.fix` is empty. A venue that swallows two chosen ClOrdIDs exits 1 and names both ids in the summary, the JSON and the JUnit failure. A venue answering twice reports `duplicates 200` and still `COMPLETE`. A rate of 100/s for 3s reports `issued 300` and `rate HELD`. `--store memory` without Reset on Logon exits 2 with the sentence. A run with `--listen` on a second single-session profile counts a reply routed there (TestFixServer answers on whichever connection matches a routing map keyed by ClOrdID prefix). |
| Docs | `docs/AUTOMATION.md` | A "Running a load without the app" section after the headless run section: the command, the flags, the exit codes, the JSON shape, the JUnit shape. |
| Help | `help.html` | A "Load runs" subsection after "Fanning a scenario out over those sessions", command-line only for now, and a sentence in Bulk Send pointing at it. |
| Changelog | `CHANGELOG.md` | Added: `fixtool load`. |

### Exit criteria for Step 2

Each maps to an acceptance criterion in #43 or its comment.

- A burst of N is issued without waiting for replies and the run reports issued, matched, unmatched, duplicates and late. Issued is three numbers.
- A reply landing on a session other than the one that sent is matched, not an unmatched plus a stray.
- Counts are exact for 100,000 issued with a pane retention of 1,000.
- A rate run holds R/s within the stated tolerance or reports the shortfall spans, the minimum achieved rate and the maximum lag. Nothing is silently issued less.
- A burst reports its first and last send times.
- Elapsed, drain and min/p50/p95/p99/max/mean appear in the JSON.
- `--set run=x` seeds `${run}` and a second run's matched count equals the first's issued count against a venue that answers cancels.
- Exit 0/1/2 as D11. `--strict-rate` promotes a shortfall.
- The tool's own contribution is visible: `discarded`, `neverLeftSocket`, `issueFailures`, lag spans, `prepareMs`.
- No run is possible on a memory store without Reset on Logon.

---

## Step 3, #43: the app surface

**Goal:** the same run started from the editor or the rail, watched live in a document, reopened
from Recent. The engine does not change.

### 3.1 · The view-model host and the claim (M)

| Item | File | Change |
|---|---|---|
| Record store | `viewmodel/FixMessageViewModel.kt` | `val loadRecordStore by lazy { LoadRecordStore() }` beside `runRecordStore`, re-created on workspace switch the same way. |
| Host | `viewmodel/ViewModelLoadHost.kt` (new) | `LoadHost` over live sessions: `openLanes` is `getProfileSessions(profileId)` filtered to `LOGGED_ON` and sorted by `profileSlot`, exactly `fanOutLanes`' list. The override reconnects the lanes with the copied config when it differs from the profile's, and reconnects them back on `disconnectAll()`. Listeners are the logged-on sessions of the named profiles. `resolveOnce` goes through the existing `sessionTemplateVariables` and `FixMessageTemplate.evaluate`. |
| Start | `viewmodel/FixMessageViewModel.kt` | `fun startLoadRun(plan: LoadPlan): LoadPlan?`. Reserve the id, `claimSessions(RunSessions.Touched(sessions = lane titles + listener titles, exclusive = true), plan.label, plan.id)` so a scenario on those sessions is refused while the run holds them, with the run named in the refusal, and vice versa. `loadRecordStore.begin(plan)`, `_activeLoadRun: MutableStateFlow<LoadProgress?>`, run on `Dispatchers.IO`, release the claim in `finally`. `fun stopLoadRun()` flips the claim's stop flag, which the runner's `cancelled()` reads. |
| Availability | `viewmodel/FixMessageViewModel.kt` | `fun loadLanes(profileId) = fanOutLanes(profileId)`. Same check, same sentences. `fanOutFarEndNotice(profileId)` reused as is. |

Tests, `viewmodel/LoadRunViewModelTest.kt`: a start claims the lanes and a fan-out on the same profile is refused naming the load run. Stop ends the run and releases the claim. A profile with one session is refused with the fan-out sentence.

### 3.2 · The dialog (M)

| Item | File | Change |
|---|---|---|
| Dialog | `ui/LoadRunDialog.kt` (new) | Mockup C. Rows: Template (name, the per-message and fixed tag lists from `CompiledTemplate.compile`), Issue on (profile picker, lanes line from `loadLanes`), Also match on (chips from other profiles' logged-on sessions), Shape (Burst count / Rate per second and duration), Match (request and reply tags prefilled from `inferMatch()`, optional reply type), Settle, Seed, Store and log (as the profile / memory store and no log). Notes: red `storeProblem()` refusal that disables Run, amber far-end notice that does not. `onRun(plan)`. Test tags on every control, as `FanOutDialog` has. |
| Rail | `ui/ScenariosRail.kt` | `RunMenu` gains `onLoadRun` and `RunMenuContents` gains `"Load run…  (${menu.laneProfiles})"` under "Fan out over sessions…", enabled under the same condition (D13). Opens the dialog with a template picker in place of the fixed template row. |

Tests, `ui/LoadRunDialogTest.kt` (Compose): the Run button is disabled while the refusal shows and enabled once the store choice goes back to the profile's. Switching to Rate shows the two rate fields. The inferred match prefills 11 for a NewOrderSingle. `ui/ScenariosRailRunMenuTest` extended for the new item and its count.

### 3.3 · The editor button (S)

| Item | File | Change |
|---|---|---|
| Button | `ui/MessageEditorPanel.kt` | `onLoad: ((fields: List<FixField>) -> Unit)?` beside `onSendToAll`. A third icon button, "Load…", disabled with its reason as tooltip when the editor holds a reply step or when `LoadTemplate.inferMatch()` is null for the current fields. |
| Wiring | `ui/App.kt` | `onLoad` opens `LoadRunDialog` with the editor's fields as the template and the editor's selected profile preselected. |

Test: `MessageEditorPanelLoadButtonTest`, the button's enabled state for a `35=D` with `11` and for a `35=0`.

### 3.4 · The document and Recent (M)

| Item | File | Change |
|---|---|---|
| Doc type | `ui/ScenarioDocuments.kt` | `data class LoadRunView(val loadId: String) : ScenarioDoc` with glyph `⚡`, `id = "load:$loadId"`, `scenarioId = null`. |
| Document | `ui/LoadRunDocument.kt` (new) | Mockup D. Reads `activeLoadRun` while the id matches and `loadRecordStore.read(id)` otherwise, so the same composable draws the live run and the reopened one. Header, progress bar, five tiles, the issue and timing lines, the distribution line and its seven buckets, the tool block, the unmatched table with a per-row wire reveal (the existing `RawMessageView`), the verdict with the record path. A Stop button while the state is not `DONE`. |
| Open on run | `viewmodel/FixMessageViewModel.kt` | `startLoadRun` calls `openDocument(ScenarioDoc.LoadRunView(id))`. |
| Recent | `ui/ScenariosRail.kt`, `viewmodel/FixMessageViewModel.kt` | `menu.recent` becomes `List<RecentRun>` where `sealed interface RecentRun { data class Set(val set: RunSet); data class Load(val summary: LoadSummary) }`, merged from both stores and sorted by start time, newest first, capped as today. A load row reads `Recent ▸  ⚡ <label>  (matched/issued)` and opens `LoadRunView`. The `✓`/`✗` mark for a load follows its completeness verdict. |

Tests: `ui/LoadRunDocumentTest` (a finished report renders the five counts, the unmatched rows and the verdict text, and a running progress renders the Stop button), `ScenariosRailRecentTest` extended for the merged order.

### 3.5 · Docs and verification (S)

| Item | File | Change |
|---|---|---|
| Help | `help.html` | The "Load runs" subsection grows the in-app path: the editor button, the dialog rows, the document, Recent. |
| Changelog | `CHANGELOG.md` | Added: load runs from the editor and the Run menu, the live document. |
| Live check | the `verify` skill | Start the FX Venue example, open a template on the client profile, run a burst of 200 through the dialog, confirm through the control surface that a `loads/` record exists and that the document tab is open. Screen pixels are not the check, per the repo's verify notes. |

### Exit criteria for Step 3

- A load run started from either door opens the document, updates live, stops from the document, and reopens from Recent after a restart.
- A fan-out on the same profile is refused while the load run holds it, and the refusal names the run.
- The dialog refuses memory store without Reset on Logon with the same sentence the CLI prints.
- The document over a headless run's record, opened from Recent, reads identically to one the app produced.

---

## Step 4, #43: the control surface

**Goal:** an agent or a script can start, poll, stop and fetch a load run through the port the
app already opens, with the same job shape as run sets.

### 4.1 · Routes (M)

| Item | File | Change |
|---|---|---|
| Routes | `control/ControlServer.kt` | `POST /load` takes the plan as JSON (`template` by name or `fields`, `profile`, `listen`, `count` or `rate` and `forMs`, `settleMs`, `match`, `replyType`, `seed`, `store`, `log`, `strictRate`). Answers 202 with `{id}`, 409 with the clash sentence when the claim is refused, 200 with an error object for a bad plan including `storeProblem()`. `GET /loads` lists summaries. `GET /loads/<id>` returns the `LoadProgress` while running and the `LoadReport` JSON when done, with `?wait=<ms>` as the run-set route offers. `POST /loads/<id>/stop`. |
| Docs | `docs/AUTOMATION.md` | The four routes in the HTTP API table and a short worked example under the end-to-end section. |

### 4.2 · MCP (S)

| Item | File | Change |
|---|---|---|
| Tools | `control/McpTools.kt` | `fixtool_load` (start, same parameters, returns the id and the first progress) and `fixtool_load_status` (poll or fetch, `id` optional to list). Descriptions say what the verdicts mean and that a rate shortfall is not a failure unless asked. |
| Changelog | `CHANGELOG.md` | Added. |

Tests: `ControlServerLoadTest` over a fake view model as the other route tests do: 202 then a poll that reaches `DONE`, 409 on a held profile, the error object on a bad store choice.

### Exit criteria for Step 4

- `curl -X POST /load` then `GET /loads/<id>?wait=60000` returns the finished report for the loopback venue.
- The MCP tool round trip works from Claude Code with the shipped shim.

---

## Cross-cutting

- **Lint.** `./gradlew :composeApp:detekt` on the module. `ktlintFormat` only on the touched files, never module-wide (it rewrites about a hundred unrelated files).
- **Tests.** `./gradlew :composeApp:jvmTest --tests "com.knapsack.fixtool.service.load.*" --no-daemon` for the engine, the integration class by name, the whole module before each step's last commit.
- **Commit messages.** The repo's `feat(scope): what it means for the user` style, no Claude or Anthropic mention, the session trailer.
- **Documentation lands with the code that makes it true**, in the same commit or the next one, never in a batch at the end.

---

## Sequencing and risks

| Risk | Where | Mitigation |
|---|---|---|
| The stamp listener adds work to the MINA I/O thread for every session, run or no run. | 2.0 | An empty `CopyOnWriteArrayList` iteration is a size check. Measured in `LoadRunnerTest`'s host as zero listeners cost. |
| `Session.sendToTarget` under a memory store still queues when the socket is not writable, so `handedToEngine` can exceed `leftSocket` at the end. | 2.3, 2.5 | That gap is `neverLeftSocket`, reported as tool-limited. It is the honest number, not a bug to hide. |
| A rate above what one producer thread can render shows up as lag and is blamed on the tool. | 2.4 | Correct attribution. The report's `prepareMs` and the dry-queue lag spans say so. A second producer is a follow-on if a real run needs it. |
| The pending map holds request wires until matched. A slow venue under a long rate run grows it. | 2.3 | Bounded by outstanding count, which the settle window bounds. Stated in the report as `pendingPeak`. |
| Reconnecting live lanes under a store override changes sequence numbers for a profile the user is also using by hand. | 3.1 | The claim is exclusive, the lanes are reconnected back on release, and the dialog says the lanes will reconnect. Memory store requires Reset on Logon anyway, so the numbers were going to start at 1. |
| `TestFixServer` is a raw socket server. Cross-session reply routing needs it to know which connection to answer on. | 2.8 | A routing map keyed by ClOrdID prefix is enough for the one integration case. The mechanism itself is proven in `StampMatcherTest` with two `SessionID`s. |
| Recent's list type changes shape. | 3.4 | `RecentRun` is a sealed type introduced in one commit with both branches, and the rail test covers the order. |

## Deliberately not in this plan

- Per-message Kotlin expressions. The design note says why. A follow-on flag is possible if a real run needs it.
- More than one template per run, or a mixed sequence such as order then cancel inside one run. Two runs with a shared `run` seed cover the two-phase case the issue describes.
- Distributing lanes across processes or machines.
- Charts beyond the seven-bucket strip. The JSON has the per-second buckets for anyone who wants to draw more.
- A `POST /load` for a venue-side load, where FixTool's acceptor is the thing under test. The far-end notice already says the acceptor is single-threaded.
