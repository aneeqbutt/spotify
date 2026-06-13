package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

/**
 * AddToPlaylistExecutor — ADD_TO_PLAYLIST
 *
 * Adds the currently playing track to a user-specified playlist.
 * Params: { "playlist_name": "My Playlist" }  (or "query" as fallback)
 *
 * UIAutoDev confirmed:
 *   Options menu item "Add to playlist":
 *     android.view.View  clickable=TRUE  text="Add to playlist"  no resource-id
 *
 *   "Save in" bottom sheet (id/bottom_sheet_content → ComposeView):
 *     Each row has playlist name text (Compose, not clickable) +
 *     android.widget.Button (green "+" icon, sibling/child of name text)
 *     → findByText(playlistName) → clickFollowNode walks up to Button/row
 *
 * Step chain:
 *   1. step_open_player     — tap Now Playing bar → open full player
 *   2. step_tap_options     — tap three-dot "More options"
 *   3. step_tap_add_to_pl  — tap "Add to playlist" in options sheet
 *   4. step_select_playlist — find playlist name in "Save in" sheet → tap row
 *   5. step_verify          — soft verify (sheet dismisses = success)
 */
public class AddToPlaylistExecutor extends SpotifyExecutor {

    private String playlistName;

    // Entry point — validates playlist_name param, then opens Now Playing screen
    @Override
    protected void doExecute(JsonObject params) {
        playlistName = params.has("playlist_name") ? params.get("playlist_name").getAsString() : "";
        if (playlistName.isEmpty()) {
            playlistName = params.has("query") ? params.get("query").getAsString() : "";
        }
        if (playlistName.isEmpty()) {
            stepFailed("step_open_player", "MISSING_PARAM_PLAYLIST_NAME");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] AddToPlaylist START | target='" + playlistName + "'");
        launchAndSettle(this::stepOpenPlayer);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    // Taps the mini-player bar to open the full Now Playing screen
    private void stepOpenPlayer() {
        if (timeoutFired) return;
        stepStarted("step_open_player", "Open Now Playing");
        scheduleStepTimeout("step_open_player", STEP_TIMEOUT_MS);

        // Confirmed: id/now_playing_bar_layout, desc="Now Playing Bar", clickable=true
        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findByDesc("Now Playing Bar");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (bar != null) {
            bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            bar.recycle();
            stepOk("step_open_player", "Open Now Playing");
            scheduleStep(this::stepTapOptions, GAP_SHORT);
        } else {
            Log.w(TAG, "[EXEC] Now Playing bar not found — trying options directly");
            stepOk("step_open_player", "Open Now Playing");
            stepTapOptions();
        }
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    // Taps the three-dot "More options" button on the full player screen
    private void stepTapOptions() {
        if (timeoutFired) return;
        stepStarted("step_tap_options", "Tap More Options");
        scheduleStepTimeout("step_tap_options", STEP_TIMEOUT_MS);

        // Three-dot "More options" button on the full player screen
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
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping options: desc='" + optionsBtn.getContentDescription() + "'");
        // performAction(ACTION_CLICK) directly — works regardless of isClickable state
        boolean clicked = optionsBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = optionsBtn.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
            }
        }
        optionsBtn.recycle();
        if (!clicked) { stepFailed("step_tap_options", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_tap_options", "Tap More Options");
        humanDelay(GAP_TINY, GAP_MEDIUM, this::stepTapAddToPlaylist);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    // Taps "Add to playlist" in the options bottom sheet
    private void stepTapAddToPlaylist() {
        if (timeoutFired) return;
        stepStarted("step_tap_add_to_pl", "Tap Add to Playlist");
        scheduleStepTimeout("step_tap_add_to_pl", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: android.view.View, clickable=TRUE, text="Add to playlist"
        // No resource-id — must find by text.
        AccessibilityNodeInfo addBtn = findByText("Add to playlist");
        if (addBtn == null) addBtn = findByText("Add to Playlist");
        if (addBtn == null) addBtn = findByDesc("Add to playlist");
        if (addBtn == null) addBtn = findByText("Save to playlist");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (addBtn == null) {
            pressBack();
            stepFailed("step_tap_add_to_pl", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping 'Add to playlist': clickable=" + addBtn.isClickable());

        // clickable=TRUE confirmed — performAction(ACTION_CLICK) directly.
        // clickFollowNode as fallback in case the text node itself doesn't absorb the click.
        boolean clicked = addBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clicked = clickFollowNode(addBtn);
        addBtn.recycle();

        if (!clicked) {
            pressBack();
            stepFailed("step_tap_add_to_pl", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }

        stepOk("step_tap_add_to_pl", "Tap Add to Playlist");
        humanDelay(GAP_SHORT, GAP_LONG, this::stepSelectPlaylist);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    // Locates the target playlist by name in the "Save in" sheet and taps its add button
    private void stepSelectPlaylist() {
        if (timeoutFired) return;
        stepStarted("step_select_playlist", "Select Target Playlist");
        scheduleStepTimeout("step_select_playlist", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed "Save in" sheet row structure (Compose, inside id/bottom_sheet_content):
        //
        //   android.view.View  (row container)
        //     ├── 0  android.view.View  (thumbnail)
        //     ├── 1  android.view.View  (name + subtitle text area)
        //     ├── 2  android.view.View  ("+"/tick icon  — clickable=FALSE, NOT the target)
        //     └── 3  android.widget.Button  (the real "+" — this is what absorbs the click)
        //
        // Strategy:
        //   findByText(playlistName) → walk UP ancestors up to 6 levels,
        //   at each level scan children for android.widget.Button → click it.
        //   This finds the Button that is a SIBLING of the text node's ancestor,
        //   something clickFollowNode (which only walks UP) cannot do.

        AccessibilityNodeInfo textNode = findByText(playlistName);
        if (textNode == null) textNode = findByDesc(playlistName);

        cancelStepTimeout();
        if (timeoutFired) return;

        if (textNode == null) {
            Log.e(TAG, "[EXEC] Playlist '" + playlistName + "' not found in picker — dumping screen");
            dumpScreenNodes();
            pressBack();
            stepFailed("step_select_playlist", "PLAYLIST_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Found playlist text: '" + textNode.getText()
                + "' clickable=" + textNode.isClickable());

        boolean clicked = clickAddButtonInRow(textNode);
        // textNode is recycled inside clickAddButtonInRow

        if (!clicked) {
            pressBack();
            stepFailed("step_select_playlist", "CLICK_ACTION_REJECTED");
            commandDone(false);
            return;
        }

        stepOk("step_select_playlist", "Select Target Playlist");
        scheduleStep(this::stepVerify, GAP_TINY);
    }

    /**
     * Given a text node inside a "Save in" row, walks up the ancestor chain (up to 6 levels)
     * looking for a sibling android.widget.Button at each level — that Button is the "+" add button.
     *
     * UIAutoDev tree (confirmed):
     *   row_container
     *     ├── view (thumbnail)
     *     ├── view (name text area)  ← textNode lives somewhere in here
     *     ├── view (+ icon, NOT clickable)
     *     └── android.widget.Button  ← TARGET (index 3 sibling)
     *
     * Recycles textNode before returning.
     */
    private boolean clickAddButtonInRow(AccessibilityNodeInfo textNode) {
        AccessibilityNodeInfo current = textNode;
        boolean clicked = false;

        for (int level = 0; level < 6 && !clicked; level++) {
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();   // always recycle — textNode at level 0, each ancestor after
            current = null;
            if (parent == null) break;
            current = parent;

            // Scan children of this ancestor for android.widget.Button
            int childCount = current.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child == null) continue;
                CharSequence cls = child.getClassName();
                if (cls != null && cls.toString().equals("android.widget.Button")) {
                    Log.i(TAG, "[EXEC] Found android.widget.Button at row level "
                            + level + " child-index=" + i
                            + " clickable=" + child.isClickable());
                    clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    child.recycle();
                    Log.i(TAG, "[EXEC] Button performAction(ACTION_CLICK) → " + clicked);
                    break;
                }
                child.recycle();
            }

            if (!clicked && current != null && current.isClickable()) {
                // Ancestor row is itself clickable — try it as a last resort at this level
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "[EXEC] Ancestor clickable at level " + level + " → " + clicked);
            }
        }

        if (current != null) current.recycle();
        return clicked;
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    // Confirms success by checking that the "Save in" bottom sheet has dismissed
    private void stepVerify() {
        if (timeoutFired) return;
        stepStarted("step_verify", "Verify Added to Playlist");
        scheduleStepTimeout("step_verify", STEP_TIMEOUT_MS);

        // After adding, the bottom sheet dismisses. If the picker is gone = success.
        AccessibilityNodeInfo sheet = findById(SPOTIFY_PACKAGE + ":id/bottom_sheet_content");
        cancelStepTimeout();
        if (timeoutFired) return;

        if (sheet == null) {
            Log.i(TAG, "[EXEC] Bottom sheet dismissed — track added to playlist");
        } else {
            sheet.recycle();
            Log.w(TAG, "[EXEC] Bottom sheet still visible — soft success (tap was sent)");
        }
        stepOk("step_verify", "Verify Added to Playlist");
        commandDone(true);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    // Fires GLOBAL_ACTION_BACK to dismiss the options or picker sheet
    @Override
    protected void pressBack() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc != null) {
            svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "[EXEC] Pressed Back to dismiss sheet");
        }
    }
}
