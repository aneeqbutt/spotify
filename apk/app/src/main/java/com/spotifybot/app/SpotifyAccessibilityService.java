package com.spotifybot.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;

import java.util.List;

/**
 * SpotifyAccessibilityService — M1-C Core Deliverable
 *
 * Responsibilities (Week 1):
 * - Detect when Spotify is in the foreground
 * - Locate UI elements using node selectors (resource-id, content-desc, text)
 * - Perform clicks and verify state changes
 * - Log every action with timestamp, result, and reason code
 *
 * Responsibilities (Week 2 — added later):
 * - Receive commands from CommandRunner
 * - Execute full Spotify automation actions
 * - Emit STEP_STARTED / STEP_OK / STEP_FAILED events
 *
 * Survival:
 * - onServiceConnected() cancels any stale "needs reactivation" notification
 * - onDestroy() fires a high-priority notification guiding the user to re-enable
 *   if Samsung's accessibility manager kills the binding unexpectedly
 */
public class SpotifyAccessibilityService extends AccessibilityService {

    private static final String TAG              = "SpotifyBot";
    private static final String SPOTIFY_PACKAGE  = "com.spotify.music";
    private static final String ALERT_CHANNEL_ID = "spotifybot_alert";
    private static final int    ALERT_NOTIF_ID   = 2001;

    /**
     * Static singleton — lets executors access the live UI tree from outside the service.
     * Set in onServiceConnected(), cleared in onDestroy().
     */
    public static SpotifyAccessibilityService instance;

