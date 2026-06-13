package com.spotifybot.app;

import android.content.Context;
import android.util.Log;

/**
 * Manual accessibility rebind — opens the SpotifyBot toggle page only.
 * Never writes Settings.Secure (creates enabled-but-unbound zombie on Samsung).
 */
public final class AccessibilityRebind {

    private static final String TAG = "SpotifyBot";
    private static volatile long lastAttemptMs = 0;
    private static final long COOLDOWN_MS = 10_000;

    private AccessibilityRebind() {}

    // Opens the accessibility toggle page (throttled to once per 10s) to let the user rebind
    public static void requestManualRebind(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < COOLDOWN_MS) {
            Log.d(TAG, "[REBIND] Manual rebind throttled");
            SetupHelper.openAccessibilitySettings(ctx);
            return;
        }
        lastAttemptMs = now;

        Log.i(TAG, "[REBIND] Opening SpotifyBot accessibility toggle");
        SetupHelper.openAccessibilityServiceDetails(ctx);
    }
}
