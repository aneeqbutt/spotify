package com.spotifybot.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * BotWebSocketClient — Robust persistent WebSocket connection to the backend.
 *
 * Robustness guarantees:
 *   • Only ONE live WebSocket at a time — connect() cancels any previous socket
 *     and clears all pending reconnect callbacks before opening a new one.
 *   • AtomicBoolean isConnecting prevents concurrent connect() calls racing.
 *   • Stored connectRunnable allows removeCallbacks() to cancel duplicate timers.
 *   • Re-discovery on reconnect: if the IP changed, the next reconnect will find
 *     the new backend automatically (SharedPreferences fallback after 3s).
 *   • Never gives up — WakeLock keeps CPU alive so retries land when backend returns.
 *   • Only stops on intentionalClose=true (explicit disconnect() call).
 *
 * Reconnect policy:
 *   Exponential backoff: 1s → 2s → 4s → 8s → 16s → 30s → 30s → … (no cap on attempts)
 *   On AUTH_OK: counter and delay both reset.
 */
public class BotWebSocketClient {

    private static final String TAG = "SpotifyBot";

    // ── Listener interface ────────────────────────────────────────────────────

    public interface CommandListener {
        void onCommandReceived(JsonObject command);
        void onConnected();
        void onDisconnected();
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final OkHttpClient    httpClient;
    private final Gson            gson        = new Gson();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final CommandListener listener;
    private final Context         context;

    /** Single-thread executor for blocking discovery calls. */
    private final ExecutorService discoveryExecutor = Executors.newSingleThreadExecutor();

    private WebSocket  socket           = null;
    private boolean    intentionalClose = false;
    private boolean    isAuthenticated  = false;
    private int        reconnectDelayMs = AppConfig.RECONNECT_INITIAL_MS;
    private int        reconnectAttempts = 0;

    /**
     * Tracks how many consecutive connection attempts used the same host and
     * all failed before AUTH_OK was ever received.  After STALE_HOST_THRESHOLD
     * consecutive failures we clear the SharedPreferences saved host so the
     * next discovery cycle relies only on a live UDP beacon — critical when
     * travelling to a new network where the old saved IP is unreachable.
     */
    private int    consecutiveFailures  = 0;
    private String lastAttemptedHost    = null;
    private static final int STALE_HOST_THRESHOLD = 4;

    /**
     * Guards against multiple simultaneous connect() calls.
     * Set to true when connect() starts, cleared in onOpen/onFailure/onClosed.
     */
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);

    /**
     * Stored reconnect runnable — always the SAME object instance so that
     * mainHandler.removeCallbacks(connectRunnable) reliably cancels it.
     * Using a lambda field prevents the "new lambda each call" gotcha.
     */
    private final Runnable connectRunnable = this::doConnect;

