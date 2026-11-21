package com.ashavaani.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Helper to format dynamic backend JSON answers (`Any` in FastAPI) cleanly for mobile UI.
 */
fun JsonElement?.toDisplayString(fallback: String = "—"): String {
    if (this == null || this is JsonNull) return fallback
    return when (this) {
        is JsonPrimitive -> {
            if (isString) {
                content.ifBlank { fallback }
            } else {
                booleanOrNull?.let { if (it) "Yes" else "No" }
                    ?: longOrNull?.toString()
                    ?: doubleOrNull?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
                    ?: contentOrNull?.ifBlank { fallback }
                    ?: fallback
            }
        }
        is JsonArray -> joinToString(", ") { it.toDisplayString("") }.ifBlank { fallback }
        else -> toString()
    }
}

/**
 * Supported Indic languages displayed in native scripts (never raw ISO codes in ASHA UI).
 */
enum class SupportedLanguage(
    val code: String,
    val nativeLabel: String,
) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "हिन्दी"),
    KANNADA("kn-IN", "ಕನ್ನಡ"),
    TAMIL("ta-IN", "தமிழ்"),
    TELUGU("te-IN", "తెలుగు"),
    AUTO("indic-auto", "Auto");

    companion object {
        fun fromCode(code: String?): SupportedLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: HINDI
    }
}

enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
    OFFLINE_CACHE,
}

// ---------------------------------------------------------------------------
// System / Health Telemetry
// ---------------------------------------------------------------------------

@Serializable
data class HealthResponse(
    val status: String = "ok",
    @SerialName("gnani_mode") val gnaniMode: String = "mock",
    @SerialName("stt_model") val sttModel: String = "prisma-v3.0",
    @SerialName("tts_model") val ttsModel: String = "timbre-v3.0",
    @SerialName("llm_model") val llmModel: String = "gemini-2.5-flash",
    @SerialName("danger_signs_count") val dangerSignsCount: Int = 12,
) {
    val isMockMode: Boolean get() = gnaniMode.equals("mock", ignoreCase = true)
}

// ---------------------------------------------------------------------------
// Custom First-Visit Registers & Patient Profiles (FR-7.1 – FR-7.6)
// ---------------------------------------------------------------------------

@Serializable
data class RegisterFieldCreate(
    @SerialName("field_name") val fieldName: String,
    @SerialName("field_type") val fieldType: String = "TEXT", // TEXT | NUMBER | DATE | BOOLEAN
    @SerialName("order_index") val orderIndex: Int = 0,
)

@Serializable
data class RegisterField(
    val id: String,
    @SerialName("register_id") val registerId: String,
    @SerialName("field_name") val fieldName: String,
    @SerialName("field_type") val fieldType: String = "TEXT",
    @SerialName("order_index") val orderIndex: Int = 0,
)

@Serializable
data class CustomRegisterCreate(
    val name: String,
    val description: String = "",
    val fields: List<RegisterFieldCreate> = emptyList(),
)

