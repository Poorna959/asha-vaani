"""Full-Duplex Async Voice Session Orchestrator & Instant Barge-In Controller."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, Optional

from fastapi import WebSocket, WebSocketDisconnect

from app.config import Settings, get_settings
from app.db import Database
from app.models import (
    CustomRegister,
    DangerSignAlert,
    LanguageCode,
    PatientRecordCreate,
    RegisterIntakeDraft,
    SessionStatus,
    VisitDraft,
    VisitRecordCreate,
    VisitType,
    WsBargeInClearEvent,
    WsDangerEscalationEvent,
    WsErrorEvent,
    WsFormUpdateEvent,
    WsMetricsEvent,
    WsPatientSavedEvent,
    WsRegisterIntakeUpdateEvent,
    WsTranscriptEvent,
    WsVisitSavedEvent,
)
from app.pipeline.compliance import ComplianceGuardrail
from app.pipeline.danger_signs import DangerSignDetector
from app.pipeline.gnani_stt import (
    ASHA_CLINICAL_KEYTERMS,
    BaseSTTClient,
    MockPrismaSTTClient,
    TranscriptEvent,
    create_stt_client,
)
from app.pipeline.gnani_tts import BaseTTSClient, create_tts_client
from app.pipeline.llm import ClinicalStateEngine, RegisterIntakeEngine, resolve_active_llm_info
from app.pipeline.vad import AdaptiveEndpointVAD, VadEvent
from app.services.event_bus import DashboardEventBus
from app.services.followup import build_follow_up_create
from app.services.register_service import (
    PatientNotFoundError,
    RegisterNotFoundError,
    RegisterService,
)

logger = logging.getLogger(__name__)


class VoiceSessionOrchestrator:
    """Coordinates a single full-duplex WebSocket voice session for an ASHA worker."""

    def __init__(
        self,
        websocket: WebSocket,
        db: Database,
        register_service: RegisterService,
        danger_detector: DangerSignDetector,
        compliance_guardrail: ComplianceGuardrail,
        clinical_engine: ClinicalStateEngine,
        stt_client: Optional[BaseSTTClient] = None,
        tts_client: Optional[BaseTTSClient] = None,
        settings: Optional[Settings] = None,
        register_intake_engine: Optional[RegisterIntakeEngine] = None,
        event_bus: Optional[DashboardEventBus] = None,
    ) -> None:
        self.ws = websocket
        self.db = db
        self.register_service = register_service
        self.danger_detector = danger_detector
        self.compliance_guardrail = compliance_guardrail
        self.clinical_engine = clinical_engine
        self.settings = settings or get_settings()
        self.event_bus = event_bus
        self.register_intake_engine = register_intake_engine or RegisterIntakeEngine(
            api_key=self.settings.gemini_api_key,
            model=self.settings.gemini_model,
            groq_api_key=self.settings.groq_api_key,
            groq_model=self.settings.groq_model,
            groq_base_url=self.settings.groq_base_url,
            llm_provider=self.settings.llm_provider,
        )

        self.stt: BaseSTTClient = stt_client or create_stt_client(self.settings)
        self.tts: BaseTTSClient = tts_client or create_tts_client(self.settings)
        self.vad = AdaptiveEndpointVAD(
            sample_rate=self.settings.sample_rate,
            silence_tail_ms=self.settings.vad_silence_tail_ms,
            speech_threshold=self.settings.vad_speech_threshold,
        )

        # Session state
        self.language_code: LanguageCode | str = "indic-auto"
        self.status: SessionStatus = "LISTENING"
        self.draft: VisitDraft = VisitDraft()
        self.readback_completed: bool = False
        self.patient_context: Optional[dict[str, Any]] = None
        self.active_register: Optional[CustomRegister] = None
        self.register_draft: Optional[RegisterIntakeDraft] = None
        self._turn_transcripts: list[str] = []

        # Concurrency & barge-in state
        self._send_lock = asyncio.Lock()
        self._stt_listener_task: Optional[asyncio.Task[None]] = None
        self._response_task: Optional[asyncio.Task[None]] = None
        self._is_speaking: bool = False
        self._speech_end_ts: Optional[float] = None
        self._last_barge_in_cutoff_ms: Optional[float] = None
        self._closed: bool = False

    # ------------------------------------------------------------------
    # Thread-Safe WebSocket Emitters
    # ------------------------------------------------------------------

    async def _send_json(self, payload: dict[str, Any]) -> None:
        if self._closed:
            return
        async with self._send_lock:
            if not self._closed:
                await self.ws.send_json(payload)

    async def _send_bytes(self, pcm_bytes: bytes) -> None:
        if self._closed or not pcm_bytes:
            return
        async with self._send_lock:
            if not self._closed:
                await self.ws.send_bytes(pcm_bytes)

    # ------------------------------------------------------------------
    # Instant Barge-In (< 150ms cutoff)
    # ------------------------------------------------------------------

    async def trigger_barge_in(self) -> bool:
        """Immediately cancel any in-flight LLM/TTS response and flush client audio."""
        cancel_start = time.perf_counter()
        was_active = self._is_speaking or (
            self._response_task is not None and not self._response_task.done()
        )
        if self._response_task is not None and not self._response_task.done():
            self._response_task.cancel()
            try:
                await self._response_task
            except asyncio.CancelledError:
                pass
            except Exception:
                pass
        self._response_task = None
        self._is_speaking = False

        if was_active:
            self.status = "LISTENING"
            self._last_barge_in_cutoff_ms = round((time.perf_counter() - cancel_start) * 1000.0, 2)
            await self._send_json(WsBargeInClearEvent().model_dump())
            return True
        return False

    def _publish_dashboard_event(self, event_type: str, data: dict[str, Any]) -> None:
        """Broadcast live field events to connected PHC Supervisor Dashboards (non-blocking)."""
        if self.event_bus is not None:
            self.event_bus.publish(event_type, data)

    def _publish_danger_escalation(
        self,
        alerts: list[DangerSignAlert],
        transcript: str,
        active_lang: str,
    ) -> None:
        if self.event_bus is None or not alerts:
            return
        primary = alerts[0]
        beneficiary_name = (
            (self.register_draft.patient_name if self.register_draft else None)
            or self.draft.beneficiary_name
            or (self.patient_context or {}).get("beneficiary_name")
            or "Live Field Alert"
        )
        beneficiary_id = (
            self.draft.beneficiary_id
            or (self.patient_context or {}).get("beneficiary_id")
            or "LIVE-SESSION"
        )
        visit_type = (
            (self.register_draft.category if self.register_draft else None)
            or self.draft.visit_type
            or (self.patient_context or {}).get("category")
            or "ANC"
        )
        self.event_bus.publish(
            "danger_escalation",
            {
                "code": primary.code,
                "name": primary.canonical_name,
                "priority": primary.priority,
                "localized_instruction": primary.localized_instruction,
                "all_alerts": [a.model_dump() for a in alerts],
                "beneficiary_id": beneficiary_id,
                "beneficiary_name": beneficiary_name,
                "visit_type": visit_type,
                "language_code": active_lang,
                "transcript": transcript,
            },
        )

    def _resolve_active_llm_model(self, is_register_mode: bool = False) -> str:
        """Return the active/most-recently-used LLM model name for Judge Telemetry HUD."""
        if is_register_mode:
            used = getattr(self.register_intake_engine, "last_used_model", "")
            if used:
                return str(used)
        else:
            used = getattr(self.clinical_engine.provider, "last_used_model", "")
            if used:
                return str(used)
        _, configured_model = resolve_active_llm_info(self.settings)
        return configured_model

    # ------------------------------------------------------------------
    # Patient Baseline Pre-Selection (FR-7.6)
    # ------------------------------------------------------------------

    async def _select_patient(self, patient_id: str) -> None:
        try:
            ctx = await self.register_service.build_followup_context(patient_id)
        except PatientNotFoundError as exc:
            await self._send_json(
                WsErrorEvent(message=str(exc), code="PATIENT_NOT_FOUND").model_dump()
            )
            return

        # Exit custom-register intake mode when locking a patient for routine follow-up
        self.active_register = None
        self.register_draft = None
        self.patient_context = ctx
        self.draft = VisitDraft(
            beneficiary_id=ctx["beneficiary_id"],
            beneficiary_name=ctx["beneficiary_name"],
            visit_type=ctx["category"],
        )
        self.readback_completed = False
        self.status = "LISTENING"
        await self._send_json(
            WsFormUpdateEvent(
                status=self.status,
                record=self.draft,
                spoken_reply="",
            ).model_dump()
        )

    # ------------------------------------------------------------------
    # Custom Register Voice Intake Mode Selection (FR-7.2)
    # ------------------------------------------------------------------

    @staticmethod
    def _infer_register_category(register_name: str) -> VisitType:
        low = register_name.lower()
        if any(tok in low for tok in ("newborn", "hbnc", "neonate", "baby")):
            return "NEWBORN"
        if any(tok in low for tok in ("immuni", "vaccin")):
            return "IMMUNISATION"
        return "ANC"

    async def _select_register(self, register_id: str) -> None:
        try:
            register = await self.register_service.get_register(register_id)
        except RegisterNotFoundError as exc:
            await self._send_json(
                WsErrorEvent(message=str(exc), code="REGISTER_NOT_FOUND").model_dump()
            )
            return

        self.patient_context = None
        self.draft = VisitDraft()
        self.active_register = register
        self.register_draft = RegisterIntakeDraft(
            register_id=register.id,
            register_name=register.name,
            category=self._infer_register_category(register.name),
            expected_fields=[f.field_name for f in register.fields],
        )
        self.readback_completed = False
        self._turn_transcripts.clear()
        self.status = "LISTENING"
        await self._send_json(
            WsRegisterIntakeUpdateEvent(
                status="LISTENING",
                draft=self.register_draft,
                spoken_reply="",
            ).model_dump()
        )

    async def _stream_tts_reply(
        self,
        safe_reply: str,
        active_lang: str,
        effective_status: SessionStatus,
        turn_start: float,
        asr_latency_ms: float,
        llm_latency_ms: float,
        active_llm_model: str = "",
        danger_fast_path: bool = False,
    ) -> None:
        """Stream synthesized 16kHz PCM audio via Gnani Timbre v3.0 (cancellable on barge-in)."""
        if not safe_reply:
            return
        self._is_speaking = True
        if effective_status == "AWAITING_CONFIRMATION":
            self.status = "SPEAKING_READBACK"
        tts_start = time.perf_counter()
        first_audio_sent = False
        try:
            async for pcm_chunk in self.tts.synthesize_stream(
                text=safe_reply,
                language_code=active_lang,
            ):
                if not first_audio_sent:
                    first_audio_sent = True
                    now = time.perf_counter()
                    ttfa_ms = (now - turn_start) * 1000.0
                    tts_first_ms = (now - tts_start) * 1000.0
                    asr_rounded = round(asr_latency_ms, 1)
                    tts_rounded = round(tts_first_ms, 1)
                    await self._send_json(
                        WsMetricsEvent(
                            ttfa_ms=round(ttfa_ms, 1),
                            asr_latency_ms=asr_rounded,
                            stt_latency_ms=asr_rounded,
                            llm_latency_ms=round(llm_latency_ms, 1),
                            tts_latency_ms=tts_rounded,
                            tts_ttfa_ms=tts_rounded,
                            barge_in_cutoff_ms=self._last_barge_in_cutoff_ms,
                            noise_floor_rms=self.vad.noise_floor_rms,
                            active_llm_model=active_llm_model,
                            danger_fast_path=danger_fast_path,
                        ).model_dump()
                    )
                await self._send_bytes(pcm_chunk)
        except asyncio.CancelledError:
            raise
        finally:
            self._is_speaking = False
            if self.status == "SPEAKING_READBACK":
                self.status = "AWAITING_CONFIRMATION"

    # ------------------------------------------------------------------
    # Final Transcript -> Danger Signs -> LLM -> FR-6 Guardrail -> TTS
    # ------------------------------------------------------------------

    async def _respond_to_final_transcript(self, event: TranscriptEvent) -> None:
        turn_start = self._speech_end_ts or time.perf_counter()
        self._speech_end_ts = None
        self.status = "PROCESSING"
        self._turn_transcripts.append(event.text)

        active_lang = (
            self.language_code
            if self.language_code != "indic-auto"
            else (event.language or "indic-auto")
        )

        is_register_mode = self.active_register is not None and self.register_draft is not None
        active_visit_type = (
            self.register_draft.category
            if is_register_mode and self.register_draft is not None
            else self.draft.visit_type
        )

        # 1. Deterministic NHM Danger-Sign Check (independent of LLM, < 1ms — active in BOTH modes)
        danger_alerts: list[DangerSignAlert] = self.danger_detector.detect(
            text=event.text,
            language_code=active_lang,
            visit_type=active_visit_type,
        )
        fast_tts_task: Optional[asyncio.Task[None]] = None
        if danger_alerts:
            for alert in danger_alerts:
                if alert.code not in self.draft.danger_signs:
                    self.draft.danger_signs.append(alert.code)
            primary = danger_alerts[0]
            await self._send_json(
                WsDangerEscalationEvent(
                    code=primary.code,
                    name=primary.canonical_name,
                    priority=primary.priority,
                    localized_instruction=primary.localized_instruction,
                    all_alerts=danger_alerts,
                ).model_dump()
            )
            self._publish_danger_escalation(
                alerts=danger_alerts,
                transcript=event.text,
                active_lang=active_lang,
            )
            # P3-01 Parallel Danger-Sign TTS Fast-Path:
            # Begin streaming the FR-6 template-locked localized PHC referral audio immediately
            # in parallel with background LLM structured extraction (< 450ms TTFA).
            fast_compliance = self.compliance_guardrail.enforce(
                proposed_reply="",
                language_code=active_lang,
                user_utterance=event.text,
                danger_alerts=danger_alerts,
            )
            if fast_compliance.safe_text:
                self.status = "ESCALATED"
                fast_tts_task = asyncio.create_task(
                    self._stream_tts_reply(
                        safe_reply=fast_compliance.safe_text,
                        active_lang=active_lang,
                        effective_status="ESCALATED",
                        turn_start=turn_start,
                        asr_latency_ms=event.latency_ms,
                        llm_latency_ms=0.0,
                        active_llm_model=self._resolve_active_llm_model(is_register_mode=is_register_mode),
                        danger_fast_path=True,
                    )
                )

        try:
            # Branch A: FR-7.2 Custom Register Voice Intake Mode
            if is_register_mode and self.active_register is not None and self.register_draft is not None:
                llm_start = time.perf_counter()
                intake_result = await self.register_intake_engine.process_turn(
                    utterance=event.text,
                    register=self.active_register,
                    current_draft=self.register_draft,
                    language_code=active_lang,
                    readback_completed=self.readback_completed,
                )
                llm_latency_ms = (time.perf_counter() - llm_start) * 1000.0

                self.register_draft = intake_result.updated_draft
                self.readback_completed = intake_result.readback_completed or bool(danger_alerts)

                compliance = self.compliance_guardrail.enforce(
                    proposed_reply=intake_result.spoken_readback,
                    language_code=active_lang,
                    user_utterance=event.text,
                    danger_alerts=danger_alerts,
                )
                safe_reply = compliance.safe_text
                effective_status: SessionStatus = intake_result.status
                if danger_alerts and not intake_result.should_save:
                    effective_status = "ESCALATED"
                self.status = effective_status

                if intake_result.should_save and self.register_draft.patient_name:
                    saved_patient = await self.register_service.add_patient(
                        PatientRecordCreate(
                            register_id=self.active_register.id,
                            patient_name=self.register_draft.patient_name,
                            category=self.register_draft.category,
                            field_answers=dict(self.register_draft.field_answers),
                        )
                    )
                    profile_view = await self.register_service.get_patient_profile_view(saved_patient.id)
                    # Refresh register schema in case new fields were auto-synced
                    self.active_register = await self.register_service.get_register(self.active_register.id)
                    await self._send_json(
                        WsPatientSavedEvent(patient=saved_patient, profile=profile_view).model_dump()
                    )
                    self._publish_dashboard_event(
                        "patient_saved",
                        {
                            "patient": saved_patient.model_dump(),
                            "register_name": self.active_register.name,
                        },
                    )
                    await self._send_json(
                        WsRegisterIntakeUpdateEvent(
                            status="SAVED",
                            draft=self.register_draft,
                            spoken_reply=safe_reply,
                        ).model_dump()
                    )
                    # Reset intake draft for the next patient in this register
                    self.register_draft = RegisterIntakeDraft(
                        register_id=self.active_register.id,
                        register_name=self.active_register.name,
                        category=self._infer_register_category(self.active_register.name),
                        expected_fields=[f.field_name for f in self.active_register.fields],
                    )
                    self.readback_completed = False
                    self._turn_transcripts.clear()
                else:
                    diffs = intake_result.field_diffs
                    await self._send_json(
                        WsRegisterIntakeUpdateEvent(
                            status=effective_status,
                            draft=self.register_draft,
                            field_diff=diffs[0] if diffs else None,
                            field_diffs=diffs,
                            spoken_reply=safe_reply,
                        ).model_dump()
                    )

                if fast_tts_task is not None:
                    await fast_tts_task
                else:
                    await self._stream_tts_reply(
                        safe_reply=safe_reply,
                        active_lang=active_lang,
                        effective_status=effective_status,
                        turn_start=turn_start,
                        asr_latency_ms=event.latency_ms,
                        llm_latency_ms=llm_latency_ms,
                        active_llm_model=self._resolve_active_llm_model(is_register_mode=True),
                        danger_fast_path=False,
                    )
                return

            # Branch B: Standard Routine Home-Visit Voice Mode (ANC / NEWBORN / IMMUNISATION)
            # 2. Clinical Structured Extraction & Read-Back State Machine
            llm_start = time.perf_counter()
            patients = await self.db.list_patients()
            roster_dicts = [p.model_dump() for p in patients]

            turn_result = await self.clinical_engine.process_turn(
                utterance=event.text,
                current_draft=self.draft,
                language_code=active_lang,
                readback_completed=self.readback_completed,
                patient_context=self.patient_context,
                roster=roster_dicts,
            )
            llm_latency_ms = (time.perf_counter() - llm_start) * 1000.0

            # 2b. Secondary deterministic danger-sign check on LLM-normalized English notes
            # (catches English clinical phrases that streaming STT wrote phonetically in Indic script)
            turn_notes = str(turn_result.extraction.extracted_fields.get("notes") or "").strip()
            if turn_notes:
                note_alerts = self.danger_detector.detect(
                    text=turn_notes,
                    language_code=active_lang,
                    visit_type=turn_result.updated_draft.visit_type or active_visit_type,
                )
                existing_codes = {a.code for a in danger_alerts}
                new_note_alerts = [a for a in note_alerts if a.code not in existing_codes]
                if new_note_alerts:
                    danger_alerts.extend(new_note_alerts)
                    for alert in new_note_alerts:
                        if alert.code not in self.draft.danger_signs:
                            self.draft.danger_signs.append(alert.code)
                    primary = danger_alerts[0]
                    await self._send_json(
                        WsDangerEscalationEvent(
                            code=primary.code,
                            name=primary.canonical_name,
                            priority=primary.priority,
                            localized_instruction=primary.localized_instruction,
                            all_alerts=danger_alerts,
                        ).model_dump()
                    )
                    self._publish_danger_escalation(
                        alerts=danger_alerts,
                        transcript=turn_notes or event.text,
                        active_lang=active_lang,
                    )

            # Preserve deterministically detected danger signs onto updated draft
            for code in self.draft.danger_signs:
                if code not in turn_result.updated_draft.danger_signs:
                    turn_result.updated_draft.danger_signs.append(code)

            self.draft = turn_result.updated_draft
            self.readback_completed = turn_result.readback_completed or bool(danger_alerts)

            # 3. FR-6 Pre-TTS Compliance Firewall (Zero Diagnosis / Zero Medicine Advice)
            compliance = self.compliance_guardrail.enforce(
                proposed_reply=turn_result.extraction.spoken_readback,
                language_code=active_lang,
                user_utterance=event.text,
                danger_alerts=danger_alerts,
            )
            safe_reply = compliance.safe_text

            effective_status = turn_result.status
            if danger_alerts and not turn_result.should_save:
                effective_status = "ESCALATED"
            self.status = effective_status

            # 4. Commit to SQLite & Auto-Schedule Follow-Up ONLY on Confirmed Save
            if turn_result.should_save:
                visit_payload = VisitRecordCreate(
                    beneficiary_id=self.draft.beneficiary_id or "BEN-WALKIN",
                    beneficiary_name=self.draft.beneficiary_name or "Unknown Beneficiary",
                    visit_type=self.draft.visit_type or "ANC",
                    weight_kg=self.draft.weight_kg,
                    vaccinations_due=list(self.draft.vaccinations_due),
                    next_visit_date=self.draft.next_visit_date,
                    ifa_tablets_given=self.draft.ifa_tablets_given,
                    has_danger_sign=bool(self.draft.danger_signs),
                    danger_signs=list(self.draft.danger_signs),
                    notes=self.draft.notes,
                    raw_transcript=" | ".join(self._turn_transcripts),
                    language_code=active_lang if active_lang in (
                        "hi-IN", "kn-IN", "ta-IN", "te-IN", "en-IN", "indic-auto"
                    ) else "hi-IN",
                )
                saved_visit = await self.db.create_visit(visit_payload)
                baseline_fields = (self.patient_context or {}).get("baseline_fields")
                saved_follow_up = await self.db.create_follow_up(
                    build_follow_up_create(saved_visit, baseline_fields=baseline_fields)
                )
                await self._send_json(
                    WsVisitSavedEvent(visit=saved_visit, follow_up=saved_follow_up).model_dump()
                )
                self._publish_dashboard_event(
                    "visit_saved",
                    {
                        "visit": saved_visit.model_dump(),
                        "follow_up": saved_follow_up.model_dump(),
                    },
                )
                # Emit final SAVED form state before clearing draft + baseline context for next house
                await self._send_json(
                    WsFormUpdateEvent(
                        status="SAVED",
                        record=self.draft,
                        spoken_reply=safe_reply,
                    ).model_dump()
                )
                self.draft = VisitDraft()
                self.patient_context = None
                self.readback_completed = False
                self._turn_transcripts.clear()
            else:
                diffs = turn_result.extraction.field_diffs
                await self._send_json(
                    WsFormUpdateEvent(
                        status=effective_status,
                        record=self.draft,
                        field_diff=diffs[0] if diffs else None,
                        field_diffs=diffs,
                        spoken_reply=safe_reply,
                    ).model_dump()
                )

            # 5. Stream Synthesized 16kHz PCM Audio via Gnani Timbre v3.0 (Cancellable on Barge-In)
            if fast_tts_task is not None:
                await fast_tts_task
            else:
                await self._stream_tts_reply(
                    safe_reply=safe_reply,
                    active_lang=active_lang,
                    effective_status=effective_status,
                    turn_start=turn_start,
                    asr_latency_ms=event.latency_ms,
                    llm_latency_ms=llm_latency_ms,
                    active_llm_model=self._resolve_active_llm_model(is_register_mode=False),
                    danger_fast_path=False,
                )
        finally:
            if fast_tts_task is not None and not fast_tts_task.done():
                fast_tts_task.cancel()
                try:
                    await fast_tts_task
                except (asyncio.CancelledError, Exception):
                    pass

    async def _stt_listener_loop(self) -> None:
        """Consume partial and final STT transcripts concurrently."""
        try:
            async for event in self.stt.transcripts():
                await self._send_json(
                    WsTranscriptEvent(
                        text=event.text,
                        is_final=event.is_final,
                        language=event.language,
                        confidence=event.confidence,
                    ).model_dump()
                )
                if event.is_final:
                    # Cancel any prior response still playing before starting the new turn
                    if self._response_task is not None and not self._response_task.done():
                        await self.trigger_barge_in()
                    self._response_task = asyncio.create_task(
                        self._respond_to_final_transcript(event)
                    )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._closed:
                logger.exception("STT listener failed: %s", exc)
                await self._send_json(
                    WsErrorEvent(message=f"STT stream error: {exc}", code="STT_ERROR").model_dump()
                )

    # ------------------------------------------------------------------
    # Incoming Frame Handlers (Binary PCM & JSON Control)
    # ------------------------------------------------------------------

    async def _handle_pcm_frame(self, pcm_bytes: bytes) -> None:
        vad_event = self.vad.process_pcm(pcm_bytes)
        if vad_event == VadEvent.SPEECH_START:
            # Instant barge-in cancellation if agent is currently reading back!
            await self.trigger_barge_in()

        await self.stt.send_audio(pcm_bytes)

        if vad_event == VadEvent.SPEECH_END:
            self._speech_end_ts = time.perf_counter()
            await self.stt.finalize_turn()

    async def _handle_control_message(self, raw_text: str) -> None:
        try:
            msg = json.loads(raw_text)
        except json.JSONDecodeError:
            await self._send_json(
                WsErrorEvent(message="Invalid JSON control message.", code="INVALID_JSON").model_dump()
            )
            return

        msg_type = msg.get("type")

        if msg_type == "session_start":
            lang = msg.get("language_code")
            if lang:
                self.language_code = lang
                await self.stt.update_language(lang)
            register_id = msg.get("register_id")
            patient_id = msg.get("patient_id")
            if register_id:
                await self._select_register(register_id)
            elif patient_id:
                await self._select_patient(patient_id)
            return

        if msg_type == "patient_select":
            patient_id = msg.get("patient_id")
            if patient_id:
                await self._select_patient(patient_id)
            return

        if msg_type == "register_select":
            register_id = msg.get("register_id")
            if register_id:
                await self._select_register(register_id)
            return

        if msg_type == "language_change":
            lang = msg.get("language_code")
            if lang:
                self.language_code = lang
                await self.stt.update_language(lang)
            return

        if msg_type == "barge_in":
            await self.trigger_barge_in()
            return

        if msg_type == "finalize_turn":
            self._speech_end_ts = time.perf_counter()
            await self.stt.finalize_turn()
            return

        if msg_type == "reset_draft":
            await self.trigger_barge_in()
            self.readback_completed = False
            self._turn_transcripts.clear()
            self.status = "LISTENING"
            if msg.get("mode") == "visit" or self.active_register is None:
                self.active_register = None
                self.register_draft = None
                self.draft = VisitDraft()
                self.patient_context = None
                await self._send_json(
                    WsFormUpdateEvent(status="LISTENING", record=self.draft).model_dump()
                )
            else:
                self.register_draft = RegisterIntakeDraft(
                    register_id=self.active_register.id,
                    register_name=self.active_register.name,
                    category=self._infer_register_category(self.active_register.name),
                    expected_fields=[f.field_name for f in self.active_register.fields],
                )
                await self._send_json(
                    WsRegisterIntakeUpdateEvent(
                        status="LISTENING",
                        draft=self.register_draft,
                    ).model_dump()
                )
            return

        if msg_type == "text_turn":
            text = str(msg.get("text", "")).strip()
            if not text:
                return
            turn_lang = msg.get("language_code") or self.language_code
            self._speech_end_ts = time.perf_counter()
            if isinstance(self.stt, MockPrismaSTTClient):
                await self.stt.inject_simulated_utterance(text, str(turn_lang))
            else:
                ev = TranscriptEvent(
                    text=text,
                    is_final=True,
                    language=str(turn_lang),
                    confidence=1.0,
                    latency_ms=0.0,
                )
                await self._send_json(
                    WsTranscriptEvent(
                        text=ev.text,
                        is_final=True,
                        language=ev.language,
                        confidence=1.0,
                    ).model_dump()
                )
                if self._response_task is not None and not self._response_task.done():
                    await self.trigger_barge_in()
                self._response_task = asyncio.create_task(self._respond_to_final_transcript(ev))
            return

        await self._send_json(
            WsErrorEvent(
                message=f"Unknown control message type: {msg_type}",
                code="UNKNOWN_MESSAGE_TYPE",
            ).model_dump()
        )

    # ------------------------------------------------------------------
    # Main Session Lifecycle
    # ------------------------------------------------------------------

    async def run(self) -> None:
        """Accept WebSocket, open Prisma STT stream, and process full-duplex frames."""
        await self.ws.accept()
        await self.stt.connect(
            language_code=self.language_code,
            keyterms=list(ASHA_CLINICAL_KEYTERMS),
        )
        self._stt_listener_task = asyncio.create_task(self._stt_listener_loop())

        try:
            while True:
                packet = await self.ws.receive()
                if packet.get("type") == "websocket.disconnect":
                    break
                if packet.get("bytes") is not None:
                    await self._handle_pcm_frame(packet["bytes"])
                elif packet.get("text") is not None:
                    await self._handle_control_message(packet["text"])
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            logger.exception("Voice session error: %s", exc)
        finally:
            await self.close()

    async def close(self) -> None:
        """Cleanly cancel background tasks and close upstream STT socket."""
        self._closed = True
        if self._response_task is not None and not self._response_task.done():
            self._response_task.cancel()
            try:
                await self._response_task
            except (asyncio.CancelledError, Exception):
                pass
        await self.stt.close()
        if self._stt_listener_task is not None and not self._stt_listener_task.done():
            self._stt_listener_task.cancel()
            try:
                await self._stt_listener_task
            except (asyncio.CancelledError, Exception):
                pass
