"""Tests for Issue #5: Gemini 2.5 Flash Clinical Extraction, Code-Mix, Voice Diffs & Follow-Up Plan."""

from __future__ import annotations

import json
from datetime import date

import httpx
import pytest

from app.config import Settings
from app.db import Database
from app.models import CustomRegister, RegisterField, RegisterIntakeDraft, VisitDraft, VisitRecord
from app.pipeline.llm import (
    ClinicalStateEngine,
    GeminiVisitExtractor,
    GroqVisitExtractor,
    RegisterIntakeEngine,
    RuleBasedVisitExtractor,
    create_visit_extractor,
    normalize_indic_numbers,
    resolve_active_llm_info,
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


async def test_groq_visit_extractor_parses_structured_json_and_falls_back_on_error():
    """Verify GroqVisitExtractor parses OpenAI-compatible JSON and falls back cleanly on 503."""
    captured_requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content.decode("utf-8"))
        captured_requests.append(body)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "intent": "LOG_VISIT",
                                    "beneficiary_id": "BEN-001",
                                    "beneficiary_name": "Sunita Devi",
                                    "visit_type": "ANC",
                                    "weight_kg": 2.8,
                                    "vaccinations_due": ["TT2"],
                                    "next_visit_date": None,
                                    "ifa_tablets_given": 30,
                                    "notes": "ANC checkup",
                                    "spoken_readback": "सुनीता देवी, वजन 2.8 किलो, 30 IFA गोली, TT2 बाकी है। क्या सेव कर लूं?",
                                }
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        base_url="https://api.groq.com/openai/v1",
        transport=httpx.MockTransport(handler),
    ) as client:
        extractor = GroqVisitExtractor(
            api_key="gsk_test_key",
            model="qwen/qwen3.8-27b",
            http_client=client,
        )
        payload = await extractor.extract(
            utterance="सुनीता देवी, ANC चेकअप, वजन 2.8 kg, 30 IFA गोली, TT2 बाकी है।",
            current_draft=VisitDraft(),
            language_code="hi-IN",
        )

    assert payload.intent == "LOG_VISIT"
    assert payload.beneficiary_id == "BEN-001"
    assert payload.weight_kg == 2.8
    assert payload.ifa_tablets_given == 30
    assert payload.vaccinations_due == ["TT2"]
    assert captured_requests[0]["model"] == "qwen/qwen3.8-27b"
    assert captured_requests[0]["reasoning_effort"] == "none"

    # Graceful offline fallback on 503 outage
    def failing_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": {"message": "Service unavailable"}})

    async with httpx.AsyncClient(
        base_url="https://api.groq.com/openai/v1",
        transport=httpx.MockTransport(failing_handler),
    ) as fail_client:
        fallback_extractor = GroqVisitExtractor(
            api_key="gsk_test_key",
            model="qwen/qwen3.8-27b",
            http_client=fail_client,
        )
        fb_payload = await fallback_extractor.extract(
            utterance="Priya Sharma ANC checkup weight 52 kilo",
            current_draft=VisitDraft(),
            language_code="en-IN",
        )
    assert fb_payload.intent == "LOG_VISIT"
    assert fb_payload.beneficiary_name == "Priya Sharma"
    assert fb_payload.weight_kg == 52.0


