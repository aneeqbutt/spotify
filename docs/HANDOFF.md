# Spotify Automation Platform — Session Handoff

> Read this at the start of every new session before doing anything.
> Last updated: 2026-05-24

---

## Project Overview

Android bot that controls the Spotify app on a Samsung S21 FE via Accessibility Service.
Commands are triggered from a React dashboard → FastAPI backend → WebSocket → APK → Spotify UI automation.

| Layer | Tech | Location |
|---|---|---|
| Frontend | React 18 + Vite + Axios | `frontend/` |
| Backend | FastAPI + SQLite + JWT | `backend/` |
| Device | Android APK (Java JDK 8) | `apk/` |
| Target | Spotify Android app | Accessibility Service |

---

## Current Status — ALL PHASES COMPLETE ✅

| Phase | Description | Status |
|---|---|---|
| Phase 1 | JWT auth + SQLite + WebSocket handler | ✅ Done |
| Phase 2 | React dashboard + task form + Run Now | ✅ Done |
| Phase 3 | APK WebSocket + DEVICE_HELLO + reconnect | ✅ Done |
| Phase 4 | Spotify Executors (search, play, like, follow, skip) | ✅ Done |
| Phase 5 | Stability hardening (timeouts, reconnect cap, expiry, heartbeat) | ✅ Done |

### Next task (pick up here)
**End-to-end test** — verify full flow works after APK reinstall:
1. Enable Accessibility Service on phone (was reset by reinstall)
2. Set battery to Unrestricted for SpotifyBot
3. Confirm device shows ONLINE in dashboard
4. Run SEARCH_AND_PLAY from dashboard — watch step events appear
5. Test remaining actions: LIKE_CURRENT_TRACK, FOLLOW_ARTIST, SKIP_TRACK

---

## How to Start Everything

### Backend
```powershell
cd C:\Users\Aneeq\Desktop\Sportify\backend
.venv\Scripts\Activate.ps1
cd ..
python -m uvicorn backend.main:app --reload --host 0.0.0.0 --port 8000
```

### Frontend
```powershell
cd C:\Users\Aneeq\Desktop\Sportify\frontend
npm run dev
```
Opens at: http://localhost:5173

### Check backend health
```
http://localhost:8000/health
```

---

## APK — Key Facts

- **Device:** Samsung Galaxy S21 FE
- **ADB Device ID:** `R5CT126FRTY`
- **APK path:** `apk/app/build/outputs/apk/debug/app-debug.apk`
- **Last built:** 2026-05-24 14:55 (includes ALL Phase 4 + 5 fixes)
- **Last installed:** 2026-05-24 (this session — reinstalled successfully)

### Build APK (Gradle)
```powershell
cd C:\Users\Aneeq\Desktop\Sportify\apk
.\gradlew assembleDebug
```

### Install APK via ADB
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "C:\Users\Aneeq\Desktop\Sportify\apk\app\build\outputs\apk\debug\app-debug.apk"
```

### Watch logs live
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -s SpotifyBot
```

---

## Configuration

### `apk/app/src/main/java/com/spotifybot/app/AppConfig.java`
```java
BACKEND_HOST  = "192.168.1.4"   // PC's LAN IP — update if IP changes
BACKEND_PORT  = 8000
DEVICE_ID     = "samsung-s21fe-001"
DEVICE_SECRET = "change_this_device_secret"   // must match .env
```

### `backend/.env`
```
DEVICE_SHARED_SECRET=change_this_device_secret
JWT_SECRET_KEY=<your_secret>
```

---

## Architecture — Key Files

