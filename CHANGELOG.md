# Changelog

All notable changes to FixTool will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 🔧 Changed

- **The demo moved out of the profile form.** Start Demo Server sat at the bottom of the Connection panel, under sections that all describe one profile, and once the venue was selected its 21 auto-expanded rule cards stood between the top of the form and Stop. The demo is a workspace action, not a profile setting, so it now lives where its audience is: a **Start demo workspace** button in the empty session area of a fresh install, and a trailing **Start demo workspace…** / **Stop demo workspace** item in the toolbar's **Quick Connect ▾** menu, after the profiles, the way *Edit Configurations…* trails a run-configuration list. The menu route picks the FIX version; the button takes the default. Quick Connect now shows with no saved profiles, which is the one moment the item matters. The section's own status dot is gone: the venue is an ordinary profile in that same menu with a state dot of its own. `POST /demo` and the `fixtool_demo` MCP tool are unchanged.
- **Every file dialog is the operating system's own.** Browse on the five configured paths (both dictionaries, the profiles and saved-messages files, the scenarios folder) and Save on a grid selection all opened a `JFileChooser`: Swing drawing its own idea of a file browser, with no sidebar of the places a user keeps things, no Recents, no iCloud, and no Cmd-Shift-G to type a path. They now open NSOpenPanel on macOS, IFileOpenDialog on Windows and the XDG desktop portal on Linux, with AWT and Swing behind them where no portal answers. A dialog opens where the field's own path points, and a save still gets `.fix` appended when the name typed over the suggestion carries no extension of its own.
- **One directory decides where FixTool keeps things, and `FIXTOOL_WORKSPACE` can move it.** Eleven places each spelled out `~/.fixtool/<thing>` for themselves, and `fixtool run --home` then rebuilt every one of those paths a second time by string concatenation, so "where does FixTool keep things" had eleven answers that only happened to agree. They now all derive from a single root. The default is `~/.fixtool` exactly as before, so nothing moves on upgrade, and setting `FIXTOOL_WORKSPACE` points a whole process somewhere else: what a build box needs when the profiles and scenarios under test are the ones checked in beside the code, without passing `--home` to every invocation. A leading `~` in that variable is expanded, because a CI config file is not a shell. `fixtool run --home` still wins over it, and now puts the previous workspace back when it finishes.
- **Workspaces: New, Open, Recent, Close — and the demo is gone.** Start Demo Server built three profiles, eleven templates and two scenarios in Kotlin, installed them into **your own** profiles list and scenarios directory, and Stop hunted them back down by id prefix. There is no demo now: a **workspace** is a folder holding its own profiles, saved messages, scenarios and session store, and the FX venue is a bundled example that **Open** can open like any other. Opening it copies it to `~/.fixtool/workspaces/fx-venue` and opens that, because the bundle lives inside the application and cannot be edited in place. **New workspace…** asks for a name and a location and makes an empty one. Nothing is mixed into your files, so there is nothing to uninstall: **Close workspace** puts your own profiles and scenarios back and leaves the copy on disk, because it is yours.
- **Dictionary, window layout and rail order do not move with the workspace.** They are properties of the person at the keyboard rather than of the project, so opening a second workspace does not hand you a fresh install with no dictionary loaded and every panel back where it started. Profiles, saved messages, scenarios, run records and the QuickFIX/J session store are the workspace's; everything else stays with the installation.
- **Nothing moves on upgrade, and your existing setup is now called Default.** `~/.fixtool` is a workspace like any other and it is where everyone starts, so an install from before workspaces existed needs no migration — it simply gained a name. Default cannot be closed and never appears in Recent, because closing any other workspace is what comes back to it.
- **The New workspace dialog does not ask for a FIX version, because it never got one.** The field was there and it was theatre: a loaded data dictionary overrides a profile's `beginString` at connect time (`FixConnectionManager.determineBeginString`) and one is essentially always loaded, so picking 4.2 produced a 4.4 session. In its place the dialog states what the sessions will speak and where that is decided: Settings → Protocol.
- `POST /demo` and `fixtool_demo` keep working: `start` opens the FX Venue example, `stop` closes the workspace. New `GET`/`POST /workspace` and `fixtool_workspace` report the open workspace, the recent ones, the environments and the bundled examples, and open a folder or an example by id.
- **Logon passwords left the file a workspace is shared as.** `connection_profiles.json` is the interesting half of a workspace — CompIDs, hosts, acceptor rules, the things worth committing beside the code they test — and it also held the password in plain text, which made the whole file unshareable: you could not put a workspace in a repository, hand one to a colleague or attach one to a ticket without handing over a credential too. Passwords now live in a sibling `secrets.json` and an existing file is migrated the first time it is read, losing nothing. This is a separation, not encryption, and does not pretend otherwise: the two files sit in the same directory with the same permissions. What it buys is that the file you would copy is not the file with the password in it. A workspace opened from an example arrives with a `.gitignore` already covering `secrets.json`, the sequence store, the session log and the run records.
- **Settings → Storage is one Workspace folder row.** The connection-profiles file, the saved-messages file and the scenarios directory were three separate path settings, which is three chances to point a colleague at two of them. The page now names the open workspace and offers Open and Close, and documents `FIXTOOL_WORKSPACE`. An existing override still wins and is listed under **Path overrides in effect** with a Clear button, so nobody's configuration changed under them; there is just no longer a way to make a new one by accident. The QuickFIX/J sequence store and message log follow the workspace too, so a copied workspace brings its own sequence numbers rather than sharing the installation's.
- **Environments: where a counterparty is, as distinct from who it is.** A profile answers both questions at once, which is how a desk ends up with `UAT1-BuySide`, `QA1-BuySide` and `DEV1-BuySide` — three profiles carrying one counterparty's CompIDs and differing only in a host, a port and a TLS flag, so every rule change has to be made three times and the day one is missed is the day a test passes against the wrong environment. A connection is now a counterparty **times** an environment: pick a profile in Quick Connect and it asks which environment to reach it in, without rewriting the profile. **The session qualifier is the environment's name**, which is load-bearing — QuickFIX/J keys its sequence store on the qualifier, so two environments reached by one counterparty would otherwise share a store. That is the exact bug in this repo's own saved profiles, where the UAT1 pair carried the qualifier `QA1`.
- **Nothing about environments is on until you ask.** A workspace with no `environments.json` behaves exactly as before: click a profile, it connects. **Settings → Storage** offers **Extract environments** only when the saved profiles already look like a grid, shows what it would produce by name first, and extracting adds the environments and changes nothing else — every profile keeps working as itself, and *As saved* stays in the menu for the endpoint a profile already names.

## [1.14.0] - 2026-09-03

The release where the latency figure stopped including the tool that reports it: round trips are
stamped at the socket now, they work through TLS, and a session probe measures the floor beneath
every order.

### ✨ Added

#### A session probe, as a template and a scenario
- **Session Probe (TestRequest)** joins the demo templates and **Session round-trip probe** joins the demo scenarios. One TestRequest out, the Heartbeat that echoes its TestReqID back: the venue's session engine answers before any rule, order book or simulated latency, so the round trip is the network and the engine alone. Run the scenario as a set of a hundred for a p50/p95/max of the session layer, or point the client profile at a real venue and probe that. The predicate names the id, so QuickFIX/J's own timer Heartbeats are not mistaken for the answer.

