# Phase 7 — Assistant: agent-platform in Portal Remote

Design notes for putting a chatbot and an action-taking assistant into the Android
app, backed by the **agent-platform** API (`../../ai/agentic-ai/agent-platform`, the
Rust `agent-platformd`).

**Status:** design only. Nothing here is built.

The short version: agent-platform already has the exact primitive this needs, the phone
must never talk to it directly, and the whole feature has to survive the platform being
switched off — which it was while this document was written.

---

## 1. What is being asked for

Two things that sound like one:

1. **A chatbot** — general conversation in the app, with our own voice/animation
   treatment, that can also see and act on Portal Remote's own state.
2. **An assistant that acts** — "turn off all my alarms", "pause whatever the PC is
   playing and lock it" — deciding *which* capability to invoke and then invoking it,
   with the user confirming anything that matters.

They share a backend and a transport. They differ in one respect that shapes everything
below: a chatbot that fails is an empty reply, while an assistant that fails halfway has
already done something.

---

## 2. What agent-platform actually gives us

Verified against `desktop/crates/server/src/` on 2026-08-13, not read off the older
design docs (several of which predate the Rust migration and still name FastAPI paths).

| Need | Endpoint | Notes |
|---|---|---|
| Conversation | `POST /v1/chat/completions` | OpenAI-shaped, streaming, whatever provider the platform has configured. Optional BYOK via `X-BYOK-*` headers |
| Register our capabilities | `POST /api/v1/action-sets` | JSON-Schema per action, `execution_mode: "client"` |
| Decide what to do | `POST /api/v1/decide` | Returns `{thought, actions[]}` — a **plan**, not an execution |
| Multi-step | `POST /api/v1/sessions` | Context accumulates across steps |
| Is it alive | `GET /health`, `GET /v1/health/readiness` | Unauthenticated, instant |
| What can it do | `GET /v1/capabilities` | Providers, modalities, BYOK support |
| Speech | `POST /v1/audio/speech` | Thin proxy; structured `501` when unconfigured |

**The action orchestrator is the whole feature.** `action_orchestrator.rs` lets a client
declare what it can do, and asks the model to pick from that list. The server never
executes anything — it returns a plan and we run it. That is exactly the shape a remote
control wants, and it is why this does not need a bespoke tool-calling loop.

Two behaviours of `/decide` worth designing around, both deliberate on their side:

- **It never fails with an error status.** An unreachable model is a `200` whose
  `thought` begins `Error during decision: `, with an empty action list. So "did it
  work?" is answered by reading the body, not the status code.
- **It has text-parsing fallbacks** for models that answer the tool prompt in prose.
  Useful, and a reason to treat the action list as untrusted input rather than something
  guaranteed to match our schema.

**Do not use** `POST /api/v1/processes` (the DAG orchestrator) for this. Its tool-calling
path is disabled — `executor.rs` refuses a task whose configuration asks for tools. The
action orchestrator is a separate path and is unaffected.

`/api/v1/assistant/chat/send` also exists but is *their* assistant (E.V.), wired to the
platform's own goals, profile and review domain. Not our chatbot.

---

## 3. Topology — the PC owns the relationship

```
┌─────── PHONE ───────┐        ┌──────────── PC ────────────┐       ┌─ agent-platformd ─┐
│  Assistant screen   │        │  PortalRemote.Server       │       │  127.0.0.1:18410  │
│    chat + voice     │        │    ├─ /ai/chat   (SSE)     │       │                   │
│    confirm sheet    │◄──WS──►│    ├─ /ai/act              │◄─────►│  /v1/chat/…       │
│                     │        │    ├─ AiHealth (probe)     │ loop  │  /api/v1/decide   │
│  android.* actions  │◄───────┤    └─ desktop.* executor   │ back  │  /health          │
└─────────────────────┘        └────────────────────────────┘       └───────────────────┘
```

**The phone must not talk to `agent-platformd` directly.** Three reasons, in order:

1. It binds `127.0.0.1` by default, and reaching it from the phone means
   `AGENT_PLATFORM_HOST=0.0.0.0`.
2. With no `AGENT_PLATFORM_MASTER_KEY` set, **auth is fully open** — a deliberate
   local-dev convenience in their `auth.rs` that becomes an open database the moment the
   port is reachable.
3. It speaks plain HTTP. A bearer token would cross the Wi-Fi in clear, and it is a
   token for a completely different trust domain than our pairing token.

