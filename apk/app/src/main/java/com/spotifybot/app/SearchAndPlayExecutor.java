package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * SearchAndPlayExecutor — Module 4.2
 *
 * Action: SEARCH_AND_PLAY
 * Params: { "query": "song name" }
 *
 * Step chain:
 *   1. step_open_search   — click Search tab
 *   2. step_type_query    — type query into search input
 *   3. step_submit_search — tap song suggestion OR submit via IME Enter
 *   4. step_tap_result    — find song row via id/title → row_root → ACTION_CLICK
 *   5. step_verify_play   — confirm Now Playing bar visible
 *
 * UI tree confirmed via UIAutoDev:
 *   com.spotify.music:id/row_root  (ViewGroup, clickable=false, long-clickable=true)
 *     ├── id/artwork
 *     ├── id/title    (TextView, text="Song Name")
 *     ├── id/subtitle (TextView, text="Song • Artist")
 *     └── id/action_bar (three-dot "More options for song X")
 *
 * Key insight: row_root is clickable=false but responds to performAction(ACTION_CLICK).
 * clickNode() is WRONG here — it gates on isClickable()=false and walks to wrong parent.
 * Always use performAction(ACTION_CLICK) directly on row_root.
 */
public class SearchAndPlayExecutor extends SpotifyExecutor {

    private String query;

