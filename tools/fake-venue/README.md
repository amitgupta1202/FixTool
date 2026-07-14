# fake-venue — a FIX venue that sends messages FixTool can get wrong

A ~120-line Python acceptor that speaks just enough FIX to log on and answer a
`NewOrderSingle`. It exists to test one thing the rest of the suite cannot reach: **that the
assertion engine reads the bytes the venue actually sent.**

## Why the built-in demo acceptor cannot do this

The demo acceptor (`service/demo/DemoFixServer.kt`) is built on QuickFIX/J. It composes its
ExecutionReport with `message.setString(37, …)`, `setString(11, …)`, and QuickFIX serialises
that to the socket. QuickFIX, with no `fieldOrder` configured, always serialises the body
**ascending by tag** and appends repeating groups at the end.

That is *the same transformation* the wire-order bug applied. `FixMessage.wireRaw` used to fall
back to `quickfix.Message.toString()`, which sorts ascending and moves groups to the end — so
against a QuickFIX-based venue, **the broken code and the fixed code emit byte-identical
output.** A green run against the demo acceptor proves nothing at all about wire order. The
test cannot fail, which means it cannot pass either.

To test the contract you need a venue that is *not* QuickFIX. That is this.

## What it sends, and what each trap catches

On a `NewOrderSingle` it replies with an ExecutionReport whose bytes are deliberately in an
order QuickFIX would never produce:

```
37=VENUE-ORD-9 | 11=<your ClOrdID> | 17=EXEC-1 | 150=2 | 39=2 | 55 | 54 | 38 | 44 | 6 | 14 | 151
453=2 | 448=FIRMA | 447=D | 452=1 | 448=FIRMA | 447=D | 452=4
58=filled|in full | 60=<now>
```

| What | Why it is there |
|---|---|
| **`37` before `11`** | A real venue's order. QuickFIX's `toString()` sorts ascending, so it would emit `6, 11, 14, 37…`. If FixTool shows `11` before `37`, it read the re-serialisation and the wire-order fix is broken. |
| **The party group mid-body** | `toString()` relocates every repeating group to the **end** of the body, after `58`. If FixTool shows `453…` after `58`, same conclusion. |
| **`58=filled\|in full`** | A pipe **inside a value**. Legal FIX — `\|` is an ordinary character. Anything that establishes a message's delimiter by looking for a pipe shreds this field. It is what caught `GET /messages` reporting `58 = "filled"`, silently dropping the tail. **It was silently dropped from this venue by `088251c`** (the commit that added the modes) and restored in Phase 5: for three phases the trap this table describes was not armed, and every live run was against a venue sending a space where the pipe should be. |
| **Two different firms** | The entries carry `448=FIRMA` and `448=FIRMB`. They have to: with the same firm twice, an entry reorder and a role swap produce **byte-identical wire**, and the `shape` and `swap` modes below would be indistinguishable — which is the one distinction this venue exists to make live. (The same-firm-twice case, where the identity does not identify, is covered by the unit fixtures; a live venue cannot carry both traps at once.) |

## The message log — and why the venue has two outputs

```bash
python3 tools/fake-venue/fake_venue.py          # listens on 127.0.0.1:19999
```

It writes **two** renderings of every message, and the difference between them is the pipe trap:

| output | delimiter | can it be read back? |
|---|---|---|
| **stdout** (`<<` / `>>`) | SOH rendered as `\|`, for a human | **No.** `58=filled\|in full` now contains a pipe that is not a delimiter, and nothing in the line says which pipe is which. FixTool's paste box **refuses** it and quotes the message's own `CheckSum(10)` as the evidence. |
| **the message log** (`$FAKE_VENUE_LOG`, default `/tmp/fake_venue.log`) | **SOH**, as a real venue's log stores them | **Yes** — SOH cannot occur inside a value, so `58` comes back whole, pipe and all. This is what W2 pastes. |

Each log line is `<timestamp> <IN|OUT> <bytes>`, so it also exercises the other half of the paste
reader: the **log prefix**, which is skipped and *reported*, never silently eaten. The `IN`/`OUT`
words are the **venue's** point of view — FixTool derives a pasted message's direction from
`SenderCompID(49)` against the session's own CompIDs, never from a word in someone else's log.

