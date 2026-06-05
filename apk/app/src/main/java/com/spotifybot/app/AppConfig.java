package com.spotifybot.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * AppConfig — Central configuration for the APK
 *
 * BACKEND_HOST_FALLBACK is the last-resort IP used only if:
 *   1. UDP beacon discovery times out (backend not reachable within 3s), AND
 *   2. No previous host has been saved in SharedPreferences.
 *
 * In normal operation BackendDiscovery sets resolvedHost at startup, so the
 * fallback is almost never used. Update it to your current IP if you ever
 * need to run without the beacon (e.g. airplane mode test).
 *
 * DEVICE_ID is now AUTO-GENERATED from the device's hardware at first launch
 * and stored in SharedPreferences so it is stable across restarts.
 * Format: {manufacturer}-{model}-{6-char-hash}
 * Example: samsung-sm-a155f-3f8a2b  (Galaxy A15 5G)
 *          samsung-sm-s711b-9c2e14  (Galaxy S21 FE)
 *
 * No manual changes needed — deploy the same APK to any device and it
 * self-registers with a unique ID automatically.
 *
 * DEVICE_SECRET — must match DEVICE_SHARED_SECRET in backend/.env
 */
public class AppConfig {

    private static final String TAG = "AppConfig";

    // ── Backend connection ────────────────────────────────────────────────────

    /** No hardcoded fallback — the UDP beacon always discovers the correct IP automatically.
     *  If discovery fails and SharedPreferences has no saved host, the APK waits and retries.
     *  This ensures it works on any network without rebuilding the APK. */
    public static final String BACKEND_HOST_FALLBACK = null;
    public static final int    BACKEND_PORT          = 8000;

    /**
     * Runtime-resolved host — set by BackendDiscovery before the first connect.
     * Volatile ensures the value is immediately visible to all threads without
     * needing synchronization blocks.
     */
    private static volatile String resolvedHost = null;

    /** Called by BackendDiscovery once the beacon is received. */
    public static void setResolvedHost(String host) {
        resolvedHost = host;
        Log.i(TAG, "[CONFIG] Backend host resolved to: " + host);
    }

    /** Returns the currently active backend host, or null if not yet discovered. */
    public static String getHost() {
        return resolvedHost; // null until BackendDiscovery sets it
    }

    /** Returns true if a backend host has been successfully discovered. */
    public static boolean hasHost() {
        return resolvedHost != null;
    }

    /** Full HTTP base URL. Returns null if host not yet discovered. */
    public static String getBackendHttp() {
        String h = resolvedHost;
        return h != null ? "http://" + h + ":" + BACKEND_PORT : null;
    }

    /** Full WebSocket base URL. Returns null if host not yet discovered. */
    public static String getBackendWs() {
        String h = resolvedHost;
        return h != null ? "ws://" + h + ":" + BACKEND_PORT : null;
    }

    // ── Device identity ───────────────────────────────────────────────────────

    public static final String DEVICE_SECRET = "change_this_device_secret";
    public static final String APP_VERSION   = "1.0.0";

    /**
     * Auto-generated device ID — cached in memory after first call to initDeviceId().
     * Backed by SharedPreferences so the same ID survives app restarts.
     */
    private static volatile String cachedDeviceId = null;

    /**
     * Initialise the device ID once, as early as possible in the app lifecycle.
     * Call this from BotForegroundService.onCreate() before any WebSocket activity.
     *
     * Generation strategy:
     *   1. Read from SharedPreferences — re-use if already generated on this device.
     *   2. Build from hardware:
     *        manufacturer slug  (e.g. "samsung")
     *      + model slug         (e.g. "sm-a155f")
     *      + 6-hex hash of ANDROID_ID (unique per app-install per device)
     *      → "samsung-sm-a155f-3f8a2b"
     *   3. Save to SharedPreferences for future app starts.
     *
     * ANDROID_ID is stable across reboots; it only changes on factory reset or
     * when the app is reinstalled with a different signing key.
     */
    public static void initDeviceId(Context context) {
        if (cachedDeviceId != null) return; // already initialised in this process

        // Derive device ID purely from hardware — no SharedPreferences dependency.
        // ANDROID_ID is a 64-bit value unique per device per app signing key.
        // Stable across reboots; changes only on factory reset or re-signing.
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);

        // Slug manufacturer: lowercase, alphanumeric only  (e.g. "samsung")
        String mfr = Build.MANUFACTURER
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "");

        // Slug model: lowercase, spaces/underscores → hyphens  (e.g. "sm-a155f")
        String model = Build.MODEL
                .toLowerCase()
                .replaceAll("[\\s_]+", "-")
                .replaceAll("[^a-z0-9\\-]", "")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        // 6-char hex hash of ANDROID_ID — unique per device
        int hash = (androidId != null) ? androidId.hashCode() : System.identityHashCode(context);
        String shortHash = String.format("%06x", hash & 0xFFFFFF);

        cachedDeviceId = mfr + "-" + model + "-" + shortHash;
        Log.i(TAG, "[CONFIG] Device ID: " + cachedDeviceId
                + "  mfr=" + Build.MANUFACTURER
                + "  model=" + Build.MODEL
                + "  androidId=" + (androidId != null ? androidId.substring(0, 4) + "…" : "null"));
    }

    /**
     * Returns the device ID.
     * Always call initDeviceId(context) at service start before this.
     * Falls back to a runtime-generated value if init was somehow skipped.
     */
    public static String getDeviceId() {
        if (cachedDeviceId != null) return cachedDeviceId;
        // Fallback — should never happen in normal operation
        String fallback = "device-" + String.format("%08x", System.nanoTime() & 0xFFFFFFFFL);
        Log.w(TAG, "[CONFIG] getDeviceId() called before initDeviceId() — using fallback: " + fallback);
        return fallback;
    }

    // ── WebSocket reconnect policy ────────────────────────────────────────────
    public static final int    RECONNECT_INITIAL_MS = 1_000;
    public static final int    RECONNECT_MAX_MS      = 30_000;
    public static final double RECONNECT_MULTIPLIER  = 2.0;
}
