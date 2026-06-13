"""
session_scheduler.py — Session-based automation scheduler

Each session holds an ordered list of tasks (stored in session_tasks).
Per iteration the scheduler executes every task in sequence, then waits a
cooldown that depends on the task type, before repeating.

Timeline for one iteration:
  SEARCH_AND_PLAY "ace"  → COMMAND_DONE → [play_duration_s wait, e.g. 180s]
  LIKE_CURRENT_TRACK     → COMMAND_DONE → [instant: 2-5s]
  SKIP_TRACK             → COMMAND_DONE → [instant: 2-5s]
  PLAY_FROM_PLAYLIST … → COMMAND_DONE → [play_duration_s wait]
                     └── inter-iteration cooldown (60-300s) ──┘

PLAYBACK tasks (SEARCH_AND_PLAY, PLAY_FROM_ALBUM, PLAY_FROM_PLAYLIST):
  After COMMAND_DONE the music is just STARTING.  The scheduler waits
  play_duration_s (from action_params, default 180s) so the track actually
  plays before moving on.  The wait is interruptible every 30s so a manual
  stop or session end-time is respected.

INSTANT tasks (LIKE_CURRENT_TRACK, SKIP_TRACK, FOLLOW_ARTIST, etc.):
  Action completes in a few seconds.  Only a short 2-5s gap is added.
"""

import asyncio
import logging
import random
import uuid
from datetime import datetime, timezone, timedelta

from sqlalchemy.ext.asyncio import AsyncEngine, async_sessionmaker
from sqlalchemy import select

from backend.models.tables import Session as SessionModel, SessionTask, Task, Run
from backend.websocket.manager import manager
from backend.websocket.broadcaster import broadcaster

logger = logging.getLogger("session_scheduler")

# ── Constants ─────────────────────────────────────────────────────────────────
SCHEDULER_INTERVAL_S     = 30     # background scan frequency
COOLDOWN_MIN_S           = 30     # min cooldown between full iterations
COOLDOWN_MAX_S           = 90     # max cooldown between full iterations (was 300 — too long for short sessions)
RUN_WAIT_TIMEOUT_S       = 180    # max time to wait for COMMAND_DONE (matches command TTL)
RUN_POLL_INTERVAL_S      = 5      # how often to poll DB while waiting for a run
MAX_CONSECUTIVE_FAILURES = 5      # abort session after this many back-to-back COMMAND failures
# NOTE: device-offline is NOT counted as a consecutive failure — it is a pause,
# not a broken command. The session waits patiently for the device to reconnect.
MAX_OFFLINE_WAIT_S       = 300   # wait up to 5 min for device to come back per dispatch
OFFLINE_POLL_S           = 5     # check every 5s during the offline wait
COMMAND_TTL_MS           = 180_000

# Playback tasks: music is still playing when COMMAND_DONE arrives.
# The scheduler waits play_duration_s before dispatching the next task.
PLAYBACK_ACTIONS     = {'SEARCH_AND_PLAY', 'PLAY_FROM_ALBUM', 'PLAY_FROM_PLAYLIST'}
DEFAULT_PLAY_DURATION_S = 86400  # play until session ends (overridable per task via action_params)

# Tasks that wait for the APK to report track-end via PLAYBACK_FINISHED event.
# All three executors call startPlaybackMonitor() — the event-driven path fires when
# the track title changes (next song started = previous song done).
# PLAY_FROM_ALBUM was previously excluded, causing the scheduler to fall back to
# _interruptible_sleep(86400) — a 24-hour freeze until session end_time.
TRACK_FINISH_ACTIONS = {'SEARCH_AND_PLAY', 'PLAY_FROM_PLAYLIST', 'PLAY_FROM_ALBUM'}

# Instant tasks: action finishes in seconds; only a short gap is needed.
INSTANT_TASK_GAP_MIN_S = 5    # was 2 — give 5-10s so LIKE/SKIP fire clearly mid-playback
INSTANT_TASK_GAP_MAX_S = 10   # was 5

# ── Playback finish event bus ─────────────────────────────────────────────────
# Maps run_id → asyncio.Event.  Set by ws.py when APK sends PLAYBACK_FINISHED.

_playback_events: dict[str, asyncio.Event] = {}

