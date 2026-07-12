# `fixtool_assert` — Crawl-phase spec

This is the keystone tool from the
[repeatable-scenarios proposal](./repeatable-scenarios-proposal.md): a single
control endpoint that asserts a received FIX message against an **expectation**
(a set of per-tag matchers). It is useful standalone — it converts the current
manual "eyeball the response" step into a machine check *before* scenarios exist —
and its matcher vocabulary is the schema the whole scenario format is built on.

It follows the existing `McpTools` / `ControlServer` 1:1 tool→endpoint pattern.

---

## Tool definition

Mirrors the `tool(...)` DSL in
[`McpTools`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/McpTools.kt):

```kotlin
tool(
    "fixtool_assert",
    "Assert a received message against an expectation (per-tag matchers). Selects the " +
        "message like fixtool_select (by messageType/direction/index), or awaits one for up " +
        "to timeoutMs. Returns {passed, tags:[{tag, matcher, expected, actual, passed}]} for " +
        "tag-by-tag pass/fail. mode=open asserts only listed tags; strict fails on extras.",
    props(
        "session" to string("session id/title/index; default active"),
        "messageType" to string("FIX msg type to select/await, e.g. 8"),
        "direction" to enumStr("in", "incoming", "out", "outgoing"),
        "index" to integer("0-based into matching messages; default last"),
        "timeoutMs" to integer("await a matching message up to this long; default 0 = use already-received"),
        "mode" to enumStr("open", "strict"),
        "fields" to arraySchema(objectSchema("FieldExpectation: {tag, matcher, path?}"), "per-tag matchers"),
    ),
    required = listOf("fields"),
)
```

---

## Request shape

```jsonc
{
  "session": "Demo User 1",
  "messageType": "8",          // ExecutionReport
  "direction": "incoming",
  "index": -1,                  // last matching (default)
  "timeoutMs": 5000,            // await up to 5s if not yet present
  "mode": "open",
  "fields": [
    { "tag": 35,  "matcher": { "type": "exact", "value": "8" } },
    { "tag": 150, "matcher": { "type": "exact", "value": "F" } },     // ExecType = Trade
    { "tag": 39,  "matcher": { "type": "oneOf", "values": ["1", "2"] } }, // OrdStatus partial|filled
    { "tag": 11,  "matcher": { "type": "reference", "expression": "${out.D.11}" } }, // ClOrdID echo
    { "tag": 37,  "matcher": { "type": "presence" } },                // OrderID assigned, value irrelevant
    { "tag": 17,  "matcher": { "type": "presence" } },                // ExecID assigned
    { "tag": 31,  "matcher": { "type": "numeric", "value": 1.2345, "tolerance": 0.00005 } }, // LastPx
    { "tag": 32,  "matcher": { "type": "numeric", "value": 100, "tolerance": 0 } },          // LastQty
    { "tag": 60,  "matcher": { "type": "temporal", "kind": "now_within_tolerance", "toleranceSeconds": 30 } }, // TransactTime
    { "tag": 58,  "matcher": { "type": "absent" } },                  // no Text/reject reason
    {
      "tag": 448, "matcher": { "type": "exact", "value": "BROKER-A" },
      "path": { "groupTag": 453, "identityTag": 452, "identityValue": "1" } // PartyID where PartyRole=1
    }
  ]
}
```

### Matcher JSON encodings

| `type`       | extra fields                          | semantics                                   |
|--------------|---------------------------------------|---------------------------------------------|
| `exact`      | `value`                               | literal string equality                     |
| `presence`   | —                                     | tag present, value ignored                  |
| `absent`     | —                                     | tag must not be present                     |
| `regex`      | `pattern`                             | value matches pattern                       |
| `oneOf`      | `values[]`                            | value ∈ set                                 |
| `numeric`    | `value`, `tolerance` (default 0)      | `abs(actual − value) ≤ tolerance`           |
| `temporal`   | `kind` (`today`\|`now_within_tolerance`), `toleranceSeconds` | parsed as UTCTimestamp/UTCDate |
| `reference`  | `expression`                          | equals `${...}` resolved over scenario scope |

`path` (optional, for repeating groups): `{ groupTag, identityTag, identityValue }`
— locate the group entry by identity, never by position.

---

## Response shape

```jsonc
{
  "passed": false,
  "messageType": "8",
  "tags": [
    { "tag": 35,  "matcher": "exact 8",                 "expected": "8",        "actual": "8",        "passed": true  },
    { "tag": 150, "matcher": "exact F",                 "expected": "F",        "actual": "F",        "passed": true  },
    { "tag": 39,  "matcher": "oneOf [1,2]",             "expected": "1 | 2",    "actual": "8",        "passed": false }, // Rejected!
    { "tag": 11,  "matcher": "reference ${out.D.11}",   "expected": "ORD-abc",  "actual": "ORD-abc",  "passed": true  },
    { "tag": 37,  "matcher": "presence",                "expected": "<present>","actual": "OID-99",   "passed": true  },
    { "tag": 31,  "matcher": "numeric 1.2345 ±5e-5",    "expected": "1.2345",   "actual": "1.23451",  "passed": true  },
    { "tag": 60,  "matcher": "temporal now ±30s",       "expected": "~now",     "actual": "2026-06-30-09:15:02.123", "passed": true },
    { "tag": 58,  "matcher": "absent",                  "expected": "<absent>", "actual": "Order rejected: limit", "passed": false }
  ]
}
```

The `tags` list is the single representation that drives **both** consumers: CI reads
`passed`; the message viewer renders each entry as a red/green row. `expected`/`actual`
are human-readable so a failure reads on its own.

A row produced by a group-path assertion additionally carries its `path`
(`{"groupTag", "identityTag", "identityValue"}`, same shape as the request field) so
that two assertions on the same tag under different group entries stay distinguishable
in results — the field is **omitted** (not null) for top-level rows, keeping older
consumers parsing.

---

## Evaluation rules

1. **Select or await.** With `timeoutMs > 0`, block until a message matching
   `messageType`/`direction` arrives or the timeout elapses (reuse `fixtool_wait`
   internals). With `timeoutMs = 0`, evaluate the already-selected/last message. A
   timeout is a failure with an explanatory `tags` entry, not an error.
2. **Resolve references** against the active scenario variable scope (the
   `variables` map). Standalone (non-scenario) calls resolve `${out.X.tag}` /
   `${in.X.tag}` against session message history exactly as `fixtool_send resolve=true`
   already does.
3. **Per-tag evaluation** produces one `TagResult`; `passed = tags.all { it.passed }`.
4. **`absent`** passes iff the tag is not present; never reports `actual` as a match.
5. **`mode = strict`** adds a synthetic failing `TagResult` for every tag present in
   the message but absent from `fields` (except header/trailer volatiles `8,9,10,34,52`).
6. **Groups.** A `path` matcher resolves the group entry whose `identityTag` equals
   `identityValue`; if no such entry exists, fail with `actual: <no entry>`.

---

## Why this first

The hard design in the whole proposal is the assertion vocabulary — tolerances,
absence, temporal semantics, references, and group-by-identity. `fixtool_assert`
forces all of it to be pinned down on a single message, with immediate standalone
value. Once its `fields`/`matcher` shape is settled, `Expectation` is literally
"these fields plus the captured golden for display", and `fixtool_run_scenario` is a
loop that calls this evaluation per `Expect` step. Build and validate this against a
real `book-a-trade` ExecReport before committing to the scenario format.
</content>