### 🐛 Fixed
- **An entry that auto-connected its own session could write an empty record.** The recorder learned of a session preflight brought up on a 100ms poll, and whatever landed on it before that poll was filed as history and left out of the record. The watermark is now taken once, synchronously, before the run moves, and a session first seen while the run is under way contributes everything it shows. (`RunSetRailTest`, which failed under load for exactly this reason, is deterministic again.)

### 🔧 Changed

#### Latency is stamped at the socket, and works through TLS
- **Round trips are now measured where the bytes touch the socket.** A filter in QuickFIX/J's network layer stamps a send the instant the kernel has accepted the last byte (after serialisation, the write queue and TLS encryption) and a reply the instant it is framed out of the decrypted stream (before QuickFIX/J's queue, parse and validation). The tool's own FIX engine is on neither side of the number. Previously both stamps were taken in the engine's callbacks, so on a fast venue the engine was most of the figure.
- **It works on a TLS session and needs no privileges**, which is what a venue on port 443 has always required. The Latency Panel's badge reads *Socket* and its tooltip states exactly what the stamp includes.
- **The libpcap packet-capture path is removed**, together with the pcap4j and JNA dependencies (about 3.3 MB of the distribution), the *Network interface* setting, the fallback notice asking for `sudo`, and the stunnel workaround in the help. It could not read an encrypted stream, so it had never run against a real venue; on review it also classified every loopback packet as a send, could double-count a round trip against the engine stamps, and keyed the grid's latency column by a form the grid never asked with. An existing `captureNetworkInterface` in `app_settings.json` is ignored.
- **Any correlation tag now pairs.** The Latency page has always offered every field in the dictionary, but the tracker silently ignored anything outside its six built-ins, so a venue's own quote reference added to the list never produced a number. Now any tag in the list pairs and is named in the panel from the dictionary.
- **TestReqID (112) is a built-in and in the default list.** A TestRequest is paired with the Heartbeat that echoes it: FIX's own ping, answered by the venue's session engine with nothing placed, so a safe probe of the network and engine alone and the floor under every order round trip. A saved list from an earlier version keeps its contents; add 112 to it by hand.
- Latency settings, panel, grid column and thresholds are otherwise unchanged. The scenario runner's per-step latency and run records still use the engine's capture clock.

## [1.13.0] - 2026-09-03

The release where the acceptor grew a memory, one scenario became a suite, and an exchange
became something you can follow across every session at once.

_Never published as its own download — this version was not tagged, so no installers were built
for it. Everything below shipped in 1.14.0, whose installers are built from a tree that contains
all of it._

### ✨ Added

#### The acceptor remembers what it is holding (#35)

- **The venue keeps an order book.** A rules engine with no memory answers the same message the same way forever — so a cancel for an order nobody placed came back *"canceled"*, and a duplicate ClOrdID was accepted as a new order. The acceptor now keeps one book per counterparty, on two rules: **the book records, the rules decide** (nothing changes what your venue answers unless a rule you can read asks it to), and **an order is its own trail, folded** — every number on a row is computed on read from the messages that touched it, never accumulated as they arrive.
- **The Order Book panel, opening to its evidence.** A row is a claim; expanding it shows the messages that made it, each with what the order looked like *after* it — so `CumQty 2500` is two fills you can count rather than a number you are asked to trust. Clicking a trail line selects that message in the grid. The footer states the three ways the book may be *wrong*: unattributed reports (with the reason, not just a count), orders evicted to stay inside the cap, and whether the book was **cleared** rather than never filled — an empty book otherwise reads identically as "nothing happened" and "somebody pressed clear", which send a tester in opposite directions.
- **Rules can read the book.** A trigger carries one optional `whenOrder` constraint — `unknown`, `pending`, `working`, `done` — ANDed with its tag conditions, so the venue answers one message two ways: a cancel for an unknown order is rejected `102=1`, a cancel for a working order is accepted, and a cancel arriving too late is rejected `102=0`. The constraint reads the state held **before** this message, so `unknown` means "a new order" and anything else means "we already hold this id" — which is the whole of what a duplicate is.
- **Replies can read the book**: `${order.orderId}`, `${order.cumQty}`, `${order.leavesQty}` and the rest, standing alone or inside arithmetic. They resolve **per step, as that step is sent**, so an accumulating fill sequence reports 500, then 750, then 1000 — read once at trigger time, all three say 500 and a client tracking CumQty watches each fill undo the last. A reference that cannot resolve is **never sent as an empty field**, refused at three levels.
- **Every reply now carries the reason that chose it**, shown beside the bytes: *"sent by rule 3 — 35=F matched, and the book said ORD-9 was unknown at 09:14:22.418"*. The reason is written when the decision is made rather than re-derived afterwards, because a cancel rejected at :22 for an unknown order would re-derive as *accepted* at :25 — a tool that reconstructs after the fact states the wrong reason confidently.
- **`ClearOrderBook` is a scenario step**, and the run boundary nothing could previously reach. Refused in preflight if aimed anywhere but a session FixTool hosts as a venue, because a setup step that quietly does nothing lets the run go green on state it never reset. Capture authors one for every pane that owns a book.
- **The order book cap is a setting** (Settings → Sessions, directly beneath the message buffer) and **applies to books already open** — the moment you discover the cap is too small is the middle of the soak run that proved it, and a setting you could only apply by reconnecting would cost the very state it was raised to preserve.
- **`GET /acceptor/orders`** and `fixtool_acceptor_orders`: the roll-up, one counterparty's whole book, a single ClOrdID with its trail, and `clear:true` to start from a known state.

#### One venue, many clients (#32)

- **`TargetCompID = *` opts an acceptor into being a venue.** It binds the port once, accepts a logon from any counterparty addressed to its SenderCompID, and gives each client its own session and its own pane. Previously an acceptor was structurally single-client, and a second client hit QuickFIX/J's *"Unknown session ID during logon"* — logged and dropped, with no Logout and no Reject, so the client hung until its own timeout and FixTool showed nothing. The only workaround was a port per client, which is not a venue's topology and forces the client under test away from its real settings.
- **Refused logons are reported** — a notification, the venue pane, and a counter on `/sessions`. Nothing else records them, and without them a wrong CompID, a wrong port and a firewall are indistinguishable from both ends.
- **A client's pane outlives its session**, marked disconnected, and a reconnect from the same CompID reuses it — what explains a drop is the traffic just before it. One client's logout never stops the port. The acceptor's own tab becomes a **venue overview**: port, client list, refused logons, rule status, and a per-client order roll-up.

#### Authoring an acceptor, instead of writing FIX by hand