# ── Run → Session mapping ─────────────────────────────────────────────────────
# Maps run_id → session_id for every run dispatched by the scheduler.
# ws.py reads this when broadcasting run_event so the frontend can include
# session_id in each event — letting the dashboard re-associate if it missed
# the session_run SSE event (e.g. due to a brief SSE reconnect).

_run_to_session: dict[str, int] = {}


def get_session_id_for_run(run_id: str) -> int | None:
    """Return the session_id that dispatched this run, or None for manual runs."""
    return _run_to_session.get(run_id)


def _cleanup_run_to_session(session_id: int) -> None:
    """Remove all run_id → session_id entries for a finished session."""
    stale = [rid for rid, sid in _run_to_session.items() if sid == session_id]
    for rid in stale:
        _run_to_session.pop(rid, None)


def register_playback_wait(run_id: str) -> asyncio.Event:
    """Create and store an Event for this run.  Called from _wait_for_playback_finish."""
    event = asyncio.Event()
    _playback_events[run_id] = event
    return event


def notify_playback_finished(run_id: str) -> bool:
    """Called by ws.py when APK sends PLAYBACK_FINISHED.  Returns True if a waiter existed."""
    event = _playback_events.get(run_id)
    if event:
        event.set()
        logger.info("[SCHED] PLAYBACK_FINISHED event set for run=%s", run_id)
        return True
    logger.debug("[SCHED] PLAYBACK_FINISHED for unregistered run=%s (ignored)", run_id)
    return False


def _unregister_playback_wait(run_id: str) -> None:
    _playback_events.pop(run_id, None)


# ── SessionScheduler singleton ────────────────────────────────────────────────

class SessionScheduler:
    def __init__(self) -> None:
        self._tasks: dict[int, asyncio.Task] = {}
        self._engine: AsyncEngine | None = None

    def init(self, engine: AsyncEngine) -> None:
        self._engine = engine

    def schedule(self, session_id: int) -> asyncio.Task:
        self.cancel(session_id)
        task = asyncio.create_task(
            _run_session_task(session_id, self._engine),
            name=f"session_{session_id}",
        )
        self._tasks[session_id] = task
        logger.info("[SCHED] Session %d task spawned", session_id)
        return task

    def cancel(self, session_id: int) -> None:
        task = self._tasks.pop(session_id, None)
        if task and not task.done():
            task.cancel()
            logger.info("[SCHED] Session %d task cancelled", session_id)

    def is_active(self, session_id: int) -> bool:
        task = self._tasks.get(session_id)
        return task is not None and not task.done()


scheduler = SessionScheduler()


# ── Background scan loop ──────────────────────────────────────────────────────

async def session_scheduler_loop(engine: AsyncEngine) -> None:
    logger.info("[SCHED] Session scheduler started (interval=%ds)", SCHEDULER_INTERVAL_S)
    while True:
        try:
            await _check_and_start_sessions(engine)
        except Exception as exc:
            logger.error("[SCHED] Scheduler scan error: %s", exc)
        await asyncio.sleep(SCHEDULER_INTERVAL_S)


async def _check_and_start_sessions(engine: AsyncEngine) -> None:
    # Use naive UTC to match SQLite's storage format — avoids broken lexicographic
    # comparisons that occur when aware datetimes are converted to "YYYY-MM-DDTHH:MM+00:00".
    now = datetime.utcnow()
    DbSession = async_sessionmaker(engine, expire_on_commit=False)

    async with DbSession() as db:
        result = await db.execute(
            select(SessionModel).where(
                SessionModel.status.in_(["scheduled", "running"]),
                SessionModel.start_time <= now,
            )
        )
        due = result.scalars().all()

    for s in due:
        if _to_naive_utc(s.end_time) <= now:
            await _set_session_status(s.id, "done", engine)
            logger.warning("[SCHED] Session %d past end_time on arrival — marked done", s.id)
            continue
        if not scheduler.is_active(s.id):
            scheduler.schedule(s.id)


# ── Per-session loop task ─────────────────────────────────────────────────────

