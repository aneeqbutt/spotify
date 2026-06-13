# Sportify Project — Brain (New Chat Reference)

> Last updated: 2026-06-02
> Read this file FIRST before doing anything in a new chat.

## 🔴 Hard Rules (Never Break)

1. **Never write files to `C:\Users\Aneeq\Desktop\`** — all output goes inside `C:\Users\Aneeq\Desktop\Sportify\` or a subfolder
2. **Never change UIAutoDev-confirmed UI logic without explicit user permission** — always report the bug first, ask before fixing
3. **Stop on first failure** — do not auto-retry, do not chain fallbacks silently (see global `~/.claude/CLAUDE.md`)
4. **`SaveToLibraryExecutor` is permanently removed** — Spotify removed the Save to Library UI feature. Do not re-add it.

## Project Summary

**Spotify Automation Platform** — Android APK controlled by a FastAPI backend + React dashboard.

| Layer     | Tech                                          | Location                                      |
|-----------|-----------------------------------------------|-----------------------------------------------|
| Backend   | FastAPI + Python 3.8+ + SQLite + JWT + WS     | `C:\Users\Aneeq\Desktop\Sportify\backend\`    |
| Frontend  | React 18 + Vite + Axios                       | `C:\Users\Aneeq\Desktop\Sportify\frontend\`   |
| APK       | Java (JDK 8+), Accessibility Service          | `C:\Users\Aneeq\Desktop\Sportify\apk\`        |
| Device    | Samsung Galaxy S21 FE — Android 14 / KNOX     |                                               |

Backend URL: `http://192.168.1.4:8000` (fallback). Actual host is discovered via UDP beacon at runtime.

## Current Status: All Features Complete ✅

| Feature | Status | Notes |
|---|---|---|
| M1 — Week 1 | ✅ DONE | Backend + Frontend + APK scaffolding + first Spotify click |
| M2 — Week 2 | ✅ DONE | JWT auth, WS, Dashboard, APK executor engine, all 9 executors |
| Live Step Streaming | ✅ DONE | Run Log panel always visible; SSE `run_event` / `run_status` |
| Session-Based Automation | ✅ DONE | Multi-task sessions, scheduler, play-duration wait logic |
| Bot-Detection Avoidance | ✅ DONE | Randomized tap coordinates + jittered step delays |
| Overlay / Ad Handling | ✅ DONE | Auto-dismiss incl. promo cards ("Try a playlist", "MADE FOR YOU") |
| Session Live Log | ✅ DONE | `session_run` SSE event wires session runs into Run Log panel |
| Playback Wait Logic | ✅ DONE | SEARCH_AND_PLAY + PLAY_FROM_PLAYLIST wait for PLAYBACK_FINISHED event from APK; PLAY_FROM_ALBUM uses timer |

## Session-Based Automation — Full Design

### Overview
A session is an ordered list of tasks that execute in a loop between `start_time` and `end_time`. The backend drives the loop — zero APK changes required. Each task is a fresh COMMAND identical to "Run Now".

### Session DB schema (`backend/models/tables.py`)
- **`sessions`** table: `id, user_id, device_id, task_id (anchor FK), start_time, end_time, status`
- **`session_tasks`** table: `id, session_id, task_id, position` — ordered task sequence
- Status values: `scheduled → running → done | failed`
- All datetimes stored as **naive UTC** in SQLite (no timezone suffix)

### Scheduler (`backend/tasks/session_scheduler.py`)
- `SessionScheduler` singleton — one asyncio Task per active session
- `session_scheduler_loop()` — background loop, wakes every 30s to start due sessions
- On startup: resumes any `status IN ('running','scheduled') AND start_time <= now` sessions
- **Post-task wait logic:**

