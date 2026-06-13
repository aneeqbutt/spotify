## APK summary (what each component does)

This document describes what each major Android APK component does and how the APK behaves end-to-end at runtime.

Path root: `apk/app/src/main/`

---

## What the APK does (high-level)

- The APK runs a **foreground service** that keeps a **persistent WebSocket** connection to the backend.
- The backend sends `COMMAND` messages over WebSocket.
- The APK routes each command to an **executor** that drives the **Spotify Android UI** using an **AccessibilityService**.
- The APK streams progress back to the backend using:
  - `STEP_STARTED`
  - `STEP_OK`
  - `STEP_FAILED` (with `reason_code`)
  - `COMMAND_DONE` (terminal event)
  - `PLAYBACK_FINISHED` (when a track ends, for session timing)

---

## Runtime flow (end-to-end)

1. **App launch**
   - `MainActivity` starts `BotForegroundService`.
   - It requests Android 13+ notification permission (needed for foreground service notification).
   - It prompts the user to whitelist battery optimization (critical for Samsung/other OEM survival).

2. **Service bootstraps**
   - `BotForegroundService.onCreate()` initializes device identity via `AppConfig.initDeviceId()`.
   - Acquires a **partial WakeLock** so the CPU stays on while screen is locked.
   - Registers a `ConnectivityManager.NetworkCallback` to reconnect fast when network returns.
   - Starts an accessibility health-check loop (keeps “online” honest).

3. **Backend discovery**
   - `BotWebSocketClient.connect()` calls `BackendDiscovery.discover()` (blocking, runs on a background executor).
   - `BackendDiscovery` listens for backend UDP beacons and extracts the backend IP.
   - The resolved host is stored in `AppConfig` (and also cached in SharedPreferences by discovery).

4. **WebSocket connect + auth**
   - `BotWebSocketClient` connects to `ws://<host>:8000/ws/<device_id>`.
   - Sends `DEVICE_HELLO` (includes `device_id`, `shared_secret`, `app_version`, and capabilities).
   - On `AUTH_OK` it becomes “authenticated” and starts application-level heartbeats (`PING`/`PONG`).

5. **Command execution**
   - Backend sends `{ type: "COMMAND", action_type, params, ... }`.
   - `BotForegroundService.onCommandReceived()` updates the notification and delegates to `CommandRouter`.
   - `CommandRouter` checks TTL (drops expired commands), maps `action_type` to an executor, then calls `executor.execute(...)`.

6. **UI automation**
   - Each executor extends `SpotifyExecutor` (shared helpers, step events, watchdog timeouts).
   - Executors use `SpotifyAccessibilityService.instance` to read the UI tree and perform clicks/gestures.
   - `OverlayGuard` runs continuously on window changes to dismiss ads/upsells/system prompts that block automation.

7. **Completion + playback monitoring**
   - Executors send `COMMAND_DONE` (with retries if the socket is briefly reconnecting).
   - For play actions, `SpotifyExecutor` starts a playback monitor and emits `PLAYBACK_FINISHED` when the track advances:
     - **Event-driven**: track-title change detection via accessibility events (reliable during screen-off)
     - **Backup polling**: seekbar position polling when available

---

## AndroidManifest + declared components

File: `apk/app/src/main/AndroidManifest.xml`

- **Permissions**
  - `INTERNET`, `ACCESS_NETWORK_STATE`: backend communication.
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`: run persistent foreground service.
  - `POST_NOTIFICATIONS`: Android 13+ foreground notification permission.
  - `WAKE_LOCK`: keep CPU alive while screen off (for stable WS/retries).
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: user grants exemption to avoid OEM “deep sleep”.
  - `RECEIVE_BOOT_COMPLETED`: restart service after reboot.
  - `<queries>` for `com.spotify.music`: package visibility (Android 11+).

- **App components**
  - `MainActivity`: launcher UI.
  - `BotForegroundService`: foreground service that owns WebSocket + command routing.
  - `BootReceiver`: restarts service on boot/screen events.
  - `SpotifyAccessibilityService`: accessibility binding used to drive Spotify UI.

---

## Core “system” classes (APK infrastructure)

### `MainActivity.java`

- Minimal launcher UI + operator controls:
  - opens Accessibility Settings
  - launches Spotify
- Requests `POST_NOTIFICATIONS` (Android 13+), then starts `BotForegroundService`.
- Prompts for battery optimization exemption right after service start.

### `BotForegroundService.java`

- The “always-on” process anchor:
  - runs as a foreground service (notification required)
  - acquires WakeLock
  - holds `BotWebSocketClient` and `CommandRouter`
  - updates notification on connect/disconnect/command
- Network auto-reconnect:
  - `ConnectivityManager.NetworkCallback.onAvailable()` triggers `wsClient.connect()` if not authenticated.
- Accessibility health monitoring:
  - polls `SpotifyAccessibilityService.instance`
  - if accessibility is dead while authenticated, it **disconnects** WS so backend marks device OFFLINE (avoids misleading “online but broken” state)
  - sends a high-priority notification to re-enable accessibility
- Exposes `public static volatile BotWebSocketClient currentWs`
  - used by `SpotifyExecutor` to deliver terminal events even if a reconnect replaced the socket mid-command.

### `BotWebSocketClient.java`

- Owns robust persistent WebSocket connection and reconnection strategy.
- Key behavior:
  - ensures **only one live socket** (cancels old socket and pending reconnect timers)
  - runs backend discovery on a single-thread executor
  - sends `DEVICE_HELLO` on open
  - waits for `AUTH_OK` before allowing sends
  - sends application heartbeats (`PING` every ~30s) and handles `PONG`
  - routes `COMMAND` messages to the service via `CommandListener.onCommandReceived`
- Stale-host eviction:
  - after repeated failures against same cached host, clears saved host so discovery must re-learn via UDP beacon.

### `BackendDiscovery.java`

- Listens for UDP beacons on port `8765` to learn backend IP automatically.
- Behavior:
  - tries multiple short UDP windows (up to ~15s)
  - persists `last_known_host` in SharedPreferences for fast reconnects
  - tracks backend `boot_id` from beacon; if it changes, clears old host (backend restarted / moved)
- Returns `null` if it cannot find any host (callers should wait and retry).

### `AppConfig.java`

- Central runtime configuration:
  - backend port (`8000`)
  - volatile `resolvedHost` set by discovery
  - builds backend URLs (`http://...`, `ws://...`) when host is known
  - reconnect backoff parameters
