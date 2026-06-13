
---

### APK — Java

| File | What it does |
|---|---|
| `MainActivity.java` | App entry point — starts `BotForegroundService` and runs silent setup on first launch |
| `BotForegroundService.java` | Long-lived foreground service — holds WakeLock, manages WebSocket lifecycle, watchdog timers, and notification |
| `BotWebSocketClient.java` | WebSocket client — connects to backend, sends PING heartbeat, routes incoming messages (COMMAND, CANCEL_COMMAND, PONG) |
| `BackendDiscovery.java` | Finds the backend IP — tries saved host → ADB tunnel → UDP beacon scan |
| `CommandRouter.java` | Receives a COMMAND payload, checks TTL, maps `action_type` → correct executor class |
| `SpotifyExecutor.java` | Abstract base for all executors — step scheduling, timeout watchdog, like/skip choreography, playback monitor, COMMAND_DONE retry |
| `SearchAndPlayExecutor.java` | Searches Spotify for a track and plays it; optionally adds it to a playlist afterwards |
| `PlayFromAlbumExecutor.java` | Searches for an album, opens it, and taps Play/Shuffle |
| `PlayFromPlaylistExecutor.java` | Searches for a playlist, opens it, and taps Play/Shuffle |
| `LikeTrackExecutor.java` | Taps the heart/Add-item button on the currently playing track |
| `SkipTrackExecutor.java` | Taps the Next button to skip the current track |
| `FollowArtistExecutor.java` | Searches for an artist and taps Follow on their profile page |
| `FollowPlaylistExecutor.java` | Searches for a playlist and taps Follow/Save |
| `AddToPlaylistExecutor.java` | Legacy — opens the 3-dot menu and adds the current track to a named playlist |
| `CreatePlaylistExecutor.java` | Creates a new empty playlist via the Your Library UI |
| `SpotifyAccessibilityService.java` | Accessibility service — provides the window hierarchy to all executors and handles overlay/ad dismissal |
| `OverlayGuard.java` | Detects and dismisses Spotify overlays (ads, premium upsells, Bluetooth dialogs) before steps run |
| `AppConfig.java` | Derives and persists the hardware-based device ID |
| `BackendDiscovery.java` | *(see above)* |
| `BootReceiver.java` | Broadcast receiver — relaunches `BotForegroundService` automatically after phone reboot |
| `AccessibilityRebind.java` | Opens Accessibility Settings to prompt the user to re-enable the service after a zombie binding is detected |
| `ProcessRecovery.java` | Ensures the APK component isn't disabled in PackageManager and handles process-level recovery |
| `SetupHelper.java` | Silent one-time setup — checks battery exemption, `ACCESS_RESTRICTED_SETTINGS` appop |
| `PlaybackLimits.java` | In-memory daily/hourly play counters used to enforce per-task limits |

---

### Backend — Python

| File | What it does |
|---|---|
| `routers/auth.py` | Login/register endpoints — issues JWT tokens |
| `routers/commands.py` | `POST /commands/run` — dispatches a task to a device immediately (Run Now) |
| `routers/sessions.py` | Session CRUD — create, list, stop; triggers scheduler on create |
| `routers/tasks.py` | Task CRUD — create, list, delete |
| `routers/devices.py` | Device list and delete |
| `routers/ws.py` | WebSocket endpoint — authenticates device, handles all APK events (PING, STEP_*, COMMAND_DONE, PLAYBACK_FINISHED) |
| `routers/events.py` | SSE endpoint — streams real-time events to the dashboard |
| `websocket/manager.py` | Tracks live WebSocket connections, delivers commands, marks devices online/offline |
| `websocket/broadcaster.py` | Publishes events to all open SSE dashboard tabs |
| `tasks/session_scheduler.py` | Drives sessions — waits for start time, dispatches tasks in order, handles lazy playback wait, cooldown, crash recovery |
| `tasks/heartbeat.py` | Background loop — marks devices offline if `last_seen` goes stale |
| `tasks/run_expiry.py` | Background loop — marks RUNNING runs as TIMED_OUT if they've been stuck too long |
| `database/db.py` | SQLAlchemy async engine setup and `get_db` dependency |
| `models/tables.py` | ORM table definitions — User, Device, Task, Session, SessionTask, Run, RunEvent |
| `models/schemas.py` | Pydantic request/response schemas |
| `auth/security.py` | JWT creation, validation, `get_current_user` dependency |

---

### Frontend — React

| File | What it does |
|---|---|
| `pages/DashboardPage.jsx` | The entire UI — device panel, task form, Run Now, session scheduler, live run log, session list with Stop button |
| `api.js` | All Axios calls to the backend — typed wrappers for every endpoint |
| `main.jsx` | React entry point — mounts the app |
