"""REST API endpoints for Visit History, Follow-Up Queue, and NHM Danger-Sign List."""

from __future__ import annotations

import asyncio
import json
from typing import Any, AsyncIterator, Optional

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.db import Database
from app.models import FollowUp, FollowUpStatus, VisitRecord
from app.pipeline.danger_signs import DangerSignDetector
from app.pipeline.llm import resolve_active_llm_info
from app.services.event_bus import DashboardEventBus

router = APIRouter(prefix="/api", tags=["visits"])


class FollowUpStatusPatch(BaseModel):
    status: FollowUpStatus


class VisitAcknowledgeRequest(BaseModel):
    acknowledged_by: str = "PHC Medical Officer"


def get_db(request: Request) -> Database:
    db = getattr(request.app.state, "db", None)
    if db is None:
        raise RuntimeError("Database not initialized on app.state.")
    return db


def get_danger_detector(request: Request) -> DangerSignDetector:
    detector = getattr(request.app.state, "danger_detector", None)
    if detector is None:
        raise RuntimeError("DangerSignDetector not initialized on app.state.")
    return detector


def get_event_bus(request: Request) -> DashboardEventBus:
    bus = getattr(request.app.state, "event_bus", None)
    if bus is None:
        raise RuntimeError("DashboardEventBus not initialized on app.state.")
    return bus


@router.get("/visits", response_model=list[VisitRecord])
async def list_visits(
    beneficiary_id: Optional[str] = Query(default=None),
    only_danger: bool = Query(default=False),
    db: Database = Depends(get_db),
) -> list[VisitRecord]:
    return await db.list_visits(beneficiary_id=beneficiary_id, only_danger=only_danger)


@router.get("/visits/{visit_id}", response_model=VisitRecord)
async def get_visit(
    visit_id: str,
    db: Database = Depends(get_db),
) -> VisitRecord:
    visit = await db.get_visit(visit_id)
    if visit is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Visit '{visit_id}' not found.",
        )
    return visit


@router.get("/follow-ups", response_model=list[FollowUp])
async def list_follow_ups(
    status_filter: Optional[FollowUpStatus] = Query(default=None, alias="status"),
    beneficiary_id: Optional[str] = Query(default=None),
    db: Database = Depends(get_db),
) -> list[FollowUp]:
    return await db.list_follow_ups(status=status_filter, beneficiary_id=beneficiary_id)


@router.patch("/follow-ups/{follow_up_id}", response_model=FollowUp)
async def update_follow_up_status(
    follow_up_id: str,
    payload: FollowUpStatusPatch,
    db: Database = Depends(get_db),
) -> FollowUp:
    updated = await db.update_follow_up_status(follow_up_id, payload.status)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Follow-up '{follow_up_id}' not found.",
        )
    return updated


@router.get("/danger-signs")
async def get_danger_signs_catalog(
    detector: DangerSignDetector = Depends(get_danger_detector),
) -> dict[str, Any]:
    return json.loads(detector.path.read_text(encoding="utf-8"))


@router.patch("/visits/{visit_id}/acknowledge", response_model=VisitRecord)
async def acknowledge_visit(
    visit_id: str,
    payload: Optional[VisitAcknowledgeRequest] = Body(default=None),
    db: Database = Depends(get_db),
    event_bus: DashboardEventBus = Depends(get_event_bus),
) -> VisitRecord:
    """Mark an escalated visit as acknowledged by the PHC Medical Officer."""
    ack_by = payload.acknowledged_by if payload is not None else "PHC Medical Officer"
    updated = await db.acknowledge_visit(visit_id=visit_id, acknowledged_by=ack_by)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Visit '{visit_id}' not found.",
        )
    event_bus.publish("visit_acknowledged", {"visit": updated.model_dump()})
    return updated


@router.get("/dashboard/summary", tags=["dashboard"])
async def get_dashboard_summary(
    request: Request,
    db: Database = Depends(get_db),
    event_bus: DashboardEventBus = Depends(get_event_bus),
) -> dict[str, Any]:
    """Return atomic snapshot of PHC escalation status, KPIs, visits, follow-ups & live alerts."""
    visits = await db.list_visits()
    follow_ups = await db.list_follow_ups()
    registers = await db.list_registers()
    patients = await db.list_patients()

    danger_visits = [v for v in visits if v.has_danger_sign]
    open_escalations = [v for v in danger_visits if not v.phc_acknowledged]
    acknowledged_escalations = [v for v in danger_visits if v.phc_acknowledged]
    pending_follow_ups = [f for f in follow_ups if f.status != "COMPLETED"]

    cfg = request.app.state.settings
    llm_mode, llm_model = resolve_active_llm_info(cfg)

    return {
        "kpis": {
            "total_visits": len(visits),
            "total_escalations": len(danger_visits),
            "active_escalations": len(open_escalations),
            "acknowledged_escalations": len(acknowledged_escalations),
            "pending_follow_ups": len(pending_follow_ups),
            "custom_registers": len(registers),
            "total_patients": len(patients),
        },
        "system": {
            "gnani_mode": "mock" if cfg.mock_gnani else "live",
            "stt_model": "prisma-v3.0",
            "tts_model": "timbre-v3.0",
            "llm_mode": llm_mode,
            "llm_model": llm_model,
        },
        "recent_live_alerts": event_bus.recent_alerts(),
        "visits": [v.model_dump() for v in visits[:50]],
        "follow_ups": [f.model_dump() for f in follow_ups[:50]],
        "registers": [r.model_dump() for r in registers],
    }


@router.get("/events/stream", tags=["dashboard"])
async def stream_dashboard_events(
    request: Request,
    once: bool = Query(default=False),
    event_bus: DashboardEventBus = Depends(get_event_bus),
) -> StreamingResponse:
    """Server-Sent Events (SSE) live stream of field danger escalations and visit saves."""

    async def event_generator() -> AsyncIterator[str]:
        connected_payload = json.dumps(
            {
                "event": "connected",
                "recent_alerts": event_bus.recent_alerts()[:5],
            },
            ensure_ascii=False,
        )
        yield f"event: connected\ndata: {connected_payload}\n\n"
        if once:
            return

        async with event_bus.subscribe() as queue:
            while True:
                if await request.is_disconnected():
                    break
                try:
                    envelope = await asyncio.wait_for(queue.get(), timeout=15.0)
                    ev_name = envelope.get("event", "message")
                    payload_json = json.dumps(envelope, ensure_ascii=False)
                    yield f"event: {ev_name}\ndata: {payload_json}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
                except asyncio.CancelledError:
                    break

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )
