package com.spotifybot.app;

import android.graphics.Path;
import android.graphics.Rect;
import android.accessibilityservice.GestureDescription;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SpotifyExecutor — Base class for all Spotify automation actions
 *
 * Provides:
 * - UI tree traversal helpers (findByDesc, findById, findByText)
 * - clickNode()              — walks up to clickable ancestor before clicking
 * - typeText()               — injects text into a focused input field
 * - sendStep()               — reports step events back to the backend via WebSocket
 * - handler                  — for postDelayed sequencing between steps
 *
 * Phase 5 additions:
 * - scheduleStepTimeout()    — arms a 15s watchdog per step
 * - cancelStepTimeout()      — disarms the watchdog when a step completes
 * - timeoutFired flag        — prevents double-reporting if the watchdog races a late completion
 *
 * Subclasses implement doExecute() which runs a chain of postDelayed steps.
 * Each step: scheduleStepTimeout → find element → interact → cancelStepTimeout → next step.
 */
public abstract class SpotifyExecutor {

    protected static final String TAG             = "SpotifyBot";
    protected static final String SPOTIFY_PACKAGE = "com.spotify.music";

    /** Default step timeout — 15 seconds */
    protected static final long STEP_TIMEOUT_MS = 15_000;

    /** How often to re-check while waiting for an ad/overlay to clear */
    private static final long AD_POLL_INTERVAL_MS = 3_000;
    /** Max polls before giving up — 20 × 3s = 60 seconds */
    private static final int  AD_MAX_POLLS        = 20;

    protected final Handler           handler = new Handler(Looper.getMainLooper());
    protected       BotWebSocketClient ws;
    protected       String            runId;
    protected       String            commandId;

    // ── Timeout watchdog ──────────────────────────────────────────────────────

    /** Currently armed timeout runnable — null when no step is in-flight */
    private Runnable pendingTimeout = null;

    /**
     * Set to true when the watchdog fires. Prevents a slow-completing step from
     * calling stepOk() + commandDone() AFTER the watchdog already sent STEP_FAILED.
     */
    protected volatile boolean timeoutFired = false;

    /**
     * Arm a per-step watchdog.
     *
     * Call this at the START of every step (after stepStarted).
     * If the step doesn't call cancelStepTimeout() within timeoutMs, the watchdog
     * fires: reports STEP_FAILED(STEP_TIMEOUT) + commandDone(false).
     *
     * @param stepId    The step that will time out (used in the failure event)
     * @param timeoutMs Milliseconds before the watchdog fires (use STEP_TIMEOUT_MS)
     */
    protected void scheduleStepTimeout(String stepId, long timeoutMs) {
        cancelStepTimeout(); // Cancel any previously armed watchdog first
        timeoutFired = false;

        pendingTimeout = () -> {
            timeoutFired = true;
            Log.e(TAG, "[EXEC] ⏰ STEP_TIMEOUT fired for step=" + stepId
                    + " after " + timeoutMs + "ms");
            stepFailed(stepId, "STEP_TIMEOUT");
            commandDone(false);
        };
        handler.postDelayed(pendingTimeout, timeoutMs);
        Log.d(TAG, "[EXEC] Watchdog armed: step=" + stepId + " timeout=" + timeoutMs + "ms");
    }