    @Override
    protected void doExecute(JsonObject params) {
        query = params.has("query") ? params.get("query").getAsString() : "";
        if (query.isEmpty()) {
            stepFailed("step_open_search", "MISSING_PARAM_QUERY");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] SearchAndPlay START | query=" + query);
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

        boolean clicked = tapAtNodeRandom(searchTab);
        searchTab.recycle();
        cancelStepTimeout();
        if (!clicked) { stepFailed("step_open_search", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_open_search", "Open Search Tab");
        handler.postDelayed(this::stepActivateSearchBar, jitteredDelay(1_500));
    }

    // ── Step 1b: Activate search bar ─────────────────────────────────────────

    private void stepActivateSearchBar() {
        if (timeoutFired) return;
        AccessibilityNodeInfo searchBar = findById(SPOTIFY_PACKAGE + ":id/query");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/search_text_field_wrapper");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/query_text_wrapper");
        if (searchBar == null) searchBar = findByDesc("Search for something to listen to");
        if (searchBar == null) searchBar = findByText("What do you want to listen to?");
        if (searchBar != null) {
            tapAtNodeRandom(searchBar);
            searchBar.recycle();
            Log.i(TAG, "[EXEC] Search bar tapped — waiting for keyboard");
        } else {
            Log.w(TAG, "[EXEC] Search bar not found — proceeding anyway");
        }
        handler.postDelayed(this::stepTypeQuery, jitteredDelay(1_500));
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private void stepTypeQuery() {
        if (timeoutFired) return;
        stepStarted("step_type_query", "Type Search Query");
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
        stepOk("step_type_query", "Type Search Query");
        handler.postDelayed(this::stepSubmitSearch, jitteredDelay(1_500));
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────
    //
    // Always submit via IME Enter to reach the full results page.
    // NEVER use findByText(query) — the search bar EditText appears first in the
    // accessibility tree (isEditable=true, top of screen), so findByText(query)
    // always returns it first, clicks it, re-focuses the keyboard, and the search
    // never actually submits.
    //
    // After submission, stepTapResult uses the UIAutoDev-confirmed id/title approach:
    //   find id/title whose text matches query → parent id/row_root → ACTION_CLICK.

    private void stepSubmitSearch() {
        if (timeoutFired) return;

        AccessibilityNodeInfo input = findById(SPOTIFY_PACKAGE + ":id/query");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_edittext");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_query");
        if (input != null) {
            input.performAction(0x01000000); // ACTION_IME_ENTER — dismisses keyboard + shows results
            input.recycle();
            Log.i(TAG, "[EXEC] IME Enter submitted — waiting for search results");
        } else {
            Log.w(TAG, "[EXEC] Could not find search input for IME Enter");
        }
        handler.postDelayed(this::stepTapResult, jitteredDelay(3_000));
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapResult() {
        if (timeoutFired) return;
        stepStarted("step_tap_result", "Tap Song Result");
        scheduleStepTimeout("step_tap_result", STEP_TIMEOUT_MS);

        // Primary: UIAutoDev-confirmed approach
        //   find id/title node whose text contains query
        //   → get parent (id/row_root, clickable=false but ACTION_CLICK works)
        //   → performAction(ACTION_CLICK) directly — DO NOT use clickNode() which gates on isClickable()
        AccessibilityNodeInfo songRow = findSongRow();

        // Secondary fallback: three-dot "More options for song" → direct parent
        if (songRow == null) {
            Log.w(TAG, "[EXEC] id/title approach found nothing — trying three-dot fallback");
            songRow = findFirstSongRowViaDot();
        }

        cancelStepTimeout();
        if (timeoutFired) return;

        if (songRow == null) {
            Log.e(TAG, "[EXEC] stepTapResult: no song row found — dumping screen for diagnosis");
            dumpScreenNodes();
            stepFailed("step_tap_result", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping song row: desc='" + songRow.getContentDescription()
                + "' id=" + songRow.getViewIdResourceName());

        // Use tapAtNodeRandom() — adds ±15px coordinate jitter + random press duration
        // to avoid machine-perfect centre-taps that bot detection flags.
        // Falls back to ACTION_CLICK if gesture is rejected (row_root is clickable=false
        // but responds to both ACTION_CLICK and dispatchGesture).
        boolean clicked = tapAtNodeRandom(songRow);
        if (!clicked) {
            // Last resort: direct ACTION_CLICK on parent
            AccessibilityNodeInfo parent = songRow.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] tapAtNodeRandom failed — tried parent ACTION_CLICK: " + clicked);
                parent.recycle();
            }
        }
        songRow.recycle();

        if (!clicked) { stepFailed("step_tap_result", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_result", "Tap Song Result");
        handler.postDelayed(this::stepTapPlayIfPaused, jitteredDelay(3_000));
    }

    /**
     * UIAutoDev-confirmed song row finder.
     *
     * Strategy:
     *   1. Get all id/title nodes via findAccessibilityNodeInfosByViewId
     *   2. Find first whose getText() contains query (case-insensitive)
     *   3. Prefer one where a sibling child has subtitle text starting with "Song •"
     *      (confirms we're on a song row and not an album/artist/playlist result)
     *   4. Return the parent node (id/row_root)
     *
     * Never uses isClickable() — row_root is clickable=false but ACTION_CLICK works.
     */
    private AccessibilityNodeInfo findSongRow() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/title");
        root.recycle();

        if (titles == null || titles.isEmpty()) {
            Log.w(TAG, "[EXEC] findSongRow: no id/title nodes on screen");
            return null;
        }

        String queryLower = query.toLowerCase();
        AccessibilityNodeInfo fallback = null; // first title match, no subtitle confirmation

        for (int i = 0; i < titles.size(); i++) {
            AccessibilityNodeInfo titleNode = titles.get(i);
            if (titleNode == null) continue;

            CharSequence text = titleNode.getText();
            boolean matches = text != null && text.toString().toLowerCase().contains(queryLower);

            if (!matches) {
                titleNode.recycle();
                continue;
            }

            // Title text contains query — get parent (should be id/row_root)
            AccessibilityNodeInfo parent = titleNode.getParent();
            titleNode.recycle();
            if (parent == null) continue;

            // Check if any sibling of id/title confirms this is a song row
            if (hasSongSubtitle(parent)) {
                // Confirmed song row — recycle fallback and remaining titles, return this
                if (fallback != null) fallback.recycle();
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findSongRow: confirmed via id/title + 'Song •' subtitle");
                return parent;
            }

            // Title matches but not confirmed as song — keep first as fallback
            if (fallback == null) {
                fallback = parent;
            } else {
                parent.recycle();
            }
        }

        if (fallback != null) {
            Log.i(TAG, "[EXEC] findSongRow: using title-only fallback (no 'Song •' subtitle found)");
        } else {
            Log.w(TAG, "[EXEC] findSongRow: no id/title node containing query='" + query + "'");
        }
        return fallback;
    }

    /**
     * Check whether a given node (expected to be id/row_root) has a child whose
     * text starts with "Song •" or "Song ·" — confirming it is a song result row.
     */
    private boolean hasSongSubtitle(AccessibilityNodeInfo parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence txt = child.getText();
            child.recycle();
            if (txt != null) {
                String s = txt.toString();
                if (s.startsWith("Song •") || s.startsWith("Song ·") || s.startsWith("Song•")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Secondary fallback: find the first "More options for song X" three-dot button
     * in the tree and return its direct parent (= id/row_root).
     *
     * Returns the parent directly — does NOT check isClickable().
     * The caller uses performAction(ACTION_CLICK) which works on row_root even though
     * isClickable()=false (Compose behavior confirmed via UIAutoDev).
     */
    private AccessibilityNodeInfo findFirstSongRowViaDot() {
        AccessibilityNodeInfo btn = findByDesc("More options for song");
        if (btn == null) return null;

        AccessibilityNodeInfo parent = btn.getParent();
        btn.recycle();
        if (parent == null) return null;

        Log.i(TAG, "[EXEC] findFirstSongRowViaDot: found via three-dot → parent (desc='"
                + parent.getContentDescription() + "')");
        return parent;
    }

    // ── Step 4b: Tap Play if the track opened in paused state ────────────────

    private void stepTapPlayIfPaused() {
        if (timeoutFired) return;

        // If Pause button is visible the song is already playing — go straight to verify
        AccessibilityNodeInfo pauseBtn = findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (pauseBtn == null) pauseBtn = findByDesc("Pause");
        if (pauseBtn != null) {
            Log.i(TAG, "[EXEC] Song already playing (Pause button visible)");
            pauseBtn.recycle();
            stepVerifyPlayback();
            return;
        }

        // No Pause found — song is paused or player hasn't opened yet — tap Play
        AccessibilityNodeInfo playBtn = findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (playBtn == null) playBtn = findByDesc("Play");
        if (playBtn == null) playBtn = findByDesc("Play song");

        if (playBtn != null) {
            Log.i(TAG, "[EXEC] Song opened paused — tapping Play");
            tapAtNodeRandom(playBtn);
            playBtn.recycle();
            handler.postDelayed(this::stepVerifyPlayback, jitteredDelay(2_000));
        } else {
            Log.i(TAG, "[EXEC] No play/pause button found — assuming playback started");
            stepVerifyPlayback();
        }
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private void stepVerifyPlayback() {
        if (timeoutFired) return;
        // Clear any overlay that appeared after tapping the song row
        dismissOverlays();
        // Check for an ad — skip it if the skip button is already available,
        // otherwise just continue (playback will resume after the ad)
        skipAdIfPresent();
        stepStarted("step_verify_play", "Verify Playback Started");
        scheduleStepTimeout("step_verify_play", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo nowPlaying = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (nowPlaying == null) nowPlaying = findByDesc("Now Playing Bar");
        if (nowPlaying == null) nowPlaying = findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (nowPlaying == null) nowPlaying = findByDesc("Pause");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (nowPlaying != null) {
            Log.i(TAG, "[EXEC] Playback confirmed: " + nowPlaying.getContentDescription());
            nowPlaying.recycle();
        } else {
            Log.w(TAG, "[EXEC] Now Playing bar not found — soft success (steps 1-4 OK)");
        }
        stepOk("step_verify_play", "Verify Playback Started");
        // Capture runId before commandDone() — monitor uses it for PLAYBACK_FINISHED.
        String monitorRunId = runId;
        commandDone(true);
        // After music starts, poll position_text vs duration_text and notify the
        // backend when the track actually finishes so the next session task fires
        // at the right time instead of immediately.
        startPlaybackMonitor(monitorRunId);
    }
}
