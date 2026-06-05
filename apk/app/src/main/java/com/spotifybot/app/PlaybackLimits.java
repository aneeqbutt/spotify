package com.spotifybot.app;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlaybackLimits — Section 4.15 (Daily/Hourly Limits & Clone Blocking)
 *
 * Static in-memory counters that persist across commands within the same app lifecycle.
 * Counters auto-reset after their respective time windows expire.
 *
 * Usage:
 *   Before playing: check isDailyLimitReached() / isHourlyLimitReached()
 *   After confirmed playback: call incrementPlayed()
 *   Clone blocking: call isClonedAction() before repeating the same action on same track
 */
public class PlaybackLimits {

    private static final String TAG = "SpotifyBot";

    // ── Song play counters ────────────────────────────────────────────────────

    private static final AtomicInteger hourlyCount = new AtomicInteger(0);
    private static final AtomicInteger dailyCount  = new AtomicInteger(0);

    private static volatile long hourWindowStart = System.currentTimeMillis();
    private static volatile long dayWindowStart  = System.currentTimeMillis();

    private static final long ONE_HOUR = 3_600_000L;
    private static final long ONE_DAY  = 86_400_000L;

    /**
     * Call after confirmed playback starts to register one song played.
     */
    public static synchronized void incrementPlayed() {
        checkAndResetWindows();
        hourlyCount.incrementAndGet();
        dailyCount.incrementAndGet();
        Log.i(TAG, "[LIMITS] Playback counted — hourly=" + hourlyCount.get()
                + " daily=" + dailyCount.get());
    }

    /**
     * Returns true if the hourly limit has been reached.
     * limit <= 0 means "no limit configured" — always returns false.
     */
    public static synchronized boolean isHourlyLimitReached(int limit) {
        if (limit <= 0) return false;
        checkAndResetWindows();
        boolean reached = hourlyCount.get() >= limit;
        if (reached) Log.w(TAG, "[LIMITS] HOURLY_LIMIT_REACHED: count="
                + hourlyCount.get() + " limit=" + limit);
        return reached;
    }

    /**
     * Returns true if the daily limit has been reached.
     * limit <= 0 means "no limit configured" — always returns false.
     */
    public static synchronized boolean isDailyLimitReached(int limit) {
        if (limit <= 0) return false;
        checkAndResetWindows();
        boolean reached = dailyCount.get() >= limit;
        if (reached) Log.w(TAG, "[LIMITS] DAILY_LIMIT_REACHED: count="
                + dailyCount.get() + " limit=" + limit);
        return reached;
    }

    public static int getHourlyCount() { return hourlyCount.get(); }
    public static int getDailyCount()  { return dailyCount.get(); }

    /** Reset all counters — called on session end or manual reset. */
    public static synchronized void reset() {
        hourlyCount.set(0);
        dailyCount.set(0);
        hourWindowStart = System.currentTimeMillis();
        dayWindowStart  = System.currentTimeMillis();
        Log.i(TAG, "[LIMITS] Counters reset");
    }

    private static void checkAndResetWindows() {
        long now = System.currentTimeMillis();
        if (now - hourWindowStart >= ONE_HOUR) {
            hourlyCount.set(0);
            hourWindowStart = now;
            Log.i(TAG, "[LIMITS] Hourly window reset");
        }
        if (now - dayWindowStart >= ONE_DAY) {
            dailyCount.set(0);
            dayWindowStart = now;
            Log.i(TAG, "[LIMITS] Daily window reset");
        }
    }

    // ── Clone blocking ────────────────────────────────────────────────────────
    // Tracks (action_type + track_id) seen in the current session.
    // Prevents the same like/follow/skip from firing twice on the same content.

    private static final java.util.Set<String> seenActions =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Returns true if this exact (actionType, identifier) pair was already
     * performed in this session — i.e. it's a duplicate / clone.
     */
    public static boolean isClonedAction(String actionType, String identifier) {
        if (identifier == null || identifier.isEmpty()) return false;
        String key = actionType + ":" + identifier.toLowerCase().trim();
        boolean seen = !seenActions.add(key);
        if (seen) Log.w(TAG, "[LIMITS] Clone blocked: " + key);
        return seen;
    }

    /** Clear the seen-actions set (called on session reset). */
    public static void clearCloneSet() {
        seenActions.clear();
        Log.i(TAG, "[LIMITS] Clone set cleared");
    }
}
