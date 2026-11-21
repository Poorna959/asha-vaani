package com.ashavaani.app.ui.walkmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashavaani.app.AppContainer
import com.ashavaani.app.audio.PcmAudioCapturer
import com.ashavaani.app.audio.PcmAudioPlayer
import com.ashavaani.app.data.AshaRepository
import com.ashavaani.app.data.local.AppSettingsStore
import com.ashavaani.app.data.remote.VoiceSessionClient
import com.ashavaani.app.model.RegisterIntakeDraft
import com.ashavaani.app.model.SupportedLanguage
import com.ashavaani.app.model.VisitDraft
import com.ashavaani.app.model.WsBargeInClearEvent
import com.ashavaani.app.model.WsDangerEscalationEvent
import com.ashavaani.app.model.WsFormUpdateEvent
import com.ashavaani.app.model.WsPatientSavedEvent
import com.ashavaani.app.model.WsRegisterIntakeUpdateEvent
import com.ashavaani.app.model.WsVisitSavedEvent
import com.ashavaani.app.ui.components.HapticCue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates the hands-free Walk-Mode voice loop:
 * AudioCapturer (16kHz PCM) <-> VoiceSessionClient (/ws/session) <-> AudioPlayer (Timbre v3.0)
 * plus write-through Room persistence via AshaRepository and haptic/escalation signals.
 */
