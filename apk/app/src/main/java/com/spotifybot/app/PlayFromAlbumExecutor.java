package com.spotifybot.app;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * PlayFromAlbumExecutor — Section 4.4
 *
 * Action: PLAY_FROM_ALBUM
 * Params: { "query": "album name", "daily_limit": 0, "hourly_limit": 0 }
 *
 * Step chain:
 *   1. step_open_search     — click Search tab
 *   2. step_type_query      — type album name into search input
 *   3. step_submit_search   — submit via IME Enter → full results page
 *   4. step_tap_filter      — tap "Albums" tab to show albums-only results
 *   5. step_tap_album       — tap album result card matching query
 *   6. step_tap_play        — tap Play / Shuffle Play on the album page
 *   7. step_verify_play     — confirm Now Playing bar visible
 */
public class PlayFromAlbumExecutor extends SpotifyExecutor {

    private String query;
    private int dailyLimit;
    private int hourlyLimit;
    /** Like/skip counts from the dashboard form — performed after playback starts. */
    private int likeTarget;
    private int skipTarget;

    /** Retry counter for stepTapPlay */
    private int     playBtnAttempts        = 0;
    private int     resultScrollCount      = 0;
    private int     albumsFilterAttempts   = 0;
    /** True once the Albums filter chip is confirmed — subtitle check relaxed when active. */
    private boolean albumFilterApplied     = false;
    /** Prevents duplicate result-tap scheduling when filter verification races. */
    private boolean albumResultScheduled   = false;
    /** Prevents double-tap on the album row (select → deselect). */
    private boolean albumRowTapped         = false;
    private int     searchRecoverAttempts  = 0;
    /** True after one physical chip tap — prevents re-tap scroll on Compose LazyRow. */
    private boolean albumsFilterTapped     = false;
    private int     albumsFilterVerifyPolls = 0;

    private static final int MAX_ALBUMS_FILTER_ATTEMPTS = 12;
    private static final int MAX_ALBUMS_FILTER_VERIFY_POLLS = 6;

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
        resultScrollCount    = 0;
        albumsFilterAttempts = 0;
        albumFilterApplied   = false;
        albumResultScheduled = false;
        albumRowTapped       = false;
        searchRecoverAttempts = 0;
        albumsFilterTapped   = false;
        albumsFilterVerifyPolls = 0;

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

