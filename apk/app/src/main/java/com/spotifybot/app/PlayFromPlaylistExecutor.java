package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * PlayFromPlaylistExecutor — Section 4.5
 *
 * Action: PLAY_FROM_PLAYLIST
 * Params: { "query": "playlist name", "daily_limit": 0, "hourly_limit": 0 }
 *
 * Step chain:
 *   1. step_open_search      — click Search tab
 *   2. step_type_query       — type playlist name
 *   3. step_submit_search    — submit query
 *   4. step_tap_filter       — tap "Playlists" filter chip
 *   5. step_tap_playlist     — tap first playlist result
 *   6. step_tap_play         — tap Play / Shuffle Play on the playlist page
 *   7. step_verify_play      — confirm Now Playing bar
 */
public class PlayFromPlaylistExecutor extends SpotifyExecutor {

    private String query;
    private int dailyLimit;
    private int hourlyLimit;

    /** Retry counter for stepTapPlay */
    private int playBtnAttempts = 0;

    @Override
    protected void doExecute(JsonObject params) {
        query = params.has("query") ? params.get("query").getAsString() : "";
        if (query.isEmpty()) {
            stepFailed("step_open_search", "MISSING_PARAM_QUERY");
            commandDone(false);
            return;
        }
        dailyLimit  = params.has("daily_limit")  ? params.get("daily_limit").getAsInt()  : 0;
        hourlyLimit = params.has("hourly_limit") ? params.get("hourly_limit").getAsInt() : 0;

        if (PlaybackLimits.isDailyLimitReached(dailyLimit)) {
            stepFailed("step_open_search", "DAILY_LIMIT_REACHED");
            commandDone(false);
            return;
        }
        if (PlaybackLimits.isHourlyLimitReached(hourlyLimit)) {
            stepFailed("step_open_search", "HOURLY_LIMIT_REACHED");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] PlayFromPlaylist START | query=" + query);
        launchAndSettle(this::stepOpenSearch);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    private void stepOpenSearch() {
        stepStarted("step_open_search", "Open Search Tab");
        scheduleStepTimeout("step_open_search", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo searchTab = findByDesc("Search, Tab");
        if (searchTab == null) searchTab = findByDesc("Search tab");
        if (searchTab == null) searchTab = findByDesc("Search");

        if (searchTab == null || timeoutFired) {
            cancelStepTimeout();
            if (!timeoutFired) { stepFailed("step_open_search", "UI_ELEMENT_NOT_FOUND"); commandDone(false); }
            return;
        }

        boolean clicked = clickNode(searchTab);
        searchTab.recycle();
        cancelStepTimeout();
        if (!clicked) { stepFailed("step_open_search", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_open_search", "Open Search Tab");
        handler.postDelayed(this::stepActivateSearchBar, 1_500);
    }

    private void stepActivateSearchBar() {
        if (timeoutFired) return;
        AccessibilityNodeInfo searchBar = findById(SPOTIFY_PACKAGE + ":id/query");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/search_text_field_wrapper");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/query_text_wrapper");
        if (searchBar == null) searchBar = findByDesc("Search for something to listen to");
        if (searchBar == null) searchBar = findByText("What do you want to listen to?");
        if (searchBar != null) {
            clickNode(searchBar);
            searchBar.recycle();
            Log.i(TAG, "[EXEC] Search bar tapped — waiting for keyboard");
        } else {
            Log.w(TAG, "[EXEC] Search bar not found in activate step — proceeding anyway");
        }
        handler.postDelayed(this::stepTypeQuery, 1_500);
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private void stepTypeQuery() {
        if (timeoutFired) return;
        stepStarted("step_type_query", "Type Playlist Name");
        scheduleStepTimeout("step_type_query", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo input = findById(SPOTIFY_PACKAGE + ":id/query");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_edittext");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_query");
        if (input == null) input = findByText("What do you want to listen to?");

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
        handler.postDelayed(this::stepSubmitSearch, 1_500);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSubmitSearch() {
        if (timeoutFired) return;

        // Always submit via IME Enter → full results page → findPlaylistRow() handles matching.
        // NEVER use findByText(query) or findByText("Playlist") — too broad, matches filter
        // chips and other UI text, causing wrong taps.
        AccessibilityNodeInfo input = findById(SPOTIFY_PACKAGE + ":id/query");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_edittext");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_query");
        if (input != null) {
            input.performAction(0x01000000); // ACTION_IME_ENTER — dismisses keyboard + shows results
            input.recycle();
            Log.i(TAG, "[EXEC] IME Enter submitted — waiting for results");
        } else {
            Log.w(TAG, "[EXEC] Search input not found for IME Enter");
        }
        handler.postDelayed(this::stepTapPlaylistsFilter, 3_000);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapPlaylistsFilter() {
        if (timeoutFired) return;
        stepStarted("step_tap_filter", "Tap Playlists Filter");
        scheduleStepTimeout("step_tap_filter", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo playlistsChip = findByText("Playlists");
        if (playlistsChip == null) playlistsChip = findByDesc("Playlists");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (playlistsChip != null) {
            clickNode(playlistsChip);
            playlistsChip.recycle();
            Log.i(TAG, "[EXEC] Playlists filter applied");
        } else {
            Log.w(TAG, "[EXEC] Playlists filter chip not found — continuing without filter");
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

        Log.i(TAG, "[EXEC] Tapping playlist row: desc='" + playlistRow.getContentDescription() + "'");
        boolean clicked = playlistRow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo gp = playlistRow.getParent();
            if (gp != null) {
                clicked = gp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] playlistRow self-click failed — tried grandparent: " + clicked);
                gp.recycle();
            }
        } else {
            Log.i(TAG, "[EXEC] playlistRow self-click succeeded");
        }
        playlistRow.recycle();
        if (!clicked) { stepFailed("step_tap_playlist", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_playlist", "Tap Playlist Result");
        playBtnAttempts = 0;
        humanDelay(4_000, 6_000, this::stepTapPlay);
    }

    /**
     * Find the playlist result row matching query using UIAutoDev-confirmed structure.
     *
     *   id/row_root  (ViewGroup, clickable=TRUE for playlist rows)
     *     ├── id/artwork
     *     ├── id/title    text="Playlist Name"
     *     ├── id/subtitle text="Playlist • Owner"
     *     └── id/action_bar (three-dot)
     *
     * Strategy: id/title text match → confirm subtitle starts with "Playlist •" → return parent id/row_root.
     */
    private AccessibilityNodeInfo findPlaylistRow() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/title");
        root.recycle();

        if (titles == null || titles.isEmpty()) {
            Log.w(TAG, "[EXEC] findPlaylistRow: no id/title nodes on screen");
            return null;
        }

        String queryLower = query.toLowerCase();
        AccessibilityNodeInfo fallback = null;

        for (int i = 0; i < titles.size(); i++) {
            AccessibilityNodeInfo titleNode = titles.get(i);
            if (titleNode == null) continue;

            CharSequence text = titleNode.getText();
            boolean matches = text != null && text.toString().toLowerCase().contains(queryLower);

            if (!matches) {
                titleNode.recycle();
                continue;
            }

            AccessibilityNodeInfo parent = titleNode.getParent();
            titleNode.recycle();
            if (parent == null) continue;

            if (hasPlaylistSubtitle(parent)) {
                if (fallback != null) fallback.recycle();
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findPlaylistRow: confirmed via id/title + 'Playlist •' subtitle");
                return parent;
            }

            if (fallback == null) {
                fallback = parent;
            } else {
                parent.recycle();
            }
        }

        if (fallback != null) Log.w(TAG, "[EXEC] findPlaylistRow: using title-only fallback");
        else Log.w(TAG, "[EXEC] findPlaylistRow: no id/title matching query='" + query + "'");
        return fallback;
    }

    /**
     * UIAutoDev confirmed: subtitle text="Playlist" (plain, no bullet).
     * Also handles "Playlist • Owner" format for some Spotify versions.
     */
    private boolean hasPlaylistSubtitle(AccessibilityNodeInfo parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence txt = child.getText();
            child.recycle();
            if (txt != null) {
                String s = txt.toString();
                // Confirmed from UIAutoDev: subtitle = "Playlist" (exact)
                // Also covers "Playlist • Owner" variants
                if (s.equalsIgnoreCase("Playlist")
                        || s.startsWith("Playlist •")
                        || s.startsWith("Playlist ·")
                        || s.startsWith("Playlist•")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Step 6 ────────────────────────────────────────────────────────────────

    private void stepTapPlay() {
        if (timeoutFired) return;
        if (playBtnAttempts == 0) {
            // Only send STEP_STARTED on first attempt
            stepStarted("step_tap_play", "Tap Play on Playlist");
        }
        scheduleStepTimeout("step_tap_play", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: collection page play button =
        //   android.widget.ImageView  id/button_play_and_pause  clickable=FALSE
        //   parent: android.widget.Button  (clickable — absorbs the click)
        // Use clickFollowNode which tries ACTION_CLICK on node then walks up to parent Button.
        AccessibilityNodeInfo playBtn = findById(SPOTIFY_PACKAGE + ":id/button_play_and_pause");
        if (playBtn == null) playBtn = findById(SPOTIFY_PACKAGE + ":id/play_button");
        if (playBtn == null) playBtn = findById(SPOTIFY_PACKAGE + ":id/shuffle_button");
        if (playBtn == null) playBtn = findById(SPOTIFY_PACKAGE + ":id/btn_play");
        if (playBtn == null) playBtn = findById(SPOTIFY_PACKAGE + ":id/header_play_pause_btn");
        if (playBtn == null) playBtn = findByDesc("Shuffle play");
        if (playBtn == null) playBtn = findByDesc("Shuffle Play");
        if (playBtn == null) playBtn = findByDesc("Play"); // broadest — checked last

        cancelStepTimeout();
        if (timeoutFired) return;

        if (playBtn == null) {
            playBtnAttempts++;
            if (playBtnAttempts <= 2) {
                // Playlist page may not have fully rendered yet — retry after 2.5s
                Log.w(TAG, "[EXEC] Play button not found — retry attempt " + playBtnAttempts + "/2 in 2500ms");
                handler.postDelayed(this::stepTapPlay, 2_500);
                return;
            }
            // After 2 retries still not found — hard fail so we can diagnose
            stepFailed("step_tap_play", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping Play (attempt " + (playBtnAttempts + 1) + "): desc='"
                + playBtn.getContentDescription() + "'");
        // clickFollowNode — playlist page play buttons are often Compose and report isClickable()=false
        boolean clicked = clickFollowNode(playBtn);
        playBtn.recycle();
        if (!clicked) { stepFailed("step_tap_play", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_play", "Tap Play on Playlist");
        handler.postDelayed(this::stepVerifyPlayback, 2_500);
    }

    // ── Step 7 ────────────────────────────────────────────────────────────────

    private void stepVerifyPlayback() {
        if (timeoutFired) return;
        stepStarted("step_verify_play", "Verify Playback");
        scheduleStepTimeout("step_verify_play", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo nowPlaying = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (nowPlaying == null) nowPlaying = findByDesc("Now Playing Bar");
        if (nowPlaying == null) nowPlaying = findByDesc("Pause");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (nowPlaying != null) {
            nowPlaying.recycle();
            PlaybackLimits.incrementPlayed();
            Log.i(TAG, "[EXEC] Playback confirmed. Daily=" + PlaybackLimits.getDailyCount()
                    + " Hourly=" + PlaybackLimits.getHourlyCount());
        } else {
            Log.w(TAG, "[EXEC] Now Playing bar not found — soft success");
        }
        stepOk("step_verify_play", "Verify Playback");
        // Capture runId before commandDone() — monitor uses it for PLAYBACK_FINISHED.
        String monitorRunId = runId;
        commandDone(true);
        // After playlist starts, poll position_text vs duration_text and notify the
        // backend when the first track finishes so the next session task fires at
        // the right time instead of immediately.
        startPlaybackMonitor(monitorRunId);
    }
}
