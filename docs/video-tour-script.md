# FixTool product tour — recording script

**Audience:** dev + QA (mostly QA). **Genre:** capability demonstration, not a promo.
**Target length:** ~15 minutes. **Format:** one video, chaptered (markers in the appendix).

**Division of labour:** the narration below is read by the presenter, word for word or lightly
adapted — it is the single source of what is claimed on camera. The agent that stages the recording
executes only the ACTIONS and the reset recipe in Appendix B; it never invents words or clicks.

**Tone rules** (these are what make it a demonstration):

- Narrate mechanism, not benefit: *"it replied because this rule matched — here is the trigger."*
- Whenever a claim is made about a message, show the RAW MESSAGE section. This audience trusts bytes.
- Do not cut the waits. The 40–80ms venue latency and the run progressing are evidence.
- No superlatives. The one allowed positioning line is in Chapter 1.

**Before recording:**

- Commit the working tree; record against a fixed commit so every retake is the same build.
- 4K capture (or 1080p minimum), UI scale ~125–150%, hide personal items from the desktop.
- Rehearse once end-to-end; the timings below assume ~150 words/min spoken.

---

## Chapter 1 — What this is (0:00–0:45)

**SCREEN:** the finished state, already running: Demo Client 1 pane with an order lifecycle in the
grid, the FX Demo Venue pane beside it, a green scenario run visible in the rail. Hold it static.

**NARRATION** (~100 words):

> FixTool is a desktop tool for testing FIX connectivity. It plays either side of a FIX session —
> the client side, or the venue side — and it turns the conversations you have into repeatable,
> field-level tests. It runs entirely on your machine; nothing leaves it.
>
> In the next fifteen minutes I'll send and read FIX messages, look at the same message in every
> view the tool has, build a venue out of rules, turn a conversation into a scenario — and then
> hand the same controls to an AI agent over MCP, and finish with a scenario running in CI with a
> real exit code.

---

## Chapter 2 — Zero to connected (0:45–1:50)

**SCREEN:** fresh app, no profiles.

**ACTIONS:**
1. Click **Open FX Venue example** in the empty session area. No dialog — it copies and opens.
2. Let the profiles appear: *FX Demo Venue*, *Demo Client 1*, *Demo Client 2*. Connect the venue, then
   the clients.
3. Let the clients log on; point the cursor at the venue's pane title *FX Demo Venue ← DEMO_CLIENT1*.

**NARRATION** (~150 words):

> I'm starting from an empty install. One click, and FixTool copies a small FX workspace into a folder
> of its own: one venue, two clients, and a set of message templates. It is a copy, so anything I change
> here is mine.
>
> The important thing here is what the venue *is*. It is not a canned simulator — it's FixTool's own
> acceptor mode, running in the same window, with an ordinary connection profile. The two clients
> are ordinary initiator profiles pointed at it. Client side and venue side are the same product.
>
> Both clients are logged on to the one venue — it accepts any CompID — and each connection gets its
> own pane. The venue is also configured with simulated latency, forty to eighty milliseconds per
> reply, because a counterparty that answers in microseconds is a latency no real venue has.
>
> Everything the venue is about to do is twenty-one rules we can read. We'll open them in a minute.

---

## Chapter 3 — Sending, receiving, and reading messages (1:50–5:15)

**SCREEN:** Demo Client 1 pane.

**ACTIONS:**
1. Open template **FX Limit Buy EUR/USD** in the editor. Change the quantity in the *field grid*,
   pause to show the *raw preview* update; then edit the raw side, show the grid follow.
2. **Send.** Watch the grid: outgoing `35=D`, then three incoming ExecutionReports —
   ack (`39=0`), partial (`39=1`), fill (`39=2`) — arriving with visible gaps.
3. Click the partial fill. Walk the parsed field grid (names + decoded enums), then scroll to the
   **RAW MESSAGE** section — same message, actual bytes.
4. Open template **FX Quote Request USD/JPY**, send it. In the incoming `35=S`, show the price at
   three decimals. Then click the *outgoing* `35=R` and show `NoRelatedSym(146)` — the Symbol
   sitting **indented inside its repeating group** in the grid.
5. Paste demo: copy a prepared ExecutionReport line — one carrying a `NoPartyIDs(453)` group and a
   log-file prefix — and use the **paste** button in the detail panel to visualize it.

**NARRATION** (~470 words):