async def test_groq_register_intake_engine_and_provider_factory():
    """Verify FR-7.2 RegisterIntakeEngine uses Groq and provider factory switches cleanly."""
    register = CustomRegister(
        id="REG-1",
        name="Pregnancy Baseline",
        fields=[
            RegisterField(id="F-1", register_id="REG-1", field_name="Age", field_type="NUMBER"),
            RegisterField(id="F-2", register_id="REG-1", field_name="Blood Group", field_type="TEXT"),
        ],
    )
    draft = RegisterIntakeDraft(
        register_id="REG-1",
        register_name="Pregnancy Baseline",
        expected_fields=["Age", "Blood Group"],
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "intent": "LOG_VISIT",
                                    "patient_name": "Kavita Devi",
                                    "category": "ANC",
                                    "answers": [
                                        {"field_name": "Age", "value": "24"},
                                        {"field_name": "Blood Group", "value": "O+"},
                                    ],
                                    "spoken_readback": "कविता देवी, उम्र 24, ब्लड ग्रुप O+ दर्ज किया। क्या सेव कर लूं?",
                                }
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        base_url="https://api.groq.com/openai/v1",
        transport=httpx.MockTransport(handler),
    ) as client:
        intake_engine = RegisterIntakeEngine(
            llm_provider="groq",
            groq_api_key="gsk_test_key",
            groq_model="qwen/qwen3.8-27b",
            http_client=client,
        )
        turn = await intake_engine.process_turn(
            utterance="कविता देवी, उम्र 24 साल, ब्लड ग्रुप O+",
            register=register,
            current_draft=draft,
            language_code="hi-IN",
        )

    assert turn.updated_draft.patient_name == "Kavita Devi"
    assert turn.updated_draft.field_answers == {"Age": 24, "Blood Group": "O+"}
    assert turn.status == "AWAITING_CONFIRMATION"

    # Provider selection checks
    groq_cfg = Settings(llm_provider="groq", groq_api_key="gsk_live", gemini_api_key="")
    assert resolve_active_llm_info(groq_cfg) == ("groq", "qwen/qwen3.8-27b")
    assert isinstance(create_visit_extractor(groq_cfg), GroqVisitExtractor)

    gemini_cfg = Settings(llm_provider="gemini", groq_api_key="", gemini_api_key="AIza_live")
    assert resolve_active_llm_info(gemini_cfg) == ("gemini", "gemini-2.5-flash")
    assert isinstance(create_visit_extractor(gemini_cfg), GeminiVisitExtractor)

    offline_cfg = Settings(llm_provider="groq", groq_api_key="", gemini_api_key="")
    assert resolve_active_llm_info(offline_cfg)[0] == "offline_fallback"
    assert isinstance(create_visit_extractor(offline_cfg), RuleBasedVisitExtractor)


async def test_p301_groq_register_intake_failover_on_429_and_503():
    """Verify P3-01/P3-07: RegisterIntakeEngine fails over from qwen3.8-27b to openai/gpt-oss-120b on 429/503."""
    register = CustomRegister(
        id="REG-2",
        name="Newborn Baseline",
        fields=[
            RegisterField(id="F-1", register_id="REG-2", field_name="Age", field_type="NUMBER"),
            RegisterField(id="F-2", register_id="REG-2", field_name="Blood Group", field_type="TEXT"),
            RegisterField(id="F-3", register_id="REG-2", field_name="House / Ward", field_type="TEXT"),
        ],
    )
    draft = RegisterIntakeDraft(
        register_id="REG-2",
        register_name="Newborn Baseline",
        expected_fields=["Age", "Blood Group", "House / Ward"],
    )

    for error_status in (429, 503):
        called_models: list[str] = []

        def handler(request: httpx.Request, status_code: int = error_status) -> httpx.Response:
            payload = json.loads(request.content.decode("utf-8"))
            called_models.append(payload["model"])
            assert payload.get("max_completion_tokens") == 1024
            if len(called_models) == 1:
                return httpx.Response(status_code, json={"error": {"message": "Rate limited / busy"}})
            assert payload["model"] == "openai/gpt-oss-120b"
            assert payload.get("reasoning_effort") == "low"
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "intent": "LOG_VISIT",
                                        "patient_name": "Radha Bai",
                                        "category": "NEWBORN",
                                        "answers": [
                                            {"field_name": "Age", "value": "twenty seven"},
                                            {"field_name": "Blood Group", "value": "B positive"},
                                            {"field_name": "House / Ward", "value": "House nineteen"},
                                        ],
                                        "spoken_readback": "Radha Bai, Age 27, Blood Group B+, House 19 recorded. Save?",
                                    }
                                )
                            }
                        }
                    ]
                },
            )

        async with httpx.AsyncClient(
            base_url="https://api.groq.com/openai/v1",
            transport=httpx.MockTransport(handler),
        ) as client:
            intake_engine = RegisterIntakeEngine(
                llm_provider="groq",
                groq_api_key="gsk_test_key",
                groq_model="qwen/qwen3.8-27b",
                http_client=client,
            )
            turn = await intake_engine.process_turn(
                utterance="Radha Bai, age twenty seven, blood group B positive, house nineteen",
                register=register,
                current_draft=draft,
                language_code="en-IN",
            )

        assert called_models == ["qwen/qwen3.8-27b", "openai/gpt-oss-120b"]
        assert intake_engine.last_used_model == "openai/gpt-oss-120b"
        assert turn.updated_draft.patient_name == "Radha Bai"
        assert turn.updated_draft.field_answers == {
            "Age": 27,
            "Blood Group": "B+",
            "House / Ward": "House 19",
        }
        assert turn.status == "AWAITING_CONFIRMATION"


