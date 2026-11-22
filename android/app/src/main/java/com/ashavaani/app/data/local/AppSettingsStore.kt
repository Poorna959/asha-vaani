package com.ashavaani.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.ashavaani.app.data.remote.ServerUrlResolver
import com.ashavaani.app.model.SupportedLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val backendUrl: String = ServerUrlResolver.DEFAULT_BASE_URL,
    val phcPhoneNumber: String = ServerUrlResolver.DEFAULT_PHC_PHONE,
    val languageCode: String = SupportedLanguage.HINDI.code,
)

/**
 * Local settings persistence for Backend URL (default Railway cloud endpoint),
 * PHC / 108 referral helpline number, and active Indic language code.
 */
class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun readSettings(): AppSettings {
        val url = ServerUrlResolver.normalizeBaseUrl(prefs.getString(KEY_BACKEND_URL, null))
        val phc = prefs.getString(KEY_PHC_PHONE, null)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: ServerUrlResolver.DEFAULT_PHC_PHONE
        val lang = prefs.getString(KEY_LANGUAGE, null)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: SupportedLanguage.HINDI.code
        return AppSettings(
            backendUrl = url,
            phcPhoneNumber = phc,
            languageCode = lang,
        )
    }

    fun updateBackendUrl(rawUrl: String): AppSettings {
        val normalized = ServerUrlResolver.normalizeBaseUrl(rawUrl)
        prefs.edit().putString(KEY_BACKEND_URL, normalized).apply()
        val updated = _settings.value.copy(backendUrl = normalized)
        _settings.value = updated
        return updated
    }

    fun updatePhcPhoneNumber(rawPhone: String): AppSettings {
        val cleaned = rawPhone.trim().ifEmpty { ServerUrlResolver.DEFAULT_PHC_PHONE }
        prefs.edit().putString(KEY_PHC_PHONE, cleaned).apply()
        val updated = _settings.value.copy(phcPhoneNumber = cleaned)
        _settings.value = updated
        return updated
    }

    fun updateLanguageCode(languageCode: String): AppSettings {
        val cleaned = languageCode.trim().ifEmpty { SupportedLanguage.HINDI.code }
        prefs.edit().putString(KEY_LANGUAGE, cleaned).apply()
        val updated = _settings.value.copy(languageCode = cleaned)
        _settings.value = updated
        return updated
    }

    companion object {
        private const val PREFS_NAME = "asha_vaani_settings"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_PHC_PHONE = "phc_phone_number"
        private const val KEY_LANGUAGE = "language_code"
    }
}
