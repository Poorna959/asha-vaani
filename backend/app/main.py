"""FastAPI Entry Point for ASHA Vaani Voice Field Register Orchestrator."""

from __future__ import annotations

from collections.abc import Callable
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, WebSocket
from fastapi.middleware.cors import CORSMiddleware

from app.config import Settings, get_settings
from app.db import Database
from app.pipeline.compliance import ComplianceGuardrail
from app.pipeline.danger_signs import DangerSignDetector
from app.pipeline.gnani_stt import BaseSTTClient, create_stt_client
from app.pipeline.gnani_tts import BaseTTSClient, create_tts_client
from app.pipeline.llm import ClinicalStateEngine, GeminiVisitExtractor
from app.pipeline.session import VoiceSessionOrchestrator
from app.routers.registers import router as registers_router
from app.routers.visits import router as visits_router
from app.services.register_service import RegisterService


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
            provider=GeminiVisitExtractor(
                api_key=cfg.gemini_api_key,
                model=cfg.gemini_model,
            )
        )

        app.state.settings = cfg
        app.state.db = db
        app.state.register_service = RegisterService(db)
        app.state.danger_detector = danger_detector
        app.state.compliance_guardrail = compliance_guardrail
        app.state.clinical_engine = engine
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
        return {
            "status": "ok",
            "gnani_mode": "mock" if cfg.mock_gnani else "live",
            "stt_model": "prisma-v3.0",
            "tts_model": "timbre-v3.0",
            "llm_model": cfg.gemini_model,
            "danger_signs_count": len(app.state.danger_detector.canonical_codes),
        }

    @app.websocket("/ws/session")
    async def voice_session_endpoint(websocket: WebSocket) -> None:
        orchestrator = VoiceSessionOrchestrator(
            websocket=websocket,
            db=app.state.db,
            register_service=app.state.register_service,
            danger_detector=app.state.danger_detector,
            compliance_guardrail=app.state.compliance_guardrail,
            clinical_engine=app.state.clinical_engine,
            stt_client=app.state.stt_factory(),
            tts_client=app.state.tts_factory(),
            settings=cfg,
        )
        await orchestrator.run()

    return app


app = create_app()