    // Tracks whether we have already performed the M1-C first click demo
    private boolean firstClickDone = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "[SERVICE] SpotifyAccessibilityService connected and running");

        // Cancel any stale "needs reactivation" notification — service is live again.
        cancelRebindNotification();

        // NOTE: do NOT call setServiceInfo() here.
        // The XML config (accessibility_service_config.xml) is complete and correct.
        // Overwriting with a programmatic AccessibilityServiceInfo that is missing
        // packageNames / canRetrieveWindowContent / canPerformGestures causes Samsung
        // Android 14 to reject the service and flip the toggle back to OFF.
        Log.i(TAG, "[SERVICE] Using XML service config — ready to receive events");

        // Auto-start BotForegroundService so the WebSocket connects immediately.
        // The user may have enabled the accessibility service directly from Android Settings
        // without ever opening the SpotifyBot app — this bridges the gap so the device
        // shows as online in the dashboard without needing to open the app manually.
        try {
            Intent fgIntent = new Intent(this, BotForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(fgIntent);
            } else {
                startService(fgIntent);
            }
            Log.i(TAG, "[SERVICE] BotForegroundService auto-started → WebSocket will connect");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Failed to auto-start BotForegroundService: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "[SERVICE] Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Only alert if service was previously connected (instance was set).
        // Avoids spurious notifications during APK install / uninstall.
        if (instance != null) {
            Log.w(TAG, "[SERVICE] Accessibility service destroyed — Samsung killed binding. Showing alert.");
            showRebindNotification();
        }

        instance = null;
        Log.i(TAG, "[SERVICE] Service destroyed");
    }

    // ── Event Handler ─────────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null
                ? event.getPackageName().toString() : "";

        // ── Automatic overlay dismissal (ALL packages) ────────────────────────
        // Run OverlayGuard on EVERY window-state change regardless of package.
        // Android system permission dialogs (e.g. "Allow Spotify to access Bluetooth")
        // fire events from "android" or "com.android.permissioncontroller" — NOT from
        // com.spotify.music — so the old Spotify-only guard below would skip them.
        // OverlayGuard.check() is throttled to 800ms so this is cheap.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            OverlayGuard.check(this);
        }

        // Only continue with Spotify-specific logic when Spotify is in the foreground
        if (!packageName.equals(SPOTIFY_PACKAGE)) return;

        Log.d(TAG, "[EVENT] Spotify event: type=" + event.getEventType()
                + " package=" + packageName);

        // ── Event-driven track-change detection ───────────────────────────────
        // TYPE_WINDOW_CONTENT_CHANGED fires continuously during Spotify playback
        // (seekbar updates, lyrics, etc.). We throttle to 2s and check whether the
        // track title changed — which signals the previous track finished.
        //
        // This is the primary PLAYBACK_FINISHED trigger and works even when Samsung
        // throttles handler.postDelayed() during screen-off, because accessibility
        // events are delivered by the system framework, not the app's main thread.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && SpotifyExecutor.activeMonitorRunId != null) {
            checkPlaybackTrackChange();
        }

        // ── M1-C: First click demo ────────────────────────────────────────────
        if (!firstClickDone
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(TAG, "[M1-C] Spotify window detected — waiting 1500ms for UI to settle");
            firstClickDone = true;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    this::performFirstClick, 1500);
        }
    }

    // ── Track-change detection ────────────────────────────────────────────────

    private static volatile long lastTrackCheckMs = 0;
    private static final long    TRACK_CHECK_THROTTLE_MS   = 2_000;
    /**
     * How long after the monitor starts to ignore title changes.
     *
     * Spotify's Now Playing bar can take 1-3s to update after a new track starts.
     * If the PREVIOUS song's title was captured as the baseline (because the UI
     * hadn't updated yet), the very first title change fires PLAYBACK_FINISHED
     * immediately — before the monitored song plays even a second.
     *
     * 10s absorbs this startup transition:  the song is already playing and
     * the UI is stable before we start watching for the NEXT change.
     */
    private static final long    MIN_TRACK_CHANGE_AGE_MS   = 10_000;

    /**
     * Called on every TYPE_WINDOW_CONTENT_CHANGED from Spotify while a playback
     * monitor is active.  Throttled to once per 2s so it doesn't spam the tree.
     *
     * Reads the current now-playing track title and compares it to the title
     * stored when the monitor started.  A title change means the previous track
     * finished and Spotify auto-advanced to the next song.
     */
    private void checkPlaybackTrackChange() {
        long now = System.currentTimeMillis();
        if (now - lastTrackCheckMs < TRACK_CHECK_THROTTLE_MS) return;
        lastTrackCheckMs = now;

        // Stabilization window: ignore changes in the first 10s after monitor start.
        // Prevents false PLAYBACK_FINISHED when Spotify's UI updates its title
        // from the previous song to the new one during the startup transition.
        long monitorAge = now - SpotifyExecutor.activeMonitorStartMs;
        if (monitorAge < MIN_TRACK_CHANGE_AGE_MS) {
            Log.d(TAG, "[MONITOR] Stabilizing (" + (monitorAge / 1000)
                    + "s / " + (MIN_TRACK_CHANGE_AGE_MS / 1000) + "s) — ignoring change");
            return;
        }

        String expectedTitle = SpotifyExecutor.activeMonitorTrackTitle;
        if (expectedTitle == null) return; // no title stored at monitor start — skip

        // Read current track title from the accessibility tree
        String currentTitle = readNowPlayingTitle();
        if (currentTitle == null || currentTitle.isEmpty()) return;
        if (currentTitle.equals(expectedTitle)) return;  // same track, still playing
        if ("Advertisement".equals(currentTitle))        return;  // ignore ad transitions

        // Title changed — previous track finished, new one started
        Log.i(TAG, "[MONITOR] Track change detected: '" + expectedTitle
                + "' → '" + currentTitle + "' — triggering PLAYBACK_FINISHED");
        SpotifyExecutor.onTrackChangedByEvent(currentTitle);
    }

    /**
     * Read the now-playing track title from Spotify's accessibility tree.
     * Tries the mini-player and full now-playing screen resource IDs.
     */
    private String readNowPlayingTitle() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        String[] titleIds = {
            SPOTIFY_PACKAGE + ":id/track_name",
            SPOTIFY_PACKAGE + ":id/nowplaying_track_name",
            SPOTIFY_PACKAGE + ":id/trackname",
        };
        for (String id : titleIds) {
            List<android.view.accessibility.AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                CharSequence text = nodes.get(0).getText();
                for (android.view.accessibility.AccessibilityNodeInfo n : nodes) n.recycle();
                root.recycle();
                if (text != null && text.length() > 0) return text.toString();
            }
        }
        root.recycle();
        return null;
    }

    // ── Rebind notification ───────────────────────────────────────────────────

    /**
     * Show a high-priority heads-up notification when Samsung kills the accessibility
     * service binding. Tapping the notification opens Accessibility Settings directly
     * so the user can toggle SpotifyBot OFF → ON in a few seconds.
     *
     * This fires from onDestroy() only when instance != null (i.e. the service was
     * previously running, not just failing to start for the first time).
     */
    private void showRebindNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            // Create high-importance channel (required on Android 8+)
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "SpotifyBot Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts when SpotifyBot accessibility service needs reactivation");
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);

            // Tap → open Accessibility Settings directly
            Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, settingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setContentTitle("⚠ SpotifyBot needs reactivation")
                    .setContentText("Tap → find SpotifyBot → toggle OFF then ON")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("Samsung stopped the accessibility service.\n\n"
                                    + "Tap this notification → scroll to SpotifyBot "
                                    + "→ toggle OFF → toggle ON. Takes 5 seconds."))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setOngoing(false)
                    .build();

            nm.notify(ALERT_NOTIF_ID, notification);
            Log.i(TAG, "[SERVICE] Rebind notification fired — user will be prompted");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Failed to show rebind notification: " + e.getMessage());
        }
    }

    /**
     * Cancel the rebind notification — called from onServiceConnected() so the
     * alert disappears automatically the moment Samsung rebinds the service.
     */
    private void cancelRebindNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(ALERT_NOTIF_ID);
        } catch (Exception ignored) {}
    }

    // ── M1-C: First Click ─────────────────────────────────────────────────────

    /**
     * M1-C.2 — Locate a real Spotify UI element and perform a click.
     * Target: Search tab (stable across Spotify versions).
     * Uses node selectors only — no coordinate-based clicking.
     */
    private void performFirstClick() {
        Log.i(TAG, "[STEP] element_search | initiated | target=search_tab");

        // Search all windows — on Android 11+ the nav bar may be in a separate window
        AccessibilityNodeInfo target = null;
        List<android.view.accessibility.AccessibilityWindowInfo> windows = getWindows();
        Log.i(TAG, "[STEP] element_search | window_count=" + (windows != null ? windows.size() : 0));

        if (windows != null) {
            for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;

                // Strategy 1 — recursive content-desc traversal (startsWith "Search")
                target = findNodeByContentDescRecursive(root, "Search");

                // Strategy 2 — resource-id fallback
                if (target == null) {
                    target = findNodeById(root, SPOTIFY_PACKAGE + ":id/search_tab");
                }

                if (target != null) {
                    Log.i(TAG, "[STEP] element_search | OK"
                            + " | element=" + target.getContentDescription()
                            + " | viewId=" + target.getViewIdResourceName()
                            + " | windowType=" + window.getType());
                    break;
                }
                root.recycle();
            }
        }

        // Fallback — active window only
        if (target == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                target = findNodeByContentDescRecursive(root, "Search");
                if (target == null) target = findNodeById(root, SPOTIFY_PACKAGE + ":id/search_tab");
                if (target == null) root.recycle();
            }
        }

        if (target == null) {
            Log.e(TAG, "[STEP] element_search | FAILED | reason=UI_ELEMENT_NOT_FOUND"
                    + " | detail=Search tab not found in any window");
            return;
        }

        // M1-C.2 — Perform the click (walk up to clickable ancestor if needed)
        AccessibilityNodeInfo clickTarget = findClickableNode(target);
        if (clickTarget == null) clickTarget = target;

        Log.i(TAG, "[STEP] element_click | attempting | clickable="
                + clickTarget.isClickable()
                + " | element=" + clickTarget.getContentDescription());

        boolean clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "[STEP] element_click | " + (clicked ? "OK" : "FAILED")
                + " | action=ACTION_CLICK | result=" + clicked);

        if (!clicked) {
            Log.e(TAG, "[STEP] element_click | FAILED | reason=CLICK_ACTION_REJECTED"
                    + " | detail=performAction returned false");
            target.recycle();
            return;
        }

        // M1-C.3 — Verify state change after click (wait 1s then re-check)
        verifyStateChange();
        target.recycle();
    }

    /**
     * M1-C.3 — Re-fetch the UI tree after 1 second and verify the expected
     * state change occurred (search bar now focused / search screen visible).
     */
    private void verifyStateChange() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "[STEP] state_verify | initiated | waiting=1000ms");

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.e(TAG, "[STEP] state_verify | FAILED | reason=UI_TREE_NULL");
                return;
            }

            // Check that the search input field is now visible — confirms navigation worked
            AccessibilityNodeInfo searchField = findNodeById(
                    root, SPOTIFY_PACKAGE + ":id/search_view");

            if (searchField == null) {
                searchField = findNodeByContentDescRecursive(root, "Search Spotify");
            }

            if (searchField != null) {
                Log.i(TAG, "[STEP] state_verify | OK"
                        + " | state=SEARCH_SCREEN_VISIBLE"
                        + " | element=" + searchField.getViewIdResourceName());
                Log.i(TAG, "[M1-C] ✅ FIRST CLICK COMPLETE — Search tab clicked and verified");
                searchField.recycle();
            } else {
                Log.w(TAG, "[STEP] state_verify | FAILED | reason=UNEXPECTED_STATE"
                        + " | detail=Search screen not confirmed after click");
            }

            root.recycle();
        }, 1000);
    }

    // ── Node Selector Helpers ─────────────────────────────────────────────────

    /**
     * Recursive depth-first search by content-description (startsWith, case-insensitive).
     * More reliable than findAccessibilityNodeInfosByText on Android 11+ for desc-only nodes.
     */
    private AccessibilityNodeInfo findNodeByContentDescRecursive(
            AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().toLowerCase().startsWith(prefix.toLowerCase())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findNodeByContentDescRecursive(child, prefix);
            if (result != null) return result;
            if (child != null) child.recycle();
        }
        return null;
    }

    /** Find node by resource-id (most precise — may change across Spotify versions) */
    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.get(0);
        }
        return null;
    }

    /**
     * Walk up the accessibility tree from the given node to find the nearest
     * clickable ancestor. Returns the original node if no clickable ancestor found.
     */
    private AccessibilityNodeInfo findClickableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) return parent;
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }
        return null;
    }
}
