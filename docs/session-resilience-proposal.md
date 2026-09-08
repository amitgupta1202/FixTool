# FixTool Session Resilience — expose the session-fault actions we already have

## Background

The hardest part of a FIX integration is not the order flow, it is the session layer:
sequence-number recovery, resend, reset, and reconnect after a drop. Every venue
conformance script has a section on it, and the bugs it catches live in the
application above the engine, not in the engine: an OMS that books a resent fill
twice, a risk check that runs again on a replayed order, a client that will not
recover after a gap.

Testing this means being the counterparty that misbehaves on demand,
deterministically, one case at a time, and then reading what the system under test
does on the wire.

FixTool already **is** that counterparty for the well-formed cases. The QuickFIX/J
engine ([`FixConnectionManager`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/FixConnectionManager.kt))
runs the state machine, so as a client or a venue FixTool already answers a
ResendRequest from its store, gap-fills, stamps `PossDupFlag(43)=Y` on replays,
detects duplicates, heartbeats, and sends a TestRequest when the line goes quiet.

What is missing is every way a *tester* would reach the fault-injection side of
this without `curl`.

## What already exists (and is reused)

The engine and the HTTP/MCP control surface already carry the whole action set.
The gap is exposure, not capability.

- **Engine calls.** `sendResendRequest`, `sendSequenceReset`, `forceLogout`,
  `forceDisconnect`, `resetSequenceNumbers`, `sequenceNumbers`, `sendTestRequest`
  on the session facade
  ([`FixMessageSession`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/FixMessageSession.kt),
  delegating to [`QuickFixService`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/QuickFixService.kt)).
- **Control endpoint.** `POST /admin` with `action` in
  `seqnum | reset-seqnum | test-request | resend-request | sequence-reset | logout | disconnect | stop-responses`
  ([`ControlServer.admin`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/ControlServer.kt), ~line 2472).
  It works on an acceptor session too, so a venue can drop a client on command today.
- **MCP.** The same set is the `fixtool_admin` tool
  ([`McpTools`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/control/McpTools.kt), ~line 562).
- **Scenarios — verification side is done.** An `Expect` step can already bind an
  admin message: the shipped `session-round-trip-probe` demo sends `35=1` and
  expects the `35=0` Heartbeat back, so `Expect 35=2` (ResendRequest) or
  `Expect 35=4` (SequenceReset) is authorable now. Only the STRICT "and nothing
  else" sweep exempts admin types, which is correct — an unasked ResendRequest is
  envelope, not a stray.
- **Scenarios — injection side, one of six.** Of the actions above, only
  `resetSeqNum` is a scenario step
  ([`ScenarioStep.ResetSeqNum`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/scenario/Scenario.kt)).
  The other five cannot be written into a repeatable flow.
- **Profiles.** `ResetOnLogon/Logout/Disconnect`, file-or-memory store, `HeartBtInt`,
  `ReconnectInterval`, and a raw QuickFIX parameter passthrough are all already on
  the connection form.

So a working session test is possible today, entirely from `curl` or an agent:

```bash
B=http://127.0.0.1:8765

# Create a gap: push our next outbound seq ahead, then send. A correct venue
# answers 35=2, and our side gap-fills and replays with 43=Y.
curl -s -X POST $B/admin -d '{"session":"CLIENT","action":"reset-seqnum","sender":20}'

# Too low: the venue should Logout "MsgSeqNum too low, expecting N but received M".
curl -s -X POST $B/admin -d '{"session":"CLIENT","action":"reset-seqnum","sender":5}'

# Ungraceful drop, then let auto-reconnect recover.
curl -s -X POST $B/admin -d '{"session":"CLIENT","action":"disconnect","reason":"kill"}'
```

## The gap

- **Nothing is clickable.** No button injects any of these, and the connection
  pane does not even show the current expected sequence numbers. A tester cannot
  see or set the one piece of state the whole session layer turns on.
- **Five of six actions are not repeatable.** A recovery flow — "send an order,
  drop the line, reconnect, assert the venue asked for a resend, assert no
  duplicate fill" — cannot be saved as a scenario and re-run as a regression test
  with a JUnit report. Only the manual `curl` route exists.

Both are exposure of code that already works and is already exercised at the engine
level. The proposal is to close them, and nothing more.

## Proposed

### 1. A Session menu on the connection pane

A small menu beside the existing Connect/Disconnect control on the toolbar
([`TabBar`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/ui/TabBar.kt) /
[`ConnectionPanel`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/ui/ConnectionPanel.kt)):

- **Next sender / next target** shown live, read from `sequenceNumbers()`.
- **Set next sequence numbers…** (reset-seqnum), **Send TestRequest**,
  **Send ResendRequest…**, **Send SequenceReset…**, **Force Logout**,
  **Force Disconnect**.
- Available on an acceptor session too, so a venue can drop or reset a chosen
  client. In multi-client venue mode the target is the selected per-client session.

This is pure UI over the session facade. No engine change.

### 2. Scenario steps for the five missing actions

Add the session-injection steps so a recovery flow is repeatable, matching the
shape of the existing `ResetSeqNum` step through the whole stack
([`ScenarioStep`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/scenario/Scenario.kt),
[`ScenarioCodec`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/ScenarioCodec.kt),
[`ScenarioRunner`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/service/ScenarioRunner.kt),
[`ScenarioEditor`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/ui/ScenarioEditor.kt),
`ScenarioHost`, `McpTools`, `AUTOMATION.md`):

