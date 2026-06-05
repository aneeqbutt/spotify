from pydantic import BaseModel
from datetime import datetime
from typing import Optional, Any


# ── Auth ──────────────────────────────────────────────────────────────────────

class LoginRequest(BaseModel):
    username: str
    password: str

class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


# ── Device ────────────────────────────────────────────────────────────────────

class DeviceOut(BaseModel):
    device_id: str
    app_version: Optional[str]
    status: str
    last_seen: Optional[datetime]

    class Config:
        from_attributes = True


# ── Task ──────────────────────────────────────────────────────────────────────

class TaskCreate(BaseModel):
    task_name: str
    action_type: str
    search_query: Optional[str] = None
    action_params: Optional[dict] = None

class TaskOut(BaseModel):
    id: int
    task_name: str
    action_type: str
    search_query: Optional[str]
    created_at: datetime

    class Config:
        from_attributes = True


# ── Command ───────────────────────────────────────────────────────────────────

class SendCommandRequest(BaseModel):
    task_id: int
    device_id: str


# ── Run ───────────────────────────────────────────────────────────────────────

class RunOut(BaseModel):
    run_id: str
    task_id: int
    device_id: str
    status: str
    start_time: datetime
    end_time: Optional[datetime]

    class Config:
        from_attributes = True


# ── Session ───────────────────────────────────────────────────────────────────

class SessionCreate(BaseModel):
    device_id: str
    task_ids: list[int]   # ordered task sequence — at least one entry required
    start_time: datetime
    end_time: datetime

class SessionOut(BaseModel):
    id: int
    device_id: str
    task_ids: list[int]   # ordered task IDs in this session's sequence
    start_time: datetime
    end_time: datetime
    status: str