PortalRemote.Server already terminates the phone's connection and already holds a paired
token. It calls agent-platform over loopback, where none of the above applies. One
credential, one place, no new attack surface, and — the reason that matters for §4 — one
place that knows whether the platform is up.

---

## 4. Availability — the platform is often not running

This is the part to get right. `agent-platformd` is a separate app the user starts
independently; while writing this document, a probe of `127.0.0.1:18410` returned
connection refused. **Unavailable is the normal case, not the error case.**

### 4.1 One source of truth, pushed not polled

The PC owns an `AiAvailability` state and pushes it to the phone on the control socket
that is already open — the same pattern `now_playing` and `cast_status` use. The phone
never probes anything and never guesses.

```
{"t":"ai_state","state":"ready"|"starting"|"unavailable"|"unconfigured",
 "detail":"…","canStart":true}
```

- **`unconfigured`** — no base URL/token in `config.json`. A setup problem, not a
  failure. Say so, and say where.
- **`unavailable`** — configured, not answering. Carries `canStart` so the phone can
  offer a Start button when the PC knows how to launch it (§4.3).
- **`starting`** — a launch is in flight. Distinct from `unavailable` because the right
  UI is a spinner, not an error.
- **`ready`** — `GET /health` answered.

Pushing rather than polling also means the phone's Assistant tab is correct the instant
it opens, with no request of its own.

### 4.2 Probing without hammering

`GET /health` is unauthenticated and instant, so the cost is a TCP connect. Even so:

- Probe on **demand and on transition**, not on a timer: when the phone opens the
  Assistant tab, before dispatching a request, and after any failure.
- **Short connect timeout (~1s).** A refused connection is immediate; anything slower is
  not "down", it's "busy", and those need different words on screen.
- **Circuit breaker.** After 3 consecutive failures, stop probing and hold `unavailable`
  for 30s, doubling to a 5-minute ceiling. Reset on any success, and on an explicit user
  "Retry" — the user pressing a button is always allowed to skip the backoff.
- A **success is cached for a few seconds** so a chat turn and the action call behind it
  don't probe twice.

### 4.3 Ensuring it runs

Same rule as mpv in [phase4-casting.md §6](phase4-casting.md): **detect, do not bundle.**
An optional `ExePath` in config; when set, `unavailable` becomes actionable — the PC can
start `agent-platformd` and report `starting` until `/health` answers or a timeout
expires.

Constraints worth writing down before building it:

- **Start once, never in a loop.** A relaunch on every failed probe is a fork bomb with
  extra steps. One attempt per explicit user request, or one per app session.
- **Adopt, don't duplicate.** If the port answers, something is already running — use
  it. The desktop app spawns its own `agent-platformd`, so a second one is a real
  possibility and it would fight over the same SQLite file.
- **Never auto-start unprompted.** Launching a background AI daemon because someone
  opened a tab is not a decision this app gets to make silently.

### 4.4 Failure mid-flight

| Failure | Handling |
|---|---|
| Connect refused before a request | Never dispatched. `unavailable`, draft preserved, Retry offered |
| SSE chat stream dies mid-reply | **Keep the partial text**, mark the turn incomplete, offer Regenerate. Do not silently discard — a half answer is usually still worth reading |
| `/decide` times out or 5xx | Retry **once** with backoff, then surface. Safe to retry: see below |
| `/decide` returns `200` with an `Error during decision:` thought | Treat as a failure, not a plan. Show the thought — it names the real cause |
| Action executes, phone drops before the result is reported | The action already happened. Report it on reconnect rather than re-running it |
| Platform dies between plan and confirm | The plan is still valid; execution is entirely ours. Confirm and execute normally |

**Retrying `/decide` is safe, and that is a property worth protecting.** It only
*proposes*; nothing happens until the user confirms and we execute. Keep it that way —
the moment a decide call has a side effect, every retry above becomes a correctness bug.

Execution is the opposite: give each confirmed plan a client-side id and refuse to run
the same id twice, so a reconnect-and-resend cannot double-execute.

### 4.5 What not to build

- **Don't queue chat turns for later.** Phase 5 queues shares across a disconnect because
  a file is still a file an hour later; a question answered twenty minutes after it was
  asked is noise. Preserve the draft, don't send it.
- **Don't hide the feature when it's down.** A tab that vanishes reads as a bug. Show it,
  disabled, with the reason and the fix.
