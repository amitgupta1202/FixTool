---
name: verify
description: Build, launch, and drive FixTool end-to-end via its own HTTP control surface to verify a change at runtime. Use when verifying UI or engine changes in the running app (not unit tests).
---

# Verifying FixTool changes at runtime

FixTool is Compose-on-Skia — no DOM to drive. The app ships its own driver: a loopback
HTTP control surface (see `docs/AUTOMATION.md`). Verify by launching the app with it
enabled, driving real FIX flows, and reading back parsed messages + screenshots.

## Check for an already-running instance FIRST

The user often has FixTool running (usually port 8765) with live sessions. Never kill or
drive it — it runs the *old* build anyway.

```bash
curl -s -m 2 http://127.0.0.1:8765/health   # if ok → pick another port for yours
```

## Launch your build (isolated)

```bash
FIXTOOL_CONTROL_PORT=8799 ./gradlew :composeApp:run --quiet   # run_in_background
# wait: until curl -s http://127.0.0.1:8799/health; do sleep 1; done   (~30s first time)
```

The demo FIX server binds a fixed port **19876**, so only one instance can own it. If the
user's instance already runs it, don't start yours — the demo acceptor accepts
DEMO_CLIENT1–4 and the user typically uses 1–2, so connect your instance as CLIENT3:

```bash
B=http://127.0.0.1:8799
curl -s -XPOST $B/profiles -d '{"name":"VERIFY TEMP Demo3","config":{"senderCompID":"DEMO_CLIENT3","targetCompID":"DEMO_SERVER","host":"localhost","port":"19876","beginString":"FIX.4.4","heartBtInt":"30","resetOnLogon":true}}'
curl -s -XPOST $B/connect -d '{"profile":"VERIFY TEMP Demo3"}'
until curl -s $B/sessions | grep -q LOGGED_ON; do sleep 0.5; done
```

## Drive

```bash
curl -s -XPOST $B/send -d '{"raw":"8=FIX.4.4|35=D|11=VRF-1|55=EUR/USD|54=1|38=1000000|40=1|60=20260101-00:00:00|"}'
curl -s "$B/messages?direction=incoming&messageType=8&limit=1"     # parsed ER back
curl -s -XPOST $B/select -d '{"messageType":"8","direction":"in"}' # opens detail panel
curl -s $B/screenshot -o shot.png                                   # main window pixels
```

Scenarios: `POST /scenarios` (JSON per `docs/fixtool-assert-spec.md` / `ScenarioCodec`),
`POST /scenarios/run -d '{"id":...}'` → per-step/per-tag report. `POST /assert` for
one-off expectations. `POST /panel -d '{"panel":"scenarios"}'` toggles the Scenarios rail
(docked in the main window). The reconcile diff and the plain diff viewer open in their own
windows; address one by title with `GET /screenshot?window=main|reconcile|diff:`.

## Gotchas

- Profiles/scenarios stores under `~/.fixtool/` are SHARED across instances — name temp
  artifacts `VERIFY TEMP …` and DELETE them (`/scenarios`, `/profiles`) when done.
- Button clicks have no HTTP hook — UI-click-only paths need Compose UI tests or visual
  screenshot evidence; the control surface covers select/send/run/panel toggles.
- Kill your instance by exact PID of the `:composeApp:run --quiet` java process — a
  pkill on the env var string misses it (env isn't in the child cmdline) and risks
  matching the user's wrapper shell instead.