async def _run_session_task(session_id: int, engine: AsyncEngine) -> None:
    """
    Drives one session from start to finish.
    Each iteration executes the full task sequence in order, then waits a
    random cooldown before the next pass.
    """
    try:
        # Wait until start_time if the session was spawned ahead of schedule.
        # This gives exact start-time accuracy regardless of when schedule() was called.
        sess = await _load_session(session_id, engine)
        device_id: str | None = sess.device_id if sess else None
        if sess:
            now = datetime.utcnow()
            start_naive = _to_naive_utc(sess.start_time)
            if start_naive > now:
                wait_s = (start_naive - now).total_seconds()
                logger.info(
                    "[SCHED] Session %d: start_time in %.0fs — sleeping until %s",
                    session_id, wait_s, sess.start_time.isoformat(),
                )
                await asyncio.sleep(wait_s)

        await _set_session_status(session_id, "running", engine)
        await broadcaster.publish({
            "type": "session_status", "session_id": session_id, "status": "running",
        })

        consecutive_failures = 0

        while True:
            # ── Check session is still active ─────────────────────────────────
            sess = await _load_session(session_id, engine)
            if not sess or sess.status != "running":
                logger.info("[SCHED] Session %d stopped externally", session_id)
                break

            now = datetime.utcnow()   # naive UTC — matches SQLite storage
            if now >= _to_naive_utc(sess.end_time):
                logger.info("[SCHED] Session %d reached end_time", session_id)
                break

            # ── Load the ordered task sequence ────────────────────────────────
            task_ids = await _load_task_sequence(session_id, engine)
            if not task_ids:
                logger.error("[SCHED] Session %d has no tasks — aborting", session_id)
                break

            logger.info(
                "[SCHED] Session %d starting iteration: %d task(s) %s",
                session_id, len(task_ids), task_ids,
            )

            # ── Execute each task in sequence ─────────────────────────────────
            #
            # LAZY PLAYBACK WAIT MODEL
            # ────────────────────────
            # After a PLAYBACK task (SEARCH_AND_PLAY, PLAY_FROM_ALBUM, etc.) the
            # scheduler does NOT block immediately. It stores the run_id as
            # "pending_playback" and continues to the next task.
            #
            # Before dispatching the NEXT PLAYBACK task, it waits for the stored
            # PLAYBACK_FINISHED — ensuring the previous song finishes before the
            # new one starts.
            #
            # Instant tasks (LIKE, SKIP, FOLLOW) execute mid-playback with a
            # 5-10s gap, which is exactly the desired behaviour:
            #
            #   play ace  ──────────────────────────▶ (playing…)
            #                   ├── follow drake (5-10s in)
            #                   └── wait for ace to finish
            #   play iceman  ◀── dispatched only after ace ends
            #                   ├── like (5-10s in)
            #                   └── skip (5-10s after like)
            #
            # At the END of the iteration the scheduler waits for the last
            # playback task before the cooldown — so the loop doesn't restart
            # while a song is still playing.

            iteration_failed       = False
            pending_playback_run_id    = None   # run_id of the last un-waited playback
            pending_playback_action    = None   # action type of that task
            pending_play_duration_s    = 0      # play_duration_s for timer-based tasks

            for i, task_id in enumerate(task_ids):
                # Re-check session and time before each task
                sess = await _load_session(session_id, engine)
                if not sess or sess.status != "running":
                    break
                if datetime.utcnow() >= _to_naive_utc(sess.end_time):
                    break

                # ── Pre-task: wait for previous song if THIS task is PLAYBACK ──
                # If there's a pending playback (previous song still playing) AND
                # this next task is also a PLAYBACK action, wait for the song to
                # finish before starting a new one. Instant tasks (LIKE/SKIP/FOLLOW)
                # skip this check — they are designed to fire mid-playback.
                if pending_playback_run_id is not None:
                    next_action = await _get_task_action(task_id, engine)
                    if next_action in PLAYBACK_ACTIONS:
                        tasks_remaining = len(task_ids) - i
                        logger.info(
                            "[SCHED] Session %d: next task is PLAYBACK (%s) — "
                            "waiting for current song to finish (run=%s)",
                            session_id, next_action, pending_playback_run_id,
                        )
                        if pending_playback_action in TRACK_FINISH_ACTIONS:
                            still_active = await _wait_for_playback_finish(
                                pending_playback_run_id, session_id, engine,
                                tasks_remaining=tasks_remaining,
                            )
                        else:
                            still_active = await _interruptible_sleep(
                                pending_play_duration_s, session_id, engine,
                            )
                        pending_playback_run_id = None
                        if not still_active:
                            break

                run_id, action_type, play_duration_s = await _dispatch_task(
                    session_id, task_id, sess.device_id, engine
                )

                # ── Device offline: wait patiently, do NOT count as failure ─────
                if run_id is None and not manager.is_connected(sess.device_id):
                    remaining_s = max(30.0, (
                        _to_naive_utc(sess.end_time) - datetime.utcnow()
                    ).total_seconds() - 30)
                    wait_cap = min(MAX_OFFLINE_WAIT_S, remaining_s)
                    logger.warning(
                        "[SCHED] Session %d: device %s offline — "
                        "pausing up to %.0fs (session has %.0fs left)",
                        session_id, sess.device_id, wait_cap, remaining_s,
                    )
                    device_came_back = await _wait_for_device_online(
                        sess.device_id, session_id, engine, wait_cap,
                    )
                    if device_came_back:
                        logger.info("[SCHED] Session %d: device back — retrying dispatch", session_id)
                        run_id, action_type, play_duration_s = await _dispatch_task(
                            session_id, task_id, sess.device_id, engine,
                        )
                    else:
                        logger.warning(
                            "[SCHED] Session %d: device still offline — skipping iteration",
                            session_id,
                        )
                        iteration_failed = True
                        break

                if run_id is None:
                    consecutive_failures += 1
                    logger.warning(
                        "[SCHED] Session %d task %d command failed (%d/%d)",
                        session_id, task_id, consecutive_failures, MAX_CONSECUTIVE_FAILURES,
                    )
                    iteration_failed = True
                    if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                        await _set_session_status(session_id, "failed", engine)
                        await broadcaster.publish({
                            "type": "session_status", "session_id": session_id, "status": "failed",
                        })
                        return
                    break

                final = await _wait_for_run(run_id, engine, session_id=session_id)
                logger.info(
                    "[SCHED] Session %d task %d (%s) done: run=%s final=%s",
                    session_id, task_id, action_type, run_id, final,
                )

                if final == "SUCCESS":
                    consecutive_failures = 0
                else:
                    consecutive_failures += 1
                    iteration_failed = True
                    if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                        await _set_session_status(session_id, "failed", engine)
                        await broadcaster.publish({
                            "type": "session_status", "session_id": session_id, "status": "failed",
                        })
                        return
                    ad_grace = random.randint(30, 50)
                    logger.info(
                        "[SCHED] Session %d: command failed — ad-grace wait %ds",
                        session_id, ad_grace,
                    )
                    await asyncio.sleep(ad_grace)

                # ── Post-task: store playback or apply instant gap ─────────────
                is_last_task = (i == len(task_ids) - 1)

                if action_type in PLAYBACK_ACTIONS and final == "SUCCESS":
                    # LAZY: store the run, let the NEXT task decide when to wait
                    pending_playback_run_id  = run_id
                    pending_playback_action  = action_type
                    pending_play_duration_s  = play_duration_s
                    logger.info(
                        "[SCHED] Session %d: playback task stored as pending "
                        "(run=%s) — instant tasks can now run mid-song",
                        session_id, run_id,
                    )

                elif not is_last_task:
                    # Instant task (LIKE, SKIP, FOLLOW, etc.)
                    # 5-10s gap so they execute clearly mid-playback
                    gap = random.randint(INSTANT_TASK_GAP_MIN_S, INSTANT_TASK_GAP_MAX_S)
                    logger.info(
                        "[SCHED] Session %d: instant task %s — %ds mid-playback gap",
                        session_id, action_type, gap,
                    )
                    await asyncio.sleep(gap)

            # ── After task loop: wait for last pending playback ────────────────
            # The last PLAYBACK task in the iteration stores its run_id but never
            # hits the "pre-task wait" block (nothing comes after it). Wait for it
            # here before the cooldown so the loop doesn't restart while a song is
            # still playing — which is what caused the "recommended song instead of
            # looping" problem (Spotify keeps playing its own queue during cooldown,
            # then the session's next "play X" command overrides it correctly).
            if pending_playback_run_id is not None:
                logger.info(
                    "[SCHED] Session %d: end of iteration — waiting for last "
                    "playback to finish (run=%s action=%s)",
                    session_id, pending_playback_run_id, pending_playback_action,
                )
                sess = await _load_session(session_id, engine)
                if sess and sess.status == "running" and \
                        datetime.utcnow() < _to_naive_utc(sess.end_time):
                    if pending_playback_action in TRACK_FINISH_ACTIONS:
                        await _wait_for_playback_finish(
                            pending_playback_run_id, session_id, engine, tasks_remaining=1,
                        )
                    else:
                        await _interruptible_sleep(pending_play_duration_s, session_id, engine)
                pending_playback_run_id = None

            # ── Re-check window before cooldown ───────────────────────────────
            sess = await _load_session(session_id, engine)
            if not sess or sess.status != "running":
                break
            if datetime.utcnow() >= _to_naive_utc(sess.end_time):
                break

            # ── Inter-iteration cooldown ──────────────────────────────────────
            cooldown = random.randint(COOLDOWN_MIN_S, COOLDOWN_MAX_S)
            logger.info(
                "[SCHED] Session %d ✓ iteration complete — cooldown %ds before next iteration "
                "(nothing will run on phone during this window)",
                session_id, cooldown,
            )
            await asyncio.sleep(cooldown)

        # Normal exit — end_time reached or session stopped externally.
        # Tell the APK to pause Spotify so music stops when the session window closes.
        if device_id and manager.is_connected(device_id):
            try:
                await manager.send_command(device_id, {
                    "type":       "CANCEL_COMMAND",
                    "session_id": session_id,
                })
                logger.info("[SCHED] Session %d: CANCEL_COMMAND sent to device %s", session_id, device_id)
            except Exception as exc:
                logger.warning("[SCHED] Session %d: CANCEL_COMMAND send failed: %s", session_id, exc)

        await _set_session_status(session_id, "done", engine)
        await broadcaster.publish({
            "type": "session_status", "session_id": session_id, "status": "done",
        })
        _cleanup_run_to_session(session_id)

    except asyncio.CancelledError:
        await _set_session_status(session_id, "cancelled", engine)
        await broadcaster.publish({
            "type": "session_status", "session_id": session_id, "status": "cancelled",
        })
        _cleanup_run_to_session(session_id)

    except Exception as exc:
        logger.exception("[SCHED] Session %d crashed: %s", session_id, exc)
        await _set_session_status(session_id, "failed", engine)
        await broadcaster.publish({
            "type": "session_status", "session_id": session_id, "status": "failed",
        })
        _cleanup_run_to_session(session_id)


