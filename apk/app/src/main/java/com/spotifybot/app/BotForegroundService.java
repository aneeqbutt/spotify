package com.spotifybot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.os.Build;
import android.view.accessibility.AccessibilityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.gson.JsonObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BotForegroundService — Persistent background service
 *
 * Survival strategy (layered defences against Android killing us):
 *   1. Foreground service + visible notification — protected from OOM killer
 *   2. WakeLock (PARTIAL) — CPU stays on through screen lock
 *   3. Battery optimization exemption — requested in MainActivity on first launch
 *   4. stopWithTask=false in manifest — survives app being swiped from recents
 *   5. START_STICKY — OS restarts service if killed
 *   6. NetworkCallback — reconnects WebSocket the instant WiFi/mobile returns
 *   7. BootReceiver (BOOT_COMPLETED only) — restarts via MainActivity trampoline
 *   8. Standby bucket set to ACTIVE via installDebug Gradle hook — Samsung won't throttle
 *
 * Accessibility is the anchor: WebSocket connects ONLY after onServiceConnected.
 * Process kill was removed — it was crashing FGS and breaking Samsung bindings.
 */
public class BotForegroundService extends Service implements BotWebSocketClient.CommandListener {

    private static volatile BotForegroundService serviceInstance;

    /** Live foreground service instance — used by executors to re-bind accessibility. */
    public static BotForegroundService getInstance() {
        return serviceInstance;
    }

    private static final String TAG              = "SpotifyBot";
    private static final String CHANNEL_ID       = "spotifybot_service";
    private static final int    NOTIFICATION_ID  = 1001;

    private BotWebSocketClient           wsClient;
    private CommandRouter                commandRouter;
    private ExecutorService              ioExecutor;
    private PowerManager.WakeLock        wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;

    /**
     * Static reference to the live, authenticated WebSocket client.
     * Updated every time a new BotWebSocketClient is created or torn down.
     * SpotifyExecutor uses this in its COMMAND_DONE retry loop so that a
     * mid-command reconnect doesn't strand event delivery on the old dead socket.
     */
    public static volatile BotWebSocketClient currentWs = null;

    // ── Accessibility-gated WebSocket ─────────────────────────────────────────
    //
    // WebSocket connects ONLY when SpotifyAccessibilityService.instance is set.
    // This prevents the "zombie" state (WS online, automation impossible) and stops
    // the connect→kill→reconnect loop that was aborting commands mid-flight.
    private static final long   ACC_BINDING_CHECK_DELAY_MS   = 4_000;
    private static final long   ACCESSIBILITY_WATCHDOG_MS     = 30_000;
    private final Handler       healthHandler                 = new Handler(Looper.getMainLooper());
    private boolean             pendingReconnectAfterCommand  = false;
    private boolean             zombieNotified                = false;
    private boolean             bindingCheckScheduled         = false;
    private boolean             zombieRestartAttempted        = false;

    /** After Settings change: wait, then cold-restart once if still unbound (Samsung bind path). */
    private final Runnable bindingCheckAfterSettings = new Runnable() {
        @Override
        public void run() {
            bindingCheckScheduled = false;
            if (SpotifyAccessibilityService.instance != null) {
                zombieNotified = false;
                zombieRestartAttempted = false;
                connectWebSocketIfReady();
                return;
            }
            if (!SetupHelper.isRestrictedSettingsAllowed(BotForegroundService.this)) {
                updateNotification("Allow restricted settings in App Info");
                return;
            }
            if (SetupHelper.isAccessibilityEnabled(BotForegroundService.this)) {
                // Settings ON but binding never fired (Samsung zombie). Recovery is now
                // silent + notification-driven: ensure the component is enabled, then let
                // the user reopen Settings by TAPPING the zombie notification. We never
                // auto-launch Settings here — that stole focus from the "Allow full
                // control" dialog and was itself preventing the bind from completing.
                Log.w(TAG, "[SERVICE_FG] Settings ON, still unbound — silent recovery + notification");
                ProcessRecovery.restartProcessForBinding(BotForegroundService.this);
                updateNotification("Accessibility stuck — tap notification to fix");
                if (!zombieNotified) {
                    zombieNotified = true;
                    showZombieRebindNotification();
                }
            } else {
                updateNotification("Waiting for accessibility…");
            }
        }
    };

