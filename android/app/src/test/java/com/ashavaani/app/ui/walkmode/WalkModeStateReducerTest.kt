package com.ashavaani.app.ui.walkmode

import com.ashavaani.app.model.FieldDiff
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.PatientFollowUpContext
import com.ashavaani.app.model.PatientProfileView
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.RegisterIntakeDraft
import com.ashavaani.app.model.VisitDraft
import com.ashavaani.app.model.VisitRecord
import com.ashavaani.app.model.WsBargeInClearEvent
import com.ashavaani.app.model.WsDangerEscalationEvent
import com.ashavaani.app.model.WsFormUpdateEvent
import com.ashavaani.app.model.WsMetricsEvent
import com.ashavaani.app.model.WsPatientSavedEvent
import com.ashavaani.app.model.WsRegisterIntakeUpdateEvent
import com.ashavaani.app.model.WsTranscriptEvent
import com.ashavaani.app.model.WsVisitSavedEvent
import com.ashavaani.app.model.toDisplayString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkModeStateReducerTest {

    @Test
    fun `partial then final transcript updates strip cleanly`() {
        val initial = WalkModeUiState()
        val afterPartial = WalkModeStateReducer.reduceServerEvent(
            initial,
            WsTranscriptEvent(text = "Sunita Devi ANC", isFinal = false),
        )
        assertEquals("Sunita Devi ANC", afterPartial.partialTranscript)
        assertEquals("", afterPartial.finalTranscript)

        val afterFinal = WalkModeStateReducer.reduceServerEvent(
            afterPartial,
            WsTranscriptEvent(text = "Sunita Devi ANC weight 3.2 kilo", isFinal = true),
        )
        assertEquals("", afterFinal.partialTranscript)
        assertEquals("Sunita Devi ANC weight 3.2 kilo", afterFinal.finalTranscript)
    }

    @Test
    fun `form_update with FieldDiff populates Amber voice correction and read-back`() {
        val updated = WalkModeStateReducer.reduceServerEvent(
            WalkModeUiState(),
            WsFormUpdateEvent(
                status = "AWAITING_CONFIRMATION",
                record = VisitDraft(
                    beneficiaryId = "BEN-001",
                    beneficiaryName = "Sunita Devi",
                    visitType = "ANC",
                    weightKg = 2.8,
                    ifaTabletsGiven = 30,
                    vaccinationsDue = listOf("TT2"),
                ),
                fieldDiff = FieldDiff("weight_kg", JsonPrimitive(3.2), JsonPrimitive(2.8)),
                spokenReply = "वजन 2.8 किलो अपडेट कर दिया है।",
            ),
        )

        assertEquals(WalkInputMode.ROUTINE_VISIT, updated.inputMode)
        assertEquals("AWAITING_CONFIRMATION", updated.sessionStatus)
        assertEquals("Sunita Devi", updated.visitDraft.beneficiaryName)
        assertEquals(2.8, updated.visitDraft.weightKg ?: 0.0, 0.001)
        assertEquals("weight_kg", updated.activeFieldDiff?.field)
        assertEquals("3.2", updated.activeFieldDiff?.oldValue.toDisplayString())
        assertEquals("2.8", updated.activeFieldDiff?.newValue.toDisplayString())
        assertTrue(updated.agentReadback.contains("2.8"))
    }

    @Test
    fun `visit_saved resets draft and clears FR-7_6 follow-up context to prevent multi-house bleed`() {
        val preSave = WalkModeUiState(
            sessionStatus = "AWAITING_CONFIRMATION",
            visitDraft = VisitDraft(beneficiaryId = "BEN-001", beneficiaryName = "Sunita Devi", weightKg = 2.8),
            followUpContext = PatientFollowUpContext(beneficiaryId = "BEN-001", beneficiaryName = "Sunita Devi"),
            activeFieldDiff = FieldDiff("weight_kg", JsonPrimitive(3.2), JsonPrimitive(2.8)),
            finalTranscript = "Haan theek hai",
            agentReadback = "Saved",
        )

        val postSave = WalkModeStateReducer.reduceServerEvent(
            preSave,
            WsVisitSavedEvent(
                visit = VisitRecord(
                    id = "VIS-200",
                    beneficiaryId = "BEN-001",
                    beneficiaryName = "Sunita Devi",
                    visitType = "ANC",
                    weightKg = 2.8,
                ),
                followUp = FollowUp(
                    id = "FU-200",
                    beneficiaryId = "BEN-001",
                    beneficiaryName = "Sunita Devi",
                    dueDate = "2026-12-19",
                    purpose = "ANC Checkup",
                ),
            ),
        )

        assertNull(postSave.visitDraft.beneficiaryName)
        assertNull(postSave.followUpContext)
        assertNull(postSave.activeFieldDiff)
        assertEquals("LISTENING", postSave.sessionStatus)
        assertTrue(postSave.toastMessage?.contains("Sunita Devi") == true)
        assertTrue(postSave.toastMessage?.contains("2026-12-19") == true)
    }

    @Test
    fun `danger_escalation triggers urgent PHC modal state and builds zero-diagnosis share slip`() {
        val escalation = WsDangerEscalationEvent(
            code = "DS_NB_HIGH_FEVER_COLD",
            name = "High fever or unusually cold body in newborn",
            priority = "IMMEDIATE_EMERGENCY",
            localizedInstruction = "कृपया बच्चे को तुरंत पीएचसी (PHC) रेफर करें।",
        )
        val afterEscalation = WalkModeStateReducer.reduceServerEvent(WalkModeUiState(), escalation)

        assertEquals("ESCALATED", afterEscalation.sessionStatus)
        assertNotNull(afterEscalation.activeDangerEscalation)
        assertEquals("DS_NB_HIGH_FEVER_COLD", afterEscalation.activeDangerEscalation?.code)

        val slip = WalkModeStateReducer.buildPhcReferralShareText(
            escalation = escalation,
            beneficiaryName = "Lakshmi Gowda (Baby)",
            phcPhone = "108",
        )
        assertTrue(slip.contains("Lakshmi Gowda (Baby)"))
        assertTrue(slip.contains("DS_NB_HIGH_FEVER_COLD"))
        assertTrue(slip.contains("108"))
        assertTrue(slip.contains("no diagnosis or treatment advice"))
    }

    @Test
    fun `FR-7_2 register_intake_update, patient_saved, barge_in_clear, and metrics update cleanly`() {
        val intakeState = WalkModeStateReducer.reduceServerEvent(
            WalkModeUiState(isAgentSpeaking = true),
            WsRegisterIntakeUpdateEvent(
                status = "AWAITING_CONFIRMATION",
                draft = RegisterIntakeDraft(
                    registerId = "REG-ANC-DEFAULT",
                    registerName = "ANC First Intake Register",
                    patientName = "Savitha Rao",
                    fieldAnswers = mapOf("Age" to JsonPrimitive(28)),
                    expectedFields = listOf("Age", "Blood Group"),
                ),
                fieldDiff = FieldDiff("Age", JsonPrimitive(26), JsonPrimitive(28)),
                spokenReply = "Age updated to 28.",
            ),
        )
        assertEquals(WalkInputMode.REGISTER_INTAKE, intakeState.inputMode)
        assertEquals("Savitha Rao", intakeState.registerIntakeDraft?.patientName)
        assertEquals("Age", intakeState.activeFieldDiff?.field)

        val afterBargeIn = WalkModeStateReducer.reduceServerEvent(intakeState, WsBargeInClearEvent)
        assertFalse(afterBargeIn.isAgentSpeaking)
        assertEquals("LISTENING", afterBargeIn.sessionStatus)

        val afterMetrics = WalkModeStateReducer.reduceServerEvent(
            afterBargeIn,
            WsMetricsEvent(ttfaMs = 24.6, asrLatencyMs = 85.0, llmLatencyMs = 11.2, ttsLatencyMs = 0.4),
        )
        assertEquals(24, afterMetrics.metrics.ttfaMs)
        assertEquals(85, afterMetrics.metrics.asrMs)

        val afterPatientSaved = WalkModeStateReducer.reduceServerEvent(
            afterMetrics,
            WsPatientSavedEvent(
                patient = PatientRecord(
                    id = "BEN-011",
                    registerId = "REG-ANC-DEFAULT",
                    patientName = "Savitha Rao",
                ),
                profile = PatientProfileView(
                    patientId = "BEN-011",
                    registerId = "REG-ANC-DEFAULT",
                    registerName = "ANC First Intake Register",
                    patientName = "Savitha Rao",
                ),
            ),
        )
        assertNull(afterPatientSaved.registerIntakeDraft?.patientName)
        assertTrue(afterPatientSaved.toastMessage?.contains("Savitha Rao") == true)
    }
}
