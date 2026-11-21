"""REST API endpoints for Visit History, Follow-Up Queue, and NHM Danger-Sign List."""

from __future__ import annotations

import json
from typing import Any, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel

from app.db import Database
from app.models import FollowUp, FollowUpStatus, VisitRecord
from app.pipeline.danger_signs import DangerSignDetector

router = APIRouter(prefix="/api", tags=["visits"])


class FollowUpStatusPatch(BaseModel):
    status: FollowUpStatus


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
