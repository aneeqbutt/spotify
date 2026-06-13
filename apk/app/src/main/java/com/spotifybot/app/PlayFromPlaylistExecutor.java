package com.spotifybot.app;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

import java.util.ArrayList;
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
    /** Like/skip counts from the dashboard form — performed after playback starts. */
    private int likeTarget;
    private int skipTarget;

    /** Retry counter for stepTapPlay */
    private int     playBtnAttempts           = 0;
    private int     resultScrollCount         = 0;
    private int     playlistsFilterAttempts   = 0;
    /** True once the Playlists filter chip is confirmed. */
    private boolean playlistFilterApplied     = false;

    private static final int MAX_PLAYLISTS_FILTER_ATTEMPTS = 12;

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
        likeTarget  = params.has("likes")        ? params.get("likes").getAsInt()        : 0;
        skipTarget  = params.has("skips")        ? params.get("skips").getAsInt()        : 0;
        resultScrollCount       = 0;
        playlistsFilterAttempts = 0;
        playlistFilterApplied   = false;

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

        Log.i(TAG, "[EXEC] PlayFromPlaylist START | query=" + query
                + " | likes=" + likeTarget + " skips=" + skipTarget);
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
        runTypeQueryStep("step_type_query", "Type Playlist Name", query,
                () -> scheduleStep(this::stepSubmitSearch, 0));
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSubmitSearch() {
        if (timeoutFired) return;
        submitSearchQuery(this::stepTapPlaylistsFilter);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapPlaylistsFilter() {
        if (timeoutFired) return;

        if (playlistsFilterAttempts == 0) {
            stepStarted("step_tap_filter", "Tap Playlists Tab");
            scheduleStepTimeout("step_tap_filter", 25_000);
        }
        playlistsFilterAttempts++;

        AccessibilityNodeInfo chip = findChipLabelNode("Playlists");
        if (chip == null) {
            if (playlistsFilterAttempts == 4 || playlistsFilterAttempts == 8) {
                Log.w(TAG, "[EXEC] Playlists chip missing — re-submitting search (attempt "
                        + playlistsFilterAttempts + ")");
                submitSearchQuery(() -> scheduleStep(this::stepTapPlaylistsFilter, GAP_MEDIUM));
                return;
            }
            if (playlistsFilterAttempts < MAX_PLAYLISTS_FILTER_ATTEMPTS) {
                Log.i(TAG, "[EXEC] Playlists chip not on screen yet — retry "
                        + playlistsFilterAttempts + "/" + MAX_PLAYLISTS_FILTER_ATTEMPTS);
                scheduleStep(this::stepTapPlaylistsFilter, GAP_SHORT);
                return;
            }
            cancelStepTimeout();
            if (timeoutFired) return;
            stepFailed("step_tap_filter", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        if (isFilterChipSelected(chip)) {
            chip.recycle();
            finishPlaylistsFilterStep();
            return;
        }
        chip.recycle();

        if (!ensureFilterChipVisible("Playlists")) {
            scheduleStep(this::stepTapPlaylistsFilter, GAP_SHORT);
            return;
        }

        if (!tapFilterChipByLabel("Playlists")) {
            if (playlistsFilterAttempts < MAX_PLAYLISTS_FILTER_ATTEMPTS) {
                scheduleStep(this::stepTapPlaylistsFilter, GAP_SHORT);
                return;
            }
            cancelStepTimeout();
            stepFailed("step_tap_filter", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }
        scheduleStep(this::verifyPlaylistsFilterSelected, jitteredDelay(GAP_LONG));
    }

    private void verifyPlaylistsFilterSelected() {
        if (timeoutFired) return;

        AccessibilityNodeInfo chip = findChipLabelNode("Playlists");
        if (chip != null && isFilterChipSelected(chip)) {
            chip.recycle();
            finishPlaylistsFilterStep();
            return;
        }
        if (chip != null) chip.recycle();

        if (playlistsFilterAttempts >= 2 && hasAnyPlaylistResultRow()) {
            Log.i(TAG, "[EXEC] Playlists filter verified via playlist result rows");
            finishPlaylistsFilterStep();
            return;
        }

        if (playlistsFilterAttempts < MAX_PLAYLISTS_FILTER_ATTEMPTS) {
            Log.w(TAG, "[EXEC] Playlists chip not selected yet — re-tapping (attempt "
                    + playlistsFilterAttempts + ")");
            scheduleStep(this::stepTapPlaylistsFilter, GAP_SHORT);
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) return;
        stepFailed("step_tap_filter", "FILTER_NOT_APPLIED");
        commandDone(false);
    }

    private void finishPlaylistsFilterStep() {
        cancelStepTimeout();
        if (timeoutFired) return;
        playlistFilterApplied = true;
        stepOk("step_tap_filter", "Tap Playlists Tab");
        Log.i(TAG, "[EXEC] Playlists filter confirmed — proceeding to result tap");
        scheduleStep(this::stepTapPlaylistResult, jitteredDelay(GAP_SHORT));
    }

    private boolean hasAnyPlaylistResultRow() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/title");
        root.recycle();
        if (titles == null) return false;
        for (AccessibilityNodeInfo titleNode : titles) {
            if (titleNode == null) continue;
            AccessibilityNodeInfo parent = titleNode.getParent();
            titleNode.recycle();
            if (parent == null) continue;
            boolean playlist = hasPlaylistSubtitle(parent) && !hasSongSubtitle(parent);
            parent.recycle();
            if (playlist) return true;
        }
        return false;
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private static final int MAX_RESULT_SCROLLS = 4;

    private void stepTapPlaylistResult() {
        if (timeoutFired) return;

        if (resultScrollCount == 0) {
            stepStarted("step_tap_playlist", "Tap Playlist Result");
            scheduleStepTimeout("step_tap_playlist", STEP_TIMEOUT_MS);
        }

        AccessibilityNodeInfo playlistRow = findPlaylistRow();

        if (playlistRow == null) {
            if (resultScrollCount < MAX_RESULT_SCROLLS) {
                resultScrollCount++;
                Log.i(TAG, "[EXEC] Playlist not found — scrolling down ("
                        + resultScrollCount + "/" + MAX_RESULT_SCROLLS + ")");
                scrollResultsList();
                scheduleStep(this::stepTapPlaylistResult, GAP_MEDIUM);
                return;
            }
            cancelStepTimeout();
            if (timeoutFired) return;
            Log.e(TAG, "[EXEC] findPlaylistRow: no row after " + MAX_RESULT_SCROLLS + " scrolls");
            stepFailed("step_tap_playlist", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            resultScrollCount = 0;
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) { playlistRow.recycle(); return; }

        Log.i(TAG, "[EXEC] Tapping playlist row (scroll=" + resultScrollCount + "): desc='"
                + playlistRow.getContentDescription() + "'");
        boolean clicked = tapAtNodeRandom(playlistRow);
        if (!clicked) {
            AccessibilityNodeInfo gp = playlistRow.getParent();
            if (gp != null) {
                clicked = gp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] playlistRow tap failed — tried grandparent: " + clicked);
                gp.recycle();
            }
        }
        playlistRow.recycle();
        resultScrollCount = 0;

        if (!clicked) { stepFailed("step_tap_playlist", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_playlist", "Tap Playlist Result");
        playBtnAttempts = 0;
        waitForCollectionPlayButton(this::stepTapPlay);
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

            boolean isPlaylist = hasPlaylistSubtitle(parent)
                    || (playlistFilterApplied && !hasSongSubtitle(parent)
                    && !looksLikeSongTitle(text));
            Log.d(TAG, "[EXEC] findPlaylistRow: title='" + text + "' filterApplied=" + playlistFilterApplied
                    + " isPlaylist=" + isPlaylist);

            if (isPlaylist) {
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findPlaylistRow: match confirmed");
                return parent;
            }

            parent.recycle();
        }

        Log.w(TAG, "[EXEC] findPlaylistRow: no playlist row found for query='" + query + "'");
        return null;
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
        AccessibilityNodeInfo playBtn = findCollectionPlayButton();

        cancelStepTimeout();
        if (timeoutFired) return;

        if (playBtn == null) {
            playBtnAttempts++;
            if (playBtnAttempts <= 5) {
                Log.w(TAG, "[EXEC] Play button not found — retry attempt "
                        + playBtnAttempts + "/5 in 300ms");
                scheduleStep(this::stepTapPlay, GAP_SHORT);
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
        scheduleStep(this::stepVerifyPlayback, GAP_SHORT);
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

        // Spotify's Shuffle Play / Play button on the playlist page can land in a
        // "ready but paused" state — the Now Playing bar appears but the track
        // hasn't actually started.  If the now-playing play/pause button shows
        // "Play" (not "Pause"), tap it here to force the track to start before
        // the monitor begins watching.
        AccessibilityNodeInfo playBtn =
                findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (playBtn != null) {
            CharSequence cd = playBtn.getContentDescription();
            if (cd != null && cd.toString().startsWith("Play")) {
                Log.i(TAG, "[EXEC] Playlist track paused at verify step — tapping Play to start");
                tapAtNodeRandom(playBtn);
            }
            playBtn.recycle();
        }

        stepOk("step_verify_play", "Verify Playback");
        // Capture runId before commandDone() — monitor uses it for PLAYBACK_FINISHED.
        final String monitorRunId = runId;

        // Spread the requested likes/skips across playback (human-paced), THEN finish.
        // Same choreography the album executor uses; soft-fails individual actions so a
        // missing like/skip never fails the whole play command. No-op when both are 0.
        runLikeSkipChoreography(likeTarget, skipTarget, () -> {
            commandDone(true);
            // After playlist starts, poll position_text vs duration_text and notify the
            // backend when the first track finishes so the next session task fires at
            // the right time instead of immediately.
            startPlaybackMonitor(monitorRunId);
        });
    }
}