```
TRACK_FINISH_ACTIONS = {SEARCH_AND_PLAY, PLAY_FROM_PLAYLIST}
PLAYBACK_ACTIONS     = {SEARCH_AND_PLAY, PLAY_FROM_ALBUM, PLAY_FROM_PLAYLIST}

After each task's COMMAND_DONE:
  if action_type in TRACK_FINISH_ACTIONS and final == SUCCESS:
      await _wait_for_playback_finish(run_id)   ← blocks on asyncio.Event
      APK sends PLAYBACK_FINISHED when position_text >= duration_text - 3s
      10-min fallback: moves on if event never arrives
  elif action_type in PLAYBACK_ACTIONS and final == SUCCESS:
      wait play_duration_s timer (PLAY_FROM_ALBUM only)
      interruptible every 30s (respects stop/end_time)
  else (instant task: LIKE, SKIP, FOLLOW, ADD_TO_PLAYLIST, CREATE_PLAYLIST):
      wait 2-5s only
```

- Inter-iteration cooldown: random 60–300s between full task-list repetitions
- Max consecutive failures before marking session failed: 3

### Session API (`backend/routers/sessions.py`)
- `POST /sessions/` — create, validates all task_ids, stores naive UTC datetimes
- `GET /sessions/` — list with task_ids list + Z-suffixed datetimes for frontend
- `POST /sessions/{id}/stop` — marks done + cancels asyncio Task

### `issued_at` fix
`_dispatch_task` uses `datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")` — the Z suffix is required for `java.time.Instant.parse()` on the APK. Without Z it throws `DateTimeParseException` (silently caught, but logs a warning).

### Datetime timezone — critical rules
- **Store**: always naive UTC (`datetime.utcnow()`) — SQLite has no timezone type
- **Compare**: always `datetime.utcnow()` — aware datetimes (`datetime.now(timezone.utc)`) produce broken SQLite string comparisons because `' '`(32) < `'+'`(43) < `'T'`(84) in ASCII
- **Serialize (API)**: append `Z` suffix so browser treats response as UTC — `dt.isoformat() + "Z"`
- **Parse (frontend)**: `new Date(s + (s.endsWith('Z') ? '' : 'Z'))` then `.toLocaleString()`
- **min/max on datetime-local inputs**: use local time getters (`getFullYear()`, `getHours()`...) — NOT `toISOString() + offset math` (breaks in UTC+ zones)

## Bot-Detection Avoidance

### `SpotifyExecutor.java` — new methods
- **`tapAtNodeRandom(node)`** — gesture-based tap via `dispatchGesture()` with ±15px random offset and 80–200ms random press duration. Falls back to `clickNode()` if rejected. `android:canPerformGestures="true"` already set in service config.
- **`jitteredDelay(baseMs)`** — returns `base ± 35%`. Used on every `handler.postDelayed()` call so step timing is never machine-perfect.
- **`launchAndSettle(Runnable firstStep)`** — launches Spotify, waits 2–3s jittered, calls `dismissOverlays()`, then runs first step. All 9 executors use this instead of raw `launchSpotify()`.
- **`dismissOverlays()`** — delegates to `OverlayGuard.dismiss()`.
- **`skipAdIfPresent()`** — checks for "Skip ad" button and taps it.

### `OverlayGuard.java` — automatic overlay dismissal
- Static helper, single source of truth for all dismissal logic
- `check(svc)` — throttled to once per 800ms, called from `onAccessibilityEvent`
- `dismiss(svc)` — unthrottled, called from executor steps
- Detection order: content-desc → text → resource-id → "Skip ad" → promo cards → "Get Premium" → `pressBack()`
- Called in `SpotifyAccessibilityService.onAccessibilityEvent()` on every `TYPE_WINDOW_STATE_CHANGED` — overlays are dismissed as soon as they appear, not just at step boundaries

### Updated executors
`SearchAndPlayExecutor` and `FollowArtistExecutor` use:
- `launchAndSettle()` instead of raw launch
- `tapAtNodeRandom()` for all UI clicks
- `jitteredDelay()` for all `postDelayed` calls

All other executors use `launchAndSettle()` (overlay dismiss) but retain their existing click and delay patterns.

## Frontend — Run Log Panel

