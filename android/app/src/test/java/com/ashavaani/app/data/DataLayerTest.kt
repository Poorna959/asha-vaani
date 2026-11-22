package com.ashavaani.app.data

import com.ashavaani.app.data.local.AppSettings
import com.ashavaani.app.data.local.AshaDao
import com.ashavaani.app.data.local.CustomRegisterEntity
import com.ashavaani.app.data.local.FollowUpEntity
import com.ashavaani.app.data.local.PatientEntity
import com.ashavaani.app.data.local.PendingSyncEntity
import com.ashavaani.app.data.local.VisitEntity
import com.ashavaani.app.data.local.toDomain
import com.ashavaani.app.data.local.toEntity
import com.ashavaani.app.data.remote.AshaApiClient
import com.ashavaani.app.data.remote.ReconnectBackoffPolicy
import com.ashavaani.app.data.remote.ServerUrlResolver
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.CustomRegisterCreate
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.PatientRecordCreate
import com.ashavaani.app.model.PatientRecordUpdate
import com.ashavaani.app.model.RegisterField
import com.ashavaani.app.model.RegisterFieldCreate
import com.ashavaani.app.model.VisitRecord
import com.ashavaani.app.model.toDisplayString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DataLayerTest {

    @Test
    fun `server url resolver normalizes http, https, bare host, and ws urls`() {
        assertEquals(ServerUrlResolver.DEFAULT_BASE_URL, ServerUrlResolver.normalizeBaseUrl(""))
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

    @Test
    fun `P3-04 reconnect backoff policy follows bounded 1s-2s-4s-8s-8s max 5 retries`() {
        val policy = ReconnectBackoffPolicy(baseDelayMs = 1_000L, maxDelayMs = 8_000L, maxAttempts = 5)
        assertNull(policy.nextDelayMs(0))
        assertEquals(1_000L, policy.nextDelayMs(1))
        assertEquals(2_000L, policy.nextDelayMs(2))
        assertEquals(4_000L, policy.nextDelayMs(3))
        assertEquals(8_000L, policy.nextDelayMs(4))
        assertEquals(8_000L, policy.nextDelayMs(5))
        assertNull(policy.nextDelayMs(6))
    }

    @Test
    fun `P3-04 offline mutations queue in FIFO outbox and replay seamlessly when connectivity returns`() = runBlocking {
        val fakeDao = InMemoryAshaDao()
        // Pre-seed one cached follow-up so offline completion works cleanly
        fakeDao.upsertFollowUp(
            FollowUp(
                id = "FUP-1",
                beneficiaryId = "BEN-001",
                beneficiaryName = "Sunita Devi",
                dueDate = "2026-11-22",
                purpose = "ANC Routine",
                status = "PENDING",
            ).toEntity()
        )

        var serverOnline = false
        val replayedOps = mutableListOf<String>()
        val fakeApi = object : AshaApiClient() {
            override suspend fun createRegister(baseUrl: String, payload: CustomRegisterCreate): CustomRegister {
                if (!serverOnline) throw IOException("Offline")
                replayedOps.add("CREATE_REGISTER:${payload.name}")
                return CustomRegister(id = "REG-SRV-1", name = payload.name, description = payload.description)
            }

            override suspend fun addPatientToRegister(
                baseUrl: String,
                registerId: String,
                payload: PatientRecordCreate,
            ): PatientRecord {
                if (!serverOnline) throw IOException("Offline")
                replayedOps.add("ADD_PATIENT:$registerId:${payload.patientName}")
                return PatientRecord(
                    id = "BEN-SRV-1",
                    registerId = registerId,
                    patientName = payload.patientName,
                    category = payload.category,
                    fieldAnswers = payload.fieldAnswers,
                )
            }

            override suspend fun updatePatient(
                baseUrl: String,
                patientId: String,
                payload: PatientRecordUpdate,
            ): PatientRecord {
                if (!serverOnline) throw IOException("Offline")
                replayedOps.add("UPDATE_PATIENT:$patientId")
                return PatientRecord(
                    id = patientId,
                    registerId = "REG-SRV-1",
                    patientName = payload.patientName ?: "Meera Bai",
                    fieldAnswers = payload.fieldAnswers ?: emptyMap(),
                )
            }

            override suspend fun updateFollowUpStatus(
                baseUrl: String,
                followUpId: String,
                status: String,
            ): FollowUp {
                if (!serverOnline) throw IOException("Offline")
                replayedOps.add("UPDATE_FOLLOWUP:$followUpId:$status")
                return FollowUp(
                    id = followUpId,
                    beneficiaryId = "BEN-001",
                    beneficiaryName = "Sunita Devi",
                    dueDate = "2026-11-22",
                    purpose = "ANC Routine",
                    status = status,
                )
            }
        }

        val repo = AshaRepository(
            dao = fakeDao,
            api = fakeApi,
            settings = MutableStateFlow(AppSettings()),
        )

        // 1. Perform 4 chained mutations while OFFLINE
        val regRes = repo.createRegister(
            CustomRegisterCreate(
                name = "Rural Survey",
                fields = listOf(RegisterFieldCreate("Age", "NUMBER", 0)),
            )
        )
        assertTrue(regRes.isSuccess)
        val localRegId = regRes.getOrThrow().id
        assertTrue(localRegId.startsWith("LOCAL-REG-"))

        val patRes = repo.addPatient(
            localRegId,
            PatientRecordCreate(registerId = localRegId, patientName = "Meera Bai"),
        )
        assertTrue(patRes.isSuccess)
        val localPatId = patRes.getOrThrow().id
        assertTrue(localPatId.startsWith("LOCAL-BEN-"))

        val updRes = repo.updatePatient(
            localPatId,
            PatientRecordUpdate(fieldAnswers = mapOf("Age" to JsonPrimitive(25))),
        )
        assertTrue(updRes.isSuccess)

        val fupRes = repo.updateFollowUpStatus("FUP-1", "COMPLETED")
        assertTrue(fupRes.isSuccess)
        assertEquals("COMPLETED", fakeDao.getFollowUpById("FUP-1")?.status)

        // Verify all 4 mutations are queued in Room outbox
        assertEquals(4, repo.observePendingSyncCount().first())

        // Flushing while still offline must keep all 4 queued
        assertEquals(0, repo.flushPendingSyncOutbox())
        assertEquals(4, repo.observePendingSyncCount().first())

        // 2. Connectivity returns -> FIFO replay remaps LOCAL IDs to server IDs & drains outbox
        serverOnline = true
        val synced = repo.flushPendingSyncOutbox()
        assertEquals(4, synced)
        assertEquals(0, repo.observePendingSyncCount().first())
        assertEquals(
            listOf(
                "CREATE_REGISTER:Rural Survey",
                "ADD_PATIENT:REG-SRV-1:Meera Bai",
                "UPDATE_PATIENT:BEN-SRV-1",
                "UPDATE_FOLLOWUP:FUP-1:COMPLETED",
            ),
            replayedOps,
        )
        assertNull(fakeDao.getRegisterById(localRegId))
        assertEquals("Rural Survey", fakeDao.getRegisterById("REG-SRV-1")?.name)
        assertNull(fakeDao.getPatientById(localPatId))
        assertEquals("Meera Bai", fakeDao.getPatientById("BEN-SRV-1")?.patientName)
    }

    @Test
    fun `P4-06 refreshAllFromServer coalesces rapid startup bursts and honors force refresh`() = runBlocking {
        val fakeDao = InMemoryAshaDao()
        var listRegisterCalls = 0
        var nowMs = 10_000L

        val fakeApi = object : AshaApiClient() {
            override suspend fun fetchHealth(baseUrl: String) =
                com.ashavaani.app.model.HealthResponse(status = "ok", gnaniMode = "live")

            override suspend fun listRegisters(baseUrl: String): List<CustomRegister> {
                listRegisterCalls += 1
                return emptyList()
            }

            override suspend fun listVisits(
                baseUrl: String,
                beneficiaryId: String?,
                onlyDanger: Boolean,
            ) = emptyList<VisitRecord>()

            override suspend fun listFollowUps(
                baseUrl: String,
                status: String?,
                beneficiaryId: String?,
            ) = emptyList<FollowUp>()
        }

        val repo = AshaRepository(
            dao = fakeDao,
            api = fakeApi,
            settings = MutableStateFlow(AppSettings()),
            refreshCooldownMs = 2_000L,
            clockMs = { nowMs },
        )

        // 3 rapid startup calls within 200ms -> coalesced into a single network pass!
        repo.refreshAllFromServer()
        nowMs += 100L
        repo.refreshAllFromServer()
        nowMs += 100L
        repo.refreshAllFromServer()
        assertEquals(1, listRegisterCalls)

        // Explicit force=true bypasses cooldown (manual Refresh tap)
        repo.refreshAllFromServer(force = true)
        assertEquals(2, listRegisterCalls)

        // After cooldown window expires (>2s), normal refresh runs cleanly again
        nowMs += 2_500L
        repo.refreshAllFromServer()
        assertEquals(3, listRegisterCalls)
    }

    private class InMemoryAshaDao : AshaDao {
        private val registers = MutableStateFlow<Map<String, CustomRegisterEntity>>(emptyMap())
        private val patients = MutableStateFlow<Map<String, PatientEntity>>(emptyMap())
        private val visits = MutableStateFlow<Map<String, VisitEntity>>(emptyMap())
        private val followUps = MutableStateFlow<Map<String, FollowUpEntity>>(emptyMap())
        private val outbox = MutableStateFlow<List<PendingSyncEntity>>(emptyList())
        private var nextOutboxId = 1L

        override fun observeRegisters(): Flow<List<CustomRegisterEntity>> =
            registers.map { it.values.toList() }

        override suspend fun getRegisterById(registerId: String): CustomRegisterEntity? =
            registers.value[registerId]

        override suspend fun upsertRegisters(items: List<CustomRegisterEntity>) {
            registers.value = registers.value + items.associateBy { it.id }
        }

        override suspend fun upsertRegister(item: CustomRegisterEntity) {
            registers.value = registers.value + (item.id to item)
        }

        override suspend fun deleteRegisterById(registerId: String) {
            registers.value = registers.value - registerId
        }

        override fun observePatientsInRegister(registerId: String): Flow<List<PatientEntity>> =
            patients.map { map -> map.values.filter { it.registerId == registerId } }

        override fun searchPatientsInRegister(registerId: String, query: String): Flow<List<PatientEntity>> =
            patients.map { map ->
                map.values.filter { it.registerId == registerId && it.patientName.contains(query, ignoreCase = true) }
            }

        override suspend fun getPatientById(patientId: String): PatientEntity? =
            patients.value[patientId]

        override fun observePatientCount(registerId: String): Flow<Int> =
            observePatientsInRegister(registerId).map { it.size }

        override fun observeAllPatients(): Flow<List<PatientEntity>> =
            patients.map { it.values.toList() }

        override suspend fun upsertPatients(items: List<PatientEntity>) {
            patients.value = patients.value + items.associateBy { it.id }
        }

        override suspend fun upsertPatient(item: PatientEntity) {
            patients.value = patients.value + (item.id to item)
        }

        override suspend fun deletePatientById(patientId: String) {
            patients.value = patients.value - patientId
        }

        override fun observeAllVisits(): Flow<List<VisitEntity>> = visits.map { it.values.toList() }

        override fun observeEscalatedVisits(): Flow<List<VisitEntity>> =
            visits.map { map -> map.values.filter { it.hasDangerSign } }

        override suspend fun getVisitsForBeneficiary(beneficiaryId: String): List<VisitEntity> =
            visits.value.values.filter { it.beneficiaryId == beneficiaryId }

        override suspend fun upsertVisits(items: List<VisitEntity>) {
            visits.value = visits.value + items.associateBy { it.id }
        }

        override suspend fun upsertVisit(item: VisitEntity) {
            visits.value = visits.value + (item.id to item)
        }

        override fun observeFollowUps(): Flow<List<FollowUpEntity>> = followUps.map { it.values.toList() }

        override fun observeOpenFollowUpCount(): Flow<Int> =
            followUps.map { map -> map.values.count { it.status != "COMPLETED" } }

        override suspend fun upsertFollowUps(items: List<FollowUpEntity>) {
            followUps.value = followUps.value + items.associateBy { it.id }
        }

        override suspend fun upsertFollowUp(item: FollowUpEntity) {
            followUps.value = followUps.value + (item.id to item)
        }

        override suspend fun getFollowUpById(followUpId: String): FollowUpEntity? =
            followUps.value[followUpId]

        override suspend fun enqueuePendingSync(item: PendingSyncEntity): Long {
            val assigned = nextOutboxId++
            outbox.value = outbox.value + item.copy(id = assigned)
            return assigned
        }

        override suspend fun getPendingSyncItems(): List<PendingSyncEntity> =
            outbox.value.sortedBy { it.id }

        override fun observePendingSyncCount(): Flow<Int> = outbox.map { it.size }

        override suspend fun deletePendingSyncById(outboxId: Long) {
            outbox.value = outbox.value.filterNot { it.id == outboxId }
        }
    }
}