    /** Slow watchdog — no spam, just ensure recovery is scheduled once. */
    private final Runnable accessibilityWatchdog = new Runnable() {
        @Override
        public void run() {
            if (SpotifyAccessibilityService.instance == null) {
                if (wsClient != null && wsClient.isAuthenticated()) {
                    disconnectWebSocket("watchdog: ws without accessibility");
                }
                ensureAccessibilityRecoveryRunning();
            } else {
                connectWebSocketIfReady();
            }
            healthHandler.postDelayed(this, ACCESSIBILITY_WATCHDOG_MS);
        }
    };

    private boolean isAccessibilityZombie() {
        return SetupHelper.isAccessibilityZombie(this);
    }

    /**
     * Connect WebSocket only when accessibility is bound.
     * If not bound, stay offline and run the accessibility recovery loop.
     */
    private void connectWebSocketIfReady() {
        if (SpotifyAccessibilityService.instance == null) {
            if (isAccessibilityZombie()) {
                Log.w(TAG, "[SERVICE_FG] ZOMBIE — settings ON but binding null, WS stays offline");
            } else {
                Log.i(TAG, "[SERVICE_FG] WS deferred — accessibility not enabled");
            }
            disconnectWebSocket("accessibility not bound");
            ensureAccessibilityRecoveryRunning();
            updateNotification(isAccessibilityZombie()
                    ? "Accessibility stuck — tap notification to fix"
                    : "Waiting for accessibility…");
            return;
        }

        if (wsClient != null && wsClient.isAuthenticated()) {
            updateNotification("Connected — waiting for commands");
            return;
        }
        // Client is non-null and still self-reconnecting (connecting now, or sleeping
        // between exponential-backoff attempts). Do NOT tear it down and build a new one
        // here — recreating mid-reconnect opens a second socket that the backend
        // force-closes ("Replaced by new connection"), which the phone sees as a Broken
        // pipe and reconnects again: an infinite flap. Let the existing client finish.
        if (wsClient != null && wsClient.isAlive()) {
            return;
        }

        if (commandRouter == null) {
            commandRouter = new CommandRouter(this);
        }

        if (wsClient != null) {
            wsClient.disconnect();
            wsClient = null;
            currentWs = null;
        }

        wsClient  = new BotWebSocketClient(this, this);
        currentWs = wsClient;
        wsClient.connect();
        updateNotification("Connecting…");
        Log.i(TAG, "[SERVICE_FG] WebSocket connect started (accessibility bound)");
    }

    /** Intentional disconnect — does not auto-reconnect until accessibility binds. */
    private void disconnectWebSocket(String reason) {
        if (wsClient == null) return;
        Log.i(TAG, "[SERVICE_FG] WS disconnect: " + reason);
        wsClient.disconnect();
        wsClient  = null;
        currentWs = null;
    }

    private void scheduleBindingCheck() {
        if (bindingCheckScheduled) return;
        bindingCheckScheduled = true;
        healthHandler.removeCallbacks(bindingCheckAfterSettings);
        healthHandler.postDelayed(bindingCheckAfterSettings, ACC_BINDING_CHECK_DELAY_MS);
        Log.d(TAG, "[SERVICE_FG] Binding check scheduled in " + ACC_BINDING_CHECK_DELAY_MS + "ms");
    }

    private void ensureAccessibilityRecoveryRunning() {
        if (SpotifyAccessibilityService.instance != null) return;

        if (!SetupHelper.isRestrictedSettingsAllowed(this)) {
            updateNotification("Allow restricted settings in App Info");
            return;
        }

        if (isAccessibilityZombie()) {
            updateNotification("Accessibility stuck — tap notification to fix");
            if (!zombieNotified) {
                zombieNotified = true;
                showZombieRebindNotification();
            }
            scheduleBindingCheck();
            return;
        }

        if (!SetupHelper.isAccessibilityEnabled(this)) {
            updateNotification("Enable SpotifyBot in Accessibility Settings");
        }
        scheduleBindingCheck();
    }

