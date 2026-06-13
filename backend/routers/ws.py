"""
routers/ws.py — WebSocket endpoint for Android device communication

GET /ws/{device_id}  — persistent WebSocket connection

Message flow:
  APK → Backend:  DEVICE_HELLO  (on connect)
  APK → Backend:  STEP_STARTED / STEP_OK / STEP_FAILED / COMMAND_DONE  (during execution)
  Backend → APK:  command payload  (when dashboard triggers Run Now)

The device must be registered in the DB before connecting.
The shared secret in DEVICE_HELLO is verified against the DB record.
"""

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from backend.config import settings
from backend.database.db import get_db
from backend.models.tables import Device, Run, RunEvent
from backend.websocket.manager import manager
from backend.websocket.broadcaster import broadcaster

logger = logging.getLogger("ws")
router = APIRouter(tags=["WebSocket"])


@router.websocket("/ws/{device_id}")
async def websocket_endpoint(
    device_id: str,
    websocket: WebSocket,
    db: AsyncSession = Depends(get_db),
):
    """
    Persistent WebSocket connection for an Android device.

    Step 1 — Accept the connection.
    Step 2 — Wait for DEVICE_HELLO (first message must be auth).
    Step 3 — Verify the device exists and the secret matches.
    Step 4 — Register in manager → device is now reachable for commands.
    Step 5 — Listen loop: receive events from APK, persist to DB.
    Step 6 — On disconnect: mark device offline.
    """

    # ── Step 1: Accept raw WebSocket (not yet in manager — auth first) ───────
    # IMPORTANT: we do NOT call manager.connect() here.
    # Registering before auth would make the device appear online and reachable
    # for commands while still in the handshake. Commands sent during auth
    # arrive before the event loop starts — the APK drops them silently.
    # We only register in the manager AFTER AUTH_OK is sent (Step 4 below).
    await websocket.accept()

    authenticated = False
    try:
        # ── Step 2: Wait for DEVICE_HELLO (first message must be auth) ────────
        try:
            raw = await websocket.receive_text()
        except Exception:
            logger.warning(f"[WS] {device_id} disconnected before sending DEVICE_HELLO")
            return

        try:
            hello = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning(f"[WS] Invalid JSON from {device_id}, closing")
            await websocket.close(code=1003)
            return

        if hello.get("type") != "DEVICE_HELLO":
            logger.warning(f"[WS] Expected DEVICE_HELLO, got {hello.get('type')} from {device_id}")
            await websocket.close(code=1008)
            return

        # ── Step 3: Verify secret, auto-register if first-time device ───────────
        #
        # Two cases:
        #   A) Known device (in DB) — verify shared_secret matches stored token.
        #   B) Unknown device (not in DB) — auto-register if shared_secret matches
        #      the backend-wide DEVICE_SHARED_SECRET. This lets any new device
        #      (e.g. new phone with an auto-generated device_id) connect without
        #      needing a separate HTTP registration step first.
        #
        shared_secret = hello.get("shared_secret", "")

        result = await db.execute(select(Device).where(Device.device_id == device_id))
        device = result.scalar_one_or_none()

        if not device:
            # Unknown device — accept only if secret matches the backend secret
            if shared_secret != settings.DEVICE_SHARED_SECRET:
                logger.warning(f"[WS] Unknown device with bad secret: {device_id}")
                await websocket.send_text(json.dumps({
                    "type": "AUTH_FAILED", "reason": "Invalid shared secret"
                }))
                await websocket.close(code=1008)
                return

            # Auto-register: create a new device row on first connect
            device = Device(
                device_id         = device_id,
                device_auth_token = settings.DEVICE_SHARED_SECRET,
                status            = "online",
                app_version       = hello.get("app_version", "unknown"),
                capabilities      = json.dumps(hello.get("capabilities", [])),
                last_seen         = datetime.now(timezone.utc),
            )
            db.add(device)
            await db.commit()
            await db.refresh(device)
            logger.info(f"[WS] Auto-registered new device: {device_id}")

        else:
            # Known device — verify its stored token
            if shared_secret != device.device_auth_token:
                logger.warning(f"[WS] Bad secret from device: {device_id}")
                await websocket.send_text(json.dumps({
                    "type": "AUTH_FAILED", "reason": "Invalid shared secret"
                }))
                await websocket.close(code=1008)
                return

        # ── Step 4: Auth OK — register in manager THEN send AUTH_OK ──────────
        # Registering here (after auth) guarantees the device is ready to receive
        # commands. Any Run Now triggered after this point will land on a connection
        # that is already in the event loop and able to process the COMMAND message.
        now = datetime.now(timezone.utc)
        device.capabilities = json.dumps(hello.get("capabilities", []))
        device.app_version  = hello.get("app_version", device.app_version)
        device.status       = "online"
        device.last_seen    = now
        await db.commit()

        await manager.connect(device_id, websocket, db)
        authenticated = True

        from backend.main import _SERVER_BOOT_ID
        await websocket.send_text(json.dumps({
            "type":      "AUTH_OK",
            "device_id": device_id,
            "boot_id":   _SERVER_BOOT_ID,
        }))
        logger.info(f"[WS] DEVICE_HELLO accepted: {device_id} "
                    f"v={device.app_version} caps={device.capabilities}")

        # ── Step 5: Event listening loop ──────────────────────────────────────
        # 120s receive timeout — APK sends a PING every 15s on a dedicated thread.
        # Without this, a hard phone crash (SIGKILL, battery out) leaves the server
        # blocked on receive_text() forever.
        while True:
            try:
                raw = await asyncio.wait_for(websocket.receive_text(), timeout=120.0)
            except asyncio.TimeoutError:
                logger.warning(f"[WS] {device_id}: no message in 120s — closing dead connection")
                break
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                logger.warning(f"[WS] Malformed JSON from {device_id}: {raw[:100]}")
                continue

            msg_type = msg.get("type", "")
            logger.debug(f"[WS] ← {device_id}: {msg_type}")

            # ── Refresh last_seen on EVERY message (keeps device online) ─────
            # The heartbeat task marks devices offline after 60s of no DB activity.
            # Updating here on every incoming message (including PING) keeps the
            # device showing as online as long as the connection is alive.
            device.last_seen = datetime.now(timezone.utc)
            await db.commit()

            # Handle APK heartbeat ping — refresh last_seen and reply with PONG.
            # Sending PONG back gives the APK bidirectional confirmation that the
            # backend is alive and receiving messages (not just an open TCP socket).
            if msg_type == "PING":
                logger.debug(f"[WS] PING from {device_id} — last_seen updated")
                await websocket.send_text(json.dumps({
                    "type": "PONG", "device_id": device_id
                }))
                continue

            # APK reports the track finished playing — wake the session scheduler
            # so it moves to the next task instead of waiting on a dead timer.
            if msg_type == "PLAYBACK_FINISHED":
                run_id = msg.get("run_id")
                if run_id:
                    from backend.tasks.session_scheduler import notify_playback_finished
                    notified = notify_playback_finished(run_id)
                    logger.info(
                        f"[WS] PLAYBACK_FINISHED from {device_id} "
                        f"run={run_id} notified={notified}"
                    )
                continue

            # Persist step events to the run_events table
            if msg_type in ("STEP_STARTED", "STEP_OK", "STEP_FAILED", "COMMAND_DONE"):
                run_id = msg.get("run_id")
                if run_id:
                    event = RunEvent(
                        run_id=run_id,
                        command_id=msg.get("command_id", str(uuid.uuid4())),
                        step_id=msg.get("step_id"),
                        step_name=msg.get("step_name"),
                        event_type=msg_type,
                        reason_code=msg.get("reason_code"),
                        payload=json.dumps(msg),
                        timestamp=datetime.now(timezone.utc),
                    )
                    db.add(event)

                    # If the command is done, close out the Run row.
                    # IMPORTANT: this intentionally overwrites TIMED_OUT with the
                    # real final status. If the WebSocket dropped mid-run and
                    # COMMAND_DONE arrives late (after the expiry task already
                    # marked the run TIMED_OUT), the device's actual result wins.
                    # A late SUCCESS is always better than a wrong TIMED_OUT.
                    if msg_type == "COMMAND_DONE":
                        run_result = await db.execute(
                            select(Run).where(Run.run_id == run_id)
                        )
                        run = run_result.scalar_one_or_none()
                        if run:
                            final = msg.get("final_status", "SUCCESS")
                            if run.status == "TIMED_OUT" and final == "SUCCESS":
                                logger.info(
                                    f"[WS] Late COMMAND_DONE for run={run_id} — "
                                    f"overwriting TIMED_OUT → SUCCESS"
                                )
                            run.status   = final
                            run.end_time = datetime.now(timezone.utc)

                    await db.commit()
                    logger.info(f"[WS] Event persisted: {msg_type} run={run_id}")

                    # Push the step event to all open dashboard SSE streams instantly.
                    # The broadcaster fans it out to every subscribed queue — one per
                    # open dashboard tab. The frontend appends it to the run log in real
                    # time without waiting for the next 2s poll cycle.
                    #
                    # session_id is included for runs dispatched by the session scheduler
                    # (None for manual Run Now).  The dashboard uses it to re-associate
                    # run_events when the session_run SSE event was missed (brief reconnect
                    # between task dispatches causes the dashboard to stay on the old run_id
                    # and silently discard all events for the new run).
                    from backend.tasks.session_scheduler import get_session_id_for_run
                    session_id_for_run = get_session_id_for_run(run_id)

                    now_iso = datetime.now(timezone.utc).isoformat()
                    await broadcaster.publish({
                        "type":        "run_event",
                        "run_id":      run_id,
                        "session_id":  session_id_for_run,
                        "event_type":  msg_type,
                        "step_id":     msg.get("step_id"),
                        "step_name":   msg.get("step_name"),
                        "reason_code": msg.get("reason_code"),
                        "timestamp":   now_iso,
                    })

                    # For COMMAND_DONE also push a status update so the badge flips
                    # from RUNNING → SUCCESS / FAILED without waiting for a poll.
                    if msg_type == "COMMAND_DONE":
                        await broadcaster.publish({
                            "type":   "run_status",
                            "run_id": run_id,
                            "status": msg.get("final_status", "SUCCESS"),
                        })

    except WebSocketDisconnect:
        logger.info(f"[WS] Device disconnected: {device_id}")
    except Exception as e:
        logger.exception(f"[WS] Unexpected error for {device_id}: {e}")
    finally:
        # ── Step 6: Cleanup ───────────────────────────────────────────────────
        if authenticated:
            await manager.disconnect(device_id, db, websocket)
            # Only fail runs if the device has NOT reconnected within a short grace.
            # Brief drops during automation (accessibility flood, WiFi hiccup) should
            # not kill a run that is still executing on the phone.
            # 30s grace — automation can briefly drop WS while Spotify is foregrounded;
            # the APK defers reconnect until the command finishes.
            if not manager.is_connected(device_id):
                await asyncio.sleep(30)
                if not manager.is_connected(device_id):
                    await _fail_running_runs_for_device(device_id, db)


async def _fail_running_runs_for_device(device_id: str, db: AsyncSession) -> None:
    """Immediately mark all RUNNING runs for a device as FAILED on disconnect."""
    result = await db.execute(
        select(Run).where(Run.device_id == device_id, Run.status == "RUNNING")
    )
    runs = result.scalars().all()
    if not runs:
        return

    now = datetime.now(timezone.utc)
    for run in runs:
        run.status   = "FAILED"
        run.end_time = now
        # Add a synthetic COMMAND_DONE event so the dashboard shows something
        # meaningful instead of "Waiting for device…"
        event = RunEvent(
            run_id     = run.run_id,
            command_id = str(uuid.uuid4()),
            event_type = "COMMAND_DONE",
            reason_code= "DEVICE_DISCONNECTED",
            payload    = json.dumps({"reason": "Device disconnected mid-command"}),
            timestamp  = now,
        )
        db.add(event)

    await db.commit()
    logger.warning(
        f"[WS] Auto-failed {len(runs)} RUNNING run(s) for disconnected device {device_id}"
    )
