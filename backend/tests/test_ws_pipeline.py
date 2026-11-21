"""Tests for Issue #7: Full-Duplex Session Orchestrator, VAD, Barge-In & FastAPI Integration."""

from __future__ import annotations

import json
import time

import numpy as np
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.pipeline.gnani_stt import MockPrismaSTTClient
from app.pipeline.gnani_tts import MockTimbreTTSClient
from app.pipeline.llm import ClinicalStateEngine, RuleBasedVisitExtractor
from app.pipeline.vad import AdaptiveEndpointVAD, VadEvent


def _make_pcm_frame(amplitude: float, duration_ms: int = 20, freq_hz: float = 300.0, sr: int = 16000) -> bytes:
    """Generate one synthetic 16kHz 16-bit signed LE PCM frame."""
    n = int(sr * duration_ms / 1000)
    t = np.arange(n, dtype=np.float32) / float(sr)
    samples = (np.sin(2.0 * np.pi * freq_hz * t) * amplitude * 32767.0).astype("<i2")
    return samples.tobytes()


def _make_silence_frame(duration_ms: int = 20, sr: int = 16000) -> bytes:
    n = int(sr * duration_ms / 1000)
    return np.zeros(n, dtype="<i2").tobytes()


def _receive_turn_packets(ws, min_pcm_chunks: int = 1) -> tuple[list[dict], list[bytes]]:
    """Collect JSON events and binary PCM frames until final form/intake update + audio chunks arrive."""
    events: list[dict] = []
    audio_chunks: list[bytes] = []
    got_state_update = False

    for _ in range(100):
        packet = ws.receive()
        if packet.get("text") is not None:
            data = json.loads(packet["text"])
            events.append(data)
            if data.get("type") in ("form_update", "register_intake_update"):
                got_state_update = True
        elif packet.get("bytes") is not None:
            audio_chunks.append(packet["bytes"])
        if got_state_update and len(audio_chunks) >= min_pcm_chunks:
            break

    return events, audio_chunks


def test_adaptive_vad_ignores_steady_noise_and_detects_speech_endpoints():
    vad = AdaptiveEndpointVAD(
        sample_rate=16000,
        silence_tail_ms=200,
        speech_threshold=0.5,
        min_speech_ms=60,
    )

    # 1. Steady low-level ambient street noise must NOT trigger speech
    noise = _make_pcm_frame(amplitude=0.005, duration_ms=20)
    for _ in range(15):
        assert vad.process_pcm(noise) == VadEvent.SILENCE

    # 2. Loud voice speech frames (60ms = 3 frames) trigger SPEECH_START
    speech = _make_pcm_frame(amplitude=0.35, duration_ms=20)
    speech_events = [vad.process_pcm(speech) for _ in range(5)]
    assert VadEvent.SPEECH_START in speech_events
    assert speech_events[-1] == VadEvent.SPEECH_CONTINUE

    # 3. Trailing silence (>= 200ms = 10 frames) triggers SPEECH_END
    silence = _make_silence_frame(duration_ms=20)
    tail_events = [vad.process_pcm(silence) for _ in range(12)]
    assert VadEvent.SPEECH_END in tail_events


def test_health_and_danger_signs_endpoints():
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        body = health.json()
        assert body["status"] == "ok"
        assert body["stt_model"] == "prisma-v3.0"
        assert body["tts_model"] == "timbre-v3.0"
        assert body["danger_signs_count"] == 12

        catalog = client.get("/api/danger-signs")
        assert catalog.status_code == 200
        assert len(catalog.json()["danger_signs"]) == 12