@Serializable
data class CustomRegister(
    val id: String,
    val name: String,
    val description: String = "",
    val fields: List<RegisterField> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PatientRecordCreate(
    val id: String? = null,
    @SerialName("register_id") val registerId: String,
    @SerialName("patient_name") val patientName: String,
    val category: String = "ANC", // ANC | NEWBORN | IMMUNISATION
    @SerialName("field_answers") val fieldAnswers: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class PatientRecordUpdate(
    @SerialName("patient_name") val patientName: String? = null,
    val category: String? = null,
    @SerialName("field_answers") val fieldAnswers: Map<String, JsonElement>? = null,
)

@Serializable
data class PatientRecord(
    val id: String,
    @SerialName("register_id") val registerId: String,
    @SerialName("patient_name") val patientName: String,
    val category: String = "ANC",
    @SerialName("field_answers") val fieldAnswers: Map<String, JsonElement> = emptyMap(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class FieldAnswerItem(
    @SerialName("field_name") val fieldName: String,
    val answer: JsonElement? = null,
)

@Serializable
data class PatientProfileView(
    @SerialName("patient_id") val patientId: String,
    @SerialName("register_id") val registerId: String,
    @SerialName("register_name") val registerName: String,
    @SerialName("patient_name") val patientName: String,
    val category: String = "ANC",
    val items: List<FieldAnswerItem> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class LastVisitSummary(
    @SerialName("visit_type") val visitType: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("vaccinations_due") val vaccinationsDue: List<String> = emptyList(),
    @SerialName("next_visit_date") val nextVisitDate: String? = null,
    @SerialName("has_danger_sign") val hasDangerSign: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PatientFollowUpContext(
    @SerialName("beneficiary_id") val beneficiaryId: String,
    @SerialName("beneficiary_name") val beneficiaryName: String,
    val category: String = "ANC",
    @SerialName("register_name") val registerName: String = "",
    @SerialName("baseline_fields") val baselineFields: Map<String, JsonElement> = emptyMap(),
    @SerialName("last_visit") val lastVisit: LastVisitSummary? = null,
)

// ---------------------------------------------------------------------------
// Clinical Visits, Voice Diffing & Follow-Up Queue (FR-4, FR-8)
// ---------------------------------------------------------------------------

@Serializable
data class VisitDraft(
    @SerialName("beneficiary_id") val beneficiaryId: String? = null,
    @SerialName("beneficiary_name") val beneficiaryName: String? = null,
    @SerialName("visit_type") val visitType: String? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("vaccinations_due") val vaccinationsDue: List<String> = emptyList(),
    @SerialName("next_visit_date") val nextVisitDate: String? = null,
    @SerialName("ifa_tablets_given") val ifaTabletsGiven: Int? = null,
    @SerialName("danger_signs") val dangerSigns: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
data class VisitRecord(
    val id: String,
    @SerialName("beneficiary_id") val beneficiaryId: String,
    @SerialName("beneficiary_name") val beneficiaryName: String,
    @SerialName("visit_type") val visitType: String,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("vaccinations_due") val vaccinationsDue: List<String> = emptyList(),
    @SerialName("next_visit_date") val nextVisitDate: String? = null,
    @SerialName("ifa_tablets_given") val ifaTabletsGiven: Int? = null,
    @SerialName("has_danger_sign") val hasDangerSign: Boolean = false,
    @SerialName("danger_signs") val dangerSigns: List<String> = emptyList(),
    val notes: String = "",
    @SerialName("raw_transcript") val rawTranscript: String = "",
    @SerialName("language_code") val languageCode: String = "hi-IN",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class FieldDiff(
    val field: String,
    @SerialName("old_value") val oldValue: JsonElement? = null,
    @SerialName("new_value") val newValue: JsonElement? = null,
)

@Serializable
data class FollowUp(
    val id: String,
    @SerialName("beneficiary_id") val beneficiaryId: String,
    @SerialName("beneficiary_name") val beneficiaryName: String,
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("due_date") val dueDate: String,
    val purpose: String,
    @SerialName("vaccines_due") val vaccinesDue: List<String> = emptyList(),
    val status: String = "PENDING", // PENDING | COMPLETED | ESCALATED
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class FollowUpStatusPatch(
    val status: String, // PENDING | COMPLETED | ESCALATED
)

// ---------------------------------------------------------------------------
// Danger-Sign Escalation & FR-7.2 Voice Register Intake
// ---------------------------------------------------------------------------

@Serializable
data class DangerSignAlert(
    val code: String,
    val category: String,
    @SerialName("canonical_name") val canonicalName: String,
    val priority: String, // IMMEDIATE_EMERGENCY | HIGH
    @SerialName("localized_instruction") val localizedInstruction: String,
    @SerialName("matched_keyword") val matchedKeyword: String = "",
)

@Serializable
data class RegisterIntakeDraft(
    @SerialName("register_id") val registerId: String,
    @SerialName("register_name") val registerName: String,
    @SerialName("patient_name") val patientName: String? = null,
    val category: String = "ANC",
    @SerialName("field_answers") val fieldAnswers: Map<String, JsonElement> = emptyMap(),
    @SerialName("expected_fields") val expectedFields: List<String> = emptyList(),
)