        Log.i(TAG, "[EXEC] PlayFromAlbum START | query=" + query
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
        runTypeQueryStep("step_type_query", "Type Album Name", query,
                () -> scheduleStep(this::stepSubmitSearch, 0));
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSubmitSearch() {
        if (timeoutFired) return;
        submitSearchQuery(this::stepTapAlbumsFilter);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapAlbumsFilter() {
        if (timeoutFired) return;

        if (albumsFilterAttempts == 0) {
            stepStarted("step_tap_filter", "Tap Albums Tab");
            scheduleStepTimeout("step_tap_filter", 25_000);
        }
        albumsFilterAttempts++;

        AccessibilityNodeInfo chip = findChipLabelNode("Albums");
        if (chip == null) {
            if (albumsFilterAttempts == 4 || albumsFilterAttempts == 8) {
                Log.w(TAG, "[EXEC] Albums chip missing — re-submitting search (attempt "
                        + albumsFilterAttempts + ")");
                submitSearchQuery(() -> scheduleStep(this::stepTapAlbumsFilter, GAP_MEDIUM));
                return;
            }
            if (albumsFilterAttempts < MAX_ALBUMS_FILTER_ATTEMPTS) {
                Log.i(TAG, "[EXEC] Albums chip not on screen yet — retry "
                        + albumsFilterAttempts + "/" + MAX_ALBUMS_FILTER_ATTEMPTS);
                scheduleStep(this::stepTapAlbumsFilter, GAP_SHORT);
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
            finishAlbumsFilterStep();
            return;
        }
        chip.recycle();

        if (albumsFilterTapped) {
            scheduleStep(this::verifyAlbumsFilterSelected, GAP_SHORT);
            return;
        }

        if (!ensureFilterChipVisible("Albums")) {
            scheduleStep(this::stepTapAlbumsFilter, GAP_SHORT);
            return;
        }

        if (!tapFilterChipByLabel("Albums")) {
            if (albumsFilterAttempts < MAX_ALBUMS_FILTER_ATTEMPTS) {
                scheduleStep(this::stepTapAlbumsFilter, GAP_SHORT);
                return;
            }
            cancelStepTimeout();
            stepFailed("step_tap_filter", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }
        albumsFilterTapped = true;
        albumsFilterVerifyPolls = 0;
        scheduleStep(this::verifyAlbumsFilterSelected, jitteredDelay(GAP_LONG));
    }

    private void verifyAlbumsFilterSelected() {
        if (timeoutFired) return;

        AccessibilityNodeInfo chip = findChipLabelNode("Albums");
        if (chip != null && isFilterChipSelected(chip)) {
            chip.recycle();
            finishAlbumsFilterStep();
            return;
        }
        if (chip != null) chip.recycle();

        if (isFilterComposeVisible() && hasAnyAlbumResultRow()) {
            Log.i(TAG, "[EXEC] Albums filter verified via album result rows");
            finishAlbumsFilterStep();
            return;
        }

        if (albumsFilterTapped && albumsFilterVerifyPolls < MAX_ALBUMS_FILTER_VERIFY_POLLS) {
            albumsFilterVerifyPolls++;
            Log.i(TAG, "[EXEC] Albums filter settling — poll "
                    + albumsFilterVerifyPolls + "/" + MAX_ALBUMS_FILTER_VERIFY_POLLS);
            scheduleStep(this::verifyAlbumsFilterSelected, GAP_MEDIUM);
            return;
        }

        if (albumsFilterAttempts < MAX_ALBUMS_FILTER_ATTEMPTS) {
            Log.w(TAG, "[EXEC] Albums chip not selected yet — re-tapping (attempt "
                    + albumsFilterAttempts + ")");
            albumsFilterTapped = false;
            albumsFilterVerifyPolls = 0;
            scheduleStep(this::stepTapAlbumsFilter, GAP_SHORT);
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) return;
        stepFailed("step_tap_filter", "FILTER_NOT_APPLIED");
        commandDone(false);
    }

    private void finishAlbumsFilterStep() {
        cancelStepTimeout();
        if (timeoutFired) return;
        if (albumResultScheduled) {
            Log.d(TAG, "[EXEC] Albums filter finish skipped — result tap already scheduled");
            return;
        }
        albumResultScheduled = true;
        albumFilterApplied = true;
        stepOk("step_tap_filter", "Tap Albums Tab");
        Log.i(TAG, "[EXEC] Albums filter confirmed — proceeding to result tap");
        scheduleStep(this::stepTapAlbumResult, GAP_BEFORE_CATEGORY_TAP);
    }

    /** Scan visible rows for an album subtitle — proof the Albums filter is active. */
    private boolean hasAnyAlbumResultRow() {
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
            boolean album = hasAlbumSubtitle(parent) && !hasSongSubtitle(parent);
            parent.recycle();
            if (album) return true;
        }
        return false;
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private static final int MAX_RESULT_SCROLLS = 4;

    private void stepTapAlbumResult() {
        if (timeoutFired) return;
        if (albumRowTapped) {
            Log.d(TAG, "[EXEC] stepTapAlbumResult: album row already tapped — skipping");
            return;
        }

        if (!isOnSearchResultsScreen()) {
            if (searchRecoverAttempts < 2) {
                searchRecoverAttempts++;
                Log.w(TAG, "[EXEC] Not on search results (Now Playing?) — Back to recover ("
                        + searchRecoverAttempts + "/2)");
                pressBack();
                scheduleStep(this::stepTapAlbumResult, GAP_MEDIUM);
                return;
            }
            cancelStepTimeout();
            stepFailed("step_tap_album", "UNEXPECTED_STATE");
            commandDone(false);
            return;
        }

        if (resultScrollCount == 0) {
            stepStarted("step_tap_album", "Tap Album Result");
            scheduleStepTimeout("step_tap_album", STEP_TIMEOUT_MS);
        }

        AccessibilityNodeInfo albumRow = findAlbumRow();

        if (albumRow == null) {
            if (resultScrollCount < MAX_RESULT_SCROLLS) {
                resultScrollCount++;
                Log.i(TAG, "[EXEC] Album not found — scrolling down ("
                        + resultScrollCount + "/" + MAX_RESULT_SCROLLS + ")");
                scrollResultsList();
                scheduleStep(this::stepTapAlbumResult, GAP_MEDIUM);
                return;
            }
            cancelStepTimeout();
            if (timeoutFired) return;
            Log.e(TAG, "[EXEC] findAlbumRow: no row after " + MAX_RESULT_SCROLLS + " scrolls");
            dumpScreenNodes();
            stepFailed("step_tap_album", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            resultScrollCount = 0;
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) { albumRow.recycle(); return; }

        Log.i(TAG, "[EXEC] Tapping album row (scroll=" + resultScrollCount + "): desc='"
                + albumRow.getContentDescription() + "'");
        albumRowTapped = true;
        boolean clicked = tapSearchResultRow(albumRow);
        albumRow.recycle();
        resultScrollCount = 0;

        if (!clicked) {
            albumRowTapped = false;
            stepFailed("step_tap_album", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }
        stepOk("step_tap_album", "Tap Album Result");
        playBtnAttempts = 0;
        waitForCollectionPlayButton(this::stepTapPlay);
    }

    /**
     * Find the album result row matching the query using UIAutoDev-confirmed structure.
     *
     * UIAutoDev confirmed:
     *   id/row_root  (ViewGroup, clickable=TRUE for album rows)
     *     ├── id/artwork
     *     ├── id/title            text="ICEMAN"
     *     ├── id/restriction_badge (explicit marker, may not always be present)
     *     ├── id/subtitle         text="Album • Drake"
     *     └── id/action_bar       (three-dot button)
     *
     * Strategy:
     *   1. Get all id/title nodes on screen
     *   2. Find first whose text contains query (case-insensitive)
     *   3. Confirm sibling id/subtitle starts with "Album •" — rules out songs/artists/playlists
     *   4. Return parent id/row_root (clickable=true — ACTION_CLICK works directly)
     */
    private AccessibilityNodeInfo findAlbumRow() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/title");
        root.recycle();

        if (titles == null || titles.isEmpty()) {
            Log.w(TAG, "[EXEC] findAlbumRow: no id/title nodes on screen");
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

            // When Albums filter is confirmed, still reject obvious song rows (e.g. "Starboy (feat. …)").
            boolean isAlbum = (albumFilterApplied && !hasSongSubtitle(parent)
                    && !looksLikeSongTitle(text))
                    || hasAlbumSubtitle(parent);
            Log.d(TAG, "[EXEC] findAlbumRow: title='" + text + "' filterApplied=" + albumFilterApplied
                    + " isAlbum=" + isAlbum);

            if (isAlbum) {
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findAlbumRow: match confirmed");
                return findRowRoot(parent);
            }

            parent.recycle();
        }

        Log.w(TAG, "[EXEC] findAlbumRow: no album row found for query='" + query + "'");
        return null;
    }

    /** Check if any direct child of parent has subtitle text starting with "Album •" */
    private boolean hasAlbumSubtitle(AccessibilityNodeInfo parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence txt = child.getText();
            child.recycle();
            if (txt != null) {
                String s = txt.toString();
                if (s.startsWith("Album •") || s.startsWith("Album ·") || s.startsWith("Album•")) {
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
            stepStarted("step_tap_play", "Tap Play on Album");
        }
        scheduleStepTimeout("step_tap_play", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: album page play button =
        //   android.widget.ImageView  id/button_play_and_pause  clickable=FALSE
        //   parent: android.widget.Button  (clickable — absorbs the click)
        // Use clickFollowNode which tries ACTION_CLICK on the node then walks up to parent Button.
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
            stepFailed("step_tap_play", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping Play (attempt " + (playBtnAttempts + 1) + "): id='"
                + playBtn.getViewIdResourceName() + "' clickable=" + playBtn.isClickable());

        // id/button_play_and_pause is clickable=false (ImageView inside Button).
        // clickFollowNode tries ACTION_CLICK on node first, then walks up to parent Button.
        boolean clicked = clickFollowNode(playBtn);
        playBtn.recycle();
        if (!clicked) { stepFailed("step_tap_play", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_play", "Tap Play on Album");
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

        // Spotify's Shuffle Play / Play button on the album page can land in a
        // "ready but paused" state — the Now Playing bar appears but the track
        // hasn't actually started.  If the now-playing play/pause button shows
        // "Play" (not "Pause"), tap it here to force the track to start before
        // the monitor begins watching.
        AccessibilityNodeInfo playBtn =
                findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (playBtn != null) {
            CharSequence cd = playBtn.getContentDescription();
            if (cd != null && cd.toString().startsWith("Play")) {
                Log.i(TAG, "[EXEC] Album track paused at verify step — tapping Play to start");
                tapAtNodeRandom(playBtn);
            }
            playBtn.recycle();
        }

        stepOk("step_verify_play", "Verify Playback");
        // Capture runId before commandDone() — monitor uses it for PLAYBACK_FINISHED.
        final String monitorRunId = runId;

        // Spread the requested likes/skips across playback (human-paced), THEN finish.
        // Runs with no watchdog armed; soft-fails individual actions so a missing
        // like/skip never fails the whole play command. When likes+skips are 0 this
        // calls the completion callback immediately.
        runLikeSkipChoreography(likeTarget, skipTarget, () -> {
            commandDone(true);
            // Start pause watchdog + seekbar end-of-track monitor so the session scheduler
            // advances correctly when the album track finishes (or resumes if paused).
            startPlaybackMonitor(monitorRunId);
        });
    }
}
