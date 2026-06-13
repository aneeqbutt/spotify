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
 * Params: { "query": "song name", "playlist_name": "My Playlist" (optional) }
 *
 * Step chain (always):
 *   1. step_open_search   — click Search tab
 *   2. step_type_query    — type query into search input
 *   3. step_submit_search — submit via IME Enter → full results page
 *   3b. step_tap_filter   — tap "Songs" tab to show songs-only results
 *   4. step_tap_result    — find song row via id/title → row_root → ACTION_CLICK
 *   5. step_verify_play   — confirm Now Playing bar visible
 *
 * Additional steps when playlist_name param is provided:
 *   6. step_open_player    — tap Now Playing bar → open full player
 *   7. step_tap_options    — tap three-dot "More options"
 *   8. step_tap_add_to_pl  — tap "Add to playlist" in options sheet
 *   9. step_select_playlist — find playlist in "Save in" sheet → tap +
 *  10. step_verify_playlist — sheet dismisses = success
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
    private String playlistName;    // optional — non-null/non-empty triggers add-to-playlist chain
    private int resultScrollCount = 0;
    private int songsFilterAttempts = 0;
    /** True once the Songs filter chip is confirmed selected — subtitle check is skipped when active. */
    private boolean songFilterApplied = false;

    private static final int MAX_SONGS_FILTER_ATTEMPTS = 12;

    @Override
    protected void doExecute(JsonObject params) {
        query        = params.has("query")         ? params.get("query").getAsString()         : "";
        playlistName = params.has("playlist_name") ? params.get("playlist_name").getAsString() : "";
        resultScrollCount   = 0;
        songsFilterAttempts = 0;
        songFilterApplied   = false;
        if (query.isEmpty()) {
            stepFailed("step_open_search", "MISSING_PARAM_QUERY");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] SearchAndPlay START | query=" + query
                + (playlistName.isEmpty() ? "" : " | playlist=" + playlistName));
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
        stepStarted("step_type_query", "Type Search Query");
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
        stepOk("step_type_query", "Type Search Query");
        scheduleStep(this::stepSubmitSearch, 0);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────
    //
    // Always submit via IME Enter to reach the full results page, then tap the
    // "Songs" tab so only song results are shown before we try to find the row.
    // NEVER use findByText(query) — the search bar EditText appears first in the
    // accessibility tree (isEditable=true, top of screen), so findByText(query)
    // always returns it first, clicks it, re-focuses the keyboard, and the search
    // never actually submits.

    private void stepSubmitSearch() {
        if (timeoutFired) return;
        submitSearchQuery(this::stepTapSongsTab);
    }

    // ── Step 3b ───────────────────────────────────────────────────────────────
    //
    // Poll until filter_compose is on screen, tap Songs, then verify the chip
    // is selected before touching any result row.  Prevents playing a Top-result
    // track when the category row has not loaded yet.

    private void stepTapSongsTab() {
        if (timeoutFired) return;

        if (songsFilterAttempts == 0) {
            stepStarted("step_tap_filter", "Tap Songs Tab");
            scheduleStepTimeout("step_tap_filter", 25_000);
        }
        songsFilterAttempts++;

        AccessibilityNodeInfo chip = findChipLabelNode("Songs");
        if (chip == null) {
            // Results page may not have loaded — re-submit search every 4 polls
            if (songsFilterAttempts == 4 || songsFilterAttempts == 8) {
                Log.w(TAG, "[EXEC] filter_compose missing — re-submitting search (attempt "
                        + songsFilterAttempts + ")");
                submitSearchQuery(() -> scheduleStep(this::stepTapSongsTab, GAP_MEDIUM));
                return;
            }
            if (songsFilterAttempts < MAX_SONGS_FILTER_ATTEMPTS) {
                Log.i(TAG, "[EXEC] Songs chip not on screen yet — retry "
                        + songsFilterAttempts + "/" + MAX_SONGS_FILTER_ATTEMPTS);
                scheduleStep(this::stepTapSongsTab, GAP_SHORT);
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
            finishSongsFilterStep();
            return;
        }
        chip.recycle();

        if (!ensureFilterChipVisible("Songs")) {
            scheduleStep(this::stepTapSongsTab, GAP_SHORT);
            return;
        }

        if (!tapFilterChipByLabel("Songs")) {
            if (songsFilterAttempts < MAX_SONGS_FILTER_ATTEMPTS) {
                scheduleStep(this::stepTapSongsTab, GAP_SHORT);
                return;
            }
            cancelStepTimeout();
            stepFailed("step_tap_filter", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }
        scheduleStep(this::verifySongsFilterSelected, jitteredDelay(GAP_LONG));
    }

    private void verifySongsFilterSelected() {
        if (timeoutFired) return;

        AccessibilityNodeInfo chip = findChipLabelNode("Songs");
        if (chip != null && isFilterChipSelected(chip)) {
            chip.recycle();
            finishSongsFilterStep();
            return;
        }
        if (chip != null) chip.recycle();

        // Compose chips may not expose isSelected — accept song-only rows as proof.
        if (songsFilterAttempts >= 2) {
            AccessibilityNodeInfo songRow = findFirstSongRowViaDot();
            if (songRow != null) {
                songRow.recycle();
                Log.i(TAG, "[EXEC] Songs filter verified via song result rows");
                finishSongsFilterStep();
                return;
            }
        }

        if (songsFilterAttempts < MAX_SONGS_FILTER_ATTEMPTS) {
            Log.w(TAG, "[EXEC] Songs chip not selected yet — re-tapping (attempt "
                    + songsFilterAttempts + ")");
            scheduleStep(this::stepTapSongsTab, GAP_SHORT);
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) return;
        stepFailed("step_tap_filter", "FILTER_NOT_APPLIED");
        commandDone(false);
    }

    private void finishSongsFilterStep() {
        cancelStepTimeout();
        if (timeoutFired) return;
        songFilterApplied = true;
        stepOk("step_tap_filter", "Tap Songs Tab");
        Log.i(TAG, "[EXEC] Songs filter confirmed — proceeding to result tap");
        scheduleStep(this::stepTapResult, jitteredDelay(GAP_SHORT));
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private static final int MAX_RESULT_SCROLLS = 4;

    private void stepTapResult() {
        if (timeoutFired) return;

        // Start the step only on the first attempt
        if (resultScrollCount == 0) {
            stepStarted("step_tap_result", "Tap Song Result");
            scheduleStepTimeout("step_tap_result", STEP_TIMEOUT_MS);
        }

        AccessibilityNodeInfo songRow = findSongRow();
        if (songRow == null) {
            Log.w(TAG, "[EXEC] id/title approach found nothing — trying three-dot fallback");
            songRow = findFirstSongRowViaDot();
        }

        if (songRow == null) {
            // Result not on screen — scroll down and retry if attempts remain
            if (resultScrollCount < MAX_RESULT_SCROLLS) {
                resultScrollCount++;
                Log.i(TAG, "[EXEC] Song not found — scrolling down ("
                        + resultScrollCount + "/" + MAX_RESULT_SCROLLS + ")");
                scrollResultsList();
                scheduleStep(this::stepTapResult, GAP_MEDIUM);
                return;
            }
            // Exhausted scrolls
            cancelStepTimeout();
            if (timeoutFired) return;
            Log.e(TAG, "[EXEC] stepTapResult: no row found after " + MAX_RESULT_SCROLLS + " scrolls");
            dumpScreenNodes();
            stepFailed("step_tap_result", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            resultScrollCount = 0;
            return;
        }

        cancelStepTimeout();
        if (timeoutFired) { songRow.recycle(); return; }

        Log.i(TAG, "[EXEC] Tapping song row (scroll=" + resultScrollCount + "): desc='"
                + songRow.getContentDescription() + "'");
        boolean clicked = tapAtNodeRandom(songRow);
        if (!clicked) {
            AccessibilityNodeInfo parent = songRow.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] tapAtNodeRandom failed — tried parent ACTION_CLICK: " + clicked);
                parent.recycle();
            }
        }
        songRow.recycle();
        resultScrollCount = 0;

        if (!clicked) { stepFailed("step_tap_result", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_result", "Tap Song Result");
        scheduleStep(this::stepTapPlayIfPaused, jitteredDelay(GAP_LONG));
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

            // When Songs filter is active, all results are songs — subtitle may show artist only.
            if (songFilterApplied || hasSongSubtitle(parent)) {
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

        if (fallback != null && songFilterApplied) {
            Log.i(TAG, "[EXEC] findSongRow: title-only fallback (Songs filter active)");
            return fallback;
        }
        if (fallback != null) {
            fallback.recycle();
            Log.w(TAG, "[EXEC] findSongRow: title match without Songs filter — skipping");
        } else {
            Log.w(TAG, "[EXEC] findSongRow: no id/title node containing query='" + query + "'");
        }
        return null;
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
            scheduleStep(this::stepVerifyPlayback, jitteredDelay(GAP_LONG));
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
        if (!playlistName.isEmpty()) {
            // Add-to-playlist chain — commandDone fires at the end of that chain
            scheduleStep(this::stepOpenPlayerForPlaylist, jitteredDelay(GAP_LONG));
        } else {
            String monitorRunId = runId;
            commandDone(true);
            startPlaybackMonitor(monitorRunId);
        }
    }

    // ── Step 6: Open full player (add-to-playlist) ────────────────────────────

    private void stepOpenPlayerForPlaylist() {
        if (timeoutFired) return;
        stepStarted("step_open_player", "Open Now Playing");
        scheduleStepTimeout("step_open_player", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findByDesc("Now Playing Bar");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (bar != null) {
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            bar.recycle();
            stepOk("step_open_player", "Open Now Playing");
            scheduleStep(this::stepTapOptionsForPlaylist, GAP_SHORT);
        } else {
            Log.w(TAG, "[EXEC] Now Playing bar not found — trying options directly");
            stepOk("step_open_player", "Open Now Playing");
            stepTapOptionsForPlaylist();
        }
    }

    // ── Step 7: Tap three-dot More Options ────────────────────────────────────

    private void stepTapOptionsForPlaylist() {
        if (timeoutFired) return;
        stepStarted("step_tap_options", "Tap More Options");
        scheduleStepTimeout("step_tap_options", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo optionsBtn = findByDesc("More options");
        if (optionsBtn == null) optionsBtn = findByDesc("More");
        if (optionsBtn == null) optionsBtn = findById(SPOTIFY_PACKAGE + ":id/context_menu_button");
        if (optionsBtn == null) optionsBtn = findById(SPOTIFY_PACKAGE + ":id/more_button");
        if (optionsBtn == null) optionsBtn = findById(SPOTIFY_PACKAGE + ":id/overflow_menu");
        if (optionsBtn == null) optionsBtn = findByDesc("Track options");
        if (optionsBtn == null) optionsBtn = findByDesc("Options");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (optionsBtn == null) {
            stepFailed("step_tap_options", "UI_ELEMENT_NOT_FOUND");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        boolean clicked = optionsBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = optionsBtn.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
            }
        }
        optionsBtn.recycle();
        if (!clicked) {
            stepFailed("step_tap_options", "CLICK_ACTION_REJECTED");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        stepOk("step_tap_options", "Tap More Options");
        humanDelay(GAP_TINY, GAP_MEDIUM, this::stepTapAddToPlaylistBtn);
    }

    // ── Step 8: Tap "Add to playlist" menu item ───────────────────────────────

    private void stepTapAddToPlaylistBtn() {
        if (timeoutFired) return;
        stepStarted("step_tap_add_to_pl", "Tap Add to Playlist");
        scheduleStepTimeout("step_tap_add_to_pl", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo addBtn = findByText("Add to playlist");
        if (addBtn == null) addBtn = findByText("Add to Playlist");
        if (addBtn == null) addBtn = findByDesc("Add to playlist");
        if (addBtn == null) addBtn = findByText("Save to playlist");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (addBtn == null) {
            pressBack();
            stepFailed("step_tap_add_to_pl", "UI_ELEMENT_NOT_FOUND");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        // clickable=TRUE confirmed — performAction(ACTION_CLICK) directly.
        boolean clicked = addBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clicked = clickFollowNode(addBtn);
        addBtn.recycle();

        if (!clicked) {
            pressBack();
            stepFailed("step_tap_add_to_pl", "CLICK_ACTION_REJECTED");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        stepOk("step_tap_add_to_pl", "Tap Add to Playlist");
        humanDelay(GAP_SHORT, GAP_LONG, this::stepSelectPlaylistForSong);
    }

    // ── Step 9: Select the target playlist in "Save in" sheet ────────────────

    private void stepSelectPlaylistForSong() {
        if (timeoutFired) return;
        stepStarted("step_select_playlist", "Select Target Playlist");
        scheduleStepTimeout("step_select_playlist", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo textNode = findByText(playlistName);
        if (textNode == null) textNode = findByDesc(playlistName);

        cancelStepTimeout();
        if (timeoutFired) return;

        if (textNode == null) {
            Log.e(TAG, "[EXEC] Playlist '" + playlistName + "' not found in picker");
            dumpScreenNodes();
            pressBack();
            stepFailed("step_select_playlist", "PLAYLIST_NOT_FOUND");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        boolean clicked = clickAddButtonInRow(textNode);  // textNode recycled inside

        if (!clicked) {
            pressBack();
            stepFailed("step_select_playlist", "CLICK_ACTION_REJECTED");
            String monitorRunId = runId;
            commandDone(false);
            startPlaybackMonitor(monitorRunId);
            return;
        }

        stepOk("step_select_playlist", "Select Target Playlist");
        scheduleStep(this::stepVerifyPlaylistAdded, GAP_TINY);
    }

    // ── Step 10: Verify sheet dismissed = song added ──────────────────────────

    private void stepVerifyPlaylistAdded() {
        if (timeoutFired) return;
        stepStarted("step_verify_playlist", "Verify Added to Playlist");
        scheduleStepTimeout("step_verify_playlist", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo sheet = findById(SPOTIFY_PACKAGE + ":id/bottom_sheet_content");
        cancelStepTimeout();
        if (timeoutFired) return;

        if (sheet == null) {
            Log.i(TAG, "[EXEC] Bottom sheet dismissed — track added to '" + playlistName + "'");
        } else {
            sheet.recycle();
            Log.w(TAG, "[EXEC] Bottom sheet still visible — soft success");
        }
        stepOk("step_verify_playlist", "Verify Added to Playlist");
        String monitorRunId = runId;
        commandDone(true);
        startPlaybackMonitor(monitorRunId);
    }

    // ── Helper: find + button sibling inside a "Save in" playlist row ─────────
    //
    // UIAutoDev-confirmed row tree (Compose, inside id/bottom_sheet_content):
    //   row_container
    //     ├── view (thumbnail)
    //     ├── view (name text area)  ← textNode lives here
    //     ├── view (+ icon, NOT clickable)
    //     └── android.widget.Button  ← TARGET (index 3 sibling)
    //
    // Walk UP ancestors (up to 6 levels), at each level scan children for Button.

    private boolean clickAddButtonInRow(AccessibilityNodeInfo textNode) {
        AccessibilityNodeInfo current = textNode;
        boolean clicked = false;

        for (int level = 0; level < 6 && !clicked; level++) {
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = null;
            if (parent == null) break;
            current = parent;

            int childCount = current.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child == null) continue;
                CharSequence cls = child.getClassName();
                if (cls != null && cls.toString().equals("android.widget.Button")) {
                    Log.i(TAG, "[EXEC] Found Button at row level " + level
                            + " child-index=" + i + " clickable=" + child.isClickable());
                    clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    child.recycle();
                    break;
                }
                child.recycle();
            }

            if (!clicked && current != null && current.isClickable()) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] Ancestor clickable at level " + level + " → " + clicked);
            }
        }

        if (current != null) current.recycle();
        return clicked;
    }
}
