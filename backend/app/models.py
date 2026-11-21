"""Core Pydantic v2 data models and WebSocket protocol schemas for ASHA Vaani."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Literal, Optional
from pydantic import BaseModel, Field


def utc_now_iso() -> str:
    """Return current UTC timestamp in ISO-8601 format."""
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Domain Literals
# ---------------------------------------------------------------------------

FieldType = Literal["TEXT", "NUMBER", "DATE", "BOOLEAN"]
VisitType = Literal["ANC", "NEWBORN", "IMMUNISATION"]
FollowUpStatus = Literal["PENDING", "COMPLETED", "ESCALATED"]
VoiceIntent = Literal[
    "LOG_VISIT",
    "CORRECT_FIELD",
    "CONFIRM_SAVE",
    "CANCEL",
    "ASK_CLARIFICATION",
]
SessionStatus = Literal[
    "LISTENING",
    "PROCESSING",
    "SPEAKING_READBACK",
    "AWAITING_CONFIRMATION",
    "SAVED",
    "ESCALATED",
]
LanguageCode = Literal["hi-IN", "kn-IN", "ta-IN", "te-IN", "en-IN", "indic-auto"]
DangerPriority = Literal["IMMEDIATE_EMERGENCY", "HIGH"]


# ---------------------------------------------------------------------------
# Dynamic Custom Registers & Patient Profiles (FR-7)
# ---------------------------------------------------------------------------


class RegisterFieldCreate(BaseModel):
    """Input schema for defining a dynamic field on a custom register."""

    field_name: str = Field(..., min_length=1, max_length=120)
    field_type: FieldType = "TEXT"
    order_index: int = 0


class RegisterField(RegisterFieldCreate):
    """Persisted custom register field definition."""

    id: str
    register_id: str


class CustomRegisterCreate(BaseModel):
    """Input schema for creating a custom register with dynamic fields."""

    name: str = Field(..., min_length=1, max_length=160)
    description: str = ""
    fields: list[RegisterFieldCreate] = Field(default_factory=list)


class CustomRegister(BaseModel):
    """Persisted custom register including its ordered fields."""

    id: str
    name: str
    description: str = ""
    fields: list[RegisterField] = Field(default_factory=list)
    created_at: str = Field(default_factory=utc_now_iso)


class PatientRecordCreate(BaseModel):
    """Input schema for onboarding a patient into a custom register."""

    id: Optional[str] = None  # Auto-generated (e.g. BEN-009) if omitted
    register_id: str
    patient_name: str = Field(..., min_length=1, max_length=160)
    category: VisitType = "ANC"
    field_answers: dict[str, Any] = Field(default_factory=dict)


class PatientRecordUpdate(BaseModel):
    """Input schema for patching a patient's name, category, or dynamic field answers."""

    patient_name: Optional[str] = Field(default=None, min_length=1, max_length=160)
    category: Optional[VisitType] = None
    field_answers: Optional[dict[str, Any]] = None


class PatientRecord(BaseModel):
    """Persisted patient profile row inside a custom register."""

    id: str
    register_id: str
    patient_name: str
    category: VisitType = "ANC"
    field_answers: dict[str, Any] = Field(default_factory=dict)
    created_at: str = Field(default_factory=utc_now_iso)
    updated_at: str = Field(default_factory=utc_now_iso)
    matched_existing: bool = False


class FieldAnswerItem(BaseModel):
    """Single Field: Answer pair for mobile-friendly patient profile rendering."""

    field_name: str
    answer: Any = None


class PatientProfileView(BaseModel):
    """Clean mobile-friendly Field-Answer view when ASHA taps a recorded patient."""

    patient_id: str
    register_id: str
    register_name: str
    patient_name: str
    category: VisitType
    items: list[FieldAnswerItem] = Field(default_factory=list)
    created_at: str
    updated_at: str


# ---------------------------------------------------------------------------
# Clinical Visits, Voice Diffing & Follow-Ups (FR-4, FR-8)
# ---------------------------------------------------------------------------


class VisitDraft(BaseModel):
    """Mutable in-memory form state during an active voice logging session."""

    beneficiary_id: Optional[str] = None
    beneficiary_name: Optional[str] = None
    visit_type: Optional[VisitType] = None
    weight_kg: Optional[float] = Field(default=None, ge=0.0, le=250.0)
    vaccinations_due: list[str] = Field(default_factory=list)
    next_visit_date: Optional[str] = None  # YYYY-MM-DD
    ifa_tablets_given: Optional[int] = Field(default=None, ge=0, le=365)
    danger_signs: list[str] = Field(default_factory=list)
    notes: str = ""


class VisitRecordCreate(BaseModel):
    """Validated payload committed to SQLite once ASHA confirms read-back."""

    beneficiary_id: str
    beneficiary_name: str
    visit_type: VisitType
    weight_kg: Optional[float] = Field(default=None, ge=0.0, le=250.0)
    vaccinations_due: list[str] = Field(default_factory=list)
    next_visit_date: Optional[str] = None
    ifa_tablets_given: Optional[int] = Field(default=None, ge=0, le=365)
    has_danger_sign: bool = False
    danger_signs: list[str] = Field(default_factory=list)
    notes: str = ""
    raw_transcript: str = ""
    language_code: LanguageCode = "hi-IN"


class VisitRecord(VisitRecordCreate):
    """Persisted home-visit record."""

    id: str
    created_at: str = Field(default_factory=utc_now_iso)
    phc_acknowledged: bool = False
    phc_acknowledged_at: Optional[str] = None
    phc_acknowledged_by: Optional[str] = None