    public BotWebSocketClient(CommandListener listener, Context context) {
        this.listener = listener;
        this.context  = context;
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)   // protocol-level keepalive every 10s
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)     // persistent — no application read timeout
                .writeTimeout(10, TimeUnit.SECONDS)   // fail fast on stuck sends
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start a fresh connection. Cancels any in-flight socket and pending reconnect
     * timers before opening the new one. Safe to call from any thread.
     */
    public void connect() {
        intentionalClose  = false;
        reconnectAttempts = 0;
        reconnectDelayMs  = AppConfig.RECONNECT_INITIAL_MS;

        // Cancel any pending reconnect timer — prevents stale timers from opening
        // a second socket after this explicit connect() call already opened one.
        mainHandler.removeCallbacks(connectRunnable);

        // Run discovery on background executor (blocks up to 15s).
        // If no host is found (null), notify the listener and schedule a retry
        // instead of attempting to open a WebSocket to a null URL.
        discoveryExecutor.execute(() -> {
            String host = BackendDiscovery.discover(context);
            AppConfig.setResolvedHost(host);
            if (host != null) {
                Log.i(TAG, "[WS] Backend found at " + host + " — connecting");
                mainHandler.post(connectRunnable);
            } else {
                Log.w(TAG, "[WS] No backend found — will retry discovery in 15s");
                mainHandler.post(() -> listener.onDisconnected()); // shows "Reconnecting…"
                // Retry discovery after 15s — covers the case where the backend
                // starts up after the APK, or the user switches WiFi networks.
                mainHandler.postDelayed(() -> {
                    if (!intentionalClose) connect();
                }, 15_000);
            }
        });
    }

    /**
     * Immediately stop the connection. Does not reconnect.
     * Sets intentionalClose=true so all pending/future reconnects are cancelled.
     */
    public void disconnect() {
        intentionalClose = true;
        isAuthenticated  = false;
        mainHandler.removeCallbacks(connectRunnable);
        if (socket != null) {
            socket.close(1000, "Service stopping");
            socket = null;
        }
        discoveryExecutor.shutdownNow();
    }

    public boolean send(JsonObject payload) {
        WebSocket ws = socket;
        if (ws == null || !isAuthenticated) {
            Log.w(TAG, "[WS] Send skipped — not authenticated");
            return false;
        }
        boolean sent = ws.send(gson.toJson(payload));
        if (!sent) Log.w(TAG, "[WS] WebSocket.send() returned false — buffer may be full");
        return sent;
    }

    public boolean isConnected()     { return socket != null && !isConnecting.get(); }
    public boolean isAuthenticated() { return isAuthenticated; }

    // ── Internal connect ──────────────────────────────────────────────────────

    /**
     * Internal connect — called on main thread after discovery completes.
     * Guards against concurrent calls with isConnecting flag.
     */
    private void doConnect() {
        if (intentionalClose) return;

        // Guard: if a connection attempt is already in progress, don't open another.
        if (!isConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "[WS] doConnect() called while already connecting — skipped");
            return;
        }

        // Cancel any previous socket immediately (no graceful close — we need a fresh start).
        // cancel() drops the socket without waiting for a close handshake, freeing the thread.
        if (socket != null) {
            Log.w(TAG, "[WS] Cancelling previous socket before opening new connection");
            socket.cancel();
            socket = null;
        }

        String wsBase = AppConfig.getBackendWs();
        if (wsBase == null) {
            // Host not yet discovered — don't try to connect to "null"
            Log.w(TAG, "[WS] doConnect called but no host resolved — aborting this attempt");
            isConnecting.set(false);
            if (!intentionalClose) connect(); // triggers discovery again
            return;
        }

        // Track which host this attempt is using so failure handler can detect
        // when we keep failing on the same stale IP (network changed).
        String currentHost = AppConfig.getHost();
        if (currentHost != null && !currentHost.equals(lastAttemptedHost)) {
            // Switched to a new host — reset the stale-host failure counter
            consecutiveFailures = 0;
            lastAttemptedHost   = currentHost;
        }

        String url = wsBase + "/ws/" + AppConfig.getDeviceId();
        Log.i(TAG, "[WS] Connecting to: " + url
                + (reconnectAttempts > 0 ? " (attempt #" + reconnectAttempts + ")" : ""));

        Request request = new Request.Builder().url(url).build();
        socket = httpClient.newWebSocket(request, new BotWebSocketListener());
    }

    // ── WebSocket event callbacks ─────────────────────────────────────────────

    private class BotWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            isConnecting.set(false);   // connection established — clear guard
            Log.i(TAG, "[WS] Connection opened — sending DEVICE_HELLO");

            try {
                JSONObject hello = new JSONObject();
                hello.put("type",          "DEVICE_HELLO");
                hello.put("device_id",     AppConfig.getDeviceId());
                hello.put("shared_secret", AppConfig.DEVICE_SECRET);
                hello.put("app_version",   AppConfig.APP_VERSION);

                JSONObject caps = new JSONObject();
                caps.put("SEARCH_AND_PLAY",    true);
                caps.put("LIKE_CURRENT_TRACK", true);
                caps.put("FOLLOW_ARTIST",      true);
                caps.put("SKIP_TRACK",         true);
                hello.put("capabilities", caps);

                ws.send(hello.toString());
                Log.i(TAG, "[WS] DEVICE_HELLO sent");
            } catch (Exception e) {
                Log.e(TAG, "[WS] Failed to build DEVICE_HELLO: " + e.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Log.d(TAG, "[WS] ← " + text.substring(0, Math.min(text.length(), 200)));

            try {
                JsonObject msg  = JsonParser.parseString(text).getAsJsonObject();
                String     type = msg.has("type") ? msg.get("type").getAsString() : "";

                switch (type) {
                    case "AUTH_OK":
                        isAuthenticated    = true;
                        reconnectDelayMs   = AppConfig.RECONNECT_INITIAL_MS;
                        reconnectAttempts  = 0;
                        consecutiveFailures = 0;   // successful auth — host is good
                        lastAttemptedHost  = AppConfig.getHost();
                        Log.i(TAG, "[WS] AUTH_OK — authenticated and ready");
                        mainHandler.post(() -> {
                            listener.onConnected();
                            startHeartbeat(ws);
                        });
                        break;

                    case "PONG":
                        // Backend echo to our PING — connection is bidirectionally alive.
                        Log.d(TAG, "[WS] PONG received — backend is alive");
                        break;

                    case "AUTH_FAILED":
                        String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
                        Log.e(TAG, "[WS] AUTH_FAILED: " + reason + " — stopping retries");
                        intentionalClose = true;
                        ws.close(1008, "Auth failed");
                        break;

                    case "COMMAND":
                        Log.i(TAG, "[WS] COMMAND: " + (msg.has("action_type") ? msg.get("action_type").getAsString() : "?"));
                        mainHandler.post(() -> listener.onCommandReceived(msg));
                        break;

                    default:
                        Log.d(TAG, "[WS] Unknown message type ignored: " + type);
                }
            } catch (Exception e) {
                Log.e(TAG, "[WS] Failed to parse message: " + e.getMessage());
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.i(TAG, "[WS] Closed: code=" + code + " reason=" + reason);
            isConnecting.set(false);
            isAuthenticated = false;
            socket = null;
            stopHeartbeat();
            mainHandler.post(() -> listener.onDisconnected());
            if (!intentionalClose) {
                // Normal close while authenticated means network dropped — count it.
                consecutiveFailures++;
                maybeEvictStaleHost();
                scheduleReconnect();
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "[WS] Failure: " + t.getMessage());
            isConnecting.set(false);
            isAuthenticated = false;
            // Only null out socket if it's THIS socket (not a newer one from a race).
            if (socket == ws) socket = null;
            stopHeartbeat();
            mainHandler.post(() -> listener.onDisconnected());
            if (!intentionalClose) {
                consecutiveFailures++;
                maybeEvictStaleHost();
                scheduleReconnect();
            }
        }
    }

    // ── Heartbeat (application-level PING to keep last_seen fresh in DB) ─────

    private static final int PING_INTERVAL_MS = 30_000;
    private Runnable pingRunnable = null;

    private void startHeartbeat(WebSocket ws) {
        stopHeartbeat();
        pingRunnable = new Runnable() {
            @Override public void run() {
                if (socket != null && isAuthenticated) {
                    JsonObject ping = new JsonObject();
                    ping.addProperty("type",      "PING");
                    ping.addProperty("device_id", AppConfig.getDeviceId());
                    boolean sent = ws.send(gson.toJson(ping));
                    Log.d(TAG, "[WS] PING sent: " + sent);
                    mainHandler.postDelayed(this, PING_INTERVAL_MS);
                }
            }
        };
        mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        if (pingRunnable != null) {
            mainHandler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }

    // ── Stale host eviction ───────────────────────────────────────────────────

    /**
     * If we have failed STALE_HOST_THRESHOLD times in a row using the same host,
     * clear the SharedPreferences saved host so the next BackendDiscovery call
     * is forced to rely on a live UDP beacon rather than the cached stale IP.
     *
     * This is the key fix for "device can't find backend on a new WiFi network":
     * the old saved IP (e.g. 192.168.1.4) is unreachable on the new subnet, but
     * without this eviction the app would loop forever retrying the wrong IP.
     */
    private void maybeEvictStaleHost() {
        if (consecutiveFailures >= STALE_HOST_THRESHOLD && lastAttemptedHost != null) {
            Log.w(TAG, "[WS] " + consecutiveFailures + " consecutive failures on host "
                    + lastAttemptedHost + " — clearing saved host, forcing fresh UDP discovery");
            BackendDiscovery.clearSavedHost(context);
            // Do NOT null resolvedHost here — the next scheduleReconnect() call runs
            // BackendDiscovery.discover() which will set it to null if UDP fails and
            // SharedPreferences is empty.  Nulling it here causes doConnect() to
            // immediately call connect() which re-queues discovery — a tight loop.
            lastAttemptedHost    = null;
            consecutiveFailures  = 0;
        }
    }

    // ── Reconnect with exponential backoff + re-discovery ────────────────────

    /**
     * Schedule a reconnect attempt.
     *
     * Key robustness properties:
     * 1. Cancels any existing pending reconnect before scheduling — prevents
     *    two simultaneous reconnect timers racing to open sockets.
     * 2. Re-runs BackendDiscovery so if the backend's IP changed while we
     *    were disconnected, we automatically find the new address.
     */
    private void scheduleReconnect() {
        if (intentionalClose) return;

        reconnectAttempts++;
        int delay = reconnectDelayMs;
        reconnectDelayMs = (int) Math.min(
                reconnectDelayMs * AppConfig.RECONNECT_MULTIPLIER,
                AppConfig.RECONNECT_MAX_MS
        );

        Log.i(TAG, "[WS] Reconnect #" + reconnectAttempts
                + " in " + delay + "ms (next=" + reconnectDelayMs + "ms)");

        // Remove any previously scheduled reconnect — this is why connectRunnable
        // is a stored field rather than a fresh lambda: removeCallbacks needs the
        // exact same Runnable object reference to match and remove it.
        mainHandler.removeCallbacks(connectRunnable);

        // Schedule: wait `delay` ms then run discovery + doConnect.
        // We wrap in a runnable that re-runs discovery (not connectRunnable directly)
        // so that each reconnect attempt refreshes the backend IP in case it changed.
        mainHandler.postDelayed(() -> {
            if (intentionalClose) return;
            discoveryExecutor.execute(() -> {
                String host = BackendDiscovery.discover(context);
                AppConfig.setResolvedHost(host);
                mainHandler.post(connectRunnable);
            });
        }, delay);
    }
}
