package com.ashavaani.app.data

import com.ashavaani.app.data.local.toDomain
import com.ashavaani.app.data.local.toEntity
import com.ashavaani.app.data.remote.ServerUrlResolver
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.RegisterField
import com.ashavaani.app.model.VisitRecord
import com.ashavaani.app.model.toDisplayString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerTest {

    @Test
    fun `server url resolver normalizes http, https, bare host, and ws urls`() {
        assertEquals("http://127.0.0.1:8000", ServerUrlResolver.normalizeBaseUrl(""))
        assertEquals("http://127.0.0.1:8000", ServerUrlResolver.normalizeBaseUrl("http://127.0.0.1:8000/"))
        assertEquals("http://192.168.1.42:8000", ServerUrlResolver.normalizeBaseUrl("192.168.1.42:8000/"))
        assertEquals("https://demo.ashavaani.in", ServerUrlResolver.normalizeBaseUrl("wss://demo.ashavaani.in/ws/session"))

        assertEquals("ws://127.0.0.1:8000/ws/session", ServerUrlResolver.toWebSocketUrl("http://127.0.0.1:8000"))
        assertEquals("wss://demo.ashavaani.in/ws/session", ServerUrlResolver.toWebSocketUrl("https://demo.ashavaani.in/"))
    }

    @Test
    fun `server url resolver builds search and export urls and parses content-disposition`() {
        assertEquals(
            "http://127.0.0.1:8000/api/registers/REG-ANC-DEFAULT/patients?q=Sunita+Devi",
            ServerUrlResolver.buildPatientsSearchUrl("http://127.0.0.1:8000", "REG-ANC-DEFAULT", "Sunita Devi"),
        )
        assertEquals(
            "http://127.0.0.1:8000/api/registers/REG-ANC-DEFAULT/export?format=xlsx",
            ServerUrlResolver.buildExportUrl("http://127.0.0.1:8000", "REG-ANC-DEFAULT", "xlsx"),
        )
        assertEquals(
            "http://127.0.0.1:8000/api/registers/REG-ANC-DEFAULT/export?format=csv",
            ServerUrlResolver.buildExportUrl("http://127.0.0.1:8000", "REG-ANC-DEFAULT", "CSV"),
        )
        assertEquals(
            "anc_first_intake.xlsx",
            ServerUrlResolver.extractExportFilename(
                contentDisposition = """attachment; filename="anc_first_intake.xlsx"""",
                registerId = "REG-ANC-DEFAULT",
                format = "xlsx",
            ),
        )
        assertEquals(
            "REG-ANC-DEFAULT_export.csv",
            ServerUrlResolver.extractExportFilename(null, "REG-ANC-DEFAULT", "csv"),
        )
    }

    @Test
    fun `room entity mappers round-trip registers, dynamic patient answers, visits, and follow-ups losslessly`() {
        val reg = CustomRegister(
            id = "REG-ANC-DEFAULT",
            name = "ANC First Intake Register",
            description = "Maternal intake",
            fields = listOf(
                RegisterField("F-1", "REG-ANC-DEFAULT", "Age", "NUMBER", 0),
                RegisterField("F-2", "REG-ANC-DEFAULT", "Blood Group", "TEXT", 1),
            ),
            createdAt = "2026-11-20T10:00:00Z",
        )
        assertEquals(reg, reg.toEntity().toDomain())

        val patient = PatientRecord(
            id = "BEN-001",
            registerId = "REG-ANC-DEFAULT",
            patientName = "Sunita Devi",
            category = "ANC",
            fieldAnswers = mapOf(
                "Age" to JsonPrimitive(24),
                "Blood Group" to JsonPrimitive("B+"),
                "High Risk" to JsonPrimitive(false),
            ),
            createdAt = "2026-11-20T10:00:00Z",
            updatedAt = "2026-11-21T10:00:00Z",
        )
        val roundTrippedPatient = patient.toEntity().toDomain()
        assertEquals(patient.id, roundTrippedPatient.id)
        assertEquals("24", roundTrippedPatient.fieldAnswers["Age"].toDisplayString())
        assertEquals("B+", roundTrippedPatient.fieldAnswers["Blood Group"].toDisplayString())
        assertEquals("No", roundTrippedPatient.fieldAnswers["High Risk"].toDisplayString())

        val visit = VisitRecord(
            id = "VIS-102",
            beneficiaryId = "BEN-002",
            beneficiaryName = "Lakshmi Gowda (Baby)",
            visitType = "NEWBORN",
            weightKg = 2.7,
            vaccinationsDue = listOf("BCG", "OPV0"),
            nextVisitDate = "2026-11-22",
            ifaTabletsGiven = 0,
            hasDangerSign = true,
            dangerSigns = listOf("DS_NB_HIGH_FEVER_COLD"),
            notes = "Urgent PHC referral",
            rawTranscript = "Lakshmi baby high fever",
            languageCode = "kn-IN",
            createdAt = "2026-11-21T10:15:00Z",
        )
        assertEquals(visit, visit.toEntity().toDomain())

        val followUp = FollowUp(
            id = "FU-1",
            beneficiaryId = "BEN-002",
            beneficiaryName = "Lakshmi Gowda (Baby)",
            visitId = "VIS-102",
            dueDate = "2026-11-21",
            purpose = "PHC Referral Verification",
            vaccinesDue = listOf("BCG"),
            status = "ESCALATED",
            createdAt = "2026-11-21T10:15:00Z",
        )
        assertEquals(followUp, followUp.toEntity().toDomain())
    }

    @Test
    fun `offline local profile builder preserves register field order and appends extra answers`() {
        val reg = CustomRegister(
            id = "REG-ANC-DEFAULT",
            name = "ANC First Intake Register",
            fields = listOf(
                RegisterField("F-1", "REG-ANC-DEFAULT", "Age", "NUMBER", 0),
                RegisterField("F-2", "REG-ANC-DEFAULT", "Blood Group", "TEXT", 1),
                RegisterField("F-3", "REG-ANC-DEFAULT", "LMP", "DATE", 2),
            ),
        )
        val patient = PatientRecord(
            id = "BEN-009",
            registerId = "REG-ANC-DEFAULT",
            patientName = "Savitha Rao",
            category = "ANC",
            fieldAnswers = mapOf(
                "Blood Group" to JsonPrimitive("O+"),
                "Age" to JsonPrimitive(28),
                "House / Ward" to JsonPrimitive("House 19"),
            ),
        )

        val profile = AshaRepository.buildLocalProfileView(patient, reg)
        assertEquals("ANC First Intake Register", profile.registerName)
        assertEquals(listOf("Age", "Blood Group", "LMP", "House / Ward"), profile.items.map { it.fieldName })
        assertEquals("28", profile.items[0].answer.toDisplayString())
        assertEquals("O+", profile.items[1].answer.toDisplayString())
        assertEquals("—", profile.items[2].answer.toDisplayString())
        assertTrue(profile.items[3].answer.toDisplayString() == "House 19")
    }
}