> Let's send something. This is a message template — a limit buy, one million EUR/USD. The editor
> is a two-way binding: the field grid and the raw FIX string are the same message, and editing
> either side updates the other. I'll change the quantity here in the grid — and the raw preview
> follows. I could just as well type in the raw string and watch the grid follow.
>
> Send. And watch the session grid: there's my order going out, and here comes the venue —
> an acknowledgement, a partial fill, and the completing fill, each about half a second apart with
> that simulated latency on top. Notice I didn't wait for a screen refresh — messages land in the
> grid as they land on the socket.
>
> Now, reading. Click any message and the detail panel parses it against the loaded FIX dictionary.
> This is the partial fill: every tag gets its name — ExecType, OrdStatus, CumQty — and enumerated
> values are decoded, so 39 equals 1 reads as *partially filled*, not as a bare digit. Five hundred
> thousand done, five hundred thousand leaves, at my limit price.
>
> And this section is the same message as bytes — the raw wire view. This is not a re-rendering;
> it's what actually crossed the socket, delimiters and all. Everything the parsed view claims, you
> can check here. That matters, because tools that re-serialise messages can quietly reorder them —
> FixTool shows you the venue's bytes in the venue's order.
>
> Two more things about reading messages. First, repeating groups. Here's a quote request for
> dollar-yen — and notice the venue's quote comes back at three decimals, because yen pairs price
> to three, not five. The request itself is the interesting one: a conformant QuoteRequest carries
> its symbol inside a repeating group — NoRelatedSym — and the grid shows that structure, with the
> group's members indented under it, in the order they appear on the wire. When a message carries
> two party blocks with the same tags, that indentation is the difference between reading it right
> and guessing.
>
> Second — and for QA this might be the most-used feature in the tool — you don't need a live
> session to read a message. This is a line straight out of a venue's message log, prefix,
> timestamp and all. Copy, and paste into the detail panel. FixTool skips the log prefix — and
> tells you it did — parses the bytes, and there's the full structure, party groups included. Any
> message from any log, readable in two clicks.

---

## Chapter 4 — The venue side (5:15–7:30)

**SCREEN:** the FX Demo Venue's profile → Auto-Responses.

**ACTIONS:**
1. Scroll the rule cards. Stop on the limit-order rule that just fired: show its trigger
   (`35=D`, Symbol one-of the three pairs, `40=2`) and its three timed reply steps.
2. Show a quote card: the per-pair price expressions, and the unknown-symbol refusal card below.
3. Back in Demo Client 1's editor: change the limit-buy template's Symbol to `NZD/USD`, send —
   incoming reject, `39=8`, `103=1`. Show 103 decoded: *unknown symbol*.
4. Send **FX Order Cancel Request** for the already-filled order — incoming `35=9` with `102=0`.
5. Send **FX Order Status Request** — status answered with the real filled quantities.
6. Right-click / open a received order and show the **Reply With…** menu, without sending.

**NARRATION** (~330 words):

> So who answered me? Here is the venue's whole personality: a list of rules, first match wins.
> This card is the one that filled my limit order. The trigger reads: message type D, symbol is one
> of the three pairs this venue prices, order type limit. The reply is three steps — acknowledge
> now, partial fill a quarter-second later, complete the fill after that — and each step is a plain
> FIX template that echoes fields from my order and computes the rest. Nothing behind this card
> decides anything; the card is the behaviour.
>
> The quotes work the same way — here's the euro-dollar pricing card, with the bid drawn once and
> the ask built from that same draw, so the spread can never invert. And below the priced pairs sits
> the refusal: any symbol the venue doesn't know falls through to this card.
>
> Let's prove that. Same limit order, but I'll change the symbol to kiwi-dollar — which this venue
> doesn't price. Send. Rejected: OrdStatus 8, and OrdRejReason 103 equals 1 — *unknown symbol*. The
> rule list decided that, and you can read exactly where.
>
> The venue also keeps state. My euro order filled completely a minute ago — so let's try to cancel
> it. The venue answers with an OrderCancelReject, and look at tag 102: reason zero, *too late to
> cancel*. Not "unknown order" — the venue knows this order; it knows it's done. It has an order
> book, the rules can ask it questions, and a status request comes back with the order's real
> quantities, answered from that book.
>
> One more thing while we're here: rules answer automatically, but you can also answer by hand.
> Any received message offers Reply With — the same reply shapes the rules use, rendered for this
> specific message, ready to edit before sending. Useful when you're exploring a client's behaviour
> one message at a time.

---

## Chapter 5 — Scenarios (7:30–10:45)

**SCREEN:** the Scenarios rail.