- **Eight rule presets and a starter-venue bundle**, offered from the Auto-Responses header and from `POST /acceptor/rules {"preset": …}`. A new acceptor used to say *"No rules — incoming messages get no reply"*, and the way forward was knowing from memory that an ExecutionReport owes 37, 17, 150, 39, 151, 14 and 6. Presets are ordinary rules the moment they land — editable, reorderable, deletable — and **placement is part of the insert**, because a preset that lands where it cannot be reached has solved nothing. The starter bundle answers a cancel in **all four order states**, since leaving one out means a cancel that matches nothing and a venue that says nothing at all.
- **A reply step opens in the message editor**, with field names, enum values and the dictionary's own naming, instead of being edited as one monospace raw-FIX field. Applying verifies the step is still where it was before writing, and **applying is not saving** — the step is staged exactly as typing into its raw field would have been.
- **Reply With…** answers what you did not anticipate: the venue's own shapes — acknowledge, fill, partially fill, reject, cancel — filled in from the incoming message and from the book. A shape that cannot work is shown **greyed with its reason** rather than hidden, and a hand-sent reply moves the book and carries a recorded reason exactly as a rule's does.
- **An unreachable rule is named on its card** — earlier, enabled, same MsgType, no conditions — and reported as `shadowedBy`. Claimed only where it is provable, because a warning that guesses is one authors learn to scroll past.
- **Acceptor rules are authorable over MCP.** `POST /profiles` now **merges** per top-level key instead of replacing the whole config (adding one rule used to silently take the keystore path, the logon fields and every other rule with it, then answer `{"status":"updated"}`); `POST`/`DELETE /acceptor/rules` edit **one rule by index**; and `POST /acceptor/test` dry-runs a message against the rules, reporting per rule whether it matched, each condition's verdict with the value it actually read, why it was skipped, what shadowed it, and the rendered reply with offsets.
- **`GET /sessions` reports what a running acceptor is mid-way through** — `acceptPort`, `rulesLive`, `latencyActive`, `triggersMatched`, `responsesSent`, `pendingResponses` — so a test can tell *"wait longer"* from *"your rule is wrong"*, which from the message log look identical.

#### Multi-run scenarios — run sets, records, examples tables and fan-out (#41)

- **A run set is an ordered list of run requests**, and a single run is a set of one — which is why nothing downstream had to learn a second shape of report. Four readings, one machinery: **repeat** one scenario ×N (is it flaky?), **suite** N scenarios (does it still pass?), **examples** one scenario per row (does it work for every case?), and **fan-out** one flow over many clients (does it hold under load?).
- **Run ▾ in the rail** — a saved set by name, ★ favourites, the current filter, Repeat ×N, Run examples table…, Run selected…, and Save as set… An item that cannot be used **stays visible and disabled with its count showing**, and a greyed-out Run now names what is holding it: *"6 of 23 wait on Venue (reconcile demo) — held by 'DEMO A — shape churn ×20'"*.
- **The record on disk is the artifact; the tab is a viewer over it.** Entry 2's setup clears the grid, and a session is a ring buffer anyway, so eleven of twelve reports would point at messages that are not there. Each entry writes `~/.fixtool/runs/<set>/NN-<scenario>.json` — the report verbatim, the bytes, the scenario **as it ran**, and the step-to-message pairing reconcile needs — **as it lands**, not at the end, because the run this exists for is the one that gets killed at entry nine.
- **The run set document** reads one back: entries down the left, and for the focused one its verdict, its steps with their latencies, its variables, and **its own message grid** re-parsed from the record's bytes and tinted by what that entry decided. Any entry can be **re-run as it ran**. Clicking an entry is what publishes it — entries run publishing nothing, because a set of twenty would re-aim your open reconcile window twenty times while you were reading it.
- **Reconcile now starts from an entry that ran an hour ago**, because the record carries the scenario as it ran. Without it you could edit an assertion, click Reconcile, and be shown the old bytes against the new expectation — with *Accept actual* writing a repair for a run that never happened.
- **The examples table** — a scenario was already a Scenario Outline and nobody had noticed. `Scenario.examples` is `{columns, rows}`, seeded into the scope **before setup runs**; a cell is resolved as it is seeded, so a row may say `${uuid}` and give each of its runs a fresh id. The editor's table sits across the foot, and **"Extract to an examples column"** is how the first one gets written: the ▦ beside a captured literal turns `55=EURUSD` into `${symbol}` and gives **every existing row** the literal as its cell. On an outline, the reconcile gutter's plain *Accept actual* is a trap — the expectation belongs to every row — so the offer that tracks the column wins any tie and says why.
- **Fan-out** — one saved flow, fifty `LOADGEN{nn}` clients, at once. A lane is a session slot **numbered by the profile's own slot** rather than by list position, so lane 7 is the same client after a reconnect. Two disjointness rules are refusals rather than hopes: a step naming no session runs on *this lane's* session, and a second leg no profile can spread is refused by name with the three things that would fix it. The report is a **distribution** — p50/p95/max over the gap between the bytes that left and the bytes that answered — with the lane wall-clock reported separately and never offered as the venue's number.
- **The batch sweep, headless at last**: `fixtool run --all | --set <name> | --repeat N | --rows | --row <name> | --fan-out <profile>`, with `--pause`, `--stop-on-failure`, `--junit` (a `.xml` file gets one `<testsuites>`, a directory gets one report per entry) and `--json`. Same scheduler as the app, so a suite behaves identically whether a click or a build step started it. Sessions stay up across entries — bringing them down between scenarios would make a suite mostly logon traffic.
- **A set is a job over the control surface.** `POST /scenarios/run` is unchanged given one scenario; given `set`, `ids` or `repeat` it answers `202` with an id, then `GET /scenarios/runs`, `GET /scenarios/runs/<id>?wait=`, `GET /scenarios/runs/<id>/entries/N` and `POST /scenarios/runs/<id>/stop`. State is read **from disk**, so the answer survives a restart of the app. MCP: `fixtool_run_set`, `fixtool_run_status`, `fixtool_run_entry`.
- **Saved run sets** live in `~/.fixtool/sets/<name>.json` — what CI selects, because a build box names things in a checkout. An entry naming a scenario nothing answers to is reported beside the plan and the rest still run: a nightly suite should not stop existing the day somebody renames a file.

#### Trace across sessions — follow one exchange through every pane
- **Follow across sessions.** One action on a conversation header, a message row's right-click menu, or a correlation-id field in the detail pane follows that exchange through every session at once. Every pane narrows to the trace, a toolbar chip names what is followed (`Following RFQ-A1 · 4 sessions · 14 messages`), and the set grows live as the venue mints new ids. `Esc` or the chip's ✕ stops following and every pane's own filter comes back untouched. It replaces the regex QAs typed into the search box to do the same by hand, which missed hops silently and matched `ORD-9` inside `ORD-91`.
- **The Trace panel (Ledger).** The grouped grid, run over every session at once: one grid in time order with a Session column, per-hop **Elapsed** times on one clock, and group headers that count sessions as well as messages, so *which RFQs touched more than one session* is read off the headers. Nothing is hidden: Ungrouped is last and counted, and a trace whose first message has left a session's buffer says so. A single-session trace carries a hint pointing at the dictionary's `.roles.json` sidecar, where a venue's own echo tag can be declared to join it. Toolbar toggle beside Group.
- **Lanes.** The Trace panel's second rendering: one column per session, time running down, each message a chip in its lane pointing out of FixTool or into it, initiator sessions left of a dashed rule and acceptors right of it (read from the profile, never guessed from a CompID). The venue under test is the space between the lanes. When FixTool holds both ends of a hop, the same bytes leaving one lane and arriving in another draw as one arrow carrying the hop time. `POST /panel {"panel":"trace","render":"lanes"}` switches it.
- **Control surface**: `GET /traces`, `GET /trace?id=` (the merged exchange with a session and elapsed time per message; a substring never matches) and `POST /panel {"panel":"trace","follow":…,"render":…}`; MCP tools `fixtool_traces`, `fixtool_trace`.

