# FixTool expression & assertion syntax

The two mini-languages an agent needs to author messages and scenarios: **template expressions**
(`${...}`, used to parameterize what you send) and **matchers** (used to assert what came back).

---

## 1. Template expressions — `${...}`

### Where they are resolved

| Context | Resolved? |
| --- | --- |
| Scenario `send` step, `raw` | **Always** — there is no flag to set |
| Scenario `expect` / `wait`, `match.fields[].value` | Always |
| `reference` matcher, `expression` | Always |
| `fixtool_send` (`POST /send`) | **Only with `resolve: true`** — the default is `false`, and an unresolved `${uuid}` is sent on the wire *literally* |
| `fixtool_send_all` (`POST /send/all`) | Always, re-resolved per session |
| `fixtool_send_template` | Always |
| Acceptor `responseTemplate` | A restricted subset only — see §4 |

### Grammar

**Generated values**

| Expression | Produces |
| --- | --- |
| `${uuid}` | a fresh UUID |
| `${now}` | current UTC timestamp, `yyyyMMdd-HH:mm:ss.SSS` |
| `${now:yyyyMMdd}` | `now` in a custom `DateTimeFormatter` pattern |
| `${now+1h}`, `${now-2d}` | offset from now — units `h` (hours), `d` (days), `w` (weeks), `m` (months), `y` (years) |
| `${now+1d:yyyyMMdd}` | offset *and* custom pattern |

**Message references** — read a tag off the latest message of a given type on this session:

| Expression | Means |
| --- | --- |
| `${D.11}` | tag 11 of the latest `D` — incoming first, then outgoing |
| `${in.D.11}` / `${out.D.11}` | explicit direction |
| `${out.D.ClOrdID}` | field names work anywhere a tag number does |
| `${out.D.11.0}` | the 0-based occurrence, for a tag inside a repeating group |

**Variables**

- `${clOrdId = uuid}` — assigns *and* expands to the value inline, so it is both the definition and the first use.
- `${clOrdId}` — re-reads it later.
- The scope persists across an entire scenario run (`setup` → `steps` → `teardown`) and across sessions in
  that run, so a value sent on one session can be asserted in a response on another.
- Gotcha: an unknown `${name}` is left as the literal text `${name}` — it does not raise an error.

**Per-session variables** — `${sessionIndex}`, `${sessionQualifier}`, `${sessionTitle}`,
`${sessionSenderCompID}`. Available on `fixtool_send` (with `resolve`), `fixtool_send_all` and
`fixtool_send_template`; **not** in scenario steps.

**Fallback** — anything else is evaluated as a Kotlin expression, e.g. `${incoming["8"].valueOfTag(37)}`.

**Not a template:** `{n}` / `{nn}` is the *profile CompID numbering* pattern (a `senderCompID` of
`LOADGEN{nn}` with `sessionCount: 4`). It is not a message expression and is not resolved in `raw`.

### The canonical scenario idiom

```
send    35=D|11=${clOrdId = uuid}|55=EUR/USD|54=1|38=1000000|40=1|60=${now}|
expect  35=8, with tag 11 asserted by {"type":"reference","expression":"${clOrdId}"}
```

`${out.D.11}` is equivalent here and needs no variable; reach for a named variable when the value must
outlive the message it came from, or cross sessions.

---

## 2. Matchers

Each entry in an expectation's `fields` is `{tag, matcher: {type, ...}, path?}`.

| `type` | Extra fields | Checks |
| --- | --- | --- |
| `exact` | `value` | literal string equality |
| `presence` | — | the tag is present; its value is ignored |
| `absent` | — | the tag is **not** present |
| `regex` | `pattern` | the value matches the pattern |
| `oneOf` | `values[]` | the value is in the set |
| `numeric` | `value`, `tolerance`? | `abs(actual − value) <= tolerance`; tolerance `0` still ignores formatting (`1.2345` == `1.23450`) |
| `temporal` | `kind` (`today` \| `now_within_tolerance`), `toleranceSeconds`? | parsed as UTCTimestamp / UTCDateOnly |
| `reference` | `expression` | the value equals a resolved `${...}` expression (§1) |