**Always visible** (no longer conditional on `activeRun`). Shows:
- **Idle state**: "Idle — click ▶ Run Now on a task, or wait for a scheduled session to start."
- **Manual Run Now**: step events + status badge + run_id prefix
- **Session auto-run**: step events from `sessionActiveRun` state
- **Both simultaneously**: manual log on top, session log below a divider

### How session runs appear in the log
1. `_dispatch_task` publishes `{type: "session_run", session_id, run_id, action_type, play_duration_s}` via broadcaster
2. Frontend SSE handler sets `sessionActiveRun = {session_id, run_id, action_type, status, events: []}`
3. Subsequent `run_event` and `run_status` SSE events update `sessionActiveRun` if `run_id` matches
4. Session finish (`session_status: done/failed`) clears `sessionActiveRun`

## Frontend — Task Creation Form

Playback tasks (`SEARCH_AND_PLAY`, `PLAY_FROM_ALBUM`, `PLAY_FROM_PLAYLIST`) no longer show a Play duration field.
- `SEARCH_AND_PLAY` and `PLAY_FROM_PLAYLIST` now wait for the track to actually finish (APK-driven)
- `play_duration_s: 86400` is hardcoded in the payload — effectively "play until session ends"
- `PLAY_FROM_ALBUM` uses the timer fallback with `DEFAULT_PLAY_DURATION_S = 86400`

Session datetime inputs:
- No `min` attribute (removed — caused invalid-date errors in UTC+ timezones)
- `max` set to 1 year from today via `maxLocalIso()` to prevent accidental far-future year typos
- Year sanity check in `handleScheduleSession`: shows error if year > current + 1

## APK Architecture

### Package: `com.spotifybot.app`
**Location:** `apk/app/src/main/java/com/spotifybot/app/`

| File | Role |
|------|------|
| `SpotifyAccessibilityService.java` | Core service — auto-dismisses overlays on every `TYPE_WINDOW_STATE_CHANGED` event |
| `OverlayGuard.java` | Static overlay/ad dismissal — used by service (auto) and executors (on-demand) |
| `BotForegroundService.java` | Persistent foreground service — owns WebSocket, CommandRouter, WakeLock, NetworkCallback |
| `BotWebSocketClient.java` | OkHttp WebSocket — DEVICE_HELLO auth, reconnect backoff, heartbeat |
| `CommandRouter.java` | Routes `action_type` → correct Executor class |
| `SpotifyExecutor.java` | Base class — UI helpers, `tapAtNodeRandom`, `jitteredDelay`, `launchAndSettle`, `dismissOverlays`, step watchdog, COMMAND_DONE retry, playback monitor (`startPlaybackMonitor`, `pollPlaybackPosition`, `sendPlaybackFinished`) |
| `AppConfig.java` | Central config — fallback IP, port, DEVICE_ID, DEVICE_SECRET |
| `BackendDiscovery.java` | UDP beacon discovery — finds backend IP dynamically |
| `MainActivity.java` | Launcher — accessibility status, battery exemption request |
| `BootReceiver.java` | Restarts BotForegroundService on BOOT / SCREEN_ON / USER_PRESENT |
| `PlaybackLimits.java` | Hourly/daily play limit tracker |

### Executors (all extend `SpotifyExecutor`)

| Executor | action_type | Key params | Session wait type |
|---|---|---|---|
| `SearchAndPlayExecutor` | SEARCH_AND_PLAY | `query` | PLAYBACK_FINISHED event (APK-driven) |
| `LikeTrackExecutor` | LIKE_CURRENT_TRACK | none | Instant |
| `FollowArtistExecutor` | FOLLOW_ARTIST | `artist_name` | Instant |
| `SkipTrackExecutor` | SKIP_TRACK | none | Instant |
| `PlayFromAlbumExecutor` | PLAY_FROM_ALBUM | `query` | Timer fallback (86400s) |
| `PlayFromPlaylistExecutor` | PLAY_FROM_PLAYLIST | `query` | PLAYBACK_FINISHED event (APK-driven) |
| `FollowPlaylistExecutor` | FOLLOW_PLAYLIST | `query` | Instant |
| `AddToPlaylistExecutor` | ADD_TO_PLAYLIST | `playlist_name` | Instant |
| `CreatePlaylistExecutor` | CREATE_PLAYLIST | `playlist_name` | Instant |

