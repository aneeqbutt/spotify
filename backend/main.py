"""
main.py — FastAPI application entry point

Phase 5 additions:
  - Background task: run_expiry_loop  — marks stale runs TIMED_OUT every 60s
  - Background task: heartbeat_loop  — marks stale devices offline every 60s
  - Enhanced /health endpoint         — includes background task status + db ping
"""

import asyncio
import json as _json
import logging
import socket
import threading
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from backend.database.db import init_db, engine
from backend.tasks.run_expiry          import run_expiry_loop
from backend.tasks.heartbeat           import heartbeat_loop
from backend.tasks.session_scheduler   import session_scheduler_loop, scheduler

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
)
logger = logging.getLogger("spotifybot")

# ── Background task handles (stored so we can cancel on shutdown) ──────────────
_bg_tasks: list[asyncio.Task] = []

# ── UDP auto-discovery beacon ─────────────────────────────────────────────────
_BEACON_PORT     = 8765
_BEACON_INTERVAL = 5   # seconds

# Unique ID for this server process.  A new UUID is generated every time the
# backend starts.  APKs include this in their beacon comparison — when the ID
# changes they know the backend restarted and immediately call clearSavedHost()
# so they re-discover the (possibly new) IP instead of retrying a stale one.
_SERVER_BOOT_ID = str(uuid.uuid4())

def _get_broadcast_addresses() -> list[str]:
    """
    Return all subnet-directed broadcast addresses for active non-loopback
    IPv4 interfaces on this machine, plus the global fallback 255.255.255.255.

    Why subnet-directed (e.g. 192.168.1.255) beats global (255.255.255.255):
        Most WiFi routers forward subnet-directed broadcasts to wireless clients
        but silently drop 255.255.255.255.  The previous implementation only
        produced the global address because a bug in the hostname loop threw an
        exception before the detection code ran.

    Two fully independent strategies, each in its own try/except:

      1. getaddrinfo(hostname) — enumerates all IPs bound to this machine.
         Works without internet. Fails only if the hostname doesn't resolve,
         which is rare on Windows machines joined to any network.

      2. UDP routing trick — creates a UDP socket (no packets sent) and asks
         the OS routing table which local interface it would use to reach an
         arbitrary public IP.  Works on any network/subnet because the OS
         picks the default-gateway interface regardless of destination.
         No hardcoded private IP — the destination (8.8.8.8) is only used
         as a routing hint; the beacon goes to the dynamically derived subnet.
    """
    addrs: list[str] = ["255.255.255.255"]

    def _add_subnet(ip: str) -> None:
        """Derive the /24 broadcast from an IP and add it if not already present."""
        if ip.startswith("127.") or ip == "0.0.0.0":
            return
        parts = ip.split(".")
        if len(parts) == 4:
            bc = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
            if bc not in addrs:
                addrs.append(bc)

    # Strategy 1: enumerate all IPv4 addresses bound to this hostname.
    # No internet required. Correctly handles multi-homed machines (VPN + WiFi, etc.)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            _add_subnet(info[4][0])
    except Exception:
        pass

    # Strategy 2: UDP routing-table trick.
    # SOCK_DGRAM connect() never transmits — it only resolves the outbound interface.
    # Using 8.8.8.8 as the routing hint works on any subnet (the OS picks the default
    # gateway interface).  No hardcoded private IP is involved.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            _add_subnet(s.getsockname()[0])
        finally:
            s.close()
    except Exception:
        pass

    return addrs


