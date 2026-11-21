package com.ashavaani.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared Kotlinx Serialization Json instance aligned with the FastAPI /ws/session and REST API wire format.
 */
@OptIn(ExperimentalSerializationApi::class)
val AshaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
    classDiscriminator = "type"
}

// ---------------------------------------------------------------------------
// Client -> Server WebSocket Control Messages
// ---------------------------------------------------------------------------

@Serializable
sealed interface WsClientMessage

@Serializable
@SerialName("session_start")
data class WsSessionStart(
    @SerialName("language_code") val languageCode: String = "indic-auto",
    @SerialName("patient_id") val patientId: String? = null,
    @SerialName("register_id") val registerId: String? = null,
) : WsClientMessage

@Serializable
@SerialName("language_change")
data class WsLanguageChange(
    @SerialName("language_code") val languageCode: String,
) : WsClientMessage

@Serializable
@SerialName("patient_select")
data class WsPatientSelect(
    @SerialName("patient_id") val patientId: String,
) : WsClientMessage

@Serializable
@SerialName("register_select")
data class WsRegisterSelect(
    @SerialName("register_id") val registerId: String,
) : WsClientMessage

@Serializable
@SerialName("barge_in")
data object WsBargeIn : WsClientMessage

@Serializable
@SerialName("finalize_turn")
data object WsFinalizeTurn : WsClientMessage

@Serializable
@SerialName("reset_draft")
data class WsResetDraft(
    val mode: String? = null, // "visit" to exit FR-7.2 register intake mode
) : WsClientMessage

@Serializable
@SerialName("text_turn")
data class WsTextTurn(
    val text: String,
    @SerialName("language_code") val languageCode: String? = null,
) : WsClientMessage

// ---------------------------------------------------------------------------
// Server -> Client WebSocket Events
// ---------------------------------------------------------------------------

@Serializable
sealed interface WsServerEvent

@Serializable
@SerialName("transcript")
data class WsTranscriptEvent(
    val text: String,
    @SerialName("is_final") val isFinal: Boolean = false,
    val language: String = "hi-IN",
    val confidence: Double = 1.0,
) : WsServerEvent

@Serializable
@SerialName("form_update")
data class WsFormUpdateEvent(
    val status: String,
    val record: VisitDraft,
    @SerialName("field_diff") val fieldDiff: FieldDiff? = null,
    @SerialName("field_diffs") val fieldDiffs: List<FieldDiff> = emptyList(),
    @SerialName("spoken_reply") val spokenReply: String = "",
) : WsServerEvent

@Serializable
@SerialName("register_intake_update")
data class WsRegisterIntakeUpdateEvent(
    val status: String,
    val draft: RegisterIntakeDraft,
    @SerialName("field_diff") val fieldDiff: FieldDiff? = null,
    @SerialName("field_diffs") val fieldDiffs: List<FieldDiff> = emptyList(),
    @SerialName("spoken_reply") val spokenReply: String = "",
) : WsServerEvent

@Serializable
@SerialName("danger_escalation")
data class WsDangerEscalationEvent(
    val code: String,
    val name: String,
    val priority: String,
    @SerialName("localized_instruction") val localizedInstruction: String,
    @SerialName("all_alerts") val allAlerts: List<DangerSignAlert> = emptyList(),
) : WsServerEvent

@Serializable
@SerialName("barge_in_clear")
data object WsBargeInClearEvent : WsServerEvent

@Serializable
@SerialName("visit_saved")
data class WsVisitSavedEvent(
    val visit: VisitRecord,
    @SerialName("follow_up") val followUp: FollowUp? = null,
) : WsServerEvent

@Serializable
@SerialName("patient_saved")
data class WsPatientSavedEvent(
    val patient: PatientRecord,
    val profile: PatientProfileView,
) : WsServerEvent

@Serializable
@SerialName("metrics")
data class WsMetricsEvent(
    @SerialName("ttfa_ms") val ttfaMs: Double,
    @SerialName("asr_latency_ms") val asrLatencyMs: Double = 0.0,
    @SerialName("llm_latency_ms") val llmLatencyMs: Double = 0.0,
    @SerialName("tts_latency_ms") val ttsLatencyMs: Double = 0.0,
) : WsServerEvent

@Serializable
@SerialName("error")
data class WsErrorEvent(
    val message: String,
    val code: String = "INTERNAL_ERROR",
) : WsServerEvent