**ACTIONS:**
1. Open the rail; point at **Capture from sessions…** (do not click). Open the bundled
   **EUR/USD order lifecycle** scenario; scroll its steps and expectation rows slowly during the
   concept narration.
2. **Run.** Green. Open the run report briefly.
3. Break the venue: edit the limit-flow rule's fill step — change the fill price constant. Save.
4. **Run** again. Red. Open the report → **Reconcile assertions →**. Show the failing row: the
   price expectation against what the venue actually sent, in wire order.
5. Revert the rule edit. Run. Green again.

**NARRATION** (~470 words):

> Everything so far was manual. This is the part that makes it repeatable.
>
> A scenario is a conversation written down as a test. Your outgoing messages are recorded and
> replayed exactly as you sent them. Every incoming message becomes a set of expectations — this
> field, this value, row by row — asserted against what the counterparty sends next time. You can
> capture one from any live session — that's this menu item, it reads the conversation you just had
> and seeds the whole thing — or you can start from one that ships with the demo, which is what
> I'll do so we can read it together.
>
> This is the euro-dollar lifecycle we ran by hand in chapter three. Step one sends the limit buy —
> the same raw FIX, replayed. Then three expected reports: the acknowledgement, with leaves-quantity
> one million; the partial, with cum-quantity five hundred thousand at exactly my limit price; the
> fill, with leaves zero. Then it sends a cancel for the completed order — and expects the reject,
> with tag 102 equal to zero, *too late*. That last row is the kind of assertion that catches real
> regressions: not "did I get a reject", but "did I get the *right reason*".
>
> Values that must differ every run — order IDs, timestamps — are captured as variables, not
> hardcoded, so the scenario doesn't fail on its own uniqueness.
>
> Run. Each row goes green as the venue's replies come in and match... and passed — the whole
> conversation, re-had and verified, in a couple of seconds.
>
> Now let's make it fail honestly. I'm going to change the venue — a one-character edit to the fill
> rule's price, the kind of regression a venue release actually ships. Run again.
>
> Red. And this is the view QA lives in: the run report says which step, and Reconcile shows the
> evidence — my expectations on the left, and on the right the reply the venue actually sent, whole,
> in wire order. The price row is flagged: expected one-oh-eight-nine-five, received this. One field,
> named, with the message around it intact. Not "scenario failed" — *this tag, this value, this
> message*.
>
> I'll put the venue back... run once more... and we're green. That loop — capture, run, break,
> reconcile, fix — is the core of the tool. And scenarios scale from here: run sets, favourites,
> repeat runs for flake-hunting, an examples table to run one scenario across many symbols. That's
> its own video.

---

## Chapter 6 — MCP: agent-driven testing (10:45–14:00)

**SCREEN:** Settings → Automation control; then a terminal beside the app window.

**ACTIONS:**
1. Show the Automation control settings page: enable, port 8765. Ten honest seconds.
2. In the terminal: `claude mcp add --transport http fixtool http://127.0.0.1:8765/mcp` — then start
   Claude Code.
3. Paste the prompt (exact text, also in Appendix A):
   *"Using the fixtool tools: on Demo Client 2, send a GBP/USD limit buy, 250,000 at 1.27050. Wait
   for the fills and verify the order filled completely at my limit price. Then capture the exchange
   as a scenario named gbp-fill and run it to confirm it passes."*
4. Split attention deliberately: as the agent works, keep cutting to the app — the `35=D` appearing
   in Demo Client 2's grid, the fills, then the *gbp-fill* scenario appearing in the rail, then its
   green run.
5. Show one of the agent's tool calls reading fields (`fixtool_get_messages`) in the terminal.

**NARRATION** (~430 words):