# ── Task dispatch ─────────────────────────────────────────────────────────────

async def _dispatch_task(
    session_id: int, task_id: int, device_id: str, engine: AsyncEngine
) -> tuple[str, str, int] | tuple[None, None, None]:
    """
    Create a Run record and push a COMMAND for one task to the device.
    Returns (run_id, action_type, play_duration_s) on success.
    Returns (None, None, None) on any failure.
    play_duration_s is only meaningful for PLAYBACK_ACTIONS; always 0 for instant tasks.
    """
    import json as _json

    DbSession = async_sessionmaker(engine, expire_on_commit=False)

    async with DbSession() as db:
        result = await db.execute(select(Task).where(Task.id == task_id))
        task = result.scalar_one_or_none()
        if task is None:
            logger.error("[SCHED] Session %d: task %d not found", session_id, task_id)
            return None, None, None

        if not manager.is_connected(device_id):
            logger.warning("[SCHED] Session %d: device %s offline", session_id, device_id)
            return None, None, None

        run_id = str(uuid.uuid4())
        _run_to_session[run_id] = session_id   # track so ws.py can include session_id in run_event
        run = Run(
            run_id=run_id,
            task_id=task.id,
            device_id=device_id,
            status="RUNNING",
            start_time=datetime.utcnow(),
        )
        db.add(run)
        await db.commit()

        # Build command payload — mirrors commands.py logic
        params: dict = {}
        if task.action_params:
            try:
                params = _json.loads(task.action_params)
            except _json.JSONDecodeError:
                pass
        if task.search_query:
            params["query"] = task.search_query
            if task.action_type == "FOLLOW_ARTIST":
                params["artist_name"] = task.search_query
            elif task.action_type in ("ADD_TO_PLAYLIST", "CREATE_PLAYLIST"):
                params["playlist_name"] = task.search_query

        # issued_at MUST end with Z so java.time.Instant.parse() succeeds on the APK.
        issued_at = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

        command_payload = {
            "type":        "COMMAND",
            "command_id":  str(uuid.uuid4()),
            "run_id":      run_id,
            "task_id":     task.id,
            "action_type": task.action_type,
            "params":      params,
            "issued_at":   issued_at,
            "ttl_ms":      COMMAND_TTL_MS,
        }

        # Capture fields before the DB session closes (ORM objects detach on exit)
        action_type_snapshot   = task.action_type
        play_duration_snapshot = int(params.get("play_duration_s", DEFAULT_PLAY_DURATION_S)) \
                                 if task.action_type in PLAYBACK_ACTIONS else 0

    sent = await manager.send_command(device_id, command_payload)
    if not sent:
        DbSession2 = async_sessionmaker(engine, expire_on_commit=False)
        async with DbSession2() as db2:
            result = await db2.execute(select(Run).where(Run.run_id == run_id))
            orphan = result.scalar_one_or_none()
            if orphan:
                orphan.status   = "FAILED"
                orphan.end_time = datetime.utcnow()
                await db2.commit()
        logger.warning("[SCHED] Session %d: send_command failed run=%s", session_id, run_id)
        return None, None, None

    logger.info(
        "[SCHED] Session %d: dispatched task=%d action=%s run=%s device=%s",
        session_id, task_id, action_type_snapshot, run_id, device_id,
    )

    # Tell every open dashboard tab which run_id is now executing for this session.
    # The frontend uses this to wire the run-event log to the Sessions panel.
    await broadcaster.publish({
        "type":           "session_run",
        "session_id":     session_id,
        "run_id":         run_id,
        "action_type":    action_type_snapshot,
        "play_duration_s": play_duration_snapshot,
    })

    return run_id, action_type_snapshot, play_duration_snapshot


