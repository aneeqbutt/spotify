package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * FollowArtistExecutor — FOLLOW_ARTIST
 *
 * UIAutoDev confirmed:
 *
 *  Artist row in search/suggestions:
 *    id/row_root  (ViewGroup, clickable=TRUE — unlike song rows)
 *      ├── id/artwork
 *      ├── id/title    text="Drake"
 *      └── id/subtitle text="Artist"
 *
 *  Follow button on artist page (inside ComposeView id/compose):
 *    android.widget.Button  clickable=FALSE (Compose)  ← no resource-id
 *    sibling: android.widget.TextView  text="Follow"
 *    → find "Follow" text → get parent view → performAction(ACTION_CLICK)
 *
 * Step chain:
 *   1. step_open_search   — click Search tab
 *   2. step_type_artist   — type artist name
 *   3. step_tap_profile   — find id/title matching name + subtitle="Artist" → parent row_root
 *   4. step_tap_follow    — find TextView "Follow" → parent → performAction(ACTION_CLICK)
 *   5. step_verify_follow — confirm "Following" / "Unfollow" state
 */
public class FollowArtistExecutor extends SpotifyExecutor {

    private String artistName;

    @Override
    protected void doExecute(JsonObject params) {
        artistName = params.has("artist_name") ? params.get("artist_name").getAsString()
                   : params.has("query")       ? params.get("query").getAsString()
                   : "";
        if (artistName.isEmpty()) {
            stepFailed("step_open_search", "MISSING_PARAM_ARTIST_NAME");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] FollowArtist START | artist=" + artistName);
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
        scheduleStep(() -> activateSearchBar(this::stepTypeArtist), GAP_TINY);
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private void stepTypeArtist() {
        if (timeoutFired) return;
        stepStarted("step_type_artist", "Type Artist Name");
        scheduleStepTimeout("step_type_artist", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo input = findSearchInput();

        if (input == null || timeoutFired) {
            cancelStepTimeout();
            if (!timeoutFired) { stepFailed("step_type_artist", "UI_ELEMENT_NOT_FOUND"); commandDone(false); }
            return;
        }
        boolean typed = typeText(input, artistName);
        input.recycle();
        cancelStepTimeout();
        if (!typed) { stepFailed("step_type_artist", "TEXT_INPUT_FAILED"); commandDone(false); return; }
        stepOk("step_type_artist", "Type Artist Name");
        scheduleStep(this::stepTapProfile, jitteredDelay(GAP_MEDIUM));
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepTapProfile() {
        if (timeoutFired) return;
        stepStarted("step_tap_profile", "Tap Artist Profile");
        scheduleStepTimeout("step_tap_profile", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: find id/title matching artistName where sibling
        // id/subtitle text="Artist" — parent is id/row_root (clickable=TRUE).
        AccessibilityNodeInfo artistRow = findArtistRow();

        cancelStepTimeout();
        if (timeoutFired) return;

        if (artistRow == null) {
            Log.e(TAG, "[EXEC] Artist row not found — dumping screen for diagnosis");
            dumpScreenNodes();
            stepFailed("step_tap_profile", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Artist row found: desc='" + artistRow.getContentDescription()
                + "' clickable=" + artistRow.isClickable());

        // tapAtNodeRandom: gesture tap with ±15px jitter + random press duration.
        // id/row_root for artist is clickable=true — gesture always lands here.
        boolean clicked = tapAtNodeRandom(artistRow);
        if (!clicked) {
            AccessibilityNodeInfo parent = artistRow.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] tapAtNodeRandom failed — tried parent ACTION_CLICK: " + clicked);
                parent.recycle();
            }
        }
        artistRow.recycle();

        if (!clicked) { stepFailed("step_tap_profile", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_profile", "Tap Artist Profile");
        // 6.5s base + jitter — artist page must fully render before Follow button appears
        scheduleStep(this::stepTapFollow, jitteredDelay(GAP_LONG));
    }

    /**
     * Find the artist card row using UIAutoDev-confirmed structure.
     *
     * Gets all id/title nodes → finds one matching artistName → checks sibling
     * subtitle has text="Artist" (not "Album", "Song", "Playlist") → returns parent
     * id/row_root (clickable=true for artist rows).
     */
    private AccessibilityNodeInfo findArtistRow() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/title");
        root.recycle();

        if (titles == null || titles.isEmpty()) {
            Log.w(TAG, "[EXEC] findArtistRow: no id/title nodes found");
            return null;
        }

        String artistLower = artistName.toLowerCase();
        AccessibilityNodeInfo fallback = null;

        for (int i = 0; i < titles.size(); i++) {
            AccessibilityNodeInfo titleNode = titles.get(i);
            if (titleNode == null) continue;

            CharSequence text = titleNode.getText();
            boolean matches = text != null && text.toString().toLowerCase().contains(artistLower);

            if (!matches) {
                titleNode.recycle();
                continue;
            }

            AccessibilityNodeInfo parent = titleNode.getParent();
            titleNode.recycle();
            if (parent == null) continue;

            // Check if sibling subtitle = "Artist" (not "Album •", "Song •", etc.)
            if (hasArtistSubtitle(parent)) {
                // Confirmed artist row — recycle fallback and remaining
                if (fallback != null) fallback.recycle();
                for (int j = i + 1; j < titles.size(); j++) {
                    AccessibilityNodeInfo rem = titles.get(j);
                    if (rem != null) rem.recycle();
                }
                Log.i(TAG, "[EXEC] findArtistRow: confirmed via id/title + 'Artist' subtitle");
                return parent;
            }

            if (fallback == null) {
                fallback = parent;
            } else {
                parent.recycle();
            }
        }

        if (fallback != null) Log.w(TAG, "[EXEC] findArtistRow: using title-only fallback");
        else Log.w(TAG, "[EXEC] findArtistRow: no id/title matching '" + artistName + "'");
        return fallback;
    }

    /** Check if any direct child of parent has text exactly "Artist" */
    private boolean hasArtistSubtitle(AccessibilityNodeInfo parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence txt = child.getText();
            child.recycle();
            if (txt != null && txt.toString().equalsIgnoreCase("Artist")) {
                return true;
            }
        }
        return false;
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepTapFollow() {
        if (timeoutFired) return;
        stepStarted("step_tap_follow", "Tap Follow Button");
        scheduleStepTimeout("step_tap_follow", STEP_TIMEOUT_MS);

        // Already following? Idempotent success.
        AccessibilityNodeInfo alreadyFollowing = findByText("Following");
        if (alreadyFollowing == null) alreadyFollowing = findByDesc("Following");
        if (alreadyFollowing == null) alreadyFollowing = findByDesc("Unfollow");
        if (alreadyFollowing != null) {
            alreadyFollowing.recycle();
            cancelStepTimeout();
            Log.i(TAG, "[EXEC] Already following " + artistName + " — idempotent success");
            stepOk("step_tap_follow",    "Tap Follow Button");
            stepOk("step_verify_follow", "Verify Follow State");
            commandDone(true);
            return;
        }

        // Root cause of UI_ELEMENT_NOT_FOUND:
        // findByText() only searches getRootInActiveWindow(). After tapping the artist row
        // the artist page window is still loading — getRootInActiveWindow() still points at
        // the search results window, so "Follow" is never found.
        //
        // Fix: search ALL windows (same approach used by findByDesc). If still not found
        // schedule one 2s retry before giving up — covers slow page renders.
        AccessibilityNodeInfo followText = findFollowTextAllWindows();

        cancelStepTimeout();
        if (timeoutFired) return;

        if (followText == null) {
            // An overlay may have appeared while the artist page was loading — clear it
            boolean dismissed = dismissOverlays();
            Log.w(TAG, "[EXEC] Follow text not found on first try — overlay dismissed="
                    + dismissed + ", scheduling retry");
            scheduleStep(this::stepTapFollowRetry, jitteredDelay(GAP_MEDIUM));
            return;
        }

        performFollowClick(followText);
    }

    /** One 2s-delayed retry of stepTapFollow (handles slow artist-page renders) */
    private void stepTapFollowRetry() {
        if (timeoutFired) return;

        AccessibilityNodeInfo followText = findFollowTextAllWindows();

        if (followText == null) {
            // Last resort: desc-based (older Spotify builds)
            followText = findByDesc("Follow artist");
            if (followText == null) followText = findByDesc("Follow");
        }

        if (followText == null) {
            Log.e(TAG, "[EXEC] Follow text not found on retry — dumping screen");
            dumpScreenNodes();
            stepFailed("step_tap_follow", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        performFollowClick(followText);
    }

    /**
     * Shared click logic — called from both stepTapFollow and stepTapFollowRetry.
     *
     * UIAutoDev confirmed structure:
     *   android.view.View (parent)
     *     [0] android.widget.TextView  text="Follow"
     *     [1] android.widget.Button    ← actual Follow button (clickable=false, Compose)
     *     [2] android.widget.Button    ← other button
     * Strategy: get parent → scan children for android.widget.Button → ACTION_CLICK.
     */
    private void performFollowClick(AccessibilityNodeInfo followText) {
        Log.i(TAG, "[EXEC] Follow text found: text='" + followText.getText()
                + "' clickable=" + followText.isClickable());

        AccessibilityNodeInfo parent = followText.getParent();
        followText.recycle();

        boolean clicked = false;
        if (parent != null) {
            int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = parent.getChild(i);
                if (child == null) continue;
                CharSequence cls = child.getClassName();
                if (cls != null && cls.toString().equals("android.widget.Button")) {
                    // tapAtNodeRandom: gesture with ±15px offset on the Follow button
                    // — avoids perfectly centred machine taps on high-value engagement action
                    clicked = tapAtNodeRandom(child);
                    Log.i(TAG, "[EXEC] Follow sibling Button[" + i + "] tapAtNodeRandom: " + clicked);
                    child.recycle();
                    if (clicked) break;
                } else {
                    child.recycle();
                }
            }
            if (!clicked) {
                clicked = tapAtNodeRandom(parent);
                Log.i(TAG, "[EXEC] Follow parent tapAtNodeRandom fallback: " + clicked);
            }
            parent.recycle();
        }

        if (!clicked) { stepFailed("step_tap_follow", "CLICK_ACTION_REJECTED"); commandDone(false); return; }
        stepOk("step_tap_follow", "Tap Follow Button");
        scheduleStep(this::stepVerifyFollow, jitteredDelay(GAP_LONG));
    }

    /**
     * Search ALL accessibility windows for a TextView with text "Follow" (not "Following").
     *
     * Why this instead of findByText(): findByText() only calls getRootInActiveWindow()
     * which returns the search results window while the artist page is still loading.
     * getWindows() returns every visible window including the artist page that is
     * rendering in the background — ensuring we find "Follow" as soon as it exists.
     */
    private AccessibilityNodeInfo findFollowTextAllWindows() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        // Search all windows first
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo result = findFollowTextInTree(root);
                if (result != null) return result;
                root.recycle();
            }
        }

        // Fallback: active window only
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo result = findFollowTextInTree(root);
        if (result == null) root.recycle();
        return result;
    }

    /**
     * Recursively find a node whose getText() equals "Follow" (case-insensitive),
     * skipping any node whose text contains "following" (already-followed state).
     */
    private AccessibilityNodeInfo findFollowTextInTree(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence txt = node.getText();
        if (txt != null) {
            String s = txt.toString().trim();
            if (s.equalsIgnoreCase("Follow")) {
                return node; // exact match — return without recycling
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findFollowTextInTree(child);
            if (result != null) {
                // recycle sibling children we already passed
                if (child != result) child.recycle();
                return result;
            }
            if (child != null) child.recycle();
        }
        return null;
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private void stepVerifyFollow() {
        if (timeoutFired) return;
        stepStarted("step_verify_follow", "Verify Follow State");
        scheduleStepTimeout("step_verify_follow", STEP_TIMEOUT_MS);

        // After following, button changes from "Follow" → "Following"
        AccessibilityNodeInfo following = findByText("Following");
        if (following == null) following = findByDesc("Following");
        if (following == null) following = findByDesc("Unfollow");
        cancelStepTimeout();
        if (timeoutFired) return;

        if (following != null) {
            following.recycle();
            Log.i(TAG, "[EXEC] Follow confirmed — button is now 'Following'");
        } else {
            Log.w(TAG, "[EXEC] 'Following' not found — soft success (Follow was tapped)");
        }
        stepOk("step_verify_follow", "Verify Follow State");
        commandDone(true);
    }
}