## Key Contracts

### Command payload (backend → APK)
```json
{
  "type": "COMMAND",
  "command_id": "UUID",
  "run_id": "UUID",
  "action_type": "SEARCH_AND_PLAY | FOLLOW_ARTIST | ...",
  "params": { "query": "..." },
  "issued_at": "2026-06-01T12:00:00Z",
  "ttl_ms": 180000
}
```
**Note:** `issued_at` MUST end with `Z` — `java.time.Instant.parse()` on APK requires it.

### Step events (APK → backend)
| Event | When |
|---|---|
| `STEP_STARTED` | Step begins |
| `STEP_OK` | Step succeeded |
| `STEP_FAILED` | Step failed — includes `reason_code` |
| `COMMAND_DONE` | All steps done — includes `final_status` (SUCCESS/FAILED) |
| `PLAYBACK_FINISHED` | Track ended — APK-driven, wakes `_wait_for_playback_finish` |

### SSE events (backend → frontend)
| Event | When | Key fields |
|---|---|---|
| `device_status` | Device connects/disconnects | `device_id`, `status` |
| `run_event` | Each step event | `run_id`, `event_type`, `step_name`, `reason_code` |
| `run_status` | COMMAND_DONE | `run_id`, `status` |
| `session_status` | Session state changes | `session_id`, `status` |
| `session_run` | Session dispatches a command | `session_id`, `run_id`, `action_type` |

### Guardrails
- Step timeout: 15s per step (`STEP_TIMEOUT_MS`)
- Command TTL: 180s default
- COMMAND_DONE retry: every 2s for up to 5 minutes
- Reconnect backoff: 1s → 2s → 4s → 8s → max 30s
- Session consecutive-failure limit: 3 before marking `failed`
- Session playback wait: interruptible every 30s (stop/end_time respected)
- Track-finish fallback: 10 minutes (`TRACK_FINISH_TIMEOUT_S`)

## Samsung Android 14 KNOX — Binding Issues (Solved)

### Root cause
Samsung KNOX only binds accessibility services when the toggle is fired from **Settings UI** with `callingPackage=android`.

### Current survival strategy (layered)
1. **Battery Unrestricted** — set manually by user
2. **Standby bucket ACTIVE** — forced after every `installDebug` via Gradle hook
3. **WakeLock (PARTIAL)** — CPU stays on through screen lock
4. **stopWithTask=false** — service survives app swipe
5. **START_STICKY** — OS restarts if OOM killed
6. **NetworkCallback** — reconnects WebSocket when WiFi returns
7. **BootReceiver** — restarts on BOOT + SCREEN_ON + USER_PRESENT
8. **Rebind notification** — fires heads-up notification on `onDestroy()` → opens Accessibility Settings

### Manual fix if service not bound
```
Settings → Accessibility → Installed apps → SpotifyBot → Toggle OFF → Toggle ON
```

## FollowArtistExecutor — UIAutoDev-Confirmed Logic

### Artist row structure (DO NOT change)
```
id/row_root  (ViewGroup, clickable=TRUE)
  ├── id/artwork
  ├── id/title     text="Drake"
  └── id/subtitle  text="Artist"
```

### Follow button structure (Jetpack Compose)
```
android.view.View (parent)
  [0] android.widget.TextView   text="Follow"    ← find this
  [1] android.widget.Button     clickable=false  ← click this
  [2] android.widget.Button
```

### Why `findFollowTextAllWindows()` not `findByText()`
After tapping the artist row, the artist page loads in a new window while `getRootInActiveWindow()` still returns the search results window. `getWindows()` returns ALL visible windows including the loading page.