# ── Track-finish wait (APK-driven) ───────────────────────────────────────────


TRACK_FINISH_TIMEOUT_S = 270  # 4.5-min hard cap per track (covers 99% of pop songs)
# Reduced from 600s: 10 minutes wasted an entire short session when PLAYBACK_FINISHED
# never arrived (Samsung throttles the APK's postDelayed monitor during screen-off).


async def _wait_for_device_online(
    device_id: str,
    session_id: int,
    engine: AsyncEngine,
    max_wait_s: float,
) -> bool:
    """
    Poll until the device comes back online or the budget expires.

    Returns True  — device reconnected, proceed with dispatch.
    Returns False — timed out or session stopped/ended.

    Logs progress every 30s so the backend logs show what is happening
    instead of silently doing nothing.
    """
    elapsed = 0.0
    log_at  = 30   # next elapsed seconds at which to log progress

    while elapsed < max_wait_s:
        await asyncio.sleep(OFFLINE_POLL_S)
        elapsed += OFFLINE_POLL_S

        if manager.is_connected(device_id):
            logger.info(
                "[SCHED] Session %d: device %s reconnected after %.0fs",
                session_id, device_id, elapsed,
            )
            return True

        # Session stopped or end_time passed?
        sess = await _load_session(session_id, engine)
        if not sess or sess.status != "running":
            logger.info("[SCHED] Session %d: stopped during device-offline wait", session_id)
            return False
        if datetime.utcnow() >= _to_naive_utc(sess.end_time):
            logger.info("[SCHED] Session %d: end_time reached during device-offline wait", session_id)
            return False

        if elapsed >= log_at:
            logger.info(
                "[SCHED] Session %d: still waiting for device %s "
                "(%.0fs / %.0fs — tap the SpotifyBot notification on the phone)",
                session_id, device_id, elapsed, max_wait_s,
            )
            log_at += 30

    logger.warning(
        "[SCHED] Session %d: device %s did not reconnect within %.0fs",
        session_id, device_id, max_wait_s,
    )
    return False


