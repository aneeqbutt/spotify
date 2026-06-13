"""
routers/commands.py — Command dispatch (Run Now)

POST /commands/run          — send a task to a connected device immediately
GET  /commands/runs/{id}    — get a run's status and events
GET  /commands/runs         — list recent runs for the current user
"""

import json
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, desc

from backend.database.db import get_db
from backend.models.tables import Task, Device, Run, RunEvent, User
from backend.models.schemas import SendCommandRequest
from backend.auth.security import get_current_user
from backend.websocket.manager import manager

logger = logging.getLogger("commands")
router = APIRouter(prefix="/commands", tags=["Commands"])

# How long the APK has to execute the command before it expires (ms)
COMMAND_TTL_MS = 180_000  # 3 minutes


# ── Run Now ───────────────────────────────────────────────────────────────────
@router.post("/run", status_code=status.HTTP_202_ACCEPTED)
async def run_now(
    body: SendCommandRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    Send a task to a device for immediate execution.

    Flow:
    1. Validate the task belongs to the current user
    2. Validate the device is registered
    3. Check the device is currently connected via WebSocket
    4. Create a Run record in DB (status=RUNNING)
    5. Build command payload and push it via WebSocket
    6. Return the run_id so the frontend can poll for progress

    The APK receives the command, executes it step by step, and sends back
    STEP_STARTED / STEP_OK / STEP_FAILED / COMMAND_DONE events.
    """

    # 1. Fetch and validate task
    task_result = await db.execute(
        select(Task).where(Task.id == body.task_id, Task.user_id == current_user.id)
    )
    task = task_result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    # 2. Fetch and validate device
    device_result = await db.execute(
        select(Device).where(Device.device_id == body.device_id)
    )
    device = device_result.scalar_one_or_none()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    # 3. Check device is online
    if not manager.is_connected(body.device_id):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=f"Device '{body.device_id}' is not currently connected"
        )

    # 4. Create Run record
    run_id = str(uuid.uuid4())
    run = Run(
        run_id=run_id,
        task_id=task.id,
        device_id=body.device_id,
        status="RUNNING",
        start_time=datetime.now(timezone.utc),
    )
    db.add(run)
    await db.commit()

    # 5. Build command payload (matches the Key Contracts spec)
    params = {}
    if task.action_params:
        try:
            params = json.loads(task.action_params)
        except json.JSONDecodeError:
            pass
    if task.search_query:
        params["query"] = task.search_query
        # Some executors use a more specific param name — set both so either works
        if task.action_type == "FOLLOW_ARTIST":
            params["artist_name"] = task.search_query
        elif task.action_type in ("ADD_TO_PLAYLIST", "CREATE_PLAYLIST"):
            # These executors read "playlist_name" — map search_query to it
            params["playlist_name"] = task.search_query

    command_payload = {
        "type": "COMMAND",
        "command_id": str(uuid.uuid4()),
        "run_id": run_id,
        "task_id": task.id,
        "action_type": task.action_type,
        "params": params,
        "issued_at": datetime.now(timezone.utc).isoformat(),
        "ttl_ms": COMMAND_TTL_MS,
    }

    # 6. Push to device via WebSocket
    # send_command never raises — returns False on any failure (offline, broken socket).
    # We catch any unexpected exception here too and always close out the run record
    # so it never gets stuck in RUNNING state waiting for the 5-minute expiry.
    try:
        sent = await manager.send_command(body.device_id, command_payload)
    except Exception as exc:
        logger.error(f"[CMD] Unexpected send error for run {run_id}: {exc}")
        sent = False

    if not sent:
        run.status   = "FAILED"
        run.end_time = datetime.now(timezone.utc)
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Device disconnected before command could be delivered. "
                   "Wait for the device to reconnect and try again."
        )

    query = params.get("query") or params.get("artist_name") or params.get("playlist_name")
    logger.info(
        f"[CMD] Run dispatched: run_id={run_id} task={task.action_type} "
        f"query={query!r} device={body.device_id}"
    )
    return {
        "run_id": run_id,
        "task_id": task.id,
        "device_id": body.device_id,
        "action_type": task.action_type,
        "status": "RUNNING",
    }


# ── Get run status ────────────────────────────────────────────────────────────
@router.get("/runs/{run_id}")
async def get_run(
    run_id: str,
    db: AsyncSession = Depends(get_db),
    _: User = Depends(get_current_user),
):
    """
    Return a run's current status and all its step events.

    The React dashboard polls this every 2 seconds to display a live
    progress log while the APK is executing.
    """
    run_result = await db.execute(select(Run).where(Run.run_id == run_id))
    run = run_result.scalar_one_or_none()
    if not run:
        raise HTTPException(status_code=404, detail="Run not found")

    events_result = await db.execute(
        select(RunEvent)
        .where(RunEvent.run_id == run_id)
        .order_by(RunEvent.timestamp)
    )
    events = events_result.scalars().all()

    return {
        "run_id": run.run_id,
        "task_id": run.task_id,
        "device_id": run.device_id,
        "status": run.status,
        "start_time": run.start_time,
        "end_time": run.end_time,
        "events": [
            {
                "event_type": e.event_type,
                "step_id": e.step_id,
                "step_name": e.step_name,
                "reason_code": e.reason_code,
                "timestamp": e.timestamp,
            }
            for e in events
        ],
    }


# ── List recent runs ──────────────────────────────────────────────────────────
@router.get("/runs")
async def list_runs(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
    limit: int = 20,
):
    """Return the most recent runs for tasks owned by the current user."""
    result = await db.execute(
        select(Run)
        .join(Task, Run.task_id == Task.id)
        .where(Task.user_id == current_user.id)
        .order_by(desc(Run.start_time))
        .limit(limit)
    )
    runs = result.scalars().all()
    return [
        {
            "run_id": r.run_id,
            "task_id": r.task_id,
            "device_id": r.device_id,
            "status": r.status,
            "start_time": r.start_time,
            "end_time": r.end_time,
        }
        for r in runs
    ]
