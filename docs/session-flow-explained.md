# Spotify Automation Platform — How It Works & Full Session Flow

> A function-by-function walkthrough of the whole system, from clicking "Run" on the
> dashboard to the very end of a session. Grounded in the actual code.

---

## What this project is

The platform drives the **real Spotify Android app** by *simulating a human* through
Android's Accessibility Service. There is **no Spotify API** — the bot finds buttons on
screen and taps them.

Three layers talk to each other:

| Layer | Role | Key files |
|---|---|---|
| **Frontend** (React/Vite) | Dashboard — schedule sessions, hit "Run Now", watch live progress | `frontend/src/pages/DashboardPage.jsx` |
| **Backend** (FastAPI) | Brain — auth, stores tasks/sessions/runs, dispatches commands, schedules sessions, relays live events | `backend/routers/commands.py`, `backend/routers/ws.py`, `backend/websocket/manager.py`, `backend/tasks/session_scheduler.py` |
| **APK** (Android/Java) | Hands — holds a WebSocket open, receives commands, taps Spotify, reports each step | `apk/.../BotWebSocketClient.java`, `CommandRouter.java`, `SearchAndPlayExecutor.java`, `SpotifyExecutor.java` |

**Connection backbone:** a persistent **WebSocket** from each phone to the backend
(`/ws/{device_id}`). Commands flow backend → phone; step events flow phone → backend;
the dashboard receives those events via **SSE** (Server-Sent Events).

**The mental model:** the system is a relay race with **one baton = the `run_id`**. The
backend mints it, the phone carries it through every Spotify tap, and stamps it on every
event it sends back. Follow the `run_id` and you follow the whole flow.

---

## Two ways a task runs

- **Run Now** (manual) → `commands.py:run_now` — fires *one* task *once*, immediately.
- **Session** (scheduled) → `session_scheduler.py` — runs an *ordered list* of tasks, *on
  a loop*, between a start and end time.

The session path reuses almost all of the Run Now machinery underneath.

---

## High-level sequence

```
Dashboard ──POST /sessions──▶ Backend
                               │ scheduler loop: start_time reached → spawn session task
                               ▼
                          COMMAND {action_type, params, run_id, ttl} ──WebSocket──▶ APK
                                                                                     │ CommandRouter: TTL check → pick Executor
                                                                                     ▼
                                                                            tap Search → type → Songs filter → tap row → Play ──▶ Spotify
                                                                                     │
            Dashboard ◀──SSE run_event── Backend ◀──STEP_STARTED / STEP_OK / STEP_FAILED── APK
            (live log)                   (persist to run_events table)
                                         │
            Dashboard ◀──SSE run_status── Backend ◀──COMMAND_DONE (final_status=SUCCESS)── APK
            (badge → SUCCESS)
                                         │ scheduler: store run as "pending playback",
                                         │            run instant tasks (LIKE/SKIP) mid-song
                                         ▼
                          Backend ◀──PLAYBACK_FINISHED (track ended)── APK (playback monitor)
                          │ notify_playback_finished → wake waiter, dispatch NEXT task
                          ▼
            repeat sequence → cooldown 30–90s → next iteration
                          │
                          ▼
            end_time reached → status=done ──SSE session_status=done──▶ Dashboard
```

Legend: arrows down = command/request, arrows up (events) = step results flowing back.

---

# PART 1 — Frontend: starting the run

### 1.1 `handleRunNow(taskId)` / session creation — `DashboardPage.jsx:516`
On **▶ Run Now** (or submitting a session list), the React handler:
- Validates a device is selected and the task has a query if its `action_type` needs one
  (`ACTION_MAP[...].needsQuery`).
- Calls `runNow(taskId, selectedDevice)` (Axios `POST /commands/run` with the JWT), or for
  a session, `POST /sessions` with the ordered task list + start/end times.
- Stores the returned `run_id` in `activeRun` and calls `startPolling(run_id)`.

> **Two live-update mechanisms run in parallel:** `startPolling` hits
> `GET /commands/runs/{id}` every 2s as a *safety net*, while the **SSE EventSource**
> (`DashboardPage.jsx:270`) gives *instant* push updates. SSE is primary; polling catches
> anything SSE drops.

### 1.2 The SSE listener — `DashboardPage.jsx:314`
A single `EventSource` receives every backend event type: `run_event` (a step happened),
`run_status` (badge flip), `session_run` (which run_id is now live for a session),
`session_status`, and `device_status`. The `session_id` carried on each `run_event` lets
the dashboard re-bind events to the right run even if it briefly missed a `session_run`.