#### The demo is the acceptor — an FX venue you can read
- **Pressing Start Demo Server now installs a workspace, not a black box.** The demo was ~1,540 lines of hard-coded QuickFIX/J that priced six pairs behind a button — nobody could open it, read why it replied, reorder a rule or make it misbehave on purpose. It is replaced by an **FX Demo Venue** acceptor profile carrying a new **FX venue** preset bundle (21 rules), two **Demo Client** profiles, ten FX templates and one bundled scenario. Everything the demo does is now a shipped feature you can inspect and change.
- **Three priced pairs, quoted live.** EUR/USD, GBP/USD and USD/JPY (three decimals, the FX convention) are quoted from template expressions evaluated as each reply is sent, so two quotes are never identical. The bid is drawn once and the ask derived from it, so the spread never varies and never inverts. Unknown symbols are refused with a proper `35=AG`, and unpriced orders with `103=1`.
- **The venue answers from its order book**: cancels distinguish *too late to cancel* from *unknown order*, replaces keep the OrderID, status requests report real quantities, and a repeated ClOrdID is rejected as a duplicate. It ships with a 40–80ms simulated latency, so replies do not land suspiciously instantly.
- **One bundled scenario — "EUR/USD order lifecycle"** — runs green on a fresh install and is repeatable, because its setup clears the venue's order book. Break a venue rule and run it again to see the reconcile view name the field that moved.
- The venue takes `TargetCompID=*`, so **any** client CompID can join it, each getting its own pane on the venue side.

#### The Scenarios rail at suite scale
- **Multi-select that speaks the message grid's own vocabulary** — shift-click for a range, Ctrl/⌘-click to add or drop one, Ctrl/⌘+A, `Esc`, and a tri-state master tick that is the one standing control on an idle rail. Picking existed before and was unusable: the tick was hover-only, so nothing said selection was possible; there was no anchor, so no range and no select-all; and the pick was spent the moment it ran, so re-running the four you just fixed cost the whole gesture again. **Every gesture is scoped to the drawn order** — a range computed over the model would sweep in rows the author cannot see. **The pick now outlives the run it starts**, with a bar above the list carrying the count, Run, Save…, ★ and ✕.
- **The set report's entry rows say they open** — hover lift, hand cursor, a trailing chevron and a one-line hint — because the one route from a failed entry to its bytes was previously invisible. An entry with no record yet gets none of that.

### ⚡ Performance

A sixteen-finding audit, each fix landed with a benchmark that measures it rather than a claim about it. Three of the audit's own claims were corrected by measuring.

- **Reconcile: 781× faster** on a large snapshot — a big reconcile was slow, not a hang.
- **The message grid: 13.6× less allocation, 5.6× faster per rebuild.** A message parsed its own fields six times per grouped rebuild; it now parses once and caches on itself. Per pane per second that is 124 MB and 38 ms of CPU down to 9.1 MB and 6.9 ms.
- **Booking a message into the order book is now flat**, where it used to cost what the book already held: at the shipped cap, **176× less allocation and 167× faster**. An order's trail went from quadratic to linear (assembling 5,000 fills: 509 MB and 23 ms → 7.6 MB and 2.9 ms).
- **Latency tracking: 48× faster** — a round trip no longer costs the size of the history it joins.
- **Per-message logging was an ingest setting nobody meant to set.** The shipped config put the module at DEBUG with three synchronous appenders, on the thread that has to keep up with the wire: 9,747 ns and 3,440 B per message, against 35 ns and 0 B with it off. Nothing is lost — QuickFIX/J already writes a full per-session message log — so the lines move to DEBUG rather than being deleted. (`AsyncAppender` was tried first and measured *slower*.)
- Compile once, wrap lazily, stat once, collect once — four smaller findings, each benchmarked.

### 📖 Documentation

- **The user guide covers the last two releases of work.** New chapters and sections for the venue's order book, `whenOrder`, the `${order.…}` vocabulary, Reply With…, one-venue-many-clients, run sets and the record on disk, the examples table, the batch CLI, and a new chapter 14 on **tracing an exchange across sessions**. Settings gains the order book cap.
- **A stale instruction is corrected**: the acceptor troubleshooting table still told authors that a rule edit needs a reconnect, which stopped being true two releases ago. Each new chapter is now pinned by tests that assert the *facts* it must state, so the guide cannot drift silently again.

### 🔧 Changed
- **Acceptor replies are built through the loaded data dictionary.** They can now carry repeating groups (a conformant `QuoteRequestReject` needs one), and a rule template's header fields — `115` OnBehalfOfCompID, a venue dialect's own header tags — are placed in the header instead of the body, where they previously drew "tag specified out of required order" from the counterparty.
- **Acceptor rules can match on a field inside a repeating group.** A conformant FIX 4.4 `QuoteRequest` carries Symbol inside `NoRelatedSym`, where no trigger could previously see it. Flat fields still take precedence, so no existing rule changes what it matches.
- **Saving a rule changes what a live acceptor does.** Rules were compiled once at connect and nothing re-read them, so editing a rule under a logged-on acceptor wrote the file and changed nothing on the wire — indistinguishable from a new rule that does not work. Only rules and latency travel; CompIDs, ports and SSL are session identity. Writes report `appliedToLiveSessions`, so a caller can tell the file changing from the wire changing.
- **A bare run refused because the run slot is taken now answers `409`**, not `200`-with-an-error: a set holds the slot for its whole batch, so "already in progress" stops being a rare race and becomes an ordinary answer for minutes at a time.
- **The Node MCP server is a pure proxy.** It no longer declares its own copy of every tool — it had drifted, still describing a rule in the spelling from before sequences existed, because nothing compared the two files. It forwards `tools/list` and `tools/call` verbatim, so there is one definition of each tool.
- **A set with an unrun entry is `INCOMPLETE`, never `PASSED`**, and headless exits 1 on it.