Then point a FixTool session at it (`SenderCompID=FIXTOOL`, `TargetCompID=FAKE_VENUE`,
`localhost:19999`, FIX.4.4) and send a `NewOrderSingle`. Over the control surface:

```bash
B=http://127.0.0.1:8799
curl -s -XPOST $B/profiles -d '{"name":"Venue","config":{"senderCompID":"FIXTOOL","targetCompID":"FAKE_VENUE","host":"127.0.0.1","port":"19999","beginString":"FIX.4.4","heartBtInt":"30","resetOnLogon":true}}'
curl -s -XPOST $B/connect  -d '{"profile":"Venue"}'
curl -s -XPOST $B/send     -d '{"session":"Venue","raw":"8=FIX.4.4|35=D|11=ORD-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260101-00:00:00|"}'
curl -s "$B/messages?session=Venue&direction=incoming"
```

## What a correct FixTool does with it

- The incoming message's fields come back **`37, 11, 17, 150, … 453, 448, 447, 452, 448, 447, 452, 58, 60`** — the venue's order, group inline.
- Tag `58` is **one** field whose value is `filled|in full`, not a truncated `filled`.
- `fixtool_capture_scenario` seeds its rows in that order, stores the golden as SOH wire bytes, and gives **each party entry its own rows** (`452 exact 1` and `452 exact 4`).
- Replaying that scenario passes.
- Editing the **second** `452` row to assert `1` — the venue sends `4` there, and `1` on the *first* entry — fails **exactly one row, at occurrence 1**. If it passes, pairing has re-aimed onto the entry that happens to satisfy the matcher, and that is the false green the whole model exists to make impossible.

See `docs/scenario-assertion-model.md`.

## The three modes, and staging the reconcile demo

The venue's behaviour is switchable at runtime by writing one word to its mode file
(`/tmp/fake_venue_mode`, or `$FAKE_VENUE_MODE_FILE`):

| mode | what the reply does | what it is for |
|---|---|---|
| `golden` | the reply a scenario is captured from | capture |
| `shape` | the party **entries swap places** (benign — FIRMA still holds role 1), plus a real `151` regression, a tag added and a tag dropped | the four kinds of failure the reconcile view is organised around. A re-order **should** be offered here. |
| `swap` | the two firms **swap ROLES**. Same tags, same positions, same everything else. | a behaviour regression that must **never** be offered as "entry moved". If a move is offered here, a false green is back. |

Staging the demo end to end, against a FixTool with the control surface enabled
(`FIXTOOL_CONTROL_PORT=8799`):

```bash
echo golden > /tmp/fake_venue_mode
python3 tools/fake-venue/fake_venue.py &          # 127.0.0.1:19999

B=http://127.0.0.1:8799
curl -s -XPOST $B/profiles -d '{"name":"Venue","config":{"senderCompID":"FIXTOOL","targetCompID":"FAKE_VENUE","host":"127.0.0.1","port":"19999","beginString":"FIX.4.4","heartBtInt":"30","resetOnLogon":true}}'
curl -s -XPOST $B/connect  -d '{"profile":"Venue"}'
curl -s -XPOST $B/send     -d '{"session":"Venue","raw":"8=FIX.4.4|35=D|11=DEMO-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260101-00:00:00|"}'

# capture the scenario from the golden reply, then break it two different ways
ID=$(curl -s -XPOST $B/scenarios/capture -d '{"name":"DEMO","sessions":["Venue"]}' | sed 's/.*"id":"\([^"]*\)".*/\1/' | head -c 36)

echo shape > /tmp/fake_venue_mode ; curl -s -XPOST $B/scenarios/run -d "{\"id\":\"$ID\"}"   # entries moved
echo swap  > /tmp/fake_venue_mode ; curl -s -XPOST $B/scenarios/run -d "{\"id\":\"$ID\"}"   # roles swapped

curl -s -XPOST $B/panel -d '{"panel":"scenarios"}'   # the run report → "Reconcile assertions →"
```

In `shape` the reconcile view brackets each party as **⇅ Entry moved** and offers **Accept new order**.
In `swap` it offers no move at all, and says why — *"these rows did not move… the values changed in
place"* — while still giving the author a `move entry ↑ ↓` handle on each entry. That difference is the
whole point of this venue.

**Two instances of FixTool can share one venue**, but not one mode file: pass a different
`FAKE_VENUE_MODE_FILE` (and edit the hard-coded port) if you need an isolated copy.