def _udp_beacon_loop():
    """
    Broadcast the backend's presence on the LAN every 5 seconds.
    APKs listen on UDP port 8765 and extract the sender IP automatically.
    Sends to both 255.255.255.255 and the subnet-directed broadcast
    (e.g. 192.168.1.255) for maximum router compatibility.

    Broadcast targets are refreshed every 60 seconds so that when the
    PC switches WiFi networks (different IP / subnet), the beacon
    automatically finds the new interface without restarting the server.

    Payload: {"service": "sportify-backend", "port": 8000}
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        payload = _json.dumps({
            "service": "sportify-backend",
            "port":    8000,
            "boot_id": _SERVER_BOOT_ID,   # changes on every backend restart
        }).encode()
        targets = _get_broadcast_addresses()
        last_refresh = time.time()
        logger.info("[BEACON] UDP beacon started — targets: %s", targets)
        while True:
            # Refresh broadcast targets every 60s to track network interface changes.
            # This is critical when the PC changes WiFi networks — without this the
            # beacon keeps sending to the old subnet and devices on the new network
            # never receive it.
            if time.time() - last_refresh >= 60:
                new_targets = _get_broadcast_addresses()
                if new_targets != targets:
                    logger.info("[BEACON] Network changed — updating targets: %s → %s",
                                targets, new_targets)
                    targets = new_targets
                last_refresh = time.time()

            for addr in targets:
                try:
                    sock.sendto(payload, (addr, _BEACON_PORT))
                except Exception as exc:
                    logger.warning("[BEACON] Send to %s failed: %s", addr, exc)
            time.sleep(_BEACON_INTERVAL)
    except Exception as exc:
        logger.error("[BEACON] Beacon thread crashed: %s", exc)


# ── Lifespan (startup / shutdown) ─────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    # ── STARTUP ───────────────────────────────────────────────────────────────
    logger.info("=== Spotify Automation Platform starting up ===")

    # 1. Initialise all DB tables
    await init_db()
    logger.info("[STARTUP] Database ready")

    # 2. Reset all device statuses to offline.
    #    On a fresh start the in-memory ConnectionManager is empty — any device
    #    showing 'online' in the DB is a stale value from the previous run.
    #    Devices will reconnect and flip back to 'online' within seconds.
    from sqlalchemy import text
    async with engine.begin() as conn:
        result = await conn.execute(text("UPDATE devices SET status = 'offline'"))
        logger.info("[STARTUP] Reset %d device(s) to offline — awaiting reconnect",
                    result.rowcount)

    # 2. Start UDP discovery beacon (daemon thread — auto-dies with process)
    threading.Thread(target=_udp_beacon_loop, daemon=True, name="udp-beacon").start()
    logger.info("[STARTUP] UDP discovery beacon thread started on port %d", _BEACON_PORT)

    # 3. Start background stability tasks (Phase 5)
    expiry_task    = asyncio.create_task(run_expiry_loop(engine),  name="run_expiry")
    heartbeat_task = asyncio.create_task(heartbeat_loop(engine),   name="device_heartbeat")
    _bg_tasks.extend([expiry_task, heartbeat_task])
    logger.info("[STARTUP] Background tasks started: run_expiry, device_heartbeat")

    # 4. Initialise singletons that need the engine
    from backend.websocket.manager import manager as _ws_manager
    _ws_manager.init(engine)          # gives manager a fresh-session factory for delayed offline
    scheduler.init(engine)
    async with engine.begin() as conn:
        resume_result = await conn.execute(
            text(
                "SELECT id FROM sessions "
                "WHERE status IN ('running', 'scheduled') "
                "  AND start_time <= :now"
            ),
            # Use space-separated naive UTC string — matches SQLite's storage format.
            # isoformat() produces "T" + "+00:00" which breaks lexicographic comparison
            # because ' ' < 'T' and ' ' < '+' in ASCII, making every stored time appear
            # "before" the aware ISO string regardless of the actual hour.
            {"now": datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")},
        )
        resumed = resume_result.fetchall()
        for row in resumed:
            scheduler.schedule(row[0])
    if resumed:
        logger.info("[STARTUP] Resumed %d interrupted session(s)", len(resumed))

    session_task = asyncio.create_task(session_scheduler_loop(engine), name="session_scheduler")
    _bg_tasks.append(session_task)
    logger.info("[STARTUP] Session scheduler started")

    yield  # ← application runs here

    # ── SHUTDOWN ──────────────────────────────────────────────────────────────
    logger.info("[SHUTDOWN] Cancelling background tasks…")
    for task in _bg_tasks:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
    logger.info("[SHUTDOWN] All background tasks stopped. Goodbye.")


# ── App ───────────────────────────────────────────────────────────────────────
app = FastAPI(
    title="Spotify Automation Platform",
    version="2.0.0",
    description="Backend API — Week 2 complete (Phase 1-5)",
    lifespan=lifespan,
)

# Allow any origin — this backend runs on a private LAN and is not internet-exposed.
# Restricting to localhost would break the React dashboard when accessed from a
# phone, tablet, or another machine on the same network (e.g. when travelling).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,   # credentials=True is incompatible with allow_origins=["*"]
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Health endpoint (Module 5.5) ──────────────────────────────────────────────
@app.get("/health", tags=["System"])
async def health():
    """
    System health check — returns:
      - server status
      - current UTC time
      - which background tasks are running
      - database connectivity (ping)

    Used by monitoring, load balancers, and the dashboard status bar.
    """
    from sqlalchemy import text

    # DB ping — confirm SQLite is reachable
    db_ok = True
    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
    except Exception as exc:
        logger.error("[HEALTH] DB ping failed: %s", exc)
        db_ok = False

    # Report which background tasks are alive
    task_status = {
        t.get_name(): ("running" if not t.done() else "stopped")
        for t in _bg_tasks
    }

    return {
        "status":      "ok" if db_ok else "degraded",
        "time_utc":    datetime.now(timezone.utc).isoformat(),
        "version":     "2.0.0",
        "database":    "ok" if db_ok else "error",
        "background_tasks": task_status,
    }


# ── Routers ───────────────────────────────────────────────────────────────────
from backend.routers import auth, devices, tasks, commands, ws, events, sessions

app.include_router(auth.router)       # /auth/login, /auth/register, /auth/me
app.include_router(devices.router)    # /devices/register, /devices/
app.include_router(tasks.router)      # /tasks/ CRUD
app.include_router(commands.router)   # /commands/run, /commands/runs
app.include_router(ws.router)         # /ws/{device_id} WebSocket
app.include_router(events.router)     # /events/stream  SSE real-time feed
app.include_router(sessions.router)   # /sessions/ scheduled automation
