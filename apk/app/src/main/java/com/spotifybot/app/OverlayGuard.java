package com.spotifybot.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * OverlayGuard — automatic Spotify overlay and ad dismissal.
 *
 * Shared by:
 *   - SpotifyAccessibilityService.onAccessibilityEvent() — called on every
 *     TYPE_WINDOW_STATE_CHANGED so any modal that appears is dismissed immediately,
 *     not just at executor step boundaries.
 *   - SpotifyExecutor.dismissOverlays() — on-demand call before/during steps.
 *
 * Handles:
 *   - Premium upsell modals ("Get Premium, free for 3 months")
 *   - "What's new" / feature announcement cards
 *   - Notification / permission prompts
 *   - Generic dismiss / close / skip / not-now buttons
 *   - "Skip ad" buttons that appear after the countdown
 *
 * Throttle: check() is capped at once per THROTTLE_MS to avoid spamming on rapid
 * window-change bursts (e.g. modal open animation fires several events).
 * dismiss() is unthrottled — safe to call directly from executor steps.
 */
public class OverlayGuard {

    private static final String TAG          = "SpotifyBot";
    private static final String SPOTIFY_PKG  = "com.spotify.music";
    private static final long   THROTTLE_MS  = 800;

    private static volatile long lastCheckMs = 0;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Throttled entry point — called from onAccessibilityEvent().
     * Returns true if an overlay was dismissed.
     */
    public static boolean check(AccessibilityService svc) {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < THROTTLE_MS) return false;
        lastCheckMs = now;
        return dismiss(svc);
    }

    /**
     * Unthrottled dismissal — called directly from executor steps.
     * Tries every detection strategy in order and stops at the first successful dismiss.
     */
    public static boolean dismiss(AccessibilityService svc) {
        if (svc == null) return false;

        // ── 1. content-description patterns ──────────────────────────────────
        String[] descPatterns = {
            "Dismiss", "Close", "dismiss", "close",
            "Not now", "No thanks", "Skip", "Got it",
            "Close button", "Close dialog",
        };
        for (String desc : descPatterns) {
            AccessibilityNodeInfo node = findByDesc(svc, desc);
            if (node != null) {
                boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
                if (clicked) {
                    Log.i(TAG, "[OVERLAY] Auto-dismissed via desc='" + desc + "'");
                    return true;
                }
            }
        }

        // ── 2. text patterns ──────────────────────────────────────────────────
        String[] textPatterns = {
            "Not now", "No thanks", "Skip", "SKIP",
            "Maybe later", "Close", "Got it", "Dismiss",
            // Free-tier / premium upsell: always pick "stay free" over "upgrade"
            "Continue with ads", "Continue Listening",
            "Keep listening for free", "I'll keep listening for free",
            "No, thanks", "Continue for free",
        };
        for (String text : textPatterns) {
            AccessibilityNodeInfo node = findByText(svc, text);
            if (node != null) {
                boolean clicked = clickUp(node);
                node.recycle();
                if (clicked) {
                    Log.i(TAG, "[OVERLAY] Auto-dismissed via text='" + text + "'");
                    return true;
                }
            }
        }

        // ── 3. resource-id patterns ────────────────────────────────────────────
        String[] idPatterns = {
            SPOTIFY_PKG + ":id/close_button",
            SPOTIFY_PKG + ":id/dismiss_button",
            SPOTIFY_PKG + ":id/upsell_close",
            SPOTIFY_PKG + ":id/upsell_dismiss",
            SPOTIFY_PKG + ":id/dialog_close",
            SPOTIFY_PKG + ":id/sheet_close",
            SPOTIFY_PKG + ":id/banner_close",
            // Free-tier interstitial / premium upsell close targets
            SPOTIFY_PKG + ":id/continue_free_button",
            SPOTIFY_PKG + ":id/interstitial_dismiss",
            SPOTIFY_PKG + ":id/free_tier_interstitial_close",
            SPOTIFY_PKG + ":id/upsell_maybe_later",
        };
        for (String id : idPatterns) {
            AccessibilityNodeInfo node = findById(svc, id);
            if (node != null) {
                boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
                if (clicked) {
                    Log.i(TAG, "[OVERLAY] Auto-dismissed via id='" + id + "'");
                    return true;
                }
            }
        }

        // ── 4. "Skip ad" button ────────────────────────────────────────────────
        AccessibilityNodeInfo skipAd = findByDesc(svc, "Skip ad");
        if (skipAd == null) skipAd = findByText(svc, "Skip ad");
        if (skipAd == null) skipAd = findByDesc(svc, "Skip Ad");
        if (skipAd != null) {
            boolean clicked = skipAd.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            skipAd.recycle();
            if (clicked) {
                Log.i(TAG, "[OVERLAY] Auto-skipped ad");
                return true;
            }
        }

        // ── 5. Spotify promotional / recommendation cards ─────────────────────
        // "Try a playlist", "MADE FOR YOU", "New releases", etc.
        // These cards have a "Dismiss" text node rendered in Compose — clickUp on
        // the text node often fails because the click is absorbed by a parent container.
        // Pressing Back is the fastest, most reliable way to close them.
        String[] promoTexts = {
            "Try a playlist", "Try a Playlist",
            "MADE FOR YOU", "Made for you",
            "CHECK IT OUT",
            "New releases for you",
            "Try this playlist",
        };
        for (String t : promoTexts) {
            AccessibilityNodeInfo n = findByText(svc, t);
            if (n == null) n = findByDesc(svc, t);
            if (n != null) {
                n.recycle();
                // First try tapping the Dismiss text directly
                AccessibilityNodeInfo dismiss = findByText(svc, "Dismiss");
                if (dismiss == null) dismiss = findByText(svc, "DISMISS");
                if (dismiss != null) {
                    boolean clicked = clickUp(dismiss);
                    dismiss.recycle();
                    if (clicked) {
                        Log.i(TAG, "[OVERLAY] Promo card dismissed via Dismiss tap");
                        return true;
                    }
                }
                // Fallback: Back always closes these cards
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                Log.i(TAG, "[OVERLAY] Promo card dismissed via Back (text='" + t + "')");
                return true;
            }
        }

        // ── 6. Premium upsell interstitial — full-screen offer with a "dismiss" link ─
        //
        // Spotify shows regional variants like:
        //   "3 months of Premium for Rs 0 are waiting."
        //   "3 months free"
        //   "Individual plan only. Rs 379.00/month after."
        //   "New to Premium only."
        //
        // The "dismiss" text at the bottom IS findable but is a Compose node with
        // isClickable()=false on the text AND all its ancestors.  clickUp() gates on
        // isClickable() so it silently fails.  Direct performAction(ACTION_CLICK) works
        // on Compose nodes even when isClickable()=false — same fix as row_root.
        //
        // Detection strategy: look for unique fine-print strings that ONLY appear on
        // this upsell screen, not in normal Spotify UI.
        String[] upsellMarkers = {
            "months of Premium",    // "3 months of Premium for Rs 0 are waiting."
            "months free",          // "3 months free", "6 months free"
            "Individual plan only", // fine print on the Rs 0 offer
            "New to Premium only",  // fine print on trial offers
            "New to Spotify Premium", // alternate fine print
            "ad-free music",        // body text of premium upsell
        };
        for (String marker : upsellMarkers) {
            AccessibilityNodeInfo markerNode = findByText(svc, marker);
            if (markerNode == null) markerNode = findByTextAllWindows(svc, marker);
            if (markerNode != null) {
                markerNode.recycle();
                // Step 1: find the "dismiss" text node and use direct ACTION_CLICK
                // (bypasses isClickable() gate — required for Compose rendered nodes)
                AccessibilityNodeInfo dismissNode = findByText(svc, "dismiss");
                if (dismissNode == null) dismissNode = findByText(svc, "Dismiss");
                if (dismissNode == null) dismissNode = findByText(svc, "DISMISS");
                if (dismissNode != null) {
                    // UIAutoDev-confirmed (2026-06-05): the "dismiss" node and ALL its
                    // Compose ancestors have isClickable=false. performAction(ACTION_CLICK)
                    // and clickUp() both fail silently on this tree.
                    //
                    // The node has valid screen bounds [464,2031][616,2094].
                    // dispatchGesture() at those coordinates is a real touch event —
                    // it bypasses the isClickable gate and works on any rendered node.
                    boolean clicked = tapNodeGesture(svc, dismissNode);
                    if (!clicked) clicked = dismissNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (!clicked) clicked = clickUp(dismissNode);
                    dismissNode.recycle();
                    if (clicked) {
                        Log.i(TAG, "[OVERLAY] Premium upsell dismissed via gesture on 'dismiss'");
                        return true;
                    }
                }
                // Step 2: Back always closes this screen without accepting the offer
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                Log.i(TAG, "[OVERLAY] Premium upsell dismissed via Back (marker='" + marker + "')");
                return true;
            }
        }

        // ── 6b. Generic "Get Premium" modal (older / other upsell variants) ───
        AccessibilityNodeInfo premium = findByText(svc, "Get Premium");
        if (premium == null) premium = findByDesc(svc, "Get Premium");
        if (premium == null) premium = findByText(svc, "Try Premium");
        if (premium == null) premium = findByText(svc, "Start your Premium");
        if (premium != null) {
            premium.recycle();
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "[OVERLAY] Generic premium upsell modal → pressed Back");
            return true;
        }

        // ── 7. Android system permission dialogs ─────────────────────────────
        // These appear in a SEPARATE system window (package: android or
        // com.android.permissioncontroller) — NOT inside Spotify's window tree.
        // Examples: "Allow Spotify to access Bluetooth", "Allow Spotify to find,
        // connect to, and determine the relative position of nearby devices".
        // We search ALL windows to find the dialog title, then click "Continue"
        // (Samsung/Android 12-14 label) or "Allow" (stock Android label).
        // Clicking "Continue" / "Allow" is correct here — Spotify needs Bluetooth
        // to discover Cast devices; denying it causes repeated prompts.
        AccessibilityNodeInfo permTitle = findByTextAllWindows(svc, "Allow Spotify to");
        if (permTitle != null) {
            permTitle.recycle();
            // Try "Continue" first — used on Samsung One UI and Android 13-14
            AccessibilityNodeInfo cont = findByTextAllWindows(svc, "Continue");
            if (cont != null) {
                boolean clicked = clickUp(cont);
                cont.recycle();
                if (clicked) {
                    Log.i(TAG, "[OVERLAY] System permission dialog → clicked 'Continue'");
                    return true;
                }
            }
            // Fallback: stock Android uses "Allow"
            AccessibilityNodeInfo allow = findByTextAllWindows(svc, "Allow");
            if (allow != null) {
                boolean clicked = clickUp(allow);
                allow.recycle();
                if (clicked) {
                    Log.i(TAG, "[OVERLAY] System permission dialog → clicked 'Allow'");
                    return true;
                }
            }
            // Last resort: Back closes the dialog without granting (better than being stuck)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            Log.i(TAG, "[OVERLAY] System permission dialog → pressed Back (no button found)");
            return true;
        }

        return false;
    }

    /**
     * Returns true if the current Spotify screen is blocked by a non-immediately-dismissible
     * overlay — i.e. a video/interstitial ad that hasn't shown its Skip button yet, or a
     * full-screen premium upsell modal.
     *
     * Audio ads playing in the mini-player are NOT considered blocking — Spotify's bottom nav
     * is still accessible during audio ads, so executors can continue normally.
     *
     * Called by SpotifyExecutor.launchAndSettle() and waitForAdToClear() to decide whether
     * to hold execution until the overlay is gone.
     */
    public static boolean isAdOrOverlayBlocking(AccessibilityService svc) {
        if (svc == null) return false;

        // Video ad: "Skip ad" button visible (countdown done) or "Skip in X" countdown text.
        // NOTE: never use a single word like "Ad" — it matches "Add to playlist", etc.
        String[] adTexts = { "Skip ad", "Skip Ad", "Skip in " };
        for (String t : adTexts) {
            AccessibilityNodeInfo n = findByText(svc, t);
            if (n != null) { n.recycle(); return true; }
        }
        AccessibilityNodeInfo skipAdDesc = findByDesc(svc, "Skip ad");
        if (skipAdDesc == null) skipAdDesc = findByDesc(svc, "Skip Ad");
        if (skipAdDesc != null) { skipAdDesc.recycle(); return true; }

        // Spotify promotional / recommendation cards (block nav the same way upsells do)
        String[] promoBlockTexts = {
            "Try a playlist", "Try a Playlist",
            "MADE FOR YOU",
            "CHECK IT OUT",
            "New releases for you",
            "Try this playlist",
        };
        for (String t : promoBlockTexts) {
            AccessibilityNodeInfo n = findByText(svc, t);
            if (n != null) { n.recycle(); return true; }
        }

        // Premium upsell / free-tier interstitial full-screen modal
        String[] upsellTexts = {
            "Get Premium",
            "Start your free trial",
            "Start free trial",
            "Continue with ads",
            "Keep listening for free",
            "Upgrade to Premium",
            // Regional / trial offer variants ("3 months of Premium for Rs 0 are waiting.")
            "months of Premium",
            "months free",
            "Individual plan only",
            "New to Premium only",
            "ad-free music",
        };
        for (String t : upsellTexts) {
            AccessibilityNodeInfo n = findByText(svc, t);
            if (n != null) { n.recycle(); return true; }
        }

        // Known interstitial / upsell container resource IDs
        String[] blockingIds = {
            SPOTIFY_PKG + ":id/upsell_fullscreen",
            SPOTIFY_PKG + ":id/interstitial_container",
            SPOTIFY_PKG + ":id/free_tier_interstitial",
            SPOTIFY_PKG + ":id/upgrade_banner",
        };
        for (String id : blockingIds) {
            AccessibilityNodeInfo n = findById(svc, id);
            if (n != null) { n.recycle(); return true; }
        }

        // Android system permission dialog — blocks Spotify's UI until answered.
        // Checked across all windows because it lives in a separate system window.
        AccessibilityNodeInfo permDialog = findByTextAllWindows(svc, "Allow Spotify to");
        if (permDialog != null) { permDialog.recycle(); return true; }

        return false;
    }

    /**
     * Returns true if a Spotify audio ad is currently playing in the mini-player.
     *
     * Audio ads are different from video/overlay ads:
     *   - They do NOT block the UI — Spotify's bottom nav is still accessible.
     *   - They appear as a short track (15–30 s) in the mini-player with the
     *     track name / artist showing "Advertisement".
     *   - The free tier can show 1–2 audio ads back-to-back (up to ~60 s total).
     *
     * We check for the word "Advertisement" in the visible UI.
     * This is specific enough — it will not match normal song/artist names.
     *
     * Called by SpotifyExecutor.waitForAudioAdToFinish() before each new command
     * so execution doesn't race against a playing ad.
     */
    public static boolean isAudioAdPlaying(AccessibilityService svc) {
        if (svc == null) return false;

        // "Advertisement" appears as the track name in the mini-player during audio ads.
        // Check visible text first (fastest), then content-description.
        AccessibilityNodeInfo n = findByText(svc, "Advertisement");
        if (n != null) { n.recycle(); return true; }

        n = findByDesc(svc, "Advertisement");
        if (n != null) { n.recycle(); return true; }

        // Some Spotify versions show the ad label in the Now Playing bar via these IDs
        n = findById(svc, SPOTIFY_PKG + ":id/ad_label");
        if (n != null) { n.recycle(); return true; }

        n = findById(svc, SPOTIFY_PKG + ":id/ad_indicator");
        if (n != null) { n.recycle(); return true; }

        return false;
    }

    // ── Node-finding helpers (work directly on AccessibilityService) ──────────

    /** Search all Spotify windows for a node whose content-desc contains needle. */
    private static AccessibilityNodeInfo findByDesc(AccessibilityService svc, String needle) {
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo result = findByDescRecursive(root, needle);
                if (result != null) return result;
                root.recycle();
            }
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root != null) return findByDescRecursive(root, needle);
        return null;
    }

    private static AccessibilityNodeInfo findByDescRecursive(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.toString().toLowerCase().contains(needle.toLowerCase())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findByDescRecursive(child, needle);
            if (result != null) return result;
            if (child != null) child.recycle();
        }
        return null;
    }

    /**
     * Dispatch a gesture tap at the center of a node's screen bounds.
     *
     * Used for Compose-rendered nodes where isClickable=false on the node AND all
     * ancestors, making performAction(ACTION_CLICK) and clickUp() both ineffective.
     * A gesture tap is a real touch event delivered at pixel coordinates — it works
     * regardless of the accessibility clickable flag.
     *
     * UIAutoDev confirmed: the Spotify "dismiss" TextView on the premium upsell screen
     * has bounds [464,2031][616,2094] and isClickable=false all the way up the tree.
     *
     * Requires android:canPerformGestures="true" in accessibility_service_config.xml
     * (already set for SpotifyBot).
     */
    private static boolean tapNodeGesture(AccessibilityService svc, AccessibilityNodeInfo node) {
        if (svc == null || node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        float tapX = bounds.centerX();
        float tapY = bounds.centerY();

        Path path = new Path();
        path.moveTo(tapX, tapY);

        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 120))
                    .build();
            boolean dispatched = svc.dispatchGesture(gesture, null, null);
            Log.d(TAG, "[OVERLAY] tapNodeGesture: dispatched=" + dispatched
                    + " coords=(" + (int) tapX + "," + (int) tapY + ")");
            return dispatched;
        } catch (Exception e) {
            Log.w(TAG, "[OVERLAY] tapNodeGesture error: " + e.getMessage());
            return false;
        }
    }

    /** Find a node by visible text in the active window. */
    private static AccessibilityNodeInfo findByText(AccessibilityService svc, String text) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /**
     * Find a node by visible text across ALL windows — including system windows such as
     * Android permission dialogs (com.android.permissioncontroller) that sit outside
     * Spotify's own window tree and are therefore invisible to findByText().
     */
    private static AccessibilityNodeInfo findByTextAllWindows(AccessibilityService svc, String text) {
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                root.recycle();
                if (nodes != null && !nodes.isEmpty()) return nodes.get(0);
            }
        }
        // Fallback: active window (covers the case where getWindows() is unavailable)
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        root.recycle();
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /** Find a node by resource-id in the active window. */
    private static AccessibilityNodeInfo findById(AccessibilityService svc, String viewId) {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        root.recycle();
        return (nodes != null && !nodes.isEmpty()) ? nodes.get(0) : null;
    }

    /** Click a node directly, then walk up to its nearest clickable ancestor if needed. */
    private static boolean clickUp(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                parent.recycle();
                return true;
            }
            AccessibilityNodeInfo gp = parent.getParent();
            parent.recycle();
            parent = gp;
        }
        return false;
    }
}