### Step delays (all jittered ±35% in current code)
- After `launchAndSettle`: settle handled internally (2–3s jittered)
- After `stepOpenSearch` click: 1.5s base
- After `stepActivateSearchBar`: 2s base
- After `stepTypeArtist`: 2s base
- After `stepTapProfile` click: **6.5s base** (artist page render)
- After `stepTapFollow` miss: 2s retry
- After `performFollowClick`: 2.5s verify

## Gradle — Memory Fix

**Problem:** Build failed with `AllocateHeap` OOM error.
**Fix:** `apk/gradle.properties` — changed from `-Xmx2048m` to:
```
org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8
```

## Known Issues / Pending

| # | Issue | Status |
|---|---|---|
| 1 | E2E test: FOLLOW_ARTIST → COMMAND_DONE + verify log | ⏳ Not yet tested |
| 2 | E2E test: session SEARCH_AND_PLAY → PLAY_FROM_PLAYLIST → SEARCH_AND_PLAY | ⏳ Pending APK reinstall |
| 3 | `WRITE_SECURE_SETTINGS` granted in Gradle hook but unused (REBIND removed) | 🟡 Harmless |
| 4 | Other 7 executors (LikeTrack, SkipTrack, etc.) still use fixed `handler.postDelayed` — not yet updated to `jitteredDelay` | 🟡 Minor |
| 5 | APK not yet reinstalled on device with today's SpotifyExecutor + OverlayGuard changes | ⏳ Pending |

## File Locations Quick Reference

```
C:\Users\Aneeq\Desktop\Sportify\
├── CLAUDE.md                         ← Project rules (must stay at root)
├── QUICK_START.txt                   ← Setup guide
├── skills-lock.json                  ← AI skill config
├── docs\
│   ├── BRAIN.md                      ← THIS FILE
│   ├── HANDOFF.md                    ← Knowledge transfer doc
│   ├── Spotify automation appilot .docx  ← Original spec document
│   ├── spotify_spec.txt              ← Spec text
│   └── ui_dumps\                     ← Spotify UI XML reference dumps
├── backend\
│   ├── main.py                       ← FastAPI entry point + lifespan (scheduler wired here)
│   ├── models\tables.py              ← ORM: users, devices, tasks, runs, run_events, sessions, session_tasks
│   ├── models\schemas.py             ← Pydantic: SessionCreate uses task_ids list
│   ├── routers\sessions.py           ← POST/GET /sessions, POST /sessions/{id}/stop
│   ├── routers\ws.py                 ← WebSocket device handler + SSE event publishing
│   ├── tasks\session_scheduler.py    ← SessionScheduler singleton + per-session asyncio tasks
│   ├── tasks\run_expiry.py           ← Marks stale RUNNING runs as TIMED_OUT
│   ├── tasks\heartbeat.py            ← Marks stale devices offline
│   ├── websocket\manager.py          ← ConnectionManager (device_id → WebSocket)
│   └── websocket\broadcaster.py     ← SSEBroadcaster (in-memory fan-out)
├── frontend\src\
│   ├── pages\DashboardPage.jsx       ← Main dashboard (always-visible Run Log, sessions panel)
│   └── api\index.js                  ← All API calls incl. createSession, listSessions, stopSession
└── apk\app\src\main\java\com\spotifybot\app\
    ├── SpotifyAccessibilityService.java  ← Auto overlay dismiss on TYPE_WINDOW_STATE_CHANGED
    ├── OverlayGuard.java                 ← Centralised overlay/ad dismissal logic
    ├── SpotifyExecutor.java              ← tapAtNodeRandom, jitteredDelay, launchAndSettle, playback monitor
    ├── FollowArtistExecutor.java         ← tapAtNodeRandom + jitteredDelay applied
    ├── SearchAndPlayExecutor.java        ← tapAtNodeRandom + jitteredDelay + startPlaybackMonitor
    ├── PlayFromPlaylistExecutor.java     ← startPlaybackMonitor applied
    ├── [other 6 executors]               ← launchAndSettle applied; delays not yet jittered
    └── res\xml\accessibility_service_config.xml  ← canPerformGestures=true (already set)
```
