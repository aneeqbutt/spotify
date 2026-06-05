"""
routers/tasks.py — Task management endpoints

POST /tasks/         — create a new automation task
GET  /tasks/         — list all tasks for the current user
GET  /tasks/{id}     — get a single task
DELETE /tasks/{id}   — delete a task
"""

import logging
import json
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from backend.database.db import get_db
from backend.models.tables import Task, User
from backend.models.schemas import TaskCreate, TaskOut
from backend.auth.security import get_current_user

logger = logging.getLogger("tasks")
router = APIRouter(prefix="/tasks", tags=["Tasks"])


# ── Create task ───────────────────────────────────────────────────────────────
@router.post("/", response_model=TaskOut, status_code=status.HTTP_201_CREATED)
async def create_task(
    body: TaskCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """
    Save a new automation task for the current user.

    action_type must be one of:
      SEARCH_AND_PLAY | LIKE_CURRENT_TRACK | FOLLOW_ARTIST | SKIP_TRACK

    action_params is stored as a JSON string — it holds action-specific
    parameters (e.g. {"query": "Blinding Lights", "artist": "The Weeknd"}).
    """
    task = Task(
        user_id=current_user.id,
        task_name=body.task_name,
        action_type=body.action_type,
        search_query=body.search_query,
        action_params=json.dumps(body.action_params) if body.action_params else None,
    )
    db.add(task)
    await db.commit()
    await db.refresh(task)
    logger.info(f"[TASK] Created task id={task.id} action={task.action_type} user={current_user.username}")
    return task


# ── List tasks ────────────────────────────────────────────────────────────────
@router.get("/", response_model=list[TaskOut])
async def list_tasks(
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Return all tasks belonging to the current user."""
    result = await db.execute(select(Task).where(Task.user_id == current_user.id))
    return result.scalars().all()


# ── Get single task ───────────────────────────────────────────────────────────
@router.get("/{task_id}", response_model=TaskOut)
async def get_task(
    task_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Return a single task. Returns 404 if not found or not owned by current user."""
    result = await db.execute(
        select(Task).where(Task.id == task_id, Task.user_id == current_user.id)
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return task


# ── Delete task ───────────────────────────────────────────────────────────────
@router.delete("/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(
    task_id: int,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Delete a task. Only the owner can delete their own tasks."""
    result = await db.execute(
        select(Task).where(Task.id == task_id, Task.user_id == current_user.id)
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    await db.delete(task)
    await db.commit()
    logger.info(f"[TASK] Deleted task id={task_id} user={current_user.username}")
