package com.ashavaani.app.ui.walkmode

import com.ashavaani.app.audio.AudioDspCapabilities
import com.ashavaani.app.model.ConnectionState
import com.ashavaani.app.model.FieldDiff
import com.ashavaani.app.model.PatientFollowUpContext
import com.ashavaani.app.model.RegisterIntakeDraft
import com.ashavaani.app.model.SupportedLanguage
import com.ashavaani.app.model.VisitDraft
import com.ashavaani.app.model.WsBargeInClearEvent
import com.ashavaani.app.model.WsDangerEscalationEvent
import com.ashavaani.app.model.WsErrorEvent
import com.ashavaani.app.model.WsFormUpdateEvent
import com.ashavaani.app.model.WsMetricsEvent
import com.ashavaani.app.model.WsPatientSavedEvent
import com.ashavaani.app.model.WsRegisterIntakeUpdateEvent
import com.ashavaani.app.model.WsServerEvent
import com.ashavaani.app.model.WsTranscriptEvent
import com.ashavaani.app.model.WsVisitSavedEvent

enum class WalkInputMode {
    ROUTINE_VISIT,
    REGISTER_INTAKE,
}

data class LatencyMetrics(
    val ttfaMs: Int? = null,
    val asrMs: Int? = null,
    val llmMs: Int? = null,
    val ttsMs: Int? = null,
    val bargeInFlushMs: Float? = null,
)

data class WalkModeUiState(
    val connectionState: ConnectionState = ConnectionState.OFFLINE_CACHE,
    val gnaniMode: String = "MOCK",
    val isMockMode: Boolean = true,
    val activeLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    val inputMode: WalkInputMode = WalkInputMode.ROUTINE_VISIT,
    val sessionStatus: String = "LISTENING",
    val isMicActive: Boolean = false,
    val isAgentSpeaking: Boolean = false,
    val rmsLevel: Float = 0f,
    val dspCapabilities: AudioDspCapabilities = AudioDspCapabilities(),
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val agentReadback: String = "",
    val visitDraft: VisitDraft = VisitDraft(),
    val registerIntakeDraft: RegisterIntakeDraft? = null,
    val activeFieldDiff: FieldDiff? = null,
    val followUpContext: PatientFollowUpContext? = null,
    val activeDangerEscalation: WsDangerEscalationEvent? = null,
    val metrics: LatencyMetrics = LatencyMetrics(),
    val isSimDrawerOpen: Boolean = false,
    val toastMessage: String? = null,
    val errorBanner: String? = null,
    val backendUrl: String = "http://127.0.0.1:8000",
    val phcPhoneNumber: String = "108",
)

/**
 * Pure deterministic state reducer for all `/ws/session` server events.
 * Enables exhaustive JVM unit testing of Walk Mode transitions, Amber FieldDiffs,
 * FR-7.2 custom register intake, barge-in cutoffs, multi-house resets, and PHC escalation.
 */
object WalkModeStateReducer {