- **Don't retry forever behind a spinner.** Two attempts then a human-readable stop.

---

## 5. The action surface

Two action sets, registered at PC startup (list by name, create when absent — the
registration must be idempotent across restarts).

**`portal.desktop.*` — executed by the PC.** Almost free: these map onto handlers that
already exist in `InputActions.Dispatch`.

| Action | Existing path |
|---|---|
| `media_control` | `media` message → `WinInput.Tap` |
| `press_keys` | `combo` |
| `type_text` | `text` |
| `cast_url` | `cast` → `CastLauncher` / `CastHub` |
| `player_transport` | `player` |
| `power` | `power` → `Power.Apply` |
| `now_playing` (read) | `NowPlaying.Snapshot` |

**`portal.android.*` — returned to the phone and executed there.** New code, and gated
by §6.

The wire shape is a new pair of messages on the existing control socket, not new
endpoints — same reasoning `cast` used:

```
-> {"t":"ai_act","goal":"pause the film and lock the PC","id":"<client id>"}
<- {"t":"ai_plan","id":"…","thought":"…","actions":[{"action_id":"…","parameters":{…}}]}
-> {"t":"ai_confirm","id":"…","approved":["player_transport","power"]}
<- {"t":"ai_result","id":"…","results":[…]}
```

The phone approves a **subset**, by action id. A plan is not all-or-nothing.

---

## 6. The Android automation ceiling

Worth stating plainly before anyone promises it, because the motivating example is the
hardest case.

**"Turn off all alarms"** has no clean answer. There is no public API to enumerate
alarms. `AlarmClock.ACTION_DISMISS_ALARM` with `ALARM_SEARCH_MODE_ALL` exists (API 23+),
but it dismisses *firing or scheduled instances* rather than disabling a recurring alarm,
and honouring it is up to the clock app — AOSP Deskclock does, OEM clocks vary. **Verify
on the actual device before shipping any wording that promises it.**

Cleanly reachable without heroics:

| Capability | Mechanism |
|---|---|
| DND / ringer mode | `NotificationManager` + `NotificationPolicy` access |
| Volume | `AudioManager` |
| Torch | `CameraManager.setTorchMode` |
| Brightness | `WRITE_SETTINGS` |
| Launch an app, open a settings panel | `Intent` |
| Media transport | `MediaSessionManager` |
| Set an alarm or timer | `ACTION_SET_ALARM` / `ACTION_SET_TIMER` |
| Read/dismiss notifications | `NotificationListenerService` |
| Calendar, contacts | ContentProvider |

Anything outside that list needs an `AccessibilityService` driving another app's UI. It
works, and it is a Play Store policy landmine — see the Tier 2 discussion in
[phase4-casting.md §9](phase4-casting.md), which reached the same conclusion for a
different feature. Fine while sideloaded; decide it deliberately, not by drift.

**Design consequence:** the phone must be able to *refuse* an action the model proposed.
Capability is checked on the device at confirm time, and an unsupported action is shown
as unavailable with a reason rather than attempted and failed.

---

## 7. Confirmation and security

`/decide` proposes; the user disposes. Structurally, not as a setting.

- **Nothing auto-executes.** The confirmation sheet lists each action in plain language
  with its parameters. Approval is per-action.
- **A second confirm for the destructive ones** — `power` with `shutdown`/`restart` is
  already behind a confirm in the TV remote and must stay behind one here.
- **The model's output is untrusted input.** Validate `action_id` against the registered
  set and every parameter against its schema before rendering, let alone executing. The
  text-parsing fallbacks in `/decide` mean a malformed action is a realistic case, not a
  hypothetical.
- **Goal text leaves the LAN** if the platform's configured provider is a hosted one.
  That is a real change from every other feature in this app, all of which are
  LAN-only. It belongs in the README's security notes, and the Assistant tab should say
  which provider is answering.
- **Conversation history stays on the PC**, in the same place the pairing token lives —
  not synced anywhere, and wipeable in one action.

---

## 8. Voice and audio

- **TTS:** Android's built-in `TextToSpeech` is free, offline and already on the device.
  Use it. `POST /v1/audio/speech` only becomes interesting if the phone should sound
  identical to the desktop app's voice, and it answers `501` unless the platform has a
  speech backend configured — so it needs a fallback path regardless.
- **STT:** Android `SpeechRecognizer` first, for the same reason.
- The audio-reactive animation work is a client concern and does not touch any of the
  above; it drives off the local TTS/mic amplitude, not the network.

