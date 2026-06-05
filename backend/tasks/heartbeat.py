"""
heartbeat.py — Module 5.4

Background asyncio task: marks devices offline when last_seen goes stale.

Problem it solves:
    If a device's process dies hard (OOM, SIGKILL), the WebSocket onDisconnect
    handler never runs — the device stays 'online' in the DB indefinitely.

Solution:
    Every HEARTBEAT_CHECK_INTERVAL_S seconds, find all devices where:
        status = 'online' AND last_seen < (now - STALE_THRESHOLD_S)
    and set status = 'offline'.

    The stale threshold is 60s. The APK's OkHttp sends a WebSocket ping every
    20s, so a healthy device's last_seen is updated at least every 20s.
    Missing 3 pings in a row → considered dead.

Schema (matches models/tables.py):
    devices.device_id  — PK string
    devices.status     — 'online' | 'offline'
    devices.last_seen  — DateTime (UTC), nullable

Usage (called from main.py lifespan):
    asyncio.create_task(heartbeat_loop(engine))
"""

import asyncio
import logging
from datetime import datetime, timezone, timedelta

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

logger = logging.getLogger("spotifybot")

# Imported lazily inside the function to avoid circular imports at module load time
def _get_manager():
    from backend.websocket.manager import manager
    return manager

HEARTBEAT_CHECK_INTERVAL_S = 60    # how often we scan
STALE_THRESHOLD_S          = 90    # last_seen older than this → act
# APK pings every 30s. 90s = 3 missed pings before we act.
# Tightened from 150s: a healthy device never goes 90s without a ping.
# A zombie connection (Samsung deep sleep killed the app) is caught sooner.


async def heartbeat_loop(engine: AsyncEngine) -> None:
    """
    Infinite loop — marks stale devices offline.
    Cancelled cleanly on app shutdown via lifespan.
    """
    logger.info(
        "[HEARTBEAT] Device heartbeat task started "
        "(interval=%ds, stale_threshold=%ds)",
        HEARTBEAT_CHECK_INTERVAL_S, STALE_THRESHOLD_S,
    )

    while True:
        try:
            await _mark_stale_devices_offline(engine)
        except Exception as exc:
            logger.error("[HEARTBEAT] Check error (will retry next cycle): %s", exc)

        await asyncio.sleep(HEARTBEAT_CHECK_INTERVAL_S)


async def _mark_stale_devices_offline(engine: AsyncEngine) -> None:
    """Mark all online devices whose last_seen is older than STALE_THRESHOLD_S."""
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=STALE_THRESHOLD_S)

    async with engine.begin() as conn:
        # Fetch stale devices so we can log by name
        result = await conn.execute(
            text("""
                SELECT device_id, last_seen
                FROM devices
                WHERE status = 'online'
                  AND last_seen < :cutoff
            """),
            {"cutoff": cutoff.isoformat()},
        )
        stale = result.fetchall()

        if not stale:
            return

        mgr = _get_manager()
        dead = []   # devices that are truly gone (no live WS + stale last_seen)

        for row in stale:
            device_id, last_seen = row[0], row[1]
            if mgr.is_connected(device_id):
                # "Zombie" connection: the TCP socket is registered as live but the
                # Android app stopped sending pings (Samsung deep sleep killed the
                # app's main thread while the OS-level TCP connection stayed open).
                #
                # Old behaviour: skip silently → device stays ONLINE forever,
                # every command fails with ACCESSIBILITY_SERVICE_NOT_RUNNING,
                # user sees device online but nothing works.
                #
                # New behaviour: force-close the WebSocket.
                #   • Backend removes it from the registry immediately.
                #   • _delayed_offline fires after 5s grace → device shows OFFLINE.
                #   • APK's OkHttp detects the server-side close and triggers its
                #     reconnect loop. On reconnect, BotForegroundService health
                #     check evaluates accessibility service state.
                logger.warning(
                    "[HEARTBEAT] Device '%s' last_seen=%s — zombie connection "
                    "(live WS but no pings) — force-closing",
                    device_id, last_seen,
                )
                await mgr.force_disconnect(device_id)
            else:
                logger.warning(
                    "[HEARTBEAT] Device '%s' last seen at %s — no live WS — marking offline",
                    device_id, last_seen,
                )
                dead.append(device_id)

        if not dead:
            return

        # Only mark offline devices that have NEITHER a live WS NOR a fresh last_seen
        placeholders = ",".join(f"'{d}'" for d in dead)
        await conn.execute(
            text(f"""
                UPDATE devices
                SET status = 'offline'
                WHERE device_id IN ({placeholders})
            """),
        )

    logger.info("[HEARTBEAT] Marked %d device(s) offline", len(dead))