### Backend
```
backend/
  main.py                  — FastAPI app, lifespan, background tasks startup
  routers/
    auth.py                — /auth/login, /auth/register, /auth/me (JWT)
    devices.py             — /devices/register, /devices/
    tasks.py               — /tasks/ CRUD
    commands.py            — /commands/run (triggers Run Now), /commands/runs
    ws.py                  — /ws/{device_id} WebSocket endpoint
  models/
    tables.py              — SQLAlchemy models: User, Device, Task, Run, RunEvent, Session
    schemas.py             — Pydantic request/response schemas
  database/
    db.py                  — Async SQLite engine, get_db dependency, init_db()
  tasks/
    heartbeat.py           — Marks devices offline if last_seen > 60s ago (runs every 60s)
    run_expiry.py          — Marks RUNNING runs TIMED_OUT if start_time > 300s ago (runs every 60s)
  websocket/
    manager.py             — ConnectionManager: connect(), disconnect(), send_to_device()
  auth/
    jwt.py                 — JWT encode/decode helpers
```

### APK
```
apk/app/src/main/java/com/spotifybot/app/
  AppConfig.java                — Backend URL, device ID, secret, reconnect policy
  MainActivity.java             — Launch screen, service start button
  SpotifyAccessibilityService.java — Accessibility service, auto-starts BotForegroundService
  BotForegroundService.java     — Foreground service: owns WebSocket, hands commands to router
  BotWebSocketClient.java       — WebSocket client: DEVICE_HELLO, AUTH_OK, heartbeat, reconnect
  CommandRouter.java            — Routes incoming COMMAND to correct executor
  SpotifyExecutor.java          — Base class: UI helpers, step events, timeout watchdog
  SearchAndPlayExecutor.java    — SEARCH_AND_PLAY action
  LikeTrackExecutor.java        — LIKE_CURRENT_TRACK action
  FollowArtistExecutor.java     — FOLLOW_ARTIST action
  SkipTrackExecutor.java        — SKIP_TRACK action
```

### Frontend
```
frontend/src/
  pages/
    LoginPage.jsx          — Login / register
    DashboardPage.jsx      — Main dashboard: devices, tasks, Run Now, run log
  api/
    axios.js               — Axios instance with JWT auth header
```

---

## Database Schema (SQLite)

| Table | Key Columns |
|---|---|
| `users` | id, username, password_hash |
| `devices` | device_id (PK), device_auth_token, status (online/offline), last_seen, capabilities |
| `tasks` | id, user_id, task_name, action_type, search_query |
| `runs` | run_id (UUID), task_id, device_id, status (RUNNING/SUCCESS/FAILED/TIMED_OUT), start_time, end_time |
| `run_events` | id, run_id, step_id, step_name, event_type, reason_code, timestamp |
| `sessions` | id, user_id, device_id, task_id, start_time, end_time, status |

---

## WebSocket Protocol

### APK → Backend
| Message | When |
|---|---|
| `DEVICE_HELLO` | First message after connect — carries shared_secret, capabilities |
| `PING` | Every 30s — keeps last_seen fresh |
| `STEP_STARTED` | Step begins |
| `STEP_OK` | Step succeeds |
| `STEP_FAILED` | Step fails — includes reason_code |
| `COMMAND_DONE` | All steps complete — includes final_status (SUCCESS/FAILED) |

### Backend → APK
| Message | When |
|---|---|
| `AUTH_OK` | DEVICE_HELLO verified |
| `AUTH_FAILED` | Bad secret or unregistered device |
| `COMMAND` | Dashboard triggers Run Now |

### Command payload
```json
{
  "type": "COMMAND",
  "command_id": "UUID",
  "run_id": "UUID",
  "task_id": 1,
  "action_type": "SEARCH_AND_PLAY",
  "params": { "query": "Blinding Lights" },
  "issued_at": "2026-05-24T14:00:00Z",
  "ttl_ms": 180000
}
```

---

## Executor Step Chains

### SEARCH_AND_PLAY
```
launchSpotify() → 2s delay
→ stepOpenSearch()        — click Search tab (findById "search_tab" first)
→ 1500ms delay
→ stepActivateSearchBar() — tap inactive placeholder to reveal EditText
→ 800ms delay
→ stepTypeQuery()         — ACTION_SET_TEXT into search_edittext
→ 2500ms delay
→ stepTapResult()         — findByDesc("Song") or "Track" (skips Top Result card)
→ 3000ms delay
→ stepTapPlayIfPaused()   — tap Play button if track opened paused
→ 1500ms delay
→ stepVerifyPlayback()    — check now_playing_bar or pause_button (soft success)
```

### SKIP_TRACK
```
launchSpotify() → 2s delay
→ stepFindSkip()          — findByDesc("Next track")
→ stepTapSkip()           — click it
→ 1500ms delay
→ stepVerifySkip()        — check now_playing_bar (soft success)
```

### LIKE_CURRENT_TRACK
```
launchSpotify() → 2s delay
→ stepFindLike()          — findByDesc("Add to liked songs") or "Like"
→ stepTapLike()           — click it
→ 1500ms delay
→ stepVerifyLike()        — check liked/saved state (soft success)
```

### FOLLOW_ARTIST
```
launchSpotify() → 2s delay
→ stepOpenSearch()
→ stepActivateSearchBar()
→ stepTypeQuery()         — uses params.artist_name (or params.query as fallback)
→ stepTapArtistResult()   — findByDesc("Artist") or "Profile"
→ stepTapFollow()         — findByDesc("Follow")
→ stepVerifyFollow()      — soft success
```

---

## Bugs Fixed in This Session

| Bug | Root Cause | Fix |
|---|---|---|
| Device shows OFFLINE despite connected | `ws.py` DEVICE_HELLO never set `status="online"` or stamped `last_seen` | Added `device.status = "online"; device.last_seen = now` in auth section |
| CLICK_ACTION_REJECTED on search tab | `findByDesc("Search")` matched keyboard IME button (windowType=2) | Use `findById("search_tab")` resource-id first (Spotify-specific) |
| UI_ELEMENT_NOT_FOUND for search input | Spotify search bar is inactive placeholder until tapped | Added `stepActivateSearchBar()` intermediate step |
| Wrong song tapped | `stepTapResult` hit "Top result" card (album/playlist) | Look for `findByDesc("Song")` / `findByDesc("Track")` first |
| Song opened paused | Spotify opens tracks paused in some contexts | Added `stepTapPlayIfPaused()` step |
| FOLLOW_ARTIST always MISSING_PARAM | Backend sent `params.query` but executor expected `params.artist_name` | Fixed in `commands.py` + executor accepts both keys |
| SpotifyBot disconnects | Samsung battery optimizer kills foreground service | `START_STICKY` + manual Battery → Unrestricted in phone settings |

---

## Critical Phone Setup (after any APK reinstall)

Android resets these on every reinstall — must redo them:

1. **Enable Accessibility Service:**
   Settings → Accessibility → Downloaded apps → SpotifyBot → ON → Allow

2. **Disable battery optimization:**
   Settings → Apps → SpotifyBot → Battery → Unrestricted

3. **Verify connection in logcat:**
   ```
   [WS] DEVICE_HELLO sent
   [WS] AUTH_OK — device authenticated and ready
   [WS] Heartbeat started (every 30s)
   ```

4. **Verify device shows ONLINE in dashboard** at http://localhost:5173

---

## Common Commands

```powershell
# Check connected ADB devices
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices

# Check if accessibility service is enabled
& $adb shell settings get secure enabled_accessibility_services

# Force-stop and relaunch app
& $adb shell am force-stop com.spotifybot.app
& $adb shell am start -n com.spotifybot.app/.MainActivity

# Kill whatever is on port 8000
netstat -ano | findstr ":8000"
taskkill /PID <PID> /F

# Find PC's LAN IP (update AppConfig.java if changed)
ipconfig
```

---

## Known Limitations / Future Work

- No live step event streaming to dashboard UI (events saved to DB but not pushed via SSE/polling)
- Scheduler UI not implemented (Sessions table exists in DB but no frontend for it)
- ADB wireless pairing (Android 11+): pair on one port, connect on a different port — check `adb pair` vs `adb connect`
- Resource IDs in Spotify may change across app versions — fallback chain in each executor handles this
