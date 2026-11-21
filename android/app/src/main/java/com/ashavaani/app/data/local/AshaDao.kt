package com.ashavaani.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AshaDao {

    // --- Custom Registers ---

    @Query("SELECT * FROM custom_registers ORDER BY createdAt DESC")
    fun observeRegisters(): Flow<List<CustomRegisterEntity>>

    @Query("SELECT * FROM custom_registers WHERE id = :registerId LIMIT 1")
    suspend fun getRegisterById(registerId: String): CustomRegisterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegisters(items: List<CustomRegisterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegister(item: CustomRegisterEntity)

    // --- Patients ---

    @Query("SELECT * FROM patients WHERE registerId = :registerId ORDER BY updatedAt DESC, patientName ASC")
    fun observePatientsInRegister(registerId: String): Flow<List<PatientEntity>>

    @Query(
        """
        SELECT * FROM patients
        WHERE registerId = :registerId
          AND (patientName LIKE '%' || :query || '%' OR id LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC, patientName ASC
        """
    )
    fun searchPatientsInRegister(registerId: String, query: String): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients WHERE id = :patientId LIMIT 1")
    suspend fun getPatientById(patientId: String): PatientEntity?

    @Query("SELECT COUNT(*) FROM patients WHERE registerId = :registerId")
    fun observePatientCount(registerId: String): Flow<Int>

    @Query("SELECT * FROM patients")
    fun observeAllPatients(): Flow<List<PatientEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPatients(items: List<PatientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPatient(item: PatientEntity)

    // --- Visits ---

    @Query("SELECT * FROM visits ORDER BY createdAt DESC")
    fun observeAllVisits(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE hasDangerSign = 1 ORDER BY createdAt DESC")
    fun observeEscalatedVisits(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE beneficiaryId = :beneficiaryId ORDER BY createdAt DESC")
    suspend fun getVisitsForBeneficiary(beneficiaryId: String): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVisits(items: List<VisitEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVisit(item: VisitEntity)

    // --- Follow-Ups ---

    @Query(
        """
        SELECT * FROM follow_ups
        ORDER BY
            CASE status
                WHEN 'ESCALATED' THEN 0
                WHEN 'PENDING' THEN 1
                ELSE 2
            END ASC,
            dueDate ASC,
            createdAt DESC
        """
    )
    fun observeFollowUps(): Flow<List<FollowUpEntity>>

    @Query("SELECT COUNT(*) FROM follow_ups WHERE status != 'COMPLETED'")
    fun observeOpenFollowUpCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFollowUps(items: List<FollowUpEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFollowUp(item: FollowUpEntity)
}
