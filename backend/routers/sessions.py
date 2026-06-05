"""
routers/sessions.py — Session-based scheduled automation

POST /sessions/             — create a scheduled session with an ordered task list
GET  /sessions/             — list sessions for the current user
POST /sessions/{id}/stop    — stop a scheduled or running session
"""

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from sqlalchemy import select, desc

from backend.database.db import get_db
from backend.models.tables import Session as SessionModel, SessionTask, Task, Device, User
from backend.models.schemas import SessionCreate, SessionOut
from backend.auth.security import get_current_user
from backend.tasks.session_scheduler import scheduler

logger = logging.getLogger("sessions")
router = APIRouter(prefix="/sessions", tags=["Sessions"])


def _to_naive_utc(dt: datetime) -> datetime:
    """Strip timezone and return naive UTC (for consistent SQLite storage)."""
    if dt.tzinfo is not None:
        return dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt


def _fmt_utc(dt: datetime) -> str:
    """Format a naive UTC datetime as an ISO string with Z suffix.
    Without the Z, browsers treat the string as local time, displaying
    the wrong hour to any user not in UTC."""
    s = dt.isoformat(timespec="seconds")
    return s if s.endswith("Z") else s + "Z"


def _session_to_out(sess: SessionModel) -> dict:
    """Serialize a Session ORM object to a SessionOut-compatible dict."""
    task_ids = [st.task_id for st in sorted(sess.session_tasks, key=lambda x: x.position)]
    return {
        "id":         sess.id,
        "device_id":  sess.device_id,
        "task_ids":   task_ids,
        "start_time": _fmt_utc(sess.start_time),
        "end_time":   _fmt_utc(sess.end_time),
        "status":     sess.status,
    }


@router.post("/", status_code=status.HTTP_201_CREATED)
async def create_session(
    body: SessionCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    Create a scheduled session with an ordered task sequence.
    The scheduler will execute the tasks in order on each loop iteration
    until end_time is reached or the session is stopped.
    """
    if not body.task_ids:
        raise HTTPException(status_code=400, detail="task_ids must contain at least one task")

    # Validate all tasks exist and belong to the current user
    for tid in body.task_ids:
        result = await db.execute(
            select(Task).where(Task.id == tid, Task.user_id == current_user.id)
        )
        if not result.scalar_one_or_none():
            raise HTTPException(status_code=404, detail=f"Task {tid} not found")

    # Validate device exists
    device_result = await db.execute(
        select(Device).where(Device.device_id == body.device_id)
    )
    if not device_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail="Device not found")

    # Normalise to naive UTC for storage.
    # Pydantic parses ISO strings with Z/+HH:MM as timezone-aware datetimes.
    # SQLite stores datetimes as naive strings ('YYYY-MM-DD HH:MM:SS'); storing
    # an aware datetime produces broken string comparisons in the scheduler.
    start = _to_naive_utc(body.start_time)
    end   = _to_naive_utc(body.end_time)

    now_naive = datetime.utcnow()

    if start >= end:
        raise HTTPException(status_code=400, detail="start_time must be before end_time")
    if end <= now_naive:
        raise HTTPException(status_code=400, detail="end_time is already in the past")

    # Create session — store naive UTC datetimes (SQLite has no timezone type)
    sess = SessionModel(
        user_id=current_user.id,
        device_id=body.device_id,
        task_id=body.task_ids[0],
        start_time=start,
        end_time=end,
        status="scheduled",
    )
    db.add(sess)
    await db.flush()  # get sess.id without full commit

    # Create ordered SessionTask rows
    for position, tid in enumerate(body.task_ids):
        db.add(SessionTask(session_id=sess.id, task_id=tid, position=position))

    await db.commit()

    # Reload with session_tasks populated
    result = await db.execute(
        select(SessionModel)
        .options(selectinload(SessionModel.session_tasks))
        .where(SessionModel.id == sess.id)
    )
    sess = result.scalar_one()

    # Always spawn the session task immediately.
    # If start_time is in the future the task sleeps internally until that moment,
    # giving exact start-time accuracy instead of up-to-30s scan-loop jitter.
    scheduler.schedule(sess.id)
    delay_s = max(0.0, (start - now_naive).total_seconds())
    logger.info(
        "[SESSIONS] Session %d created — task spawned (starts in %.0fs at %s)",
        sess.id, delay_s, body.start_time.isoformat(),
    )

    return _session_to_out(sess)


@router.get("/")
async def list_sessions(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """List the 20 most recent sessions for the current user, including their task sequences."""
    result = await db.execute(
        select(SessionModel)
        .options(selectinload(SessionModel.session_tasks))
        .where(SessionModel.user_id == current_user.id)
        .order_by(desc(SessionModel.start_time))
        .limit(20)
    )
    sessions = result.scalars().all()
    return [_session_to_out(s) for s in sessions]


@router.post("/{session_id}/stop", status_code=status.HTTP_200_OK)
async def stop_session(
    session_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Stop a scheduled or running session immediately."""
    result = await db.execute(
        select(SessionModel).where(
            SessionModel.id == session_id,
            SessionModel.user_id == current_user.id,
        )
    )
    sess = result.scalar_one_or_none()
    if not sess:
        raise HTTPException(status_code=404, detail="Session not found")

    if sess.status not in ("scheduled", "running"):
        raise HTTPException(
            status_code=400,
            detail=f"Session is already '{sess.status}' and cannot be stopped",
        )

    sess.status = "done"
    await db.commit()

    scheduler.cancel(session_id)

    logger.info("[SESSIONS] Session %d stopped by user %s", session_id, current_user.username)
    return {"message": "Session stopped"}
