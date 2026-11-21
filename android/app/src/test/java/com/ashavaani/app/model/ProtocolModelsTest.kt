package com.ashavaani.app.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolModelsTest {

    @Test
    fun `decodes form_update event with Amber FieldDiff accurately`() {
        val raw = """
            {
              "type": "form_update",
              "status": "AWAITING_CONFIRMATION",
              "record": {
                "beneficiary_id": "BEN-001",
                "beneficiary_name": "Sunita Devi",
                "visit_type": "ANC",
                "weight_kg": 2.8,
                "vaccinations_due": ["TT2"],
                "next_visit_date": "2026-12-19",
                "ifa_tablets_given": 30,
                "danger_signs": [],
                "notes": "Weight corrected by voice"
              },
              "field_diff": {
                "field": "weight_kg",
                "old_value": 3.2,
                "new_value": 2.8
              },
              "spoken_reply": "वजन 2.8 किलो अपडेट कर दिया है।"
            }
        """.trimIndent()

        val event = AshaJson.decodeFromString<WsServerEvent>(raw)
        assertTrue(event is WsFormUpdateEvent)
        val form = event as WsFormUpdateEvent
        assertEquals("AWAITING_CONFIRMATION", form.status)
        assertEquals("Sunita Devi", form.record.beneficiaryName)
        assertEquals(2.8, form.record.weightKg ?: 0.0, 0.001)
        assertEquals(listOf("TT2"), form.record.vaccinationsDue)
        assertEquals(30, form.record.ifaTabletsGiven)
        assertNotNull(form.fieldDiff)
        assertEquals("weight_kg", form.fieldDiff?.field)
        assertEquals("3.2", form.fieldDiff?.oldValue.toDisplayString())
        assertEquals("2.8", form.fieldDiff?.newValue.toDisplayString())
    }

    @Test
    fun `decodes FR-7_2 register_intake_update and patient_saved events`() {
        val rawIntake = """
            {
              "type": "register_intake_update",
              "status": "AWAITING_CONFIRMATION",
              "draft": {
                "register_id": "REG-ANC-DEFAULT",
                "register_name": "ANC First Intake Register",
                "patient_name": "Savitha Rao",
                "category": "ANC",
                "field_answers": {
                  "Age": 28,
                  "Blood Group": "O+",
                  "House / Ward": "House 19"
                },
                "expected_fields": ["Age", "Blood Group", "House / Ward"]
              },
              "field_diff": {
                "field": "Age",
                "old_value": 26,
                "new_value": 28
              },
              "spoken_reply": "Age updated to 28."
            }
        """.trimIndent()

        val event = AshaJson.decodeFromString<WsServerEvent>(rawIntake) as WsRegisterIntakeUpdateEvent
        assertEquals("Savitha Rao", event.draft.patientName)
        assertEquals("28", event.draft.fieldAnswers["Age"].toDisplayString())
        assertEquals("O+", event.draft.fieldAnswers["Blood Group"].toDisplayString())
        assertEquals("26", event.fieldDiff?.oldValue.toDisplayString())
    }

    @Test
    fun `decodes danger_escalation event with canonical NHM alerts`() {
        val raw = """
            {
              "type": "danger_escalation",
              "code": "DS_NB_HIGH_FEVER_COLD",
              "name": "High fever or unusually cold body in newborn",
              "priority": "IMMEDIATE_EMERGENCY",
              "localized_instruction": "तुरंत पीएचसी (PHC) रेफर करें।",
              "all_alerts": [
                {
                  "code": "DS_NB_HIGH_FEVER_COLD",
                  "category": "NEWBORN",
                  "canonical_name": "High fever or unusually cold body in newborn",
                  "priority": "IMMEDIATE_EMERGENCY",
                  "localized_instruction": "तुरंत पीएचसी (PHC) रेफर करें।",
                  "matched_keyword": "high fever"
                }
              ]
            }
        """.trimIndent()

        val event = AshaJson.decodeFromString<WsServerEvent>(raw) as WsDangerEscalationEvent
        assertEquals("DS_NB_HIGH_FEVER_COLD", event.code)
        assertEquals("IMMEDIATE_EMERGENCY", event.priority)
        assertEquals(1, event.allAlerts.size)
    }

    @Test
    fun `encodes client WebSocket messages with FastAPI type discriminator`() {
        val startJson = AshaJson.encodeToString<WsClientMessage>(
            WsSessionStart(languageCode = "kn-IN", registerId = "REG-ANC-DEFAULT")
        )
        assertTrue(startJson.contains("\"type\":\"session_start\""))
        assertTrue(startJson.contains("\"language_code\":\"kn-IN\""))
        assertTrue(startJson.contains("\"register_id\":\"REG-ANC-DEFAULT\""))

        val bargeInJson = AshaJson.encodeToString<WsClientMessage>(WsBargeIn)
        assertEquals("{\"type\":\"barge_in\"}", bargeInJson)

        val resetJson = AshaJson.encodeToString<WsClientMessage>(WsResetDraft(mode = "visit"))
        assertTrue(resetJson.contains("\"type\":\"reset_draft\""))
        assertTrue(resetJson.contains("\"mode\":\"visit\""))
    }

    @Test
    fun `decodes health response and PatientProfileView`() {
        val health = AshaJson.decodeFromString<HealthResponse>(
            """{"status":"ok","gnani_mode":"mock","stt_model":"prisma-v3.0","tts_model":"timbre-v3.0","llm_model":"gemini-2.5-flash","danger_signs_count":12}"""
        )
        assertTrue(health.isMockMode)
        assertEquals(12, health.dangerSignsCount)

        val liveHealth = health.copy(gnaniMode = "live")
        assertFalse(liveHealth.isMockMode)

        assertEquals("Yes", JsonPrimitive(true).toDisplayString())
        assertEquals("28", JsonPrimitive(28.0).toDisplayString())
        assertEquals("2.8", JsonPrimitive(2.8).toDisplayString())
    }
}
