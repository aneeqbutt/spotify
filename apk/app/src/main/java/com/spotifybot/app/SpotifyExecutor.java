package com.spotifybot.app;

import android.graphics.Path;
import android.graphics.Rect;
import android.accessibilityservice.GestureDescription;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Looper;
import android.os.PowerManager;
import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;
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

    /** True while any executor step chain is in-flight — WS reconnect must defer. */
    private static final AtomicBoolean commandInProgress = new AtomicBoolean(false);

    /** The executor currently running. Set in execute(), cleared in releaseCommandLock(). */
    static volatile SpotifyExecutor currentExecutor;

    public static boolean isCommandInProgress() {
        return commandInProgress.get();
    }

    /** Default step timeout — 15 seconds */
    protected static final long STEP_TIMEOUT_MS = 15_000;

    /** Minimal UI-settle gaps between steps (gestures are async — these are read buffers only). */
    protected static final long GAP_TINY   = 250;
    /** After typing — brief pause before keyboard Search key. */
    protected static final long GAP_KEYBOARD_SEARCH = 100;
    /** Pause after query is typed before IME Enter + keyboard Search are tapped. */
    protected static final long GAP_BEFORE_SEARCH_SUBMIT = 700;
    /** Pause after category filter (Albums, Playlists, etc.) before tapping a result row. */
    protected static final long GAP_BEFORE_CATEGORY_TAP   = 700;
    protected static final long GAP_SHORT  = 450;
    protected static final long GAP_MEDIUM = 600;
    protected static final long GAP_LONG   = 850;

    /** Poll interval while waiting for album/playlist page play button */
    private static final long COLLECTION_PLAY_POLL_MS  = 350;
    /** Max wait for collection play button — 40 × 250ms = 10s */
    private static final int  COLLECTION_PLAY_MAX_POLLS = 40;

    /** Dedicated thread for step timers — immune to main-looper floods from accessibility events */
    private static final HandlerThread STEP_THREAD;
    private static final Handler         stepHandler;
    protected static final Handler       mainHandler = new Handler(Looper.getMainLooper());

    static {
        STEP_THREAD = new HandlerThread("SpotifyExecutorSteps");
        STEP_THREAD.start();
        stepHandler = new Handler(STEP_THREAD.getLooper());
    }

    /** How often to re-check while waiting for an ad/overlay to clear */
    private static final long AD_POLL_INTERVAL_MS = 3_000;
    /** Max polls before giving up — 20 × 3s = 60 seconds */
    private static final int  AD_MAX_POLLS        = 20;

    /** Legacy alias — prefer {@link #scheduleStep(Runnable, long)} for delayed steps */
    protected final Handler           handler = mainHandler;
    protected       BotWebSocketClient ws;
    protected       String            runId;
    protected       String            commandId;

    // ── Timeout watchdog ──────────────────────────────────────────────────────

    /** Currently armed timeout runnable — null when no step is in-flight */
    private Runnable pendingTimeout      = null;
    /** Timer wrapper on stepHandler — needed so cancelStepTimeout() can remove it */
    private Runnable pendingTimeoutTimer = null;

    /**
     * Set to true when the watchdog fires. Prevents a slow-completing step from
     * calling stepOk() + commandDone() AFTER the watchdog already sent STEP_FAILED.
     */
    protected volatile boolean timeoutFired = false;
    /** Set by cancel() — prevents double-cancel and guards the COMMAND_DONE logic. */
    private   volatile boolean cancelled    = false;

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
        pendingTimeoutTimer = () -> mainHandler.post(pendingTimeout);
        stepHandler.postDelayed(pendingTimeoutTimer, timeoutMs);
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
        if (pendingTimeoutTimer != null) {
            stepHandler.removeCallbacks(pendingTimeoutTimer);
            pendingTimeoutTimer = null;
            pendingTimeout = null;
            Log.d(TAG, "[EXEC] Watchdog cancelled");
        }
    }

    /**
     * Schedule a step on the main thread after {@code delayMs}.
     * The delay timer runs on a dedicated thread so accessibility-event floods on the
     * main looper cannot stall executor steps (e.g. playlist-page → Play button).
     */
    protected void scheduleStep(Runnable step, long delayMs) {
        if (delayMs <= 0) {
            mainHandler.post(step);
        } else {
            stepHandler.postDelayed(() -> mainHandler.post(step), delayMs);
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /** Poll interval while waiting for accessibility service to bind. */
    private static final long ACC_BIND_POLL_MS   = 500;
    /** Max wait — 30 × 500ms = 15s (WS may be down during zombie recovery). */
    private static final int  ACC_BIND_MAX_POLLS  = 30;

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
        cancelled      = false;
        currentExecutor = this;
        commandInProgress.set(true);
        stopPlaybackMonitor();
        SpotifyAccessibilityService.disableFirstClickDemo();

        if (SpotifyAccessibilityService.instance == null) {
            Log.w(TAG, "[EXEC] Accessibility not bound — waiting up to "
                    + (ACC_BIND_MAX_POLLS * ACC_BIND_POLL_MS / 1000) + "s");
            scheduleStep(() -> waitForAccessibilityBind(params, 0), ACC_BIND_POLL_MS);
            return;
        }

        doExecute(params);
    }

    /** Poll until SpotifyAccessibilityService.instance is set, then run the command. */
    private void waitForAccessibilityBind(JsonObject params, int poll) {
        if (timeoutFired) return;

        if (SpotifyAccessibilityService.instance != null) {
            Log.i(TAG, "[EXEC] Accessibility bound after "
                    + (poll * ACC_BIND_POLL_MS) + "ms — starting command");
            doExecute(params);
            return;
        }

        if (poll >= ACC_BIND_MAX_POLLS) {
            Log.e(TAG, "[EXEC] Accessibility not bound after "
                    + (poll * ACC_BIND_POLL_MS) + "ms — failing command");
            BotForegroundService.handleAccessibilityDead();
            stepFailed("pre_check", "ACCESSIBILITY_SERVICE_NOT_RUNNING");
            commandDone(false);
            return;
        }

        scheduleStep(() -> waitForAccessibilityBind(params, poll + 1), ACC_BIND_POLL_MS);
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
        boolean alreadyForeground = isSpotifyInForeground();
        Log.i(TAG, "[EXEC] launchAndSettle — spotifyForeground=" + alreadyForeground);

        Runnable startFirstStep = () -> {
            SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
            if (OverlayGuard.isAdOrOverlayBlocking(svc)) {
                dismissOverlays();
            }
            if (OverlayGuard.isAudioAdPlaying(svc)) {
                Log.i(TAG, "[EXEC] Audio ad playing — waiting before starting command");
                waitForAudioAdToFinish(firstStep);
                return;
            }
            Log.i(TAG, "[EXEC] launchAndSettle complete — starting first step");
            firstStep.run();
        };

        if (alreadyForeground) {
            dismissOverlays();
            scheduleStep(startFirstStep, GAP_TINY);
            return;
        }

        ensureScreenOn();
        launchSpotify();
        // Cold start (screen was off / Spotify not foreground) — UI needs longer to render
        scheduleStep(() -> {
            dismissOverlays();
            scheduleStep(startFirstStep, GAP_MEDIUM);
        }, jitteredDelay(1_500));
    }

    /** Wake the screen if it is off so accessibility can read Spotify's UI tree. */
    protected void ensureScreenOn() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return;
        PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        Log.i(TAG, "[EXEC] Screen off — waking for automation");
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "SpotifyBot:ScreenOn");
            wl.acquire(5_000);
            wl.release();
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] ensureScreenOn failed: " + e.getMessage());
        }
    }

    /**
     * Type a query into the search field with up to 3 retries when the input is not found
     * (common when Spotify is still loading after a cold start).
     */
    protected void runTypeQueryStep(String stepId, String stepName, String text, Runnable onDone) {
        runTypeQueryStepAttempt(stepId, stepName, text, onDone, 0, false);
    }

    private void runTypeQueryStepAttempt(String stepId, String stepName, String text,
                                          Runnable onDone, int attempt, boolean started) {
        if (timeoutFired) return;
        if (!started) {
            stepStarted(stepId, stepName);
            scheduleStepTimeout(stepId, STEP_TIMEOUT_MS);
        }

        AccessibilityNodeInfo input = findSearchInput();
        if (input == null) {
            if (attempt < 3) {
                Log.w(TAG, "[EXEC] Search input not found — retry " + (attempt + 1) + "/3");
                cancelStepTimeout();
                scheduleStep(() -> activateSearchBar(
                        () -> runTypeQueryStepAttempt(stepId, stepName, text, onDone,
                                attempt + 1, true)), GAP_MEDIUM);
                return;
            }
            cancelStepTimeout();
            if (!timeoutFired) {
                stepFailed(stepId, "UI_ELEMENT_NOT_FOUND");
                commandDone(false);
            }
            return;
        }

        boolean typed = typeText(input, text);
        input.recycle();
        cancelStepTimeout();
        if (!typed) {
            stepFailed(stepId, "TEXT_INPUT_FAILED");
            commandDone(false);
            return;
        }
        stepOk(stepId, stepName);
        onDone.run();
    }

    /** Reject song rows when searching for albums/playlists. */
    protected boolean looksLikeSongTitle(CharSequence text) {
        if (text == null) return false;
        String t = text.toString().toLowerCase();
        return t.contains("feat.") || t.contains("ft.") || t.contains("(live)")
                || t.contains("remix") || t.contains(" - single");
    }

    /** True when a result row subtitle identifies it as a song. */
    protected boolean hasSongSubtitle(AccessibilityNodeInfo parent) {
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

    /** True when Spotify is already the active foreground app — skip relaunch delay. */
    protected boolean isSpotifyInForeground() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        return pkg != null && SPOTIFY_PACKAGE.equals(pkg.toString());
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
        scheduleStep(
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
        scheduleStep(() -> audioAdPoll(onClear, poll + 1), AUDIO_AD_POLL_MS);
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

    /** Find node by resource-id across all Spotify windows (collection pages may split layers). */
    protected AccessibilityNodeInfo findByIdAllWindows(String viewId) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
                root.recycle();
                if (nodes != null && !nodes.isEmpty()) return nodes.get(0);
            }
        }
        return findById(viewId);
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

    /**
     * Find the first editable node in the active window by BFS traversal.
     *
     * Used in search-related executors as a robust fallback for finding the search
     * EditText when resource-id lookups fail (e.g. Spotify rebranded the id, or the
     * keyboard hasn't fully transferred focus yet).
     *
     * An editable node (isEditable()=true) is definitionally the focused text field
     * once the on-screen keyboard is showing — no resource-id knowledge required.
     *
     * Memory: follows the same no-root-recycle pattern as findById / findByText.
     * The returned node is owned by the caller who must recycle it.
     */
    protected AccessibilityNodeInfo findEditableNode() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        return findEditableRecursive(root);
    }

    private AccessibilityNodeInfo findEditableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findEditableRecursive(child);
            if (found != null) {
                // Recycle intermediate children that are not the match and not the match's
                // direct ancestor (consistent with findTextNodeInSubtree pattern).
                if (found != child) child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
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
     * Find the bottom-nav Search tab.
     * NEVER use bare "Search" — it matches the home-page search bar, keyboard keys, etc.
     */
    protected AccessibilityNodeInfo findSearchTab() {
        AccessibilityNodeInfo tab = findById(SPOTIFY_PACKAGE + ":id/search_tab");
        if (tab != null) return tab;
        tab = findByDesc("Search, Tab");
        if (tab == null) tab = findByDesc("Search tab");
        return tab;
    }

    /**
     * Tap the Search bottom-nav tab via gesture, with coordinate fallback.
     * UIAutoDev S21 FE 1080×2340: bounds [216,2082][432,2214] → center (324, 2148).
     */
    protected boolean tapSearchTab() {
        AccessibilityNodeInfo searchTab = findSearchTab();
        if (searchTab != null) {
            boolean clicked = tapAtNodeRandom(searchTab);
            searchTab.recycle();
            if (clicked) {
                Log.i(TAG, "[EXEC] tapSearchTab: gesture tap on nav tab");
                return true;
            }
        }
        Log.w(TAG, "[EXEC] tapSearchTab: node not found — fallback coords (324,2148)");
        return tapAtCoords(324, 2148);
    }

    /**
     * Tap the search input to open the keyboard, then run onReady after a jittered delay.
     * Shared by all search-based executors.
     */
    protected void activateSearchBar(Runnable onReady) {
        activateSearchBarAttempt(onReady, 0);
    }

    private void activateSearchBarAttempt(Runnable onReady, int attempt) {
        Log.i(TAG, "[EXEC] activateSearchBar — attempt=" + attempt + " timeoutFired=" + timeoutFired);
        if (timeoutFired) return;

        AccessibilityNodeInfo searchBar = findById(SPOTIFY_PACKAGE + ":id/query");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/search_text_field_wrapper");
        if (searchBar == null) searchBar = findById(SPOTIFY_PACKAGE + ":id/query_text_wrapper");
        if (searchBar == null) searchBar = findByDesc("Search for something to listen to");
        if (searchBar == null) searchBar = findByText("What do you want to listen to?");

        if (searchBar != null) {
            tapAtNodeRandom(searchBar);
            searchBar.recycle();
            Log.i(TAG, "[EXEC] Search bar tapped via gesture — waiting for keyboard");
            scheduleStep(onReady, jitteredDelay(GAP_TINY));
            return;
        }

        if (attempt < 4) {
            scheduleStep(() -> activateSearchBarAttempt(onReady, attempt + 1), GAP_TINY);
            return;
        }

        Log.w(TAG, "[EXEC] Search bar node not found — tapping fallback coords (540,165)");
        tapAtCoords(540, 165);
        scheduleStep(onReady, jitteredDelay(GAP_TINY));
    }

    /**
     * Submit the current search query: focus field → IME Enter → keyboard Search key.
     * Uses {@link #findSearchInput()} so editable-node fallbacks match the type step.
     *
     * @param onSubmitted step to run after submit (e.g. tap filter chip) — may be null
     */
    protected void submitSearchQuery(Runnable onSubmitted) {
        if (timeoutFired) return;

        scheduleStep(() -> {
            if (timeoutFired) return;
            AccessibilityNodeInfo input = findSearchInput();
            if (input != null) {
                input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                input.performAction(0x01000000); // ACTION_IME_ENTER
                input.recycle();
                Log.i(TAG, "[EXEC] ACTION_IME_ENTER attempted on search field");
            } else {
                Log.w(TAG, "[EXEC] Search input not found for submit");
            }
            tapKeyboardSearchButton();
        }, GAP_BEFORE_SEARCH_SUBMIT);

        if (onSubmitted != null) {
            scheduleStep(onSubmitted, GAP_BEFORE_SEARCH_SUBMIT + jitteredDelay(GAP_MEDIUM));
        }
    }

    /** True when the search-results filter chip row (filter_compose) is on screen. */
    protected boolean isFilterComposeVisible() {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> containers = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/filter_compose");
        root.recycle();
        if (containers == null || containers.isEmpty()) return false;
        for (AccessibilityNodeInfo n : containers) {
            if (n != null) n.recycle();
        }
        return true;
    }

    /**
     * Find the large Play / Shuffle button on an album or playlist collection page.
     * Avoids broad findByDesc("Play") which false-matches search results and mini-player.
     */
    protected AccessibilityNodeInfo findCollectionPlayButton() {
        String[] ids = {
            SPOTIFY_PACKAGE + ":id/button_play_and_pause",
            SPOTIFY_PACKAGE + ":id/play_button",
            SPOTIFY_PACKAGE + ":id/shuffle_button",
            SPOTIFY_PACKAGE + ":id/btn_play",
            SPOTIFY_PACKAGE + ":id/header_play_pause_btn",
            SPOTIFY_PACKAGE + ":id/collection_play_button",
        };
        for (String id : ids) {
            AccessibilityNodeInfo btn = findByIdAllWindows(id);
            if (btn != null) return btn;
        }
        AccessibilityNodeInfo btn = findByDesc("Shuffle play");
        if (btn == null) btn = findByDesc("Shuffle Play");
        if (btn == null) btn = findByDesc("Play album");
        if (btn == null) btn = findByDesc("Play playlist");
        return btn;
    }

    /**
     * After tapping a playlist/album row, poll until the collection-page play button appears.
     * Replaces a blind humanDelay — taps Play as soon as the page is ready.
     */
    protected void waitForCollectionPlayButton(Runnable onReady) {
        waitForCollectionPlayButtonPoll(onReady, 0);
    }

    private void waitForCollectionPlayButtonPoll(Runnable onReady, int poll) {
        if (timeoutFired) return;

        AccessibilityNodeInfo btn = findCollectionPlayButton();
        if (btn != null) {
            btn.recycle();
            Log.i(TAG, "[EXEC] Collection play button ready after "
                    + (poll * COLLECTION_PLAY_POLL_MS) + "ms");
            onReady.run();
            return;
        }

        if (poll >= COLLECTION_PLAY_MAX_POLLS) {
            Log.w(TAG, "[EXEC] Collection play button not found after "
                    + (COLLECTION_PLAY_MAX_POLLS * COLLECTION_PLAY_POLL_MS)
                    + "ms — proceeding anyway");
            onReady.run();
            return;
        }

        scheduleStep(() -> waitForCollectionPlayButtonPoll(onReady, poll + 1),
                COLLECTION_PLAY_POLL_MS);
    }

    /**
     * Find the search EditText using ID → editable-node → placeholder strategies.
     * Caller must recycle the returned node.
     */
    protected AccessibilityNodeInfo findSearchInput() {
        AccessibilityNodeInfo input = findById(SPOTIFY_PACKAGE + ":id/query");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_edittext");
        if (input == null) input = findById(SPOTIFY_PACKAGE + ":id/search_query");
        if (input == null) {
            input = findEditableNode();
            if (input != null) Log.i(TAG, "[EXEC] findSearchInput: found via findEditableNode");
        }
        if (input == null) input = findByText("What do you want to listen to?");
        return input;
    }

    /**
     * Find the label TextView of a filter chip inside the filter_compose ComposeView.
     *
     * UIAutoDev confirmed: the entire chip row is ONE filter_compose container.
     * Each chip ("Songs", "Albums", etc.) is a nested TextView — findByText("Songs")
     * alone matches wrong nodes; we must search inside filter_compose only.
     * Caller must recycle the returned node.
     */
    protected AccessibilityNodeInfo findChipLabelNode(String label) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return null;
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> containers = root.findAccessibilityNodeInfosByViewId(
                SPOTIFY_PACKAGE + ":id/filter_compose");
        root.recycle();

        if (containers == null || containers.isEmpty()) {
            Log.w(TAG, "[EXEC] findChipLabelNode: no filter_compose on screen");
            return null;
        }

        AccessibilityNodeInfo container = containers.get(0);
        for (int i = 1; i < containers.size(); i++) {
            if (containers.get(i) != null) containers.get(i).recycle();
        }

        AccessibilityNodeInfo result = findTextNodeInSubtree(container, label);
        container.recycle();

        if (result != null) {
            Log.i(TAG, "[EXEC] findChipLabelNode: found '" + label + "'");
        } else {
            Log.w(TAG, "[EXEC] findChipLabelNode: '" + label + "' not in filter_compose");
        }
        return result;
    }

    /**
     * Depth-first search for the first node whose text equals label (case-insensitive).
     * Returns the matched node — caller must recycle it.
     */
    protected AccessibilityNodeInfo findTextNodeInSubtree(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.toString().equalsIgnoreCase(label)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findTextNodeInSubtree(child, label);
            if (found != null) {
                if (found != child) child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    /**
     * Find the innermost chip container wrapping a filter label TextView.
     * Compose chips expose a generic android.view.View wrapper around the label;
     * tapping the label center can miss the hit target and scroll the LazyRow.
     * Caller must recycle the returned node.
     */
    protected AccessibilityNodeInfo findChipContainer(AccessibilityNodeInfo labelNode) {
        if (labelNode == null) return null;
        Rect labelBounds = new Rect();
        labelNode.getBoundsInScreen(labelBounds);

        AccessibilityNodeInfo best = null;
        int bestWidth = Integer.MAX_VALUE;
        AccessibilityNodeInfo cur = labelNode;

        for (int i = 0; i < 8; i++) {
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != labelNode) cur.recycle();
            if (parent == null) break;

            String id = parent.getViewIdResourceName();
            if (id != null && id.endsWith(":id/filter_compose")) {
                parent.recycle();
                break;
            }

            Rect pb = new Rect();
            parent.getBoundsInScreen(pb);
            if (pb.contains(labelBounds) && pb.width() >= labelBounds.width()
                    && pb.width() < 600 && pb.height() >= 40 && pb.height() <= 160) {
                if (pb.width() < bestWidth) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(parent);
                    bestWidth = pb.width();
                }
            }
            cur = parent;
        }

        if (best != null) return best;
        return AccessibilityNodeInfo.obtain(labelNode);
    }

    /**
     * Scroll the filter chip row horizontally until the chip is fully on screen.
     * Returns false when a scroll gesture was dispatched — caller should retry after a delay.
     */
    protected boolean ensureFilterChipVisible(String label) {
        AccessibilityNodeInfo labelNode = findChipLabelNode(label);
        if (labelNode == null) return true;

        AccessibilityNodeInfo chip = findChipContainer(labelNode);
        labelNode.recycle();
        Rect chipBounds = new Rect();
        chip.getBoundsInScreen(chipBounds);
        chip.recycle();

        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        int screenW = svc != null
                ? svc.getResources().getDisplayMetrics().widthPixels : 1080;
        int margin = 40;
        if (chipBounds.left >= margin && chipBounds.right <= screenW - margin) {
            return true;
        }

        AccessibilityNodeInfo compose = findById(SPOTIFY_PACKAGE + ":id/filter_compose");
        if (compose == null) return true;
        Rect rowBounds = new Rect();
        compose.getBoundsInScreen(rowBounds);
        compose.recycle();

        float y = rowBounds.centerY();
        float startX;
        float endX;
        if (chipBounds.left < margin) {
            // Reveal chips on the left — finger moves right
            startX = rowBounds.left + rowBounds.width() * 0.25f;
            endX   = rowBounds.left + rowBounds.width() * 0.75f;
            Log.i(TAG, "[EXEC] ensureFilterChipVisible '" + label
                    + "': scrolling row right (chip clipped left)");
        } else {
            // Reveal chips on the right — finger moves left
            startX = rowBounds.left + rowBounds.width() * 0.75f;
            endX   = rowBounds.left + rowBounds.width() * 0.25f;
            Log.i(TAG, "[EXEC] ensureFilterChipVisible '" + label
                    + "': scrolling row left (chip clipped right)");
        }
        swipeHorizontal(startX, y, endX, y);
        return false;
    }

    /**
     * Tap a filter chip inside filter_compose using the chip container bounds.
     * Tries ACTION_CLICK on ancestors first; falls back to a single gesture at
     * the container center (not the label TextView, which can be offset).
     */
    protected boolean tapFilterChipByLabel(String label) {
        AccessibilityNodeInfo labelNode = findChipLabelNode(label);
        if (labelNode == null) return false;

        AccessibilityNodeInfo chip = findChipContainer(labelNode);
        if (chip != labelNode) labelNode.recycle();

        AccessibilityNodeInfo cur = chip;
        for (int level = 0; level < 6; level++) {
            if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "[EXEC] tapFilterChip '" + label + "': ACTION_CLICK level=" + level);
                if (cur != chip) cur.recycle();
                chip.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != chip) cur.recycle();
            if (parent == null) break;

            String id = parent.getViewIdResourceName();
            if (id != null && id.endsWith(":id/filter_compose")) {
                parent.recycle();
                break;
            }
            cur = parent;
        }

        Rect b = new Rect();
        chip.getBoundsInScreen(b);
        chip.recycle();
        if (b.isEmpty()) return false;

        int tapX = b.centerX();
        int tapY = b.centerY();
        Log.i(TAG, "[EXEC] tapFilterChip '" + label + "' container bounds=" + b
                + " tap=(" + tapX + "," + tapY + ")");
        return tapAtCoords(tapX, tapY);
    }

    /**
     * Tap a search-results filter chip by label inside filter_compose.
     * Uses exact screen bounds from the label TextView (Compose chips are not clickable).
     *
     * @param fallbackX/Y UIAutoDev-confirmed center coords when the node is not found
     */
    /** True when the chip label or its parent reports isSelected(). */
    protected boolean isFilterChipSelected(AccessibilityNodeInfo chip) {
        if (chip == null) return false;
        if (chip.isSelected()) return true;
        AccessibilityNodeInfo parent = chip.getParent();
        if (parent != null) {
            boolean selected = parent.isSelected();
            parent.recycle();
            return selected;
        }
        return false;
    }

    protected boolean tapFilterChip(String label, float fallbackX, float fallbackY) {
        if (tapFilterChipByLabel(label)) return true;
        Log.w(TAG, "[EXEC] tapFilterChip '" + label + "' — fallback (" + (int) fallbackX + ","
                + (int) fallbackY + ")");
        return tapAtCoords(fallbackX, fallbackY);
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
     * Scroll the search results list down by one page.
     *
     * Primary: ACTION_SCROLL_FORWARD on the search RecyclerView.
     * Fallback: swipe-up gesture across the results area (y 1800→950).
     */
    protected void scrollResultsList() {
        // Primary: RecyclerView scroll action
        AccessibilityNodeInfo recycler = findById(SPOTIFY_PACKAGE + ":id/search_content_recyclerview");
        if (recycler == null) recycler = findById(SPOTIFY_PACKAGE + ":id/search_content_recycler_view");
        if (recycler != null) {
            boolean scrolled = recycler.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            recycler.recycle();
            Log.i(TAG, "[EXEC] scrollResultsList: ACTION_SCROLL_FORWARD dispatched=" + scrolled);
            if (scrolled) return;
        }
        // Fallback: swipe up gesture — results area sits between filter bar (~855) and nav bar (~2100)
        boolean swiped = swipeUp(540, 1800, 540, 950);
        Log.i(TAG, "[EXEC] scrollResultsList: swipe fallback dispatched=" + swiped);
    }

    /**
     * Dispatch a swipe-up gesture (finger moves from fromY to toY, scrolling content downward).
     * x stays fixed; duration 350ms mimics a natural scroll.
     */
    protected boolean swipeUp(float x, float fromY, float toX, float toY) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        try {
            Path path = new Path();
            path.moveTo(x, fromY);
            path.lineTo(toX, toY);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 350))
                    .build();
            return svc.dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] swipeUp error: " + e.getMessage());
            return false;
        }
    }

    /** Horizontal swipe on the filter chip row (or any horizontal scroller). */
    protected boolean swipeHorizontal(float fromX, float y, float toX, float toY) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        try {
            Path path = new Path();
            path.moveTo(fromX, y);
            path.lineTo(toX, toY);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 300))
                    .build();
            boolean dispatched = svc.dispatchGesture(gesture, null, null);
            Log.d(TAG, "[EXEC] swipeHorizontal: dispatched=" + dispatched
                    + " (" + (int) fromX + "," + (int) y + ")→(" + (int) toX + "," + (int) toY + ")");
            return dispatched;
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] swipeHorizontal error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Walk up the node tree to {@code id/row_root} — the clickable search-result row.
     * Returns the original node if row_root is not found. Caller owns the return value.
     */
    protected AccessibilityNodeInfo findRowRoot(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            String id = cur.getViewIdResourceName();
            if (id != null && id.endsWith(":id/row_root")) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent != cur && cur != node) cur.recycle();
            cur = parent;
        }
        return node;
    }

    /**
     * Open a search-result row with a single tap — avoids double-tap select/deselect.
     * Uses ACTION_CLICK on row_root first; one gesture fallback on the title column
     * (right of artwork) if needed. Never combines gesture + performAction.
     */
    protected boolean tapSearchResultRow(AccessibilityNodeInfo row) {
        if (row == null) return false;

        AccessibilityNodeInfo rowRoot = findRowRoot(row);
        if (rowRoot != row) row.recycle();

        if (rowRoot.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "[EXEC] tapSearchResultRow: ACTION_CLICK on row_root");
            rowRoot.recycle();
            return true;
        }

        Rect b = new Rect();
        rowRoot.getBoundsInScreen(b);
        rowRoot.recycle();
        if (b.isEmpty()) return false;

        // Artwork is the left ~40% — tap the title column to open, not toggle selection
        int tapX = (int) (b.left + b.width() * 0.62f);
        int tapY = b.centerY();
        Log.i(TAG, "[EXEC] tapSearchResultRow: single gesture at title column ("
                + tapX + "," + tapY + ")");
        return tapAtCoords(tapX, tapY);
    }

    /**
     * Dispatch a tap gesture to raw screen coordinates.
     *
     * Used when the target is in a different window/package than the active app
     * (e.g. the Samsung keyboard).  dispatchGesture fires at screen coordinates
     * regardless of which window owns that area.
     */
    protected boolean tapAtCoords(float x, float y) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return false;
        long tapDuration = 80 + ThreadLocalRandom.current().nextInt(120);
        Path path = new Path();
        path.moveTo(x, y);
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, tapDuration))
                    .build();
            boolean dispatched = svc.dispatchGesture(gesture, null, null);
            Log.d(TAG, "[EXEC] tapAtCoords: dispatched=" + dispatched
                    + " coords=(" + (int) x + "," + (int) y + ")");
            return dispatched;
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] tapAtCoords error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Tap the keyboard's search / action key by:
     *   1. Finding the TYPE_INPUT_METHOD window via getWindows()
     *   2. Scanning the keyboard's view tree for a node with content-desc = "Search"
     *      (UIAutoDev confirmed: Samsung keyboard search key has exactly this desc)
     *   3. Dispatching a gesture at that node's center coordinates
     *
     * Why content-desc search instead of hardcoded coordinates:
     *   UIAutoDev confirmed the exact node:
     *     package = com.samsung.android.honeyboard
     *     content-desc = "Search"
     *     bounds = [916,2061][1057,2177]  → center (987, 2119)
     *   The bounds vary slightly with keyboard height/mode, but content-desc = "Search"
     *   is stable — finding the node directly gives the exact tap coordinates every time.
     *
     * Fallback: if no "Search" node is found in the keyboard tree (Gboard, SwiftKey, etc.),
     *   falls back to tapping at 8.6% from the right edge and 5% from the bottom of the
     *   keyboard window — a proportional estimate that covers most layouts.
     *
     * If the keyboard was already dismissed (ACTION_IME_ENTER succeeded), no
     * TYPE_INPUT_METHOD window is visible and this returns as a safe no-op.
     */
    protected void tapKeyboardSearchButton() {
        // ── Tier 1: node-based tap ─────────────────────────────────────────────
        // Requires flagRetrieveInteractiveWindows in accessibility_service_config.xml.
        // Iterate ALL windows; for each window that looks like the Samsung keyboard
        // (TYPE_INPUT_METHOD or package contains "honeyboard"), BFS-search the tree
        // for the node with content-desc = "Search".
        //
        // UIAutoDev confirmed (Samsung S21 FE, 1080×2340):
        //   resource-id  : com.samsung.android.honeyboard:id/keyboard_touch_layer
        //   content-desc : "Search"
        //   bounds       : [916,2061][1057,2177]  → center (987, 2119)
        //
        // ── Tier 2: proportional coords from keyboard window rect ──────────────
        // Falls back when the node tree is inaccessible (Samsung keyboard may
        // restrict root access in some ROM versions).
        //
        // ── Tier 3: hardcoded confirmed center ────────────────────────────────
        // If no keyboard window found at all (flagRetrieveInteractiveWindows not
        // yet in effect, or service not restarted), use the UIAutoDev-confirmed
        // center (987, 2119) directly.  Reliable on this device.

        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;
        if (svc == null) return;

        // ── Tier 1 ────────────────────────────────────────────────────────────
        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows != null) {
                Rect kbWindowBounds = null;   // saved for tier-2 fallback
                boolean tapped = false;

                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;

                    boolean isKeyboardWindow =
                            w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD;

                    // Also accept windows whose root package contains "honeyboard"
                    // in case Samsung keyboard uses a non-standard window type.
                    if (!isKeyboardWindow) {
                        AccessibilityNodeInfo peek = w.getRoot();
                        if (peek != null) {
                            CharSequence pkg = peek.getPackageName();
                            if (pkg != null && pkg.toString().contains("honeyboard"))
                                isKeyboardWindow = true;
                            peek.recycle();
                        }
                    }

                    if (isKeyboardWindow && !tapped) {
                        // Save window bounds for tier-2 fallback
                        Rect wb = new Rect();
                        w.getBoundsInScreen(wb);
                        if (!wb.isEmpty()) kbWindowBounds = wb;

                        AccessibilityNodeInfo kbRoot = w.getRoot();
                        if (kbRoot != null) {
                            AccessibilityNodeInfo searchKey = findKeyboardKey(kbRoot, "Search");
                            kbRoot.recycle();
                            if (searchKey != null) {
                                Rect b = new Rect();
                                searchKey.getBoundsInScreen(b);
                                tapped = tapAtCoords(b.centerX(), b.centerY());
                                searchKey.recycle();
                                Log.i(TAG, "[EXEC] tapKeyboardSearchButton [tier1] content-desc='Search'"
                                        + " bounds=" + b.toShortString()
                                        + " center=(" + b.centerX() + "," + b.centerY() + ")"
                                        + " dispatched=" + tapped);
                            } else {
                                Log.w(TAG, "[EXEC] tapKeyboardSearchButton [tier1] 'Search' node not found in tree");
                            }
                        } else {
                            Log.w(TAG, "[EXEC] tapKeyboardSearchButton [tier1] kbRoot=null (keyboard restricts tree access)");
                        }
                    }
                    w.recycle();
                }

                if (tapped) return;

                // ── Tier 2 ────────────────────────────────────────────────────
                if (kbWindowBounds != null) {
                    // Button is near bottom-right; 8.6% from right, 5% from bottom
                    float tapX = kbWindowBounds.right  - kbWindowBounds.width()  * 0.086f;
                    float tapY = kbWindowBounds.bottom - kbWindowBounds.height() * 0.05f;
                    boolean t2 = tapAtCoords(tapX, tapY);
                    Log.i(TAG, "[EXEC] tapKeyboardSearchButton [tier2] proportional coords"
                            + " kb=" + kbWindowBounds.toShortString()
                            + " tap=(" + (int) tapX + "," + (int) tapY + ")"
                            + " dispatched=" + t2);
                    if (t2) return;
                }

                if (kbWindowBounds != null) return; // keyboard found but taps failed — don't guess
            }
        } catch (Exception e) {
            Log.w(TAG, "[EXEC] tapKeyboardSearchButton tier1/2 error: " + e.getMessage());
        }

        // ── Tier 3 ────────────────────────────────────────────────────────────
        // getWindows() returned nothing useful (flagRetrieveInteractiveWindows not
        // yet active until service is re-enabled after APK install, or no keyboard
        // window visible).  Use UIAutoDev-confirmed center from bounds [916,2061][1057,2177].
        boolean t3 = tapAtCoords(987, 2119);
        Log.i(TAG, "[EXEC] tapKeyboardSearchButton [tier3] hardcoded (987,2119) dispatched=" + t3);
    }

    /**
     * BFS scan of a keyboard window tree looking for the first node whose
     * content-description exactly matches desc and is enabled.
     *
     * Uses BFS (queue) rather than recursion so memory management is simple:
     * every dequeued node is either returned (caller recycles) or recycled here.
     * Remaining queue nodes are recycled when the match is found early.
     *
     * Returns the matching node (caller must call recycle()) or null.
     */
    private AccessibilityNodeInfo findKeyboardKey(AccessibilityNodeInfo root, String desc) {
        if (root == null) return null;
        // Check root first (no queue allocation if root itself matches)
        CharSequence rootCd = root.getContentDescription();
        if (rootCd != null && desc.equals(rootCd.toString()) && root.isEnabled()) {
            return root;
        }
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) queue.add(child);
        }
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            CharSequence cd = node.getContentDescription();
            if (cd != null && desc.equals(cd.toString()) && node.isEnabled()) {
                // Recycle all remaining queued nodes before returning
                for (AccessibilityNodeInfo rem : queue) rem.recycle();
                return node; // caller recycles this one
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            node.recycle();
        }
        return null;
    }

    /**
     * Add ±10% random jitter to a base delay.
     *
     * Usage:  handler.postDelayed(this::nextStep, jitteredDelay(2_000));
     * Result: delay between 1300–2700ms instead of a machine-perfect 2000ms.
     *
     * Applied to every postDelayed call in executors so step-to-step timing
     * is never constant across repeated runs.
     */
    protected long jitteredDelay(long baseMs) {
        if (baseMs <= 0) return 0;
        long jitter = Math.max(15, (long) (baseMs * 0.10));
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
     * Schedule the next step with a short randomized delay (500–900 ms).
     */
    protected void humanDelay(Runnable next) {
        humanDelay(650, 1_000, next);
    }

    /**
     * Schedule the next step with a random delay between minMs and maxMs.
     */
    protected void humanDelay(long minMs, long maxMs, Runnable next) {
        long delay = minMs + ThreadLocalRandom.current().nextInt((int)(maxMs - minMs));
        Log.d(TAG, "[EXEC] Human delay: " + delay + "ms");
        scheduleStep(next, delay);
    }

    /**
     * Type text into a focused input field using ACTION_SET_TEXT.
     * More reliable than simulating key events — works with Spotify's search input.
     */
    protected boolean typeText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        // Clear stale text from a previous search before setting the new query.
        Bundle clear = new Bundle();
        clear.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        Log.i(TAG, "[EXEC] typeText: set '" + text + "' ok=" + ok);
        return ok;
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

    /** Stop seekbar/event monitor from a previous run — must run before any new command. */
    public static void stopPlaybackMonitor() {
        if (activeMonitorRunId != null) {
            Log.i(TAG, "[MONITOR] Stopped — new command starting (was run=" + activeMonitorRunId + ")");
            activeMonitorRunId      = null;
            activeMonitorTrackTitle = null;
            activeMonitorStartMs    = 0;
        }
    }

    /** True when Spotify is showing search results (filter chips or query field visible). */
    protected boolean isOnSearchResultsScreen() {
        if (isFilterComposeVisible()) return true;
        AccessibilityNodeInfo input = findSearchInput();
        if (input != null) {
            input.recycle();
            return true;
        }
        return false;
    }

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
        if (commandInProgress.get()) {
            Log.d(TAG, "[MONITOR] Track change ignored — command in progress");
            return;
        }

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
        startPlaybackMonitorAttempt(monitorRunId, 0);
    }

    /**
     * Internal retry helper for startPlaybackMonitor.
     *
     * Two preconditions must be satisfied before the baseline title is captured:
     *
     *   1. No audio ad is currently playing.
     *      If an ad is active when commandDone(true) fires (e.g. the previous song ended
     *      mid-session and an ad started before the executor returned), capturing its title
     *      as the baseline would cause checkPlaybackTrackChange() to fire PLAYBACK_FINISHED
     *      the instant the ad ends and the next song title appears — far too early.
     *      We wait up to 5 × 3 s = 15 s for the ad to clear.
     *
     *   2. readCurrentTrackTitle() returns a non-null value.
     *      Spotify's Now Playing bar can take 1-3 s to update after a song starts.
     *      A null baseline makes checkPlaybackTrackChange() skip every check
     *      (line: if (expectedTitle == null) return;), leaving the event-driven path blind
     *      until the seekbar-poll backup catches up.
     *      We retry up to 3 × 2 s = 6 s for the title to settle.
     *
     * @param monitorRunId  run_id echoed in PLAYBACK_FINISHED.
     * @param attempt       retry counter — used to cap both retry loops.
     */
    private void startPlaybackMonitorAttempt(String monitorRunId, int attempt) {
        SpotifyAccessibilityService svc = SpotifyAccessibilityService.instance;

        // Precondition 1: wait for any active audio ad to clear (max 5 retries × 3 s)
        if (attempt < 5 && svc != null && OverlayGuard.isAudioAdPlaying(svc)) {
            Log.i(TAG, "[MONITOR] Ad active at monitor start (attempt=" + attempt
                    + ") — deferring 3 s, run=" + monitorRunId);
            scheduleStep(
                    () -> startPlaybackMonitorAttempt(monitorRunId, attempt + 1),
                    3_000);
            return;
        }

        // Precondition 2: wait for a non-null track title (max 3 retries × 2 s)
        String initialTitle = readCurrentTrackTitle();
        if (initialTitle == null && attempt < 3) {
            Log.i(TAG, "[MONITOR] Track title null at monitor start (attempt=" + attempt
                    + ") — deferring 2 s, run=" + monitorRunId);
            scheduleStep(
                    () -> startPlaybackMonitorAttempt(monitorRunId, attempt + 1),
                    2_000);
            return;
        }

        // Strategy 1: register for event-driven detection.
        // SpotifyAccessibilityService will call onTrackChangedByEvent() the instant
        // Spotify's now-playing title changes — no postDelayed required.
        activeMonitorRunId      = monitorRunId;
        activeMonitorTrackTitle = initialTitle;
        activeMonitorStartMs    = System.currentTimeMillis();
        Log.i(TAG, "[MONITOR] Playback monitor started run=" + monitorRunId
                + " initial_title='" + initialTitle + "' attempt=" + attempt
                + " (event-driven + seekbar poll, stabilization=10s)");

        // Strategy 2: seekbar poll backup (fires if event-driven misses the change)
        long startMs = System.currentTimeMillis();
        scheduleStep(
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
        if (commandInProgress.get()) {
            Log.i(TAG, "[MONITOR] poll halted — automation command in progress");
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

        // ── Pause watchdog ────────────────────────────────────────────────────
        // If Spotify is paused mid-session (accidental button press, headphone
        // disconnect, notification tap, Bluetooth handoff, etc.) the seekbar
        // stops advancing and PLAYBACK_FINISHED never fires — the session
        // scheduler hangs in _wait_for_playback_finish() for the full timeout.
        //
        // Every 5s poll: detect the paused state and silently tap Play to resume.
        // Skip this check while an audio ad is playing — ads can look "paused"
        // to the UI tree but are not, and tapping Play during an ad skips it
        // or opens a premium upsell.
        SpotifyAccessibilityService pauseSvc = SpotifyAccessibilityService.instance;
        if (!OverlayGuard.isAudioAdPlaying(pauseSvc) && isPlaybackPaused()) {
            Log.i(TAG, "[MONITOR] Playback paused detected — auto-resuming run=" + monitorRunId);
            resumePlayback();
            // Reschedule next poll; skip seekbar read this cycle (position is stale while paused)
            scheduleStep(
                    () -> pollPlaybackPosition(monitorRunId, startMs, notFoundCount),
                    PLAYBACK_POLL_INTERVAL_MS);
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
                scheduleStep(
                        () -> pollPlaybackPosition(monitorRunId, startMs, notFoundCount + 1),
                        2_000);
                return;
            }
            scheduleStep(
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
            scheduleStep(
                    () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                    PLAYBACK_POLL_INTERVAL_MS);
            return;
        }

        // Guard 2: don't fire in the first 30s of monitoring — prevents false positives
        // if the monitor started near the tail end of a seekbar (e.g. a residual UI state)
        long monitorAgeMs = System.currentTimeMillis() - startMs;
        if (monitorAgeMs < MIN_MONITOR_AGE_MS) {
            Log.d(TAG, "[MONITOR] Too early to fire (" + (monitorAgeMs/1000) + "s into monitoring)");
            scheduleStep(
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
        scheduleStep(
                () -> pollPlaybackPosition(monitorRunId, startMs, 0),
                PLAYBACK_POLL_INTERVAL_MS);
    }

    /**
     * Returns true if Spotify is currently paused (not playing).
     *
     * Detection strategy:
     *   The play/pause button in the full Now Playing screen and in the mini-player
     *   changes its content-description depending on state:
     *     - Playing  → content-desc contains "Pause"
     *     - Paused   → content-desc contains "Play"
     *
     *   We check the full-screen button first (most reliable when the player is open),
     *   then fall back to the mini-player button.
     *
     *   NOTE: this is intentionally a lightweight read — no tree traversal, just
     *   findById() on a known resource-id.  It runs every 5s inside the poll loop
     *   so it must be cheap.
     */
    private boolean isPlaybackPaused() {
        // Full Now Playing screen play/pause button
        // content-desc = "Play" or "Play song" when paused; "Pause" when playing
        AccessibilityNodeInfo btn =
                findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (btn != null) {
            CharSequence cd = btn.getContentDescription();
            btn.recycle();
            if (cd != null) {
                String desc = cd.toString();
                // "Pause" → still playing, "Play…" → paused
                return desc.startsWith("Play");
            }
        }

        // Mini-player play/pause button (when full screen is closed)
        AccessibilityNodeInfo mini = findById(SPOTIFY_PACKAGE + ":id/play_pause_button");
        if (mini != null) {
            CharSequence cd = mini.getContentDescription();
            mini.recycle();
            if (cd != null) return cd.toString().startsWith("Play");
        }

        // findByDesc broad fallback — "Play song" content-desc visible on paused state
        AccessibilityNodeInfo broad = findByDesc("Play song");
        if (broad != null) { broad.recycle(); return true; }

        return false;  // could not determine — assume playing
    }

    /**
     * Taps the Spotify Play button to resume paused playback.
     *
     * Tries the full Now Playing screen button first, then the mini-player,
     * then a broad content-desc search.  Uses tapAtNodeRandom() (coordinate
     * jitter + random press duration) to match the executor pattern and avoid
     * machine-perfect taps that bot detection might flag.
     */
    private void resumePlayback() {
        AccessibilityNodeInfo btn =
                findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
        if (btn == null) btn = findByDesc("Play song");
        if (btn == null) btn = findById(SPOTIFY_PACKAGE + ":id/play_pause_button");
        if (btn == null) btn = findByDesc("Play");

        if (btn != null) {
            boolean tapped = tapAtNodeRandom(btn);
            btn.recycle();
            Log.i(TAG, "[MONITOR] resumePlayback: tapped=" + tapped);
        } else {
            Log.w(TAG, "[MONITOR] resumePlayback: Play button not found in UI tree");
        }
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
        if (commandInProgress.get()) {
            Log.i(TAG, "[MONITOR] skip expand mini-player — command in progress");
            return;
        }
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

    // ── Like / Skip choreography (PLAYBACK actions) ───────────────────────────
    //
    // PLAY_FROM_ALBUM / PLAY_FROM_PLAYLIST / SEARCH_AND_PLAY tasks can carry
    // "likes" and "skips" counts (set in the dashboard "Songs to like / skip"
    // fields). After playback is confirmed, runLikeSkipChoreography() spreads those
    // like/skip actions across the playing collection with randomized human delays,
    // then calls onComplete (which fires commandDone + startPlaybackMonitor).
    //
    // Constraints baked in here:
    //   • Runs while commandInProgress=true with NO step watchdog armed — the spread
    //     gaps (7-13s) would otherwise trip the 15s STEP_TIMEOUT and fail the command.
    //   • Must finish well within the 180s command TTL, so the total number of
    //     actions is capped (MAX_CHOREO_ACTIONS) and gaps kept moderate. A typical
    //     3-like / 1-skip run completes in ~60-80s including the play setup.
    //   • To like DISTINCT songs the bot taps Next between likes. Those advances
    //     reuse the skip budget first; if more likes remain after skips run out it
    //     adds extra Next presses (logged as step_advance_*) so each like lands on a
    //     new track. Net result: N liked songs and at least M skips.

    protected static final long CHOREO_GAP_MIN_MS = 7_000;
    protected static final long CHOREO_GAP_MAX_MS = 13_000;
    /** Hard cap on total like+skip+advance actions — protects the command TTL. */
    private static final int    MAX_CHOREO_ACTIONS = 9;

    private java.util.List<String> choreoPlan;     // entries: "LIKE" / "SKIP" / "ADVANCE"
    private int                    choreoIndex;
    private Runnable               choreoOnComplete;

    /**
     * Spread {@code likes} like actions and {@code skips} skip actions across the
     * currently playing collection, then run {@code onComplete}. When both counts
     * are 0 this is a no-op that calls onComplete immediately.
     *
     * Call this AFTER the play step succeeds and AFTER cancelStepTimeout() — it must
     * run with no watchdog armed.
     */
    protected void runLikeSkipChoreography(int likes, int skips, Runnable onComplete) {
        likes = Math.max(0, likes);
        skips = Math.max(0, skips);
        if (likes == 0 && skips == 0) {
            onComplete.run();
            return;
        }
        choreoPlan       = buildChoreoPlan(likes, skips);
        choreoIndex      = 0;
        choreoOnComplete = onComplete;
        Log.i(TAG, "[CHOREO] Plan (likes=" + likes + " skips=" + skips + "): " + choreoPlan);
        // Let the first song actually play for a few seconds before touching the UI.
        scheduleStep(this::runNextChoreoAction, jitteredDelay(CHOREO_GAP_MIN_MS));
    }

    /** Interleave likes and skips; insert Next "advance" steps so likes hit new songs. */
    private java.util.List<String> buildChoreoPlan(int likes, int skips) {
        java.util.List<String> plan = new java.util.ArrayList<>();
        int likesLeft = likes;
        int skipsLeft = skips;
        while ((likesLeft > 0 || skipsLeft > 0) && plan.size() < MAX_CHOREO_ACTIONS) {
            if (likesLeft > 0) { plan.add("LIKE"); likesLeft--; }
            if (plan.size() >= MAX_CHOREO_ACTIONS) break;
            if (skipsLeft > 0) {
                plan.add("SKIP"); skipsLeft--;
            } else if (likesLeft > 0) {
                plan.add("ADVANCE");   // move to a new song so the next like is distinct
            }
        }
        return plan;
    }

    private void runNextChoreoAction() {
        if (timeoutFired) return;

        if (choreoPlan == null || choreoIndex >= choreoPlan.size()) {
            Log.i(TAG, "[CHOREO] Complete — " + choreoIndex + " actions performed");
            Runnable done = choreoOnComplete;
            choreoOnComplete = null;
            choreoPlan       = null;
            if (done != null) done.run();
            return;
        }

        String action = choreoPlan.get(choreoIndex);
        int    n      = choreoIndex + 1;
        choreoIndex++;

        Runnable next = () -> {
            long delay = (choreoPlan != null && choreoIndex < choreoPlan.size())
                    ? humanChoreoGap() : GAP_SHORT;
            scheduleStep(this::runNextChoreoAction, delay);
        };

        switch (action) {
            case "LIKE":
                likeCurrentTrack("step_like_" + n, next);
                break;
            case "ADVANCE":
                // Reported as an advance — it taps Next purely to reach a new song.
                skipCurrentTrack("step_advance_" + n, next);
                break;
            case "SKIP":
            default:
                skipCurrentTrack("step_skip_" + n, next);
                break;
        }
    }

    private long humanChoreoGap() {
        return CHOREO_GAP_MIN_MS
                + ThreadLocalRandom.current().nextLong(CHOREO_GAP_MAX_MS - CHOREO_GAP_MIN_MS + 1);
    }

    /** Like the currently playing track inline (no commandDone). Soft-succeeds if the
     *  button can't be found — a missing like must never fail the whole play command. */
    private void likeCurrentTrack(String stepId, Runnable onDone) {
        stepStarted(stepId, "Like Song");
        attemptLike(stepId, onDone, false);
    }

    private void attemptLike(String stepId, Runnable onDone, boolean openedPlayer) {
        if (timeoutFired) return;

        // Always navigate to the full player first. The album/playlist detail page has
        // its own "Add item" heart that saves the whole collection — not the current track.
        // Opening the full player guarantees the like button targets the playing song.
        if (!openedPlayer) {
            if (openFullPlayer()) {
                scheduleStep(() -> attemptLike(stepId, onDone, true), GAP_LONG);
            } else {
                Log.w(TAG, "[CHOREO] " + stepId + " — could not open full player, soft-skip");
                stepOk(stepId, "Like Song");
                onDone.run();
            }
            return;
        }

        // Already liked → idempotent success.
        AccessibilityNodeInfo already = findByDesc("Remove item");
        if (already != null) {
            already.recycle();
            Log.i(TAG, "[CHOREO] " + stepId + " — already liked (idempotent)");
            stepOk(stepId, "Like Song");
            onDone.run();
            return;
        }

        AccessibilityNodeInfo likeBtn = findByDesc("Add item");
        if (likeBtn == null) likeBtn = findById(SPOTIFY_PACKAGE + ":id/add_to_button");

        if (likeBtn == null) {
            Log.w(TAG, "[CHOREO] " + stepId + " — like button not found on full player, soft-skip");
            stepOk(stepId, "Like Song");
            onDone.run();
            return;
        }

        boolean clicked = likeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) {
            AccessibilityNodeInfo parent = likeBtn.getParent();
            if (parent != null) {
                clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
            }
        }
        likeBtn.recycle();
        Log.i(TAG, "[CHOREO] " + stepId + " — like tapped=" + clicked);

        // Some Spotify builds show a "Liked Songs" bottom sheet — confirm it.
        scheduleStep(() -> {
            AccessibilityNodeInfo sheet = findByText("Liked Songs");
            if (sheet != null) {
                clickFollowNode(sheet);
                sheet.recycle();
            }
            stepOk(stepId, "Like Song");
            onDone.run();
        }, GAP_SHORT);
    }

    /** Skip to the next track inline (no commandDone). Soft-succeeds if Next is missing. */
    private void skipCurrentTrack(String stepId, Runnable onDone) {
        stepStarted(stepId, "Skip Track");
        attemptSkip(stepId, onDone, false);
    }

    private void attemptSkip(String stepId, Runnable onDone, boolean openedPlayer) {
        if (timeoutFired) return;

        AccessibilityNodeInfo skipBtn = findByDesc("Next");
        if (skipBtn == null) skipBtn = findByDesc("Next track");
        if (skipBtn == null) skipBtn = findByDesc("Skip to next track");
        if (skipBtn == null) skipBtn = findByDesc("Skip forward");

        if (skipBtn == null) {
            if (!openedPlayer && openFullPlayer()) {
                scheduleStep(() -> attemptSkip(stepId, onDone, true), GAP_LONG);
                return;
            }
            Log.w(TAG, "[CHOREO] " + stepId + " — Next button not found, soft-skip");
            stepOk(stepId, "Skip Track");
            onDone.run();
            return;
        }

        boolean clicked = skipBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        skipBtn.recycle();
        Log.i(TAG, "[CHOREO] " + stepId + " — skip tapped=" + clicked);
        stepOk(stepId, "Skip Track");
        onDone.run();
    }

    /** Tap the Now Playing bar to open the full player so like/skip buttons exist. */
    private boolean openFullPlayer() {
        AccessibilityNodeInfo bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_bar_layout");
        if (bar == null) bar = findById(SPOTIFY_PACKAGE + ":id/now_playing_container");
        if (bar == null) bar = findByDesc("Now Playing Bar");
        if (bar == null) return false;
        boolean clicked = bar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) clicked = tapAtNodeRandom(bar);
        bar.recycle();
        Log.i(TAG, "[CHOREO] openFullPlayer tapped=" + clicked);
        return true;
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
     * Called when the backend sends CANCEL_COMMAND (user tapped Stop on the dashboard).
     * Handles two cases:
     *
     *   A) Command still in progress (commandInProgress == true):
     *      Kill step chain + send COMMAND_DONE CANCELLED so the backend closes the run.
     *
     *   B) Command already done, playback monitor still running (commandInProgress == false):
     *      commandDone() already sent SUCCESS — don't send a second terminal event or the
     *      backend would overwrite a SUCCESS run with FAILED. Just stop the monitor + pause.
     *
     * Must be called on the main thread (accessibility node reads require it).
     */
    public final void cancel() {
        if (cancelled) {
            Log.d(TAG, "[EXEC] cancel() — already cancelled, ignoring");
            return;
        }
        cancelled = true;
        Log.i(TAG, "[EXEC] cancel() — commandInProgress=" + commandInProgress.get());

        // Stop all pending step callbacks (safe — only executor uses stepHandler).
        timeoutFired = true;
        stepHandler.removeCallbacksAndMessages(null);
        cancelStepTimeout();

        // Stop the playback monitor so it doesn't keep polling Spotify.
        stopPlaybackMonitor();

        // Pause Spotify so music actually stops on the phone.
        pauseForCancel();

        // Only send COMMAND_DONE CANCELLED if the step chain hasn't finished yet.
        // If commandDone() already fired, the run is closed on the backend —
        // a second terminal event would corrupt the run status.
        if (commandInProgress.get()) {
            JsonObject event = buildEvent("COMMAND_DONE", null, null, "CANCELLED", "FAILED");
            sendCommandDoneWithRetry(event, 0);
        }
    }

    private void pauseForCancel() {
        try {
            AccessibilityNodeInfo btn =
                    findById(SPOTIFY_PACKAGE + ":id/nowplaying_elements_playpause_button");
            if (btn == null) btn = findByDesc("Pause");
            if (btn != null) {
                CharSequence cd = btn.getContentDescription();
                if (cd == null || cd.toString().startsWith("Pause")) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "[EXEC] cancel() — Spotify paused");
                }
                btn.recycle();
            }
        } catch (Exception ignored) {}
    }

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
            releaseCommandLock();
            return;
        }

        if (attempt >= COMMAND_DONE_MAX_RETRIES) {
            Log.e(TAG, "[EXEC] COMMAND_DONE delivery failed after " + attempt
                    + " retries — backend will time out the run");
            releaseCommandLock();
            return;
        }

        Log.w(TAG, "[EXEC] COMMAND_DONE send failed (WS down?) — retry #"
                + (attempt + 1) + " in " + COMMAND_DONE_RETRY_MS + "ms");
        scheduleStep(() -> sendCommandDoneWithRetry(event, attempt + 1),
                COMMAND_DONE_RETRY_MS);
    }

    private static void releaseCommandLock() {
        // Keep currentExecutor set — CANCEL_COMMAND may arrive after commandDone()
        // while the playback monitor is still running. execute() overwrites it on
        // the next command, so there is no leak.
        if (commandInProgress.compareAndSet(true, false)) {
            BotForegroundService.onCommandFinished();
        }
    }

    private static final int  STEP_EVENT_MAX_RETRIES = 20;
    private static final long STEP_EVENT_RETRY_MS    = 500;

    private void sendStep(String eventType, String stepId, String stepName,
                           String reasonCode, String finalStatus) {
        JsonObject event = buildEvent(eventType, stepId, stepName, reasonCode, finalStatus);
        sendStepWithRetry(event, 0);
    }

    private void sendStepWithRetry(JsonObject event, int attempt) {
        BotWebSocketClient liveWs = ws;
        if (liveWs == null || !liveWs.isAuthenticated()) {
            liveWs = BotForegroundService.currentWs;
        }
        if (liveWs != null && liveWs.isAuthenticated() && liveWs.send(event)) {
            return;
        }
        if (attempt >= STEP_EVENT_MAX_RETRIES) {
            Log.w(TAG, "[EXEC] Step event delivery failed after " + attempt + " retries: "
                    + event.get("type").getAsString());
            return;
        }
        scheduleStep(() -> sendStepWithRetry(event, attempt + 1), STEP_EVENT_RETRY_MS);
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
