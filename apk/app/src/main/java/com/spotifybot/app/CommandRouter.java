package com.spotifybot.app;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonObject;

/**
 * CommandRouter — Module 4.6
 *
 * Routes incoming COMMAND payloads to the correct SpotifyExecutor subclass.
 *
 * Responsibilities:
 *   1. TTL check   — ignore commands older than ttl_ms (device was offline/reconnected)
 *   2. Route       — map action_type → executor class → executor.execute()
 *   3. Unknown     — send COMMAND_DONE FAILED for unrecognised action types
 *
 * Each executor is a fresh instance per command — no shared mutable state.
 *
 * Supported action_type values:
 *   SEARCH_AND_PLAY    → SearchAndPlayExecutor    (params: query, playlist_name [optional])
 *   LIKE_CURRENT_TRACK → LikeTrackExecutor        (params: none)
 *   FOLLOW_ARTIST      → FollowArtistExecutor     (params: artist_name)
 *   SKIP_TRACK         → SkipTrackExecutor        (params: none)
 *   PLAY_FROM_ALBUM    → PlayFromAlbumExecutor    (params: query, daily_limit, hourly_limit)
 *   PLAY_FROM_PLAYLIST → PlayFromPlaylistExecutor (params: query, daily_limit, hourly_limit)
 *   FOLLOW_PLAYLIST    → FollowPlaylistExecutor   (params: query)
 *   ADD_TO_PLAYLIST    → AddToPlaylistExecutor    (params: playlist_name) [legacy — use SEARCH_AND_PLAY + playlist_name]
 *   CREATE_PLAYLIST    → CreatePlaylistExecutor   (params: playlist_name)
 */
public class CommandRouter {

    private static final String TAG = "SpotifyBot";

    // Context kept for future use (e.g. launching Spotify via Intent if not in foreground)
    private final Context context;

    public CommandRouter(Context context) {
        this.context = context;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Execute a command received from the backend.
     *
     * @param command  Parsed JSON command payload
     * @param wsClient Active WebSocket — executors send step events back on this
     */
    public void execute(JsonObject command, BotWebSocketClient wsClient) {
        String runId      = getStr(command, "run_id");
        String commandId  = getStr(command, "command_id");
        String actionType = getStr(command, "action_type");
        long   ttlMs      = command.has("ttl_ms")
                ? command.get("ttl_ms").getAsLong() : 180_000;

        // ── TTL check ─────────────────────────────────────────────────────────
        // Commands older than ttl_ms are silently dropped — the dashboard will
        // already show them as timed-out; re-executing would corrupt the run log.
        if (command.has("issued_at")) {
            try {
                java.time.Instant issued = java.time.Instant.parse(
                        command.get("issued_at").getAsString());
                long ageMs = java.time.Instant.now().toEpochMilli() - issued.toEpochMilli();
                if (ageMs > ttlMs) {
                    Log.w(TAG, "[CMD] ⏰ Command expired — age=" + ageMs + "ms ttl=" + ttlMs + "ms");
                    sendTerminalEvent(wsClient, runId, commandId, "COMMAND_EXPIRED", "FAILED");
                    return;
                }
                Log.i(TAG, "[CMD] TTL OK — age=" + ageMs + "ms");
            } catch (Exception e) {
                Log.w(TAG, "[CMD] Could not parse issued_at — skipping TTL check: " + e.getMessage());
            }
        }

        // ── Extract params ────────────────────────────────────────────────────
        JsonObject params = command.has("params")
                ? command.get("params").getAsJsonObject()
                : new JsonObject();

        String queryHint = params.has("query") ? params.get("query").getAsString() : "";
        Log.i(TAG, "[CMD] Received: action=" + actionType
                + " query=" + queryHint + " run=" + runId + " cmd=" + commandId);

        // ── Route to executor ─────────────────────────────────────────────────
        SpotifyExecutor executor = null;

        switch (actionType) {
            case "SEARCH_AND_PLAY":
                Log.i(TAG, "[CMD] → SearchAndPlayExecutor");
                executor = new SearchAndPlayExecutor();
                break;

            case "LIKE_CURRENT_TRACK":
                Log.i(TAG, "[CMD] → LikeTrackExecutor");
                executor = new LikeTrackExecutor();
                break;

            case "FOLLOW_ARTIST":
                Log.i(TAG, "[CMD] → FollowArtistExecutor");
                executor = new FollowArtistExecutor();
                break;

            case "SKIP_TRACK":
                Log.i(TAG, "[CMD] → SkipTrackExecutor");
                executor = new SkipTrackExecutor();
                break;

            case "PLAY_FROM_ALBUM":
                Log.i(TAG, "[CMD] → PlayFromAlbumExecutor");
                executor = new PlayFromAlbumExecutor();
                break;

            case "PLAY_FROM_PLAYLIST":
                Log.i(TAG, "[CMD] → PlayFromPlaylistExecutor");
                executor = new PlayFromPlaylistExecutor();
                break;

            case "FOLLOW_PLAYLIST":
                Log.i(TAG, "[CMD] → FollowPlaylistExecutor");
                executor = new FollowPlaylistExecutor();
                break;

            case "ADD_TO_PLAYLIST":
                Log.i(TAG, "[CMD] → AddToPlaylistExecutor");
                executor = new AddToPlaylistExecutor();
                break;

            case "CREATE_PLAYLIST":
                Log.i(TAG, "[CMD] → CreatePlaylistExecutor");
                executor = new CreatePlaylistExecutor();
                break;

            default:
                Log.e(TAG, "[CMD] Unknown action_type: " + actionType);
                sendTerminalEvent(wsClient, runId, commandId, "UNKNOWN_ACTION", "FAILED");
                return;
        }

        // ── Dispatch ──────────────────────────────────────────────────────────
        // executor.execute() sets context (runId, commandId, ws) then calls doExecute().
        // doExecute() runs its first step and returns immediately — subsequent steps
        // are scheduled via postDelayed on the main-thread Handler inside SpotifyExecutor.
        executor.execute(runId, commandId, params, wsClient);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Sends a COMMAND_DONE event with a reason_code for terminal non-executor failures
     * (expired TTL, unknown action type).  Real executor failures use SpotifyExecutor.commandDone().
     */
    private void sendTerminalEvent(BotWebSocketClient ws, String runId,
                                    String commandId, String reasonCode, String finalStatus) {
        JsonObject event = new JsonObject();
        event.addProperty("type",         "COMMAND_DONE");
        event.addProperty("run_id",       runId);
        event.addProperty("command_id",   commandId);
        event.addProperty("reason_code",  reasonCode);
        event.addProperty("final_status", finalStatus);
        ws.send(event);
        Log.i(TAG, "[CMD] Terminal event sent: " + reasonCode + " / " + finalStatus);
    }

    private String getStr(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }
}
