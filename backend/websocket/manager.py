"""
websocket/manager.py — WebSocket Connection Manager

This is the heart of the real-time system.

How it works:
- Each Android device connects to /ws/{device_id} and maintains a persistent WebSocket.
- The manager stores these live connections in a dict keyed by device_id.
- When the dashboard hits "Run Now", the command router calls manager.send_command()
  which finds the device's WebSocket and pushes the JSON command directly.
- If the device disconnects, the manager removes it and marks it offline in the DB.

Why in-memory dict?
- For a single-server setup (Week 2 scope), this is fine and fast.
- Multi-server would require a pub/sub layer (Redis) — out of scope for now.
"""

import asyncio
import json
import logging
from datetime import datetime, timezone
from fastapi import WebSocket
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, AsyncEngine
from sqlalchemy import select

from backend.models.tables import Device
from backend.websocket.broadcaster import broadcaster

logger = logging.getLogger("ws_manager")


class ConnectionManager:
    def __init__(self):
        # device_id → WebSocket (only one connection per device at a time)
        self._connections: dict[str, WebSocket] = {}
        self._engine: AsyncEngine | None = None

    def init(self, engine: AsyncEngine) -> None:
        """Call once from main.py lifespan after the engine is created."""
        self._engine = engine

    # ── Connection lifecycle ───────────────────────────────────────────────────

    async def connect(self, device_id: str, websocket: WebSocket, db: AsyncSession):
        """
        Register an already-accepted WebSocket and mark the device online.

        NOTE: ws.py calls websocket.accept() BEFORE calling this method (auth
        happens between accept and register). Do NOT call accept() here — calling
        it twice raises RuntimeError and kills the connection.

        If the same device_id reconnects, the old connection is explicitly closed
        before being replaced.
        """
        old_ws = self._connections.get(device_id)
        if old_ws is not None and old_ws is not websocket:
            logger.info(f"[WS] Closing stale connection for {device_id} — device reconnected")
            try:
                await old_ws.close(code=1001, reason="Replaced by new connection")
            except Exception:
                pass  # already closed — safe to ignore

        self._connections[device_id] = websocket

        # Mark device online in DB
        await self._set_device_status(device_id, "online", db)
        logger.info(f"[WS] Device connected: {device_id} | total={len(self._connections)}")

        # Push real-time status update to all SSE dashboard clients
        await broadcaster.publish({
            "type":      "device_status",
            "device_id": device_id,
            "status":    "online",
        })

    async def disconnect(self, device_id: str, db: AsyncSession, websocket: WebSocket = None):
        """
        Remove the device's WebSocket and mark it offline in the DB.

        Grace period: waits 3 seconds before broadcasting offline and updating the
        DB. If the device reconnects within those 3 seconds, the offline event is
        suppressed entirely — prevents dashboard flicker on brief network hiccups.

        Stale-disconnect guard: if a newer connection has already replaced this
        websocket in the registry, the disconnect is ignored completely.
        """
        current = self._connections.get(device_id)
        if websocket is not None and current is not websocket:
            logger.info(f"[WS] Stale disconnect ignored for {device_id} — newer connection is live")
            return

        # Remove from registry immediately so no new commands are sent to the dead socket
        self._connections.pop(device_id, None)
        logger.info(f"[WS] Device socket removed: {device_id} | total={len(self._connections)}")

        # Grace period — APKs often reconnect within 1-2s on brief drops.
        # We fire a background task so the original (soon-to-be-closed) db session
        # is NOT used after the sleep — we create a fresh session for the delayed update.
        asyncio.create_task(self._delayed_offline(device_id))

    # ── Messaging ─────────────────────────────────────────────────────────────

    async def send_command(self, device_id: str, payload: dict) -> bool:
        """
        Push a command JSON to a specific device.

        Returns True  — message delivered successfully.
        Returns False — device not connected, or socket broken (auto-removed).

        Never raises. A broken WebSocket is silently cleaned up here so the
        caller can immediately mark the run FAILED instead of leaving it stuck
        in RUNNING state until the 5-minute expiry fires.
        """
        ws = self._connections.get(device_id)
        if not ws:
            logger.warning(f"[WS] Cannot send command — device offline: {device_id}")
            return False

        try:
            # 5s timeout prevents a hung WebSocket from blocking the entire request.
            # A healthy connection delivers instantly; if it takes >5s the socket is dead.
            await asyncio.wait_for(ws.send_text(json.dumps(payload)), timeout=5.0)
            logger.info(f"[WS] Command sent to {device_id}: {payload.get('action_type')}")
            return True
        except asyncio.TimeoutError:
            logger.error(f"[WS] Send timed out for {device_id} — removing stale connection")
            self._connections.pop(device_id, None)
            return False
        except Exception as exc:
            logger.error(f"[WS] Send failed for {device_id} — removing stale connection: {exc}")
            self._connections.pop(device_id, None)
            return False

    async def force_disconnect(self, device_id: str) -> None:
        """
        Forcefully close a zombie WebSocket.

        A zombie is a connection where the TCP socket is alive (is_connected()=True)
        but the Android app is sleeping and not sending any messages — last_seen goes
        stale while the connection appears live to the backend.

        Called by the heartbeat task when it detects this state.
        Closing the socket forces the APK's OkHttp to detect the disconnect and
        trigger its reconnect loop. On reconnect, the APK re-authenticates and the
        BotForegroundService health check handles accessibility service state.
        """
        ws = self._connections.pop(device_id, None)
        if ws:
            try:
                await ws.close(code=1001, reason="Zombie: stale last_seen")
            except Exception:
                pass  # already dead — fine
            logger.warning(f"[WS] Force-closed zombie connection: {device_id}")

        # Schedule the offline DB update + SSE broadcast immediately (no grace period).
        # A genuine reconnect will re-add the device before this fires.
        asyncio.create_task(self._delayed_offline(device_id, grace_s=5))

    async def broadcast(self, payload: dict):
        """Send a message to ALL connected devices (used for system-wide events)."""
        message = json.dumps(payload)
        for device_id, ws in list(self._connections.items()):
            try:
                await ws.send_text(message)
            except Exception as e:
                logger.warning(f"[WS] Broadcast failed for {device_id}: {e}")

    def is_connected(self, device_id: str) -> bool:
        return device_id in self._connections

    def connected_devices(self) -> list[str]:
        return list(self._connections.keys())

    async def _delayed_offline(self, device_id: str, grace_s: int = 10) -> None:
        """
        Background task: wait grace_s seconds, then mark device offline if it
        hasn't reconnected. Uses a fresh engine session — safe to call after
        the original WebSocket request's db session has been closed.

        10s grace (up from 3s) — gives the APK enough time to finish its
        exponential-backoff reconnect (1s → 2s → 4s) before the dashboard
        shows the device as offline, avoiding dashboard flicker on brief drops.
        """
        await asyncio.sleep(grace_s)

        if device_id in self._connections:
            logger.info(f"[WS] Device {device_id} reconnected in grace period — offline suppressed")
            return

        if self._engine is None:
            logger.warning(f"[WS] Engine not set — cannot mark {device_id} offline")
            return

        try:
            Session = async_sessionmaker(self._engine, expire_on_commit=False)
            async with Session() as db:
                result = await db.execute(select(Device).where(Device.device_id == device_id))
                device = result.scalar_one_or_none()
                if device:
                    device.status   = "offline"
                    device.last_seen = datetime.now(timezone.utc)
                    await db.commit()
            logger.info(f"[WS] Device offline (after grace): {device_id}")
            await broadcaster.publish({
                "type":      "device_status",
                "device_id": device_id,
                "status":    "offline",
            })
        except Exception as exc:
            logger.error(f"[WS] Failed to mark {device_id} offline: {exc}")

    # ── DB helpers ────────────────────────────────────────────────────────────

    async def _set_device_status(self, device_id: str, status: str, db: AsyncSession):
        """Update the device's status and last_seen timestamp in the database."""
        result = await db.execute(select(Device).where(Device.device_id == device_id))
        device = result.scalar_one_or_none()
        if device:
            device.status = status
            device.last_seen = datetime.now(timezone.utc)
            await db.commit()


# Singleton — imported by both the WS router and the commands router
manager = ConnectionManager()
