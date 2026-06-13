package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

/**
 * SkipTrackExecutor — SKIP_TRACK
 *
 * UIAutoDev confirmed (full player screen):
 *   id/playback_controls_container
 *     └── 3 android.widget.ImageButton  content-desc="Next"  clickable=TRUE
 *   No resource-id — must use content-desc.
 *
 * Step chain:
 *   1. step_open_player — tap Now Playing bar to expand full player
 *   2. step_tap_skip    — find "Next" button → performAction(ACTION_CLICK)
 *   3. step_verify_skip — confirm playback still active (soft success)
 */
public class SkipTrackExecutor extends SpotifyExecutor {

    // Entry point — launches Spotify and starts the skip step chain
    @Override
    protected void doExecute(JsonObject params) {
        Log.i(TAG, "[EXEC] SkipTrack START");
        launchAndSettle(this::stepOpenPlayer);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    // Taps the mini-player bar to open the full Now Playing screen
    private void stepOpenPlayer() {
        if (timeoutFired) return;
        stepStarted("step_open_player", "Open Full Player");
        scheduleStepTimeout("step_open_player", STEP_TIMEOUT_MS);

        // Confirmed ID: id/now_playing_bar_layout  desc="Now Playing Bar"
        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findByDesc("Now Playing Bar");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (bar != null) {
            Log.i(TAG, "[EXEC] Tapping Now Playing bar to open full player");
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            bar.recycle();
            stepOk("step_open_player", "Open Full Player");
            scheduleStep(this::stepTapSkip, GAP_SHORT);
        } else {
            // Already on full player or no track playing — try skip directly
            Log.w(TAG, "[EXEC] Now Playing bar not found — trying skip in current view");
            stepOk("step_open_player", "Open Full Player");
            stepTapSkip();
        }
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    // Finds the "Next" button by content-desc and taps it to skip the track
    private void stepTapSkip() {
        if (timeoutFired) return;
        stepStarted("step_tap_skip", "Tap Skip Button");
        scheduleStepTimeout("step_tap_skip", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: content-desc="Next", android.widget.ImageButton, clickable=TRUE
        // Inside id/playback_controls_container, index=3, no resource-id.
        AccessibilityNodeInfo skipBtn = findByDesc("Next");
        if (skipBtn == null) skipBtn = findByDesc("Next track");
        if (skipBtn == null) skipBtn = findByDesc("Skip to next track");
        if (skipBtn == null) skipBtn = findByDesc("Skip forward");
        if (skipBtn == null) skipBtn = findById(SPOTIFY_PACKAGE + ":id/skip_next_button");
        if (skipBtn == null) skipBtn = findById(SPOTIFY_PACKAGE + ":id/btn_next");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (skipBtn == null) {
            stepFailed("step_tap_skip", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Skip button found: desc='" + skipBtn.getContentDescription()
                + "' clickable=" + skipBtn.isClickable());

        // clickable=true confirmed — performAction(ACTION_CLICK) directly
        boolean clicked = skipBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        skipBtn.recycle();

        if (!clicked) { stepFailed("step_tap_skip", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_skip", "Tap Skip Button");
        scheduleStep(this::stepVerifySkip, GAP_SHORT);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    // Soft-verifies the skip by checking that the Now Playing bar is still visible
    private void stepVerifySkip() {
        if (timeoutFired) return;
        stepStarted("step_verify_skip", "Verify Skip");
        scheduleStepTimeout("step_verify_skip", STEP_TIMEOUT_MS);

        // Verify playback is still running after the skip
        AccessibilityNodeInfo nowPlaying = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (nowPlaying == null) nowPlaying = findByDesc("Now Playing Bar");
        if (nowPlaying == null) nowPlaying = findByDesc("Pause");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (nowPlaying != null) {
            nowPlaying.recycle();
            Log.i(TAG, "[EXEC] Skip confirmed — playback still active");
        } else {
            Log.w(TAG, "[EXEC] Now Playing bar not visible — soft success (skip button was tapped)");
        }
        stepOk("step_verify_skip", "Verify Skip");
        commandDone(true);
    }
}
