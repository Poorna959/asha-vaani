"""Tests for Issue #7: Full-Duplex Session Orchestrator, VAD, Barge-In & FastAPI Integration."""

from __future__ import annotations

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
    """Collect JSON events and binary PCM frames until metrics + audio chunks arrive."""
    events: list[dict] = []
    audio_chunks: list[bytes] = []
    got_form_or_saved = False

    for _ in range(100):
        packet = ws.receive()
        if packet.get("text") is not None:
            import json

            data = json.loads(packet["text"])
            events.append(data)
            if data.get("type") in ("form_update", "visit_saved"):
                got_form_or_saved = True
        elif packet.get("bytes") is not None:
            audio_chunks.append(packet["bytes"])
            if got_form_or_saved and len(audio_chunks) >= min_pcm_chunks:
                # Drain remaining frames cleanly when mock TTS is fast
                pass
        if got_form_or_saved and len(audio_chunks) >= min_pcm_chunks:
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
    cfg = Settings(mock_gnani=True, gemini_api_key="")
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
    cfg = Settings(mock_gnani=True, gemini_api_key="", vad_silence_tail_ms=100)
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
    cfg = Settings(mock_gnani=True, gemini_api_key="", vad_silence_tail_ms=100)
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
    cfg = Settings(mock_gnani=True, gemini_api_key="")
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
