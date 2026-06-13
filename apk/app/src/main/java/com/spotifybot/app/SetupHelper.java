package com.spotifybot.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * One-shot setup — grants everything we can without user dialogs.
 *
 * With WRITE_SECURE_SETTINGS (auto-granted by Gradle installDebug via adb):
 *   • Enables the accessibility service programmatically (first install only)
 *
 * Zombie recovery (Settings=ON but binding dead) is handled by AccessibilityRebind
 * (programmatic kill + restore) and ProcessRecovery as last resort.
 */
public final class SetupHelper {

    private static final String TAG = "SpotifyBot";
    private static final String PREFS = "setup_wizard";
    private static final String KEY_DONE = "auto_setup_done";

    public static final String EXTRA_AUTO_SETUP = "extra_auto_setup";

    private SetupHelper() {}

    /** Whether installDebug adb grant is present — required for programmatic rebind. */
    public static boolean hasWriteSecureSettings(Context ctx) {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Android 13+ blocks accessibility bind for sideloaded apps until the user (or adb)
     * grants ACCESS_RESTRICTED_SETTINGS via App Info → Allow restricted settings.
     */
    public static boolean isRestrictedSettingsAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        try {
            android.app.AppOpsManager appOps =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return true;
            int mode = appOps.unsafeCheckOpNoThrow(
                    "android:access_restricted_settings",
                    android.os.Process.myUid(),
                    ctx.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return true;
        }
    }

    /** Opens App Info where user taps ⋮ → Allow restricted settings (one-time). */
    public static void openAppDetailsForRestrictedSettings(Context ctx) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Log.i(TAG, "[SETUP] Opened app details for restricted-settings grant");
        } catch (Exception e) {
            Log.w(TAG, "[SETUP] Cannot open app details: " + e.getMessage());
        }
    }

    // Builds the fully-qualified accessibility service component ID string
    private static String serviceId(Context ctx) {
        return ctx.getPackageName() + "/" + SpotifyAccessibilityService.class.getName();
    }

    /** Full component id as stored in Settings.Secure (for rebind logging). */
    public static String getAccessibilityServiceId(Context ctx) {
        return serviceId(ctx);
    }

    // Returns true if the one-time auto-setup has already been completed on this device
    public static boolean isSetupComplete(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false);
    }

    // Persists a flag in SharedPreferences indicating setup is done
    public static void markSetupComplete(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply();
    }

    /** Full auto-setup — permissions and battery only. Never writes Settings.Secure (causes zombie bind). */
    public static void runAutoSetup(Activity activity) {
        Log.i(TAG, "[SETUP] Running auto-setup (no programmatic accessibility toggle)");

        ProcessRecovery.ensureComponentEnabled(activity);

        if (!isRestrictedSettingsAllowed(activity)) {
            Log.w(TAG, "[SETUP] ACCESS_RESTRICTED_SETTINGS not allowed — open App Info");
            openAppDetailsForRestrictedSettings(activity);
        }

        tryGrantNotificationPermission(activity);
        tryRequestBatteryExemption(activity);

        if (isAccessibilityBound() && isBatteryExempt(activity)) {
            markSetupComplete(activity);
            Log.i(TAG, "[SETUP] Auto-setup complete");
        }
    }

    /**
     * @deprecated Do not use — adb/settings writes show ON but never bind on Samsung.
     * User must toggle in Accessibility Settings UI.
     */
    @Deprecated
    public static boolean tryEnableAccessibility(Context ctx) {
        Log.w(TAG, "[SETUP] tryEnableAccessibility skipped — use UI toggle only");
        return isAccessibilityBound();
    }

    /** @deprecated Settings.Secure restore never binds on Samsung — use UI toggle. */
    @Deprecated
    public static boolean restoreAccessibilityExact(Context ctx) {
        Log.w(TAG, "[SETUP] restoreAccessibilityExact skipped — use UI toggle only");
        return false;
    }

    /** True when SpotifyBot is listed in accessibility settings (may still be unbound). */
    public static boolean isAccessibilityEnabled(Context ctx) {
        String expected = serviceId(ctx);
        String pkg = ctx.getPackageName();
        String shortId = pkg + "/." + SpotifyAccessibilityService.class.getSimpleName();
        try {
            int enabled = Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null
                    && (services.contains(expected) || services.contains(shortId));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the live binding exists — the only check that matters for automation. */
    public static boolean isAccessibilityBound() {
        return SpotifyAccessibilityService.instance != null;
    }

    /**
     * Samsung zombie: toggle ON in Settings but onServiceConnected never fired.
     * WebSocket must stay offline; recovery needs process restart or manual OFF→ON.
     */
    public static boolean isAccessibilityZombie(Context ctx) {
        return isAccessibilityEnabled(ctx) && !isAccessibilityBound();
    }

    /** Open the system toggle page for SpotifyBot (not the generic list). */
    public static void openAccessibilityServiceDetails(Context ctx) {
        ComponentName cn = new ComponentName(ctx, SpotifyAccessibilityService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                intent.putExtra("android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
                        cn.flattenToString());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                Log.i(TAG, "[SETUP] Opened accessibility details for " + cn.flattenToString());
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "[SETUP] Details settings failed: " + e.getMessage());
        }
        openAccessibilitySettings(ctx);
    }

    /** Open system accessibility settings — fallback when details page unavailable. */
    public static void openAccessibilitySettings(Context ctx) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Log.i(TAG, "[SETUP] Opened accessibility settings for manual rebind");
        } catch (Exception e) {
            Log.w(TAG, "[SETUP] Cannot open accessibility settings: " + e.getMessage());
        }
    }

    // Returns true if this app is excluded from Android battery optimizations
    public static boolean isBatteryExempt(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** Opens the system battery-exemption dialog directly (one tap "Allow"). */
    public static void tryRequestBatteryExemption(Activity activity) {
        if (isBatteryExempt(activity)) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            Log.i(TAG, "[SETUP] Opened battery exemption dialog");
        } catch (Exception e) {
            Log.w(TAG, "[SETUP] Battery exemption intent failed: " + e.getMessage());
        }
    }

    // Requests POST_NOTIFICATIONS permission on Android 13+ if not already granted
    private static void tryGrantNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(activity,
                android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
    }

    /** Open accessibility settings for manual rebind, or programmatic fix when possible. */
    public static void openAccessibilitySettingsForRebind(Activity activity) {
        if (isAccessibilityBound()) {
            Toast.makeText(activity, "Accessibility service is running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isRestrictedSettingsAllowed(activity)) {
            Toast.makeText(activity,
                    "Tap ⋮ → Allow restricted settings, then enable accessibility",
                    Toast.LENGTH_LONG).show();
            openAppDetailsForRestrictedSettings(activity);
            return;
        }
        Toast.makeText(activity, "Toggle SpotifyBot OFF → wait 2s → ON",
                Toast.LENGTH_LONG).show();
        openAccessibilityServiceDetails(activity);
    }

    /** @deprecated use {@link #openAccessibilitySettingsForRebind} */
    public static void openAccessibilitySettingsIfNeeded(Activity activity) {
        openAccessibilitySettingsForRebind(activity);
    }
}