def test_full_3_turn_voice_visit_correction_and_persistence_over_ws():
    """End-to-end: binary PCM -> STT -> extract -> read-back -> voice correction -> save -> follow-up."""
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(
            scenarios=[
                "Sunita Devi ANC checkup weight 3.2 kilo IFA goli 30 TT2 injection due",
                "No, weight is 2.8 kilo",
                "Haan theek hai",
            ],
            partial_delay_s=0.0,
        ),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=3),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "hi-IN"})

            # Turn 1: Stream real 16kHz PCM speech + silence so VAD triggers SPEECH_END
            speech_frame = _make_pcm_frame(amplitude=0.4, duration_ms=20)
            silence_frame = _make_silence_frame(duration_ms=20)
            for _ in range(5):
                ws.send_bytes(speech_frame)
            for _ in range(7):
                ws.send_bytes(silence_frame)

            events1, audio1 = _receive_turn_packets(ws, min_pcm_chunks=3)
            types1 = [e["type"] for e in events1]
            assert "transcript" in types1
            assert "form_update" in types1
            assert "metrics" in types1
            assert len(audio1) >= 3

            form1 = next(e for e in events1 if e["type"] == "form_update")
            assert form1["status"] == "AWAITING_CONFIRMATION"
            assert form1["record"]["beneficiary_id"] == "BEN-001"
            assert form1["record"]["weight_kg"] == 3.2

            metrics1 = next(e for e in events1 if e["type"] == "metrics")
            assert metrics1["ttfa_ms"] < 800.0

            # Drain any remaining audio frames from sentence 2 of turn 1 before turn 2
            ws.send_json({"type": "barge_in"})

            # Turn 2: Voice correction ("No, weight is 2.8 kilo")
            for _ in range(5):
                ws.send_bytes(speech_frame)
            for _ in range(7):
                ws.send_bytes(silence_frame)

            events2, audio2 = _receive_turn_packets(ws, min_pcm_chunks=3)
            form2 = next(e for e in events2 if e["type"] == "form_update")
            assert form2["status"] == "AWAITING_CONFIRMATION"
            assert form2["record"]["weight_kg"] == 2.8
            assert form2["field_diff"] == {"field": "weight_kg", "old_value": 3.2, "new_value": 2.8}
            assert len(audio2) >= 3

            ws.send_json({"type": "barge_in"})

            # Turn 3: Voice confirmation ("Haan theek hai") -> saves visit & creates follow-up
            for _ in range(5):
                ws.send_bytes(speech_frame)
            for _ in range(7):
                ws.send_bytes(silence_frame)

            events3, _ = _receive_turn_packets(ws, min_pcm_chunks=2)
            saved_ev = next(e for e in events3 if e["type"] == "visit_saved")
            assert saved_ev["visit"]["beneficiary_id"] == "BEN-001"
            assert saved_ev["visit"]["weight_kg"] == 2.8
            assert saved_ev["follow_up"]["status"] == "PENDING"

        # Verify persisted records via REST endpoints
        visits_resp = client.get("/api/visits")
        assert visits_resp.status_code == 200
        assert len(visits_resp.json()) == 1
        assert visits_resp.json()[0]["weight_kg"] == 2.8

        fups_resp = client.get("/api/follow-ups", params={"beneficiary_id": "BEN-001"})
        assert fups_resp.status_code == 200
        assert len(fups_resp.json()) >= 2  # 1 seeded + 1 newly created from visit


def test_instant_barge_in_cancels_active_tts_under_150ms():
    """Verify speaking mid-readback immediately triggers barge_in_clear within < 150ms."""
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        # Slow TTS pacing so TTS is guaranteed to be mid-stream when speech arrives
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.04, max_chunks_per_sentence=40),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Sunita Devi ANC checkup weight 54 kilo IFA goli 30 TT2 injection due",
                }
            )

            # Wait until first binary PCM audio chunk of read-back arrives
            while True:
                pkt = ws.receive()
                if pkt.get("bytes") is not None:
                    break

            # Interrupt mid-readback with loud voice PCM frames -> VAD SPEECH_START
            barge_start = time.perf_counter()
            speech_frame = _make_pcm_frame(amplitude=0.45, duration_ms=20)
            for _ in range(5):
                ws.send_bytes(speech_frame)

            got_barge_clear = False
            for _ in range(30):
                pkt = ws.receive()
                if pkt.get("text") is not None:
                    import json

                    data = json.loads(pkt["text"])
                    if data.get("type") == "barge_in_clear":
                        got_barge_clear = True
                        break

            barge_latency_ms = (time.perf_counter() - barge_start) * 1000.0
            assert got_barge_clear is True
            assert barge_latency_ms < 150.0, f"Barge-in took {barge_latency_ms:.1f}ms (target < 150ms)"


