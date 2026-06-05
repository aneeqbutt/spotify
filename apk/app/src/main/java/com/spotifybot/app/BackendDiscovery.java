package com.spotifybot.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

/**
 * BackendDiscovery — UDP beacon listener for zero-config backend discovery.
 *
 * The backend broadcasts a JSON beacon every 5s on UDP port 8765:
 *   {"service": "sportify-backend", "port": 8000}
 *
 * This class listens for that beacon and extracts the sender's IP address.
 * The discovered IP is persisted in SharedPreferences so the last known
 * good IP is available even when the backend is not broadcasting on the
 * next app start (e.g. backend not yet running, firewall, etc.).
 *
 * Resolution priority (highest to lowest):
 *   1. Live UDP beacon — backend is reachable right now
 *   2. SharedPreferences — last successfully resolved IP from a previous run
 *   3. AppConfig.BACKEND_HOST_FALLBACK — compile-time constant (last resort)
 *
 * Call {@link #discover(Context)} once on a background thread before
 * opening the WebSocket. It blocks for at most TIMEOUT_MS milliseconds.
 */
public class BackendDiscovery {

    private static final String TAG          = "SpotifyBot";
    private static final int    BEACON_PORT  = 8765;
    private static final int    ATTEMPT_MS   = 5_000;  // per-attempt window
    private static final int    MAX_ATTEMPTS = 3;      // 3 × 5s = 15s max
    private static final String PREFS_NAME   = "backend_discovery";
    private static final String KEY_HOST     = "last_known_host";
    private static final String KEY_BOOT_ID  = "last_boot_id";

    /**
     * Discover the backend host. Blocking — run on a background thread.
     *
     * Strategy (highest priority first):
     *   1. UDP beacon — retried up to MAX_ATTEMPTS times (5 s each).
     *      The backend broadcasts every 5 s so within 3 attempts we are
     *      guaranteed to receive a beacon if the backend is reachable.
     *      Works on ANY network automatically — no hardcoded IP needed.
     *   2. SharedPreferences — last successfully discovered IP from a
     *      previous session on the same or a different network.
     *   3. Compile-time fallback — last resort only.
     *
     * @param context Android context used to read/write SharedPreferences
     * @return the resolved host IP string (never null)
     */
    public static String discover(Context context) {
        Log.i(TAG, "[DISCOVERY] Starting UDP beacon discovery (max "
                + MAX_ATTEMPTS + " attempts × " + ATTEMPT_MS + "ms)");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String[] result = tryUdpOnce();  // [host, boot_id] or null
            if (result != null) {
                String host   = result[0];
                String bootId = result[1];
                Log.i(TAG, "[DISCOVERY] ✅ Backend discovered at: " + host
                        + " boot_id=" + bootId
                        + " (attempt " + attempt + "/" + MAX_ATTEMPTS + ")");

                // If boot_id changed the backend restarted (possibly on a new IP).
                // Clear the old saved host first so no stale IP is used as a fallback
                // even if the caller later falls through to the SharedPreferences path.
                String savedBootId = loadBootId(context);
                if (savedBootId != null && !savedBootId.equals(bootId)) {
                    Log.i(TAG, "[DISCOVERY] Backend restarted (boot_id changed: "
                            + savedBootId + " → " + bootId + ") — clearing stale saved host");
                    clearSavedHost(context);
                }

                saveHost(context, host);
                saveBootId(context, bootId);
                return host;
            }
            Log.w(TAG, "[DISCOVERY] Attempt " + attempt + "/" + MAX_ATTEMPTS
                    + " timed out — " + (attempt < MAX_ATTEMPTS ? "retrying…" : "giving up"));
        }

        // Priority 2: last known host — works if still on the same or similar network
        String saved = loadHost(context);
        if (saved != null) {
            Log.i(TAG, "[DISCOVERY] Using saved host from previous session: " + saved);
            return saved;
        }

        // No hardcoded fallback — returning null signals the caller to wait and retry.
        // This ensures the APK works on ANY network without ever being wrong.
        Log.w(TAG, "[DISCOVERY] No host found and no saved host — caller must retry");
        return null;
    }

    /**
     * Single UDP listen attempt.
     * Returns String[]{ host, boot_id } if a valid beacon arrives within ATTEMPT_MS,
     * or null on timeout / error.
     */
    private static String[] tryUdpOnce() {
        try (DatagramSocket socket = new DatagramSocket(BEACON_PORT)) {
            socket.setSoTimeout(ATTEMPT_MS);
            socket.setBroadcast(true);

            byte[]         buf    = new byte[512];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            String     json = new String(packet.getData(), 0, packet.getLength());
            JSONObject obj  = new JSONObject(json);

            if ("sportify-backend".equals(obj.optString("service"))) {
                String host   = packet.getAddress().getHostAddress();
                String bootId = obj.optString("boot_id", "");
                return new String[]{ host, bootId };
            }
            Log.w(TAG, "[DISCOVERY] Unknown beacon service — ignoring: " + json);

        } catch (SocketTimeoutException e) {
            // normal — no beacon in this window
        } catch (Exception e) {
            Log.e(TAG, "[DISCOVERY] UDP error: " + e.getMessage());
        }
        return null;
    }

    // ── SharedPreferences helpers ─────────────────────────────────────────────

    private static void saveHost(Context context, String host) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_HOST, host)
               .apply();
        Log.d(TAG, "[DISCOVERY] Host saved to SharedPreferences: " + host);
    }

    private static String loadHost(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_HOST, null);
    }

    /**
     * Clear the saved host so the next {@link #discover} call relies only on
     * the live UDP beacon.  Call this when consecutive connection attempts to
     * the saved host all fail — a sign that the network has changed (e.g. the
     * device moved to a new WiFi with a different subnet).
     */
    public static void clearSavedHost(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .remove(KEY_HOST)
               .apply();
        Log.i(TAG, "[DISCOVERY] Saved host cleared — next discovery is UDP-only");
    }

    // ── boot_id helpers ───────────────────────────────────────────────────────

    private static void saveBootId(Context context, String bootId) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit()
               .putString(KEY_BOOT_ID, bootId)
               .apply();
    }

    private static String loadBootId(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getString(KEY_BOOT_ID, null);
    }
}
