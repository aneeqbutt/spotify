"""
routers/devices.py — Device management endpoints

POST   /devices/register   — APK registers itself (device_id + shared secret)
GET    /devices/           — list all registered devices (authenticated)
GET    /devices/{id}       — get a single device's status
DELETE /devices/{id}       — remove a device and all its related data
"""

import logging
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, delete

from backend.database.db import get_db
from backend.models.tables import Device, User, Run, RunEvent, Session, SessionTask
from backend.auth.security import get_current_user
from backend.config import settings
from backend.websocket.manager import manager

logger = logging.getLogger("devices")
router = APIRouter(prefix="/devices", tags=["Devices"])


# ── Register ──────────────────────────────────────────────────────────────────
@router.post("/register", status_code=status.HTTP_201_CREATED)
async def register_device(
    device_id: str,
    shared_secret: str,
    app_version: str = "1.0.0",
    db: AsyncSession = Depends(get_db),
):
    """
    Called by the APK on first launch (and on reconnect if not yet registered).

    The shared secret must match DEVICE_SHARED_SECRET in .env — this prevents
    random devices from registering. In production this would be a per-device
    secret provisioned at build time.

    If the device_id already exists, we just update its app_version and return OK.
    """
    if shared_secret != settings.DEVICE_SHARED_SECRET:
        logger.warning(f"[DEVICE] Bad shared secret from device_id={device_id}")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid device secret")

    result = await db.execute(select(Device).where(Device.device_id == device_id))
    device = result.scalar_one_or_none()

    if device:
        device.app_version = app_version
        await db.commit()
        logger.info(f"[DEVICE] Re-registered: device_id={device_id}")
        return {"device_id": device_id, "status": "updated"}

    device = Device(
        device_id=device_id,
        device_auth_token=shared_secret,
        app_version=app_version,
        status="offline",
    )
    db.add(device)
    await db.commit()
    logger.info(f"[DEVICE] Registered new device: device_id={device_id}")
    return {"device_id": device_id, "status": "registered"}


# ── List all devices ──────────────────────────────────────────────────────────
@router.get("/")
async def list_devices(
    db: AsyncSession = Depends(get_db),
    _: User = Depends(get_current_user),
):
    """
    Return all registered devices with their current online/offline status.
    Used by the React dashboard to show device cards.
    """
    result = await db.execute(select(Device))
    devices = result.scalars().all()
    return [
        {
            "device_id": d.device_id,
            # Live WebSocket registry is the authoritative source — avoids stale DB
            # values after backend restarts or disconnect race conditions.
            "status": "online" if manager.is_connected(d.device_id) else "offline",
            "app_version": d.app_version,
            # Append Z so browsers treat this as UTC and convert to local time.
            # Without Z, JavaScript's new Date() treats the string as local time,
            # displaying the UTC value directly (e.g. 11:46 AM instead of 4:46 PM UTC+5).
            "last_seen": (d.last_seen.isoformat() + "Z") if d.last_seen else None,
        }
        for d in devices
    ]


# ── Single device ─────────────────────────────────────────────────────────────
@router.get("/{device_id}")
async def get_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
    _: User = Depends(get_current_user),
):
    """Return a single device's full status. Used by the command sender to verify the device is online."""
    result = await db.execute(select(Device).where(Device.device_id == device_id))
    device = result.scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    return {
        "device_id": device.device_id,
        "status": "online" if manager.is_connected(device.device_id) else "offline",
        "app_version": device.app_version,
        "capabilities": device.capabilities,
        "last_seen": (device.last_seen.isoformat() + "Z") if device.last_seen else None,
    }


# ── Delete device ─────────────────────────────────────────────────────────────
@router.delete("/{device_id}", status_code=status.HTTP_200_OK)
async def delete_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
    _: User = Depends(get_current_user),
):
    """
    Remove a device and all its associated data (runs, run_events, sessions).
    If the device is currently online the WebSocket connection is NOT forcibly
    closed — it will simply re-register on its next DEVICE_HELLO. To prevent
    re-registration you must uninstall the APK or keep the phone offline.
    """
    result = await db.execute(select(Device).where(Device.device_id == device_id))
    device = result.scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    # Collect run_ids for this device so we can delete run_events first
    run_result = await db.execute(select(Run.run_id).where(Run.device_id == device_id))
    run_ids = [r for r in run_result.scalars().all()]

    if run_ids:
        await db.execute(delete(RunEvent).where(RunEvent.run_id.in_(run_ids)))
        await db.execute(delete(Run).where(Run.device_id == device_id))

    # Collect session_ids for this device so we can delete session_tasks first
    session_result = await db.execute(select(Session.id).where(Session.device_id == device_id))
    session_ids = [s for s in session_result.scalars().all()]

    if session_ids:
        await db.execute(delete(SessionTask).where(SessionTask.session_id.in_(session_ids)))
        await db.execute(delete(Session).where(Session.device_id == device_id))

    await db.execute(delete(Device).where(Device.device_id == device_id))
    await db.commit()

    logger.info(f"[DEVICE] Deleted device={device_id} runs={len(run_ids)} sessions={len(session_ids)}")
    return {"device_id": device_id, "status": "deleted"}