---

# PART 2 — Backend: minting the command

### 2.1a Manual path — `run_now()` — `commands.py:32`
1. **Validate task** belongs to `current_user` (JWT-decoded user) — `select(Task).where(id, user_id)`.
2. **Validate device** exists in DB.
3. **`manager.is_connected(device_id)`** — bail with `503` if the phone's WebSocket isn't
   live. *This gate makes "Run Now" fail fast instead of hanging.*
4. **Create the `Run` row** (`status=RUNNING`, fresh `run_id = uuid4()`). This is the baton.
5. **Build `command_payload`** — merges `task.action_params` (JSON) with `search_query`,
   mapping it to the param name each executor expects (`query`, `artist_name`, or
   `playlist_name`). Adds `issued_at` and `ttl_ms=180000`.
6. **`manager.send_command()`** pushes it. If it returns `False`, the run is immediately
   marked `FAILED` so it never gets stuck in `RUNNING`.

### 2.1b Session path — `_dispatch_task()` — `session_scheduler.py:465`
The session's equivalent of `run_now`, called once per task per iteration. Same
payload-building logic, plus:
- `_run_to_session[run_id] = session_id` — so `ws.py` can tag every event with its session.
- After a successful send, broadcasts SSE **`session_run`** ("this run_id is now
  executing"), carrying `play_duration_s`.
- `issued_at` is formatted with a literal `Z` suffix (`...%SZ`) because the APK parses it
  with `java.time.Instant.parse()`, which *requires* the Z.

### 2.2 `manager.send_command(device_id, payload)` — `manager.py:111`
The actual wire push:
- Looks up the live `WebSocket` in the in-memory `_connections` dict.
- **3 attempts with 0.4s gaps** — covers a momentary reconnect.
- `asyncio.wait_for(ws.send_text(...), timeout=5.0)` — a stuck socket can't block forever.
- **Never raises.** On failure it pops the dead socket and returns `False`, so the caller
  can close the run cleanly.

---

# PART 3 — The WebSocket lifeline

### 3.1 Why the connection already exists — `ws.py:36`
The phone opened this socket earlier. Handshake order matters:
1. `websocket.accept()` — but **NOT** registered in the manager yet.
2. Wait for the first message; it *must* be `DEVICE_HELLO` (else close).
3. Verify `shared_secret`. Unknown device + correct backend secret → **auto-register** a
   new `Device` row. Known device → check its stored token.
4. **Only now** `manager.connect()` registers the socket, *then* sends `AUTH_OK`.
   Registering before auth would make the device "reachable" mid-handshake, and a command
   sent then would arrive before the receive-loop starts and be silently dropped.

### 3.2 The receive loop — `ws.py:156`
`asyncio.wait_for(receive_text(), timeout=120)` — if the phone dies hard (battery out), the
server isn't blocked forever. Every message refreshes `device.last_seen`. It dispatches by
`type`: `PING`→`PONG`, `PLAYBACK_FINISHED`→scheduler wake, and the four step
events→persist+broadcast.

### 3.3 APK side: receiving the command — `BotWebSocketClient.onMessage:361`
On `type=COMMAND` it calls `mainHandler.postAtFrontOfQueue(...)` — **not** a normal `post`.
During automation the accessibility service floods the main thread; a normal post could
delay the command by seconds.

### 3.4 The heartbeat — `startHeartbeat():471`
Runs on a **dedicated `HandlerThread`**, not the main looper (same flood reason). Every 15s
it sends a JSON `PING`. If no `PONG` arrives in 90s (`PONG_STALE_MS`), it declares the
socket a zombie and calls `forceReconnect()` — *unless* `isCommandInProgress()`, in which
case it pauses so it never interrupts a tap sequence.

---

# PART 4 — APK: routing and executing

### 4.1 `CommandRouter.execute()` — `CommandRouter.java:50`
1. **TTL check** — parses `issued_at`, compares age to `ttl_ms`. If expired, it sends
   `COMMAND_EXPIRED`/`FAILED` and **does not run** — re-running a stale command would
   corrupt the run log.
2. **Routes** `action_type` → a fresh executor instance (`SEARCH_AND_PLAY →
   SearchAndPlayExecutor`, etc.). Fresh instance = no shared state between commands.
3. Unknown action → `UNKNOWN_ACTION`/`FAILED`.
4. Calls `executor.execute(runId, commandId, params, ws)`.

### 4.2 `SpotifyExecutor.execute()` (base) — `SpotifyExecutor.java:175`
The `final` entry point shared by every executor:
- Stores `runId`/`commandId`/`ws`, sets **`commandInProgress = true`** (the global flag
  that tells the WebSocket layer "do not reconnect now").
- `stopPlaybackMonitor()` — kills any leftover monitor from the previous command.
- If accessibility isn't bound, `waitForAccessibilityBind()` polls up to 15s before giving
  up with `ACCESSIBILITY_SERVICE_NOT_RUNNING`.
- Calls `doExecute(params)` — the subclass's step chain.

### 4.3 `launchAndSettle()` — `SpotifyExecutor.java:267`
Every executor begins here. Guarantees Spotify is foreground and the UI is readable before
step 1:
- If Spotify already foreground → just `dismissOverlays()` and a tiny gap.
- If not → `ensureScreenOn()` (acquires a `SCREEN_BRIGHT_WAKE_LOCK` if the screen is off —
  accessibility can't read a dark screen), `launchSpotify()` via the package launch intent,
  then a jittered ~1.5s settle.
- Before the first step it checks `OverlayGuard` for a blocking ad/upsell and, if an
  **audio ad** is playing, `waitForAudioAdToFinish()` defers the whole command. This is the
  ad-resilience that stops sessions crashing on ads.

### 4.4 The step engine (the heart of every executor)
Each step follows the same skeleton, built from base-class primitives:

| Primitive | File:line | Job |
|---|---|---|
| `stepStarted(id,name)` | `2359` | sends `STEP_STARTED` over WS |
| `scheduleStepTimeout(id,ms)` | `119` | arms a 15s watchdog on a **dedicated thread** |
| `findById/findByDesc/findByText` | — | locate the Spotify UI node in the accessibility tree |
| `tapAtNodeRandom` / `performAction(ACTION_CLICK)` | — | the actual tap |
| `cancelStepTimeout()` | `142` | disarms the watchdog on success |
| `stepOk(id,name)` / `stepFailed(id,reason)` | `2364` | report result |
| `scheduleStep(next, delayMs)` | `156` | chain to the next step (timer on dedicated thread, runnable on main) |

The **`timeoutFired` flag** (`107`) is the race guard: if the watchdog fires first, a
slow-completing step checks this flag and bails so it can't send `stepOk` *after* the
watchdog already sent `STEP_FAILED`.

### 4.5 A concrete chain — `SearchAndPlayExecutor.doExecute()` — `SearchAndPlayExecutor.java:46`
1. **`stepOpenSearch`** (`62`) — `tapSearchTab()`.
2. **`stepTypeQuery`** (`80`) — `findSearchInput()` + `typeText()`.
3. **`stepSubmitSearch`** → **`stepTapSongsTab`** (`121`) — taps the **Songs** filter chip
   and *verifies it's selected* (`verifySongsFilterSelected`) before touching any row.
   Prevents accidentally playing a "Top result" album.
4. **`stepTapResult`** (`225`) — `findSongRow()` matches `id/title` against the query
   (confirmed by a `"Song •"` subtitle), with a three-dot `findFirstSongRowViaDot()`
   fallback and up to 4 scrolls. Taps `row_root` via `ACTION_CLICK`.
   > Documented gotcha: `row_root` reports `isClickable=false` but **responds to
   > `ACTION_CLICK`** — so it never uses `clickNode()` here.
5. **`stepTapPlayIfPaused`** (`387`) — taps Play only if no Pause button is visible.
6. **`stepVerifyPlayback`** (`418`) — `dismissOverlays()` + `skipAdIfPresent()`, confirms
   the Now Playing bar, then **`commandDone(true)`** and **`startPlaybackMonitor(runId)`**.

### 4.6 `commandDone(success)` — `SpotifyExecutor.java:2388`
The single terminal event. Because it's the *only* signal that closes the run, it's
hardened:
- Cancels the watchdog first.
- `sendCommandDoneWithRetry()` retries every 2s for up to **5 minutes**
  (`COMMAND_DONE_MAX_RETRIES=150`), and if the original socket died mid-command it falls
  back to `BotForegroundService.currentWs` (always the live socket). Then
  `releaseCommandLock()` clears `commandInProgress`, freeing the WebSocket layer to
  reconnect again.

---

# PART 5 — Events flowing back

### 5.1 `sendStep` → backend persistence — `ws.py:202`
Each `STEP_*`/`COMMAND_DONE` message:
1. Is written to the **`run_events`** table (the permanent log `GET /commands/runs/{id}`
   reads).
2. On `COMMAND_DONE`, closes the `Run` row with `final_status`. Special rule (`ws.py:230`):
   a late `COMMAND_DONE=SUCCESS` **overwrites** a `TIMED_OUT` — the phone's real result
   always wins over the timeout guess.

### 5.2 `broadcaster.publish()` — `broadcaster.py:54`
Fans the event out to every open dashboard tab. Each tab = one `asyncio.Queue` (created by
`subscribe()`). A **slow tab whose queue fills (maxsize 200) is silently dropped** rather
than blocking everyone. The SSE endpoint generator reads its queue and streams `data: ...`
lines to the browser. This is the instant path; the 2s poll is the backup.

---

# PART 6 — The timing brain (sessions only)

### 6.1 The playback monitor — `startPlaybackMonitor():1754`
After a song *starts*, the APK must detect when it *ends*. Two complementary detectors run
because Samsung throttles timers when the screen is off:
- **Event-driven** (`onTrackChangedByEvent:1715`) — `SpotifyAccessibilityService` watches
  `WINDOW_CONTENT_CHANGED` events; when the track **title changes** (new song = old song
  finished), it fires `PLAYBACK_FINISHED`. Accessibility events bypass timer throttling, so
  this works during sleep.
- **Seekbar poll** (backup) — every 5s reads the seekbar's `position` vs `duration`, firing
  3s before the end. Guards: ignores tracks under 45s (`MIN_SONG_DURATION_MS` — that's an
  ad, not a song) and ignores the first 30s of monitoring (`MIN_MONITOR_AGE_MS` — avoids a
  false positive at startup).

### 6.2 Backend wakes the scheduler — `ws.py:190` → `notify_playback_finished():102`
`PLAYBACK_FINISHED` sets an `asyncio.Event` keyed by `run_id`. The scheduler coroutine is
blocked on exactly that event in `_wait_for_playback_finish()`.

### 6.3 The lazy-wait loop — `_run_session_task()` — `session_scheduler.py:189`
Per iteration it walks the task list and:
- Dispatches each task via `_dispatch_task`, then `_wait_for_run()` until that command's
  `COMMAND_DONE`.
- For a **playback** task, it does *not* block — it stores `pending_playback_run_id` and
  moves on, so **instant tasks (LIKE/SKIP/FOLLOW) fire mid-song** with a 5–10s gap.
- Only when the *next* playback task is about to start does it call
  `_wait_for_playback_finish()` (the Event from 6.2), so the previous song actually finishes
  before a new one begins.
- `_wait_for_playback_finish` computes a **fair-share timeout** (`session_scheduler.py:658`):
  remaining session time ÷ remaining tasks, capped at 4.5 min, so one stalled track can't
  starve the rest of the sequence.
- After the last task, waits a random **30–90s cooldown**, then repeats.
- Device offline mid-session is treated as a **pause** (`_wait_for_device_online`, up to
  5 min), **not** counted toward `MAX_CONSECUTIVE_FAILURES=5`.

### 6.4 Ending — `session_scheduler.py:440`
The loop exits when `now >= end_time`, the session is stopped, or 5 consecutive command
failures occur. It sets `status` (`done`/`failed`), broadcasts a final `session_status`, and
`_cleanup_run_to_session()` clears the run→session map.

---

## One-line summary of the whole flow

> Dashboard `POST` → backend mints a **`run_id`** and `Run` row → `manager.send_command`
> pushes a JSON `COMMAND` down the phone's WebSocket → `CommandRouter` TTL-checks and routes
> it → an `Executor` runs a watchdog-guarded **step chain** of accessibility taps on Spotify
> → each step sends an event back → backend **persists to `run_events`** and
> **SSE-broadcasts** to your live log → `commandDone` closes the run → a **playback monitor**
> fires `PLAYBACK_FINISHED` when the song ends → the **session scheduler** advances to the
> next task, loops with a cooldown, and stops at `end_time`.

---

## Resilience features worth knowing

- **Fail-fast dispatch:** `manager.send_command` never raises; a broken socket → run marked
  `FAILED` instead of stuck `RUNNING`.
- **30s disconnect grace** (`ws.py:288`): brief WS drops don't kill a run still executing on
  the phone.
- **Device-offline = pause, not failure:** sessions wait patiently for reconnect.
- **Reconnect deferred during commands:** `isCommandInProgress()` blocks reconnect so
  automation isn't interrupted mid-tap.
- **Zombie detection:** no-PONG-in-90s → `forceReconnect()`; backend mirror via
  `force_disconnect`.
- **Late-result wins:** a real `COMMAND_DONE=SUCCESS` overwrites a `TIMED_OUT`.
- **TTL drop:** commands older than `ttl_ms` are never executed on reconnect.
