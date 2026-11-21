package com.ashavaani.app.data

import com.ashavaani.app.data.local.AppSettings
import com.ashavaani.app.data.local.AppSettingsStore
import com.ashavaani.app.data.local.AshaDao
import com.ashavaani.app.data.local.toDomain
import com.ashavaani.app.data.local.toEntity
import com.ashavaani.app.data.remote.AshaApiClient
import com.ashavaani.app.data.remote.ExportedFile
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.CustomRegisterCreate
import com.ashavaani.app.model.FieldAnswerItem
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.HealthResponse
import com.ashavaani.app.model.LastVisitSummary
import com.ashavaani.app.model.PatientFollowUpContext
import com.ashavaani.app.model.PatientProfileView
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.PatientRecordCreate
import com.ashavaani.app.model.PatientRecordUpdate
import com.ashavaani.app.model.RegisterField
import com.ashavaani.app.model.RegisterFieldCreate
import com.ashavaani.app.model.VisitRecord
import com.ashavaani.app.model.WsPatientSavedEvent
import com.ashavaani.app.model.WsVisitSavedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import java.io.File

/**
 * Local-first write-through repository coordinating Room DB (`AshaDao`), FastAPI REST (`AshaApiClient`),
 * and WebSocket save events (`visit_saved`, `patient_saved`).
 *
 * All 4 UI tabs observe Room Flows directly so screens load instantaneously and stay usable offline.
 */