> Everything I've done with the mouse, an agent can do over MCP — and this has become the way a lot
> of our users drive the tool day to day.
>
> The setup is honest enough to show in full. FixTool embeds an MCP server; it's off by default,
> local-only, and switched on here in settings. One command registers it with Claude Code, and the
> agent sees the tool set the app itself serves — about forty tools: connect sessions, send raw FIX,
> read parsed messages back field by field, manage the venue's rules, capture and run scenarios.
>
> So instead of clicking, I'll describe a test case. *(read the prompt as you paste it)* — send a
> cable limit buy on the second client, verify it fills at my price, then save the whole exchange as
> a scenario and prove it passes.
>
> Watch the app, not the terminal. There's the order landing in Demo Client 2's grid — the agent
> sent real FIX through the same session I was using. There are the venue's fills coming back.
>
> And here's the part I want to slow down on: the agent is not looking at screenshots. It's calling
> get-messages and reading the same parsed fields you saw in chapter three — it checks tag 39, tag
> 14, tag 31 against my limit price, on the bytes. When it asserts the order filled at one-two-seven-
> oh-five, that's a field-level check, the same discipline a scenario row applies.
>
> Now it captures — and there it is in the rail: *gbp-fill*, a scenario seeded from the conversation
> the agent just had, sends and expectations both. And it runs it... green. The agent wrote a
> regression test by having the conversation once.
>
> Think about what that means for a QA workflow: the test case starts as a sentence. "Send an order
> that breaches the size limit and verify the reject reason." "Request a quote for every pair and
> check the spreads." The agent drives the real sessions, verifies on real fields, and what you keep
> is not a chat transcript — it's a scenario, sitting in the rail next to the hand-made ones,
> re-runnable forever without the agent.
>
> Both sides are reachable this way, by the way — the venue's rules can be authored over the same
> tools, so "build me a venue that rejects every third cancel" is also a sentence.

---

## Chapter 7 — CI, and where this leaves us (14:00–15:00)

**SCREEN:** terminal only.

**ACTIONS:**
1. Run: `fixtool run "EUR/USD order lifecycle" --junit reports/lifecycle.xml`
2. Show the pass output, then `echo $?` → `0`.
3. Open `reports/lifecycle.xml` for two seconds — recognisable JUnit XML.
4. Cut back to the app for the closing frame from Chapter 1.

**NARRATION** (~180 words):

> Last thing. A scenario that only runs inside a window is not a test yet — so FixTool has a
> headless runner. Same binary, no window: fixtool run, the scenario's name, and a JUnit flag.
>
> It connects, replays, asserts every row — and exits. Exit code zero on green, one on red, which
> means a CI step can run it with no wrapper at all. And the JUnit XML is the same report format
> your build server already renders, so a failing field shows up in the pipeline the way a failing
> unit test does. The scenarios your team captures — by hand, or by agent — become gates.
>
> That's the tour: send and read FIX in every view down to the bytes; run both sides of the
> connection, with a venue you can read and edit; turn conversations into field-level scenarios;
> drive all of it with an agent over MCP; and run the result in CI. Chapter links are below, and the
> deep-dives — scenario authoring, venue rules, the examples table — are coming as their own videos.

---

## Appendix A — exact texts

**MCP prompt (Chapter 6, paste verbatim):**

```
Using the fixtool tools: on Demo Client 2, send a GBP/USD limit buy, 250,000 at 1.27050.
Wait for the fills and verify the order filled completely at my limit price. Then capture
the exchange as a scenario named gbp-fill and run it to confirm it passes.
```

**YouTube chapter markers:**

```
0:00 What FixTool is
0:45 Zero to connected — the demo workspace
1:50 Sending, receiving, and reading messages (grid, raw, groups, paste)
5:15 The venue side — rules, the order book, Reply With…
7:30 Scenarios — conversations as repeatable tests
10:45 Agent-driven testing over MCP
14:00 Headless runs and CI
```

**Paste-demo fixture (Chapter 3, step 5):** prepare a text file containing one log line — prefix +
timestamp + an ExecutionReport carrying a `453` party group with two entries — and have it open in
a spare editor tab before recording. Keep the same line for every take.

## Appendix B — staging and reset (for the driving agent)

- Launch for staging: `FIXTOOL_CONTROL_PORT=8765 ./gradlew :composeApp:run` (the same port the MCP
  chapter uses; enable Automation control in settings once, on camera, in Chapter 6).
- **Reset between takes:** workspace switcher (top left) **→ Close workspace**, then **Open FX Venue example**
  again — it copies to `fx-venue-2`, `fx-venue-3` and so on. A fresh copy is the reset, so nothing has to be un-edited: the venue, clients,
  templates and bundled scenarios come back in their shipped state and any captured `gbp-fill`
  scenario or rule edit is left behind in the old folder.
- Chapter 1's opening frame is recorded **last** — it's the end state of a full rehearsal run.
- Verify during rehearsal, before believing this script: the exact `fixtool` launcher path for
  Chapter 7 on the recording machine, and that the Chapter 6 agent names the scenario `gbp-fill`
  (the prompt asks it to; if it improvises a name, retake rather than editing around it).
- The presenter records narration separately over the staged picture where takes drift; Chapters 3
  and 6 have the most timing risk (venue latency, agent pacing) — capture generous B-roll of both.