class WalkModeViewModel(
    private val repository: AshaRepository,
    private val settingsStore: AppSettingsStore,
    private val voiceClient: VoiceSessionClient,
    private val audioCapturer: PcmAudioCapturer,
    private val audioPlayer: PcmAudioPlayer,
) : ViewModel() {

    private val initialSettings = settingsStore.settings.value

    private val _uiState = MutableStateFlow(
        WalkModeUiState(
            activeLanguage = SupportedLanguage.fromCode(initialSettings.languageCode),
            backendUrl = initialSettings.backendUrl,
            phcPhoneNumber = initialSettings.phcPhoneNumber,
            dspCapabilities = audioCapturer.dspCapabilities.value,
        )
    )
    val uiState: StateFlow<WalkModeUiState> = _uiState.asStateFlow()

    private val _hapticEvents = MutableSharedFlow<HapticCue>(extraBufferCapacity = 16)
    val hapticEvents: SharedFlow<HapticCue> = _hapticEvents.asSharedFlow()

    val openFollowUpCount: StateFlow<Int> = MutableStateFlow(0).also { backing ->
        viewModelScope.launch {
            repository.observeOpenFollowUpCount().collect { backing.value = it }
        }
    }

    init {
        // Direct low-latency audio path: binary Timbre v3.0 PCM -> AudioTrack
        voiceClient.onBinaryAudio = { pcmBytes ->
            audioPlayer.enqueuePcm(pcmBytes)
        }

        observeAudioFlows()
        observeSettingsAndHealth()
        observeWebSocketEvents()
        connectAndRefresh()
    }

    private fun observeAudioFlows() {
        viewModelScope.launch {
            audioCapturer.isCapturing.collect { capturing ->
                _uiState.update { it.copy(isMicActive = capturing) }
            }
        }
        viewModelScope.launch {
            audioCapturer.rmsLevel.collect { rms ->
                _uiState.update { it.copy(rmsLevel = rms) }
            }
        }
        viewModelScope.launch {
            audioCapturer.dspCapabilities.collect { caps ->
                _uiState.update { it.copy(dspCapabilities = caps) }
            }
        }
        viewModelScope.launch {
            audioPlayer.isPlaying.collect { playing ->
                _uiState.update { it.copy(isAgentSpeaking = playing) }
            }
        }
        viewModelScope.launch {
            audioPlayer.lastBargeInFlushMs.collect { flushMs ->
                if (flushMs != null) {
                    _uiState.update { state ->
                        state.copy(metrics = state.metrics.copy(bargeInFlushMs = flushMs))
                    }
                }
            }
        }
    }

    private fun observeSettingsAndHealth() {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        backendUrl = settings.backendUrl,
                        phcPhoneNumber = settings.phcPhoneNumber,
                        activeLanguage = SupportedLanguage.fromCode(settings.languageCode),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.health.collect { health ->
                if (health != null) {
                    _uiState.update {
                        it.copy(
                            gnaniMode = health.gnaniMode.uppercase(),
                            isMockMode = health.isMockMode,
                            isSimDrawerOpen = if (!health.isMockMode) false else it.isSimDrawerOpen,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            voiceClient.connectionState.collect { conn ->
                _uiState.update { it.copy(connectionState = conn) }
            }
        }
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            voiceClient.events.collect { event ->
                when (event) {
                    is WsBargeInClearEvent -> {
                        val flushMs = audioPlayer.flushImmediately()
                        _uiState.update { state ->
                            WalkModeStateReducer.reduceServerEvent(state, event).copy(
                                metrics = state.metrics.copy(bargeInFlushMs = flushMs)
                            )
                        }
                    }

                    is WsDangerEscalationEvent -> {
                        _hapticEvents.tryEmit(HapticCue.DANGER_ESCALATION)
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }

                    is WsFormUpdateEvent -> {
                        if (event.fieldDiff != null || event.fieldDiffs.isNotEmpty()) {
                            _hapticEvents.tryEmit(HapticCue.FIELD_DIFF)
                        }
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }

                    is WsRegisterIntakeUpdateEvent -> {
                        if (event.fieldDiff != null || event.fieldDiffs.isNotEmpty()) {
                            _hapticEvents.tryEmit(HapticCue.FIELD_DIFF)
                        }
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }

                    is WsVisitSavedEvent -> {
                        _hapticEvents.tryEmit(HapticCue.RECORD_SAVED)
                        repository.onVisitSavedFromWs(event)
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }

                    is WsPatientSavedEvent -> {
                        _hapticEvents.tryEmit(HapticCue.RECORD_SAVED)
                        repository.onPatientSavedFromWs(event)
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }

                    else -> {
                        _uiState.update { WalkModeStateReducer.reduceServerEvent(it, event) }
                    }
                }
            }
        }
    }

    fun connectAndRefresh() {
        val current = settingsStore.settings.value
        voiceClient.connect(
            baseUrl = current.backendUrl,
            languageCode = current.languageCode,
        )
        viewModelScope.launch {
            repository.refreshAllFromServer()
        }
    }

    /**
     * Primary one-thumb mic interaction:
     * - If agent is speaking TTS, immediately barge-in (flush AudioTrack + send barge_in) and keep mic live.
     * - Otherwise toggle continuous hands-free 16kHz PCM streaming on/off.
     */
    fun onMicButtonPressed() {
        _hapticEvents.tryEmit(HapticCue.MIC_TOGGLE)
        val state = _uiState.value

        if (state.isAgentSpeaking) {
            val flushMs = audioPlayer.flushImmediately()
            voiceClient.sendBargeIn()
            _uiState.update {
                it.copy(
                    isAgentSpeaking = false,
                    metrics = it.metrics.copy(bargeInFlushMs = flushMs),
                    toastMessage = "Interrupted TTS (${String.format("%.1f", flushMs)}ms)",
                )
            }
            if (!state.isMicActive) {
                startAudioCapture()
            }
            return
        }

        if (state.isMicActive) {
            audioCapturer.stop()
            voiceClient.sendFinalizeTurn()
        } else {
            startAudioCapture()
        }
    }

    private fun startAudioCapture() {
        audioPlayer.ensureStarted()
        val started = audioCapturer.start(
            onFrame = { frame640 -> voiceClient.sendPcmFrame(frame640) },
            onError = { err ->
                _uiState.update { it.copy(errorBanner = "Microphone error: ${err.message}") }
            },
        )
        if (!started) {
            _uiState.update { it.copy(errorBanner = "Unable to start 16kHz microphone capture.") }
        }
    }

    fun selectLanguage(language: SupportedLanguage) {
        settingsStore.updateLanguageCode(language.code)
        voiceClient.sendLanguageChange(language.code)
        _uiState.update {
            it.copy(
                activeLanguage = language,
                toastMessage = "Language: ${language.nativeLabel}",
            )
        }
    }

    /**
     * FR-7.6: Launch Walk Mode with a registered patient's baseline pre-loaded.
     */
    fun startPatientFollowUp(patientId: String) {
        voiceClient.sendPatientSelect(patientId)
        viewModelScope.launch {
            val context = repository.getFollowUpContext(patientId).getOrNull()
            _uiState.update { state ->
                state.copy(
                    inputMode = WalkInputMode.ROUTINE_VISIT,
                    registerIntakeDraft = null,
                    followUpContext = context,
                    visitDraft = state.visitDraft.copy(
                        beneficiaryId = context?.beneficiaryId ?: patientId,
                        beneficiaryName = context?.beneficiaryName,
                        visitType = context?.category,
                    ),
                    activeFieldDiff = null,
                    toastMessage = context?.let { "Baseline loaded: ${it.beneficiaryName}" },
                )
            }
        }
    }

    fun clearFollowUpContext() {
        voiceClient.sendResetDraft(mode = "visit")
        _uiState.update {
            it.copy(
                followUpContext = null,
                visitDraft = VisitDraft(),
                activeFieldDiff = null,
            )
        }
    }

    /**
     * FR-7.2: Enter voice-driven onboarding mode for a custom register.
     */
    fun startRegisterVoiceIntake(
        registerId: String,
        registerName: String,
        expectedFields: List<String>,
    ) {
        voiceClient.sendRegisterSelect(registerId)
        _uiState.update {
            it.copy(
                inputMode = WalkInputMode.REGISTER_INTAKE,
                followUpContext = null,
                registerIntakeDraft = RegisterIntakeDraft(
                    registerId = registerId,
                    registerName = registerName,
                    expectedFields = expectedFields,
                ),
                activeFieldDiff = null,
                partialTranscript = "",
                finalTranscript = "",
                agentReadback = "",
                toastMessage = "Voice Intake: $registerName",
            )
        }
    }

    fun exitRegisterIntake() {
        audioPlayer.flushImmediately()
        voiceClient.sendResetDraft(mode = "visit")
        _uiState.update {
            it.copy(
                inputMode = WalkInputMode.ROUTINE_VISIT,
                registerIntakeDraft = null,
                visitDraft = VisitDraft(),
                activeFieldDiff = null,
                partialTranscript = "",
                finalTranscript = "",
                agentReadback = "",
            )
        }
    }

    fun resetCurrentDraft() {
        audioPlayer.flushImmediately()
        voiceClient.sendResetDraft()
        _uiState.update { state ->
            state.copy(
                sessionStatus = "LISTENING",
                visitDraft = VisitDraft(),
                registerIntakeDraft = state.registerIntakeDraft?.copy(
                    patientName = null,
                    fieldAnswers = emptyMap(),
                ),
                activeFieldDiff = null,
                followUpContext = null,
                partialTranscript = "",
                finalTranscript = "",
                agentReadback = "",
                errorBanner = null,
            )
        }
    }

    fun sendSimulatedUtterance(text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        audioPlayer.ensureStarted()
        voiceClient.sendTextTurn(
            text = cleaned,
            languageCode = _uiState.value.activeLanguage.code,
        )
    }

    fun toggleSimDrawer() {
        _uiState.update { it.copy(isSimDrawerOpen = !it.isSimDrawerOpen) }
    }

    fun dismissDangerEscalation() {
        _uiState.update { it.copy(activeDangerEscalation = null) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorBanner = null) }
    }

    fun saveSettingsAndReconnect(backendUrl: String, phcPhone: String) {
        val updated = settingsStore.updateBackendUrl(backendUrl)
        settingsStore.updatePhcPhoneNumber(phcPhone)
        audioCapturer.stop()
        voiceClient.connect(
            baseUrl = updated.backendUrl,
            languageCode = updated.languageCode,
        )
        viewModelScope.launch {
            repository.refreshAllFromServer()
        }
        _uiState.update {
            it.copy(
                toastMessage = "Reconnecting to ${updated.backendUrl}",
                errorBanner = null,
            )
        }
    }

    override fun onCleared() {
        audioCapturer.stop()
        voiceClient.onBinaryAudio = null
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WalkModeViewModel(
                        repository = container.repository,
                        settingsStore = container.settingsStore,
                        voiceClient = container.voiceSessionClient,
                        audioCapturer = container.audioCapturer,
                        audioPlayer = container.audioPlayer,
                    ) as T
                }
            }
    }
}
