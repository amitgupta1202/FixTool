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
| `${uuid:20}` | a fresh dash-less UUID truncated to N chars (1–32) — what capture mints for correlation ids, short enough for venues that cap ClOrdID length |
| `${now}` | current **local** timestamp, `yyyyMMdd-HH:mm:ss.SSS` |
| `${utcnow}` | current **UTC** timestamp — what capture mints for UTCTimestamp fields (TransactTime, …) so a replay's stamp does not carry your local offset; takes the same `:pattern` and offsets as `now` |
| `${now:yyyyMMdd}` | `now` in a custom `DateTimeFormatter` pattern |
| `${now+1h}`, `${utcnow+5min}` | offset — units `min` (minutes), `h` (hours), `d` (days), `w` (weeks), `m` (months), `y` (years); `min` is minutes, a bare `m` is months |
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

Each entry in an expectation's `fields` is `{tag, matcher: {type, ...}}`. There is no `path`.

| `type` | Extra fields | Checks |
| --- | --- | --- |
| `exact` | `value` | literal string equality |
| `presence` | — | the tag is present; its value is ignored |
| `absent` | — | the tag is **not** present |
| `regex` | `pattern` | the value matches the pattern |
| `oneOf` | `values[]` | the value is in the set |
| `numeric` | `value`, `tolerance`? | `abs(actual − value) <= tolerance`; tolerance `0` still ignores formatting (`1.2345` == `1.23450`) |
| `temporal` | `kind` (`today` \| `now_within_tolerance`), `toleranceSeconds`? (default 60) | parsed as UTCTimestamp / UTCDateOnly |
| `reference` | `expression` | the value equals a resolved `${...}` expression (§1) |

Tip: `fixtool_capture_expectation` builds a draft expectation from a message already received, with
matchers pre-seeded from the data dictionary, **in wire order** — usually faster, and always correctly
ordered (see §3), than writing one by hand.

---

## 3. `fields` is ordered, and the order is the assertion

**`fields` is a list, not a set.** Two rules follow from that, and both matter.

### The k-th row for a tag asserts the k-th occurrence of that tag

This is how you assert inside a repeating group. There is no path, no entry, no identity — a repeating
group is just a tag appearing more than once, and a row's **position is its address**:

```
453=2 |
  448=FIRMA | 447=D | 452=1 |     <- executing firm
  448=FIRMA | 447=D | 452=4 |     <- clearing firm
```

```json
"fields": [
  {"tag": 453, "matcher": {"type": "exact", "value": "2"}},
  {"tag": 448, "matcher": {"type": "exact", "value": "FIRMA"}},
  {"tag": 447, "matcher": {"type": "exact", "value": "D"}},
  {"tag": 452, "matcher": {"type": "exact", "value": "1"}},
  {"tag": 448, "matcher": {"type": "exact", "value": "FIRMA"}},
  {"tag": 447, "matcher": {"type": "exact", "value": "D"}},
  {"tag": 452, "matcher": {"type": "exact", "value": "4"}}
]
```

The 4th row asserts the *first* `452`; the 7th asserts the *second*. Both firms are `FIRMA`, so no
identity could have told the two entries apart — the position does. **Do not sort or de-duplicate
`fields`**: doing either silently re-aims the assertions onto different fields.

### Your rows must be a subsequence of the message

They must appear in the order you list them, with anything else allowed in between. Given a reply
carrying `1, 2, 3, 4, 5`:

| Your `fields` | Result |
| --- | --- |
| `1, 3, 5` | ✅ passes — a subsequence; `2` and `4` are simply ignored |
| `1, 2, 3` | ✅ passes |
| `1, 2, 4` | ✅ passes |
| `1, 6, 3` | ❌ fails — there is no `6` in the reply (`status: missing`) |
| `1, 3, 2` | ❌ fails — the reply puts `2` before `3` (`status: moved`) |

So if the venue sends `37` before `11` and you list `11` before `37`, **the step fails** with
`status: moved`. That is not a bug: it is the same rule that catches a venue swapping two group
entries. List your rows in the order the venue sends them, or let
`fixtool_capture_expectation` do it for you.

### When FixTool refuses to judge

An expectation asserts an **order** as well as a set of values, so a message whose bytes FixTool could not
read cannot be evaluated at all — the order would have to be invented, and QuickFIX's re-serialisation is
not the venue's order (it sorts the body by tag and moves repeating groups to the end).

So `fixtool_assert` returns a **top-level** `status` of `no-wire-bytes`, with `passed: false` and an empty
`tags[]`, and `fixtool_capture_expectation` returns an error. **This is a FixTool limitation, not a venue
failure** — the message itself may be perfectly correct. Do not report it as a regression.

(In practice you will not see it: QuickFIX/J retains the bytes it parses. The one deliberate exception is an
*outgoing* Logon carrying `ResetSeqNumFlag=Y`, where QuickFIX rewrites `MsgSeqNum` after FixTool has seen the
message — so FixTool records no bytes rather than a sequence number the venue never received.)