### 🐛 Fixed
- **A strict scenario's second run counted the first run's traffic as its own surplus.** `trafficCheck` scanned each session's whole log, and a session log is not emptied between runs — so every second run went red, and a twenty-times repeat gave nineteen reds about nothing. The run's own watermark now excludes what predates it, and what was set aside is counted into the verdict's detail rather than dropped in silence.
- **A cancel was booked as a replacement of the order it canceled.** A venue's own accepted cancel opened a book entry for the cancel *request* and left the order reading `working` after the client had been told it was canceled — the one direction the book must not be wrong in.
- **The order book panel was drawing a book it had stopped reading**, freezing at whatever the numbers were when it first drew. A stale book is worse than no book, because it is wrong with a straight face.
- **The starter venue answered a cancel for an order nobody placed with "canceled"** — the defect #35 opens with, alive inside the feature built to remove it, because the default bundle still carried the unconditional cancel rule.
- **A row of buttons where only the last one worked.** Material3 gives every `IconButton` a 48dp minimum touch target whatever it is drawn at; the rules editor's rows are 16dp buttons at a 20dp pitch, so each button's target covered its left neighbour entirely and hit-testing went to the last sibling drawn. Move earlier, Move later and the enable checkbox drew their hover and did nothing.
- **The fan-out p50 was counting the sends it took to get there.** A Send carries a `latencyMs` — the ~1ms to hand the message to the session — and the statistic sampled every step that had one, dropping the median onto it. Three lanes that each took ~106ms reported `p50 1ms · p95 106ms`: the headline number of a load run, wrong by two orders of magnitude, in the direction that flatters the venue.
- **A fan-out set came back from disk as something else.** The writer wrote `"fanOut"` and the reader matched `"fanout"`, so a reopened set lost its identity and with it the entire latency report — while every `latencyMs` it is built from sat intact in the sibling record files.
- **`--json` on a batch wrote the wrong thing, or nothing at all**: it was silently conditional on `--junit`, and when it did run it wrote the *last* entry's report presented as the run's — twenty iterations reported as iteration twenty.
- **The Kotlin template engine is not thread-safe**, found by the lanes: three lanes resolving `${out.D.11}` against three different sessions all came back with the second lane's ClOrdID — every lane confidently right or wrong about a message it had never seen. Evaluation is serialised, and the cost is written down rather than hidden.
- **Nine run-set defects a code review caught before the announcement**: an entry whose host throws now fails that entry and finishes the set instead of leaving `set.json` stuck at `running` with the poll route waiting out every deadline; stop-on-first-failure no longer fires on a *skipped* entry; a set left running by a process that exited is healed on the first read nobody vouches for; the rail's stop button stopped every run in flight rather than its own; a column rename passing through a sibling's name merged the two columns' cells for good; and `--session a=b` was read and then dropped on the batch path.
- **Two runs started in the same second were the same run.**
- **The capture stamp was two clocks glued together**, and every latency was the seam.
- **The MCP shim aborted every call at a flat 15s**, so `fixtool_wait` asking for 25s failed at 15 with an error naming the wrong cause — *"could not reach FixTool"* — when the app was up and doing exactly what it was told.
- **Stopping the demo and starting it again brought back a demo with no venue in it.** The demo's clients auto-reconnect, so a venue taken down while they were still live was immediately dialled again and minted fresh panes; those outlived the Stop and were adopted by the next Start in the venue's place. The venue is now closed first, and Start sweeps anything a previous Stop could not reach.
- **Stopping the demo left two dead venue panes on screen.** A pane is no longer opened for a venue whose profile has already been removed.
- Demo templates were written to the default store rather than the configured one, ignoring a custom saved-messages path.
- A pipe inside a value was read as the end of the field when reading a record back.
- A reopened fan-out had no distribution to show; a wall clock is not a reply latency and no longer stands in for one; and a set now records *why* it ran, not just what it ran.
- "1 rows", "×1 lanes", "1 scenarios".


## [1.12.0] - 2026-07-24

### ✨ Added

#### Latency simulation for acceptor mode
- **The acceptor delays its auto-responses the way a network does.** FixTool replied to an incoming order as fast as the machine allowed — a latency a real venue never has, so a client whose timeout/retry logic is wrong passed here and failed against a real venue. Latency is now a per-profile property of the simulated venue, configured in a **Latency** section beside the auto-response rules: **Off**, **Fixed**, **Random range**, or **Normal distribution**, plus an independent **spike** probability that occasionally stalls a reply to a large draw (e.g. 5% of replies to 2000–5000ms).
- **One sample per triggering message, shifting the whole reply.** The delay is drawn once for each incoming message that fires a rule and shifts that rule's entire reply — ack, partial, fill — by the one number, so a sequence's authored inter-step order can never invert under an unlucky draw. The per-step delays remain the venue's own processing time, layered on top of the network hop. The applied value is written to the log per trigger, so a delayed reply is explained rather than mysterious.
- **Inert by default.** A profile with no latency configured behaves exactly as before, and nothing samples on the response path until a latency is set — so existing profiles are untouched.

#### The scenario editor is a bottom dock, and the workbench layout persists
- **The scenario editor is now an IntelliJ-style tool window docked full-width beneath the sessions**, the same idiom the terminal already uses, instead of rendering inside the session split where its position was a side effect of the view mode (and, in TABS, could not be resized at all). The dock has its own document tab strip, drag-to-resize, and minimize-to-header that survives composition; opening or focusing any document — a rail step click, a failure deep-link, a capture — restores a minimized dock and lands on that step.
- **Workbench layout persists across launches.** Panel sizes, which panels are open, and the dock heights are saved to a new `layout.json` (view state, kept out of app settings) and restored on launch; writes are debounced and drag sizes persisted on release.
- **A shared resize grab-handle replaces the 1px dividers everywhere** — which also fixes a rail divider that captured its ratio by value and barely moved per drag; the handle applies the delta against live state instead.

## [1.11.0] - 2026-07-23

### ✨ Added

