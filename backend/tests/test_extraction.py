"""Tests for Issue #5: Gemini 2.5 Flash Clinical Extraction, Code-Mix, Voice Diffs & Follow-Up Plan."""

from __future__ import annotations

from datetime import date

import pytest

from app.db import Database
from app.models import VisitDraft, VisitRecord
from app.pipeline.llm import (
    ClinicalStateEngine,
    GeminiVisitExtractor,
    RuleBasedVisitExtractor,
    normalize_indic_numbers,
)
from app.services.followup import build_follow_up_create, compute_next_visit_plan
from app.services.register_service import RegisterService


def test_normalize_indic_numbers_handles_english_hindi_kannada_decimals():
    assert normalize_indic_numbers("weight do point aath kilo") == "weight 2.8 kilo"
    assert normalize_indic_numbers("two point eight kilo") == "2.8 kilo"
    assert normalize_indic_numbers("eradu point entu kilo") == "2.8 kilo"
    assert normalize_indic_numbers("tees goli") == "30 goli"


async def test_anc_codemix_extraction_resolves_roster_and_enforces_readback_gate():
    db = Database(db_path=":memory:")
    await db.connect()
    await db.seed_default_data()
    patients = [p.model_dump() for p in await db.list_patients()]
    await db.close()

    engine = ClinicalStateEngine(provider=RuleBasedVisitExtractor())
    ref_day = date(2026, 11, 21)

    turn1 = await engine.process_turn(
        utterance="Sunita Devi ANC checkup weight 54.5 kilo IFA goli 30 TT2 injection due",
        current_draft=VisitDraft(),
        language_code="hi-IN",
        readback_completed=False,
        roster=patients,
        reference_date=ref_day,
    )

    assert turn1.should_save is False
    assert turn1.status == "AWAITING_CONFIRMATION"
    assert turn1.readback_completed is True
    assert turn1.updated_draft.beneficiary_id == "BEN-001"
    assert turn1.updated_draft.beneficiary_name == "Sunita Devi"
    assert turn1.updated_draft.visit_type == "ANC"
    assert turn1.updated_draft.weight_kg == 54.5
    assert turn1.updated_draft.ifa_tablets_given == 30
    assert turn1.updated_draft.vaccinations_due == ["TT2"]
    assert turn1.updated_draft.next_visit_date == "2026-12-19"
    assert "54.5" in turn1.extraction.spoken_readback
    assert "सेव" in turn1.extraction.spoken_readback

    # Confirming BEFORE readback_completed=True must NEVER save
    premature = await engine.process_turn(
        utterance="haan theek hai",
        current_draft=turn1.updated_draft,
        language_code="hi-IN",
        readback_completed=False,
        roster=patients,
        reference_date=ref_day,
    )
    assert premature.should_save is False
    assert premature.status == "AWAITING_CONFIRMATION"

    # Confirming AFTER readback_completed=True commits cleanly
    confirmed = await engine.process_turn(
        utterance="haan theek hai",
        current_draft=turn1.updated_draft,
        language_code="hi-IN",
        readback_completed=True,
        roster=patients,
        reference_date=ref_day,
    )
    assert confirmed.should_save is True
    assert confirmed.status == "SAVED"
    assert confirmed.extraction.intent == "CONFIRM_SAVE"


async def test_voice_correction_emits_exact_field_diff_and_delta_readback():
    engine = ClinicalStateEngine(provider=RuleBasedVisitExtractor())
    initial_draft = VisitDraft(
        beneficiary_id="BEN-002",
        beneficiary_name="Lakshmi Gowda (Baby)",
        visit_type="NEWBORN",
        weight_kg=3.2,
        vaccinations_due=["BCG", "OPV-0"],
        next_visit_date="2026-11-28",
    )

    # English correction
    turn_en = await engine.process_turn(
        utterance="No, weight is 2.8 kilo",
        current_draft=initial_draft,
        language_code="en-IN",
        readback_completed=True,
    )
    assert turn_en.should_save is False
    assert turn_en.status == "AWAITING_CONFIRMATION"
    assert turn_en.extraction.intent == "CORRECT_FIELD"
    assert turn_en.updated_draft.weight_kg == 2.8
    assert len(turn_en.extraction.field_diffs) == 1
    assert turn_en.extraction.field_diffs[0].field == "weight_kg"
    assert turn_en.extraction.field_diffs[0].old_value == 3.2
    assert turn_en.extraction.field_diffs[0].new_value == 2.8
    assert "2.8 kilo" in turn_en.extraction.spoken_readback

    # Hinglish spoken decimal correction ("nahi wajan do point aath kilo hai")
    turn_hi = await engine.process_turn(
        utterance="nahi wajan do point aath kilo hai",
        current_draft=initial_draft,
        language_code="hi-IN",
        readback_completed=True,
    )
    assert turn_hi.updated_draft.weight_kg == 2.8
    assert turn_hi.extraction.field_diffs[0].new_value == 2.8
    assert "2.8" in turn_hi.extraction.spoken_readback
    assert "अपडेट" in turn_hi.extraction.spoken_readback

    # Gram conversion correction ("no weight is 2800 gram")
    turn_gm = await engine.process_turn(
        utterance="no weight is 2800 gram",
        current_draft=initial_draft,
        language_code="kn-IN",
        readback_completed=True,
    )
    assert turn_gm.updated_draft.weight_kg == 2.8
    assert "2.8" in turn_gm.extraction.spoken_readback


