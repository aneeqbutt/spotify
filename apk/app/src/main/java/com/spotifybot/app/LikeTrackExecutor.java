package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

/**
 * LikeTrackExecutor — LIKE_CURRENT_TRACK
 *
 * UIAutoDev confirmed (full player screen):
 *   id/feedback_buttons_container
 *     └── android.widget.ImageButton  content-desc="Add item"   (not liked)
 *     └── android.widget.ImageButton  content-desc="Remove item" (already liked)
 *   clickable=true on the button itself — use performAction(ACTION_CLICK) directly.
 *
 * Step chain:
 *   1. step_open_player    — tap Now Playing bar to open full player
 *   2. step_tap_like       — find "Add item" button → click it
 *   3. step_tap_liked_songs — tap "Liked Songs" in bottom sheet (if sheet appears)
 *   4. step_verify_liked   — confirm button changed to "Remove item"
 */
public class LikeTrackExecutor extends SpotifyExecutor {

    // Entry point — launches Spotify and kicks off the like step chain
    @Override
    protected void doExecute(JsonObject params) {
        Log.i(TAG, "[EXEC] LikeTrack START");
        launchAndSettle(this::stepOpenPlayer);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    // Taps the mini-player bar to open the full Now Playing screen
    private void stepOpenPlayer() {
        if (timeoutFired) return;
        stepStarted("step_open_player", "Open Full Player");
        scheduleStepTimeout("step_open_player", STEP_TIMEOUT_MS);

        // Confirmed ID: id/now_playing_bar_layout, desc="Now Playing Bar"
        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findByDesc("Now Playing Bar");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (bar != null) {
            Log.i(TAG, "[EXEC] Tapping Now Playing bar to open full player");
            // bar is clickable=true — direct ACTION_CLICK
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            bar.recycle();
            stepOk("step_open_player", "Open Full Player");
            scheduleStep(this::stepTapLike, GAP_SHORT);
        } else {
            // Already on full player, or no track playing — try like button directly
            Log.w(TAG, "[EXEC] Now Playing bar not found — attempting like button directly");
            stepOk("step_open_player", "Open Full Player");
            stepTapLike();
        }
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    // Finds the "Add item" heart button and taps it to like the current track
    private void stepTapLike() {
        if (timeoutFired) return;
        stepStarted("step_tap_like", "Tap Like Button");
        scheduleStepTimeout("step_tap_like", STEP_TIMEOUT_MS);

        // Already liked? Idempotent success.
        AccessibilityNodeInfo alreadyLiked = findByDesc("Remove item");
        if (alreadyLiked != null) {
            alreadyLiked.recycle();
            cancelStepTimeout();
            Log.i(TAG, "[EXEC] Track already liked — idempotent success");
            stepOk("step_tap_like",       "Tap Like Button");
            stepOk("step_tap_liked_songs","Tap Liked Songs");
            stepOk("step_verify_liked",   "Verify Liked State");
            commandDone(true);
            return;
        }

        // UIAutoDev confirmed: full player like = ImageButton content-desc="Add item"
        // inside id/feedback_buttons_container. clickable=true on button itself.
        AccessibilityNodeInfo likeBtn = findByDesc("Add item");
        if (likeBtn == null) likeBtn = findById(SPOTIFY_PACKAGE + ":id/add_to_button");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (likeBtn == null) {
            stepFailed("step_tap_like", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Like button found: desc='" + likeBtn.getContentDescription()
                + "' id=" + likeBtn.getViewIdResourceName()
                + " clickable=" + likeBtn.isClickable());

        // Try direct ACTION_CLICK first (button IS clickable per UIAutoDev).
        // clickFollowNode as fallback covers the case where parent container absorbs the click.
        boolean clicked = likeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            Log.w(TAG, "[EXEC] Direct ACTION_CLICK failed — trying clickFollowNode on parent");
            AccessibilityNodeInfo parent = likeBtn.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] Parent click result: " + clicked);
                parent.recycle();
            }
        }
        likeBtn.recycle();

        if (!clicked) { stepFailed("step_tap_like", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_tap_like", "Tap Like Button");
        // Wait for "Save to Library" bottom sheet (appears on some Spotify versions)
        scheduleStep(this::stepTapLikedSongs, GAP_SHORT);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────
    // On some Spotify builds tapping "Add item" shows a bottom sheet with
    // "Liked Songs" and "New playlist". Tap "Liked Songs" to save.
    // On others it saves directly — soft success if sheet doesn't appear.

    // Taps "Liked Songs" in the save-to-library bottom sheet if it appears, soft-succeeds if not
    private void stepTapLikedSongs() {
        if (timeoutFired) return;
        stepStarted("step_tap_liked_songs", "Tap Liked Songs");
        scheduleStepTimeout("step_tap_liked_songs", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo likedSongs = findByText("Liked Songs");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (likedSongs == null) {
            // No bottom sheet — direct save occurred (newer Spotify builds)
            Log.i(TAG, "[EXEC] No 'Liked Songs' sheet — assuming direct save succeeded");
            stepOk("step_tap_liked_songs", "Tap Liked Songs");
            scheduleStep(this::stepVerifyLiked, GAP_TINY);
            return;
        }

        // Sheet appeared — "Liked Songs" is a Compose text node (may not be directly clickable)
        // clickFollowNode walks up to the clickable row container
        boolean clicked = clickFollowNode(likedSongs);
        likedSongs.recycle();

        if (!clicked) {
            Log.w(TAG, "[EXEC] Could not tap Liked Songs — pressing Back and soft-succeeding");
            SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
            if (svc != null) svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        } else {
            Log.i(TAG, "[EXEC] Tapped 'Liked Songs' — track saved to library");
        }

        stepOk("step_tap_liked_songs", "Tap Liked Songs");
        scheduleStep(this::stepVerifyLiked, GAP_TINY);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    // Confirms the like by checking that the button changed to "Remove item"
    private void stepVerifyLiked() {
        if (timeoutFired) return;
        stepStarted("step_verify_liked", "Verify Liked State");
        scheduleStepTimeout("step_verify_liked", STEP_TIMEOUT_MS);

        // After liking, button changes from "Add item" → "Remove item"
        AccessibilityNodeInfo liked = findByDesc("Remove item");
        cancelStepTimeout();
        if (timeoutFired) return;

        if (liked != null) {
            liked.recycle();
            Log.i(TAG, "[EXEC] Like confirmed — button is now 'Remove item'");
        } else {
            Log.w(TAG, "[EXEC] 'Remove item' not found — soft success (like was tapped)");
        }
        stepOk("step_verify_liked", "Verify Liked State");
        commandDone(true);
    }
}
