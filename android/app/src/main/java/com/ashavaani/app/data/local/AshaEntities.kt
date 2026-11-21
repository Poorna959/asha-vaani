package com.ashavaani.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ashavaani.app.model.AshaJson
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.RegisterField
import com.ashavaani.app.model.VisitRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

@Entity(tableName = "custom_registers")
data class CustomRegisterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val fieldsJson: String,
    val createdAt: String,
)

@Entity(
    tableName = "patients",
    indices = [
        Index(value = ["registerId"]),
        Index(value = ["patientName"]),
    ],
)
data class PatientEntity(
    @PrimaryKey val id: String,
    val registerId: String,
    val patientName: String,
    val category: String,
    val fieldAnswersJson: String,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "visits",
    indices = [
        Index(value = ["beneficiaryId"]),
        Index(value = ["hasDangerSign"]),
    ],
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val beneficiaryId: String,
    val beneficiaryName: String,
    val visitType: String,
    val weightKg: Double?,
    val vaccinationsDueJson: String,
    val nextVisitDate: String?,
    val ifaTabletsGiven: Int?,
    val hasDangerSign: Boolean,
    val dangerSignsJson: String,
    val notes: String,
    val rawTranscript: String,
    val languageCode: String,
    val createdAt: String,
)

@Entity(
    tableName = "follow_ups",
    indices = [
        Index(value = ["status"]),
        Index(value = ["beneficiaryId"]),
    ],
)
data class FollowUpEntity(
    @PrimaryKey val id: String,
    val beneficiaryId: String,
    val beneficiaryName: String,
    val visitId: String?,
    val dueDate: String,
    val purpose: String,
    val vaccinesDueJson: String,
    val status: String,
    val createdAt: String,
)

// ---------------------------------------------------------------------------
// Lossless Domain <-> Room Entity Mappers
// ---------------------------------------------------------------------------

fun CustomRegister.toEntity(): CustomRegisterEntity = CustomRegisterEntity(
    id = id,
    name = name,
    description = description,
    fieldsJson = AshaJson.encodeToString(fields),
    createdAt = createdAt,
)

fun CustomRegisterEntity.toDomain(): CustomRegister = CustomRegister(
    id = id,
    name = name,
    description = description,
    fields = runCatching { AshaJson.decodeFromString<List<RegisterField>>(fieldsJson) }
        .getOrDefault(emptyList()),
    createdAt = createdAt,
)

fun PatientRecord.toEntity(): PatientEntity = PatientEntity(
    id = id,
    registerId = registerId,
    patientName = patientName,
    category = category,
    fieldAnswersJson = AshaJson.encodeToString(fieldAnswers),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PatientEntity.toDomain(): PatientRecord = PatientRecord(
    id = id,
    registerId = registerId,
    patientName = patientName,
    category = category,
    fieldAnswers = runCatching { AshaJson.decodeFromString<Map<String, JsonElement>>(fieldAnswersJson) }
        .getOrDefault(emptyMap()),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun VisitRecord.toEntity(): VisitEntity = VisitEntity(
    id = id,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    visitType = visitType,
    weightKg = weightKg,
    vaccinationsDueJson = AshaJson.encodeToString(vaccinationsDue),
    nextVisitDate = nextVisitDate,
    ifaTabletsGiven = ifaTabletsGiven,
    hasDangerSign = hasDangerSign,
    dangerSignsJson = AshaJson.encodeToString(dangerSigns),
    notes = notes,
    rawTranscript = rawTranscript,
    languageCode = languageCode,
    createdAt = createdAt,
)

fun VisitEntity.toDomain(): VisitRecord = VisitRecord(
    id = id,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    visitType = visitType,
    weightKg = weightKg,
    vaccinationsDue = runCatching { AshaJson.decodeFromString<List<String>>(vaccinationsDueJson) }
        .getOrDefault(emptyList()),
    nextVisitDate = nextVisitDate,
    ifaTabletsGiven = ifaTabletsGiven,
    hasDangerSign = hasDangerSign,
    dangerSigns = runCatching { AshaJson.decodeFromString<List<String>>(dangerSignsJson) }
        .getOrDefault(emptyList()),
    notes = notes,
    rawTranscript = rawTranscript,
    languageCode = languageCode,
    createdAt = createdAt,
)

fun FollowUp.toEntity(): FollowUpEntity = FollowUpEntity(
    id = id,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    visitId = visitId,
    dueDate = dueDate,
    purpose = purpose,
    vaccinesDueJson = AshaJson.encodeToString(vaccinesDue),
    status = status,
    createdAt = createdAt,
)

fun FollowUpEntity.toDomain(): FollowUp = FollowUp(
    id = id,
    beneficiaryId = beneficiaryId,
    beneficiaryName = beneficiaryName,
    visitId = visitId,
    dueDate = dueDate,
    purpose = purpose,
    vaccinesDue = runCatching { AshaJson.decodeFromString<List<String>>(vaccinesDueJson) }
        .getOrDefault(emptyList()),
    status = status,
    createdAt = createdAt,
)
