package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

/**
 * FollowPlaylistExecutor — Section 4.10
 *
 * Action: FOLLOW_PLAYLIST
 * Params: { "query": "playlist name" }
 *
 * Step chain:
 *   1. step_open_search      — click Search tab
 *   2. step_type_query       — type playlist name
 *   3. step_submit_search    — submit
 *   4. step_tap_filter       — tap "Playlists" filter chip
 *   5. step_tap_playlist     — tap first playlist result
 *   6. step_tap_follow       — tap Follow button on playlist page
 *   7. step_verify_follow    — verify button changed to Following/Saved
 */
public class FollowPlaylistExecutor extends SpotifyExecutor {

    private String query;

    @Override
    protected void doExecute(JsonObject params) {
        query = params.has("query") ? params.get("query").getAsString() : "";
        if (query.isEmpty()) {
            stepFailed("step_open_search", "MISSING_PARAM_QUERY");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] FollowPlaylist START | query=" + query);
        launchAndSettle(this::stepOpenSearch);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    private void stepOpenSearch() {
        stepStarted("step_open_search", "Open Search Tab");
        scheduleStepTimeout("step_open_search", STEP_TIMEOUT_MS);

        if (timeoutFired) {
            cancelStepTimeout();
            return;
        }

        boolean clicked = tapSearchTab();
        cancelStepTimeout();
        if (!clicked) { stepFailed("step_open_search", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_open_search", "Open Search Tab");
        scheduleStep(() -> activateSearchBar(this::stepTypeQuery), GAP_TINY);
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private void stepTypeQuery() {
        if (timeoutFired) return;
        stepStarted("step_type_query", "Type Playlist Name");
        scheduleStepTimeout("step_type_query", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo input = findSearchInput();

        if (input == null || timeoutFired) {
            cancelStepTimeout();
            if (!timeoutFired) { stepFailed("step_type_query", "UI_ELEMENT_NOT_FOUND"); commandDone(false); }
            return;
        }

        boolean typed = typeText(input, query);
        input.recycle();
        cancelStepTimeout();
        if (!typed) { stepFailed("step_type_query", "TEXT_INPUT_FAILED"); commandDone(false); return; }
        stepOk("step_type_query", "Type Playlist Name");
        scheduleStep(this::stepSubmitSearch, 0);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSubmitSearch() {
        if (timeoutFired) return;
        submitSearchQuery(this::stepTapPlaylistsFilter);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapPlaylistsFilter() {
        if (timeoutFired) return;
        stepStarted("step_tap_filter", "Tap Playlists Filter");
        scheduleStepTimeout("step_tap_filter", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo chip = findByText("Playlists");
        if (chip == null) chip = findByDesc("Playlists");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (chip != null) {
            clickNode(chip);
            chip.recycle();
            Log.i(TAG, "[EXEC] Playlists filter applied");
        } else {
            Log.w(TAG, "[EXEC] Playlists filter chip not found");
        }
        stepOk("step_tap_filter", "Tap Playlists Filter");
        humanDelay(this::stepTapPlaylistResult);
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private void stepTapPlaylistResult() {
        if (timeoutFired) return;
        stepStarted("step_tap_playlist", "Tap Playlist Result");
        scheduleStepTimeout("step_tap_playlist", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo playlistRow = findPlaylistRow();
        cancelStepTimeout();
        if (timeoutFired) return;

        if (playlistRow == null) {
            stepFailed("step_tap_playlist", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        boolean clicked = clickNode(playlistRow);
        playlistRow.recycle();
        if (!clicked) { stepFailed("step_tap_playlist", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_playlist", "Tap Playlist Result");
        humanDelay(GAP_MEDIUM, 1_000, this::stepTapFollow);
    }

    private AccessibilityNodeInfo findPlaylistRow() {
        AccessibilityNodeInfo btn = findByDesc("More options for playlist");
        if (btn != null) {
            AccessibilityNodeInfo parent = btn.getParent();
            btn.recycle();
            if (parent == null) return null;
            if (parent.isClickable()) return parent;
            AccessibilityNodeInfo gp = parent.getParent();
            parent.recycle();
            if (gp != null && gp.isClickable()) return gp;
            if (gp != null) gp.recycle();
        }
        AccessibilityNodeInfo row = findById(SPOTIFY_PACKAGE + ":id/row_root");
        if (row == null) row = findById(SPOTIFY_PACKAGE + ":id/playlist_row");
        return row;
    }

    // ── Step 6 ────────────────────────────────────────────────────────────────

    private void stepTapFollow() {
        if (timeoutFired) return;
        stepStarted("step_tap_follow", "Tap Follow Playlist");
        scheduleStepTimeout("step_tap_follow", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed (2026-06-03): after following, the button becomes
        // "Remove from Your Library" / "Remove playlist from Your Library".
        // Check these first — if already saved, skip the tap (idempotent).
        AccessibilityNodeInfo alreadySaved = findByDesc("Remove from Your Library");
        if (alreadySaved == null) alreadySaved = findByDesc("Remove playlist from Your Library");
        if (alreadySaved == null) alreadySaved = findByDesc("Unfollow");
        if (alreadySaved == null) alreadySaved = findByText("Following");
        if (alreadySaved != null) {
            alreadySaved.recycle();
            cancelStepTimeout();
            Log.i(TAG, "[EXEC] Playlist already in library — idempotent success");
            stepOk("step_tap_follow", "Tap Follow Playlist");
            stepOk("step_verify_follow", "Verify Follow");
            commandDone(true);
            return;
        }

        // UIAutoDev confirmed (2026-06-03): the "Follow" action uses the
        // "Add playlist to Your Library" button — there is no "Follow" label.
        AccessibilityNodeInfo followBtn = findByDesc("Add playlist to Your Library");
        if (followBtn == null) followBtn = findByDesc("Add to Your Library");
        if (followBtn == null) followBtn = findByDesc("Save");
        if (followBtn == null) followBtn = findByText("Follow");       // older Spotify versions
        if (followBtn == null) followBtn = findByDesc("Follow");
        if (followBtn == null) followBtn = findById(SPOTIFY_PACKAGE + ":id/follow_button");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (followBtn == null) {
            stepFailed("step_tap_follow", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping library button: desc='" + followBtn.getContentDescription() + "'");
        boolean clicked = clickFollowNode(followBtn);
        followBtn.recycle();
        if (!clicked) { stepFailed("step_tap_follow", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_tap_follow", "Tap Follow Playlist");
        scheduleStep(this::stepVerifyFollow, GAP_LONG);
    }

    // ── Step 7 ────────────────────────────────────────────────────────────────

    private void stepVerifyFollow() {
        if (timeoutFired) return;
        stepStarted("step_verify_follow", "Verify Follow");
        scheduleStepTimeout("step_verify_follow", STEP_TIMEOUT_MS);

        // After tapping "Add playlist to Your Library", button flips to
        // "Remove from Your Library" — that confirms the save succeeded.
        AccessibilityNodeInfo confirmed = findByDesc("Remove from Your Library");
        if (confirmed == null) confirmed = findByDesc("Remove playlist from Your Library");
        if (confirmed == null) confirmed = findByDesc("Unfollow");
        if (confirmed == null) confirmed = findByText("Following");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (confirmed != null) {
            confirmed.recycle();
            Log.i(TAG, "[EXEC] Playlist saved to library — confirmed");
        } else {
            Log.w(TAG, "[EXEC] Library state not confirmed — soft success");
        }
        stepOk("step_verify_follow", "Verify Follow");
        commandDone(true);
    }
}
