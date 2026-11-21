package com.ashavaani.app

import android.content.Context
import com.ashavaani.app.audio.PcmAudioCapturer
import com.ashavaani.app.audio.PcmAudioPlayer
import com.ashavaani.app.data.AshaRepository
import com.ashavaani.app.data.local.AppSettingsStore
import com.ashavaani.app.data.local.AshaDatabase
import com.ashavaani.app.data.remote.AshaApiClient
import com.ashavaani.app.data.remote.VoiceSessionClient

/**
 * Lightweight application-scoped dependency container shared across all 4 tabs and ViewModels.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore: AppSettingsStore = AppSettingsStore(appContext)
    val database: AshaDatabase = AshaDatabase.getInstance(appContext)
    val apiClient: AshaApiClient = AshaApiClient()
    val repository: AshaRepository = AshaRepository(
        dao = database.ashaDao(),
        api = apiClient,
        settingsStore = settingsStore,
    )
    val voiceSessionClient: VoiceSessionClient = VoiceSessionClient()
    val audioCapturer: PcmAudioCapturer = PcmAudioCapturer()
    val audioPlayer: PcmAudioPlayer = PcmAudioPlayer()

    fun release() {
        audioCapturer.release()
        audioPlayer.release()
        voiceSessionClient.release()
    }
}