async def test_p301_groq_visit_extractor_failover_tracks_last_used_model():
    """Verify P3-01/P3-07: GroqVisitExtractor fails over & caches 429 cooldown across turns."""
    called_models: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content.decode("utf-8"))
        called_models.append(payload["model"])
        assert payload.get("max_completion_tokens") == 1024
        if payload["model"] == "qwen/qwen3.8-27b":
            return httpx.Response(429, headers={"Retry-After": "45"}, json={"error": {"message": "Too many requests"}})
        if payload["model"] == "openai/gpt-oss-120b":
            return httpx.Response(429, json={"error": {"message": "Too many requests"}})
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "intent": "LOG_VISIT",
                                    "beneficiary_id": "BEN-001",
                                    "beneficiary_name": "Sunita Devi",
                                    "visit_type": "ANC",
                                    "weight_kg": 54.0,
                                    "vaccinations_due": ["TT2"],
                                    "next_visit_date": None,
                                    "ifa_tablets_given": 30,
                                    "notes": "ANC checkup",
                                    "spoken_readback": "Sunita Devi ANC checkup recorded. Save?",
                                }
                            )
                        }
                    }
                ]
            },
        )

    async with httpx.AsyncClient(
        base_url="https://api.groq.com/openai/v1",
        transport=httpx.MockTransport(handler),
    ) as client:
        extractor = GroqVisitExtractor(
            api_key="gsk_test_key",
            model="qwen/qwen3.8-27b",
            http_client=client,
        )
        # Turn 1 hits 429 on qwen & 120b -> falls through to 20b
        result = await extractor.extract(
            utterance="Sunita Devi ANC checkup weight 54 kilo IFA 30 TT2 due",
            current_draft=VisitDraft(),
            language_code="en-IN",
        )
        assert called_models == ["qwen/qwen3.8-27b", "openai/gpt-oss-120b", "openai/gpt-oss-20b"]
        assert extractor.last_used_model == "openai/gpt-oss-20b"
        assert result.beneficiary_name == "Sunita Devi"
        assert result.weight_kg == 54.0

        # Turn 2 immediately skips cooled-down models and calls openai/gpt-oss-20b directly!
        called_models.clear()
        result2 = await extractor.extract(
            utterance="Sunita Devi ANC checkup weight 54 kilo IFA 30 TT2 due",
            current_draft=VisitDraft(),
            language_code="en-IN",
        )
        assert called_models == ["openai/gpt-oss-20b"]
        assert result2.weight_kg == 54.0


