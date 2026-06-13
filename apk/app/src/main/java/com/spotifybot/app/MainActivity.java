package com.spotifybot.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * MainActivity — SpotifyBot Launcher
 *
 * On install, Gradle's installDebug hook grants permissions via adb and enables
 * accessibility automatically. On first open, SetupHelper runs any remaining
 * steps silently (no explanatory dialogs).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG             = "SpotifyBot";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";

    /** Intent extra from BotForegroundService battery-fix path. */
    public static final String EXTRA_BATTERY_FIX = "extra_battery_fix";
    /** Zombie recovery — skip Settings.Secure writes; battery fix only. */
    public static final String EXTRA_ZOMBIE_RECOVERY = "extra_zombie_recovery";
    /** Process kill recovery — start FGS from foreground then exit immediately. */
    public static final String EXTRA_PROCESS_RECOVERY = "extra_process_recovery";

    private TextView tvStatus;
    private Button   btnAccessibility;
    private Button   btnLaunchSpotify;
    private boolean  zombieSettingsOpened;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Trampoline after ProcessRecovery kill — FGS must start from a foreground activity.
        if (getIntent().getBooleanExtra(EXTRA_PROCESS_RECOVERY, false)) {
            Log.i(TAG, "[MAIN] Process recovery trampoline — starting FGS");
            ProcessRecovery.ensureComponentEnabled(this);
            startBotService();
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        tvStatus         = findViewById(R.id.tv_status);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        btnLaunchSpotify = findViewById(R.id.btn_launch_spotify);

        btnAccessibility.setOnClickListener(v ->
                SetupHelper.openAccessibilitySettingsForRebind(this));
        btnLaunchSpotify.setOnClickListener(v -> launchSpotify());

        startBotService();
        runSilentSetup();

        if (getIntent().getBooleanExtra(EXTRA_BATTERY_FIX, false)
                || getIntent().getBooleanExtra(EXTRA_ZOMBIE_RECOVERY, false)) {
            Log.i(TAG, "[MAIN] Battery fix requested — opening system dialog");
            SetupHelper.tryRequestBatteryExemption(this);
        }

        Log.i(TAG, "[MAIN] MainActivity created (v1.2)");
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (SetupHelper.isAccessibilityBound()) {
            SetupHelper.markSetupComplete(this);
            zombieSettingsOpened = false;
        } else if (!SetupHelper.isRestrictedSettingsAllowed(this)) {
            Log.w(TAG, "[MAIN] Restricted settings not allowed — open App Info");
            SetupHelper.openAppDetailsForRestrictedSettings(this);
        }

        updateServiceStatus();

        if (!SetupHelper.isBatteryExempt(this)) {
            SetupHelper.tryRequestBatteryExemption(this);
        }
    }

    private void runSilentSetup() {
        if (getIntent().getBooleanExtra(EXTRA_ZOMBIE_RECOVERY, false)) {
            Log.i(TAG, "[MAIN] Zombie recovery setup — battery only, no Settings writes");
            if (!SetupHelper.isBatteryExempt(this)) {
                SetupHelper.tryRequestBatteryExemption(this);
            }
            return;
        }
        SetupHelper.runAutoSetup(this);
    }

    private void startBotService() {
        Intent serviceIntent = new Intent(this, BotForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.i(TAG, "[MAIN] BotForegroundService started");
    }

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

    private void updateServiceStatus() {
        if (SetupHelper.isAccessibilityBound()) {
            tvStatus.setText(getString(R.string.service_running));
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            Log.i(TAG, "[MAIN] Accessibility Service status: BOUND");
        } else if (SetupHelper.isAccessibilityEnabled(this)) {
            if (!SetupHelper.isRestrictedSettingsAllowed(this)) {
                tvStatus.setText("Allow restricted settings in App Info");
            } else {
                tvStatus.setText("Enabled in settings — toggle OFF then ON");
            }
            tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
            Log.w(TAG, "[MAIN] Accessibility Service status: ZOMBIE (settings ON, not bound)");
        } else {
            tvStatus.setText(getString(R.string.service_stopped));
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            Log.w(TAG, "[MAIN] Accessibility Service status: STOPPED");
        }
    }
}