def test_danger_sign_escalation_and_patient_select_continuity_over_ws():
    """Verify FR-5 + FR-6 danger-sign lock and FR-7.6 pre-selected patient baseline continuity."""
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            # 1. Pre-select registered patient BEN-002 (Lakshmi Gowda Baby)
            ws.send_json({"type": "session_start", "language_code": "hi-IN", "patient_id": "BEN-002"})
            pre_form = ws.receive_json()
            assert pre_form["type"] == "form_update"
            assert pre_form["record"]["beneficiary_id"] == "BEN-002"
            assert pre_form["record"]["visit_type"] == "NEWBORN"

            # 2. Speak a newborn danger sign + adversarial medicine question
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "High fever in a newborn, what medicine should I give?",
                }
            )
            events, _ = _receive_turn_packets(ws, min_pcm_chunks=2)
            escalation = next(e for e in events if e["type"] == "danger_escalation")
            assert escalation["code"] == "DS_NB_HIGH_FEVER_COLD"
            assert escalation["priority"] == "IMMEDIATE_EMERGENCY"

            form = next(e for e in events if e["type"] == "form_update")
            assert form["status"] == "ESCALATED"
            assert "DS_NB_HIGH_FEVER_COLD" in form["record"]["danger_signs"]
            assert "पीएचसी" in form["spoken_reply"]
            assert "paracetamol" not in form["spoken_reply"].lower()

            # Drain remaining audio and confirm saving the flagged visit
            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": "haan theek hai"})
            save_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            saved = next(e for e in save_events if e["type"] == "visit_saved")
            assert saved["visit"]["has_danger_sign"] is True
            assert saved["follow_up"]["status"] == "ESCALATED"

        # Verify danger filter endpoint returns the flagged record
        danger_visits = client.get("/api/visits", params={"only_danger": True}).json()
        assert len(danger_visits) == 1
        assert danger_visits[0]["beneficiary_id"] == "BEN-002"
        assert danger_visits[0]["danger_signs"] == ["DS_NB_HIGH_FEVER_COLD"]


def test_multi_house_session_clears_patient_context_after_save():
    """Verify saving a pre-selected patient visit clears patient_context for the next house."""
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            # House 1: Pre-select BEN-001 (Sunita Devi) and save visit
            ws.send_json({"type": "session_start", "language_code": "en-IN", "patient_id": "BEN-001"})
            pre_form = ws.receive_json()
            assert pre_form["record"]["beneficiary_id"] == "BEN-001"

            ws.send_json({"type": "text_turn", "text": "Weight 55 kilo, TT2 injection due"})
            _receive_turn_packets(ws, min_pcm_chunks=1)
            ws.send_json({"type": "barge_in"})

            ws.send_json({"type": "text_turn", "text": "Yes save"})
            save_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            saved1 = next(e for e in save_events if e["type"] == "visit_saved")
            assert saved1["visit"]["beneficiary_id"] == "BEN-001"
            ws.send_json({"type": "barge_in"})

            # House 2 (same WS session): Speak about Meena Kumari (BEN-003)
            ws.send_json({"type": "text_turn", "text": "Meena Kumari ANC checkup weight 51 kilo"})
            h2_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            h2_form = next(e for e in h2_events if e["type"] == "form_update")
            assert h2_form["record"]["beneficiary_id"] == "BEN-003"
            assert h2_form["record"]["beneficiary_name"] == "Meena Kumari"
            assert h2_form["record"]["weight_kg"] == 51.0


