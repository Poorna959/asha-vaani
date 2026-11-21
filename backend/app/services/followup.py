"""Smart Follow-Up & Immunisation Schedule Calculator for ASHA Vaani."""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any, Optional

from app.models import FollowUpCreate, VisitRecord, VisitType


HBNC_MILESTONE_DAYS = (1, 3, 7, 14, 28, 42)


def _parse_iso_date(raw: Any) -> Optional[date]:
    if not isinstance(raw, str):
        return None
    try:
        return date.fromisoformat(raw.strip()[:10])
    except ValueError:
        return None


def compute_next_visit_plan(
    visit_type: VisitType,
    reference_date: Optional[date] = None,
    baseline_fields: Optional[dict[str, Any]] = None,
    existing_vaccines_due: Optional[list[str]] = None,
) -> tuple[str, str, list[str]]:
    """Compute (due_date_iso, purpose, suggested_vaccines) based on NHM ASHA guidelines."""
    today = reference_date or date.today()
    baseline = baseline_fields or {}
    vaccines = list(existing_vaccines_due or [])

    if visit_type == "ANC":
        next_date = today + timedelta(days=28)
        if not vaccines:
            history = str(baseline.get("Medical History", "")).lower()
            if "tt1" in history and "tt2" not in history:
                vaccines = ["TT2"]
            elif "tt" not in history:
                vaccines = ["TT1"]
        return next_date.isoformat(), "Next Routine ANC Checkup", vaccines

    if visit_type == "NEWBORN":
        dob = _parse_iso_date(baseline.get("Date of Birth"))
        if dob is not None:
            age_days = max((today - dob).days, 0)
            next_milestone = next((d for d in HBNC_MILESTONE_DAYS if d > age_days), None)
            if next_milestone is not None:
                next_date = dob + timedelta(days=next_milestone)
                purpose = f"HBNC Day {next_milestone} Newborn Weight & Feeding Check"
            else:
                next_date = dob + timedelta(days=42)
                if next_date <= today:
                    next_date = today + timedelta(days=14)
                purpose = "6-Week Newborn Immunisation & Growth Follow-Up"
            if not vaccines and age_days <= 7:
                vaccines = ["BCG", "OPV-0"]
            elif not vaccines and age_days >= 35:
                vaccines = ["Pentavalent-1", "OPV-1", "Rotavirus-1"]
            return next_date.isoformat(), purpose, vaccines

        next_date = today + timedelta(days=7)
        return next_date.isoformat(), "HBNC Newborn Follow-Up Check", vaccines

    # IMMUNISATION
    next_date = today + timedelta(days=28)
    return next_date.isoformat(), "Scheduled Immunisation Follow-Up", vaccines


def build_follow_up_create(
    visit: VisitRecord,
    baseline_fields: Optional[dict[str, Any]] = None,
    reference_date: Optional[date] = None,
) -> FollowUpCreate:
    """Create a follow-up queue entry from a confirmed VisitRecord."""
    today = reference_date or date.today()

    if visit.has_danger_sign:
        return FollowUpCreate(
            beneficiary_id=visit.beneficiary_id,
            beneficiary_name=visit.beneficiary_name,
            visit_id=visit.id,
            due_date=today.isoformat(),
            purpose=f"URGENT PHC Referral Follow-Up ({', '.join(visit.danger_signs)})",
            vaccines_due=list(visit.vaccinations_due),
            status="ESCALATED",
        )

    default_date, purpose, suggested_vaccines = compute_next_visit_plan(
        visit_type=visit.visit_type,
        reference_date=today,
        baseline_fields=baseline_fields,
        existing_vaccines_due=visit.vaccinations_due,
    )
    return FollowUpCreate(
        beneficiary_id=visit.beneficiary_id,
        beneficiary_name=visit.beneficiary_name,
        visit_id=visit.id,
        due_date=visit.next_visit_date or default_date,
        purpose=purpose,
        vaccines_due=list(visit.vaccinations_due) or suggested_vaccines,
        status="PENDING",
    )
