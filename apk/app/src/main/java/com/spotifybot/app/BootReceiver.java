package com.spotifybot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver — Keeps BotForegroundService alive across reboots and screen unlocks.
 *
 * Handles three intents:
 *   BOOT_COMPLETED   — phone just booted; start the service automatically
 *   SCREEN_ON        — screen turned on (may be lock screen); trigger a restart attempt
 *   USER_PRESENT     — user dismissed the lock screen; definitive "device is usable" signal
 *
 * Why SCREEN_ON + USER_PRESENT?
 *   If the WakeLock kept everything alive the service is already running and the
 *   startForegroundService call is a harmless no-op (onStartCommand fires again and
 *   the wsClient != null guard handles the duplicate gracefully).
 *   If Samsung killed the service despite the WakeLock, this receiver brings it back
 *   the moment the screen turns on — no manual intervention needed.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SpotifyBot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                Log.i(TAG, "[BOOT] Device booted — starting BotForegroundService");
                startService(context);
                break;

            case Intent.ACTION_SCREEN_ON:
                Log.i(TAG, "[BOOT] Screen on — ensuring BotForegroundService is running");
                startService(context);
                break;

            case Intent.ACTION_USER_PRESENT:
                Log.i(TAG, "[BOOT] User unlocked — ensuring BotForegroundService is running");
                startService(context);
                break;
        }
    }

    private void startService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, BotForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "[BOOT] Failed to start BotForegroundService: " + e.getMessage());
        }
    }
}
