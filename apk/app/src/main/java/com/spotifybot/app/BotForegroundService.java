package com.spotifybot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.net.ConnectivityManager;
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
 *   7. BootReceiver (SCREEN_ON / USER_PRESENT / BOOT_COMPLETED) — restarts service
 *   8. Standby bucket set to ACTIVE via installDebug Gradle hook — Samsung won't throttle
 *
 * Note: The old programmatic REBIND (Settings.Secure OFF→ON toggle) has been removed.
 * Samsung Android 14 KNOX does not respond to Settings.Secure writes from a non-system
 * callingPackage — the write was silently accepted but never triggered a bind.
 * Binding is now handled by: SpotifyAccessibilityService.onDestroy() fires a notification
 * guiding the user to the exact Settings page when Samsung kills the binding.
 */
public class BotForegroundService extends Service implements BotWebSocketClient.CommandListener {

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

    // ── Accessibility service health monitor ──────────────────────────────────
    //
    // Problem: Samsung kills SpotifyAccessibilityService (accessibility binding)
    // while BotForegroundService (WebSocket) keeps running.  The device appears
    // ONLINE in the dashboard but every command immediately fails with
    // ACCESSIBILITY_SERVICE_NOT_RUNNING — a silent, confusing broken state.
    //
    // Fix: poll SpotifyAccessibilityService.instance every 15s.
    // If it is null while the WebSocket is connected → disconnect the WebSocket
    // so the backend marks the device OFFLINE (honest state).
    // When the user re-enables the accessibility service, onServiceConnected()
    // calls startForegroundService() which re-enters onStartCommand() and
    // reconnects the WebSocket automatically.
    private static final long   ACC_CHECK_INTERVAL_MS = 15_000;
    private static final String ACC_DEAD_CHANNEL_ID   = "spotifybot_acc_dead";
    private static final int    ACC_DEAD_NOTIF_ID     = 2002;
    private final Handler       healthHandler         = new Handler(Looper.getMainLooper());
    private final Runnable      healthCheck           = new Runnable() {
        @Override
        public void run() {
            if (SpotifyAccessibilityService.instance == null) {
                if (wsClient != null && wsClient.isAuthenticated()) {
                    Log.w(TAG, "[SERVICE_FG] ⚠ Accessibility service dead while WebSocket is "
                            + "live — disconnecting so device shows OFFLINE in dashboard");
                    wsClient.disconnect();
                    wsClient  = null;
                    currentWs = null;
                    updateNotification("⚠ Accessibility off — re-enable SpotifyBot in Settings");
                    showAccessibilityDeadNotification();
                }
            }
            healthHandler.postDelayed(this, ACC_CHECK_INTERVAL_MS);
        }
    };

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
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

        // Start the accessibility service health monitor.
        // First check fires after 15s — gives the accessibility service time to bind
        // on initial startup before we start evaluating its state.
        healthHandler.postDelayed(healthCheck, ACC_CHECK_INTERVAL_MS);
        Log.i(TAG, "[SERVICE_FG] Accessibility health monitor started (interval="
                + ACC_CHECK_INTERVAL_MS / 1000 + "s)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "[SERVICE_FG] onStartCommand called");

        // Must call startForeground before any early return (Android requirement)
        String notifText = (wsClient != null && wsClient.isAuthenticated())
                ? "Connected — waiting for commands" : "Connecting…";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(notifText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(notifText));
        }

        // Guard — if already fully authenticated, skip reconnect entirely.
        // BootReceiver fires SCREEN_ON on every unlock, and SpotifyAccessibilityService
        // auto-starts this service on every onServiceConnected. Without this guard every
        // unlock would tear down and re-open the WebSocket, causing the "offline" flicker
        // and duplicate connection race conditions on the backend.
        if (wsClient != null && wsClient.isAuthenticated()) {
            Log.i(TAG, "[SERVICE_FG] Already connected and authenticated — skipping reconnect");
            return START_STICKY;
        }

        // Not authenticated — safe to (re)initialise
        commandRouter = new CommandRouter(this);

        if (wsClient != null) {
            Log.w(TAG, "[SERVICE_FG] Stale WS client found — disconnecting before reconnect");
            wsClient.disconnect();
            wsClient = null;
            currentWs = null;
        }

        // Device registration is handled automatically by the backend on first WebSocket
        // connection (DEVICE_HELLO auto-registers any device with a valid shared secret).
        // No separate HTTP registration call needed.

        // BotWebSocketClient owns the full connect lifecycle: discovery → socket → auth.
        wsClient = new BotWebSocketClient(BotForegroundService.this, BotForegroundService.this);
        currentWs = wsClient;   // expose live socket globally for executor COMMAND_DONE retry
        wsClient.connect();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "[SERVICE_FG] Foreground service destroyed");
        healthHandler.removeCallbacks(healthCheck);
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

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "[SERVICE_FG] Network became available — checking WebSocket state");
                if (wsClient != null && !wsClient.isAuthenticated()) {
                    Log.i(TAG, "[SERVICE_FG] Not authenticated — triggering reconnect");
                    wsClient.connect();
                } else if (wsClient == null) {
                    // Service was just created and wsClient hasn't been assigned yet.
                    // onStartCommand will handle the connect; nothing to do here.
                    Log.d(TAG, "[SERVICE_FG] Network available but wsClient not yet initialised — ignored");
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
    }

    @Override
    public void onDisconnected() {
        Log.w(TAG, "[SERVICE_FG] WebSocket disconnected — reconnecting…");
        updateNotification("Reconnecting…");
    }

    @Override
    public void onCommandReceived(JsonObject command) {
        String actionType = command.has("action_type")
                ? command.get("action_type").getAsString() : "?";
        Log.i(TAG, "[SERVICE_FG] Command received: " + actionType);
        updateNotification("Executing: " + actionType);
        commandRouter.execute(command, wsClient);
    }

    // ── Notification helpers ──────────────────────────────────────────────────

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
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SpotifyBot")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
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
}
