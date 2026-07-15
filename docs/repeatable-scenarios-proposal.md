# FixTool Repeatable Scenarios & Automated Verification — Design Spec

> **The assertion sections of this document are superseded.** How an expectation is expressed, how it is
> compared against a reply, and how a failure is reconciled are specified in
> **[`scenario-assertion-model.md`](./scenario-assertion-model.md)** — which replaced the group-path model
> this document originally proposed. The sections below have been cut back to a pointer where they said
> something that is now false; a design doc that describes a deleted model as if it were current is worse
> than no design doc.
>
> The rest — the background, the storage format, the runner and its report, the MCP surface, and the
> resolved decisions — still stands.

## Background

QA uses FixTool today to build flows like "create and book a trade": a sequence of
FIX messages sent in order (`NewOrderSingle` → wait for `ExecutionReport(New)` →
wait for `ExecutionReport(Fill)` → allocation → booking confirm). The MCP control
surface ([`McpTools`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/McpTools.kt))
makes this easy to drive, manually or via an agent.

Two gaps remain:

1. **Templates are single requests.** A saved template
   ([`SavedFixMessage`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/SavedFixMessage.kt))
   is one message. A scenario is an ordered sequence of sends *and* expected
   responses. There is no artifact that captures the whole flow.

2. **Verification is manual.** The tester eyeballs each response in the message
   viewer to decide if it looks right. There is no machine check, so a flow cannot
   be re-run as a regression test.

This spec defines **repeatable scenarios**: a saved, parameterized sequence of
sends and assertions that a deterministic runner replays — identically, with no
LLM in the loop — producing a tag-by-tag pass/fail report.

### Why not "replayable", and why not re-prompting

- **Captured bytes do not replay.** Every meaningful field is non-deterministic:
  `ClOrdID`, `TransactTime`, `MsgSeqNum`, venue-assigned `OrderID`/`ExecID`. A
  literal byte replay rejects on the second message. The artifact must be
  *parameterized*, not recorded verbatim.
- **Re-prompting an agent is slow and non-deterministic.** The LLM is excellent for
  *authoring* a scenario (exploratory, natural language) and a poor fit for
  *repeating* one (latency, variance). The design principle is therefore:

> **The LLM (or a human) authors the scenario once. A dumb runner repeats it
> forever. Get the model out of the hot path.**

---

## What already exists (and is reused)

This design is mostly assembly of parts FixTool already owns:

