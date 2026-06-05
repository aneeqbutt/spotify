"""
routers/events.py — Server-Sent Events endpoint for real-time dashboard updates

GET /events/stream?token=<jwt>

Why query-param auth?
  The browser's EventSource API does not support custom headers — you cannot
  pass "Authorization: Bearer …" like a normal fetch() call. Sending the JWT
  as a query param is the standard workaround for SSE endpoints.

Event format (SSE spec):
  data: {"type": "device_status", "device_id": "...", "status": "online"}\n\n
  : keepalive\n\n  (comment line — keeps connection alive through proxies)

Client lifecycle:
  1. Dashboard opens EventSource → this endpoint streams events forever.
  2. Every 25 s a keepalive comment is sent so the browser doesn't drop the connection.
  3. On device connect/disconnect, manager.py calls broadcaster.publish() →
     every subscribed queue gets the event → this generator yields it immediately.
"""

import asyncio
import json
import logging

from fastapi import APIRouter, Query, HTTPException, status
from fastapi.responses import StreamingResponse
from jose import JWTError, jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import settings
from backend.database.db import AsyncSessionLocal
from backend.models.tables import User, Device
from backend.websocket.broadcaster import broadcaster

logger = logging.getLogger("sse")
router = APIRouter(tags=["Events"])

# ── JWT validation (manual — can't use Depends with EventSource) ──────────────

async def _validate_token(token: str) -> bool:
    """
    Decode and verify the JWT. Returns True if valid, False otherwise.
    We do NOT raise — a bad token just gets the connection closed.
    """
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM]
        )
        username: str = payload.get("sub")
        if not username:
            return False
        # Confirm user still exists in DB
        async with AsyncSessionLocal() as db:
            result = await db.execute(select(User).where(User.username == username))
            return result.scalar_one_or_none() is not None
    except JWTError:
        return False


# ── SSE endpoint ──────────────────────────────────────────────────────────────

@router.get("/events/stream")
async def event_stream(token: str = Query(..., description="JWT access token")):
    """
    Persistent SSE stream for dashboard real-time updates.

    The client (EventSource) connects once and receives a continuous stream of
    JSON events. The connection is kept alive with comment keepalives every 25s.

    Produces: text/event-stream
    """
    # Validate token before opening the stream
    if not await _validate_token(token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
        )

    async def generate():
        q = broadcaster.subscribe()
        logger.info("[SSE] Client connected (total subscribers=%d)", len(broadcaster._queues))
        try:
            # Immediately push current state of ALL devices so the dashboard is
            # accurate even if device connected before this SSE stream opened.
            # This closes the race: device online → SSE opens 1s later → would miss event.
            async with AsyncSessionLocal() as db:
                result = await db.execute(select(Device))
                devices = result.scalars().all()
                for device in devices:
                    yield f"data: {json.dumps({'type': 'device_status', 'device_id': device.device_id, 'status': device.status})}\n\n"
            logger.debug("[SSE] Sent initial state for %d device(s)", len(devices))

            while True:
                try:
                    # Wait up to 25s for an event — if none arrive, send a keepalive
                    # comment. This prevents proxies/load balancers from closing the
                    # idle connection and lets the browser's EventSource know we're alive.
                    event = await asyncio.wait_for(q.get(), timeout=25.0)
                    yield f"data: {json.dumps(event)}\n\n"
                    logger.debug("[SSE] Pushed event type=%s", event.get("type"))
                except asyncio.TimeoutError:
                    # SSE spec: lines starting with ':' are comments — browsers ignore them
                    yield ": keepalive\n\n"
        except asyncio.CancelledError:
            # Client disconnected — this is normal
            logger.info("[SSE] Client disconnected (stream cancelled)")
        except GeneratorExit:
            logger.info("[SSE] Client disconnected (generator exit)")
        finally:
            broadcaster.unsubscribe(q)
            logger.info("[SSE] Queue cleaned up (total subscribers=%d)", len(broadcaster._queues))

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control":       "no-cache",
            "X-Accel-Buffering":   "no",   # disable Nginx buffering — critical for SSE
            "Connection":          "keep-alive",
        },
    )