def test_fr72_custom_register_voice_intake_correction_save_and_safety_over_ws():
    """Verify FR-7.2: voice onboarding into custom register dynamic fields, diff correction, save, search & safety."""
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        # 1. Create a custom register with dynamic fields
        reg_resp = client.post(
            "/api/registers",
            json={
                "name": "High Risk Maternal Intake",
                "description": "First-visit custom survey",
                "fields": [
                    {"field_name": "Age", "field_type": "NUMBER"},
                    {"field_name": "Blood Group", "field_type": "TEXT"},
                    {"field_name": "Medical History", "field_type": "TEXT"},
                    {"field_name": "House / Ward", "field_type": "TEXT"},
                ],
            },
        )
        assert reg_resp.status_code == 201
        reg_id = reg_resp.json()["id"]

        with client.websocket_connect("/ws/session") as ws:
            # 2. Enter custom-register voice intake mode
            ws.send_json({"type": "session_start", "language_code": "en-IN", "register_id": reg_id})
            init_ev = ws.receive_json()
            assert init_ev["type"] == "register_intake_update"
            assert init_ev["draft"]["register_id"] == reg_id
            assert init_ev["draft"]["expected_fields"] == [
                "Age",
                "Blood Group",
                "Medical History",
                "House / Ward",
            ]

            # 3. Turn 1: Speak patient intake details
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Savitha Rao, Age 26, Blood Group O+, Medical History High BP, House 19",
                }
            )
            t1_events, t1_pcm = _receive_turn_packets(ws, min_pcm_chunks=1)
            assert len(t1_pcm) >= 1
            upd1 = next(e for e in t1_events if e["type"] == "register_intake_update")
            assert upd1["status"] == "AWAITING_CONFIRMATION"
            assert upd1["draft"]["patient_name"] == "Savitha Rao"
            assert upd1["draft"]["field_answers"]["Age"] == 26
            assert upd1["draft"]["field_answers"]["Blood Group"] == "O+"
            assert upd1["draft"]["field_answers"]["Medical History"] == "High BP"
            assert upd1["draft"]["field_answers"]["House / Ward"] == "House 19"
            ws.send_json({"type": "barge_in"})

            # 4. Turn 2: Voice correction on dynamic field ("No, Age is 28")
            ws.send_json({"type": "text_turn", "text": "No, Age is 28"})
            t2_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            upd2 = next(e for e in t2_events if e["type"] == "register_intake_update")
            assert upd2["draft"]["field_answers"]["Age"] == 28
            assert upd2["field_diff"] == {"field": "Age", "old_value": 26, "new_value": 28}
            ws.send_json({"type": "barge_in"})

            # 5. Turn 3: Confirm save -> commits PatientRecord + returns Field: Answer ProfileView
            ws.send_json({"type": "text_turn", "text": "Yes save"})
            t3_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            saved_pat = next(e for e in t3_events if e["type"] == "patient_saved")
            new_patient_id = saved_pat["patient"]["id"]
            assert saved_pat["patient"]["patient_name"] == "Savitha Rao"
            assert saved_pat["patient"]["field_answers"]["Age"] == 28
            assert any(
                item["field_name"] == "Blood Group" and item["answer"] == "O+"
                for item in saved_pat["profile"]["items"]
            )
            ws.send_json({"type": "barge_in"})

            # 6. Safety check: Danger signs STILL escalate immediately during custom register intake
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Geeta Devi, Age 24, heavy bleeding in pregnancy",
                }
            )
            esc_events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            esc = next(e for e in esc_events if e["type"] == "danger_escalation")
            assert esc["code"] == "DS_MAT_BLEEDING"
            esc_upd = next(e for e in esc_events if e["type"] == "register_intake_update")
            assert esc_upd["status"] == "ESCALATED"
            assert "PHC" in esc_upd["spoken_reply"]
            ws.send_json({"type": "barge_in"})

            # 7. Continuity check: Tap newly voice-onboarded patient -> switches back to visit follow-up mode
            ws.send_json({"type": "patient_select", "patient_id": new_patient_id})
            # Drain any stale frames until form_update
            fup_form = None
            for _ in range(10):
                pkt = ws.receive()
                if pkt.get("text"):
                    parsed = json.loads(pkt["text"])
                    if parsed.get("type") == "form_update":
                        fup_form = parsed
                        break
            assert fup_form is not None
            assert fup_form["record"]["beneficiary_id"] == new_patient_id
            assert fup_form["record"]["beneficiary_name"] == "Savitha Rao"

        # 8. Verify voice-onboarded patient appears in in-register search & CSV export
        search_res = client.get(f"/api/registers/{reg_id}/patients", params={"q": "Savitha"}).json()
        assert len(search_res) == 1
        assert search_res[0]["field_answers"]["Age"] == 28
        csv_text = client.get(f"/api/registers/{reg_id}/export", params={"format": "csv"}).content.decode("utf-8-sig")
        assert "Savitha Rao" in csv_text and "O+" in csv_text


