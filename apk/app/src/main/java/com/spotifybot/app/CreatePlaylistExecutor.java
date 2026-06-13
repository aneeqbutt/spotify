package com.spotifybot.app;

import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.gson.JsonObject;

/**
 * CreatePlaylistExecutor — CREATE_PLAYLIST
 *
 * Creates a new empty playlist in the user's library.
 * Params: { "playlist_name": "My New Playlist" }
 *
 * UIAutoDev confirmed:
 *
 *   Your Library screen — "+" button top-right:
 *     content-desc="Create"  (or "Add" fallback)  no resource-id
 *
 *   Options sheet after tapping "+":
 *     android.view.View  clickable=TRUE  (Playlist row, index 0 in ScrollView)
 *       ├── android.view.View  (icon)
 *       ├── android.view.View  (text area: "Playlist" + subtitle)
 *       └── android.widget.Button
 *     → findByText("Playlist") → clickFollowNode walks up to the clickable row
 *
 *   "Give your playlist a name" dialog (ComposeView → ScrollView):
 *     0  android.widget.TextView  text="Give your playlist a name"  (label)
 *     1  android.widget.EditText  text="My playlist #N"  isEditable=TRUE  ← name input
 *     2  android.view.View
 *     3  android.view.View
 *     4  android.view.View
 *          0  android.widget.TextView  text="Create"  clickable=FALSE
 *          1  android.widget.Button    ← absorbs the click (TARGET)
 *
 * Step chain:
 *   1. step_go_to_library       — tap Your Library nav tab
 *   2. step_tap_create_plus     — tap "+" at top-right of Library
 *   3. step_select_playlist_type — tap "Playlist" from options sheet
 *   4. step_enter_name          — type playlist name into EditText
 *   5. step_confirm             — tap Create button (Button sibling of "Create" TextView)
 *   6. step_verify              — soft verify (new playlist page or name visible)
 */
public class CreatePlaylistExecutor extends SpotifyExecutor {

    private String playlistName;

    @Override
    protected void doExecute(JsonObject params) {
        playlistName = params.has("playlist_name") ? params.get("playlist_name").getAsString() : "";
        if (playlistName.isEmpty()) {
            playlistName = params.has("query") ? params.get("query").getAsString() : "";
        }
        if (playlistName.isEmpty()) {
            stepFailed("step_go_to_library", "MISSING_PARAM_PLAYLIST_NAME");
            commandDone(false);
            return;
        }
        Log.i(TAG, "[EXEC] CreatePlaylist START | name='" + playlistName + "'");
        launchAndSettle(this::stepGoToLibrary);
    }

    // ── Step 1 ────────────────────────────────────────────────────────────────

