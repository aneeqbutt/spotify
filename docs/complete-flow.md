# Spotify Automation Platform — Complete Flow
## From APK Install to Session Execution

---

## Table of Contents

1. [Stage 1 — APK Installation](#stage-1--apk-installation)
2. [Stage 2 — First App Open](#stage-2--first-app-open)
3. [Stage 3 — BotForegroundService Starts](#stage-3--botforegroundservice-starts)
4. [Stage 4 — Accessibility Toggle ON](#stage-4--accessibility-toggle-on)
5. [Stage 5 — Backend Discovery (UDP)](#stage-5--backend-discovery-udp)
6. [Stage 6 — WebSocket Connects](#stage-6--websocket-connects)
7. [Stage 7 — Backend Authenticates Device](#stage-7--backend-authenticates-device)
8. [Stage 8 — APK Receives AUTH_OK](#stage-8--apk-receives-auth_ok)
9. [Stage 9 — Backend Event Loop](#stage-9--backend-event-loop)
10. [Stage 10 — User Creates Tasks](#stage-10--user-creates-tasks)
11. [Stage 11 — User Creates a Session](#stage-11--user-creates-a-session)
12. [Stage 12 — Scheduler Spawns Session Task](#stage-12--scheduler-spawns-session-task)
13. [Stage 13 — Waiting for Start Time](#stage-13--waiting-for-start-time)
14. [Stage 14 — Task Dispatch Loop](#stage-14--task-dispatch-loop)
15. [Stage 15 — APK Receives the Command](#stage-15--apk-receives-the-command)
16. [Stage 16 — CommandRouter Routes to Executor](#stage-16--commandrouter-routes-to-executor)
17. [Stage 17 — Executor Drives Spotify](#stage-17--executor-drives-spotify)
18. [Stage 18 — COMMAND_DONE Returns to Backend](#stage-18--command_done-returns-to-backend)
19. [Stage 19 — Scheduler Sees COMMAND_DONE](#stage-19--scheduler-sees-command_done)
20. [Stage 20 — Track Finishes Playing](#stage-20--track-finishes-playing)
21. [Stage 21 — Session Ends](#stage-21--session-ends)
22. [Complete File Map](#complete-file-map)

---

## Stage 1 — APK Installation

**Trigger:** `adb install -r app-debug.apk`

The moment Gradle finishes the install the `installDebug` hook fires automatically.

**File:** `apk/app/build.gradle.kts` → `grantDebugPermissions(device)`

Runs these adb shell commands in sequence:

```
pm grant com.spotifybot.app android.permission.POST_NOTIFICATIONS
appops set ... ACCESS_RESTRICTED_SETTINGS allow    ← sideload gate lifted
dumpsys deviceidle whitelist +com.spotifybot.app   ← battery whitelist
am set-standby-bucket ... active                   ← Samsung won't throttle
appops set ... RUN_IN_BACKGROUND allow             ← Samsung anti-sleep
appops set ... RUN_ANY_IN_BACKGROUND allow
appops set ... START_FOREGROUND allow
settings put secure accessibility_enabled 0        ← wipe zombie state
settings delete secure enabled_accessibility_services
am start -n .../.MainActivity                      ← launch the app
am start -a android.settings.ACCESSIBILITY_SETTINGS ← open toggle page
```

The app launches. The Accessibility Settings page opens. You toggle SpotifyBot **ON** and confirm **"Allow full control"**.

> **Why `adb install -r` and never uninstall+reinstall:**
> `-r` does an in-place upgrade that keeps the app's data, permissions, and — critically — the user consent token that Samsung requires for the accessibility service to bind. Uninstall wipes the consent, forces you to re-grant everything, and risks triggering the AMS firmware wedge.

---

## Stage 2 — First App Open

**File:** `apk/app/src/main/java/com/spotifybot/app/MainActivity.java` → `onCreate()`

```java
startBotService()      // fires BotForegroundService via startForegroundService()
runSilentSetup()       // calls SetupHelper.runAutoSetup()
```

`SetupHelper.runAutoSetup()` runs silently — no dialogs:

| Call | What it does |
|---|---|
| `ProcessRecovery.ensureComponentEnabled()` | Makes sure the APK component is not disabled in PackageManager |
| `isRestrictedSettingsAllowed()` | Checks `ACCESS_RESTRICTED_SETTINGS` appop — already granted by Gradle |
| `tryRequestBatteryExemption()` | Shows system battery-exempt dialog if not already exempt |

`startBotService()` calls `startForegroundService(Intent → BotForegroundService)`.

---

## Stage 3 — BotForegroundService Starts

**File:** `apk/app/src/main/java/com/spotifybot/app/BotForegroundService.java` → `onCreate()`

Every line runs in order:

```java
serviceInstance = this                     // static ref so other classes can reach it
AppConfig.initDeviceId(this)               // derives hardware-based device ID (never changes)
createNotificationChannel()                // registers Android notification channel
ioExecutor = Executors.newSingleThreadExecutor()
wakeLock.acquire()                         // CPU stays on through screen lock
registerNetworkCallback()                  // reconnects WS the instant WiFi returns
registerAccessibilityObserver()            // ContentObserver on ENABLED_ACCESSIBILITY_SERVICES
registerAccessibilityStateListener()       // AccessibilityManager listener (Android 13+)
healthHandler.postDelayed(accessibilityWatchdog, 30_000)  // watchdog loop every 30s
healthHandler.postDelayed(wsWatchdog, 20_000)              // WS health check every 20s
healthHandler.post(() -> connectWebSocketIfReady())        // immediate first connect attempt
```

`connectWebSocketIfReady()` checks `SpotifyAccessibilityService.instance` — it is `null` at this point so the service **waits**. Notification shows "Waiting for accessibility…".

### Background watchdogs running continuously

**`accessibilityWatchdog`** (every 30s):
- If `instance == null` and WS is authenticated → disconnect WS (zombie state)
- If `instance == null` → call `ensureAccessibilityRecoveryRunning()`
- If `instance != null` → call `connectWebSocketIfReady()`

**`wsWatchdog`** (every 20s):
- If WS authenticated but `lastPongAtMs` is stale (>90s) → `forceReconnect()`
- If WS not authenticated, not connecting, and `isAlive() == false` → recreate client

---

## Stage 4 — Accessibility Toggle ON

You tap the toggle in Settings. Samsung's `AccessibilityManagerService` issues a bind.

**File:** `apk/app/src/main/java/com/spotifybot/app/SpotifyAccessibilityService.java` → `onServiceConnected()`

```java
instance = this   // CRITICAL: set synchronously FIRST — if anything crashes after this
                  // the bind is still recorded; nothing can reverse it
```

Then deferred onto the main looper (so onServiceConnected() returns immediately and the framework commits the bind before we touch anything):

```java
cancelRebindNotification()               // cancels notification IDs 2001 AND 2002 (stale dead-alert)
BotForegroundService.onAccessibilityBound()
startForegroundServiceSafely()
```

`BotForegroundService.onAccessibilityBound()`:

```java
zombieNotified = false
zombieRestartAttempted = false
bindingCheckScheduled = false
healthHandler.removeCallbacks(bindingCheckAfterSettings)
connectWebSocketIfReady()    // accessibility is now bound — proceed to WS
```

---

## Stage 5 — Backend Discovery (UDP)

**File:** `BotForegroundService.java` → `connectWebSocketIfReady()`

`instance != null` — passes the gate. Creates:

```java
commandRouter = new CommandRouter(this)
wsClient      = new BotWebSocketClient(this, this)
currentWs     = wsClient
wsClient.connect()
```

**File:** `BotWebSocketClient.java` → `connect()`

Fires a background thread:

```java
discoveryExecutor.execute(() -> {
    host = BackendDiscovery.discoverFast(context)
    if (host == null) host = BackendDiscovery.discover(context)
    ...
})
```

**File:** `BackendDiscovery.java` → `discoverFast(context)`

Checks in priority order:

1. `loadHost(context)` — SharedPreferences last known host → `canReachTcp(saved, 8000)` — opens a 2s TCP socket. If reachable → returns immediately
2. `canReachTcp("127.0.0.1", 8000)` — ADB reverse tunnel test
3. `tryUdpOnce(2_000)` — opens UDP socket on port 8765, waits 2s for beacon

If `discoverFast` returns null → `discover(context)`:

```java
for (int attempt = 1; attempt <= 3; attempt++) {
    result = tryUdpOnce(5_000)   // 5s window per attempt, 3 attempts max = 15s total
    if (result != null) break
}
```

**`tryUdpOnce()`** — opens `DatagramSocket(8765)`, calls `socket.receive(packet)`. Backend broadcasts every 5s:

```json
{"service": "sportify-backend", "port": 8000, "boot_id": "abc123"}
```

The sender's IP is extracted from `packet.getAddress()`. The `boot_id` is checked against SharedPreferences — if it changed, the backend restarted and the old saved host is cleared. Both `host` and `boot_id` are saved.

Returns `"192.168.100.5"`.

### Discovery priority summary

| Priority | Method | Condition |
|---|---|---|
| 1 | Saved host + TCP probe | Known IP, backend still reachable |
| 2 | ADB reverse tunnel | `127.0.0.1:8000` responds |
| 3 | Live UDP beacon | Backend broadcasting on LAN |
| 4 | Saved host (no probe) | UDP timed out, use last known |
| 5 | null | Nothing reachable — retry in 15s |

---

## Stage 6 — WebSocket Connects

**File:** `BotWebSocketClient.java` → `doConnect()`

```java
connectionGeneration++        // invalidates any stale socket callbacks from old connections
url = "ws://192.168.100.5:8000/ws/samsung-sm-g990e-abc123"
wifiNetwork = getWifiNetwork() // binds to WiFi — avoids USB ADB route when cable is plugged in
socket = httpClient.newWebSocket(request, new BotWebSocketListener(myGeneration))
```

OkHttp upgrades the TCP connection to WebSocket (HTTP 101 Switching Protocols).

**`BotWebSocketListener.onOpen()`** fires:

```java
isConnecting.set(false)
// Sends DEVICE_HELLO:
{
  "type":          "DEVICE_HELLO",
  "device_id":     "samsung-sm-g990e-abc123",
  "shared_secret": "spotifybot-secret-2024",
  "app_version":   "1.2",
  "capabilities":  {"SEARCH_AND_PLAY": true, "LIKE_CURRENT_TRACK": true, ...}
}
```

---

## Stage 7 — Backend Authenticates Device

**File:** `backend/routers/ws.py` → `websocket_endpoint()`

```python
await websocket.accept()
raw  = await websocket.receive_text()   # blocks until DEVICE_HELLO arrives
hello = json.loads(raw)
```

Checks `hello.get("type") == "DEVICE_HELLO"`.

Queries SQLite: `SELECT * FROM devices WHERE device_id = ?`

**First time (new device):** row doesn't exist → verifies `shared_secret` against `settings.DEVICE_SHARED_SECRET` → auto-registers:

```python
device = Device(
    device_id         = "samsung-sm-g990e-abc123",
    device_auth_token = settings.DEVICE_SHARED_SECRET,
    status            = "online",
    app_version       = "1.2",
    capabilities      = json.dumps({...}),
    last_seen         = datetime.now(timezone.utc),
)
db.add(device)
await db.commit()
```

**Known device:** verifies `shared_secret == device.device_auth_token`.

Then registers in the connection manager:

**File:** `backend/websocket/manager.py` → `ConnectionManager.connect()`

```python
self._connections[device_id] = websocket   # stored — enables send_command() later
device.status   = "online"
device.last_seen = now
await db.commit()
await broadcaster.publish({"type": "device_status", "device_id": ..., "status": "online"})
```

The SSE broadcast flips the dashboard device badge from red to **green** instantly.

Backend sends `AUTH_OK`:

```python
await websocket.send_text(json.dumps({
    "type":      "AUTH_OK",
    "device_id": device_id,
    "boot_id":   _SERVER_BOOT_ID,
}))
```

---

## Stage 8 — APK Receives AUTH_OK

**File:** `BotWebSocketClient.java` → `BotWebSocketListener.onMessage()` → `case "AUTH_OK":`

```java
isAuthenticated   = true
lastPongAtMs      = System.currentTimeMillis()
reconnectDelayMs  = RECONNECT_INITIAL_MS    // reset backoff to 1s
reconnectAttempts = 0
consecutiveFailures = 0
BackendDiscovery.saveBootIdFromAuth(context, bootId)
mainHandler.post(() -> {
    listener.onConnected()       // → BotForegroundService → notification: "Connected — waiting for commands"
    startHeartbeat(ws)
})
```

**`startHeartbeat(ws)`:**
- Creates `HandlerThread("SpotifyBot-Heartbeat")` — dedicated thread, NOT the main looper
- Schedules `pingRunnable` every **15,000ms**
- Every 15s: sends `{"type": "PING", "device_id": "..."}` over the socket
- If PONG age exceeds 90s → calls `forceReconnect()`

**The device is now ONLINE, visible in the dashboard, and ready to receive commands.**

---

## Stage 9 — Backend Event Loop (stays open forever)

**File:** `backend/routers/ws.py` → `websocket_endpoint()` event loop

```python
while True:
    raw = await asyncio.wait_for(websocket.receive_text(), timeout=120.0)
    # 120s timeout — APK sends PING every 15s so this only fires on a hard crash
    msg = json.loads(raw)
    device.last_seen = now
    await db.commit()

    if msg_type == "PING":
        await websocket.send_text(json.dumps({"type": "PONG", ...}))

    elif msg_type == "PLAYBACK_FINISHED":
        notify_playback_finished(run_id)   # wakes session scheduler

    elif msg_type in ("STEP_STARTED", "STEP_OK", "STEP_FAILED", "COMMAND_DONE"):
        db.add(RunEvent(...))
        if msg_type == "COMMAND_DONE":
            run.status   = final_status
            run.end_time = now
        await db.commit()
        await broadcaster.publish({"type": "run_event", ...})
        await broadcaster.publish({"type": "run_status", ...})  # on COMMAND_DONE only
```

Every incoming message — including PING — refreshes `device.last_seen`. The heartbeat task reads this column and marks the device offline if it goes stale for >60s.

---

## Stage 10 — User Creates Tasks

**File:** `frontend/src/pages/DashboardPage.jsx` → `handleCreateTask()`

Calls `createTask({task_name, action_type, search_query, action_params})` → `POST /tasks`

Backend writes a `Task` row to SQLite:

```
id | user_id | task_name              | action_type     | search_query      | action_params
1  | 1       | "Play Blinding Lights" | SEARCH_AND_PLAY | "Blinding Lights" | {"play_duration_s":86400}
2  | 1       | "Like Track"           | LIKE_CURRENT_TRACK | null            | null
3  | 1       | "Follow Drake"         | FOLLOW_ARTIST   | "Drake"           | null
```

---

## Stage 11 — User Creates a Session

**File:** `DashboardPage.jsx` → `handleScheduleSession()`

Calls `createSession({device_id, task_ids:[1,2,3], start_time, end_time})` → `POST /sessions`

**File:** `backend/routers/sessions.py` → `create_session()`

Writes to SQLite:

```
sessions table:
  id=5, device_id="samsung-sm-g990e-abc123", status="scheduled",
  start_time="2026-06-12T10:00:00Z", end_time="2026-06-12T11:00:00Z"

session_tasks table:
  (session_id=5, task_id=1, position=0)
  (session_id=5, task_id=2, position=1)
  (session_id=5, task_id=3, position=2)
```

Immediately calls `scheduler.schedule(5)` — does not wait for the background scan.

---

## Stage 12 — Scheduler Spawns Session Task

**File:** `session_scheduler.py` → `SessionScheduler.schedule(session_id=5)`

```python
task = asyncio.create_task(
    _run_session_task(5, engine),
    name="session_5"
)
self._tasks[5] = task
```

An asyncio coroutine starts running **concurrently** alongside the web server. It shares the same event loop — no threads, no blocking.

---

## Stage 13 — Waiting for Start Time

**File:** `session_scheduler.py` → `_run_session_task(session_id=5, engine)`

```python
sess = await _load_session(5, engine)        # SELECT * FROM sessions WHERE id=5
wait_s = (start_time - datetime.utcnow()).total_seconds()
await asyncio.sleep(wait_s)                  # exact start — one clean sleep, no poll loop
```

When it wakes:

```python
await _set_session_status(5, "running", engine)
await broadcaster.publish({"type": "session_status", "session_id": 5, "status": "running"})
```

Dashboard receives the SSE → session badge turns **yellow "running"**.

---

## Stage 14 — Task Dispatch Loop

```python
task_ids = await _load_task_sequence(5, engine)
# → reads SessionTask rows ordered by position → [1, 2, 3]
```

### The Lazy Playback Wait Model

After a PLAYBACK task (SEARCH_AND_PLAY, PLAY_FROM_ALBUM, PLAY_FROM_PLAYLIST) the scheduler does **not** block immediately. It stores the `run_id` as `pending_playback` and continues to the next task.

Before dispatching the **next PLAYBACK task**, it waits for the stored `PLAYBACK_FINISHED`. Instant tasks (LIKE, SKIP, FOLLOW) execute mid-song with a 5–10s gap.

```
play "Blinding Lights"  ─────────────────────────────▶ (playing…)
                              ├── like (5-10s in)
                              ├── follow Drake (5-10s after like)
                              └── wait for "Blinding Lights" to finish
play "Starboy"  ◀─── dispatched only after Blinding Lights ends
                              ├── skip (5-10s in)
                              └── wait for "Starboy" to finish
cooldown (30-90s)
next iteration…
```

### Per-task: `_dispatch_task(session_id, task_id, device_id, engine)`

```python
task = SELECT * FROM tasks WHERE id=task_id
run_id = uuid4()                           # e.g. "f3a9..."
_run_to_session["f3a9..."] = 5            # maps run → session for SSE tagging
Run row inserted: run_id, task_id, status="RUNNING", start_time=now

params = json.loads(task.action_params)    # {"play_duration_s": 86400}
params["query"] = task.search_query        # "Blinding Lights"

command_payload = {
    "type":        "COMMAND",
    "command_id":  uuid4(),
    "run_id":      "f3a9...",
    "action_type": "SEARCH_AND_PLAY",
    "params":      {"query": "Blinding Lights", "play_duration_s": 86400},
    "issued_at":   "2026-06-12T10:00:00Z",
    "ttl_ms":      180000
}

await manager.send_command(device_id, command_payload)
# → websocket.send_text(json.dumps(command_payload))
```

Broadcasts `session_run` SSE → dashboard wires up the run log panel for this task.

---

## Stage 15 — APK Receives the Command

**File:** `BotWebSocketClient.java` → `BotWebSocketListener.onMessage()` → `case "COMMAND":`

```java
mainHandler.postAtFrontOfQueue(() -> listener.onCommandReceived(msg))
// postAtFrontOfQueue jumps ahead of accessibility event flood
// so the command isn't delayed by Spotify UI events
```

**File:** `BotForegroundService.java` → `onCommandReceived(msg)`

```java
commandRouter.execute(msg, wsClient)
```

---

## Stage 16 — CommandRouter Routes to Executor

**File:** `CommandRouter.java` → `execute(JsonObject command, BotWebSocketClient wsClient)`

**TTL check:**
```java
issued_at = Instant.parse("2026-06-12T10:00:00Z")
age_ms    = Instant.now().toEpochMilli() - issued_at.toEpochMilli()
// e.g. 120ms — well within 180,000ms ttl
```

If `age_ms > ttl_ms` → sends `COMMAND_EXPIRED` back and returns immediately.

**Route:**
```java
switch (actionType) {
    case "SEARCH_AND_PLAY":
        executor = new SearchAndPlayExecutor()
        break
    // LIKE_CURRENT_TRACK → LikeTrackExecutor
    // FOLLOW_ARTIST      → FollowArtistExecutor
    // SKIP_TRACK         → SkipTrackExecutor
    // PLAY_FROM_ALBUM    → PlayFromAlbumExecutor
    // PLAY_FROM_PLAYLIST → PlayFromPlaylistExecutor
    // FOLLOW_PLAYLIST    → FollowPlaylistExecutor
    // CREATE_PLAYLIST    → CreatePlaylistExecutor
}
executor.execute(run_id, command_id, params, wsClient)
```

---

## Stage 17 — Executor Drives Spotify

**File:** `SpotifyExecutor.java` → `execute()` → `launchAndSettle(this::doExecute)`

`launchAndSettle()` fires an Intent to launch Spotify, then polls `getRootInActiveWindow()` every 300ms until the Spotify package appears in the accessibility window hierarchy (≤ 3s typical). Once settled it calls `doExecute(params)`.

**File:** `SearchAndPlayExecutor.java` → `doExecute(params)`

```java
query        = "Blinding Lights"
playlistName = ""    // no playlist for this run
launchAndSettle(this::stepOpenSearch)
```

Each step is scheduled via `handler.postDelayed()` on the main thread — non-blocking.

### Step chain

| Step | Function | What happens |
|---|---|---|
| 1 | `stepOpenSearch()` | Finds Search tab by content-desc → `performAction(ACTION_CLICK)` |
| 2 | `stepTypeQuery()` | Finds EditText via `findSearchInput()` → `typeText(input, "Blinding Lights")` |
| 3 | `stepSubmitSearch()` | `performAction(0x01000000)` = `ACTION_IME_ENTER` → shows full results page |
| 3b | `stepTapSongsTab()` | `findChipLabelNode("Songs")` → taps → polls until `isFilterChipSelected()` |
| 4 | `stepTapResult()` | `findSongRow()` → `id/row_root` (clickable=false but ACTION_CLICK works) → `tapAtNodeRandom()` uses **bounds** coordinates for gesture |
| 4b | `stepTapPlayIfPaused()` | Checks Pause/Play button, taps Play if paused |
| 5 | `stepVerifyPlayback()` | Confirms `id/now_playing_bar_layout` visible |

After step 5 — if `playlistName` is empty:

```java
String monitorRunId = runId
commandDone(true)               // sends COMMAND_DONE
startPlaybackMonitor(monitorRunId)
```

If `playlistName` is set — chains steps 6–10: open full player → 3-dot menu → "Add to playlist" → select playlist → verify.

### How every step sends events

`SpotifyExecutor.stepStarted()`:
```java
wsClient.send({"type": "STEP_STARTED", "run_id": "f3a9...", "step_id": "step_tap_result", "step_name": "Tap Song Result"})
```

`SpotifyExecutor.stepOk()`:
```java
wsClient.send({"type": "STEP_OK", "run_id": "f3a9...", "step_id": "step_tap_result", "step_name": "Tap Song Result"})
```

Each event is persisted to `run_events` table and broadcast as SSE → dashboard live log panel appends a new line.

### How bounds are used for clicking

When a Compose node reports `isClickable=false` (Spotify's `row_root`, dismiss buttons, etc.) `performAction(ACTION_CLICK)` fails. The executor reads the node's bounds:

```java
Rect bounds = new Rect()
node.getBoundsInScreen(bounds)       // e.g. [138,450][1038,560]
// center: x=588, y=505
dispatchGesture(GestureDescription)  // real finger touch at random point inside rectangle
```

This bypasses the `isClickable` gate entirely.

---

## Stage 18 — COMMAND_DONE Returns to Backend

**File:** `SpotifyExecutor.java` → `commandDone(true)`

```java
wsClient.send({
    "type":         "COMMAND_DONE",
    "run_id":       "f3a9...",
    "command_id":   "...",
    "final_status": "SUCCESS"
})
```

**File:** `backend/routers/ws.py` → event loop receives `COMMAND_DONE`:

```python
run.status   = "SUCCESS"
run.end_time = now
await db.commit()
await broadcaster.publish({"type": "run_status",  "run_id": "f3a9...", "status": "SUCCESS"})
await broadcaster.publish({"type": "run_event",   "event_type": "COMMAND_DONE", ...})
```

Dashboard receives the SSE → run badge turns **green**.

---

## Stage 19 — Scheduler Sees COMMAND_DONE

Back in `_run_session_task()` → `_wait_for_run(run_id, engine)`:

```python
# Polls DB every 5s:
while datetime.now(timezone.utc) < deadline:
    await asyncio.sleep(5)
    run = SELECT * FROM runs WHERE run_id = "f3a9..."
    if run.status != "RUNNING":
        return run.status   # returns "SUCCESS"
```

`SEARCH_AND_PLAY` is a PLAYBACK action — lazy model:

```python
pending_playback_run_id = "f3a9..."
pending_playback_action = "SEARCH_AND_PLAY"
# Don't wait yet — dispatch instant tasks first (LIKE, SKIP, etc.)
```

**Instant tasks (LIKE/SKIP/FOLLOW):** execute with a 5–10s random gap between them, mid-playback.

**Next PLAYBACK task:** before dispatching, calls `_wait_for_playback_finish()`.

---

## Stage 20 — Track Finishes Playing

**File:** `SpotifyExecutor.java` → `startPlaybackMonitor(runId)` → `onTrackChangedByEvent()`

The APK watches `TYPE_WINDOW_CONTENT_CHANGED` accessibility events. When the track title text changes (new song started = previous song done), after a 10s stabilization window:

```java
wsClient.send({"type": "PLAYBACK_FINISHED", "run_id": "f3a9..."})
```

**File:** `backend/routers/ws.py` → receives `PLAYBACK_FINISHED`:

```python
notify_playback_finished("f3a9...")
```

**File:** `session_scheduler.py` → `notify_playback_finished("f3a9...")`:

```python
event = _playback_events["f3a9..."]
event.set()    # asyncio.Event — wakes _wait_for_playback_finish()
```

The scheduler was sleeping in `_wait_for_playback_finish()`. It wakes, returns `True`, and dispatches the next PLAYBACK task.

### Playback wait timeout

`_wait_for_playback_finish()` doesn't wait forever. It computes:

```python
remaining_s  = (session.end_time - now).total_seconds()
fair_share   = (remaining_s - 30) / tasks_remaining
timeout      = min(270s_hard_cap, fair_share)
```

If `PLAYBACK_FINISHED` never arrives (phone screen off, Samsung throttled the monitor), the scheduler moves on after the timeout — the session doesn't stall.

---

## Stage 21 — Session Ends

When `datetime.utcnow() >= end_time` (checked at the top of every iteration):

```python
_set_session_status(5, "done", engine)
await broadcaster.publish({"type": "session_status", "session_id": 5, "status": "done"})
_cleanup_run_to_session(5)   # removes all run_id → session_id entries from memory
```

Dashboard badge turns **grey "done"**. The asyncio coroutine exits cleanly.

The phone's WebSocket stays open. The heartbeat keeps pulsing. The device stays **online** — ready for the next session.

### Inter-iteration cooldown

Before repeating the task sequence the scheduler sleeps a random cooldown:

```python
cooldown = random.randint(30, 90)
await asyncio.sleep(cooldown)
```

Then loops back to the top of `while True` and runs the full task list again until `end_time`.

---

## Complete File Map

```
apk/app/build.gradle.kts
  └── grantDebugPermissions()              install-time ADB grants

MainActivity.java
  └── onCreate()                           app entry point, starts FGS

BotForegroundService.java
  ├── onCreate()                           service setup, watchdogs, wakelock
  ├── connectWebSocketIfReady()            gated on accessibility bind
  ├── onAccessibilityBound()              called by SpotifyAccessibilityService
  ├── accessibilityWatchdog               30s loop — monitors bind state
  └── wsWatchdog                          20s loop — monitors WS health

SpotifyAccessibilityService.java
  └── onServiceConnected()                accessibility bind — sets instance

BackendDiscovery.java
  ├── discoverFast()                      fast path — saved host + TCP probe
  ├── discover()                          full UDP scan (up to 15s)
  └── tryUdpOnce()                        single 5s UDP beacon listen

BotWebSocketClient.java
  ├── connect()                           entry point — kicks off discovery
  ├── doConnect()                         opens OkHttp WebSocket
  ├── BotWebSocketListener.onOpen()       sends DEVICE_HELLO
  ├── BotWebSocketListener.onMessage()    handles AUTH_OK / PONG / COMMAND
  ├── startHeartbeat()                    15s PING on dedicated thread
  ├── disconnect()                        intentional close + stopHeartbeat()
  ├── forceReconnect()                    drops stale socket, reconnects fast
  └── isAlive()                           !intentionalClose — guards mid-backoff

backend/routers/ws.py
  └── websocket_endpoint()                accept → DEVICE_HELLO → AUTH_OK → event loop

backend/websocket/manager.py
  ├── connect()                           registers device, marks online, broadcasts SSE
  └── send_command()                      websocket.send_text(json)

backend/websocket/broadcaster.py
  └── publish()                           SSE to all open dashboard tabs

frontend/src/pages/DashboardPage.jsx
  ├── handleCreateTask()                  POST /tasks
  └── handleScheduleSession()            POST /sessions

backend/routers/sessions.py
  └── create_session()                    writes Session + SessionTask rows, calls scheduler.schedule()

session_scheduler.py
  ├── SessionScheduler.schedule()         asyncio.create_task(_run_session_task)
  ├── _run_session_task()                 main session loop (while True)
  ├── _dispatch_task()                    builds + sends COMMAND, writes Run row
  ├── _wait_for_run()                     polls DB every 5s for COMMAND_DONE
  ├── _wait_for_playback_finish()         asyncio.Event wait for track end
  ├── notify_playback_finished()          called by ws.py → sets asyncio.Event
  └── _interruptible_sleep()             sleep that checks session end_time every 30s

CommandRouter.java
  └── execute()                           TTL check → route → executor.execute()

SearchAndPlayExecutor.java
  ├── doExecute()                         reads query + playlist_name
  ├── stepOpenSearch()                    taps Search tab
  ├── stepTypeQuery()                     types query into EditText
  ├── stepSubmitSearch()                  ACTION_IME_ENTER
  ├── stepTapSongsTab()                   finds + taps Songs filter chip
  ├── stepTapResult()                     findSongRow() → tapAtNodeRandom() via bounds
  ├── stepVerifyPlayback()                confirms Now Playing bar visible
  └── (steps 6-10)                        add-to-playlist chain if playlist_name set

SpotifyExecutor.java
  ├── stepStarted() / stepOk() / stepFailed()   send STEP_* events over WS
  ├── commandDone()                       sends COMMAND_DONE
  └── startPlaybackMonitor()              watches track title changes → PLAYBACK_FINISHED
```

---

## Key Numbers

| Parameter | Value | Why |
|---|---|---|
| UDP beacon interval | 5s | Backend broadcasts every 5s — 3 attempts guarantees a hit |
| Max discovery time | 15s | 3 × 5s UDP attempts |
| WS reconnect backoff | 1s → 2s → 4s → … → 30s max | Exponential, resets on AUTH_OK |
| PING interval | 15s | Keeps `last_seen` fresh; detects dead sockets before 90s PONG timeout |
| PONG stale threshold | 90s | ~6 missed PING cycles — graceful before force-reconnect |
| Step timeout | 10–20s | Each Spotify UI step gets its own timer; timeout → STEP_FAILED |
| Command TTL | 180s (3 min) | Commands older than this are expired on arrival |
| Run wait timeout | 180s | Backend waits this long for COMMAND_DONE before marking TIMED_OUT |
| Track finish timeout | 270s | Hard cap per song; fair-share may be shorter |
| Session cooldown | 30–90s random | Between iterations — human-like pacing |
| Instant task gap | 5–10s random | LIKE/SKIP fire mid-playback — not too fast, not too slow |
| Consecutive failure limit | 5 | Session aborts after 5 back-to-back command failures |
| Offline wait | up to 300s | Device offline is a pause, not a failure |
