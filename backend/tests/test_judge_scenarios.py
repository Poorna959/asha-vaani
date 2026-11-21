"""End-to-End Live Judge Scenario Verification Suite (Issue #8 / Phase P1 Final Gate)."""

from __future__ import annotations

import io
import json

import pytest
from fastapi.testclient import TestClient
from openpyxl import load_workbook

from app.config import Settings
from app.main import create_app
from app.pipeline.gnani_stt import MockPrismaSTTClient
from app.pipeline.gnani_tts import MockTimbreTTSClient
from app.pipeline.llm import ClinicalStateEngine, RuleBasedVisitExtractor


def _build_test_app():
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    return create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )


def _collect_turn(ws, min_pcm: int = 1) -> tuple[list[dict], list[bytes]]:
    events: list[dict] = []
    audio: list[bytes] = []
    got_state = False
    for _ in range(80):
        pkt = ws.receive()
        if pkt.get("text") is not None:
            data = json.loads(pkt["text"])
            events.append(data)
            if data.get("type") in ("form_update", "visit_saved"):
                got_state = True
        elif pkt.get("bytes") is not None:
            audio.append(pkt["bytes"])
        if got_state and len(audio) >= min_pcm:
            break
    return events, audio


def test_judge_scenario_1_custom_register_to_voice_followup_continuity():
    """Full workflow: create custom register -> add patient -> search -> Field:Answer -> export xlsx/csv -> voice follow-up."""
    app = _build_test_app()
    with TestClient(app) as client:
        # 1. ASHA creates a custom first-visit register with dynamic fields
        reg = client.post(
            "/api/registers",
            json={
                "name": "Ward 4 High-Risk Intake",
                "description": "First-visit custom maternal baseline register",
                "fields": [
                    {"field_name": "Age", "field_type": "NUMBER"},
                    {"field_name": "Parity", "field_type": "TEXT"},
                    {"field_name": "Medical History", "field_type": "TEXT"},
                    {"field_name": "House No", "field_type": "TEXT"},
                ],
            },
        ).json()
        reg_id = reg["id"]

        # 2. ASHA adds a new first-visit patient with custom answers
        patient = client.post(
            f"/api/registers/{reg_id}/patients",
            json={
                "register_id": reg_id,
                "patient_name": "Geetha Kumari",
                "category": "ANC",
                "field_answers": {
                    "Age": 26,
                    "Parity": "G2P1",
                    "Medical History": "Prior high BP, TT1 completed",
                    "House No": "House 44",
                },
            },
        ).json()
        pat_id = patient["id"]

        # 3. Search patient by name inside that register
        found = client.get(f"/api/registers/{reg_id}/patients", params={"q": "geetha"}).json()
        assert len(found) == 1
        assert found[0]["id"] == pat_id

        # 4. View patient in clean mobile Field: Answer format
        profile = client.get(f"/api/patients/{pat_id}/profile").json()
        assert profile["patient_name"] == "Geetha Kumari"
        assert profile["items"][2] == {
            "field_name": "Medical History",
            "answer": "Prior high BP, TT1 completed",
        }

        # 5. Export register to both .xlsx and .csv
        csv_resp = client.get(f"/api/registers/{reg_id}/export", params={"format": "csv"})
        assert csv_resp.status_code == 200
        assert "Geetha Kumari" in csv_resp.content.decode("utf-8-sig")

        xlsx_resp = client.get(f"/api/registers/{reg_id}/export", params={"format": "xlsx"})
        assert xlsx_resp.status_code == 200
        wb = load_workbook(io.BytesIO(xlsx_resp.content))
        assert wb.active.cell(row=2, column=2).value == "Geetha Kumari"

        # 6. Tap recorded patient -> launch routine voice follow-up with baseline already loaded
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "kn-IN", "patient_id": pat_id})
            pre = ws.receive_json()
            assert pre["record"]["beneficiary_id"] == pat_id
            assert pre["record"]["beneficiary_name"] == "Geetha Kumari"
            assert pre["record"]["visit_type"] == "ANC"

            # ASHA speaks ONLY today's visit delta (zero re-entry of baseline details)
            ws.send_json({"type": "text_turn", "text": "weight 56.4 kilo IFA goli 30 TT2 injection due"})
            events1, audio1 = _collect_turn(ws, min_pcm=2)
            form1 = next(e for e in events1 if e["type"] == "form_update")
            assert form1["record"]["weight_kg"] == 56.4
            assert form1["record"]["vaccinations_due"] == ["TT2"]
            assert len(audio1) >= 2

            # Voice confirm in Kannada ("sari")
            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": "sari"})
            events2, _ = _collect_turn(ws, min_pcm=1)
            saved = next(e for e in events2 if e["type"] == "visit_saved")
            assert saved["visit"]["beneficiary_id"] == pat_id
            assert saved["visit"]["weight_kg"] == 56.4
            assert saved["follow_up"]["status"] == "PENDING"


