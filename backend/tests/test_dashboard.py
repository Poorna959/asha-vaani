"""Tests for Issue #18 (P3-03): Real-Time PHC Supervisor Escalation Dashboard, SSE & Acknowledge."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.db import Database
from app.main import create_app
from app.pipeline.gnani_stt import MockPrismaSTTClient
from app.pipeline.gnani_tts import MockTimbreTTSClient
from app.pipeline.llm import ClinicalStateEngine, RuleBasedVisitExtractor
from app.services.event_bus import DashboardEventBus


def _make_dashboard_test_app():
    cfg = Settings(mock_gnani=True, gemini_api_key="", groq_api_key="", vad_silence_tail_ms=100)
    return create_app(
        settings=cfg,
        db_path=":memory:",
        stt_factory=lambda: MockPrismaSTTClient(partial_delay_s=0.0),
        tts_factory=lambda: MockTimbreTTSClient(chunk_delay_s=0.0, max_chunks_per_sentence=2),
        clinical_engine=ClinicalStateEngine(provider=RuleBasedVisitExtractor()),
    )


def _receive_turn(ws) -> list[dict]:
    events: list[dict] = []
    saw_final = False
    got_form = False
    got_metrics = False
    pcm = 0
    for _ in range(120):
        pkt = ws.receive()
        if pkt.get("text") is not None:
            data = json.loads(pkt["text"])
            if not saw_final:
                if data.get("type") == "transcript" and data.get("is_final"):
                    saw_final = True
                    events.append(data)
                continue
            events.append(data)
            if data.get("type") == "form_update":
                got_form = True
            elif data.get("type") == "metrics":
                got_metrics = True
        elif pkt.get("bytes") is not None and saw_final:
            pcm += 1
        if saw_final and got_form and got_metrics and pcm >= 1:
            break
    return events


def test_dashboard_html_and_initial_summary_endpoints():
    app = _make_dashboard_test_app()
    with TestClient(app) as client:
        page = client.get("/dashboard")
        assert page.status_code == 200
        assert "text/html" in page.headers["content-type"]
        assert "ASHA Vaani — PHC Supervisor Escalation Console" in page.text
        assert "/api/events/stream" in page.text
        assert "Zero Diagnosis or Treatment Advice" in page.text

        summary = client.get("/api/dashboard/summary")
        assert summary.status_code == 200
        body = summary.json()
        assert body["kpis"]["total_visits"] == 0
        assert body["kpis"]["active_escalations"] == 0
        assert body["kpis"]["acknowledged_escalations"] == 0
        assert body["kpis"]["custom_registers"] >= 1
        assert body["kpis"]["total_patients"] >= 8
        assert body["system"]["stt_model"] == "prisma-v3.0"
        assert body["system"]["tts_model"] == "timbre-v3.0"


def test_live_danger_escalation_broadcasts_to_dashboard_and_phc_acknowledge_persists():
    """Speaking a danger sign on /ws/session flashes live alerts & updates after PHC acknowledge."""
    app = _make_dashboard_test_app()
    with TestClient(app) as client:
        with client.websocket_connect("/ws/session") as ws:
            ws.send_json({"type": "session_start", "language_code": "hi-IN", "patient_id": "BEN-001"})
            ws.receive_json()  # pre-loaded form_update

            # 1. Speak a maternal danger sign -> must immediately broadcast to event_bus
            ws.send_json(
                {
                    "type": "text_turn",
                    "text": "Sunita Devi ANC checkup, heavy bleeding in pregnancy",
                }
            )
            ev1 = _receive_turn(ws)
            assert any(e["type"] == "danger_escalation" for e in ev1)

            # Live alert is immediately visible in /api/dashboard/summary BEFORE save!
            summary_live = client.get("/api/dashboard/summary").json()
            assert len(summary_live["recent_live_alerts"]) >= 1
            latest_alert = summary_live["recent_live_alerts"][0]
            assert latest_alert["event"] == "danger_escalation"
            assert latest_alert["data"]["code"] == "DS_MAT_BLEEDING"
            assert latest_alert["data"]["beneficiary_name"] == "Sunita Devi"

            # 2. Confirm save -> visit persisted as unacknowledged escalation
            ws.send_json({"type": "barge_in"})
            ws.send_json({"type": "text_turn", "text": "haan theek hai"})
            ev2 = _receive_turn(ws)
            saved = next(e for e in ev2 if e["type"] == "visit_saved")
            visit_id = saved["visit"]["id"]
            assert saved["visit"]["has_danger_sign"] is True
            assert saved["visit"]["phc_acknowledged"] is False

        summary_before_ack = client.get("/api/dashboard/summary").json()
        assert summary_before_ack["kpis"]["total_visits"] == 1
        assert summary_before_ack["kpis"]["active_escalations"] == 1
        assert summary_before_ack["kpis"]["acknowledged_escalations"] == 0

        # 3. PHC Medical Officer clicks Acknowledge Referral
        ack_resp = client.patch(
            f"/api/visits/{visit_id}/acknowledge",
            json={"acknowledged_by": "Dr. Kavitha (PHC)"},
        )
        assert ack_resp.status_code == 200
        acked = ack_resp.json()
        assert acked["phc_acknowledged"] is True
        assert acked["phc_acknowledged_by"] == "Dr. Kavitha (PHC)"
        assert acked["phc_acknowledged_at"] is not None

        # Verify KPI flips from open -> acknowledged
        summary_after_ack = client.get("/api/dashboard/summary").json()
        assert summary_after_ack["kpis"]["active_escalations"] == 0
        assert summary_after_ack["kpis"]["acknowledged_escalations"] == 1

        # Unknown visit returns 404
        not_found = client.patch("/api/visits/VIS-DOESNOTEXIST/acknowledge")
        assert not_found.status_code == 404


def test_sse_events_stream_endpoint_and_event_bus_fanout():
    """Verify GET /api/events/stream emits valid SSE framing including recent live alerts."""
    app = _make_dashboard_test_app()
    with TestClient(app) as client:
        app.state.event_bus.publish(
            "danger_escalation",
            {
                "code": "DS_NB_UMBILICAL_PUS",
                "name": "Pus discharge or spreading redness around the newborn navel",
                "priority": "HIGH",
                "beneficiary_name": "Lakshmi Gowda Baby",
            },
        )
        sse_resp = client.get("/api/events/stream", params={"once": True})
        assert sse_resp.status_code == 200
        assert "text/event-stream" in sse_resp.headers["content-type"]
        body = sse_resp.text
        assert "event: connected" in body
        assert "DS_NB_UMBILICAL_PUS" in body
        assert "Lakshmi Gowda Baby" in body


async def test_event_bus_live_subscriber_receives_published_events():
    bus = DashboardEventBus()
    async with bus.subscribe() as queue:
        assert bus.subscriber_count == 1
        bus.publish("danger_escalation", {"code": "DS_MAT_CONVULSIONS"})
        ev = await queue.get()
        assert ev["event"] == "danger_escalation"
        assert ev["data"]["code"] == "DS_MAT_CONVULSIONS"
    assert bus.subscriber_count == 0


async def test_sqlite_legacy_visits_table_migrates_phc_acknowledge_columns(tmp_path: Path):
    """Existing local asha_visits.db without phc_acknowledged columns upgrades cleanly."""
    legacy_db = tmp_path / "legacy_asha.db"
    conn = sqlite3.connect(legacy_db)
    conn.execute(
        """
        CREATE TABLE visits (
            id TEXT PRIMARY KEY,
            beneficiary_id TEXT NOT NULL,
            beneficiary_name TEXT NOT NULL,
            visit_type TEXT NOT NULL,
            weight_kg REAL,
            vaccinations_due_json TEXT NOT NULL DEFAULT '[]',
            next_visit_date TEXT,
            ifa_tablets_given INTEGER,
            has_danger_sign INTEGER NOT NULL DEFAULT 0,
            danger_signs_json TEXT NOT NULL DEFAULT '[]',
            notes TEXT NOT NULL DEFAULT '',
            raw_transcript TEXT NOT NULL DEFAULT '',
            language_code TEXT NOT NULL DEFAULT 'hi-IN',
            created_at TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        INSERT INTO visits (
            id, beneficiary_id, beneficiary_name, visit_type, has_danger_sign,
            danger_signs_json, created_at
        ) VALUES ('VIS-LEGACY01', 'BEN-001', 'Sunita Devi', 'ANC', 1, '["DS_MAT_BLEEDING"]', '2026-11-21T10:00:00Z')
        """
    )
    conn.commit()
    conn.close()

    db = Database(db_path=str(legacy_db))
    await db.connect()
    try:
        legacy_visit = await db.get_visit("VIS-LEGACY01")
        assert legacy_visit is not None
        assert legacy_visit.phc_acknowledged is False
        acked = await db.acknowledge_visit("VIS-LEGACY01", "PHC Duty Doctor")
        assert acked is not None
        assert acked.phc_acknowledged is True
        assert acked.phc_acknowledged_by == "PHC Duty Doctor"
    finally:
        await db.close()
