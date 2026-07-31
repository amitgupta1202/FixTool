# Default acceptor response presets — proposal

**Issue:** [#31](https://github.com/amitgupta1202/FixTool/issues/31)
**Depends on:** #30 (auto-response rules engine, shipped), #34 (conditional logic, shipped)
**Unblocks:** [#40](https://github.com/amitgupta1202/FixTool/issues/40) ("Reply With…") — **shipped**, as
`AcceptorPresets.replyShapes` and the `Reply With…` menu in the message detail panel. A shape is a *step*
where a preset is a *rule*: every template it offers is one a preset here already plays, referenced rather
than re-typed, so the venue answers the same way whether a rule sends it or a tester does.
**Mockups:** https://claude.ai/code/artifact/521f1b1f-2ce4-4a3f-a69c-1e010cb49429

---

## The problem

Everything needed to *author* a venue shipped with #30 and #34: triggers with the full matcher
vocabulary, multi-step replies with delays, `${req.<tag>}` substitution, computed fields, and a dry
run. What did not ship is any *content*. Open **Auto-Responses** on a new acceptor profile and it
says `No rules — incoming messages get no reply`, and the only way forward is to know, from memory,
that an ExecutionReport needs tags 37, 17, 150, 39, 151, 14 and 6 — and which of those the incoming
order can supply.

So the tester who wants to point a client at a venue and watch it fill an order spends their first
hour writing FIX by hand, before finding out whether their client works at all.

## What already exists — do not rebuild

- **Custom templates**: `/templates` GET/POST/DELETE and the `fixtool_*_template` tools.
- **Field copying**: `${req.<tag>}`, `${uuid}`, `${now}`, and computed forms like `14=${req.38 / 2}`.
- **Multi-step replies with delays**, per-rule triggers with the full matcher vocabulary, and
  `POST /acceptor/test`, which renders exactly what a rule would send.
- **A message editor** whose preview pane is already a live two-way binding between a field grid and
  a raw FIX string (`MessageEditorPanel`), and whose field values already carry `${…}` expressions.

## The shape

Two slices, both under #31.

- **Slice A — the content.** A catalogue of presets, inserted as ordinary rules, placed where they
  can fire, plus the engine and control-surface changes they need.
- **Slice B — the editing.** A reply step opens in the message editor instead of being a raw string.

---

## The catalogue

Eight presets and one bundle. Every reply is a raw FIX template in the existing format — QuickFIX/J
stamps the header and trailer, as for every rule today.

| Preset | Trigger | Reply | Why this shape |
|---|---|---|---|
| Order acknowledged | `35=D` | `150=0`, `39=0`, `14=0`, `151=${req.38}` | The floor: any client that sends an order gets a well-formed ExecutionReport back. |
| Acknowledged, then filled | `35=D`, `40 = 2` | +0ms ack, +250ms `150=F`/`39=2` | Conditioned on LIMIT because the fill prices at `${req.44}`, and a market order carries no 44. |
| Acknowledged, partial, then filled | `35=D`, `40 = 2` | +0 / +250 / +250 | Integer halves, so CumQty + LeavesQty equals OrderQty at every step, for any quantity. |
| Order rejected — over size limit | `35=D`, `38 > 1000000` | `150=8`, `39=8`, `103=3` | A venue rejects *some* orders. A bound is a real venue rule and needs no naming convention. |
| Cancel accepted | `35=F` | +0ms `150=6`/`39=6`, +150ms `150=4`/`39=4` | Pending-then-canceled is what venues send, and it is a client's most-missed transition. |
| Cancel rejected — unknown order | `35=F` | `35=9`, `434=1`, `102=1` | The other half of the cancel path. See the limitation below. |
| Replace accepted | `35=G`, `38` present | `150=5`, `39=0`, `151=${req.38}` | The presence condition is not decoration: OrderQty is optional on 35=G, and the reply reads it. |
| Unsupported message | `35=H` | `35=j`, `372=H`, `380=3` | Every venue refuses something, and BusinessMessageReject is the shape clients handle worst. |
| **Starter venue** (bundle) | — | 4 rules | Ack-and-fill for limits, ack for the rest, cancel accepted, replace accepted — in that order. |

### One preset carries a limitation worth stating

**"Cancel rejected — unknown order" cannot know the order is unknown.** With no order book, nothing
distinguishes a cancel for a live order from a cancel for one that never existed, so this preset is
an unconditional alternative to "Cancel accepted" — useful for driving a client's reject path,
honest about being a stub. **#35 (order state management) is what makes it conditionable**, and this
is the concrete argument for #35 that the issue itself does not make.

---

## Decisions

**D1 — A preset is a rule, not a kind of rule.** Insert copies an `AcceptorResponseRule` into the
list. No provenance on disk, no new file-format concept, no migration, and nothing at runtime that
behaves differently because a rule came from the library.

**D2 — Presets live in code, like the demo templates.** One `AcceptorPresets` object, the single
source for the menu, the control surface, and later #40. Not a bundled JSON file: a file would need
loading, validating, versioning, and a story for the user who edits it.

**D3 — Nothing is seeded automatically.** New acceptor profiles stay silent until an author asks.
Same stance as #32's opt-in wildcard: a simulator that answers on its own can turn a real client
misconfiguration green.

**D4 — Placement is part of the insert.** Rules are first-match-wins, so a conditioned preset lands
above the first **enabled rule for its MsgType**, whichever it is; an unconditioned one appends,
since it answers everything of its type and would otherwise take that type from the rules already
there. Above an unconditioned rule because otherwise it can never fire at all; above a conditioned
one for the weaker but commoner reason that an author adding a specific behaviour means the specific
one to happen. *Revised during live verification*: the first version displaced only unconditioned
rules, which put `order-reject-size` below the starter venue's ack-and-fill — so a two-million-share
limit order filled instead of rejecting. It was reachable (a market order that size would have
reached it) and still the wrong answer to the question just asked. This is a default position, not a
claim about overlap (see D5); the menu states where it will land before the click, and every rule
keeps its up/down arrows.

**D5 — Unreachability is claimed only when provable.** Earlier + enabled + same MsgType + *no*
conditions. Whether two conditioned rules overlap is undecidable in general, and a warning that is
sometimes wrong is one authors learn to ignore.

**D6 — The preview shows the template, not a rendering.** `/acceptor/test` is where you see what the
wire gets, against a message you chose. The menu shows what lands in the editor; otherwise the two
disagree and the author has to reconcile them.

**D7 — Same insert path for the panel and the agent.** `POST /acceptor/rules {"preset": "…"}` reuses
the endpoint that already owns index semantics, live-session push and `appliedToLiveSessions`. A
preset is not a second way to write a rule.

**D8 — Presets carry conditions where a venue would.** They are the shipped examples of the matcher
vocabulary — a range on a size limit, an exact on OrdType, a presence on OrderQty. An author's first
edit is to a rule that already shows how conditions work.

**D9 — One editor, reached from a second place** *(slice B)*. No field grid inside the connection
panel. That is the call `MatcherEditor` already made for triggers: a second editor is a second
vocabulary wearing the first one's clothes, and the two drift. The raw field stays; the grid is an
alternative, not a replacement.

**D10 — The sample request is derived, not stored** *(slice B)*. The rule already says what its
sample must contain: the tags its trigger conditions name, plus every `${req.<tag>}` its steps read,
valued by the dictionary's type per tag. So a step that grows `${req.44}` grows a tag 44 in its own
sample, with no maintenance and nothing to keep in sync — and no file-format change. An author's
override is view state, and it is what the Test button sends.

**D11 — Apply refuses what the string cannot carry** *(slice B)*. A value containing the field
delimiter, and an empty value, are named by tag at Apply rather than written into a template that
will go out malformed. The round trip is lossless only while both ends agree, so the disagreements
are reported instead of absorbed.

---

## Two defects the catalogue surfaced

Both are invisible while every rule is hand-written by someone watching the wire, and neither is
survivable in something we ship.

### 1. `${uuid}` cannot hold an OrderID across a sequence

`resolveAtSendTime` re-runs per step, so a two-step ack-then-fill emits a **different** tag 37 in
each message. To a client tracking OrderID that is two unrelated orders, and the fill belongs to
neither. In the other direction, within one step every `${uuid}` resolves to the *same* value, so
OrderID and ExecID come out identical.

**Fix:** `${req.uuid}` — one id per triggering message, resolved in `plan()` beside
`${req.<tag>}` and shared by every step of that reply. No new concept: everything in the `req.`
namespace already means "fixed when the trigger arrived". `${uuid}` keeps its per-step meaning and
stays right for ExecID.

### 2. A missing tag becomes an empty field on the wire

`${req.44}` against a market order substitutes an empty string, and `parseFixMessage` reads `31=` as
a real field with an empty value — so the venue sends a malformed message and blames the client.

**The rule this imposes:** a preset never reads a tag its own trigger does not guarantee. That is
why the fill presets are conditioned on `40 = 2`, why the replace preset carries `38` present, and
why the cancel presets never read OrderQty. A test asserts it for every preset rather than trusting
the list to stay disciplined.

---

## Verification

The risk in shipping content is that it goes stale silently: a preset that stops firing looks
exactly like a venue that is working. So presets are tested through the engine that runs them.

- **Every preset fires.** `AcceptorResponder.explain` is asked with each preset's canonical trigger
  and must select that rule — the same judgement the wire makes.
- **Every preset renders cleanly.** Planned and built against that trigger, the result must parse to
  the MsgType it advertises and contain **no empty-valued field**.
- **Every preset is valid.** `validationError() == null`, so the library cannot ship a rule the
  editor would flag.
- **OrderID is stable across a sequence.** Multi-step presets emit the same tag 37 in every step and
  a different tag 17 — the assertion that pins `${req.uuid}`.
- **Placement and shadowing.** A conditioned insert lands above an unconditioned same-type rule; an
  unconditioned one appends; the static-shadow predicate fires on the provable case and stays silent
  on two conditioned rules.
- **Control surface.** Insert by name, refuse an unknown name, report index and live-session count.
- **Live.** Starter venue applied to a running acceptor, a limit order sent, ack and fill observed
  with the authored gap between them.

*(Slice B adds: the raw → fields → raw round trip is an identity for every preset step, and Apply
refuses a delimiter or an empty value by tag.)*

---

## Scope

**In — slice A:** `AcceptorPresets`; the menu and hover preview in `AcceptorRulesEditor`; the
empty-state offer and Starter venue button; conditioned-insert placement with its transient note;
the static unreachable-rule warning (for all rules, not just presets); `${req.uuid}`;
`GET /acceptor/presets`, `preset` on `POST /acceptor/rules`, and one MCP tool.

**In — slice B:** a reply-step target for `MessageEditorPanel` with Apply/Cancel in place of Send;
`⧉` on every step row and the staged write-back; the request-value picker on the value cell; the
derived sample and its resolved preview; apply-time refusals; docs.

**Out:** "Reply With…" on a received message (#40 — it is slice B with the sample swapped for a real
received message and Apply swapped for Send); an order book or any preset that depends on one (#35);
a field grid inside the connection panel (D9); user-defined presets (the template library and
profile export already share rules between people); editing presets in place — an inserted rule is
an ordinary rule and is edited as one; any change to the rule file format.