---

## 9. Configuration and credentials

A new block in `ServerConfig` (`%APPDATA%\portal-remote\config.json`, alongside the
existing pairing `Token`):

```json
"AgentPlatform": {
  "BaseUrl": "http://127.0.0.1:18410",
  "Token": "",
  "ExePath": ""
}
```

**No token goes in this repo, this document, or any committed file.** An agent-platform
token grants full access to a workspace; a real one in git is a credential leak whether
or not the repo is public. Mint one on the machine that runs it:

```bash
curl -s -X POST http://127.0.0.1:18410/api/v1/workspaces/1/api-tokens/ \
  -H "Authorization: Bearer $AGENT_PLATFORM_MASTER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name":"portal-remote","scopes":["*"]}'
```

The response's `token` field is shown **once**. Paste it into `config.json`, or add a
field to the PC's settings window.

Empty `Token` is legal and useful: with no `AGENT_PLATFORM_MASTER_KEY` set on their side,
auth is open on loopback. That is the zero-setup path for a local install, and the reason
`unconfigured` in §4.1 keys off `BaseUrl` rather than off the token.

Identify ourselves with `X-Agent-Platform-Client: portal-remote` on every request — their
`auth.rs` resolves that header and their other client already uses it.

---

## 10. Build order

| # | Step | Size | Proves |
|---|---|---|---|
| **7a** | Config block + `AiHealth` probe + `ai_state` push + a disabled Assistant tab that explains itself | **S** | The availability model, before anything depends on it. This is the step that makes the rest safe to build |
| **7b** | `/ai/chat` SSE passthrough + chat UI with partial-reply retention and Regenerate | M | The chatbot, and the mid-stream failure path |
| **7c** | `portal.desktop.*` action set + `/decide` + confirm sheet + execution | M | The whole assistant loop, using only handlers that already exist |
| **7d** | Voice in/out with the platform-independent engines | S | — |
| **7e** | `portal.android.*` — start with DND, volume, torch, launch-app | M | The on-device half, once the confirm flow is proven |
| **7f** | `/api/v1/sessions` for multi-step goals | S | Only if one-shot `/decide` visibly can't chain |
| **7g** | Optional `ExePath` launch (§4.3) | S | Deliberately last — it is the only step that starts a process |

**Do 7a first and don't skip it.** Every other step assumes a correct answer to "is it
up?", and building that answer after the features that need it is how a feature ends up
with three different retry behaviours.

---

## 11. Decisions needed

1. **Play Store, ever?** Same question as [phase4-casting.md §12](phase4-casting.md), and
   it decides §6's ceiling permanently. Sideloaded keeps `AccessibilityService` open.
2. **Which provider answers?** A local model via the platform keeps everything on the
   machine; a hosted one is better and sends the goal text off the LAN (§7).
3. **Does the assistant get read access to the PC's screen?** The mirror already exists,
   and a vision model could use it. It is also the single largest privacy step in this
   document.
4. **`ExePath` autostart in or out?** Convenient; also means this app can start a
   background daemon.
5. **Chat history retention** — how long, and wiped on unpair or not?

---

## 12. Should the two apps merge?

**No.** Reviewed 2026-08-13, same conclusion agent-platform's own
`docs/portal-desktop-review.md` §3 reached about its other client.

Portal Remote's server is ~62% Windows-bound *by what it does* — `SendInput`, WinForms
tray, GDI capture, the WinRT media session — and none of that becomes portable by moving
to a Rust/iced repo. The two also have opposite network postures: `agent-platformd` is
loopback-only headless by decision, while this server must be LAN-reachable and
QR-paired. Merging produces one binary with two contradictory security models.

They stay separate and talk over the API, which is what this document specifies.

## Sources

- `../../ai/agentic-ai/agent-platform/docs/CLIENT_INTEGRATION.md` — tokens, workspaces, the client contract
- `../../ai/agentic-ai/agent-platform/docs/action-orchestrator-api.md` — action sets, `/decide`, sessions
- `../../ai/agentic-ai/agent-platform/docs/portal-desktop-review.md` — the merge question, answered once already
- `desktop/crates/server/src/action_orchestrator.rs` — the routes and the parsing fallbacks
- [Android — AlarmClock intents](https://developer.android.com/reference/android/provider/AlarmClock)
- [Android — NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