    private void stepGoToLibrary() {
        if (timeoutFired) return;
        stepStarted("step_go_to_library", "Go to Your Library");
        scheduleStepTimeout("step_go_to_library", STEP_TIMEOUT_MS);

        AccessibilityNodeInfo libraryTab = findByDesc("Your Library, Tab");
        if (libraryTab == null) libraryTab = findByDesc("Your Library");
        if (libraryTab == null) libraryTab = findByDesc("Library, Tab");
        if (libraryTab == null) libraryTab = findByDesc("Library");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (libraryTab == null) {
            stepFailed("step_go_to_library", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        boolean clicked = clickNode(libraryTab);
        libraryTab.recycle();
        if (!clicked) { stepFailed("step_go_to_library", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_go_to_library", "Go to Your Library");
        humanDelay(GAP_SHORT, GAP_LONG, this::stepTapCreatePlus);
    }

    // ── Step 2 ────────────────────────────────────────────────────────────────

    private void stepTapCreatePlus() {
        if (timeoutFired) return;
        stepStarted("step_tap_create_plus", "Tap + Create Button");
        scheduleStepTimeout("step_tap_create_plus", STEP_TIMEOUT_MS);

        // UIAutoDev: "+" button top-right of Your Library.
        // No resource-id. Typically content-desc="Create" in Spotify.
        AccessibilityNodeInfo createBtn = findByDesc("Create");
        if (createBtn == null) createBtn = findByDesc("Add");
        if (createBtn == null) createBtn = findById(SPOTIFY_PACKAGE + ":id/create_button");
        if (createBtn == null) createBtn = findById(SPOTIFY_PACKAGE + ":id/fab");
        if (createBtn == null) createBtn = findById(SPOTIFY_PACKAGE + ":id/add_button");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (createBtn == null) {
            stepFailed("step_tap_create_plus", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping '+': desc='" + createBtn.getContentDescription() + "'");
        boolean clicked = createBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = createBtn.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
            }
        }
        createBtn.recycle();

        if (!clicked) { stepFailed("step_tap_create_plus", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_tap_create_plus", "Tap + Create Button");
        humanDelay(GAP_TINY, GAP_MEDIUM, this::stepSelectPlaylistType);
    }

    // ── Step 3 ────────────────────────────────────────────────────────────────

    private void stepSelectPlaylistType() {
        if (timeoutFired) return;
        stepStarted("step_select_playlist_type", "Select Playlist Type");
        scheduleStepTimeout("step_select_playlist_type", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed options sheet:
        //   android.view.View  clickable=TRUE  (the "Playlist" row, index 0 in ScrollView)
        //   Row text node = "Playlist"  subtitle = "Create a playlist with songs or episodes"
        //
        // clickFollowNode: walks up from text node → hits the clickable=true row ancestor → clicks it.
        AccessibilityNodeInfo playlistOption = findByText("Playlist");
        if (playlistOption == null) playlistOption = findByText("Create a playlist with songs or episodes");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (playlistOption == null) {
            stepFailed("step_select_playlist_type", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Tapping Playlist option: text='" + playlistOption.getText()
                + "' clickable=" + playlistOption.isClickable());

        // Row is clickable=true → clickFollowNode reaches it by walking up ancestors
        boolean clicked = clickFollowNode(playlistOption);
        playlistOption.recycle();

        if (!clicked) { stepFailed("step_select_playlist_type", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_select_playlist_type", "Select Playlist Type");
        humanDelay(GAP_TINY, GAP_MEDIUM, this::stepEnterName);
    }

    // ── Step 4 ────────────────────────────────────────────────────────────────

    private void stepEnterName() {
        if (timeoutFired) return;
        stepStarted("step_enter_name", "Enter Playlist Name");
        scheduleStepTimeout("step_enter_name", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed: android.widget.EditText  text="My playlist #N"  isEditable=TRUE
        // Located inside ComposeView → ScrollView at index 1.
        // typeText() uses ACTION_SET_TEXT → replaces the pre-filled "My playlist #N" entirely.
        AccessibilityNodeInfo nameInput = findEditableInput();

        cancelStepTimeout();
        if (timeoutFired) return;

        if (nameInput == null) {
            stepFailed("step_enter_name", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] EditText found: text='" + nameInput.getText()
                + "' editable=" + nameInput.isEditable());

        // Click to focus, then ACTION_SET_TEXT overwrites pre-filled content
        nameInput.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        boolean typed = typeText(nameInput, playlistName);
        nameInput.recycle();

        if (!typed) { stepFailed("step_enter_name", "TEXT_INPUT_FAILED"); commandDone(false); return; }

        stepOk("step_enter_name", "Enter Playlist Name");
        scheduleStep(this::stepConfirm, GAP_SHORT);
    }

    // ── Step 5 ────────────────────────────────────────────────────────────────

    private void stepConfirm() {
        if (timeoutFired) return;
        stepStarted("step_confirm", "Tap Create Button");
        scheduleStepTimeout("step_confirm", STEP_TIMEOUT_MS);

        // UIAutoDev confirmed (screenshot 4):
        //   android.view.View (index 4 inside ScrollView)
        //     ├── 0  android.widget.TextView  text="Create"  clickable=FALSE
        //     └── 1  android.widget.Button    ← TARGET
        //
        // findByText("Create") → get parent → scan siblings for android.widget.Button → click it.
        AccessibilityNodeInfo createText = findByText("Create");

        cancelStepTimeout();
        if (timeoutFired) return;

        if (createText == null) {
            // Last resort: press IME Enter on the still-focused EditText
            AccessibilityNodeInfo input = findEditableInput();
            if (input != null) {
                input.performAction(0x01000000); // ACTION_IME_ENTER
                input.recycle();
                Log.i(TAG, "[EXEC] No Create button found — pressed IME Enter as fallback");
                stepOk("step_confirm", "Tap Create Button");
                scheduleStep(this::stepVerify, GAP_SHORT);
                return;
            }
            stepFailed("step_confirm", "UI_ELEMENT_NOT_FOUND");
            commandDone(false);
            return;
        }

        Log.i(TAG, "[EXEC] Found 'Create' text node: clickable=" + createText.isClickable());
        // createText is recycled inside clickButtonSiblingOf
        boolean clicked = clickButtonSiblingOf(createText);

        if (!clicked) { stepFailed("step_confirm", "CLICK_ACTION_REJECTED"); commandDone(false); return; }

        stepOk("step_confirm", "Tap Create Button");
        scheduleStep(this::stepVerify, GAP_SHORT);
    }

    // ── Step 6 ────────────────────────────────────────────────────────────────

    private void stepVerify() {
        if (timeoutFired) return;
        stepStarted("step_verify", "Verify Playlist Created");
        scheduleStepTimeout("step_verify", STEP_TIMEOUT_MS);

        // After creation Spotify navigates to the new playlist page which shows the playlist name
        AccessibilityNodeInfo nameNode = findByText(playlistName);

        cancelStepTimeout();
        if (timeoutFired) return;

        if (nameNode != null) {
            nameNode.recycle();
            Log.i(TAG, "[EXEC] Playlist '" + playlistName + "' confirmed visible in new page");
        } else {
            Log.w(TAG, "[EXEC] Playlist name not visible after create — soft success");
        }
        stepOk("step_verify", "Verify Playlist Created");
        commandDone(true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Walk the accessibility tree recursively to find any isEditable=true node.
     * UIAutoDev confirmed: android.widget.EditText is the playlist name input (isEditable=true).
     * Returns the node — caller must recycle it.
     */
    private AccessibilityNodeInfo findEditableInput() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findEditableRecursive(root);
        // root is a different object from found (found is a deep child obtained via getChild chains)
        root.recycle();
        return found;
    }

    private AccessibilityNodeInfo findEditableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findEditableRecursive(child);
            if (result != null) {
                if (child != result) child.recycle();
                return result;
            }
            child.recycle();
        }
        return null;
    }

    /**
     * Given a label node (e.g. TextView text="Create", clickable=false), walks up to its parent
     * then scans siblings for android.widget.Button — that Button absorbs the click.
     *
     * UIAutoDev confirmed for the Create button:
     *   parent android.view.View (index 4 in ScrollView)
     *     ├── 0  android.widget.TextView  text="Create"  (clickable=false) ← anchorNode
     *     └── 1  android.widget.Button    ← TARGET
     *
     * Recycles anchorNode before returning.
     */
    private boolean clickButtonSiblingOf(AccessibilityNodeInfo anchorNode) {
        AccessibilityNodeInfo parent = anchorNode.getParent();
        anchorNode.recycle();

        if (parent == null) return false;

        boolean clicked = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence cls = child.getClassName();
            if (cls != null && cls.toString().equals("android.widget.Button")) {
                Log.i(TAG, "[EXEC] Found Button sibling at index=" + i
                        + " clickable=" + child.isClickable());
                clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                child.recycle();
                Log.i(TAG, "[EXEC] Create Button performAction(ACTION_CLICK) → " + clicked);
                break;
            }
            child.recycle();
        }

        // Fallback: parent row is itself clickable
        if (!clicked && parent.isClickable()) {
            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "[EXEC] Fallback: clicked parent of 'Create' text → " + clicked);
        }

        parent.recycle();
        return clicked;
    }
}