    fun reduceServerEvent(
        state: WalkModeUiState,
        event: WsServerEvent,
    ): WalkModeUiState = when (event) {
        is WsTranscriptEvent -> {
            if (event.isFinal) {
                state.copy(
                    partialTranscript = "",
                    finalTranscript = event.text,
                    errorBanner = null,
                )
            } else {
                state.copy(
                    partialTranscript = event.text,
                    errorBanner = null,
                )
            }
        }

        is WsFormUpdateEvent -> {
            if (event.status.equals("SAVED", ignoreCase = true)) {
                state.copy(
                    inputMode = WalkInputMode.ROUTINE_VISIT,
                    registerIntakeDraft = null,
                    sessionStatus = "LISTENING",
                    visitDraft = VisitDraft(),
                    followUpContext = null,
                    activeFieldDiff = null,
                    agentReadback = event.spokenReply.ifBlank { state.agentReadback },
                    errorBanner = null,
                )
            } else {
                val primaryDiff = event.fieldDiff ?: event.fieldDiffs.lastOrNull()
                state.copy(
                    inputMode = WalkInputMode.ROUTINE_VISIT,
                    registerIntakeDraft = null,
                    sessionStatus = event.status,
                    visitDraft = event.record,
                    activeFieldDiff = primaryDiff,
                    agentReadback = event.spokenReply.ifBlank { state.agentReadback },
                    errorBanner = null,
                )
            }
        }

        is WsRegisterIntakeUpdateEvent -> {
            if (event.status.equals("SAVED", ignoreCase = true)) {
                state.copy(
                    inputMode = WalkInputMode.REGISTER_INTAKE,
                    sessionStatus = "LISTENING",
                    registerIntakeDraft = event.draft.copy(
                        patientName = null,
                        fieldAnswers = emptyMap(),
                    ),
                    activeFieldDiff = null,
                    agentReadback = event.spokenReply.ifBlank { state.agentReadback },
                    errorBanner = null,
                )
            } else {
                val primaryDiff = event.fieldDiff ?: event.fieldDiffs.lastOrNull()
                state.copy(
                    inputMode = WalkInputMode.REGISTER_INTAKE,
                    sessionStatus = event.status,
                    registerIntakeDraft = event.draft,
                    activeFieldDiff = primaryDiff,
                    agentReadback = event.spokenReply.ifBlank { state.agentReadback },
                    errorBanner = null,
                )
            }
        }

        is WsDangerEscalationEvent -> {
            state.copy(
                sessionStatus = "ESCALATED",
                activeDangerEscalation = event,
                agentReadback = event.localizedInstruction.ifBlank { state.agentReadback },
                errorBanner = null,
            )
        }

        is WsBargeInClearEvent -> {
            state.copy(
                isAgentSpeaking = false,
                sessionStatus = "LISTENING",
            )
        }

        is WsVisitSavedEvent -> {
            val nextDate = event.followUp?.dueDate?.let { " · Follow-up $it" }.orEmpty()
            state.copy(
                inputMode = WalkInputMode.ROUTINE_VISIT,
                sessionStatus = "LISTENING",
                visitDraft = VisitDraft(),
                activeFieldDiff = null,
                followUpContext = null, // Prevent multi-house bleed for the next household
                partialTranscript = "",
                finalTranscript = "",
                agentReadback = "",
                toastMessage = "Saved ${event.visit.beneficiaryName}'s visit$nextDate",
                errorBanner = null,
            )
        }

        is WsPatientSavedEvent -> {
            state.copy(
                sessionStatus = "LISTENING",
                registerIntakeDraft = state.registerIntakeDraft?.copy(
                    patientName = null,
                    fieldAnswers = emptyMap(),
                ),
                activeFieldDiff = null,
                partialTranscript = "",
                finalTranscript = "",
                agentReadback = "",
                toastMessage = "Added ${event.patient.patientName} to ${event.profile.registerName}",
                errorBanner = null,
            )
        }

        is WsMetricsEvent -> {
            state.copy(
                metrics = state.metrics.copy(
                    ttfaMs = event.ttfaMs.toInt(),
                    asrMs = event.asrLatencyMs.toInt(),
                    llmMs = event.llmLatencyMs.toInt(),
                    ttsMs = event.ttsLatencyMs.toInt(),
                )
            )
        }

        is WsErrorEvent -> {
            state.copy(errorBanner = event.message)
        }
    }

    /**
     * Build a clean, zero-diagnosis PHC referral slip for Android WhatsApp / SMS sharing (`ACTION_SEND`).
     */
    fun buildPhcReferralShareText(
        escalation: WsDangerEscalationEvent,
        beneficiaryName: String?,
        phcPhone: String,
    ): String {
        val patient = beneficiaryName?.takeIf { it.isNotBlank() } ?: "Beneficiary"
        return buildString {
            appendLine("🚨 ASHA VAANI — URGENT PHC REFERRAL")
            appendLine("Patient: $patient")
            appendLine("Danger Sign (${escalation.code}): ${escalation.name}")
            appendLine("Priority: ${escalation.priority}")
            appendLine("Action: ${escalation.localizedInstruction}")
            appendLine("PHC / Helpline: $phcPhone")
            append("Note: Flagged from canonical NHM danger-sign list only (no diagnosis or treatment advice).")
        }
    }
}