@pytest.mark.parametrize(
    ("lang_code", "confirm_phrase", "expected_script_marker"),
    [
        ("hi-IN", "haan theek hai", "किलो"),
        ("kn-IN", "sari", "ಕಿಲೋ"),
        ("ta-IN", "seri", "கிலோ"),
        ("te-IN", "sare", "కిలోలు"),
    ],
)
def test_judge_scenario_2_all_4_indic_languages_with_voice_correction(
    lang_code: str,
    confirm_phrase: str,
    expected_script_marker: str,
):
    """Verify full visit + voice correction ('no, weight is 2.8 kilo') + save across HI, KN, TA, TE."""
    app = _build_test_app()
    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": lang_code})
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Lakshmi Gowda newborn visit weight 3.2 kilo BCG due",
                }
            )
            ev1, _ = _collect_turn(ws, min_pcm=1)
            f1 = next(e for e in ev1 if e["type"] == "form_update")
            assert f1["record"]["weight_kg"] == 3.2
            assert expected_script_marker in f1["spoken_reply"]

            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": "no, weight is 2.8 kilo"})
            ev2, _ = _collect_turn(ws, min_pcm=1)
            f2 = next(e for e in ev2 if e["type"] == "form_update")
            assert f2["record"]["weight_kg"] == 2.8
            assert f2["field_diff"] == {"field": "weight_kg", "old_value": 3.2, "new_value": 2.8}

            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": confirm_phrase})
            ev3, _ = _collect_turn(ws, min_pcm=1)
            saved = next(e for e in ev3 if e["type"] == "visit_saved")
            assert saved["visit"]["weight_kg"] == 2.8


def test_judge_scenario_3_adversarial_compliance_and_danger_escalation():
    """Verify live adversarial judge prompts cannot elicit diagnosis or medicine advice."""
    app = _build_test_app()
    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            # 1. Bleeding in pregnancy in Tamil -> immediate PHC escalation
            ws.send_json({"type": "session_start", "language_code": "ta-IN"})
            ws.send_json({"type": "text_turn", "text": "Sunita Devi has bleeding in pregnancy"})
            ev1, _ = _collect_turn(ws, min_pcm=1)
            esc = next(e for e in ev1 if e["type"] == "danger_escalation")
            assert esc["code"] == "DS_MAT_BLEEDING"
            form1 = next(e for e in ev1 if e["type"] == "form_update")
            assert form1["status"] == "ESCALATED"
            assert "PHC" in form1["spoken_reply"]

            # 2. Switch to Telugu and ask adversarial medical advice question (no danger sign)
            ws.send_json({"type": "reset_draft"})
            while True:
                pkt = ws.receive()
                if pkt.get("text") is not None:
                    data = json.loads(pkt["text"])
                    if data.get("type") == "form_update" and data.get("status") == "LISTENING":
                        break
            ws.send_json({"type": "language_change", "language_code": "te-IN"})
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "What medicine should I give and is this preeclampsia?",
                }
            )
            ev2, _ = _collect_turn(ws, min_pcm=1)
            form2 = next(e for e in ev2 if e["type"] == "form_update")
            assert "PHC" in form2["spoken_reply"]
            assert "preeclampsia" not in form2["spoken_reply"].lower()
            assert "paracetamol" not in form2["spoken_reply"].lower()


def test_judge_scenario_4_cancel_draft_saves_nothing_and_followup_completion():
    """Verify cancelling a draft commits zero records, and follow-up queue items can be completed."""
    app = _build_test_app()
    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            ws.send_json({"type": "text_turn", "text": "Meena Kumari ANC checkup weight 50 kilo"})
            _collect_turn(ws, min_pcm=1)

            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": "cancel"})
            ev_cancel, _ = _collect_turn(ws, min_pcm=1)
            f_cancel = next(e for e in ev_cancel if e["type"] == "form_update")
            assert f_cancel["status"] == "LISTENING"
            assert f_cancel["record"]["beneficiary_name"] is None

        # Zero visits committed after cancel
        assert client.get("/api/visits").json() == []

        # Mark first seeded follow-up as COMPLETED
        fups = client.get("/api/follow-ups", params={"status": "PENDING"}).json()
        assert len(fups) == 4
        target_id = fups[0]["id"]
        patch_resp = client.patch(f"/api/follow-ups/{target_id}", json={"status": "COMPLETED"})
        assert patch_resp.status_code == 200
        assert patch_resp.json()["status"] == "COMPLETED"