| Step JSON | Action |
|---|---|
| `{"type":"testRequest","session?","id?"}` | send `35=1` |
| `{"type":"resendRequest","session?","begin","end?"}` | send `35=2` (`end` 0 = to latest) |
| `{"type":"sequenceReset","session?","newSeq","gapFill?"}` | send `35=4` |
| `{"type":"forceLogout","session?","reason?"}` | send `35=5`, disable the session |
| `{"type":"disconnect","session?","reason?"}` | drop the socket, no Logout |

The verification half needs nothing new: `Expect 35=2` and friends already bind.

A worked recovery scenario, FixTool as the client:

```
send          35=D|11=${clOrdId = ORD-1}...
expect        35=8
disconnect
wait          state=LOGGED_ON          # auto-reconnect
expect        35=2                      # venue asks for a resend
expect        35=8  55=... 11=${clOrdId}  # the fill comes back exactly once
```

**One behavioural note the editor must surface.** `disconnect` is ungraceful and
leaves the session enabled, so `autoReconnect` brings it back on its own.
`forceLogout` calls QuickFIX's graceful logout, which disables the session
(`setEnabled(false)`), so it will *not* reconnect by itself. A scenario that logs
out and expects the session back needs a reconnect. The cleanest fix is a small
sixth step, `{"type":"connect","session?"}`, over the host's existing
`connectSession` (used today only for preflight auto-connect). This is worth
including in item 2 rather than leaving a step that quietly never recovers.

## Non-goals (the identity boundary)

FixTool injects only what a FIX engine can express legitimately: sequence numbers,
admin messages, and the connection lifecycle. **It never tampers with bytes or
timing below the engine.** That rules the following out by principle, not by mood:

- Dropping or reordering individual bytes on the wire.
- Muting heartbeats, ignoring a ResendRequest, corrupting a checksum, sending
  `43=Y` without `122` on purpose, refusing a Logon with a malformed body.

These are a network-chaos proxy, a different product. A tester who needs them puts
`toxiproxy` or `tc netem` in front of FixTool, or kills the process. This is the
sentence to point at when the next "can it corrupt a checksum" request arrives.

## Deferred (named so its absence is not read as a gap)

**Rule-driven session actions.** Today an acceptor auto-response rule
([`AcceptorResponseRule`](../composeApp/src/jvmMain/kotlin/com/knapsack/fixtool/model/AcceptorResponseRule.kt))
reacts to a trigger with a **reply message**. The mirror of item 2 is a rule that
reacts with a **session action** instead — "when `35=D` and `11=KILL-ME` arrives,
drop the connection" — so a client team can point their own unattended test suite
at FixTool as the venue and have it misbehave with no one operating FixTool.

It is inside the boundary above (it uses the same engine calls), so it is a fit.
It is deferred because:

- It changes what a rule *is* (reply-with-a-message becomes reply-with-an-action),
  a design decision that belongs in its own issue with the rule card and codec.
- When a tester is present, an acceptor-bound scenario already covers the same case.
- Dropping the connection from inside a rule interacts with the venue's other
  clients, queued responses, and the order book — each needs a decision and a test.

## Testing

- **Unit / codec.** Round-trip each new step through `ScenarioCodec`; a file that
  never used one never grows the key (the `ResetSeqNum` bargain).
- **Integration.** Extend `ControlServerIntegrationTest`, which today only asserts
  `/admin` returned `ok`, to drive a real recovery against `TestFixServer`: inject
  a gap, assert the ResendRequest and the single replayed fill on the wire.
- **Scenario runner.** A recovery scenario against `TestFixServer` that passes
  green and, with a duplicate-fill bug injected, fails by name.

## Verified live (2026-09-06)

Driven through the control surface against a self-contained FixTool venue and client
(both `VERIFY TEMP`, deleted after), confirming the "already works" premise:

- **All `/admin` actions execute at runtime:** `seqnum`, `reset-seqnum`,
  `test-request` (Heartbeat came back echoing `112`), `resend-request`,
  `sequence-reset`, `disconnect`.
- **FixTool is already the recovering counterparty.** Pushing the client's next
  sender seq to 20 and sending an order produced the full dance on the wire:

  ```
  OUT 34=20 35=D   NewOrderSingle          (the injected gap)
  INC 34=3  35=2   ResendRequest  7=3 16=0 (venue asks for a resend)
  OUT 34=20 35=D   NewOrderSingle  43=Y    (replay, PossDup)
  OUT 34=3  35=4   SequenceReset  36=20 123=Y 43=Y  (gap fill)
  INC 34=4  35=8   ExecReport              (back in sync, acked)
  ```

- **Expect already binds admin messages.** A saved scenario that sends `35=1` and
  expects `35=0` passed (`detail: messageType=0`), so the verification half of item
  2 needs no new work.
- **The disconnect / reconnect note holds.** `disconnect` left the session enabled;
  it went DISCONNECTED then auto-reconnected to LOGGED_ON after ~26s
  (≈ `ReconnectInterval`). `forceLogout` would instead disable it — the reason item
  2 includes a `connect` step.

## Resolved decisions

- Scope is items 1 and 2 only. The non-goals are out by principle. Rule-driven
  actions are deferred to their own issue.
- Verification needs no change: `Expect` already binds admin message types.
- `connect` is included in item 2, because `forceLogout` disables the session and a
  recovery flow otherwise cannot come back.