async def _wait_for_playback_finish(
    run_id: str, session_id: int, engine: AsyncEngine,
    tasks_remaining: int = 1,
) -> bool:
    """
    Block until the APK sends PLAYBACK_FINISHED for this run_id, or the session
    is stopped / has reached its end_time.

    The APK polls position_text vs duration_text on the device every 5 s and
    fires PLAYBACK_FINISHED when the track ends.  This function waits on an
    asyncio.Event that ws.py sets when that message arrives.

    tasks_remaining — how many tasks (including this one) are still left to run
    in this iteration.  Used to compute a fair time budget so a single stalled
    track cannot starve the rest of the sequence.

    Returns True  — track finished (or fallback timeout), proceed to next task.
    Returns False — session stopped or end_time reached; break out of task loop.
    """
    event = register_playback_wait(run_id)
    CHECK_INTERVAL = 30
    total_waited_s = 0

    # ── Compute effective timeout ─────────────────────────────────────────────
    # Two limits, whichever is tighter:
    #   1. TRACK_FINISH_TIMEOUT_S — hard cap per track (4.5 min)
    #   2. Fair share — remaining session time divided among remaining tasks,
    #      minus 30s overhead buffer, so every task gets a turn.
    #
    # Example: 14-min session, 3 tasks remaining, 13 min left
    #   fair share = (13*60 - 30) / 3 = 250s
    #   effective  = min(270, 250) = 250s  ← no single track wastes the session
    sess = await _load_session(session_id, engine)
    if sess:
        remaining_s = max(0.0, (_to_naive_utc(sess.end_time) - datetime.utcnow()).total_seconds())
        fair_share  = max(60.0, (remaining_s - 30) / max(tasks_remaining, 1))
        effective_timeout = min(TRACK_FINISH_TIMEOUT_S, fair_share)
    else:
        effective_timeout = TRACK_FINISH_TIMEOUT_S

    logger.info(
        "[SCHED] Session %d: playback wait run=%s timeout=%.0fs "
        "(hard_cap=%ds tasks_remaining=%d)",
        session_id, run_id, effective_timeout, TRACK_FINISH_TIMEOUT_S, tasks_remaining,
    )

    try:
        while True:
            try:
                await asyncio.wait_for(event.wait(), timeout=CHECK_INTERVAL)
                logger.info(
                    "[SCHED] Session %d: PLAYBACK_FINISHED received run=%s after %ds",
                    session_id, run_id, total_waited_s,
                )
                return True
            except asyncio.TimeoutError:
                total_waited_s += CHECK_INTERVAL

            # Timed out — move on to keep the session progressing
            if total_waited_s >= effective_timeout:
                logger.warning(
                    "[SCHED] Session %d: PLAYBACK_FINISHED not received after %.0fs "
                    "— moving to next task (run=%s)",
                    session_id, effective_timeout, run_id,
                )
                return True

            # Session still alive?
            sess = await _load_session(session_id, engine)
            if not sess or sess.status != "running":
                logger.info("[SCHED] Session %d: stopped during track-finish wait", session_id)
                return False

            now     = datetime.utcnow()
            end_utc = _to_naive_utc(sess.end_time)
            if now >= end_utc:
                logger.info("[SCHED] Session %d: end_time reached during track-finish wait", session_id)
                return False

            # Recalculate fair-share on every check — shrinks as the session window closes.
            # If less than 60s remains, give up the wait immediately so the next task
            # (e.g. a LIKE or FOLLOW that takes only 2s) still has a chance to run.
            remaining_s = (end_utc - now).total_seconds()
            if remaining_s < 60:
                logger.info(
                    "[SCHED] Session %d: only %.0fs left — skipping playback wait to run "
                    "remaining tasks (run=%s)",
                    session_id, remaining_s, run_id,
                )
                return True

            logger.debug(
                "[SCHED] Session %d: waiting for track finish run=%s "
                "(%ds/%.0fs, %.0fs session remaining)",
                session_id, run_id, total_waited_s, effective_timeout, remaining_s,
            )

    finally:
        _unregister_playback_wait(run_id)


