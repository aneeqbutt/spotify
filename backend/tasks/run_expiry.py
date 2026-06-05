"""
run_expiry.py — Module 5.3

Background asyncio task: marks stale runs as TIMED_OUT.

Problem it solves:
    If a device disconnects mid-command (phone dies, network drop), the APK
    never sends COMMAND_DONE. The run stays in status='RUNNING' indefinitely,
    and the dashboard shows it as in-progress forever.

Solution:
    Every EXPIRY_CHECK_INTERVAL_S seconds, scan the runs table for any run
    where status='RUNNING' AND start_time is older than DEFAULT_TTL_S.
    Mark those runs as 'TIMED_OUT'.

Schema (matches models/tables.py):
    runs.run_id      — UUID string (PK)
    runs.status      — 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMED_OUT'
    runs.start_time  — DateTime (UTC)
    runs.end_time    — DateTime (UTC), nullable

Usage (called from main.py lifespan):
    asyncio.create_task(run_expiry_loop(engine))
"""

import asyncio
import logging
from datetime import datetime, timezone, timedelta

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

logger = logging.getLogger("spotifybot")

EXPIRY_CHECK_INTERVAL_S = 30    # how often we scan (seconds)
DEFAULT_TTL_S           = 300   # 5 minutes — gives time for WS reconnect + COMMAND_DONE retry


async def run_expiry_loop(engine: AsyncEngine) -> None:
    """
    Infinite loop that periodically expires stale runs.
    Cancelled cleanly on app shutdown via lifespan.
    """
    logger.info("[EXPIRY] Run expiry task started (interval=%ds, ttl=%ds)",
                EXPIRY_CHECK_INTERVAL_S, DEFAULT_TTL_S)

    while True:
        try:
            await _expire_stale_runs(engine)
        except Exception as exc:
            logger.error("[EXPIRY] Scan error (will retry next cycle): %s", exc)

        await asyncio.sleep(EXPIRY_CHECK_INTERVAL_S)


async def _expire_stale_runs(engine: AsyncEngine) -> None:
    """
    Find runs stuck in RUNNING longer than DEFAULT_TTL_S and mark them TIMED_OUT.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=DEFAULT_TTL_S)
    now    = datetime.now(timezone.utc)

    async with engine.begin() as conn:
        # Fetch candidates so we can log them individually
        result = await conn.execute(
            text("""
                SELECT run_id, start_time
                FROM runs
                WHERE status = 'RUNNING'
                  AND start_time < :cutoff
            """),
            {"cutoff": cutoff.isoformat()},
        )
        stale_rows = result.fetchall()

    if not stale_rows:
        return

    async with engine.begin() as conn:
        for row in stale_rows:
            run_id_val  = row[0]
            start_time  = row[1]

            age_s = (now - datetime.fromisoformat(
                start_time if isinstance(start_time, str)
                else start_time.isoformat()
            ).replace(tzinfo=timezone.utc)).total_seconds()

            await conn.execute(
                text("""
                    UPDATE runs
                    SET status   = 'TIMED_OUT',
                        end_time = :now
                    WHERE run_id = :run_id
                """),
                {"now": now.isoformat(), "run_id": run_id_val},
            )
            logger.warning(
                "[EXPIRY] Run %s marked TIMED_OUT (ran for %.1fs, limit=%ds)",
                run_id_val, age_s, DEFAULT_TTL_S,
            )

    logger.info("[EXPIRY] Expired %d stale run(s)", len(stale_rows))
