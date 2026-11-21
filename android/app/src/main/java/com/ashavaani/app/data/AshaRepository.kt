package com.ashavaani.app.data

import com.ashavaani.app.data.local.AppSettings
import com.ashavaani.app.data.local.AppSettingsStore
import com.ashavaani.app.data.local.AshaDao
import com.ashavaani.app.data.local.PendingSyncEntity
import com.ashavaani.app.data.local.PendingSyncOp
import com.ashavaani.app.data.local.toDomain
import com.ashavaani.app.data.local.toEntity
import com.ashavaani.app.data.remote.AshaApiClient
import com.ashavaani.app.data.remote.ExportedFile
import com.ashavaani.app.model.AshaJson
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.CustomRegisterCreate
import com.ashavaani.app.model.FieldAnswerItem
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.FollowUpStatusPatch
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Local-first write-through repository coordinating Room DB (`AshaDao`), FastAPI REST (`AshaApiClient`),
 * and WebSocket save events (`visit_saved`, `patient_saved`).
 *
 * All 4 UI tabs observe Room Flows directly so screens load instantaneously and stay usable offline.
 */
class AshaRepository(
    private val dao: AshaDao,
    private val api: AshaApiClient,
    val settings: StateFlow<AppSettings>,
) {
    constructor(
        dao: AshaDao,
        api: AshaApiClient,
        settingsStore: AppSettingsStore,
    ) : this(dao = dao, api = api, settings = settingsStore.settings)

    private val _health = MutableStateFlow<HealthResponse?>(null)
    val health: StateFlow<HealthResponse?> = _health.asStateFlow()

    private val _isSyncingOutbox = MutableStateFlow(false)
    val isSyncingOutbox: StateFlow<Boolean> = _isSyncingOutbox.asStateFlow()

    private val syncMutex = Mutex()

    private val currentBaseUrl: String get() = settings.value.backendUrl

    // ------------------------------------------------------------------
    // Reactive Local-First Room Observers
    // ------------------------------------------------------------------

    fun observePendingSyncCount(): Flow<Int> = dao.observePendingSyncCount()

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

        // Replay any offline-queued rural mutations in FIFO order before pulling latest server snapshot
        runCatching { flushPendingSyncOutbox() }

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

    /**
     * Replay offline-queued mutations in strict FIFO order when connectivity returns.
     * Stops early on transient network error so remaining items stay safely queued.
     */
    suspend fun flushPendingSyncOutbox(): Int = syncMutex.withLock {
        val pending = dao.getPendingSyncItems()
        if (pending.isEmpty()) return 0

        _isSyncingOutbox.value = true
        var syncedCount = 0
        val remappedRegisterIds = mutableMapOf<String, String>()
        val remappedPatientIds = mutableMapOf<String, String>()

        try {
            for (item in pending) {
                val success = runCatching {
                    when (item.opType) {
                        PendingSyncOp.CREATE_REGISTER -> {
                            val payload = AshaJson.decodeFromString<CustomRegisterCreate>(item.payloadJson)
                            val created = api.createRegister(currentBaseUrl, payload)
                            if (item.targetId != created.id) {
                                dao.deleteRegisterById(item.targetId)
                                remappedRegisterIds[item.targetId] = created.id
                            }
                            dao.upsertRegister(created.toEntity())
                        }

                        PendingSyncOp.ADD_PATIENT -> {
                            val rawPayload = AshaJson.decodeFromString<PatientRecordCreate>(item.payloadJson)
                            val resolvedRegId = remappedRegisterIds[rawPayload.registerId] ?: rawPayload.registerId
                            val payload = rawPayload.copy(registerId = resolvedRegId)
                            val created = api.addPatientToRegister(currentBaseUrl, resolvedRegId, payload)
                            if (item.targetId != created.id) {
                                dao.deletePatientById(item.targetId)
                                remappedPatientIds[item.targetId] = created.id
                            }
                            dao.upsertPatient(created.toEntity())
                        }

                        PendingSyncOp.UPDATE_PATIENT -> {
                            val resolvedPatientId = remappedPatientIds[item.targetId] ?: item.targetId
                            val payload = AshaJson.decodeFromString<PatientRecordUpdate>(item.payloadJson)
                            val updated = api.updatePatient(currentBaseUrl, resolvedPatientId, payload)
                            dao.upsertPatient(updated.toEntity())
                        }

                        PendingSyncOp.UPDATE_FOLLOW_UP_STATUS -> {
                            val patch = AshaJson.decodeFromString<FollowUpStatusPatch>(item.payloadJson)
                            val updated = api.updateFollowUpStatus(currentBaseUrl, item.targetId, patch.status)
                            dao.upsertFollowUp(updated.toEntity())
                        }
                    }
                }.isSuccess

                if (success) {
                    dao.deletePendingSyncById(item.id)
                    syncedCount += 1
                } else {
                    // Backend still unreachable — preserve FIFO order for next reconnect
                    break
                }
            }
        } finally {
            _isSyncingOutbox.value = false
        }
        syncedCount
    }

    suspend fun refreshRegisterPatients(registerId: String, query: String = ""): Result<List<PatientRecord>> =
        runCatching {
            val remote = api.searchRegisterPatients(currentBaseUrl, registerId, query)
            if (query.isBlank()) {
                dao.upsertPatients(remote.map { it.toEntity() })
            }
            remote
        }

    suspend fun createRegister(payload: CustomRegisterCreate): Result<CustomRegister> {
        val remote = runCatching { api.createRegister(currentBaseUrl, payload) }
        if (remote.isSuccess) {
            val created = remote.getOrThrow()
            dao.upsertRegister(created.toEntity())
            return Result.success(created)
        }

        // Offline fallback: persist locally + enqueue FIFO outbox entry
        val now = Instant.now().toString()
        val localRegId = "LOCAL-REG-${UUID.randomUUID().toString().take(8).uppercase()}"
        val localRegister = CustomRegister(
            id = localRegId,
            name = payload.name.trim(),
            description = payload.description.trim(),
            fields = payload.fields.mapIndexed { idx, f ->
                RegisterField(
                    id = "LOCAL-FLD-${idx + 1}",
                    registerId = localRegId,
                    fieldName = f.fieldName.trim(),
                    fieldType = f.fieldType,
                    orderIndex = f.orderIndex,
                )
            },
            createdAt = now,
        )
        dao.upsertRegister(localRegister.toEntity())
        dao.enqueuePendingSync(
            PendingSyncEntity(
                opType = PendingSyncOp.CREATE_REGISTER,
                targetId = localRegId,
                payloadJson = AshaJson.encodeToString(payload),
                createdAt = now,
            )
        )
        return Result.success(localRegister)
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
    ): Result<PatientRecord> {
        val remote = runCatching { api.addPatientToRegister(currentBaseUrl, registerId, payload) }
        if (remote.isSuccess) {
            val created = remote.getOrThrow()
            dao.upsertPatient(created.toEntity())
            return Result.success(created)
        }

        val now = Instant.now().toString()
        val localPatientId = payload.id ?: "LOCAL-BEN-${UUID.randomUUID().toString().take(8).uppercase()}"
        val localPatient = PatientRecord(
            id = localPatientId,
            registerId = registerId,
            patientName = payload.patientName.trim(),
            category = payload.category,
            fieldAnswers = payload.fieldAnswers,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertPatient(localPatient.toEntity())
        dao.enqueuePendingSync(
            PendingSyncEntity(
                opType = PendingSyncOp.ADD_PATIENT,
                targetId = localPatientId,
                payloadJson = AshaJson.encodeToString(payload.copy(registerId = registerId)),
                createdAt = now,
            )
        )
        return Result.success(localPatient)
    }

    suspend fun updatePatient(
        patientId: String,
        payload: PatientRecordUpdate,
    ): Result<PatientRecord> {
        val remote = runCatching { api.updatePatient(currentBaseUrl, patientId, payload) }
        if (remote.isSuccess) {
            val updated = remote.getOrThrow()
            dao.upsertPatient(updated.toEntity())
            return Result.success(updated)
        }

        val existing = dao.getPatientById(patientId)?.toDomain()
            ?: return Result.failure(remote.exceptionOrNull() ?: NoSuchElementException("Patient $patientId not found"))
        val now = Instant.now().toString()
        val mergedAnswers = existing.fieldAnswers.toMutableMap().apply {
            payload.fieldAnswers?.let { putAll(it) }
        }
        val updatedLocal = existing.copy(
            patientName = payload.patientName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.patientName,
            category = payload.category ?: existing.category,
            fieldAnswers = mergedAnswers,
            updatedAt = now,
        )
        dao.upsertPatient(updatedLocal.toEntity())
        dao.enqueuePendingSync(
            PendingSyncEntity(
                opType = PendingSyncOp.UPDATE_PATIENT,
                targetId = patientId,
                payloadJson = AshaJson.encodeToString(payload),
                createdAt = now,
            )
        )
        return Result.success(updatedLocal)
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

    suspend fun updateFollowUpStatus(followUpId: String, status: String): Result<FollowUp> {
        val remote = runCatching { api.updateFollowUpStatus(currentBaseUrl, followUpId, status) }
        if (remote.isSuccess) {
            val updated = remote.getOrThrow()
            dao.upsertFollowUp(updated.toEntity())
            return Result.success(updated)
        }

        val existing = dao.getFollowUpById(followUpId)?.toDomain()
            ?: return Result.failure(remote.exceptionOrNull() ?: NoSuchElementException("Follow-up $followUpId not found"))
        val updatedLocal = existing.copy(status = status)
        dao.upsertFollowUp(updatedLocal.toEntity())
        dao.enqueuePendingSync(
            PendingSyncEntity(
                opType = PendingSyncOp.UPDATE_FOLLOW_UP_STATUS,
                targetId = followUpId,
                payloadJson = AshaJson.encodeToString(FollowUpStatusPatch(status = status)),
                createdAt = Instant.now().toString(),
            )
        )
        return Result.success(updatedLocal)
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