def test_p301_parallel_danger_sign_tts_fast_path_beats_slow_llm():
    """Verify P3-01: Layer-1 danger sign streams TTS in parallel without waiting for slow LLM."""
    import asyncio

    class SlowVisitExtractor(RuleBasedVisitExtractor):
        last_used_model = "qwen/qwen3.8-27b-simulated"

        def __init__(self, delay_s: float = 0.35) -> None:
            self.delay_s = delay_s

        async def extract(self, *args, **kwargs):
            await asyncio.sleep(self.delay_s)
            return await super().extract(*args, **kwargs)

    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=SlowVisitExtractor(delay_s=0.35)),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "hi-IN"})
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Sunita Devi ANC checkup, heavy vaginal bleeding",
                }
            )

            ordered_kinds: list[str] = []
            events: list[dict] = []
            audio_chunks: list[bytes] = []
            got_form = False

            for _ in range(80):
                pkt = ws.receive()
                if pkt.get("text") is not None:
                    data = json.loads(pkt["text"])
                    events.append(data)
                    ordered_kinds.append(data["type"])
                    if data.get("type") == "form_update":
                        got_form = True
                elif pkt.get("bytes") is not None:
                    audio_chunks.append(pkt["bytes"])
                    ordered_kinds.append("pcm_audio")
                if got_form and len(audio_chunks) >= 2:
                    break

            assert "danger_escalation" in ordered_kinds
            assert "metrics" in ordered_kinds
            assert "pcm_audio" in ordered_kinds
            assert "form_update" in ordered_kinds

            # Critical P3-01 proof: first PCM audio & metrics MUST arrive BEFORE the 350ms LLM form_update!
            assert ordered_kinds.index("danger_escalation") < ordered_kinds.index("pcm_audio")
            assert ordered_kinds.index("pcm_audio") < ordered_kinds.index("form_update")

            metrics = next(e for e in events if e["type"] == "metrics")
            assert metrics["danger_fast_path"] is True
            assert metrics["llm_latency_ms"] == 0.0
            assert metrics["ttfa_ms"] < 200.0
            assert metrics["stt_latency_ms"] == metrics["asr_latency_ms"]
            assert metrics["tts_ttfa_ms"] == metrics["tts_latency_ms"]
            assert metrics["active_llm_model"] == "qwen/qwen3.8-27b-simulated"

            form = next(e for e in events if e["type"] == "form_update")
            assert form["status"] == "ESCALATED"
            assert form["record"]["beneficiary_id"] == "BEN-001"
            assert "DS_MAT_BLEEDING" in form["record"]["danger_signs"]


def test_p301_granular_telemetry_and_fast_path_barge_in_cutoff():
    """Verify P3-01: fast-path TTS cancels cleanly on barge-in and reports barge_in_cutoff_ms & noise_floor_rms."""
    import asyncio

    class SlowVisitExtractor(RuleBasedVisitExtractor):
        last_used_model = "rule_based_slow_test"

        async def extract(self, *args, **kwargs):
            await asyncio.sleep(0.4)
            return await super().extract(*args, **kwargs)

    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.04, max_chunks_per_sentence=30),
        clinical_engine=ClinicalStateEngine(provider=SlowVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            # Feed a few ambient noise frames so adaptive VAD noise_floor_rms is active
            noise_frame = _make_pcm_frame(amplitude=0.004, duration_ms=20)
            for _ in range(5):
                ws.send_bytes(noise_frame)

            # Trigger danger-sign fast-path while slow LLM is still running
            ws.send_json({"type": "text_turn", "text": "Newborn high fever and convulsions"})
            while True:
                pkt = ws.receive()
                if pkt.get("bytes") is not None:
                    break

            # Barge-in mid-fast-path TTS while LLM is ALSO still running
            barge_start = time.perf_counter()
            speech_frame = _make_pcm_frame(amplitude=0.45, duration_ms=20)
            for _ in range(5):
                ws.send_bytes(speech_frame)

            got_clear = False
            for _ in range(30):
                pkt = ws.receive()
                if pkt.get("text") is not None:
                    data = json.loads(pkt["text"])
                    if data.get("type") == "barge_in_clear":
                        got_clear = True
                        break
            assert got_clear is True
            assert (time.perf_counter() - barge_start) * 1000.0 < 150.0

            # Follow-up normal turn emits granular telemetry including barge_in_cutoff_ms & noise_floor_rms
            ws.send_json({"type": "text_turn", "text": "Sunita Devi ANC checkup weight 54 kilo"})
            events2, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            metrics2 = next(e for e in events2 if e["type"] == "metrics")
            assert metrics2["danger_fast_path"] is False
            assert metrics2["llm_latency_ms"] >= 350.0
            assert metrics2["barge_in_cutoff_ms"] is not None
            assert 0.0 <= metrics2["barge_in_cutoff_ms"] < 150.0
            assert metrics2["noise_floor_rms"] > 0.0
            assert metrics2["active_llm_model"] == "rule_based_slow_test"


def test_p308_negated_english_never_false_triggers_via_llm_notes_rescan():
    """Verify P3-08: Step 2b never false-triggers danger escalation for symptoms negated in raw speech."""
    from app.pipeline.llm import GeminiExtractionPayload, LLMProvider

    class SloppyNotesLLM(LLMProvider):
        async def extract(self, *args, **kwargs) -> GeminiExtractionPayload:
            return GeminiExtractionPayload(
                intent="LOG_VISIT",
                beneficiary_id="BEN-001",
                beneficiary_name="Sunita Devi",
                visit_type="ANC",
                weight_kg=54.0,
                ifa_tablets_given=30,
                # Sloppy LLM drops "no" when summarizing notes!
                notes="ANC checkup; bleeding, high fever, severe headache checked",
                spoken_readback="Sunita Devi ANC checkup, weight 54 kg, 30 IFA tablets. Shall I save?",
            )

    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=SloppyNotesLLM()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Sunita Devi ANC checkup, weight 54 kilo, no bleeding, no high fever, no severe headache, IFA goli 30.",
                }
            )
            events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            assert not any(e["type"] == "danger_escalation" for e in events)
            form = next(e for e in events if e["type"] == "form_update")
            assert form["status"] == "AWAITING_CONFIRMATION"
            assert form["record"]["danger_signs"] == []
            assert form["record"]["weight_kg"] == 54.0