    /** Called from SpotifyAccessibilityService.onServiceConnected. */
    public static void onAccessibilityBound() {
        BotForegroundService inst = serviceInstance;
        if (inst == null) return;
        inst.zombieNotified = false;
        inst.zombieRestartAttempted = false;
        inst.bindingCheckScheduled = false;
        inst.healthHandler.removeCallbacks(inst.bindingCheckAfterSettings);
        inst.connectWebSocketIfReady();
    }

    /** Called from SpotifyAccessibilityService.onDestroy. */
    public static void onAccessibilityLost() {
        BotForegroundService inst = serviceInstance;
        if (inst == null) return;
        inst.disconnectWebSocket("accessibility onDestroy");
        inst.ensureAccessibilityRecoveryRunning();
        inst.updateNotification("Accessibility lost — recovering…");
    }

    private static final String ACC_DEAD_CHANNEL_ID          = "spotifybot_acc_dead";
    private static final int    ACC_DEAD_NOTIF_ID            = 2002;
    private static final int    BATTERY_FIX_NOTIF_ID         = 2003;
    private static final String BATTERY_FIX_CHANNEL          = "spotifybot_battery_fix";

    // Polls every 20s — if the socket is a zombie (claims connected but PONG is stale),
    // kill it and open a fresh connection immediately.
    private static final long WS_WATCHDOG_INTERVAL_MS = 20_000;
    private final Runnable wsWatchdog = new Runnable() {
        @Override
        public void run() {
            if (wsClient != null && !SpotifyExecutor.isCommandInProgress()) {
                if (SpotifyAccessibilityService.instance == null) {
                    disconnectWebSocket("ws-watchdog: no accessibility");
                } else if (wsClient.isAuthenticated() && !wsClient.isHealthy()) {
                    Log.w(TAG, "[SERVICE_FG] WS watchdog — zombie connection"
                            + " (last PONG " + wsClient.getLastPongAgeMs() + "ms ago)");
                    wsClient.forceReconnect();
                    updateNotification("Reconnecting — fixing stale connection…");
                } else if (!wsClient.isAuthenticated() && !wsClient.isConnected()
                        && !wsClient.isConnecting() && !wsClient.isAlive()) {
                    // Only recreate when the client is genuinely abandoned. While it is
                    // mid-backoff (isAlive() == true) it will reconnect on its own —
                    // recreating it here would open a duplicate socket and flap.
                    Log.w(TAG, "[SERVICE_FG] WS watchdog — client abandoned, recreating");
                    connectWebSocketIfReady();
                }
            }
            healthHandler.postDelayed(this, WS_WATCHDOG_INTERVAL_MS);
        }
    };

    /** Called when a command finishes — run any reconnect that was deferred mid-run. */
    public static void onCommandFinished() {
        BotForegroundService inst = serviceInstance;
        if (inst == null) return;
        if (inst.pendingReconnectAfterCommand) {
            inst.pendingReconnectAfterCommand = false;
            inst.connectWebSocketIfReady();
            return;
        }
        if (inst.wsClient != null && inst.wsClient.isAuthenticated()
                && !inst.wsClient.isHealthy()) {
            inst.requestReconnect("unhealthy after command");
        }
    }

    private void requestReconnect(String reason) {
        Log.w(TAG, "[SERVICE_FG] WS reconnect: " + reason);
        if (SpotifyExecutor.isCommandInProgress()) {
            pendingReconnectAfterCommand = true;
            Log.i(TAG, "[SERVICE_FG] Reconnect deferred until command completes");
            return;
        }
        connectWebSocketIfReady();
    }

    // ── Accessibility settings observer ──────────────────────────────────────
    //
    // Watches for changes to ENABLED_ACCESSIBILITY_SERVICES.  Fires the instant
    // the user toggles SpotifyBot in Settings.  If instance is still null at that
    // point we are in the Samsung zombie state (Settings=ON but no onServiceConnected
    // callback) — restart the process so Android properly binds the service.
    private ContentObserver accSettingsObserver;
    private AccessibilityManager accessibilityManager;
    private AccessibilityManager.AccessibilityServicesStateChangeListener accServicesListener;

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        serviceInstance = this;
        // Resolve device ID first — everything else (registration, WebSocket URL) depends on it
        AppConfig.initDeviceId(this);
        Log.i(TAG, "[SERVICE_FG] Foreground service created — device=" + AppConfig.getDeviceId());
        createNotificationChannel();
        ioExecutor = Executors.newSingleThreadExecutor();