async def test_p307_anti_hallucination_numeric_anchor_and_strict_language_lock():
    """Verify P3-07: LLM cannot hallucinate 54->58 kg or drift into wrong language when locked."""
    from app.pipeline.llm import (
        GeminiExtractionPayload,
        LLMProvider,
        matches_locked_language,
        normalize_spoken_numbers_to_digits,
    )

    assert normalize_spoken_numbers_to_digits("House nineteen") == "House 19"
    assert normalize_spoken_numbers_to_digits("nineteen") == "19"
    assert normalize_spoken_numbers_to_digits("fifty four kilo") == "54 kilo"
    assert matches_locked_language("Sunita Devi ANC visit weight 54 kg. Save?", "en-IN") is True
    assert matches_locked_language("सुनीता देवी, वजन 58 किलो। सेव करें?", "en-IN") is False
    assert matches_locked_language("ಸುನಿತಾ ದೇವಿ, ತೂಕ 54 ಕಿಲೋ. ಉಳಿಸಬೇಕೇ?", "kn-IN") is True
    assert matches_locked_language("Sunita Devi, weight 54 kg. Save?", "kn-IN") is False

    class HallucinatingDriftingLLM(LLMProvider):
        async def extract(self, *args, **kwargs) -> GeminiExtractionPayload:
            return GeminiExtractionPayload(
                intent="LOG_VISIT",
                beneficiary_id="BEN-001",
                beneficiary_name="Sunita Devi",
                visit_type="ANC",
                weight_kg=58.0,  # Hallucinated! User said 54 kilo!
                ifa_tablets_given=20,  # Hallucinated! User said 30!
                spoken_readback="सुनीता देवी, वजन 58 किलो दर्ज किया। सेव करें?",  # Wrong language for en-IN!
            )

    engine = ClinicalStateEngine(provider=HallucinatingDriftingLLM())
    turn = await engine.process_turn(
        utterance="Sunita Devi ANC checkup, weight 54 kilo, no bleeding, no fever, IFA goli 30.",
        current_draft=VisitDraft(),
        language_code="en-IN",
    )
    assert turn.updated_draft.weight_kg == 54.0
    assert turn.updated_draft.ifa_tablets_given == 30
    assert matches_locked_language(turn.extraction.spoken_readback, "en-IN") is True
    assert "54" in turn.extraction.spoken_readback
    assert "सुनीता" not in turn.extraction.spoken_readback


async def test_p309_empty_draft_yes_save_overrides_llm_saved_lie_and_blocks_ghost_save():
    """Verify P3-09: empty draft + 'Yes save' says 'nothing to save yet' and non-confirming words never ghost-save."""
    from app.pipeline.llm import GeminiExtractionPayload, LLMProvider, is_explicit_save_confirmation

    assert is_explicit_save_confirmation("Yes save") is True
    assert is_explicit_save_confirmation("haan theek hai") is True
    assert is_explicit_save_confirmation("sari") is True
    assert is_explicit_save_confirmation("ह") is False
    assert is_explicit_save_confirmation("ये") is False
    assert is_explicit_save_confirmation("अच्छा") is False
    assert is_explicit_save_confirmation("hmm") is False
    assert is_explicit_save_confirmation("No, weight is 2.8 kilo") is False

    class PrematureConfirmLLM(LLMProvider):
        async def extract(self, *args, **kwargs) -> GeminiExtractionPayload:
            return GeminiExtractionPayload(
                intent="CONFIRM_SAVE",
                spoken_readback="Thank you, the record has been saved!",  # Hallucinated save lie!
            )

    engine = ClinicalStateEngine(provider=PrematureConfirmLLM())

    # 1. Fresh empty draft + "Yes save" -> must NOT save AND must say "nothing to save yet"!
    empty_turn = await engine.process_turn(
        utterance="Yes save",
        current_draft=VisitDraft(),
        language_code="en-IN",
        readback_completed=False,
    )
    assert empty_turn.should_save is False
    assert empty_turn.status == "LISTENING"
    assert "nothing to save" in empty_turn.extraction.spoken_readback.lower()
    assert "has been saved" not in empty_turn.extraction.spoken_readback.lower()

    # 2. Populated draft after readback + ambiguous non-confirm phrase -> must NOT ghost-save!
    populated = VisitDraft(beneficiary_id="BEN-001", beneficiary_name="Sunita Devi", visit_type="ANC", weight_kg=54.0)
    ghost_turn = await engine.process_turn(
        utterance="wait let me check the diary",
        current_draft=populated,
        language_code="en-IN",
        readback_completed=True,
    )
    assert ghost_turn.should_save is False
    assert ghost_turn.status == "AWAITING_CONFIRMATION"
    assert ghost_turn.updated_draft.beneficiary_name == "Sunita Devi"
    assert "has been saved" not in ghost_turn.extraction.spoken_readback.lower()

    # 3. Populated draft after readback + explicit "Yes save" -> saves cleanly!
    real_save = await engine.process_turn(
        utterance="Yes save",
        current_draft=populated,
        language_code="en-IN",
        readback_completed=True,
    )
    assert real_save.should_save is True
    assert real_save.status == "SAVED"
