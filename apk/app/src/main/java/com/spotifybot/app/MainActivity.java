package com.spotifybot.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * MainActivity — SpotifyBot Launcher
 *
 * Week 2 additions:
 * - Requests POST_NOTIFICATIONS permission (Android 13+) for the foreground service
 * - Starts BotForegroundService on launch (WebSocket + device registration)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG             = "SpotifyBot";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final int    NOTIF_PERM_CODE = 1001;

    private TextView tvStatus;
    private Button   btnAccessibility;
    private Button   btnLaunchSpotify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus         = findViewById(R.id.tv_status);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        btnLaunchSpotify = findViewById(R.id.btn_launch_spotify);

        // Open Accessibility Settings
        btnAccessibility.setOnClickListener(v -> {
            Log.i(TAG, "[MAIN] Opening Accessibility Settings");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        // Launch Spotify
        btnLaunchSpotify.setOnClickListener(v -> launchSpotify());

        // Request notification permission (Android 13+), then start foreground service
        requestNotificationPermissionAndStartService();

        Log.i(TAG, "[MAIN] MainActivity created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    // ── Foreground service startup ────────────────────────────────────────────

    /**
     * On Android 13+ (API 33) we must ask for POST_NOTIFICATIONS before
     * showing a foreground service notification.
     * On older versions, start the service directly.
     */
    private void requestNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIF_PERM_CODE
                );
                return;  // Service will start in onRequestPermissionsResult
            }
        }
        startBotService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIF_PERM_CODE) {
            // Start service whether or not permission was granted
            // (notification just won't show if denied, but service still runs)
            startBotService();
        }
    }

    private void startBotService() {
        Intent serviceIntent = new Intent(this, BotForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.i(TAG, "[MAIN] BotForegroundService started");

        // Request battery optimization exemption immediately after service start.
        // On Samsung/Xiaomi/OPPO this is THE critical step — without it Android puts
        // the app into "deep sleep" which suppresses WakeLocks, alarms, and broadcasts,
        // causing the foreground service to die silently after the screen turns off.
        requestBatteryOptimizationExemption();
    }

    /**
     * Ask the OS to whitelist this app from battery optimization.
     * Shows a system dialog — user taps "Allow" once and the service
     * survives indefinitely regardless of Samsung battery policy.
     */
    private void requestBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;

        String pkg = getPackageName();
        if (pm.isIgnoringBatteryOptimizations(pkg)) {
            Log.i(TAG, "[MAIN] Battery optimization already disabled — service will survive");
            return;
        }

        Log.i(TAG, "[MAIN] Requesting battery optimization exemption");
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception e) {
            // Some Samsung builds block ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
            // Fall back to the general battery settings page so the user can do it manually.
            Log.w(TAG, "[MAIN] Direct exemption request failed — opening battery settings: " + e.getMessage());
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Log.e(TAG, "[MAIN] Cannot open battery settings: " + e2.getMessage());
            }
        }
    }

    // ── Launch Spotify ────────────────────────────────────────────────────────

    private void launchSpotify() {
        Log.i(TAG, "[STEP] launch_spotify | initiated");
        Intent spotifyIntent = getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);

        if (spotifyIntent == null) {
            Log.e(TAG, "[STEP] launch_spotify | FAILED | reason=SPOTIFY_NOT_INSTALLED");
            Toast.makeText(this, "Spotify is not installed", Toast.LENGTH_LONG).show();
            return;
        }

        spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(spotifyIntent);
        Log.i(TAG, "[STEP] launch_spotify | OK | package=" + SPOTIFY_PACKAGE);
        Toast.makeText(this, "Spotify launched", Toast.LENGTH_SHORT).show();
    }

    // ── Accessibility service status ──────────────────────────────────────────

    private void updateServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            tvStatus.setText(getString(R.string.service_running));
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            Log.i(TAG, "[MAIN] Accessibility Service status: RUNNING");
        } else {
            tvStatus.setText(getString(R.string.service_stopped));
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            Log.w(TAG, "[MAIN] Accessibility Service status: STOPPED");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedService = getPackageName() + "/"
                + SpotifyAccessibilityService.class.getName();
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "[MAIN] Could not read ACCESSIBILITY_ENABLED setting");
        }
        if (enabled != 1) return false;
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.contains(expectedService);
    }
}