    /**
     * Disarm the watchdog.
     *
     * Call this at the END of every step before proceeding to the next one,
     * or immediately before calling commandDone().
     * Safe to call even if no watchdog is armed.
     */
    protected void cancelStepTimeout() {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
            Log.d(TAG, "[EXEC] Watchdog cancelled");
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Entry point — called by CommandRouter.
     * Sets context fields then calls doExecute() which subclasses implement.
     */
    public final void execute(String runId, String commandId,
                               JsonObject params, BotWebSocketClient ws) {
        this.runId     = runId;
        this.commandId = commandId;
        this.ws        = ws;
        timeoutFired   = false;

        // Fail fast if the accessibility service is not running.
        // This gives a clear ACCESSIBILITY_SERVICE_NOT_RUNNING reason code instead of
        // UI_ELEMENT_NOT_FOUND on every step (which happens when Samsung's battery
        // manager has killed the accessibility service in the background).
        if (SpotifyAccessibilityService.instance == null) {
            Log.e(TAG, "[EXEC] Accessibility service not running — failing command immediately");
            stepFailed("pre_check", "ACCESSIBILITY_SERVICE_NOT_RUNNING");
            commandDone(false);
            return;
        }

        doExecute(params);
    }

    /** Subclasses implement their step chain here */
    protected abstract void doExecute(JsonObject params);

    // ── Spotify launch helper ─────────────────────────────────────────────────

    /**
     * Bring Spotify to the foreground.
     * Called at the start of every executor so UI elements are guaranteed
     * to exist when steps run — even if the user is on a different app.
     *
     * Uses the package manager to get Spotify's launch intent, then starts
     * the activity from the accessibility service context.
     * FLAG_ACTIVITY_NEW_TASK is required when starting from a service.
     */
    protected void launchSpotify() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) {
            Log.w(TAG, "[EXEC] Cannot launch Spotify — accessibility service not running");
            return;
        }
        try {
            android.content.Intent intent = svc.getPackageManager()
                    .getLaunchIntentForPackage(SPOTIFY_PACKAGE);
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                svc.startActivity(intent);
                Log.i(TAG, "[EXEC] Spotify launched / brought to foreground");
            } else {
                Log.w(TAG, "[EXEC] Spotify not installed or not launchable");
            }
        } catch (Exception e) {
            Log.e(TAG, "[EXEC] Failed to launch Spotify: " + e.getMessage());
        }
    }

    /**
     * Launch Spotify, wait for it to settle, dismiss any blocking overlay, then
     * run the first step.
     *
     * Replaces the raw `launchSpotify(); handler.postDelayed(firstStep, 2000)` pattern
     * used by every executor.  The overlay check fires inside the settle delay so it
     * adds zero extra time when no overlay is present.
     *
     * Sequence:
     *   launchSpotify()
     *     └─ 2–3s jitter ─→ dismissOverlays()
     *                          └─ 400ms ─→ firstStep.run()
     */
    protected void launchAndSettle(Runnable firstStep) {
        launchSpotify();
        handler.postDelayed(() -> {
            dismissOverlays();
            handler.postDelayed(() -> {
                SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
                if (OverlayGuard.isAdOrOverlayBlocking(svc)) {
                    // Full-screen blocking overlay (video ad, premium upsell) — FAIL if timeout
                    Log.w(TAG, "[EXEC] Blocking ad/overlay at launch — entering ad wait");
                    waitForAdToClear(firstStep, "pre_launch_ad");
                } else if (OverlayGuard.isAudioAdPlaying(svc)) {
                    // Audio ad in mini-player — wait it out then PROCEED (never fail for this)
                    Log.i(TAG, "[EXEC] Audio ad playing — waiting before starting command");
                    waitForAudioAdToFinish(firstStep);
                } else {
                    firstStep.run();
                }
            }, 400);
        }, jitteredDelay(2_000));
    }

    /**
     * Attempt to dismiss any Spotify overlay blocking the UI.
     * Delegates to OverlayGuard which is the single source of truth for all
     * dismissal patterns — the same logic also runs automatically from
     * SpotifyAccessibilityService.onAccessibilityEvent() on every window change.
     *
     * Safe to call at any time. Returns true if something was dismissed.
     */
    protected boolean dismissOverlays() {
        return OverlayGuard.dismiss(SpotifyAccessibilityService.instance);
    }

    /**
     * Check for a mid-track "Skip ad" button and tap it if available.
     * The button appears after Spotify's ad countdown expires.
     * Returns true if a skip was performed.
     */
    protected boolean skipAdIfPresent() {
        AccessibilityNodeInfo skipAd = findByDesc("Skip ad");
        if (skipAd == null) skipAd = findByText("Skip ad");
        if (skipAd == null) skipAd = findByDesc("Skip Ad");
        if (skipAd != null) {
            boolean clicked = tapAtNodeRandom(skipAd);
            Log.i(TAG, "[AD] Skip ad tapped: " + clicked);
            skipAd.recycle();
            return clicked;
        }
        AccessibilityNodeInfo adLabel = findByDesc("Advertisement");
        if (adLabel == null) adLabel = findByText("Advertisement");
        if (adLabel != null) {
            adLabel.recycle();
            Log.i(TAG, "[AD] Ad playing — skip not yet available");
            return false;
        }
        return false;
    }

    // ── Ad / overlay wait ─────────────────────────────────────────────────────

    /**
     * Wait for any blocking Spotify ad or premium upsell overlay to clear, then
     * run {@code onClear}.
     *
     * "Blocking" means a video/interstitial ad or a full-screen premium upsell modal
     * that hides the Spotify bottom nav — NOT a background audio ad playing in the
     * mini-player (those don't block the UI and are ignored).
     *
     * On every poll we:
     *   1. Call OverlayGuard.dismiss() — catches "Continue with ads", "Not now", etc.
     *   2. Call skipAdIfPresent()      — taps "Skip ad" if the countdown finished.
     *   3. Re-check isAdOrOverlayBlocking().
     *
     * If the overlay clears → onClear.run() (the first/next executor step).
     * If still blocked after AD_MAX_POLLS × AD_POLL_INTERVAL_MS (60 s) →
     * stepFailed(failStepId, "AD_TIMEOUT") + commandDone(false).
     *
     * @param onClear     Step to run once the screen is clear.
     * @param failStepId  Step-id reported in STEP_FAILED if we time out.
     */
    protected void waitForAdToClear(Runnable onClear, String failStepId) {
        Log.i(TAG, "[AD_WAIT] Ad/overlay blocking — will retry every "
                + (AD_POLL_INTERVAL_MS / 1000) + "s (max "
                + (AD_MAX_POLLS * AD_POLL_INTERVAL_MS / 1000) + "s)");
        waitForAdPoll(onClear, failStepId, 0);
    }

    private void waitForAdPoll(Runnable onClear, String failStepId, int poll) {
        if (timeoutFired) return; // Command was already terminated elsewhere

        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;

        // Attempt dismissal on every poll — catches "Skip ad", "Continue with ads", Back
        OverlayGuard.dismiss(svc);
        skipAdIfPresent();

        if (!OverlayGuard.isAdOrOverlayBlocking(svc)) {
            Log.i(TAG, "[AD_WAIT] Cleared after poll " + poll
                    + " — resuming step=" + failStepId);
            onClear.run();
            return;
        }

        if (poll >= AD_MAX_POLLS) {
            Log.e(TAG, "[AD_WAIT] Ad/overlay did not clear after "
                    + (AD_MAX_POLLS * AD_POLL_INTERVAL_MS / 1000) + "s — failing");
            timeoutFired = true;
            stepFailed(failStepId, "AD_TIMEOUT");
            commandDone(false);
            return;
        }

        Log.d(TAG, "[AD_WAIT] Still blocked — poll " + (poll + 1)
                + "/" + AD_MAX_POLLS + ", retrying in "
                + (AD_POLL_INTERVAL_MS / 1000) + "s");
        handler.postDelayed(
                () -> waitForAdPoll(onClear, failStepId, poll + 1),
                AD_POLL_INTERVAL_MS);
    }

    // ── Audio ad wait ─────────────────────────────────────────────────────────
    //
    // Different from waitForAdToClear():
    //   • waitForAdToClear  → for blocking overlays  → times out with STEP_FAILED
    //   • waitForAudioAdToFinish → for audio ads      → times out with PROCEED
    //
    // Audio ads are a normal free-tier condition, not an error. We must never fail
    // a command just because an ad was playing — we wait it out and continue.
    //
    // Spotify free tier plays 1–2 audio ads back-to-back, each 15–30s.
    // 20 polls × 3s = 60s covers the worst-case back-to-back scenario.

    private static final int  AUDIO_AD_MAX_POLLS     = 20;   // 20 × 3s = 60s max
    private static final long AUDIO_AD_POLL_MS       = 3_000;

    /**
     * Wait for the current audio ad to finish, then run {@code onClear}.
     * Never fails — if the ad doesn't clear within 60s we proceed anyway.
     */
    protected void waitForAudioAdToFinish(Runnable onClear) {
        Log.i(TAG, "[AUDIO_AD] Waiting for audio ad to finish (max "
                + (AUDIO_AD_MAX_POLLS * AUDIO_AD_POLL_MS / 1000) + "s)");
        audioAdPoll(onClear, 0);
    }

    private void audioAdPoll(Runnable onClear, int poll) {
        if (timeoutFired) return;

        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;

        if (!OverlayGuard.isAudioAdPlaying(svc)) {
            Log.i(TAG, "[AUDIO_AD] Ad finished after poll " + poll + " — resuming");
            onClear.run();
            return;
        }

        if (poll >= AUDIO_AD_MAX_POLLS) {
            // 60s elapsed and ad is still showing — proceed anyway.
            // Better to attempt the command than to stall forever.
            Log.w(TAG, "[AUDIO_AD] Ad did not finish within 60s — proceeding anyway");
            onClear.run();
            return;
        }

        Log.d(TAG, "[AUDIO_AD] Ad still playing — poll " + (poll + 1)
                + "/" + AUDIO_AD_MAX_POLLS + ", next check in "
                + (AUDIO_AD_POLL_MS / 1000) + "s");
        handler.postDelayed(() -> audioAdPoll(onClear, poll + 1), AUDIO_AD_POLL_MS);
    }

    // ── Node finding ──────────────────────────────────────────────────────────

    /**
     * Search all Spotify windows for a node whose content-desc contains `prefix`.
     * Searches recursively — handles deep Compose hierarchies.
     */
    protected AccessibilityNodeInfo findByDesc(String prefix) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo result = findByDescRecursive(root, prefix);
                if (result != null) return result;
                root.recycle();
            }
        }
        // Fallback — active window
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null) return findByDescRecursive(root, prefix);
        return null;
    }

    private AccessibilityNodeInfo findByDescRecursive(AccessibilityNodeInfo node, String prefix) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().toLowerCase().contains(prefix.toLowerCase())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findByDescRecursive(child, prefix);
            if (result != null) return result;
            if (child != null) child.recycle();
        }
        return null;
    }

    /** Find node by resource-id in the active window */
    protected AccessibilityNodeInfo findById(String viewId) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /** Find node by visible text (case-insensitive contains) */
    protected AccessibilityNodeInfo findByText(String text) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /**
     * Like findByText but SKIPS editable nodes (the search bar EditText).
     *
     * Problem: after submitting a search for e.g. "Ice man", the search bar
     * EditText still holds that text. findByText("Ice man") returns the search bar
     * FIRST (it appears before results in the tree), so clickFollowNode would tap
     * the search bar instead of the album/playlist card.
     *
     * This method skips any node where isEditable()=true, returning the first
     * NON-editable text match — which is the result item title in the search list.
     */
    protected AccessibilityNodeInfo findByTextNotEditable(String text) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isEditable() && found == null) {
                found = node;
            } else {
                node.recycle();
            }
        }
        return found;
    }

    /**
     * Try ACTION_CLICK on a node, then walk up at most maxAncestors levels.
     * Safer than clickFollowNode (which goes 6 levels) for result row taps
     * where over-shooting can accidentally click the wrong container.
     */
    protected boolean clickNodeBounded(AccessibilityNodeInfo node, int maxAncestors) {
        if (node == null) return false;
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo current = node.getParent();
        for (int level = 1; level <= maxAncestors && current != null; level++) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "[EXEC] clickNodeBounded: clicked ancestor level=" + level);
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return false;
    }

    // ── Interaction helpers ───────────────────────────────────────────────────

    /**
     * Click a node — walks up to its nearest clickable ancestor if the node itself
     * isn't clickable (common with Compose-rendered views).
     */
    protected boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        // Walk up
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            AccessibilityNodeInfo gp = parent.getParent();
            parent.recycle();
            parent = gp;
        }
        return false;
    }

    /**
     * Click a node by trying ACTION_CLICK directly — NOT gated by isClickable().
     * Walks up to 6 ancestors trying each one. Required for Jetpack Compose nodes
     * that report click=false but still respond to ACTION_CLICK (bottom sheets, nav rows).
     */
    protected boolean clickFollowNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "[EXEC] clickFollowNode: clicked node itself");
            return true;
        }
        AccessibilityNodeInfo current = node.getParent();
        for (int level = 1; level <= 6 && current != null; level++) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "[EXEC] clickFollowNode: clicked ancestor level=" + level);
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return false;
    }

    /**
     * Tap a node using a gesture with a small random coordinate offset (±15px) and
     * a randomised press duration (80–200ms).  This prevents perfectly centred,
     * machine-precise taps that bot-detection systems flag.
     *
     * Uses dispatchGesture() which requires android:canPerformGestures="true"
     * (already set in accessibility_service_config.xml).  Falls back to
     * clickNode() if the gesture is rejected or the node has no screen bounds.
     *
     * Drop-in replacement for clickNode() wherever coordinate variance matters.
     * The gesture fires asynchronously but returns immediately — the subsequent
     * postDelayed call provides the buffer before the next step reads the UI.
     */
    protected boolean tapAtNodeRandom(AccessibilityNodeInfo node) {
        if (node == null) return false;

        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return clickNode(node);

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return clickNode(node);

        // Random offset clamped so the tap stays 2px inside node edges
        int offsetX = ThreadLocalRandom.current().nextInt(-15, 16);
        int offsetY = ThreadLocalRandom.current().nextInt(-15, 16);
        float tapX = Math.max(bounds.left + 2f,
                     Math.min(bounds.right  - 2f, bounds.centerX() + offsetX));
        float tapY = Math.max(bounds.top  + 2f,
                     Math.min(bounds.bottom - 2f, bounds.centerY() + offsetY));

        // Random press duration — humans don't tap instantaneously
        long tapDuration = 80 + ThreadLocalRandom.current().nextInt(120);

        Path tapPath = new Path();
        tapPath.moveTo(tapX, tapY);

        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(tapPath, 0, tapDuration))
                    .build();
            boolean dispatched = svc.dispatchGesture(gesture, null, null);
            Log.d(TAG, "[EXEC] tapAtNodeRandom: dispatched=" + dispatched
                    + " coords=(" + (int) tapX + "," + (int) tapY + ")"
                    + " offset=(" + offsetX + "," + offsetY + ")"
                    + " dur=" + tapDuration + "ms");
            if (dispatched) return true;
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] tapAtNodeRandom gesture error: " + e.getMessage());
        }

        // Fallback
        return clickNode(node);
    }

    /**
     * Add ±35% random jitter to a base delay.
     *
     * Usage:  handler.postDelayed(this::nextStep, jitteredDelay(2_000));
     * Result: delay between 1300–2700ms instead of a machine-perfect 2000ms.
     *
     * Applied to every postDelayed call in executors so step-to-step timing
     * is never constant across repeated runs.
     */
    protected long jitteredDelay(long baseMs) {
        long jitter = (long) (baseMs * 0.35);
        // Uniformly distributed in [-jitter, +jitter]
        return baseMs + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    /**
     * Press the system Back button.
     */
    protected void pressBack() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc != null) {
            svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "[EXEC] pressBack()");
        }
    }

    /**
     * Schedule the next step with a randomized human-like delay (2–4 seconds).
     * Inserts between automation steps to avoid machine-perfect timing patterns.
     */
    protected void humanDelay(Runnable next) {
        humanDelay(2_000, 4_000, next);
    }

    /**
     * Schedule the next step with a random delay between minMs and maxMs.
     */
    protected void humanDelay(long minMs, long maxMs, Runnable next) {
        long delay = minMs + ThreadLocalRandom.current().nextInt((int)(maxMs - minMs));
        Log.d(TAG, "[EXEC] Human delay: " + delay + "ms");
        handler.postDelayed(next, delay);
    }

    /**
     * Type text into a focused input field using ACTION_SET_TEXT.
     * More reliable than simulating key events — works with Spotify's search input.
     */
    protected boolean typeText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        // Focus the field first
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /**
     * Diagnostic: log the first 60 nodes in the active window (text + desc + class + clickable).
     * Call when a UI element is not found so logcat shows exactly what IS on screen.
     */
    protected void dumpScreenNodes() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { Log.w(TAG, "[DUMP] root is null"); return; }
        Log.w(TAG, "[DUMP] ===== SCREEN NODE DUMP =====");
        dumpRecursive(root, 0, new int[]{0});
        root.recycle();
        Log.w(TAG, "[DUMP] ===== END DUMP =====");
    }

    private void dumpRecursive(AccessibilityNodeInfo node, int depth, int[] count) {
        if (node == null || count[0] > 80) return;
        CharSequence txt  = node.getText();
        CharSequence desc = node.getContentDescription();
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        cls = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
        if ((txt != null && txt.length() > 0) || (desc != null && desc.length() > 0)) {
            Log.w(TAG, "[DUMP][" + count[0] + "] depth=" + depth
                    + " cls=" + cls
                    + " text='" + (txt != null ? txt : "") + "'"
                    + " desc='" + (desc != null ? desc : "") + "'"
                    + " clickable=" + node.isClickable()
                    + " editable=" + node.isEditable());
            count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            dumpRecursive(child, depth + 1, count);
            if (child != null) child.recycle();
        }
    }

    // ── Playback monitor ─────────────────────────────────────────────────────
    //
    // After commandDone(true) for SEARCH_AND_PLAY / PLAY_FROM_PLAYLIST, the
    // executor calls startPlaybackMonitor(runId).
    //
    // UIAutoDev-confirmed approach (2026-06-02):
    //   The full Now Playing screen contains:
    //     android.widget.SeekBar  id=com.spotify.music:id/seekbar  text=<positionMs>
    //   getRangeInfo().getCurrent() = position in ms
    //   getRangeInfo().getMax()     = total duration in ms
    //
    //   When Spotify is showing the mini-player (not full screen), the seekbar is absent.
    //   In that case we tap  id=com.spotify.music:id/now_playing_container  (UIAutoDev-confirmed
    //   ID for the mini-player bar) to expand it, then resume polling.

    private static final long  PLAYBACK_POLL_INTERVAL_MS  = 5_000;
    private static final long  PLAYBACK_MONITOR_MAX_MS    = 90L * 60 * 1_000; // 90-min safety cap
    private static final float TRACK_END_THRESHOLD_MS     = 3_000f; // fire 3 s before actual end
    /** Minimum track duration to be considered a real song (not an ad).
     *  Spotify ads are 15-30s. Songs are almost always > 60s. */
    private static final float MIN_SONG_DURATION_MS       = 45_000f; // 45 seconds
    /** Don't fire PLAYBACK_FINISHED within the first 30s of monitoring —
     *  prevents false positives if the monitor starts near the end of a seekbar. */
    private static final long  MIN_MONITOR_AGE_MS         = 30_000L; // 30 seconds

    // ── Event-driven track-change detection ───────────────────────────────────
    //
    // Samsung throttles handler.postDelayed() during screen-off, so the seekbar
    // poll never fires and PLAYBACK_FINISHED is never sent.
    //
    // Solution: register the active monitor in static fields that
    // SpotifyAccessibilityService.onAccessibilityEvent() reads on every
    // TYPE_WINDOW_CONTENT_CHANGED event from Spotify.  Accessibility events are
    // delivered by the system framework and are NOT subject to postDelayed throttling.
    //
    // When the track title changes (new song started = old song ended),
    // onTrackChangedByEvent() is called immediately — no polling required.

    /** Run-id being monitored. Null means no active monitor. Volatile for thread safety. */
    public  static volatile String activeMonitorRunId      = null;
    /** Track title when monitoring started. Title change = previous track finished. */
    public  static volatile String activeMonitorTrackTitle = null;
    /**
     * Timestamp (ms) when monitoring started. The event-driven detector ignores
     * title changes within the first MIN_TRACK_CHANGE_AGE_MS milliseconds.
     *
     * Why: Spotify's Now Playing bar can take 1-3s to update after a song starts.
     * If the previous song was still showing when the monitor registered, the first
     * title change fires PLAYBACK_FINISHED immediately — before the monitored song
     * even begins. The stabilization window absorbs this startup transition.
     */
    public  static volatile long   activeMonitorStartMs    = 0;

    /**
     * Called by SpotifyAccessibilityService when it detects a track title change
     * while a monitor is active.  Sends PLAYBACK_FINISHED and clears state so
     * the postDelayed backup poll also stops.
     *
     * @param newTitle  The new track title that triggered the change detection.
     */
    public static void onTrackChangedByEvent(String newTitle) {
        String runId = activeMonitorRunId;
        if (runId == null) return;

        // Clear immediately so the postDelayed backup poll stops on its next tick
        activeMonitorRunId      = null;
        activeMonitorTrackTitle = null;

        Log.i("SpotifyBot", "[MONITOR] Track changed via accessibility event → '"
                + newTitle + "' — sending PLAYBACK_FINISHED run=" + runId);

        // BotForegroundService.currentWs is always the live, authenticated socket
        BotWebSocketClient liveWs = BotForegroundService.currentWs;
        if (liveWs != null && liveWs.isAuthenticated()) {
            com.google.gson.JsonObject event = new com.google.gson.JsonObject();
            event.addProperty("type",   "PLAYBACK_FINISHED");
            event.addProperty("run_id", runId);
            boolean sent = liveWs.send(event);
            Log.i("SpotifyBot", "[MONITOR] PLAYBACK_FINISHED sent=" + sent
                    + " (event-driven) run=" + runId);
        } else {
            Log.w("SpotifyBot", "[MONITOR] No live WS for event-driven PLAYBACK_FINISHED run=" + runId);
        }
    }

    /**
     * Start monitoring playback position after commandDone(true).
     *
     * Uses two complementary strategies:
     *   1. Event-driven: reads title change via accessibility events (reliable during sleep)
     *   2. Seekbar poll: postDelayed loop that reads seekbar position (backup for when
     *      the track doesn't auto-advance, e.g. user paused, or accessibility events missed)
     *
     * @param monitorRunId run_id of the just-completed command, echoed in PLAYBACK_FINISHED.
     */
    protected void startPlaybackMonitor(String monitorRunId) {
        // Strategy 1: register for event-driven detection.
        // SpotifyAccessibilityService will call onTrackChangedByEvent() the instant
        // Spotify's now-playing title changes — no postDelayed required.
        String initialTitle = readCurrentTrackTitle();
        activeMonitorRunId      = monitorRunId;
        activeMonitorTrackTitle = initialTitle;
        activeMonitorStartMs    = System.currentTimeMillis();
        Log.i(TAG, "[MONITOR] Playback monitor started run=" + monitorRunId
                + " initial_title='" + initialTitle + "'"
                + " (event-driven + seekbar poll, stabilization=10s)");

        // Strategy 2: seekbar poll backup (fires if event-driven misses the change)
        long startMs = System.currentTimeMillis();
        handler.postDelayed(
                () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                PLAYBACK_POLL_INTERVAL_MS);
    }

    /**
     * Read the current track title from Spotify's now-playing UI.
     * Returns null if Spotify is not visible or the title node can't be found.
     */
    private String readCurrentTrackTitle() {
        // Try the most common Spotify resource IDs for the track name.
        // These cover the mini-player and the full now-playing screen.
        String[] titleIds = {
            SPOTIFY_PACKAGE + ":id/track_name",
            SPOTIFY_PACKAGE + ":id/nowplaying_track_name",
            SPOTIFY_PACKAGE + ":id/trackname",
            SPOTIFY_PACKAGE + ":id/title",
        };
        for (String id : titleIds) {
            AccessibilityNodeInfo node = findById(id);
            if (node != null) {
                CharSequence text = node.getText();
                node.recycle();
                if (text != null && text.length() > 0) return text.toString();
            }
        }
        return null;
    }

    private void pollPlaybackPosition(String monitorRunId, long startMs, int notFoundCount) {
        // Stop if the event-driven path already fired PLAYBACK_FINISHED for this run
        if (!monitorRunId.equals(activeMonitorRunId)) {
            Log.d(TAG, "[MONITOR] postDelayed poll stopping — event-driven path already "
                    + "handled run=" + monitorRunId);
            return;
        }
        if (System.currentTimeMillis() - startMs > PLAYBACK_MONITOR_MAX_MS) {
            Log.w(TAG, "[MONITOR] 90-min cap — stopping monitor run=" + monitorRunId);
            return;
        }
        if (SpotifyAccessibilityService.instance == null) {
            Log.w(TAG, "[MONITOR] Service gone — stopping monitor run=" + monitorRunId);
            return;
        }

        float[] progress = readSeekbarProgress();

        if (progress == null) {
            // SeekBar not visible — full Now Playing screen is closed (mini-player only).
            // After 3 consecutive misses (15 s) tap the mini-player to open the full screen.
            // UIAutoDev confirmed: mini-player container = id/now_playing_container.
            if (notFoundCount == 3) {
                Log.i(TAG, "[MONITOR] SeekBar absent after 3 polls — tapping mini-player to expand");
                tryExpandNowPlayingBar();
                // Give the expand animation 2 s before the next poll
                handler.postDelayed(
                        () -> pollPlaybackPosition(monitorRunId, startMs, notFoundCount + 1),
                        2_000);
                return;
            }
            handler.postDelayed(
                    () -> pollPlaybackPosition(monitorRunId, startMs, notFoundCount + 1),
                    PLAYBACK_POLL_INTERVAL_MS);
            return;
        }

        float currentMs = progress[0];
        float maxMs     = progress[1];

        Log.d(TAG, "[MONITOR] pos=" + (int)(currentMs / 1000) + "s"
                + " dur=" + (int)(maxMs / 1000) + "s"
                + " run=" + monitorRunId);

        // Guard 1: ignore short content (ads are 15-30s, real songs are 45s+)
        if (maxMs < MIN_SONG_DURATION_MS) {
            Log.d(TAG, "[MONITOR] Skipping short seekbar (" + (int)(maxMs/1000) + "s) — likely an ad");
            handler.postDelayed(
                    () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                    PLAYBACK_POLL_INTERVAL_MS);
            return;
        }

        // Guard 2: don't fire in the first 30s of monitoring — prevents false positives
        // if the monitor started near the tail end of a seekbar (e.g. a residual UI state)
        long monitorAgeMs = System.currentTimeMillis() - startMs;
        if (monitorAgeMs < MIN_MONITOR_AGE_MS) {
            Log.d(TAG, "[MONITOR] Too early to fire (" + (monitorAgeMs/1000) + "s into monitoring)");
            handler.postDelayed(
                    () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                    PLAYBACK_POLL_INTERVAL_MS);
            return;
        }

        if (currentMs >= maxMs - TRACK_END_THRESHOLD_MS) {
            Log.i(TAG, "[MONITOR] Track finished (" + (int)(currentMs/1000) + "s / "
                    + (int)(maxMs/1000) + "s) — sending PLAYBACK_FINISHED run=" + monitorRunId);
            sendPlaybackFinished(monitorRunId);
            return;
        }

        // Still playing — poll again (reset counter since seekbar is visible)
        handler.postDelayed(
                () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                PLAYBACK_POLL_INTERVAL_MS);
    }

    /**
     * Read current position and total duration from the Spotify seekbar using
     * AccessibilityNodeInfo.RangeInfo.
     *
     * UIAutoDev confirmed (2026-06-02):
     *   id=com.spotify.music:id/seekbar — android.widget.SeekBar
     *   text=<currentPositionMs>  (e.g. "61695.0")
     *   RangeInfo.getCurrent() = position in ms
     *   RangeInfo.getMax()     = total duration in ms
     *
     * @return float[2] = { currentMs, maxMs }, or null if seekbar not in tree.
     */
    private float[] readSeekbarProgress() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;

        AccessibilityNodeInfo seekbar = findSeekbarNode(svc);
        if (seekbar == null) return null;

        AccessibilityNodeInfo.RangeInfo range = seekbar.getRangeInfo();
        seekbar.recycle();

        if (range == null) return null;

        float current = range.getCurrent();
        float max     = range.getMax();
        if (max <= 0) return null;

        return new float[]{ current, max };
    }

    /**
     * Find the Spotify seekbar node across all windows.
     * Active window is checked first (fastest), then all windows.
     */
    private AccessibilityNodeInfo findSeekbarNode(SpotifyAccessibilityService svc) {
        final String SEEKBAR_ID = SPOTIFY_PACKAGE + ":id/seekbar";

        // Active window first
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(SEEKBAR_ID);
            root.recycle();
            if (nodes != null && !nodes.isEmpty()) {
                AccessibilityNodeInfo result = nodes.get(0);
                for (int i = 1; i < nodes.size(); i++) nodes.get(i).recycle();
                return result;
            }
        }

        // All windows (handles Spotify in background)
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo wRoot = window.getRoot();
                if (wRoot == null) continue;
                List<AccessibilityNodeInfo> nodes = wRoot.findAccessibilityNodeInfosByViewId(SEEKBAR_ID);
                wRoot.recycle();
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo result = nodes.get(0);
                    for (int i = 1; i < nodes.size(); i++) nodes.get(i).recycle();
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Tap the mini-player bar to expand it into the full Now Playing screen.
     *
     * UIAutoDev confirmed (2026-06-02):
     *   Mini-player container = id=com.spotify.music:id/now_playing_container
     *   (previously used now_playing_bar_layout which does NOT exist on this device)
     *
     * Uses ACTION_CLICK directly — more reliable than dispatchGesture for this expand action.
     */
    private void tryExpandNowPlayingBar() {
        // UIAutoDev-confirmed ID first, then fallbacks for older Spotify versions
        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_container");
        if (bar == null) bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar");
        if (bar == null) bar = findByDesc("Now Playing Bar");

        if (bar != null) {
            // ACTION_CLICK is more reliable than dispatchGesture for expand
            boolean clicked = bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) clicked = tapAtNodeRandom(bar); // gesture fallback
            bar.recycle();
            Log.i(TAG, "[MONITOR] Expand mini-player ACTION_CLICK=" + clicked);
        } else {
            Log.w(TAG, "[MONITOR] Mini-player container not found — cannot expand");
        }
    }

    /**
     * Send PLAYBACK_FINISHED to the backend via WebSocket.
     * Falls back to BotForegroundService.currentWs if original WS was replaced by a reconnect.
     */
    private void sendPlaybackFinished(String monitorRunId) {
        // Clear static monitor state so the event-driven path stops checking
        if (monitorRunId.equals(activeMonitorRunId)) {
            activeMonitorRunId      = null;
            activeMonitorTrackTitle = null;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type",   "PLAYBACK_FINISHED");
        event.addProperty("run_id", monitorRunId);

        BotWebSocketClient liveWs = ws;
        if (liveWs == null || !liveWs.isAuthenticated()) {
            liveWs = BotForegroundService.currentWs;
        }
        if (liveWs != null && liveWs.isAuthenticated()) {
            boolean sent = liveWs.send(event);
            Log.i(TAG, "[MONITOR] PLAYBACK_FINISHED sent=" + sent
                    + " (seekbar poll) run=" + monitorRunId);
        } else {
            Log.w(TAG, "[MONITOR] No live WS — PLAYBACK_FINISHED not delivered run=" + monitorRunId);
        }
    }

    // ── Step event protocol ───────────────────────────────────────────────────

    protected void stepStarted(String stepId, String stepName) {
        sendStep("STEP_STARTED", stepId, stepName, null, null);
        Log.i(TAG, "[EXEC] → STEP_STARTED step=" + stepId);
    }

    protected void stepOk(String stepId, String stepName) {
        sendStep("STEP_OK", stepId, stepName, null, null);
        Log.i(TAG, "[EXEC] → STEP_OK step=" + stepId);
    }

    protected void stepFailed(String stepId, String reasonCode) {
        sendStep("STEP_FAILED", stepId, null, reasonCode, null);
        Log.e(TAG, "[EXEC] → STEP_FAILED step=" + stepId + " reason=" + reasonCode);
    }

    // ── COMMAND_DONE retry ────────────────────────────────────────────────────
    // COMMAND_DONE is the only terminal event — if it's lost the backend times
    // out the run 2 minutes later. The WS may be briefly reconnecting (1–30s)
    // exactly when we try to send. Retry every 2s for up to 30s to cover this.

    private static final int  COMMAND_DONE_MAX_RETRIES = 150; // 150 × 2s = 300s (5 min) window
    private static final long COMMAND_DONE_RETRY_MS    = 2_000;

    /**
     * Signal command completion.
     * Always cancels any live watchdog first — prevents the watchdog from firing
     * after commandDone() has already sent a terminal event.
     * Retries delivery for up to 30s if the WebSocket is temporarily down.
     */
    protected void commandDone(boolean success) {
        cancelStepTimeout();
        String status = success ? "SUCCESS" : "FAILED";
        Log.i(TAG, "[EXEC] → COMMAND_DONE status=" + status);

        JsonObject event = buildEvent("COMMAND_DONE", null, null, null, status);
        sendCommandDoneWithRetry(event, 0);
    }

    private void sendCommandDoneWithRetry(JsonObject event, int attempt) {
        // Try the captured WS first; if that's dead (reconnect happened mid-command),
        // fall through to BotForegroundService.currentWs which always points at the live socket.
        BotWebSocketClient liveWs = ws;
        if (liveWs == null || !liveWs.isAuthenticated()) {
            liveWs = BotForegroundService.currentWs;
        }
        if (liveWs != null && liveWs.send(event)) {
            if (attempt > 0) Log.i(TAG, "[EXEC] COMMAND_DONE delivered on retry #" + attempt
                    + " via " + (liveWs == ws ? "original" : "reconnected") + " socket");
            return;
        }

        if (attempt >= COMMAND_DONE_MAX_RETRIES) {
            Log.e(TAG, "[EXEC] COMMAND_DONE delivery failed after " + attempt
                    + " retries — backend will time out the run");
            return;
        }

        Log.w(TAG, "[EXEC] COMMAND_DONE send failed (WS down?) — retry #"
                + (attempt + 1) + " in " + COMMAND_DONE_RETRY_MS + "ms");
        handler.postDelayed(() -> sendCommandDoneWithRetry(event, attempt + 1),
                COMMAND_DONE_RETRY_MS);
    }

    private void sendStep(String eventType, String stepId, String stepName,
                           String reasonCode, String finalStatus) {
        JsonObject event = buildEvent(eventType, stepId, stepName, reasonCode, finalStatus);
        if (ws != null) ws.send(event);
    }

    private JsonObject buildEvent(String eventType, String stepId, String stepName,
                                   String reasonCode, String finalStatus) {
        JsonObject event = new JsonObject();
        event.addProperty("type",       eventType);
        event.addProperty("run_id",     runId);
        event.addProperty("command_id", commandId);
        if (stepId      != null) event.addProperty("step_id",      stepId);
        if (stepName    != null) event.addProperty("step_name",    stepName);
        if (reasonCode  != null) event.addProperty("reason_code",  reasonCode);
        if (finalStatus != null) event.addProperty("final_status", finalStatus);
        return event;
    }
}