### `mode`

| `mode` | Checks |
| --- | --- |
| `open` (default) | only the listed rows, in the listed order. A tag you do not mention is ignored, so a venue adding an optional field does not break you. |
| `strict` | additionally asserts the message's **shape**: the same tags, the same number of times, in the same order. Any unexpected tag fails. |

Neither mode **auto-seeds** the session envelope — `8`, `9`, `10`, `34`, `49`, `52`, `56`, `369` — and
`strict` never calls one an unexpected extra. They identify the connection and the moment, not the venue's
behaviour, so a scenario captured on DEV would otherwise go red on QA on every step. Capture reports them
under `notAsserted`.

A row you write **explicitly** on one of them is evaluated like any other, though: `{"tag": 34, "matcher":
{"type": "exact", "value": "5"}}` is a legitimate check on a gap-fill test, and it is judged normally. The
envelope is invisible to *seeding*, not to *you*.

An `absent` row takes no part in the ordering: it asserts the tag appears nowhere in the message.

---

## 4. Acceptor `responseTemplate` — restricted subset

Auto-response rules (`acceptorResponseRules` in a profile's `config`) use a *smaller* substitution
language than §1 — plain replacement, no variables, offsets or message references:

| Expression | Produces |
| --- | --- |
| `${req.<tag>}` | that tag echoed from the request, e.g. `${req.11}` |
| `${uuid}` | a fresh UUID |
| `${now}` | current local timestamp (`${utcnow}` for UTC) |

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

### Excluding a field from a `send` without deleting it

Prefix the tag with `#` in the `raw` and the field stays in the scenario but stays off the wire:

```
"raw": "35=D|11=${clOrdId = uuid}|#9303=1|38=100|"
```

This is for the question authors actually ask — *does the venue still accept this without tag 9303?* —
which wants a toggle rather than a delete-and-retype-from-memory. An excluded field is **wholly inert**:
it is not sent, it raises no unknown-tag lint, and its `${...}` is never resolved, so a mint sitting in
an excluded field binds nothing and a later `${...}` reference to that name is left literal on the wire
(the editor warns about exactly this). Un-prefix the tag and the field — and its mint — come back.

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
       {"tag": 11, "matcher": {"type": "reference", "expression": "${clOrdId}"}},
       {"tag": 37, "matcher": {"type": "presence"}},
       {"tag": 150, "matcher": {"type": "exact", "value": "0"}}
     ]}}
  ]
}
```

**The rows above are in the order the venue sends them, and that is not a stylistic choice.** They read
`11, 37, 150` because that is the order the built-in demo acceptor emits. List them `150, 37, 11` — the
order you might naturally think of them in, most-important-first — and the step **fails** with
`status: moved` on two rows, because an expectation must be a subsequence of the reply (§3).

A different venue may well send `37` before `11`, in which case *this* example would fail against *it*.
There is no ordering that is right for every venue, and there does not need to be: **`fixtool_capture_expectation`
and `fixtool_capture_scenario` seed rows in the venue's real wire order**, so a captured scenario is a
subsequence of its own golden by construction. Hand-write an expectation only when you know the order, and
reach for capture when you do not.

Tip: `fixtool_capture_scenario` records a live message flow into a scenario — it auto-parameterizes
TransactTime and correlation IDs and auto-wires echoed ids to `reference` matchers, which is the
quickest way to a working, correctly-templated scenario.

### Correlation ids the spec never named

Capture parameterizes the correlation ids **standard FIX** defines. A venue's own — `LegRefID(20001)`,
a proprietary batch id, a deal handle in the 20000s — are declared per dictionary, because a FIX dictionary
records a field's name and type but never **who mints its value**, and `ClOrdID(11)` and `OrderID(37)` are
indistinguishable by either.

Undeclared, a venue id replays **verbatim**: the same value every run (a duplicate at any venue enforcing
uniqueness), and the expect binds to the first message of that type rather than the reply to this run's ids.

- `GET /dictionary/roles` lists the tags this dictionary adds beyond standard FIX, id-shaped first.
- `POST /dictionary/roles` declares them — `CLIENT_MINTED_ID` (fresh per run, echo → `reference` + bind
  constraint), `VENUE_MINTED_ID` (`presence`, and a later send that quotes it back reads it from *this run's*
  reply via `bindAs`), or `LIFETIME`. Written to `<dictionary>.roles.json` beside the dictionary and live
  for the next capture.

Two fields on a capture response are worth reading rather than ignoring:

- **`warning`** — the loaded dictionary could not name these tags, so they were classified blind. A
  timestamp among them replays the captured moment for ever.
- **`echoProposals[]`** — values this flow shows coming back that nobody has declared, each with the
  evidence (`kind` is `MINT` if you sent it first, `CAPTURE` if the venue did). **Reported, never applied**:
  accept one by POSTing it to `/dictionary/roles` and capturing again. Declaring a role from a guessed echo
  is a write to the venue's dictionary, and that is the author's call, not an agent's.