- Device identity:
  - generates `device_id` from manufacturer + model + short hash
  - caches in memory (and expects init early in service lifecycle)
- Shared secret:
  - `DEVICE_SECRET` must match backend’s configured shared secret (used in `DEVICE_HELLO`).

### `BootReceiver.java`

- Starts `BotForegroundService` on:
  - `BOOT_COMPLETED`
  - `SCREEN_ON`
  - `USER_PRESENT`
- Goal: recover service automatically if OEM kills it during screen-off / standby.

### `CommandRouter.java`

- Converts incoming `COMMAND` messages into executor runs.
- Responsibilities:
  - **TTL** check using `issued_at` vs `ttl_ms`
  - route `action_type` → executor class
  - terminal failure (`COMMAND_DONE FAILED`) for unknown/expired commands

---

## Accessibility + UI automation layer

### `SpotifyAccessibilityService.java`

- The live accessibility binding that provides:
  - access to Spotify UI tree (`getRootInActiveWindow()`, `getWindows()`)
  - ability to click nodes and dispatch gestures (`canPerformGestures=true`)
  - always-on overlay dismissal trigger: `OverlayGuard.check()` on window-state changes
- Maintains `public static SpotifyAccessibilityService instance`:
  - executors use this to read/act on UI even when invoked outside the service class
- Track-change detection:
  - watches Spotify `TYPE_WINDOW_CONTENT_CHANGED`
  - compares now-playing title to detect “track advanced”
  - triggers `SpotifyExecutor.onTrackChangedByEvent()` which sends `PLAYBACK_FINISHED`
- Survival UX:
  - if Samsung kills the binding, the service shows a high-priority notification guiding user to re-enable it.

### `OverlayGuard.java`

- One place for “unstick the UI” logic (ads, upsells, permission dialogs).
- Called from:
  - `SpotifyAccessibilityService` (continuous, throttled)
  - `SpotifyExecutor` (on-demand before steps and during waits)
- Handles:
  - premium upsells (“Continue with ads”, “dismiss” variants, etc.)
  - promo cards / modals (sometimes easiest to close via global Back)
  - system permission dialogs (searches across **all windows**, not just Spotify package)
  - “Skip ad” buttons when available
- Provides “state checks”:
  - `isAdOrOverlayBlocking(...)`: blocking overlays that should pause automation
  - `isAudioAdPlaying(...)`: audio ads that should be waited out (but not treated as an error)

### `SpotifyExecutor.java` (base class)

- Shared executor framework used by every action:
  - step event protocol: `STEP_STARTED`, `STEP_OK`, `STEP_FAILED`
  - per-step watchdog timeouts (default ~15s)
  - “human-like” randomized delays and gesture taps
  - node find helpers: by text / content-desc / resource-id
  - click strategies for Compose nodes (`performAction` even when `isClickable=false`)
  - `COMMAND_DONE` delivery with retry window so reconnects don’t lose the terminal event
- Launch + settle:
  - brings Spotify to foreground
  - runs overlay dismissal
  - if blocking ad/overlay: waits/polls until clear (or fails with `AD_TIMEOUT`)
  - if audio ad: waits, then proceeds (never hard-fails)
- Playback monitoring:
  - event-driven: title-change detection via accessibility events (reliable during screen-off)
  - backup polling: seekbar progress when visible
  - sends `PLAYBACK_FINISHED` with the `run_id`

---

## Anti-abuse / safety controls

