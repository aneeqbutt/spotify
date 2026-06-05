from datetime import datetime
from sqlalchemy import String, Integer, Float, Boolean, DateTime, Text, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from backend.database.db import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    tasks: Mapped[list["Task"]] = relationship("Task", back_populates="user")
    sessions: Mapped[list["Session"]] = relationship("Session", back_populates="user")


class Device(Base):
    __tablename__ = "devices"

    device_id: Mapped[str] = mapped_column(String(100), primary_key=True)
    device_auth_token: Mapped[str] = mapped_column(String(255), nullable=False)
    app_version: Mapped[str] = mapped_column(String(50), nullable=True)
    capabilities: Mapped[str] = mapped_column(Text, nullable=True)  # JSON string
    status: Mapped[str] = mapped_column(String(20), default="offline")  # online / offline
    last_seen: Mapped[datetime] = mapped_column(DateTime, nullable=True)

    runs: Mapped[list["Run"]] = relationship("Run", back_populates="device")
    sessions: Mapped[list["Session"]] = relationship("Session", back_populates="device")


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), nullable=False)
    task_name: Mapped[str] = mapped_column(String(200), nullable=False)
    action_type: Mapped[str] = mapped_column(String(100), nullable=False)
    search_query: Mapped[str] = mapped_column(String(255), nullable=True)
    action_params: Mapped[str] = mapped_column(Text, nullable=True)  # JSON string
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    user: Mapped["User"] = relationship("User", back_populates="tasks")
    runs: Mapped[list["Run"]] = relationship("Run", back_populates="task", cascade="all, delete-orphan")
    sessions: Mapped[list["Session"]] = relationship("Session", back_populates="task", cascade="all, delete-orphan")


class Run(Base):
    __tablename__ = "runs"

    run_id: Mapped[str] = mapped_column(String(36), primary_key=True)  # UUID
    task_id: Mapped[int] = mapped_column(Integer, ForeignKey("tasks.id"), nullable=False)
    device_id: Mapped[str] = mapped_column(String(100), ForeignKey("devices.device_id"), nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="RUNNING")  # RUNNING / SUCCESS / FAILED
    start_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    end_time: Mapped[datetime] = mapped_column(DateTime, nullable=True)

    task: Mapped["Task"] = relationship("Task", back_populates="runs")
    device: Mapped["Device"] = relationship("Device", back_populates="runs")
    events: Mapped[list["RunEvent"]] = relationship("RunEvent", back_populates="run", cascade="all, delete-orphan")


class RunEvent(Base):
    __tablename__ = "run_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    run_id: Mapped[str] = mapped_column(String(36), ForeignKey("runs.run_id"), nullable=False)
    command_id: Mapped[str] = mapped_column(String(36), nullable=False)
    step_id: Mapped[str] = mapped_column(String(100), nullable=True)
    step_name: Mapped[str] = mapped_column(String(200), nullable=True)
    event_type: Mapped[str] = mapped_column(String(50), nullable=False)  # STEP_STARTED / STEP_OK / STEP_FAILED / COMMAND_DONE
    reason_code: Mapped[str] = mapped_column(String(100), nullable=True)
    payload: Mapped[str] = mapped_column(Text, nullable=True)  # JSON string
    timestamp: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

    run: Mapped["Run"] = relationship("Run", back_populates="events")


class Session(Base):
    __tablename__ = "sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id"), nullable=False)
    device_id: Mapped[str] = mapped_column(String(100), ForeignKey("devices.device_id"), nullable=False)
    task_id: Mapped[int] = mapped_column(Integer, ForeignKey("tasks.id"), nullable=False)  # first task (anchor for FK)
    start_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    end_time: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    status: Mapped[str] = mapped_column(String(20), default="scheduled")  # scheduled / running / done / failed

    user: Mapped["User"] = relationship("User", back_populates="sessions")
    device: Mapped["Device"] = relationship("Device", back_populates="sessions")
    task: Mapped["Task"] = relationship("Task", back_populates="sessions")
    session_tasks: Mapped[list["SessionTask"]] = relationship(
        "SessionTask", back_populates="session",
        order_by="SessionTask.position",
        cascade="all, delete-orphan",
    )


class SessionTask(Base):
    """Ordered task slot within a session. One row per task in the sequence."""
    __tablename__ = "session_tasks"

    id:         Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    session_id: Mapped[int] = mapped_column(Integer, ForeignKey("sessions.id"), nullable=False)
    task_id:    Mapped[int] = mapped_column(Integer, ForeignKey("tasks.id"), nullable=False)
    position:   Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    session: Mapped["Session"] = relationship("Session", back_populates="session_tasks")
    task:    Mapped["Task"]    = relationship("Task")
