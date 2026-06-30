# Repeatable Scenarios — Phase 1 (Crawl) delivery evidence

This records what was built for **Phase 1 / Crawl** of the
[implementation plan](./repeatable-scenarios-impl-plan.md) and the test evidence that it works.
Phase 1 is the keystone: the matcher vocabulary + the `fixtool_assert` /
`fixtool_capture_expectation` control endpoints. It converts the manual "eyeball the response"
step into a machine check, standalone, before scenarios exist.

## What shipped

| Plan item | Status | Where |
|-----------|--------|-------|
| 1.1 Matcher model | ✅ | `model/scenario/Matcher.kt` |
| 1.2 Expectation evaluator (6 rules, groups, STRICT) | ✅ | `service/ExpectationEvaluator.kt` |
| 1.3 Reference resolution (against session history) | ✅ | `control/ControlServer.referenceResolverFor` (reuses `FixMessageTemplate`) |
| 1.4 Auto-seeding from dictionary field types | ✅ | `service/ExpectationSeeder.kt` |
| 1.5 `fixtool_assert` tool | ✅ | `control/McpTools.kt`, `control/ControlServer.assertMessage` |
| 1.6 `fixtool_capture_expectation` tool | ✅ | `control/McpTools.kt`, `control/ControlServer.captureExpectation` |
| 1.7 Atomic-write utility | ✅ | `util/AtomicFiles.kt` |
| Message adapter / JSON codec | ✅ | `service/FixMessageView.kt`, `service/MatcherCodec.kt` |

Matcher vocabulary delivered: `exact`, `presence`, `absent`, `regex`, `oneOf`, `numeric`
(value + tolerance), `temporal` (today / now±N seconds), `reference` (`${...}` over session scope),
plus group-by-identity (`path`) and OPEN/STRICT modes.

## How to reproduce the evidence

```
./gradlew jvmTest \
  --tests "com.knapsack.fixtool.service.ExpectationEvaluatorTest" \
  --tests "com.knapsack.fixtool.service.ExpectationSeederTest" \
  --tests "com.knapsack.fixtool.integration.AssertIntegrationTest" \
  --tests "com.knapsack.fixtool.integration.ControlServerIntegrationTest"
```

## Result — `BUILD SUCCESSFUL`, 42 tests, 0 failures

| Test class | Tests | Failures | What it proves |
|------------|------:|---------:|----------------|
| `ExpectationEvaluatorTest` | 9 | 0 | Every matcher type + STRICT extras + group-by-identity, on an in-memory message |
| `ExpectationSeederTest` | 1 | 0 | Field-type → matcher seeding (timestamp→temporal, price/qty→numeric, OrderID→presence, volatiles dropped) |
| `AssertIntegrationTest` | 3 | 0 | End-to-end over the live control surface (see below) |
| `ControlServerIntegrationTest` | 29 | 0 | Existing suite still green; MCP tool count updated 28 → 30 |

### Unit coverage (`ExpectationEvaluatorTest`)
- exact match / mismatch, presence, absent (present + absent cases)
- oneOf, regex
- numeric: trailing-zero formatting (`1.23450` == `1.2345`), within/outside tolerance, non-numeric input
- temporal: now-within-tolerance (pass at 18s/30s, fail at 18s/5s), today vs yesterday
- reference: resolves against an injected scope; unresolvable reference fails (no crash)
- STRICT: flags an unexpected business tag, ignores header/trailer volatiles `8,9,10,34,52`
- group-by-identity: locates the entry by `PartyRole`, fails with `<no entry>` when identity is absent

### Integration coverage (`AssertIntegrationTest`) — live FIX session, no eyeballing
A FixTool **acceptor** is stood up with an auto-response rule
(`NewOrderSingle 35=D → ExecutionReport 35=8` echoing ClOrdID + Symbol) and a FixTool
**initiator** logs on to it over a real socket. Then:

1. **`assert machine-checks an execution report tag by tag`** — the client sends an order; the
   acceptor auto-responds; `POST /assert` checks the ExecutionReport with `exact` (35, 150, 55),
   `oneOf` (39), `presence` (37 OrderID) and a **`reference` matcher** asserting tag 11 equals
   `${out.D.11}` — i.e. the report's ClOrdID echoes the order we sent (resolved to `ORD-ACC`).
   All pass. A deliberately wrong expectation (`39 == 2`) fails on exactly tag 39, with the report
   showing `expected: 2 / actual: 0`.
2. **`capture expectation auto-seeds matchers from the response`** — `POST /expectation/capture`
   returns a draft expectation; MsgType(35) seeds `exact`, OrderID(37) seeds `presence`, and the
   volatile CheckSum(10) is omitted.
3. **`assert is reachable over the MCP transport`** — the same assertion succeeds when invoked as
   the `fixtool_assert` MCP tool over `/mcp` (`"passed":true`).

## Captured `/assert` output (from the live integration run)

The actual tag-by-tag report the endpoint returns — a passing assertion (note the `reference`
matcher resolved `${out.D.11}` to `ORD-ACC`, and `presence` matched the random OrderID):

```json
{
  "passed": true,
  "messageType": "8",
  "direction": "INCOMING",
  "tags": [
    { "tag": 35,  "matcher": "exact 8",              "expected": "8",         "actual": "8",         "passed": true },
    { "tag": 150, "matcher": "exact 0",              "expected": "0",         "actual": "0",         "passed": true },
    { "tag": 39,  "matcher": "oneOf [0,1,2]",        "expected": "0 | 1 | 2", "actual": "0",         "passed": true },
    { "tag": 55,  "matcher": "exact EUR/USD",        "expected": "EUR/USD",   "actual": "EUR/USD",   "passed": true },
    { "tag": 37,  "matcher": "presence",             "expected": "<present>", "actual": "dabcb09b-…","passed": true },
    { "tag": 11,  "matcher": "reference ${out.D.11}","expected": "ORD-ACC",   "actual": "ORD-ACC",   "passed": true }
  ]
}
```

And a failing assertion pinpointing the offending tag with a self-explanatory expected/actual:

```json
{
  "passed": false,
  "messageType": "8",
  "direction": "INCOMING",
  "tags": [
    { "tag": 39, "matcher": "exact 2", "expected": "2", "actual": "0", "passed": false }
  ]
}
```

## Quality gates
- `./gradlew compileTestKotlinJvm` — clean.
- `./gradlew detekt` — no new violations in any added/modified file (the matcher core, codec,
  seeder, view adapter, atomic-write util, and the two new control handlers are all clean;
  remaining detekt output is limited to pre-existing files untouched by this work).

## Not yet delivered (Phase 2 / Walk and Phase 3 / Run)
Per the plan, still ahead: the `Scenario`/`ScenarioStep` model, the persistent scenario-scoped
variable map, the extended `MatchPredicate` (multi-tag AND + consumed cursor), the
`ScenarioRunner`, the `ScenarioService` directory store, the CI report, the four scenario MCP
tools, and the in-app authoring UX. The atomic-write utility (1.7) is in place ahead of the
storage work.
