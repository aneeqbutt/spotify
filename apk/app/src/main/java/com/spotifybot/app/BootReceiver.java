package com.spotifybot.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver — restart after reboot only.
 *
 * SCREEN_ON / USER_PRESENT were removed: they started BotForegroundService from the
 * background on every unlock, exhausting the dataSync FGS quota on Android 14+ and
 * destabilising the accessibility binding on Samsung.
 *
 * When accessibility is enabled, SpotifyAccessibilityService.onServiceConnected()
 * already starts BotForegroundService — that is the primary path.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SpotifyBot";

    // Handles BOOT_COMPLETED broadcast and starts the bot service via activity trampoline
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "[BOOT] Device booted — starting via activity trampoline");
        ProcessRecovery.launchServiceViaActivity(context);
    }
}