class AshaRepository(
    private val dao: AshaDao,
    private val api: AshaApiClient,
    private val settingsStore: AppSettingsStore,
) {
    private val _health = MutableStateFlow<HealthResponse?>(null)
    val health: StateFlow<HealthResponse?> = _health.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val currentBaseUrl: String get() = settingsStore.settings.value.backendUrl

    // ------------------------------------------------------------------
    // Reactive Local-First Room Observers
    // ------------------------------------------------------------------

    fun observeRegisters(): Flow<List<CustomRegister>> =
        dao.observeRegisters().map { list -> list.map { it.toDomain() } }

    fun observePatients(registerId: String, query: String = ""): Flow<List<PatientRecord>> {
        val trimmed = query.trim()
        val source = if (trimmed.isEmpty()) {
            dao.observePatientsInRegister(registerId)
        } else {
            dao.searchPatientsInRegister(registerId, trimmed)
        }
        return source.map { list -> list.map { it.toDomain() } }
    }

    fun observePatientCountsByRegister(): Flow<Map<String, Int>> =
        dao.observeAllPatients().map { rows -> rows.groupingBy { it.registerId }.eachCount() }

    fun observeVisits(onlyDanger: Boolean = false): Flow<List<VisitRecord>> {
        val source = if (onlyDanger) dao.observeEscalatedVisits() else dao.observeAllVisits()
        return source.map { list -> list.map { it.toDomain() } }
    }

    fun observeFollowUps(): Flow<List<FollowUp>> =
        dao.observeFollowUps().map { list -> list.map { it.toDomain() } }

    fun observeOpenFollowUpCount(): Flow<Int> = dao.observeOpenFollowUpCount()

    // ------------------------------------------------------------------
    // Write-Through WebSocket Mirrors (Instant Local Persistence)
    // ------------------------------------------------------------------

    suspend fun onVisitSavedFromWs(event: WsVisitSavedEvent) {
        dao.upsertVisit(event.visit.toEntity())
        event.followUp?.let { dao.upsertFollowUp(it.toEntity()) }
    }

    suspend fun onPatientSavedFromWs(event: WsPatientSavedEvent) {
        dao.upsertPatient(event.patient.toEntity())
    }

    // ------------------------------------------------------------------
    // Server Refresh & Write-Through Mutations
    // ------------------------------------------------------------------

    suspend fun refreshHealth(): Result<HealthResponse> = runCatching {
        val h = api.fetchHealth(currentBaseUrl)
        _health.value = h
        h
    }

    suspend fun refreshAllFromServer(): Result<Unit> = runCatching {
        runCatching { api.fetchHealth(currentBaseUrl) }.onSuccess { _health.value = it }

        val registers = api.listRegisters(currentBaseUrl)
        dao.upsertRegisters(registers.map { it.toEntity() })

        registers.forEach { reg ->
            runCatching { api.searchRegisterPatients(currentBaseUrl, reg.id) }.onSuccess { patients ->
                dao.upsertPatients(patients.map { it.toEntity() })
            }
        }

        val visits = api.listVisits(currentBaseUrl)
        dao.upsertVisits(visits.map { it.toEntity() })

        val followUps = api.listFollowUps(currentBaseUrl)
        dao.upsertFollowUps(followUps.map { it.toEntity() })
    }

    suspend fun refreshRegisterPatients(registerId: String, query: String = ""): Result<List<PatientRecord>> =
        runCatching {
            val remote = api.searchRegisterPatients(currentBaseUrl, registerId, query)
            if (query.isBlank()) {
                dao.upsertPatients(remote.map { it.toEntity() })
            }
            remote
        }

    suspend fun createRegister(payload: CustomRegisterCreate): Result<CustomRegister> = runCatching {
        val created = api.createRegister(currentBaseUrl, payload)
        dao.upsertRegister(created.toEntity())
        created
    }

    suspend fun addRegisterField(
        registerId: String,
        field: RegisterFieldCreate,
    ): Result<RegisterField> = runCatching {
        val added = api.addRegisterField(currentBaseUrl, registerId, field)
        runCatching { api.getRegister(currentBaseUrl, registerId) }.onSuccess { refreshed ->
            dao.upsertRegister(refreshed.toEntity())
        }
        added
    }

    suspend fun addPatient(
        registerId: String,
        payload: PatientRecordCreate,
    ): Result<PatientRecord> = runCatching {
        val created = api.addPatientToRegister(currentBaseUrl, registerId, payload)
        dao.upsertPatient(created.toEntity())
        created
    }

    suspend fun updatePatient(
        patientId: String,
        payload: PatientRecordUpdate,
    ): Result<PatientRecord> = runCatching {
        val updated = api.updatePatient(currentBaseUrl, patientId, payload)
        dao.upsertPatient(updated.toEntity())
        updated
    }

    /**
     * Fetch clean mobile `Field: Answer` profile view from backend, transparently falling back
     * to Room cache if offline so patient profiles are always readable in rural dead zones.
     */
    suspend fun getPatientProfile(patientId: String): Result<PatientProfileView> {
        val remote = runCatching { api.getPatientProfile(currentBaseUrl, patientId) }
        if (remote.isSuccess) return remote

        val localPatient = dao.getPatientById(patientId)?.toDomain()
            ?: return Result.failure(remote.exceptionOrNull() ?: NoSuchElementException("Patient $patientId not found"))
        val localRegister = dao.getRegisterById(localPatient.registerId)?.toDomain()
        return Result.success(buildLocalProfileView(localPatient, localRegister))
    }

    /**
     * Fetch FR-7.6 follow-up baseline context from backend, with local Room fallback when offline.
     */
    suspend fun getFollowUpContext(patientId: String): Result<PatientFollowUpContext> {
        val remote = runCatching { api.getPatientFollowUpContext(currentBaseUrl, patientId) }
        if (remote.isSuccess) return remote

        val localPatient = dao.getPatientById(patientId)?.toDomain()
            ?: return Result.failure(remote.exceptionOrNull() ?: NoSuchElementException("Patient $patientId not found"))
        val localRegister = dao.getRegisterById(localPatient.registerId)?.toDomain()
        val lastVisit = dao.getVisitsForBeneficiary(patientId).firstOrNull()?.toDomain()

        return Result.success(
            PatientFollowUpContext(
                beneficiaryId = localPatient.id,
                beneficiaryName = localPatient.patientName,
                category = localPatient.category,
                registerName = localRegister?.name ?: localPatient.registerId,
                baselineFields = localPatient.fieldAnswers.filterValues { it !is JsonNull },
                lastVisit = lastVisit?.let {
                    LastVisitSummary(
                        visitType = it.visitType,
                        weightKg = it.weightKg,
                        vaccinationsDue = it.vaccinationsDue,
                        nextVisitDate = it.nextVisitDate,
                        hasDangerSign = it.hasDangerSign,
                        createdAt = it.createdAt,
                    )
                },
            )
        )
    }

    suspend fun updateFollowUpStatus(followUpId: String, status: String): Result<FollowUp> = runCatching {
        val updated = api.updateFollowUpStatus(currentBaseUrl, followUpId, status)
        dao.upsertFollowUp(updated.toEntity())
        updated
    }

    suspend fun downloadRegisterExport(
        registerId: String,
        format: String,
        exportsDir: File,
    ): Result<ExportedFile> = runCatching {
        api.downloadRegisterExport(
            baseUrl = currentBaseUrl,
            registerId = registerId,
            format = format,
            exportsDir = exportsDir,
        )
    }

    companion object {
        /**
         * Pure helper to construct a `Field : Answer` profile view from local Room records.
         */
        fun buildLocalProfileView(
            patient: PatientRecord,
            register: CustomRegister?,
        ): PatientProfileView {
            val items = mutableListOf<FieldAnswerItem>()
            val included = mutableSetOf<String>()

            register?.fields?.sortedBy { it.orderIndex }?.forEach { field ->
                items.add(
                    FieldAnswerItem(
                        fieldName = field.fieldName,
                        answer = patient.fieldAnswers[field.fieldName],
                    )
                )
                included.add(field.fieldName)
            }

            patient.fieldAnswers.keys.sorted().forEach { extraKey ->
                if (extraKey !in included) {
                    items.add(
                        FieldAnswerItem(
                            fieldName = extraKey,
                            answer = patient.fieldAnswers[extraKey],
                        )
                    )
                }
            }

            return PatientProfileView(
                patientId = patient.id,
                registerId = patient.registerId,
                registerName = register?.name ?: patient.registerId,
                patientName = patient.patientName,
                category = patient.category,
                items = items,
                createdAt = patient.createdAt,
                updatedAt = patient.updatedAt,
            )
        }
    }
}
