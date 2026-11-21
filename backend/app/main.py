"""FastAPI Entry Point for ASHA Vaani Voice Field Register Orchestrator."""

from __future__ import annotations

from collections.abc import Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse

from app.config import Settings, get_settings
from app.db import Database
from app.pipeline.compliance import ComplianceGuardrail
from app.pipeline.danger_signs import DangerSignDetector
from app.pipeline.gnani_stt import BaseSTTClient, create_stt_client
from app.pipeline.gnani_tts import BaseTTSClient, create_tts_client
from app.pipeline.llm import (
    ClinicalStateEngine,
    RegisterIntakeEngine,
    create_visit_extractor,
    resolve_active_llm_info,
)
from app.pipeline.session import VoiceSessionOrchestrator
from app.routers.registers import router as registers_router
from app.routers.visits import router as visits_router
from app.services.event_bus import DashboardEventBus
from app.services.register_service import RegisterService

DASHBOARD_HTML_PATH = Path(__file__).resolve().parent / "static" / "dashboard.html"


def create_app(
    settings: Optional[Settings] = None,
    db_path: Optional[str] = None,
    stt_factory: Optional[Callable[[], BaseSTTClient]] = None,
    tts_factory: Optional[Callable[[], BaseTTSClient]] = None,
    clinical_engine: Optional[ClinicalStateEngine] = None,
) -> FastAPI:
    """Build and wire the ASHA Vaani FastAPI application."""
    cfg = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        db = Database(db_path=db_path or cfg.sqlite_path)
        await db.connect()
        await db.seed_default_data()

        danger_detector = DangerSignDetector(cfg.danger_signs_path)
        compliance_guardrail = ComplianceGuardrail(danger_detector=danger_detector)
        engine = clinical_engine or ClinicalStateEngine(
            provider=create_visit_extractor(cfg)
        )
        register_intake_engine = RegisterIntakeEngine(
            api_key=cfg.gemini_api_key,
            model=cfg.gemini_model,
            groq_api_key=cfg.groq_api_key,
            groq_model=cfg.groq_model,
            groq_base_url=cfg.groq_base_url,
            llm_provider=cfg.llm_provider,
        )

        app.state.settings = cfg
        app.state.db = db
        app.state.register_service = RegisterService(db)
        app.state.danger_detector = danger_detector
        app.state.compliance_guardrail = compliance_guardrail
        app.state.clinical_engine = engine
        app.state.register_intake_engine = register_intake_engine
        app.state.event_bus = DashboardEventBus()
        app.state.stt_factory = stt_factory or (lambda: create_stt_client(cfg))
        app.state.tts_factory = tts_factory or (lambda: create_tts_client(cfg))

        try:
            yield
        finally:
            await db.close()

    app = FastAPI(
        title="ASHA Vaani Voice Field Register",
        description="Gnani Voice Hackathon PS1 — Zero-typing Indic Voice Register for ASHA Workers",
        version="0.1.0",
        lifespan=lifespan,
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(registers_router)
    app.include_router(visits_router)

    @app.get("/health", tags=["system"])
    async def health() -> dict[str, object]:
        llm_mode, llm_model = resolve_active_llm_info(cfg)
        return {
            "status": "ok",
            "gnani_mode": "mock" if cfg.mock_gnani else "live",
            "llm_mode": llm_mode,
            "stt_model": "prisma-v3.0",
            "tts_model": "timbre-v3.0",
            "llm_model": llm_model,
            "danger_signs_count": len(app.state.danger_detector.canonical_codes),
        }

    @app.get("/dashboard", response_class=HTMLResponse, tags=["dashboard"])
    async def phc_supervisor_dashboard() -> HTMLResponse:
        """Serve the zero-build Real-Time PHC Supervisor Escalation Dashboard."""
        return HTMLResponse(content=DASHBOARD_HTML_PATH.read_text(encoding="utf-8"))

    @app.websocket("/ws/session")
    async def voice_session_endpoint(websocket: WebSocket) -> None:
        orchestrator = VoiceSessionOrchestrator(
            websocket=websocket,
            db=app.state.db,
            register_service=app.state.register_service,
            danger_detector=app.state.danger_detector,
            compliance_guardrail=app.state.compliance_guardrail,
            clinical_engine=app.state.clinical_engine,
            register_intake_engine=app.state.register_intake_engine,
            event_bus=app.state.event_bus,
            stt_client=app.state.stt_factory(),
            tts_client=app.state.tts_factory(),
            settings=cfg,
        )
        await orchestrator.run()

    return app


app = create_app()
