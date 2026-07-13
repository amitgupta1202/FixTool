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
| **`58=filled\|in full`** | A pipe **inside a value**. Legal FIX — `\|` is an ordinary character. Anything that establishes a message's delimiter by looking for a pipe shreds this field. It is what caught `GET /messages` reporting `58 = "filled"`, silently dropping the tail. |
| **The same firm twice** | Both party entries carry `448=FIRMA` under different `452` roles. This is the case the sequence model exists for and the one identity-based matching could never address — the identity does not identify. |

## Running it

```bash
python3 tools/fake-venue/fake_venue.py          # listens on 127.0.0.1:19999
```

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