        // Keep CPU running while screen is locked so OkHttp pings can fire
        // and the WebSocket stays alive through screen lock/sleep cycles.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotifyBot:WakeLock");
        wakeLock.acquire();
        Log.i(TAG, "[SERVICE_FG] WakeLock acquired — CPU will stay on through screen lock");

        // Register a network callback so we reconnect the instant WiFi or mobile
        // data becomes available again. Static CONNECTIVITY_CHANGE broadcasts are
        // blocked on Android 7+ for battery-optimized apps, so NetworkCallback is
        // the reliable alternative.
        registerNetworkCallback();

        // NOTE: healthCheck removed — replaced by zombieWatchdog which kills WS immediately
        // when accessibility is unbound while the socket still shows authenticated.

        // Register ContentObserver so we react the instant the user changes the
        // accessibility setting — no need to wait for the next poll cycle.
        registerAccessibilityObserver();
        registerAccessibilityStateListener();
        healthHandler.postDelayed(accessibilityWatchdog, ACCESSIBILITY_WATCHDOG_MS);
        healthHandler.postDelayed(wsWatchdog, WS_WATCHDOG_INTERVAL_MS);
        healthHandler.post(() -> connectWebSocketIfReady());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "[SERVICE_FG] onStartCommand called");

        // Must call startForeground before any early return (Android requirement)
        String notifText;
        if (SpotifyAccessibilityService.instance == null) {
            notifText = isAccessibilityZombie()
                    ? "Accessibility stuck — recovering…"
                    : "Waiting for accessibility…";
        } else if (wsClient != null && wsClient.isAuthenticated()) {
            notifText = "Connected — waiting for commands";
        } else {
            notifText = "Connecting…";
        }
        if (!promoteToForeground(notifText)) {
            return START_NOT_STICKY;
        }

        // Never tear down the WebSocket while a command is executing on the phone.
        if (SpotifyExecutor.isCommandInProgress()) {
            Log.d(TAG, "[SERVICE_FG] onStartCommand during command — leaving WS alone");
            return START_STICKY;
        }

        // Guard — if already authenticated or mid-connect, skip re-init.
        // SpotifyAccessibilityService auto-starts this on onServiceConnected.
        // BootReceiver no longer fires on every screen unlock.
        if (wsClient != null) {
            if (wsClient.isAuthenticated() || wsClient.isConnecting()) {
                if (wsClient.isAuthenticated() && !wsClient.isConnected()) {
                    requestReconnect("socket dead (onStartCommand)");
                }
                return START_STICKY;
            }
            // Still self-reconnecting (mid-backoff) — don't recreate on a START_STICKY
            // redelivery; that would race the in-flight reconnect into a duplicate socket.
            if (wsClient.isAlive()) {
                return START_STICKY;
            }
            disconnectWebSocket("stale client onStartCommand");
        }

        commandRouter = new CommandRouter(this);
        connectWebSocketIfReady();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (serviceInstance == this) serviceInstance = null;
        Log.i(TAG, "[SERVICE_FG] Foreground service destroyed");
        healthHandler.removeCallbacks(accessibilityWatchdog);
        healthHandler.removeCallbacks(bindingCheckAfterSettings);
        healthHandler.removeCallbacks(wsWatchdog);
        unregisterAccessibilityObserver();
        unregisterAccessibilityStateListener();
        unregisterNetworkCallback();
        if (wsClient != null) wsClient.disconnect();
        if (ioExecutor != null) ioExecutor.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "[SERVICE_FG] WakeLock released");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Accessibility observer helpers ───────────────────────────────────────

    /**
     * Register a ContentObserver on ENABLED_ACCESSIBILITY_SERVICES.
     * Fires whenever the user enables or disables any accessibility service.
     * If SpotifyBot is toggled ON while instance is still null, trigger rebind recovery.
     */
    private void registerAccessibilityObserver() {
        accSettingsObserver = new ContentObserver(healthHandler) {
            @Override
            public void onChange(boolean selfChange) {
                Log.d(TAG, "[SERVICE_FG] Accessibility settings changed");
                scheduleBindingCheck();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accSettingsObserver);
        Log.i(TAG, "[SERVICE_FG] Accessibility settings observer registered");
    }

    private void unregisterAccessibilityObserver() {
        if (accSettingsObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(accSettingsObserver);
            } catch (Exception ignored) {}
            accSettingsObserver = null;
        }
    }

    /** React when the system rebinding accessibility services (Samsung recovery path). */
    private void registerAccessibilityStateListener() {
        accessibilityManager = getSystemService(AccessibilityManager.class);
        if (accessibilityManager == null) return;

        accServicesListener = am -> {
            Log.d(TAG, "[SERVICE_FG] System accessibility services changed");
            scheduleBindingCheck();
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            accessibilityManager.addAccessibilityServicesStateChangeListener(
                    r -> healthHandler.post(r), accServicesListener);
        }
        Log.i(TAG, "[SERVICE_FG] AccessibilityServicesStateChangeListener registered");
    }

    private void unregisterAccessibilityStateListener() {
        if (accessibilityManager != null && accServicesListener != null) {
            try {
                accessibilityManager.removeAccessibilityServicesStateChangeListener(
                        accServicesListener);
            } catch (Exception ignored) {}
        }
        accServicesListener = null;
        accessibilityManager = null;
    }

    /**
     * Check whether SpotifyBot's accessibility service is listed as enabled in
     * Settings.Secure — this is the same check MainActivity uses for its status UI.
     * Returns true even if the actual binding is dead (Samsung zombie state).
     */
    private boolean isAccessibilityServiceEnabled() {
        String expectedService = getPackageName() + "/"
                + SpotifyAccessibilityService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.contains(expectedService);
    }

    // ── Network reconnect (WiFi / mobile data change) ─────────────────────────

    /**
     * Register a ConnectivityManager callback that fires whenever a usable network
     * becomes available (WiFi reconnects, airplane mode off, etc.).
     *
     * When the network returns and the WebSocket is not authenticated, we call
     * wsClient.connect() which triggers BackendDiscovery + a fresh WebSocket.
     * This handles the most common dropout case: phone briefly loses WiFi.
     */
    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        // Restrict to WiFi only — USB ADB creates an RNDIS interface that also
        // reports NET_CAPABILITY_INTERNET, which would trigger a reconnect attempt
        // routed over USB (where the backend is unreachable).
        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "[SERVICE_FG] Network became available — checking WebSocket state");
                if (SpotifyExecutor.isCommandInProgress()) {
                    pendingReconnectAfterCommand = true;
                    return;
                }
                if (wsClient != null && !wsClient.isAuthenticated() && !wsClient.isConnecting()) {
                    Log.i(TAG, "[SERVICE_FG] Network available — reconnect if accessibility bound");
                    connectWebSocketIfReady();
                } else if (wsClient == null) {
                    connectWebSocketIfReady();
                }
            }
        };

        try {
            cm.registerNetworkCallback(req, networkCallback);
            Log.i(TAG, "[SERVICE_FG] NetworkCallback registered — will auto-reconnect on network change");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE_FG] Failed to register NetworkCallback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        networkCallback = null;
    }

    // ── CommandListener callbacks ─────────────────────────────────────────────

    @Override
    public void onConnected() {
        Log.i(TAG, "[SERVICE_FG] WebSocket connected and authenticated");
        updateNotification("Connected — waiting for commands");
        zombieNotified = false;
    }

    @Override
    public void onConnectionStale() {
        Log.w(TAG, "[SERVICE_FG] Connection stale — zombie socket being replaced");
        updateNotification("Reconnecting — stale connection…");
    }

    @Override
    public void onDisconnected() {
        if (wsClient == null) {
            Log.d(TAG, "[SERVICE_FG] WS closed intentionally — no auto-reconnect");
            return;
        }
        Log.w(TAG, "[SERVICE_FG] WebSocket disconnected — client will self-reconnect");
        updateNotification("Reconnecting…");
        healthHandler.postDelayed(() -> {
            if (SpotifyExecutor.isCommandInProgress()) {
                pendingReconnectAfterCommand = true;
                return;
            }
            if (SpotifyAccessibilityService.instance == null) {
                Log.i(TAG, "[SERVICE_FG] Accessibility off — tearing down WS");
                disconnectWebSocket("disconnected without accessibility");
                return;
            }
            // BotWebSocketClient reconnects itself with exponential backoff. Only step
            // in if it has actually been abandoned (intentionally closed) — otherwise
            // creating a fresh client here races the in-flight reconnect and produces a
            // duplicate socket → Broken-pipe flap loop.
            if (wsClient != null && wsClient.isAlive()) {
                Log.d(TAG, "[SERVICE_FG] WS self-reconnecting — leaving existing client alone");
                return;
            }
            connectWebSocketIfReady();
        }, 2_000);
    }

    @Override
    public void onCommandReceived(JsonObject command) {
        String actionType = command.has("action_type")
                ? command.get("action_type").getAsString() : "?";
        Log.i(TAG, "[SERVICE_FG] Command received: " + actionType
                + " acc=" + (SpotifyAccessibilityService.instance != null ? "ON" : "OFF"));
        updateNotification("Executing: " + actionType);
        if (commandRouter == null) {
            Log.e(TAG, "[SERVICE_FG] commandRouter is null — reinitializing");
            commandRouter = new CommandRouter(this);
        }
        if (SpotifyAccessibilityService.instance == null) {
            ensureAccessibilityRecoveryRunning();
        }
        commandRouter.execute(command, wsClient);
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    /**
     * Promote to foreground FGS. On Android 14+ background starts of dataSync FGS
     * throw ForegroundServiceStartNotAllowedException — fall back to MainActivity trampoline.
     */
    private boolean promoteToForeground(String notifText) {
        Notification notification = buildNotification(notifText);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Exception specialUseErr) {
            Log.w(TAG, "[SERVICE_FG] specialUse startForeground failed: "
                    + specialUseErr.getMessage());
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                Log.i(TAG, "[SERVICE_FG] FGS promoted via dataSync fallback");
                return true;
            } catch (Exception fallbackErr) {
                Log.e(TAG, "[SERVICE_FG] startForeground failed — activity trampoline: "
                        + fallbackErr.getMessage());
                ProcessRecovery.launchServiceViaActivity(this);
                stopSelf();
                return false;
            }
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SpotifyBot Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the SpotifyBot automation service running");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SpotifyBot")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    /**
     * Fire a high-priority "tap to fix" notification after the accessibility service
     * has been dead for ACC_DEAD_BATTERY_AFTER × ACC_DEAD_RECONNECT_MS seconds.
     *
     * Tapping opens MainActivity with EXTRA_BATTERY_FIX=true which immediately triggers
     * the system ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog.  On Samsung One UI,
     * approving that dialog adds the app to "Never sleeping apps" (Background usage limits),
     * which lifts Samsung's battery-based block on accessibility service binding.
     */
    /** Called when a command timed out waiting for accessibility bind. */
    public static void handleAccessibilityDead() {
        BotForegroundService inst = serviceInstance;
        if (inst == null) return;
        inst.ensureAccessibilityRecoveryRunning();
        inst.showAccessibilityDeadNotification();
    }

    /** Brings MainActivity to front for battery fix only — no Settings.Secure writes. */
    private void launchZombieRecovery() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(MainActivity.EXTRA_ZOMBIE_RECOVERY, true);
            intent.putExtra(MainActivity.EXTRA_BATTERY_FIX, true);
            startActivity(intent);
            Log.i(TAG, "[SERVICE_FG] Launched zombie recovery (battery fix only)");
        } catch (Exception e) {
            Log.w(TAG, "[SERVICE_FG] Zombie recovery launch failed: " + e.getMessage());
            showBatteryFixNotification();
        }
    }

    /** Brings MainActivity to front and runs silent setup (one system dialog max). */
    private void launchSilentSetup() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(SetupHelper.EXTRA_AUTO_SETUP, true);
            intent.putExtra(MainActivity.EXTRA_BATTERY_FIX, true);
            startActivity(intent);
            Log.i(TAG, "[SERVICE_FG] Launched silent setup via MainActivity");
        } catch (Exception e) {
            Log.w(TAG, "[SERVICE_FG] Silent setup launch failed: " + e.getMessage());
            showBatteryFixNotification();
        }
    }

    private void showBatteryFixNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    BATTERY_FIX_CHANNEL,
                    "SpotifyBot Battery Fix",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);

            Intent fixIntent = new Intent(this, MainActivity.class);
            fixIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            fixIntent.putExtra(MainActivity.EXTRA_BATTERY_FIX, true);
            PendingIntent pi = PendingIntent.getActivity(
                    this, BATTERY_FIX_NOTIF_ID, fixIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notif = new androidx.core.app.NotificationCompat.Builder(this, BATTERY_FIX_CHANNEL)
                    .setContentTitle("⚡ SpotifyBot: Fix Battery Restriction")
                    .setContentText("Samsung is blocking the bot. Tap to open battery fix (2 taps).")
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                            .bigText("Samsung battery restrictions are preventing SpotifyBot "
                                    + "from running.\n\nTap this notification — SpotifyBot will "
                                    + "open a system dialog. Tap  Allow  to permanently fix it."))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .build();

            nm.notify(BATTERY_FIX_NOTIF_ID, notif);
            Log.i(TAG, "[SERVICE_FG] Battery-fix notification fired");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE_FG] Failed to show battery-fix notification: " + e.getMessage());
        }
    }

    /**
     * Fire a high-priority notification when the health check detects the
     * accessibility service is dead while the WebSocket was connected.
     *
     * Tapping opens Accessibility Settings directly so the user can toggle
     * SpotifyBot OFF → ON in a few seconds.
     *
     * This is separate from SpotifyAccessibilityService.showRebindNotification()
     * — that fires from onDestroy() (Samsung kills the binding).  This fires from
     * the health check (detected from the foreground service side).  Both point to
     * the same fix, but having both ensures the user sees the alert regardless of
     * which component detects the problem first.
     */
    /** Called by SpotifyAccessibilityService.onServiceConnected() — clears stale dead-alerts. */
    static void cancelAccessibilityAlerts(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ACC_DEAD_NOTIF_ID);
        } catch (Exception ignored) {}
    }

    private void showAccessibilityDeadNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    ACC_DEAD_CHANNEL_ID,
                    "SpotifyBot Accessibility Alert",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);

            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notif = new androidx.core.app.NotificationCompat.Builder(this, ACC_DEAD_CHANNEL_ID)
                    .setContentTitle("⚠ SpotifyBot: Accessibility service is OFF")
                    .setContentText("Device disconnected. Tap → find SpotifyBot → toggle OFF then ON")
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                            .bigText("The automation service stopped.\n\n"
                                    + "Tap this notification → scroll to SpotifyBot "
                                    + "→ toggle OFF → toggle ON.\n\n"
                                    + "The device will reconnect automatically."))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .build();

            nm.notify(ACC_DEAD_NOTIF_ID, notif);
            Log.i(TAG, "[SERVICE_FG] Accessibility-dead notification fired");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE_FG] Failed to show accessibility-dead notification: " + e.getMessage());
        }
    }

    /**
     * Zombie-specific alert: Settings toggle is ON but the service never bound.
     * Process restart often fixes this; if not, user must toggle OFF → ON manually.
     */
    private void showZombieRebindNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    ACC_DEAD_CHANNEL_ID,
                    "SpotifyBot Accessibility Alert",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);

            android.content.ComponentName cn = new android.content.ComponentName(
                    this, SpotifyAccessibilityService.class);
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                intent.putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                        cn.flattenToString());
            } else {
                intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notif = new androidx.core.app.NotificationCompat.Builder(this, ACC_DEAD_CHANNEL_ID)
                    .setContentTitle("⚠ SpotifyBot: Accessibility stuck (zombie)")
                    .setContentText("Toggle looks ON but service is dead. Tap → OFF then ON")
                    .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                            .bigText("Samsung left accessibility ON without a live binding.\n\n"
                                    + "Tap → find SpotifyBot → toggle OFF → wait 2s → toggle ON.\n\n"
                                    + "The device will reconnect when the binding returns."))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .build();

            nm.notify(ACC_DEAD_NOTIF_ID, notif);
            Log.w(TAG, "[SERVICE_FG] Zombie rebind notification fired");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE_FG] Failed to show zombie notification: " + e.getMessage());
        }
    }
}