`mode` is `open` (default — only the listed tags are checked, extras ignored) or `strict` (any
unexpected tag, besides volatile header/trailer tags, fails).

Tip: `fixtool_capture_expectation` builds a draft expectation from a message already received, with
matchers pre-seeded from the data dictionary — usually faster than writing one by hand.

---

## 3. `path` — asserting inside a repeating group

`path` locates a group entry by **identity, never by position** (entry order is not guaranteed):

```json
{"tag": 448, "matcher": {"type": "exact", "value": "BROKER-A"},
 "path": {"groupTag": 453, "identityTag": 452, "identityValue": "1"}}
```

→ "PartyID(448) of the NoPartyIDs(453) entry whose PartyRole(452) is 1".

| Field | Meaning |
| --- | --- |
| `groupTag` | the group's count tag, e.g. `453` |
| `identityTag` | the tag that identifies the entry, e.g. `452` |
| `identityValue` | the value that identifies it, e.g. `"1"` |
| `occurrence`? | 0-based, default `0` — only needed when the identity is **not** unique (e.g. several `NoMDEntries` sharing an `MDEntryType`); it counts, in wire order, among entries sharing that identity value |

---

## 4. Acceptor `responseTemplate` — restricted subset

Auto-response rules (`acceptorResponseRules` in a profile's `config`) use a *smaller* substitution
language than §1 — plain replacement, no variables, offsets or message references:

| Expression | Produces |
| --- | --- |
| `${req.<tag>}` | that tag echoed from the request, e.g. `${req.11}` |
| `${uuid}` | a fresh UUID |
| `${now}` | current UTC timestamp |

```json
{"whenMsgType": "D",
 "responseTemplate": "35=8|150=0|39=0|37=${uuid}|11=${req.11}|55=${req.55}|38=${req.38}"}
```

---

## 5. Scenario shape

`{name, profile?, userTags?, setup?: [step], steps: [step], teardown?: [step]}` — `setup` runs first,
`teardown` always runs, even on failure. A step is `{type, ...}`:

| `type` | Fields |
| --- | --- |
| `send` | `raw`, `session?` |
| `expect` | `session?`, `direction?`, `match?`, `timeoutMs?`, `expectation: {messageType?, mode?, fields: [...]}` |
| `wait` | `session?`, `state?` (e.g. `LOGGED_ON`), `match?`, `timeoutMs?` |
| `clearMessages` | `session?` |
| `resetSeqNum` | `session?`, `sender?`, `target?` |

`match` is `{messageType?, direction?, fields: [{tag, value}]}` — all conditions AND together, and its
values may themselves be `${...}` expressions. An `expect` **consumes** the message it matches, so a
partial-fill sequence is just successive `expect` steps. Each step may target a different `session`
(an initiator and an acceptor in one scenario).

```json
{
  "name": "book-a-trade",
  "setup": [{"type": "clearMessages", "session": "CLI"}],
  "steps": [
    {"type": "send", "session": "CLI",
     "raw": "35=D|11=${clOrdId = uuid}|55=EUR/USD|54=1|38=100|40=1|60=${now}|"},
    {"type": "expect", "session": "CLI", "direction": "in", "timeoutMs": 8000,
     "expectation": {"messageType": "8", "fields": [
       {"tag": 150, "matcher": {"type": "exact", "value": "0"}},
       {"tag": 37, "matcher": {"type": "presence"}},
       {"tag": 11, "matcher": {"type": "reference", "expression": "${clOrdId}"}}
     ]}}
  ]
}
```

Tip: `fixtool_capture_scenario` records a live message flow into a scenario — it auto-parameterizes
TransactTime and correlation IDs and auto-wires echoed ids to `reference` matchers, which is the
quickest way to a working, correctly-templated scenario.