def test_p309_spurious_noise_does_not_vanish_fr72_register_form_and_empty_yes_save_refuses():
    """Verify P3-09: ambient noise ('ह', 'ये', 'अच्छा', 'hmm') never wipes FR-7.2 draft before save."""
    from app.pipeline.session import is_spurious_noise_transcript

    for noise in ("", "...", "ह।", "ये।", "अच्छा।", "हम्म.", "hmm", "uh", "ಹ", "ಹ್ಮ್", "ಇವು"):
        assert is_spurious_noise_transcript(noise) is True
    for real in ("Yes save", "sari", "No, Age is 28", "Savitha Rao, Age 26, Blood Group O+, House 19", "cancel"):
        assert is_spurious_noise_transcript(real) is False

    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="")
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        reg = client.post(
            "/api/registers",
            json={
                "name": "ANC Intake Test",
                "fields": [
                    {"field_name": "Age", "field_type": "NUMBER"},
                    {"field_name": "Blood Group", "field_type": "TEXT"},
                    {"field_name": "House / Ward", "field_type": "TEXT"},
                ],
            },
        ).json()
        reg_id = reg["id"]

        with client.websocket_connect("/ws/session") as ws:
            # 1. Fresh empty visit draft + "Yes save" -> must say nothing to save and NOT save
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            ws.send_json({"type": "text_turn", "text": "Yes save"})
            ev_empty, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            assert not any(e["type"] == "visit_saved" for e in ev_empty)
            form_empty = next(e for e in ev_empty if e["type"] == "form_update")
            assert form_empty["status"] == "LISTENING"
            assert "nothing to save" in form_empty["spoken_reply"].lower()
            ws.send_json({"type": "barge_in"})

            # 2. Switch to FR-7.2 custom register intake & speak Savitha Rao details
            ws.send_json({"type": "register_select", "register_id": reg_id})
            for _ in range(10):
                pkt = ws.receive()
                if pkt.get("text") and json.loads(pkt["text"]).get("type") == "register_intake_update":
                    break

            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Savitha Rao, Age 26, Blood Group O positive, House nineteen",
                }
            )
            ev_fill, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            upd_fill = next(e for e in ev_fill if e["type"] == "register_intake_update")
            assert upd_fill["draft"]["patient_name"] == "Savitha Rao"
            assert upd_fill["draft"]["field_answers"]["Age"] == 26
            assert upd_fill["draft"]["field_answers"]["Blood Group"] == "O+"
            assert upd_fill["draft"]["field_answers"]["House / Ward"] == "House 19"
            ws.send_json({"type": "barge_in"})

            # 3. Inject ambient noise/fillers -> MUST NOT ghost-save or clear the form!
            for filler in ("ह।", "ये।", "अच्छा।", "hmm"):
                ws.send_json({"type": "text_turn", "text": filler})

            # 4. Now voice-correct Age 26 -> 28 -> draft MUST still be intact!
            ws.send_json({"type": "text_turn", "text": "No, Age is 28"})
            ev_fix, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            assert not any(e["type"] == "patient_saved" for e in ev_fix)
            upd_fix = next(e for e in ev_fix if e["type"] == "register_intake_update")
            assert upd_fix["draft"]["patient_name"] == "Savitha Rao"
            assert upd_fix["draft"]["field_answers"]["Age"] == 28
            assert upd_fix["field_diff"] == {"field": "Age", "old_value": 26, "new_value": 28}
            ws.send_json({"type": "barge_in"})

            # 5. Explicit "Yes save" commits exactly 1 patient with normalized answers
            ws.send_json({"type": "text_turn", "text": "Yes save"})
            ev_save, _ = _receive_turn_packets(ws, min_pcm_chunks=1)
            saved = next(e for e in ev_save if e["type"] == "patient_saved")
            assert saved["patient"]["patient_name"] == "Savitha Rao"
            assert saved["patient"]["field_answers"]["Age"] == 28
            assert saved["patient"]["field_answers"]["House / Ward"] == "House 19"

        # Confirm zero empty visits saved and exactly 1 Savitha Rao patient saved
        assert client.get("/api/visits").json() == []
        patients = client.get(f"/api/registers/{reg_id}/patients").json()
        assert len(patients) == 1
        assert patients[0]["field_answers"]["House / Ward"] == "House 19"


