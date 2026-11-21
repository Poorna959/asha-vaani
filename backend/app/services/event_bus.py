"""In-process async event bus for real-time PHC Supervisor Dashboard streaming (P3-03)."""

from __future__ import annotations

import asyncio
from collections import deque
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from app.models import utc_now_iso


class DashboardEventBus:
    """Non-blocking fan-out pub-sub broadcaster for Server-Sent Events (/api/events/stream)."""

    def __init__(self, history_limit: int = 25, queue_maxsize: int = 64) -> None:
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()
        self._recent_alerts: deque[dict[str, Any]] = deque(maxlen=history_limit)
        self._queue_maxsize = queue_maxsize

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

    def recent_alerts(self) -> list[dict[str, Any]]:
        """Return newest-first recent live danger-escalation events."""
        return list(reversed(self._recent_alerts))

    def publish(self, event_type: str, data: dict[str, Any]) -> dict[str, Any]:
        """Publish an event to all connected dashboard subscribers without blocking."""
        envelope: dict[str, Any] = {
            "event": event_type,
            "timestamp": utc_now_iso(),
            "data": data,
        }
        if event_type == "danger_escalation":
            self._recent_alerts.append(envelope)

        stale: list[asyncio.Queue[dict[str, Any]]] = []
        for queue in list(self._subscribers):
            try:
                queue.put_nowait(envelope)
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                    queue.put_nowait(envelope)
                except Exception:
                    stale.append(queue)
            except Exception:
                stale.append(queue)

        for q in stale:
            self._subscribers.discard(q)
        return envelope

    @asynccontextmanager
    async def subscribe(self) -> AsyncIterator[asyncio.Queue[dict[str, Any]]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=self._queue_maxsize)
        self._subscribers.add(queue)
        try:
            yield queue
        finally:
            self._subscribers.discard(queue)