# ── Interruptible playback wait ───────────────────────────────────────────────

async def _interruptible_sleep(
    total_s: int, session_id: int, engine: AsyncEngine
) -> bool:
    """
    Sleep for total_s seconds while checking every 30s that the session is
    still active and within its time window.

    Returns True if the full duration elapsed normally.
    Returns False early if the session was stopped or end_time was reached
    (the caller should break out of the task loop).
    """
    elapsed = 0
    CHECK_INTERVAL = 30

    while elapsed < total_s:
        chunk = min(CHECK_INTERVAL, total_s - elapsed)
        await asyncio.sleep(chunk)
        elapsed += chunk

        sess = await _load_session(session_id, engine)
        if not sess or sess.status != "running":
            logger.info("[SCHED] Session %d: stopped during playback wait", session_id)
            return False
        if datetime.utcnow() >= _to_naive_utc(sess.end_time):
            logger.info("[SCHED] Session %d: end_time reached during playback wait", session_id)
            return False

        remaining = total_s - elapsed
        if remaining > 0:
            logger.debug("[SCHED] Session %d: playback wait — %ds remaining", session_id, remaining)

    return True


# ── Wait for run completion ───────────────────────────────────────────────────

async def _wait_for_run(
    run_id: str,
    engine: AsyncEngine,
    timeout_s: int = RUN_WAIT_TIMEOUT_S,
    session_id: int | None = None,
) -> str:
    """
    Poll until the run finishes, the timeout fires, or the session end_time passes.
    Previously this had no session-end check, which left sessions stuck in RUNNING
    status for up to 400s past their end_time.
    """
    DbSession = async_sessionmaker(engine, expire_on_commit=False)
    deadline   = datetime.now(timezone.utc) + timedelta(seconds=timeout_s)
    poll_count = 0

    while datetime.now(timezone.utc) < deadline:
        await asyncio.sleep(RUN_POLL_INTERVAL_S)
        poll_count += 1

        async with DbSession() as db:
            result = await db.execute(select(Run).where(Run.run_id == run_id))
            run = result.scalar_one_or_none()
            if run and run.status != "RUNNING":
                return run.status

            # Every 4 polls (≈20s) also check whether the session window has closed.
            # This prevents the scheduler from holding a RUNNING session long after
            # its end_time because it was stuck waiting for a hung command.
            if session_id is not None and poll_count % 4 == 0:
                sess_result = await db.execute(
                    select(SessionModel).where(SessionModel.id == session_id)
                )
                sess = sess_result.scalar_one_or_none()
                if not sess or sess.status != "running":
                    logger.info(
                        "[SCHED] Session %d stopped externally during run wait — "
                        "aborting wait for run %s", session_id, run_id,
                    )
                    return "TIMED_OUT"
                if datetime.utcnow() >= _to_naive_utc(sess.end_time):
                    logger.info(
                        "[SCHED] Session %d end_time reached during run wait — "
                        "aborting wait for run %s", session_id, run_id,
                    )
                    return "TIMED_OUT"

    logger.warning("[SCHED] Timed out waiting for run %s", run_id)
    return "TIMED_OUT"