def test_stt_noise_gating_hesitation_filter_and_single_connect_language_lock():
    """Verify STT anti-buffering & anti-hallucination guarantees:
    1. Multi-word hesitation hallucinations ('ummmm ummm what to do....') are suppressed before UI emit.
    2. Real clinical utterances & commands (even starting with 'umm') are NEVER filtered.
    3. Steady ambient room noise is NEVER streamed raw to non-mock STT; 300ms pre-speech ring buffer
       is replayed on SPEECH_START so opening consonants are never clipped.
    4. Immediate `session_start` opens upstream STT ONCE with the locked language_code.
    """
    from app.pipeline.gnani_stt import BaseSTTClient, TranscriptEvent
    from app.pipeline.session import is_spurious_noise_transcript

    # 1. Hesitation & ASR silence hallucination filter
    for hallucination in (
        "ummmm ummm what to do....",
        "ummm umm what to do",
        "ummmm ummm",
        "uhhh hmm...",
        "hmm hmm",
        "um um um",
        "ह।",
        "ये।",
    ):
        assert is_spurious_noise_transcript(hallucination) is True, f"Should filter {hallucination!r}"

    for valid_speech in (
        "Sunita Devi ANC checkup weight 54 kilo",
        "umm yes save",
        "what medicine should I give for fever?",
        "No, weight is 2.8 kilo",
        "Savitha Rao, Age 26, Blood Group O+, House 19",
        "Yes save",
        "Haan theek hai",
        "19",
    ):
        assert is_spurious_noise_transcript(valid_speech) is False, f"Must keep {valid_speech!r}"

    # 2. Recording fake live STT client (non-MockPrismaSTTClient) to verify VAD gating + single connect
    class RecordingLiveSTT(BaseSTTClient):
        def __init__(self):
            self.connect_langs: list[str] = []
            self.update_langs: list[str] = []
            self.sent_frames: list[bytes] = []
            self.finalize_count: int = 0
            self._queue = None

        async def connect(self, language_code="indic-auto", keyterms=None):
            import asyncio

            self.language_code = str(language_code)
            self.connect_langs.append(self.language_code)
            self._queue = asyncio.Queue()

        async def send_audio(self, pcm_bytes: bytes):
            self.sent_frames.append(pcm_bytes)

        async def finalize_turn(self):
            self.finalize_count += 1
            # Emit one hallucination (must be suppressed before WS!) followed by the real utterance
            await self._queue.put(
                TranscriptEvent(text="ummmm ummm what to do....", is_final=True, language=self.language_code)
            )
            await self._queue.put(
                TranscriptEvent(
                    text="Sunita Devi ANC checkup weight 54 kilo",
                    is_final=True,
                    language=self.language_code,
                )
            )

        async def update_language(self, language_code):
            self.language_code = str(language_code)
            self.update_langs.append(self.language_code)

        async def transcripts(self):
            while True:
                ev = await self._queue.get()
                if ev is None:
                    break
                yield ev

        async def close(self):
            if self._queue is not None:
                await self._queue.put(None)

    rec_stt = RecordingLiveSTT()
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: rec_stt,
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            # Immediate session_start must lock language_code on FIRST connect with zero redundant update
            ws.send_json({"type": "session_start", "language_code": "en-IN"})

            # Stream 10 frames of steady low-level ambient room noise -> must NOT be sent raw during silence!
            noise_frame = _make_pcm_frame(amplitude=0.004, duration_ms=20)
            for _ in range(10):
                ws.send_bytes(noise_frame)

            # Now speak (6 speech frames) + trailing silence (7 frames -> SPEECH_END)
            speech_frame = _make_pcm_frame(amplitude=0.40, duration_ms=20)
            silence_frame = _make_silence_frame(duration_ms=20)
            for _ in range(6):
                ws.send_bytes(speech_frame)
            for _ in range(7):
                ws.send_bytes(silence_frame)

            events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)

    # Single connect directly with en-IN, zero redundant update_language
    assert rec_stt.connect_langs == ["en-IN"]
    assert rec_stt.update_langs == []
    # Pre-speech ring buffer (10 noise lead-in frames + 3 speech onset frames) + remaining speech + trailing zero
    assert rec_stt.finalize_count == 1
    assert len(rec_stt.sent_frames) >= 16  # 10 pre-speech + 6 speech + >=1 zero tail
    # Hallucinated "ummmm ummm what to do...." was NEVER sent to the client UI!
    transcript_texts = [e["text"] for e in events if e["type"] == "transcript"]
    assert transcript_texts == ["Sunita Devi ANC checkup weight 54 kilo"]
    form = next(e for e in events if e["type"] == "form_update")
    assert form["record"]["beneficiary_name"] == "Sunita Devi"
    assert form["record"]["weight_kg"] == 54.0


