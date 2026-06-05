"""
websocket/broadcaster.py — In-process SSE event broadcaster

Allows backend events (device connected, run step completed, etc.) to be
pushed instantly to all connected dashboard clients via Server-Sent Events.

Architecture:
  Each SSE client subscribes with a Queue. When the backend publishes an event
  (e.g. "device came online"), every queue gets the message immediately. The SSE
  generator reads from its queue and streams the data: ... line to the browser.

Why asyncio.Queue?
  All FastAPI async handlers share the same event loop. asyncio.Queue is safe
  to use across coroutines in the same loop without any locking.

Usage:
  from backend.websocket.broadcaster import broadcaster

  # Publisher (e.g. connection manager):
  await broadcaster.publish({"type": "device_status", "device_id": "...", "status": "online"})

  # Subscriber (SSE endpoint generator):
  q = broadcaster.subscribe()
  try:
      event = await asyncio.wait_for(q.get(), timeout=25)
  finally:
      broadcaster.unsubscribe(q)
"""

import asyncio
import logging
from typing import Set

logger = logging.getLogger("broadcaster")


class SSEBroadcaster:
    def __init__(self):
        # Set of active subscriber queues — one per connected dashboard tab
        self._queues: Set[asyncio.Queue] = set()

    def subscribe(self) -> asyncio.Queue:
        """Register a new SSE client. Returns its dedicated queue."""
        q: asyncio.Queue = asyncio.Queue(maxsize=200)
        self._queues.add(q)
        logger.debug("[BROADCAST] SSE client subscribed (total=%d)", len(self._queues))
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        """Remove a client queue when the SSE connection closes."""
        self._queues.discard(q)
        logger.debug("[BROADCAST] SSE client unsubscribed (total=%d)", len(self._queues))

    async def publish(self, event: dict) -> None:
        """
        Push an event to every subscribed SSE client.
        Clients whose queues are full (slow consumer) are silently dropped.
        """
        if not self._queues:
            return  # no SSE clients open — fast path

        dead = []
        for q in list(self._queues):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                logger.warning("[BROADCAST] Client queue full — dropping slow subscriber")
                dead.append(q)

        for q in dead:
            self._queues.discard(q)

        logger.debug("[BROADCAST] Published '%s' to %d client(s)",
                     event.get("type"), len(self._queues))


# Singleton — import this everywhere
broadcaster = SSEBroadcaster()