async def test_preselected_patient_baseline_context_inheritance_and_hbnc_schedule():
    """FR-7.6: Tapping a registered patient carries forward baseline profile into routine visits."""
    db = Database(db_path=":memory:")
    await db.connect()
    await db.seed_default_data()
    reg_service = RegisterService(db)
    context = await reg_service.build_followup_context("BEN-002")  # Born 2026-11-14 (Day 7 on Nov 21)
    await db.close()

    engine = ClinicalStateEngine(provider=RuleBasedVisitExtractor())
    turn = await engine.process_turn(
        utterance="weight 3.1 kilo, feeding well",
        current_draft=VisitDraft(),
        language_code="kn-IN",
        readback_completed=False,
        patient_context=context,
        reference_date=date(2026, 11, 21),
    )

    assert turn.updated_draft.beneficiary_id == "BEN-002"
    assert turn.updated_draft.beneficiary_name == "Lakshmi Gowda (Baby)"
    assert turn.updated_draft.visit_type == "NEWBORN"
    assert turn.updated_draft.weight_kg == 3.1
    # Next HBNC milestone after Day 7 is Day 14 -> 2026-11-14 + 14 days = 2026-11-28
    assert turn.updated_draft.next_visit_date == "2026-11-28"
    assert "BCG" in turn.updated_draft.vaccinations_due


async def test_gemini_extractor_falls_back_cleanly_without_api_key():
    extractor = GeminiVisitExtractor(api_key="")
    payload = await extractor.extract(
        utterance="ANC checkup weight 52 kilo TT injection",
        current_draft=VisitDraft(),
        language_code="en-IN",
    )
    assert payload.intent == "LOG_VISIT"
    assert payload.visit_type == "ANC"
    assert payload.weight_kg == 52.0
    assert payload.vaccinations_due == ["TT Injection"]


def test_followup_calculator_routine_vs_escalated():
    ref = date(2026, 11, 21)
    due_date, purpose, vaccines = compute_next_visit_plan(
        visit_type="ANC",
        reference_date=ref,
        baseline_fields={"Medical History": "TT1 completed"},
    )
    assert due_date == "2026-12-19"
    assert vaccines == ["TT2"]

    urgent_visit = VisitRecord(
        id="VIS-0001",
        beneficiary_id="BEN-001",
        beneficiary_name="Sunita Devi",
        visit_type="ANC",
        has_danger_sign=True,
        danger_signs=["DS_MAT_BLEEDING"],
    )
    urgent_fup = build_follow_up_create(urgent_visit, reference_date=ref)
    assert urgent_fup.status == "ESCALATED"
    assert urgent_fup.due_date == "2026-11-21"
    assert "DS_MAT_BLEEDING" in urgent_fup.purpose


async def test_offline_name_matching_avoids_surname_conflict_and_captures_walkin():
    """Verify 'Priya Sharma' does NOT falsely match 'Priya Nair (Baby)' and walk-ins work offline."""
    db = Database(":memory:")
    await db.connect()
    await db.seed_default_data()
    roster = [p.model_dump() for p in await db.list_patients()]
    await db.close()

    engine = ClinicalStateEngine(provider=RuleBasedVisitExtractor())

    # Conflicting surname: Priya Sharma != Priya Nair (Baby)
    t1 = await engine.process_turn(
        utterance="Priya Sharma ANC checkup weight 52 kilo",
        current_draft=VisitDraft(),
        language_code="en-IN",
        roster=roster,
    )
    assert t1.updated_draft.beneficiary_id is None
    assert t1.updated_draft.beneficiary_name == "Priya Sharma"
    assert t1.updated_draft.visit_type == "ANC"
    assert t1.updated_draft.weight_kg == 52.0

    # Completely unseen walk-in patient via explicit cue
    t2 = await engine.process_turn(
        utterance="Patient Radha Bai ANC checkup weight 50 kilo",
        current_draft=VisitDraft(),
        language_code="en-IN",
        roster=roster,
    )
    assert t2.updated_draft.beneficiary_id is None
    assert t2.updated_draft.beneficiary_name == "Radha Bai"

    # Legitimate first-name roster match WITHOUT conflicting surname still resolves
    t3 = await engine.process_turn(
        utterance="Sunita ANC checkup weight 54 kilo",
        current_draft=VisitDraft(),
        language_code="hi-IN",
        roster=roster,
    )
    assert t3.updated_draft.beneficiary_id == "BEN-001"
    assert t3.updated_draft.beneficiary_name == "Sunita Devi"
