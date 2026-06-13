package com.spotifybot.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Recovery helpers when accessibility settings show ON but the service is not bound.
 */
public final class ProcessRecovery {

    private static final String TAG = "SpotifyBot";
    private static volatile long lastTrampolineMs = 0;
    private static volatile long lastProcessRestartMs = 0;
    private static final long TRAMPOLINE_COOLDOWN_MS = 15_000;
    private static final long PROCESS_RESTART_COOLDOWN_MS = 45_000;

    private ProcessRecovery() {}

    // Starts BotForegroundService via a MainActivity trampoline (used on boot and recovery)
    public static void launchServiceViaActivity(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastTrampolineMs < TRAMPOLINE_COOLDOWN_MS) {
            Log.d(TAG, "[RECOVERY] Activity trampoline throttled");
            return;
        }
        lastTrampolineMs = now;

        try {
            Context app = ctx.getApplicationContext();
            Intent intent = new Intent(app, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(MainActivity.EXTRA_PROCESS_RECOVERY, true);
            app.startActivity(intent);
            Log.i(TAG, "[RECOVERY] MainActivity trampoline launched");
        } catch (Exception e) {
            Log.e(TAG, "[RECOVERY] Activity trampoline failed: " + e.getMessage());
        }
    }

    /**
     * Samsung bind fix: settings may show ON without onServiceConnected.
     *
     * IMPORTANT: this must NOT auto-open the accessibility Settings page or launch
     * any Activity. Doing so steals foreground focus while the user is mid-toggle and
     * dismisses Samsung's "Allow full control" confirmation dialog — which is exactly
     * what prevented the bind from ever completing. Recovery is now silent: we only
     * make sure the PackageManager component is enabled. The user is prompted to
     * reopen Settings through the zombie notification (which they tap deliberately),
     * never by the app yanking them there on its own.
     */
    public static void restartProcessForBinding(Context ctx) {
        if (SpotifyAccessibilityService.instance != null) return;
        if (!SetupHelper.isAccessibilityEnabled(ctx)) return;

        long now = System.currentTimeMillis();
        if (now - lastProcessRestartMs < PROCESS_RESTART_COOLDOWN_MS) {
            Log.d(TAG, "[RECOVERY] Process restart throttled");
            return;
        }
        lastProcessRestartMs = now;

        Log.w(TAG, "[RECOVERY] Settings ON but unbound — ensuring component enabled "
                + "(silent; user reopens Settings via notification)");
        ensureComponentEnabled(ctx);
    }

    /** Ensure PackageManager has the accessibility component enabled (not stuck DISABLED). */
    static void ensureComponentEnabled(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, SpotifyAccessibilityService.class);
            ctx.getPackageManager().setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "[RECOVERY] Component enable failed: " + e.getMessage());
        }
    }

    // Alias for restartProcessForBinding — triggers silent component-enable recovery
    public static void restartForAccessibilityBind(Context ctx) {
        restartProcessForBinding(ctx);
    }
}