### `PlaybackLimits.java`

- In-memory counters (persist for app process lifetime):
  - hourly and daily playback count
  - auto-reset after window expiration
- Clone blocking:
  - blocks repeating the same (action + identifier) within the session.

---

## Executors (each automation action)

All executor files live under:
`apk/app/src/main/java/com/spotifybot/app/`

They all:
- extend `SpotifyExecutor`
- read `params` from incoming command
- run a chain of steps (each emits step events and is guarded by a watchdog)

### `SearchAndPlayExecutor.java` (`SEARCH_AND_PLAY`)

- Params: `{ "query": "..." }`
- Steps:
  - open Search tab
  - activate search bar, type query
  - submit via IME Enter (avoids “query text matches EditText” trap)
  - locate the best-matching song row and tap it
  - verify playback; then starts playback monitor and emits `PLAYBACK_FINISHED` later

### `LikeTrackExecutor.java` (`LIKE_CURRENT_TRACK`)

- Steps:
  - open full player
  - tap like (“Add item”) or detect already-liked (“Remove item”) for idempotency
  - handle optional “Liked Songs” sheet
  - verify liked state (soft verify allowed)

### `FollowArtistExecutor.java` (`FOLLOW_ARTIST`)

- Params: `{ "artist_name": "..." }` (or `query`)
- Steps:
  - search for artist
  - tap artist card (subtitle “Artist”)
  - tap Follow on artist page (Compose button patterns, searches across all windows)
  - verify “Following” (soft verify allowed)

### `SkipTrackExecutor.java` (`SKIP_TRACK`)

- Steps:
  - open full player
  - tap “Next” (content-desc)
  - verify playback continues (soft verify allowed)

### `PlayFromAlbumExecutor.java` (`PLAY_FROM_ALBUM`)

- Params: `{ "query": "...", "daily_limit": 0, "hourly_limit": 0 }`
- Enforces playback limits via `PlaybackLimits`.
- Steps:
  - search album (tries album suggestion path first; otherwise results + “Albums” filter)
  - open album result
  - tap Play/Shuffle (Compose patterns; retries if page still rendering)
  - verify playback and increments limits

### `PlayFromPlaylistExecutor.java` (`PLAY_FROM_PLAYLIST`)

- Params: `{ "query": "...", "daily_limit": 0, "hourly_limit": 0 }`
- Enforces playback limits via `PlaybackLimits`.
- Steps:
  - search playlist → “Playlists” filter → open playlist result
  - tap Play/Shuffle (retries if needed)
  - verify playback; increments limits; starts playback monitor and emits `PLAYBACK_FINISHED`

### `FollowPlaylistExecutor.java` (`FOLLOW_PLAYLIST`)

- Params: `{ "query": "..." }`
- Steps:
  - search playlist → “Playlists” filter → open playlist
  - follow/save via “Add playlist to Your Library” patterns (idempotent if already saved)
  - verify library state (soft verify allowed)

### `AddToPlaylistExecutor.java` (`ADD_TO_PLAYLIST`)

- Params: `{ "playlist_name": "..." }` (or `query`)
- Steps:
  - open full player
  - open “More options”
  - select “Add to playlist”
  - in “Save in” sheet, find the target playlist row and click its sibling `Button` (“+”)
  - verify by checking sheet dismissed (soft verify allowed)

### `CreatePlaylistExecutor.java` (`CREATE_PLAYLIST`)

- Params: `{ "playlist_name": "..." }` (or `query`)
- Steps:
  - go to “Your Library”
  - tap “Create” (+)
  - choose “Playlist” type
  - type playlist name into the editable field
  - tap Create (find “Create” text and click its sibling `Button`)
  - verify by checking the playlist name visible (soft verify allowed)

---

## Resources (APK configuration)

### `res/xml/accessibility_service_config.xml`

- Configures which events are delivered and enables:
  - `canRetrieveWindowContent=true` (required to read UI tree)
  - `canPerformGestures=true` (used for gesture tapping / coordinate taps)
- Restricts package events to Spotify via `packageNames="com.spotify.music"`.

### `res/xml/network_security_config.xml`

- Permits cleartext traffic (HTTP + WS) for dev LAN backend connections.

---

## Quick reference: supported action types

Routed by `CommandRouter`:

- `SEARCH_AND_PLAY` → `SearchAndPlayExecutor`
- `LIKE_CURRENT_TRACK` → `LikeTrackExecutor`
- `FOLLOW_ARTIST` → `FollowArtistExecutor`
- `SKIP_TRACK` → `SkipTrackExecutor`
- `PLAY_FROM_ALBUM` → `PlayFromAlbumExecutor`
- `PLAY_FROM_PLAYLIST` → `PlayFromPlaylistExecutor`
- `FOLLOW_PLAYLIST` → `FollowPlaylistExecutor`
- `ADD_TO_PLAYLIST` → `AddToPlaylistExecutor`
- `CREATE_PLAYLIST` → `CreatePlaylistExecutor`

