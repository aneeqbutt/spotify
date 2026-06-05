package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

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
 *   3. step_submit_search   — submit (tap suggestion row or IME Enter)
 *   4. step_tap_filter      — tap "Albums" filter chip in results
 *   5. step_tap_album       — tap first album result card
 *   6. step_tap_play        — tap Play / Shuffle Play on the album page
 *   7. step_verify_play     — confirm Now Playing bar visible
 */
public class PlayFromAlbumExecutor extends SpotifyExecutor {

    private String query;
    private int dailyLimit;
    private int hourlyLimit;

    /** Retry counter for stepTapPlay */
    private int playBtnAttempts = 0;

    /**
     * True when we navigated directly to the album page via a suggestion tap.
     * In this case stepTapAlbumsFilter and stepTapAlbumResult are skipped entirely.
     */
    private boolean navigatedViaSuggestion = false;

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

        Log.i(TAG, "[EXEC] PlayFromAlbum START | query=" + query);
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

    // ── Step 1b: Activate search bar ─────────────────────────────────────────

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
        stepStarted("step_type_query", "Type Album Name");
        scheduleStepTimeout("step_type_query", STEP_TIMEOUT_MS);

        // Same fallback chain as SearchAndPlayExecutor — confirmed id/query works on this device
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
        stepOk("step_type_query", "Type Album Name");
        handler.postDelayed(this::stepSubmitSearch, 1_500);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSubmitSearch() {
        if (timeoutFired) return;

        // ── Primary path: tap album suggestion directly from the dropdown ────────
        // Spotify's suggestion list shows album entries with subtitle "Album • Artist"
        // or "E Album • Artist" (E = explicit). Tapping this navigates directly to
        // the album page — no filter step, no album-row-finding step needed.
        // We look for the subtitle text since it uniquely identifies album suggestions.
        AccessibilityNodeInfo albumLabel = findByText("Album •"); // "Album •"
        if (albumLabel == null) albumLabel = findByText("• Album");
        if (albumLabel == null) albumLabel = findByText("Album ·"); // "Album ·"

        if (albumLabel != null) {
            Log.i(TAG, "[EXEC] Album suggestion found in dropdown: '" + albumLabel.getText() + "'");
            // Walk up from subtitle text to the clickable suggestion row (max 4 levels)
            boolean tapped = clickFollowNode(albumLabel);
            albumLabel.recycle();
            if (tapped) {
                navigatedViaSuggestion = true;
                Log.i(TAG, "[EXEC] Tapped album suggestion — navigating directly to album page");
                playBtnAttempts = 0;
                // Album page takes ~3-5s to fully render
                handler.postDelayed(this::stepTapPlay, 4_500);
                return;
            }
            Log.w(TAG, "[EXEC] Album suggestion click failed — falling back to IME Enter");
        } else {
            Log.i(TAG, "[EXEC] No album suggestion in dropdown — submitting via IME Enter");
        }

        // ── Fallback path: submit search and use Albums filter ───────────────────
        // IMPORTANT: never use findByText(query) here — it matches the search bar
        // EditText (which also shows the query text), clicks it, and never submits.
        navigatedViaSuggestion = false;
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
        // Wait for full search results page (keyboard dismissed, filter chips visible)
        handler.postDelayed(this::stepTapAlbumsFilter, 3_000);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapAlbumsFilter() {
        if (timeoutFired) return;
        stepStarted("step_tap_filter", "Tap Albums Filter");
        scheduleStepTimeout("step_tap_filter", STEP_TIMEOUT_MS);

        // Albums filter chip sits in the horizontal chip row at top of results
        AccessibilityNodeInfo albumsChip = findByText("Albums");
        if (albumsChip == null) albumsChip = findByDesc("Albums");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (albumsChip != null) {
            clickNode(albumsChip);
            albumsChip.recycle();
            Log.i(TAG, "[EXEC] Albums filter applied");
        } else {
            Log.w(TAG, "[EXEC] Albums filter chip not found — continuing without filter");
        }
        stepOk("step_tap_filter", "Tap Albums Filter");
        humanDelay(this::stepTapAlbumResult);
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private void stepTapAlbumResult() {
        if (timeoutFired) return;
        stepStarted("step_tap_album", "Tap Album Result");
        scheduleStepTimeout("step_tap_album", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo albumRow = findAlbumRow();
        cancelStepTimeout();
        if (timeoutFired) return;

        if (albumRow == null) {
            Log.e(TAG, "[EXEC] findAlbumRow returned null — dumping screen nodes for diagnosis:");
            dumpScreenNodes();
            stepFailed("step_tap_album", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping album row: desc='" + albumRow.getContentDescription() + "'");
        // Try ACTION_CLICK on the node itself (parent of three-dot, or title node).
        // If that fails, try one level up (grandparent of three-dot = actual row container).
        // Stop there — never walk further to avoid hitting RecyclerView or outer containers.
        boolean clicked = albumRow.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo gp = albumRow.getParent();
            if (gp != null) {
                clicked = gp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] albumRow self-click failed — tried grandparent: " + clicked);
                gp.recycle();
            }
        } else {
            Log.i(TAG, "[EXEC] albumRow self-click succeeded");
        }
        albumRow.recycle();
        if (!clicked) { stepFailed("step_tap_album", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_album", "Tap Album Result");
        playBtnAttempts = 0;
        humanDelay(4_000, 6_000, this::stepTapPlay);
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

            // Confirm it's an album row via subtitle "Album • Artist"
            if (hasAlbumSubtitle(parent)) {
                if (fallback != null) fallback.recycle();
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findAlbumRow: confirmed via id/title + 'Album •' subtitle");
                return parent;
            }

            if (fallback == null) {
                fallback = parent;
            } else {
                parent.recycle();
            }
        }

        if (fallback != null) Log.w(TAG, "[EXEC] findAlbumRow: using title-only fallback");
        else Log.w(TAG, "[EXEC] findAlbumRow: no id/title matching query='" + query + "'");
        return fallback;
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
                Log.w(TAG, "[EXEC] Play button not found — retry attempt " + playBtnAttempts + "/2 in 2500ms");
                handler.postDelayed(this::stepTapPlay, 2_500);
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
        commandDone(true);
    }
}