class FieldDiff(BaseModel):
    """Represents a voice-corrected field delta for UI highlight animations."""

    field: str
    old_value: Any = None
    new_value: Any = None


class VoiceExtractionOutput(BaseModel):
    """Structured output returned by the clinical LLM extractor."""

    intent: VoiceIntent = "LOG_VISIT"
    extracted_fields: dict[str, Any] = Field(default_factory=dict)
    field_diffs: list[FieldDiff] = Field(default_factory=list)
    spoken_readback: str = ""


class FollowUpCreate(BaseModel):
    """Input schema for scheduling a follow-up visit in the ASHA queue."""

    beneficiary_id: str
    beneficiary_name: str
    visit_id: Optional[str] = None
    due_date: str  # YYYY-MM-DD
    purpose: str
    vaccines_due: list[str] = Field(default_factory=list)
    status: FollowUpStatus = "PENDING"


class FollowUp(FollowUpCreate):
    """Persisted follow-up item in the village route queue."""

    id: str
    created_at: str = Field(default_factory=utc_now_iso)


# ---------------------------------------------------------------------------
# Danger-Sign Escalation (FR-5)
# ---------------------------------------------------------------------------


class DangerSignAlert(BaseModel):
    """Deterministic NHM danger-sign detection match."""

    code: str
    category: str
    canonical_name: str
    priority: DangerPriority
    localized_instruction: str
    matched_keyword: str = ""


# ---------------------------------------------------------------------------
# WebSocket Protocol Schemas (FR-1)
# ---------------------------------------------------------------------------


class RegisterIntakeDraft(BaseModel):
    """Mutable in-memory draft when onboarding a patient into a custom register by voice (FR-7.2)."""

    register_id: str
    register_name: str
    patient_name: Optional[str] = None
    category: VisitType = "ANC"
    field_answers: dict[str, Any] = Field(default_factory=dict)
    expected_fields: list[str] = Field(default_factory=list)


class WsSessionStart(BaseModel):
    """Client -> Server message configuring session language and optional patient or register."""

    type: Literal["session_start"] = "session_start"
    language_code: LanguageCode = "indic-auto"
    patient_id: Optional[str] = None
    register_id: Optional[str] = None


class WsPatientSelect(BaseModel):
    """Client -> Server message locking a registered patient for follow-up context."""

    type: Literal["patient_select"] = "patient_select"
    patient_id: str


class WsRegisterSelect(BaseModel):
    """Client -> Server message entering voice intake mode for a custom register (FR-7.2)."""

    type: Literal["register_select"] = "register_select"
    register_id: str


class WsRegisterIntakeUpdateEvent(BaseModel):
    """Server -> Client live custom-register intake draft and voice-correction diff."""

    type: Literal["register_intake_update"] = "register_intake_update"
    status: SessionStatus
    draft: RegisterIntakeDraft
    field_diff: Optional[FieldDiff] = None
    field_diffs: list[FieldDiff] = Field(default_factory=list)
    spoken_reply: str = ""


class WsPatientSavedEvent(BaseModel):
    """Server -> Client confirmation when a custom-register patient is committed via voice."""

    type: Literal["patient_saved"] = "patient_saved"
    patient: PatientRecord
    profile: PatientProfileView
    matched_existing: bool = False


class WsTextTurn(BaseModel):
    """Client -> Server message for simulator/testing text turn injection."""

    type: Literal["text_turn"] = "text_turn"
    text: str
    language_code: Optional[LanguageCode] = None


class WsTranscriptEvent(BaseModel):
    """Server -> Client live STT transcript update."""

    type: Literal["transcript"] = "transcript"
    text: str
    is_final: bool = False
    language: str = "hi-IN"
    confidence: float = 1.0


class WsFormUpdateEvent(BaseModel):
    """Server -> Client structured form state and optional voice-correction diff."""

    type: Literal["form_update"] = "form_update"
    status: SessionStatus
    record: VisitDraft
    field_diff: Optional[FieldDiff] = None
    field_diffs: list[FieldDiff] = Field(default_factory=list)
    spoken_reply: str = ""


class WsDangerEscalationEvent(BaseModel):
    """Server -> Client urgent PHC escalation payload."""

    type: Literal["danger_escalation"] = "danger_escalation"
    code: str
    name: str
    priority: DangerPriority
    localized_instruction: str
    all_alerts: list[DangerSignAlert] = Field(default_factory=list)


class WsBargeInClearEvent(BaseModel):
    """Server -> Client instant AudioTrack flush command on barge-in."""

    type: Literal["barge_in_clear"] = "barge_in_clear"


class WsVisitSavedEvent(BaseModel):
    """Server -> Client confirmation when a visit is committed to SQLite."""

    type: Literal["visit_saved"] = "visit_saved"
    visit: VisitRecord
    follow_up: Optional[FollowUp] = None


class WsMetricsEvent(BaseModel):
    """Server -> Client latency telemetry for live judge HUD."""

    type: Literal["metrics"] = "metrics"
    ttfa_ms: float
    asr_latency_ms: float = 0.0
    stt_latency_ms: float = 0.0
    llm_latency_ms: float = 0.0
    tts_latency_ms: float = 0.0
    tts_ttfa_ms: float = 0.0
    barge_in_cutoff_ms: Optional[float] = None
    noise_floor_rms: float = 0.0
    active_llm_model: str = ""
    danger_fast_path: bool = False


class WsErrorEvent(BaseModel):
    """Server -> Client structured error event."""

    type: Literal["error"] = "error"
    message: str
    code: str = "INTERNAL_ERROR"
