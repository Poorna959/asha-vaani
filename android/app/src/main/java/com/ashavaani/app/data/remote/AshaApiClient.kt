package com.ashavaani.app.data.remote

import com.ashavaani.app.model.AshaJson
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.CustomRegisterCreate
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.FollowUpStatusPatch
import com.ashavaani.app.model.HealthResponse
import com.ashavaani.app.model.PatientFollowUpContext
import com.ashavaani.app.model.PatientProfileView
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.PatientRecordCreate
import com.ashavaani.app.model.PatientRecordUpdate
import com.ashavaani.app.model.RegisterField
import com.ashavaani.app.model.RegisterFieldCreate
import com.ashavaani.app.model.VisitRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiHttpException(
    val statusCode: Int,
    override val message: String,
) : IOException("HTTP $statusCode: $message")

data class ExportedFile(
    val file: File,
    val filename: String,
    val mimeType: String,
)

/**
 * OkHttp REST client covering all Phase P1 FastAPI endpoints (`/health`, `/api/registers`,
 * `/api/patients`, `/api/visits`, `/api/follow-ups`, `/api/danger-signs`, and `.xlsx`/`.csv` exports).
 */
open class AshaApiClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    open suspend fun fetchHealth(baseUrl: String): HealthResponse =
        getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/health"))

    open suspend fun listRegisters(baseUrl: String): List<CustomRegister> =
        getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/registers"))

    open suspend fun getRegister(baseUrl: String, registerId: String): CustomRegister =
        getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/registers/$registerId"))

    open suspend fun createRegister(
        baseUrl: String,
        payload: CustomRegisterCreate,
    ): CustomRegister = postJson(
        url = ServerUrlResolver.buildRestUrl(baseUrl, "/api/registers"),
        bodyJson = AshaJson.encodeToString(payload),
    )

    open suspend fun addRegisterField(
        baseUrl: String,
        registerId: String,
        field: RegisterFieldCreate,
    ): RegisterField = postJson(
        url = ServerUrlResolver.buildRestUrl(baseUrl, "/api/registers/$registerId/fields"),
        bodyJson = AshaJson.encodeToString(field),
    )

    open suspend fun searchRegisterPatients(
        baseUrl: String,
        registerId: String,
        query: String = "",
    ): List<PatientRecord> =
        getJson(ServerUrlResolver.buildPatientsSearchUrl(baseUrl, registerId, query))

    open suspend fun addPatientToRegister(
        baseUrl: String,
        registerId: String,
        payload: PatientRecordCreate,
    ): PatientRecord = postJson(
        url = ServerUrlResolver.buildRestUrl(baseUrl, "/api/registers/$registerId/patients"),
        bodyJson = AshaJson.encodeToString(payload),
    )

    open suspend fun getPatientProfile(
        baseUrl: String,
        patientId: String,
    ): PatientProfileView =
        getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/patients/$patientId/profile"))

    open suspend fun updatePatient(
        baseUrl: String,
        patientId: String,
        payload: PatientRecordUpdate,
    ): PatientRecord = patchJson(
        url = ServerUrlResolver.buildRestUrl(baseUrl, "/api/patients/$patientId"),
        bodyJson = AshaJson.encodeToString(payload),
    )

    open suspend fun getPatientFollowUpContext(
        baseUrl: String,
        patientId: String,
    ): PatientFollowUpContext =
        getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/patients/$patientId/followup-context"))

    open suspend fun listVisits(
        baseUrl: String,
        beneficiaryId: String? = null,
        onlyDanger: Boolean = false,
    ): List<VisitRecord> {
        val params = buildList {
            if (!beneficiaryId.isNullOrBlank()) add("beneficiary_id=$beneficiaryId")
            if (onlyDanger) add("only_danger=true")
        }
        val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/visits$query"))
    }

    open suspend fun listFollowUps(
        baseUrl: String,
        status: String? = null,
        beneficiaryId: String? = null,
    ): List<FollowUp> {
        val params = buildList {
            if (!status.isNullOrBlank()) add("status=$status")
            if (!beneficiaryId.isNullOrBlank()) add("beneficiary_id=$beneficiaryId")
        }
        val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return getJson(ServerUrlResolver.buildRestUrl(baseUrl, "/api/follow-ups$query"))
    }

    open suspend fun updateFollowUpStatus(
        baseUrl: String,
        followUpId: String,
        status: String,
    ): FollowUp = patchJson(
        url = ServerUrlResolver.buildRestUrl(baseUrl, "/api/follow-ups/$followUpId"),
        bodyJson = AshaJson.encodeToString(FollowUpStatusPatch(status = status)),
    )

    suspend fun fetchDangerSignsRaw(baseUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(ServerUrlResolver.buildRestUrl(baseUrl, "/api/danger-signs"))
            .get()
            .build()
        executeString(request)
    }

    /**
     * Download `.xlsx` or `.csv` register export into `<cacheDir>/exports/` for FileProvider sharing.
     */
    suspend fun downloadRegisterExport(
        baseUrl: String,
        registerId: String,
        format: String,
        exportsDir: File,
    ): ExportedFile = withContext(Dispatchers.IO) {
        val safeFormat = if (format.equals("csv", ignoreCase = true)) "csv" else "xlsx"
        val url = ServerUrlResolver.buildExportUrl(baseUrl, registerId, safeFormat)
        val request = Request.Builder().url(url).get().build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, response.body?.string().orEmpty())
            }
            val bytes = response.body?.bytes()
                ?: throw IOException("Empty export body from $url")
            val filename = ServerUrlResolver.extractExportFilename(
                contentDisposition = response.header("Content-Disposition"),
                registerId = registerId,
                format = safeFormat,
            )
            if (!exportsDir.exists()) {
                exportsDir.mkdirs()
            }
            val outFile = File(exportsDir, filename)
            outFile.writeBytes(bytes)
            val mimeType = if (safeFormat == "csv") {
                "text/csv"
            } else {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }
            ExportedFile(file = outFile, filename = filename, mimeType = mimeType)
        }
    }

    private suspend inline fun <reified T> getJson(url: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        val body = executeString(request)
        AshaJson.decodeFromString(body)
    }

    private suspend inline fun <reified T> postJson(url: String, bodyJson: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val body = executeString(request)
            AshaJson.decodeFromString(body)
        }

    private suspend inline fun <reified T> patchJson(url: String, bodyJson: String): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val body = executeString(request)
            AshaJson.decodeFromString(body)
        }

    private fun executeString(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, bodyText.ifBlank { response.message })
            }
            return bodyText
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