- **Expression engine** —
  [`FixMessageTemplate`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/FixMessageTemplate.kt)
  evaluates `${...}` at send time: `${UUID.randomUUID()}`, `${LocalDateTime.now()...}`,
  cross-message references `${out.D.11}` / `incoming["D"].valueOfTag(11)`, and
  variable assignment/retrieval (`${orderId = UUID.randomUUID()}` then `${orderId}`).
  This is the foundation for **parameterizing requests** and for **reference
  matchers**. ⚠️ **Scope caveat (see [Decision 0](#decision-0--persistent-scenario-scoped-variables)):**
  the `variables` map is allocated **fresh per send** — assignments made in one
  `fixtool_send` do *not* survive to the next. Only the `incoming[...]`/`outgoing[...]`
  message context persists across sends (per-session, and wiped by
  `fixtool_clear_messages`). The runner therefore must own a persistent scenario
  scope; it cannot simply reuse the engine's map.
- **Deterministic wait** — `fixtool_wait` blocks until a session reaches a state or
  a matching message arrives, or until `timeoutMs`. This *is* the positive-presence
  assertion primitive.
- **Parsed message access** — `fixtool_get_messages` returns ordered `{tag, value}`
  fields; the message viewer
  ([`MessageDetailPanel`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/ui/MessageDetailPanel.kt),
  [`HierarchicalGridView`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/ui/HierarchicalGridView.kt))
  already renders a message tag-by-tag with field names and enum descriptions.
- **Dictionary metadata** —
  [`FixDictionaryAdapter`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/FixDictionaryAdapter.kt)
  exposes field names, enum values (`getFieldEnumValues`), group detection
  (`isGroupTag`), and the underlying QuickFIX `DataDictionary`
  (`getDataDictionary().getFieldType(tag)`). Used to **auto-seed matchers** by field
  type.
- **Group-by-identity inspection** — `fixtool_detail_search` mode `identity` already
  shows the identity field of each repeating-group entry "so you can tell which
  entry matched". The matcher model inherits this idea.

The genuinely new pieces are: the **expectation** (a FIX message whose tags carry
matchers), the **scenario** artifact, and the **runner**.

---

## End-to-end flow

```
┌─ AUTHOR (once, manual or agent-driven) ──────────────────────────────┐
│ 1. Send the messages by hand to build the flow.                      │
│ 2. For each response: open it in the message viewer, assign a        │
│    matcher per tag (pre-seeded from the dictionary), preview live.   │
│ 3. Parameterize the requests (volatile + correlated fields → ${...}).│
│ 4. Save as a scenario template.                                      │
└──────────────────────────────────────────────────────────────────────┘
┌─ REPEAT (any number of times, no LLM) ───────────────────────────────┐
│ Runner walks the steps top-to-bottom:                                │
│   send (expressions resolved) → wait → assert response vs            │
│   expectation → next step. Emits a per-tag pass/fail report.         │
└──────────────────────────────────────────────────────────────────────┘
```

Two capture concerns, one on each side of every step:

- **Outbound** → parameterize volatile and correlated fields so each run gets fresh
  IDs and preserves cross-step correlation.
- **Inbound** → attach matchers so the response is checked, not eyeballed.

---

## Data model

A **scenario** is an ordered list of steps over a **persistent, scenario-scoped
variable map owned by the runner** (not the engine's per-send map — see
[Decision 0](#decision-0--persistent-scenario-scoped-variables)). Each step is a
send, a wait, or an assert, plus optional `setup`/`teardown` step lists. Each
scenario is persisted as its own JSON file (see
[Storage](#storage--one-file-per-scenario)) so it is diffable, shareable, and
PR-able.

```kotlin
data class Scenario(
    val id: String,
    val name: String,
    val profile: String,              // connection profile id/name
    val setup: List<ScenarioStep> = emptyList(),    // run before steps; e.g. clear_messages, reset-seqnum
    val steps: List<ScenarioStep>,
    val teardown: List<ScenarioStep> = emptyList(), // run after steps, even on failure
    val userTags: List<String> = emptyList(),
    val version: Int = 1,             // per-file schema version (migrate() hook)
)

sealed interface ScenarioStep {
    // Send a (parameterized) message. Expressions resolved against the scenario scope.
    data class Send(
        val raw: String,              // pipe/SOH-delimited FIX with ${...} expressions
        val session: String? = null,  // default: active session
    ) : ScenarioStep

    // Block until a state or matching message arrives (deterministic sync).
    data class Wait(
        val session: String? = null,
        val state: String? = null,    // e.g. LOGGED_ON
        val match: MatchPredicate? = null,
        val timeoutMs: Int = 10_000,
    ) : ScenarioStep

    // Assert the next message that satisfies `match` against an expectation.
    data class Expect(
        val session: String? = null,
        val direction: Direction = Direction.INCOMING,
        val match: MatchPredicate? = null, // bind to a specific message (e.g. ExecType=F, OrdStatus=2);
                                           // consumed-on-match so successive Expects walk successive fills
        val timeoutMs: Int = 10_000,  // how long to wait for the message to arrive
        val expectation: Expectation,
    ) : ScenarioStep
}
```

`MatchPredicate` extends the existing `fixtool_wait` predicate
([`ControlServer.matchesMessage`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/ControlServer.kt))
with **multiple tag/value pairs (AND)** and **consumed-cursor** semantics so an
ordered run of partial-fill `Expect`s each binds to the next matching message
rather than re-matching the first (see
[Decision 4](#decision-4--partial-fill-sequences)).

### Expectation, matchers, and repeating groups — see the assertion model

This document originally specified an expectation as a captured message plus a **per-tag matcher overlay**,
and located a field inside a repeating group by an **identity predicate** —
`GroupPath(groupTag = 453, identityTag = 448, identityValue = "FIRMA")`, read as *"the party entry whose
PartyID is FIRMA"*.

**That model is gone, and it was wrong on ordinary messages.** One firm can act in two roles, so both party
entries carry `448=FIRMA` and the identity does not identify. An expectation is now an **ordered list of
rows**; the *k*-th row for a tag asserts the *k*-th occurrence of that tag. Position is the address.

The matcher vocabulary (`exact`, `presence`, `absent`, `regex`, `oneOf`, `numeric`, `temporal`, `reference`),
the auto-seeding rules, the OPEN and STRICT semantics, the wire format, and the reconcile view are all
specified in **[`scenario-assertion-model.md`](./scenario-assertion-model.md)**.

### Storage — one file per scenario

The two existing stores
([`SavedMessagesService`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/SavedMessagesService.kt)
→ `~/.fixtool/saved_messages.json`,
[`ConnectionProfileService`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/ConnectionProfileService.kt)
→ `~/.fixtool/connection_profiles.json`) each hold one collection, hard-typed inside
their own `*Container`, written whole-file on every change. That pattern does not
scale to the target of **hundreds-to-low-thousands of scenarios** (≈5–20 KB each
with captured goldens), and it fights the diffable/PR-able goal above:

- a single multi-MB file produces unreadable git diffs when one scenario changes;
- every edit rewrites the entire collection (write amplification);
- one interrupted/corrupt write loses *all* scenarios — and today's `saveAll`
  uses `writeText` (truncate-in-place), which is **not atomic** despite its comment;
- `list` and `run` (the hot paths) must deserialize every golden just to read
  one scenario's metadata or body.

**Decision:** a dedicated `ScenarioService` backed by a **directory store** —
one file per scenario at `~/.fixtool/scenarios/<id>.json` (base path overridable
via `AppSettings.scenariosPath`). Each file is a versioned, self-migrating JSON
document; per-profile scoping reuses the existing `userTags` tag-filter convention.
`list_scenarios` scans the directory on demand (cheap at this scale); a metadata
sidecar/index is added **only if** profiling later shows it is needed. Stay on
plain JSON (not SQLite) to preserve diffability. **All writes are atomic** (write
`<id>.json.tmp`, then `Files.move(…, ATOMIC_MOVE)`) — a fix worth backporting to
the existing services.

---

## Auto-seeding matchers (the usability lever)

If every tag of a captured response defaulted to `Exact`, the author would have to
manually downgrade every timestamp, seqnum, and ID, and the first replay would fail
on a dozen forgotten tags. To prevent rubber-stamping, the capture step **pre-seeds**
the matcher from the dictionary field type
(`DataDictionary.getFieldType(tag)`), and the author only corrects:

| Field type / tag                          | Seeded matcher              |
|-------------------------------------------|-----------------------------|
| `UTCTIMESTAMP`, `UTCDATEONLY`             | `Temporal(NOW_WITHIN_TOLERANCE)` |
| `PRICE`, `QTY`, `AMT`, `FLOAT`            | `Numeric(captured, tol)`    |
| Header volatiles: `9`, `10`, `34`, `52`   | omitted (ignored)           |
| Enum fields (`hasFieldValues` true)       | `Exact` (one-click → `OneOf`) |
| ID-like (`OrderID 37`, `ExecID 17`, …)    | `Presence`                  |
| everything else                           | `Exact`                     |

---

## OPEN vs STRICT comparison

Superseded — and not only in its details. This document said OPEN "asserts only the tags the expectation
lists; ignores extras", with no constraint on order. **OPEN now also requires the expectation to be a
subsequence of the reply**: the listed rows must appear in the order they are listed, with anything else
allowed in between. A reply that keeps every per-tag sequence intact but reshuffles the fields between them
is a message no venue sent, and OPEN says so.

See [`scenario-assertion-model.md`](./scenario-assertion-model.md#the-two-modes).

---

## Live preview, and the two-instance check

Authoring happens in the message viewer: each tag row shows its matcher chip and a
green/red result computed live against the captured message.

**One-instance preview only proves the matcher parses** — `Temporal`-vs-`Exact`
mistakes pass trivially because a message always equals itself. The strong check:
evaluate the expectation against a **second instance** of the same response (re-run
the step, or capture a second occurrence) with fresh timestamps/IDs. If a volatile
field was left as `Exact`, it goes red immediately.

- One-instance preview → "does it parse".
- Two-instance preview → "is it actually repeatable".

The authoring UI should offer the two-instance check as a one-click "verify
generalizes" action before save.

---

## The runner and its report

The runner walks steps top-to-bottom against a live session, no LLM:

1. `Send` — resolve `${...}` against the shared scope, send.
2. `Wait` — block until predicate or timeout.
3. `Expect` — await the message (up to `timeoutMs`), then evaluate every
   `FieldExpectation`. Produce a per-tag result.

```kotlin
data class TagResult(
    val tag: Int,
    val matcher: Matcher,
    val expected: String,   // human-readable matcher description
    val actual: String?,    // null if tag absent
    val passed: Boolean,
)
data class StepResult(val stepIndex: Int, val passed: Boolean, val tags: List<TagResult>)
data class ScenarioResult(val passed: Boolean, val steps: List<StepResult>)
```

One `TagResult` list drives **both** consumers:

- **CI** — `ScenarioResult.passed` → process exit code; the report serializes to
  JSON/JUnit XML.
- **UI** — the same per-tag results render as red/green highlights in the existing
  message viewer.

---

## MCP / control surface additions

New tools mirroring the existing dispatch pattern in `McpTools` / `ControlServer`:

| Tool                        | Purpose                                                        |
|-----------------------------|---------------------------------------------------------------|
| `fixtool_assert`            | Assert the selected/awaited message against an inline expectation. Useful standalone (machine-checks the current manual flow) *before* scenarios exist. |
| `fixtool_capture_expectation` | From a selected message, build an auto-seeded expectation for editing. |
| `fixtool_save_scenario`     | Persist a scenario (steps + expectations).                    |
| `fixtool_list_scenarios`    | List saved scenarios per profile.                             |
| `fixtool_run_scenario`      | Run a scenario deterministically; returns `ScenarioResult`.   |

`fixtool_assert` is the keystone: it is useful on its own, and the whole scenario
schema falls out of getting its predicate/matcher shape right.

---

## Phasing

- **Crawl** — `fixtool_assert` + the `Matcher` set + dictionary auto-seeding. Even
  the current manual/agent flow stops eyeballing and starts machine-checking.
- **Walk** — `Scenario`/`ScenarioStep`, `save_scenario` / `run_scenario`, the
  runner, and the `ScenarioResult` report. Repeatable and CI-runnable.
- **Run** — message-viewer authoring UX: per-tag matcher chips, live preview, the
  two-instance "verify generalizes" check, group-by-identity editing.

Build `fixtool_assert` first: the assertion vocabulary (predicates, tolerances,
absence, groups, masking) is the actual hard design, and the scenario format is a
thin sequencing layer on top of it.

The detailed, task-level breakdown of these phases — deliverables, file touchpoints,
new types/services/tools, and exit criteria — is in the companion
[implementation plan](./repeatable-scenarios-impl-plan.md).

---

## Resolved decisions

These were the open questions; each is now resolved with the rationale, grounded in
the current codebase. They are the contract the
[implementation plan](./repeatable-scenarios-impl-plan.md) builds against.

### Decision 0 — Persistent scenario-scoped variables

*(Surfaced during review; prerequisite for everything below.)* The engine's
`variables` map is allocated **fresh per send** — values set in one `fixtool_send`
do not survive to the next; only `incoming[...]`/`outgoing[...]` context persists
across sends (per-session, wiped by `fixtool_clear_messages`). The original
"steps share the engine's variables map" assumption is therefore false.

**Decision:** the runner owns a **persistent, scenario-wide variable map**. It is
injected as `seedVariables` into every step's evaluation, and assignments are
accumulated back out after each step. The scope is scenario-wide (not per-session)
so a value produced on one session can be referenced in an assertion on another
([Decision 5](#decision-5--multi-session-scenarios)). Because `clear_messages` in
setup wipes the message-reference context, **scenario variables are the preferred
cross-step correlation mechanism**; `${out.D.11}`-style refs remain available but
are secondary.

### Decision 1 — Storage: directory store, one file per scenario

Resolved in [Storage](#storage--one-file-per-scenario). A dedicated `ScenarioService`
+ `~/.fixtool/scenarios/<id>.json` directory store with atomic writes — chosen over
the existing single-collection-file pattern because it scales to hundreds/thousands,
gives clean per-scenario git diffs, isolates write blast radius, and makes the
list/run hot paths read only what they need.

### Decision 2 — Setup/teardown in scope; environment reset is a precondition

A scenario owns optional `setup`/`teardown` step lists (ordinary `ScenarioStep`s, so
no new execution machinery; teardown runs even on failure). **In scope** and
auto-suggested in setup: `fixtool_clear_messages` + `fixtool_admin reset-seqnum`
(both already exist and compose cleanly; clearing the log is what makes "await the
next message" deterministic). **Out of scope:** business/market state (flat book,
reference data) lives on the MD/exchange server FixTool does not control — it is a
documented **precondition**, optionally asserted by a leading `Expect` step that
fails fast with a clear message when the environment is not clean.

### Decision 3 — Numeric tolerance: per-field-type default, visible and editable

The capture step seeds tolerance from the dictionary field type (the same metadata
that drives matcher auto-seeding) and surfaces it as an editable chip — never a
silent global default. Defaults: `PRICE` → small **absolute** tolerance (traders
think in ticks; relative tolerance misbehaves near zero), tick size if available;
`QTY` → `0` (exact, but format-robust: `100` == `100.0`); `AMT` → currency precision;
generic `FLOAT` → `0`. "Always explicit" is rejected because it defeats the
auto-seeding usability lever; a silent global default is rejected because it can mask
a real price break.

### Decision 4 — Partial-fill sequences: match predicate on `Expect`, consumed-on-match

A step binds to a specific message via the `match` predicate on `Expect`, selecting
by business fields (`ExecType 150`, `OrdStatus 39`) rather than position (arrival
order is not guaranteed — same reasoning as group-by-identity). This reuses the
existing `fixtool_wait` matcher, extended minimally with **multi-tag AND** (a partial
is `150=F AND 39=1`; the fill is `150=F AND 39=2`) and a **consumed cursor** so
successive same-type `Expect`s walk successive fills instead of re-matching the
first. *Deferred:* aggregate/cross-message assertions (e.g. Σ`LastQty` == `OrderQty`)
— a separate future feature.

### Decision 5 — Multi-session: supported in the model now, authoring UX later

Every `ScenarioStep` already carries an optional `session` selector, resolved per
step via the existing `resolveSession()`. "Send on initiator, assert on acceptor" is
just two steps with different `session` values — no atomic cross-session primitive
needed (sequential steps plus the `Wait`/`Expect` timeouts cover the async gap), and
it enables validating acceptor auto-response rules by driving an initiator. The
marginal cost is zero, so the format supports it from day one; the polished
multi-session **authoring UI** is deferred to the Run phase.

### Decision 6 — Disambiguating same-type messages: presence/absence bind constraints + an occurrence ordinal

Decision 4 selects a message by business fields (`ExecType`, `OrdStatus`) plus a
consumed cursor. That is sufficient only when (a) the distinguishing signal is a plain
`tag=value` **and** (b) every message the author cares about has an `Expect` that
consumes the ones before it. Real lifecycles break both. In the **Repo Full Lifecycle**
scenario two `ExecutionReport`s (`35=8`) share the same `ClOrdID`; the first is the ack
(`ExecType=0`, no `QuoteReqID`) and the terminal one carries `QuoteReqID (131)`. The
scenario has no `Expect` for the ack, so the terminal step binds the *earliest* `8` —
the ack — and its `131` assertion fails against a message that never had the tag. The
one field that separates the two (131 present vs absent) was expressed only as an
*assertion*, and assertions never steer binding (by design — see the evaluator's "never
re-aim onto whatever field makes it green"). **Reconcile cannot fix this:** it repairs
the *expected value* against the bound (wrong) message, erasing the check rather than
moving to the right message. Disambiguation must happen at the **binding** layer.

Two additions to `MatchPredicate` (`Scenario.kt`) close the gap, jointly:

1. **Presence/absence bind constraints.** `TagValue` gains an operator (`EQ` default,
   plus `PRESENT` / `ABSENT`; `REGEX`/`ONE_OF` left open). The terminal step then binds
   on `131 PRESENT` — the *reason* it is the right message, reorder- and count-proof.
   This is the preferred, intent-revealing form and covers the screenshot case directly.
2. **An occurrence ordinal.** `MatchPredicate` gains `occurrence: Int? = null`. When set
   (1-based), the step binds the N-th message in the `messageType + direction + fields`-
   filtered chronological snapshot — the literal "2nd `ExecutionReport`" — rather than
   the first not-yet-consumed one. This is the escape hatch for messages that are
   genuinely identical except for order, where no discriminator exists. `null` preserves
   today's exact behaviour (first unconsumed).

**Semantics and precedence** (in `ScenarioRunner.matches`/`runExpect`): filter by
`messageType`+`direction`, apply each `fields` constraint through the *wire view*
(`host.view(msg).fields()`, so grouped/repeated tags and presence tests read through the
same door the evaluator judges through); then, if `occurrence` is set, index the
filtered snapshot to the N-th match instead of taking the first unconsumed. The consumed
cursor still records the bound message, and a step fails loudly if its slot is already
consumed or fewer than N matches exist by the deadline. `occurrence` is **absolute over
the filtered snapshot**, not relative to the cursor — "the 2nd" means the same message
regardless of what earlier steps consumed. A negative/`last` ordinal is **deferred**:
under polling "the last" is unknowable while more messages may still arrive, so binding
it early is a race; positive ordinals only in the first cut.

**Capture auto-seeds the discriminator (the usability lever).** `ScenarioCapture.expectStep`
today seeds bind constraints only from echoed correlation ids (`ID_TAGS`), which are
identical across fills of one order — exactly the blind spot. A post-pass over the
captured steps groups `Expect`s by `(session, messageType)`; for each ambiguous group it
seeds the *minimal* distinguisher: prefer a tag whose **value differs** across the group
(`ExecType`/`OrdStatus` and any other differing tag), else a **presence/absence**
difference (`131` present in one, absent in another), and only if the members are
otherwise tag-identical does it fall back to assigning `occurrence` ordinals by capture
order. Intent (presence/value) is always preferred over position (count), so scenarios
do not rot when a venue inserts a message.

*Back-compat & codec:* `TagValue.op` defaults `EQ` and `occurrence` defaults `null`, so
existing scenario files are byte-identical and behave identically; the codec writes the
new keys only when non-default and **rejects an unknown operator at load** (fail closed,
consistent with the assertion-model doc's stance on unknown keys). *UI:* the `Expect`
editor gains an ordinal chip ("2nd ▾ of type") and a per-constraint operator
(`= / present / absent`) on the bind row. *Deferred:* the relative anchor ("the first
`8` **after** step 6's `TradeCaptureReport`", by `stepId`) — expressive for lifecycles
but largely redundant with presence + ordinal for current flows, and a larger surface;
revisit if a scenario needs "next match after an event" that neither a discriminator nor
an absolute ordinal can express.