# ── Helpers ───────────────────────────────────────────────────────────────────

async def _load_task_sequence(session_id: int, engine: AsyncEngine) -> list[int]:
    """Return ordered task_ids for this session."""
    DbSession = async_sessionmaker(engine, expire_on_commit=False)
    async with DbSession() as db:
        result = await db.execute(
            select(SessionTask)
            .where(SessionTask.session_id == session_id)
            .order_by(SessionTask.position)
        )
        rows = result.scalars().all()
        if rows:
            return [r.task_id for r in rows]
        # Fallback: legacy single-task session
        sess_result = await db.execute(
            select(SessionModel).where(SessionModel.id == session_id)
        )
        sess = sess_result.scalar_one_or_none()
        return [sess.task_id] if sess else []


async def _get_task_action(task_id: int, engine: AsyncEngine) -> str | None:
    """Return only the action_type of a task — used to peek ahead in the sequence."""
    DbSession = async_sessionmaker(engine, expire_on_commit=False)
    async with DbSession() as db:
        result = await db.execute(select(Task).where(Task.id == task_id))
        task = result.scalar_one_or_none()
        return task.action_type if task else None


async def _load_session(session_id: int, engine: AsyncEngine) -> SessionModel | None:
    DbSession = async_sessionmaker(engine, expire_on_commit=False)
    async with DbSession() as db:
        result = await db.execute(
            select(SessionModel).where(SessionModel.id == session_id)
        )
        return result.scalar_one_or_none()


async def _set_session_status(session_id: int, status: str, engine: AsyncEngine) -> None:
    DbSession = async_sessionmaker(engine, expire_on_commit=False)
    async with DbSession() as db:
        result = await db.execute(
            select(SessionModel).where(SessionModel.id == session_id)
        )
        sess = result.scalar_one_or_none()
        if sess:
            sess.status = status
            await db.commit()
    logger.info("[SCHED] Session %d → %s", session_id, status)


def _to_naive_utc(dt: datetime) -> datetime:
    """
    Return a naive UTC datetime for comparison with SQLite-stored values.
    SQLite stores datetimes as naive strings ('YYYY-MM-DD HH:MM:SS').
    Comparing an aware datetime against these produces broken lexicographic
    results because '+00:00' and 'T' sort differently than ' '.
    """
    if dt.tzinfo is not None:
        return dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt  # already naive — assumed UTC as stored by SQLAlchemy
