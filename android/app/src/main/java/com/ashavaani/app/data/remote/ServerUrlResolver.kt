package com.ashavaani.app.data.remote

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Pure URL normalization and endpoint builder for ASHA Vaani FastAPI server.
 * Defaults to `http://127.0.0.1:8000` for zero-config USB `adb reverse tcp:8000 tcp:8000`.
 */
object ServerUrlResolver {
    const val DEFAULT_BASE_URL: String = "http://127.0.0.1:8000"
    const val DEFAULT_PHC_PHONE: String = "108"

    fun normalizeBaseUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_BASE_URL

        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://${trimmed.substring(5)}"
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://${trimmed.substring(6)}"
            else -> "http://$trimmed"
        }

        return withScheme
            .removeSuffix("/")
            .removeSuffix("/ws/session")
            .removeSuffix("/")
    }

    fun toWebSocketUrl(rawBaseUrl: String?): String {
        val httpBase = normalizeBaseUrl(rawBaseUrl)
        val wsBase = when {
            httpBase.startsWith("https://", ignoreCase = true) ->
                "wss://${httpBase.substring(8)}"
            httpBase.startsWith("http://", ignoreCase = true) ->
                "ws://${httpBase.substring(7)}"
            else -> "ws://$httpBase"
        }
        return "$wsBase/ws/session"
    }

    fun buildRestUrl(rawBaseUrl: String?, path: String): String {
        val base = normalizeBaseUrl(rawBaseUrl)
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$base$cleanPath"
    }

    fun buildPatientsSearchUrl(rawBaseUrl: String?, registerId: String, query: String): String {
        val encodedId = encodePathSegment(registerId)
        val base = buildRestUrl(rawBaseUrl, "/api/registers/$encodedId/patients")
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            base
        } else {
            val encodedQ = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
            "$base?q=$encodedQ"
        }
    }

    fun buildExportUrl(rawBaseUrl: String?, registerId: String, format: String): String {
        val safeFormat = if (format.equals("csv", ignoreCase = true)) "csv" else "xlsx"
        val encodedId = encodePathSegment(registerId)
        return buildRestUrl(rawBaseUrl, "/api/registers/$encodedId/export?format=$safeFormat")
    }

    fun extractExportFilename(
        contentDisposition: String?,
        registerId: String,
        format: String,
    ): String {
        val safeFormat = if (format.equals("csv", ignoreCase = true)) "csv" else "xlsx"
        val match = contentDisposition?.let { header ->
            Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)
        }
        val rawName = match?.trim().takeUnless { it.isNullOrBlank() } ?: "${registerId}_export.$safeFormat"
        return rawName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