def test_rapid_back_to_back_stt_segments_merge_instead_of_dropping_first_clause():
    """Verify mid-sentence Prisma splits never drop clause 1 when clause 2 arrives mid-LLM."""
    import asyncio
    from app.pipeline.gnani_stt import BaseSTTClient, TranscriptEvent

    class SlowRuleBasedExtractor(RuleBasedVisitExtractor):
        async def extract(self, *args, **kwargs):
            await asyncio.sleep(0.08)
            return await super().extract(*args, **kwargs)

    class TwoClauseSTT(BaseSTTClient):
        def __init__(self):
            self._queue = None

        async def connect(self, language_code="indic-auto", keyterms=None):
            self.language_code = str(language_code)
            self._queue = asyncio.Queue()

        async def send_audio(self, pcm_bytes: bytes):
            pass

        async def finalize_turn(self):
            # Emit clause 1 then clause 2 20ms later (while clause 1 is still mid-LLM!)
            await self._queue.put(
                TranscriptEvent(
                    text="Sunita Devi ANC checkup weight 54 kilo no bleeding",
                    is_final=True,
                    language=self.language_code,
                )
            )
            await asyncio.sleep(0.02)
            await self._queue.put(
                TranscriptEvent(
                    text="IFA goli 30",
                    is_final=True,
                    language=self.language_code,
                )
            )

        async def update_language(self, language_code):
            self.language_code = str(language_code)

        async def transcripts(self):
            while True:
                ev = await self._queue.get()
                if ev is None:
                    break
                yield ev

        async def close(self):
            if self._queue is not None:
                await self._queue.put(None)

    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    app = create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: TwoClauseSTT(),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=SlowRuleBasedExtractor()),
    )

    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "en-IN"})
            speech = _make_pcm_frame(amplitude=0.4, duration_ms=20)
            silence = _make_silence_frame(duration_ms=20)
            for _ in range(5):
                ws.send_bytes(speech)
            for _ in range(7):
                ws.send_bytes(silence)

            events, _ = _receive_turn_packets(ws, min_pcm_chunks=1)

    form = next(e for e in events if e["type"] == "form_update")
    assert form["record"]["beneficiary_name"] == "Sunita Devi"
    assert form["record"]["visit_type"] == "ANC"
    assert form["record"]["weight_kg"] == 54.0
    assert form["record"]["ifa_tablets_given"] == 30
    assert form["record"]["danger_signs"] == []