#### The Scenarios rail, made usable at 20–30 scenarios
- **Sort.** A picker (⇅) in the rail header orders the list three ways: **Name** (A–Z, the default and prior behaviour), **Recently modified** (by the file's mtime, so the one you just touched floats up), or **Creation order** (oldest first — the order the suite was built). Ordering lives in a pure, unit-tested helper, so every sort is total and the list never reshuffles on an idle redraw.
- **★ Favourites.** A star on each row — filled and always shown once starred, an outline on hover for the rest. Starred scenarios lift into a **★ Favourites** section pinned above **All**; each section sorts independently and folds shut on its own header. With nothing starred the rail reads exactly as it did before: one flat list, no headers.
- **Collapse-all / expand-all.** One button (⊟/⊞) in the header folds every open scenario's steps shut at once, or opens them all.
- **A scenario carries a `createdAt`**, minted once at first save — the birth time the "Creation order" sort reads. Additive and default-omitting on disk: a file authored before the field existed grows no key and is never rewritten to add one (it sorts by mtime instead), so the change touches nothing already on disk.

### 🔧 Changed
- **The run report opens compact** — the verdict, the one first-failure line, and the route — with the "what else arrived" diagnosis behind an explicit **Show full error ▾**. Expanding reveals the whole error in a bounded, scrolling box, so seeing all of it can never reclaim the list's height.
- **The scenario that just ran is pinned** to a **CURRENT RUN** block at the top of the list until the report is dismissed, so a failure sorted down the alphabet is where the eye already is — its steps, their verdicts, and each step's reconcile route in reach.
- **Sort, favourites, and collapsed sections persist locally**, to `~/.fixtool/scenario_view.json` — never in a scenario file (a star never rewrites one) and never in app settings. A missing or corrupt file loads as defaults and never throws.

### 🐛 Fixed
- **A tall failure report could push the scenario list off the bottom of the rail, with no way to scroll to it.** The list was measured against the full pane height and rendered *below* the report, so a large error left its lower rows unreachable — routine on a list of 20–30. The list now owns the height the header and report leave and scrolls within it; the report is height-capped either way.

## [1.10.0] - 2026-07-20

### ✨ Added

#### The reconcile window is the pass, not the step
- **One diff window per scenario.** Reconciling is one act — repair a failing step, Save & re-run, meet the next failure — and the runner stops at the first failure, so a scenario diverging in five places surfaces one step per run. Keyed on the step, that loop opened a *new* window at every stop and left the finished ones on screen showing green rows: repair ten steps, close ten windows. The window is now the scenario's, and the step in view moves inside it.
- **A step strip** across the top of the diff: one chip per Expect step, coloured by where it stands (failing / repaired-not-saved / passing / not reached / not run), the current one filled, click any to jump. A pinned summary says what is left (`2 of 12 failing · 1 unsaved`), and the current chip is scrolled back into view whenever the pass moves it.
- **The pass has an ending.** A re-run that comes back green swaps the body for a completion state naming every repair the pass made — accumulated across all its Save & re-runs, not just the last one — with a single `Done`.

#### Bulk repair
- **New repair classes** in the fix plan: `oneOf`, `regex` and presence, alongside the numeric and temporal bands. A regex is inferred from the authored value and the actual by common prefix/suffix plus the narrowest class that full-matches both, and is refused if it would assert nothing.
- **A repair notices its siblings** — the same fix offered across every row of the step it can legitimately reach, folded into one edit and one undo.
- **The same fix travels across steps** — previewed per step against that step's own reply, staged into the draft (never past it), with a one-shot revert.
- **The author may overrule a class, downward**, in one click.

### 🔧 Changed
- `Cancel` in the diff is now **`Revert this step`**, and leaves the window open: with a whole pass in one window, closing on it would discard every other step's repairs.
- The close prompt counts what it would discard — *"Discard unsaved repairs to 3 steps and close?"*.
- The diff window title carries the step and the scenario's total (`rfq flow · Step 3 of 12 · reconcile`), and reads `green` once the pass has ended.
- **Control surface:** `?window=<step text>` no longer addresses a *step*. A scenario has exactly one reconcile window, so the step in its title is whichever it happens to be showing. Address the scenario, and drive the step through the reconcile API.

### 🐛 Fixed
- **A repair that travelled into a step could be silently reverted.** Cross-step repairs write sibling expectations straight into the draft, bypassing those steps' sessions; the next edit in such a step then wrote its stale expectation back, undoing work the author had watched happen, with no message. Sessions now adopt external writes — keeping their own undo stack and staged count, which a rebase would have flattened.
- A run re-bound only the first of a window's steps; every step after it silently kept the previous run's reply.
- Saving rebased only the visible step, so steps behind it went on counting edits already on disk.
- An armed reference slot now carries the step it was armed on: a grid click binds that step and returns the window to it, rather than binding somewhere the author cannot see.
- Deleting a step drops its reconcile state instead of leaving an orphan that kept writing into the draft; a scenario with no reconcilable step left closes its window rather than showing a dead end.
- A travelling repair no longer re-points a step's golden — that rule now depends on what the author actually reconciled against, not on which chips they happened to click.

## [1.9.0] - 2026-07-20

> Backfilled: 1.9.0 shipped without a changelog entry. Reconstructed from its commits.

### ✨ Added
- **Send fields: move and insert**, where position is load-bearing on the wire.
- **Exclude a send field without deleting it** — keep it in the scenario, off the message.
- **Variable badges split capture from mint**, and say which is which in words rather than by a `●`/`○` the reader has to decode.

### ⚡ Performance
- The scenario runner's polling moved off the EDT.
- Global search debounced and scanned off the UI thread.
- The template script engine warms at startup, off the UI thread.
- Per-row work is no longer rebuilt on every frame.

### 🐛 Fixed
- A `No*`-named field is only treated as a group count when its dictionary type agrees.
- Indentation is derived from the group overlay rather than guessed from shape, with one definition of depth shared by the tree walk and the wire overlay.
- Undefined counted groups survive the manual parse whole; a group count that builds no group is still kept on the message.
- The viewer reads group counts without writing to the message.
- Message rows are keyed by identity, not list position.
- Grid column widths fit every expanded message, not just the last one opened.

## [1.8.0] - 2026-07-17

### ✨ Added

#### Repeatable Scenarios
- **Scenario engine**: record a (multi-session) FIX flow and replay it as a repeatable test — a runner, a store, red/green run-results in the session window with per-tag drill-in, and a CI report.
- **Capture-driven authoring**: capture a live flow (or paste a FIX log) straight into a dedicated Scenarios workbench; every step is badged by source, and the review screen describes the scenario it is about to save.
- **Assertion vocabulary**: `fixtool_assert` with a matcher vocabulary (exact / regex / numeric & temporal bands / presence), auto-seeding, and assert-the-k-th-occurrence of a tag rather than a fragile group path.
- **Portable, git-friendly scenarios**: readable `slug--shortid` filenames, a configurable scenarios directory you can point at a git repo, and scenarios that never assert session identity so they move between environments.
- **Session mappings & save-as**: run a recorded flow against another environment's sessions, with preflight that auto-connects the sessions a run needs; "save as scenario" writes an environment copy rather than sharing mappings.
- **Variables & scope**: named capture variables per field (`${clOrdID}` not `${id0}`), shorthands `${uuid:N}`, `${utcnow}`, `${..+Nmin}`, `bindAs` to echo a venue's value back, and a visible run scope carried by `THIS_RUN` that outlives the run.
- **Traffic mode** (a strict run fails on unbound incoming messages) and **muted steps** (skipped by the runner, kept in the file).

#### Diff & Reconcile
- **The diff surface**: a failed step opens as an editable diff document in its own window — the one surface that repairs an assertion. Reconcile a failure where both sides are visible, move entries by hand with a rule that says why a move is withheld, and follow the crossing connector between paired rows.
- **Authoring on green**: delete any asserted row or add an absent assertion, plus a **fix plan** that bulk-widens numeric and temporal bands, previewed and staged as one edit.
- **Chained re-runs**: the pass carries forward — entry-scoped drop, clickable assertion rows, Save & re-run, and row-level deep links.
- **Plain diff viewer**: a scenario-less, read-only "Diff against…" / "Diff messages…" window with a one-way Seed door.

#### Embedded Terminal
- **Run Claude inside FixTool**: an embedded terminal docked as a resizable bottom pane drives the app over MCP; minimize it to reclaim space while keeping the session, and it survives a layout reflow. App-matching dark styling and a thin scrollbar.

#### Automation Control Surface
- New MCP / HTTP endpoints: `fixtool_get_scenario` (read a scenario's full definition), `fixtool_diff` / `POST /scenarios/diff` (open the diff door), relax/constrain assertions at the point of failure, and discoverable template & matcher syntax.
- **Acceptor auto-response rules** and preflight session auto-connect support self-contained round-trip tests.

### 🔧 Changed
- Scenarios moved to a dedicated workbench window; the from-scratch UI was removed in favour of capture-driven authoring.
- The reconcile/authoring surfaces were consolidated so one surface authors an assertion and the verdict is counted once, in one place.
- Captured timestamps are stamped in UTC; the demo server stamps `TransactTime`/`ValidUntilTime` in UTC.

### 🐛 Fixed
- Numerous correctness fixes across the diff/reconcile pairing rules (role-swap vs reorder, false greens, ghost interleaving), capture round-trips (pipe values, ambiguous CompIDs, pasted admin messages), STRICT-mode group handling, and the frame parser (a frame ends at `CheckSum(10)`; trailing bytes are refused, not parsed).

## [1.7.0] - 2026-06-25

### ✨ Added

#### Automation Control Surface & MCP Server
- **Agent-driven testing**: an opt-in, loopback-only (127.0.0.1) HTTP control surface lets Claude / an MCP client / curl drive FixTool for automated testing. Enable it from **Settings** (or set `FIXTOOL_CONTROL_PORT`), with an optional `X-Control-Token` for auth.
- **Embedded MCP server**: the app serves the Model Context Protocol over HTTP at `/mcp` — register it with `claude mcp add --transport http …`. A standalone Node MCP server (`tools/fixtool-mcp`) mirrors the same tools over stdio.
- **Full workflow exposed** as endpoints / MCP tools:
  - **Connections & profiles** — connect, disconnect, profiles CRUD
  - **Sending** — send, send-to-all (bulk / load testing), send-template (expressions resolved per session)
  - **Reading & inspection** — read parsed messages (`{tag, value}` fields), `wait` (block until a state or matching message), clear, select, **detail** (drive the detail-panel tag search), cross-session `search` timeline, grid `filter`, `screenshot`
  - **Config & control** — templates CRUD, dictionary read/switch, message `validate`, session/admin control (seqnum, reset-seqnum, test-request, resend-request, sequence-reset, logout, disconnect), and the built-in demo FIX server
- **Acceptor auto-response rules**: run FixTool as an acceptor that auto-replies to matching messages from a response template (e.g. `35=D` → `35=8` echoing the request's `ClOrdID` / `Symbol`), for self-contained round-trip tests.
- Documented in `docs/AUTOMATION.md`, `tools/fixtool-mcp/README.md`, and the in-app Help.

#### Context-Preserving Tag Search
- **Match-context modes** in the message detail panel: searching a nested tag (e.g. `PartyRole` inside a `NoPartyIDs` group) no longer collapses to bare matching rows that lose their context. A toggle, shown while searching, chooses how much surrounding context each match reveals:
  - **Bare** — matched rows only (previous behaviour)
  - **Identity** (default) — each matching repeating-group entry also shows its identity field (its first simple field, e.g. `PartyID`) so you can tell which entry matched
  - **Full** — the whole matching entry
- Only matching group instances are revealed, each under its group ancestor header so the match keeps its path, and the matched text is highlighted.
- Drivable via automation: **`POST /detail`** / the **`fixtool_detail_search`** MCP tool set the query and mode, so an agent can inspect a nested tag end to end (`select` → `detail_search` → `screenshot`).

### 🔧 Changed
- Flaky timing/order-sensitive integration and UI tests are now retried on CI (`org.gradle.test-retry`, CI-only) so transient failures don't fail the release build.
- Dependency and GitHub Actions upgrades; Linux installer naming fix in docs.

## [1.6.0] - 2026-06-12

### ✨ Added

#### Multi-Session Load Testing
- **Session count per profile**: Connect opens up to 100 concurrent sessions from one profile in a single click (initiators only)
- **Per-session identities**: SenderCompID, TargetCompID, Username, and Password accept `{n}`/`{nn}` numbering patterns (e.g. `LOADGEN{nn}` → LOADGEN01, LOADGEN02, …) or comma-separated lists for arbitrary server-assigned IDs; a single shared value falls back to auto-derived SessionQualifiers for servers that allow duplicate CompIDs
- **Group-aware connection panel**: aggregated status ("Logged On (3/3)"), live preview of resolved identities, group disconnect, and automatic top-up of closed sessions on reconnect

#### Bulk Send
- **Send to all sessions**: new message editor button sends the current message to every logged-on session, re-resolving template expressions per session so dynamic values (e.g. `${UUID.randomUUID()}` in MDReqID) are unique per session
- **Per-session template variables**: `${sessionIndex}`, `${sessionQualifier}`, `${sessionTitle}`, `${sessionSenderCompID}` available in message templates (also for single-session Send)

#### Release
- **macOS installer**: release builds now include the macOS DMG (Apple Silicon)

---

## [1.5.0] - 2026-03-18

### ✨ Added

#### FIX Acceptor Mode
- **FIX Acceptor mode** with connection type selection (Initiator/Acceptor) and accept port configuration

#### UI Improvements
- **Tag numbers in expanded groups**: Show tag number in GroupHeaderRow aligned with FieldRow tag column
- **Session tab sync control**: Setting to disable auto-sync of session tab to editor connection dropdown

### 🔧 Fixed

- **Save Message Template dialog**: Add scroll to Share with Users list
- **Message list checkboxes**: Fix checkboxes not selectable when messages reach the bottom of the session window

---

## [1.4.1] - 2026-02-07

### 🔧 Fixed

- **Saved message visibility**: Show untagged saved messages in message browser for all profiles, not just the profile that created them

---

## [1.4.0] - 2026-02-06

### ✨ Added

#### Connection Settings
- **Socket connection timeout** setting for configurable connection timeouts
- **Reconnect interval** setting for controlling delay between reconnection attempts
- **Auto-reconnect toggle** to enable/disable automatic reconnection per session

#### Improved Message Parsing
- **Re-parse incoming messages using wire bytes** for complete repeating group fields, ensuring all group entries are correctly captured

#### UI Improvements
- **Demo Server moved from toolbar to Connection Panel** for a cleaner toolbar and more logical grouping
- **Scroll-to-bottom button** in session header bar and Tab View for quick navigation to latest messages
- **Improved scrollbar visibility** with grey scrollbar color for better contrast

### 🔧 Fixed

- **Auto-reconnect behavior**: Stop reconnection after any disconnect when auto-reconnect is disabled
- **Send button activation**: Enable send button by activating session when auto-selecting profile on connect
- **Fix Logs grid layout**: Sync separator row dimensions with message summary rows for consistent alignment

---

## [1.3.1] - 2026-01-23

### 🔧 Fixed

- **FIX 5.0+ session headers**: Use FIXT.1.1 as BeginString and set default ApplVerID (FIX.5.0SP2) when transport dictionary is configured, ensuring proper FIX 5.0+ protocol compliance

---

## [1.3.0] - 2026-01-23

### ✨ Added

#### Transport Dictionary Support
- **Separate transport dictionary configuration** for FIX 5.0+ sessions
- New `defaultTransportDictionary` field in Settings for custom transport dictionaries
- Transport dictionary field visible in Settings when using custom dictionaries
- Supports custom setups where app dictionary has incorrect version headers

#### Demo Server FIX Version Selection
- **FIX version dropdown** replaces toggle button in toolbar
- Select any FIX version (4.0, 4.1, 4.2, 4.3, 4.4, 5.0, 5.0 SP1, 5.0 SP2)
- Demo profiles automatically configured for selected version
- Current running version displayed in toolbar

### 🔧 Fixed

- **Dictionary loading for FIX 5.0+**: Transport dictionary now used regardless of detected version, allowing proper FIX 5.0+ connections even with mislabeled dictionaries

---

## [1.2.2] - 2026-01-23

### 🔧 Fixed

- **Backward compatible dictionary default**: Changed `useBundledDictionary` default from `true` to `false` to prevent custom dictionaries from being silently ignored during settings migration. Users upgrading from v1.1.0 now have their custom dictionaries respected.
- **FIX version preservation in QuickFIX messages**: Fixed header/trailer tag detection to properly preserve the FIX version when constructing QuickFIX messages

---

## [1.2.1] - 2026-01-22

### 🔧 Fixed

- **Template variable sharing on send**: Variables defined in earlier fields (e.g., `${abc = uuid}`) are now available to later fields (e.g., `${abc}`) when validating before sending messages

---

## [1.2.0] - 2026-01-21

### ✨ Added

#### Multi-FIX Version Support
- **FIX 4.0 through 4.4** protocol version support
- **FIX 5.0, 5.0 SP1, and 5.0 SP2** protocol version support
- Seamless switching between FIX versions in session configuration

#### Enhanced FX Demo Server
- **Bundled FIX dictionary** for FX-specific message types
- **Pre-built templates** for common FX trading workflows
- Improved demo experience with realistic FX trading scenarios

### 🔧 Fixed

- **Profile session map sync**: Fixed issue where profileToSessionMap wasn't updated when tabs are reordered
- **Template variable sharing**: Variables are now correctly shared across fields during template validation

---

## [1.1.0] - 2026-01-18

### ✨ Added

#### FIX Message Latency Tracking
- **Packet-level timestamp capture** for accurate latency measurement
- **Latency displayed in message list** showing round-trip time for request/response pairs
- **Logon latency tracking** (35=A → 35=A) to measure session establishment time
- **Timestamps captured at QuickFIX/J callback layer** for minimal overhead
- **Support for TLS and localhost connections** with improved accuracy

#### Expandable Long Field Values
- **Click-to-expand** for long tag values in the Message Detail Panel
- Values longer than 50 characters show a `▼` indicator and are truncated with "..."
- **Click to toggle** between collapsed (single line) and expanded (full content) views
- Visual feedback with `▲`/`▼` indicators for expand/collapse state

### 📝 Documentation

- Added **Latency Measurement** section to help documentation explaining how latency tracking works

---

## [1.0.3] - 2026-01-15

### ✨ Added

#### Timestamp Offset Shorthand
- **New date/time offset syntax** for flexible timestamp manipulation in templates
- Supported units: `h` (hours), `d` (days), `w` (weeks), `m` (months), `y` (years)
- Examples:
  - `${now+1h}` → 1 hour from now
  - `${now-2d}` → 2 days ago
  - `${now+1w}` → 1 week from now
  - `${now+1m}` → 1 month from now
  - `${now-1y}` → 1 year ago
- **Custom format support**: `${now+1d:yyyyMMdd}` → tomorrow in date-only format
- **Variable assignment support**: `${expiry = now+30d}`
- Case-insensitive units (h/H, d/D, w/W, m/M, y/Y)

#### Multi-Select Message Operations
- **Multi-select FIX messages** in the message list with Shift+Click and Ctrl/Cmd+Click
- **Bulk copy**: Copy multiple selected messages to clipboard
- **Bulk save to file**: Save selected messages to a file

### 🔧 Fixed

- **Profile dropdown sync**: Message editor profile dropdown now correctly syncs with active session tab

---

## [1.0.2] - 2025-01-30

### 🔧 Fixed

#### Critical: Saved Message Race Condition
- **Fixed race condition** in `SavedMessagesService` that could cause message loss
- Added **thread-safe synchronization** using `synchronized(fileLock)` for all read/write operations
- Eliminated load-modify-save race condition when multiple operations happen concurrently
- **Impact:** No more lost messages when auto-save and manual save occur simultaneously

### 🧹 Removed

#### Backward Compatibility Cleanup (Post v1.0.0)
- Removed legacy `messagesByProfile` field and migration code
- Removed deprecated `profileId` field from `SavedFixMessage`
- Removed auto-deduplication on every load (performance improvement)
- Cleaned up backward compatibility code from `getAllUserTags()`
- **Result:** Cleaner codebase, better performance, simpler data model

### ✨ Added

#### Migration Version System
- Implemented schema versioning system (`CURRENT_VERSION = 1`)
- Added `migrate()` framework for future version upgrades
- Version checking on load with automatic migration support
- Ready for v1.1, v1.2+ migrations

#### Comprehensive Sharing Tests
- **17 new integration tests** in `MessageSharingTest.kt`
- Complete coverage of multi-user sharing scenarios:
  - Share/unshare with multiple users
  - Clone and modify messages independently
  - Complex multi-user workflows
  - Edge cases and error conditions
- **Total test count:** 632+ tests (all passing)

#### Installation & Distribution Documentation
- **INSTALLATION.md**: Complete cross-platform installation guide
  - macOS security warning explanations (unsigned app is normal for open source)
  - Right-click workaround and terminal commands
  - Windows SmartScreen bypass instructions
  - Linux installation steps
- **QUICK_INSTALL_GUIDE.md**: One-page TL;DR installation reference
- **DISTRIBUTION_GUIDE.md**: Developer guide for unsigned distribution
  - Build script for all platforms
  - GitHub release template
  - FAQ for open source distribution
- **MESSAGE_SHARING_TEST_COVERAGE.md**: Detailed test scenario documentation
- Updated README.md with clear macOS security section

#### Build Automation
- **scripts/build-release.sh**: Automated build script for all platforms
  - Builds DMG (macOS), MSI (Windows), DEB (Linux)
  - Generates SHA-256 checksums automatically
  - Creates CHECKSUMS.txt for user verification
  - No signing required (standard for open source projects)

### 📝 Changed

- Updated build configuration to support optional code signing (commented out by default)
- Improved user communication about security warnings on macOS/Windows
- Emphasized open source nature and comparison to major projects (Audacity, OBS Studio, GIMP)

### 🧪 Testing

- All 632+ tests passing
- New test categories:
  - Basic sharing operations (3 tests)
  - Cloning workflows (3 tests)
  - Complex multi-user scenarios (4 tests)
  - Edge cases (3 tests)
  - Sharing + cloning combined (2 tests)
  - Metadata preservation (2 tests)

### 📦 Distribution

- **Unsigned distribution** (no $99/year Apple fee, no $300/year Windows certificate)
- Clear user instructions for security warning bypass
- Checksums provided for download verification
- Professional documentation for open source approach

---

## [1.0.0] - 2025-01-29

### 🎉 Initial Release

FixTool 1.0.0 is a professional FIX protocol testing tool built with Kotlin Multiplatform and Compose Desktop.

### ✨ Core Features

#### Message Management
- **Template Browser**: Searchable browser with IntelliJ-style search, grouping by type, user, and favorites
- **Save as New**: Duplicate existing templates with one click
- **Duplicate Prevention**: Global duplicate name checking with case-insensitive validation

#### Template System
- **Dynamic Templates**: Use expressions like `${UUID.randomUUID()}` and `${currentTimestamp()}`
- **Message References**: Reference incoming/outgoing messages with shorthand syntax
- **Validation**: Real-time template expression validation with error blocking

#### Profile Management
- **Multi-Profile Support**: Manage multiple FIX sessions with different configurations
- **Profile Dropdown**: Smart profile dropdown with connection status indicators
- **Disconnected Profiles**: Select and manage disconnected profiles for template editing

#### User Experience
- **Layout Options**: Choose between horizontal and vertical split layouts
- **Session Management**: Configurable buffer size for message retention
- **Search**: Fast IntelliJ-style search across all templates

### 🛠️ Technical
- **Kotlin Multiplatform**: Built with modern Kotlin and Compose Desktop
- **QuickFIX/J**: Industry-standard FIX engine integration
- **JVM 17**: Requires Java 17 or higher
- **Cross-Platform**: Native installers for Windows, macOS, and Linux

### 📦 Installation

**System Requirements:**
- Java 17 or higher
- Windows 10/11, macOS 11+, or Linux (Ubuntu 20.04+)

**Platform-Specific:**
- Windows: MSI installer
- macOS: DMG installer
- Linux: DEB package (Debian/Ubuntu)

### 🧪 Testing

- 617 unit and integration tests
- Comprehensive test coverage for:
  - Template evaluation and validation
  - Duplicate checking and migration
  - Message filtering and search
  - Profile management
  - Favorites system

### 📝 Notes

This is the first stable release of FixTool. All core features are production-ready and battle-tested.

For bug reports and feature requests, please visit: https://github.com/amitgupta1202/FixTool/issues

---

## Future Releases

See [GitHub Releases](https://github.com/amitgupta1202/FixTool/releases) for upcoming versions.
