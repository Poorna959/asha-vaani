package com.ashavaani.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Low-latency 16kHz 16-bit mono PCM audio capturer using Android's `VOICE_COMMUNICATION`
 * pipeline with hardware AcousticEchoCanceler, NoiseSuppressor, and AutomaticGainControl.
 *
 * Emits exact 640-byte (20ms) frames for streaming to FastAPI `/ws/session` and exposes
 * smoothed `rmsLevel` (`0f..1f`) to drive Walk Mode's concentric Mic Orb pulse rings.
 */
class PcmAudioCapturer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _dspCapabilities = MutableStateFlow(probeHardwareAvailability())
    val dspCapabilities: StateFlow<AudioDspCapabilities> = _dspCapabilities.asStateFlow()

    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private val chunker = PcmFrameChunker(PcmAudioConfig.FRAME_BYTES)

    /**
     * Start capturing 16kHz mono 16-bit PCM and delivering exact 20ms (640-byte) frames.
     * Caller must hold `Manifest.permission.RECORD_AUDIO`.
     */
    @SuppressLint("MissingPermission")
    fun start(
        onFrame: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): Boolean {
        if (_isCapturing.value) return true

        val minBuf = AudioRecord.getMinBufferSize(
            PcmAudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            onError(IllegalStateException("Device does not support 16kHz mono 16-bit PCM capture."))
            return false
        }

        val bufferSize = max(minBuf * 2, PcmAudioConfig.FRAME_BYTES * 8)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                PcmAudioConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            onError(t)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(IllegalStateException("AudioRecord failed to initialize."))
            return false
        }

        audioRecord = record
        attachHardwareDspEffects(record.audioSessionId)
        chunker.reset()

        try {
            record.startRecording()
        } catch (t: Throwable) {
            cleanupAudioResources()
            onError(t)
            return false
        }

        _isCapturing.value = true
        captureJob = scope.launch {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val readBuffer = ByteArray(PcmAudioConfig.FRAME_BYTES * 2)
            var smoothedRms = 0f

            try {
                while (isActive && _isCapturing.value) {
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        chunker.push(readBuffer, bytesRead) { frame640 ->
                            val instantRms = PcmFrameProcessor.computeNormalizedRms(frame640)
                            smoothedRms = PcmFrameProcessor.smoothRms(smoothedRms, instantRms)
                            _rmsLevel.value = smoothedRms
                            onFrame(frame640)
                        }
                    } else if (bytesRead < 0) {
                        onError(IllegalStateException("AudioRecord.read failed with code $bytesRead"))
                        break
                    }
                }
            } catch (t: Throwable) {
                if (_isCapturing.value) {
                    onError(t)
                }
            } finally {
                _rmsLevel.value = 0f
                _isCapturing.value = false
                cleanupAudioResources()
            }
        }
        return true
    }

    fun stop() {
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null
        _rmsLevel.value = 0f
        cleanupAudioResources()
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun attachHardwareDspEffects(audioSessionId: Int) {
        val aecAvail = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val nsAvail = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        val agcAvail = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)

        var aecOn = false
        var nsOn = false
        var agcOn = false

        if (aecAvail) {
            runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.let { effect ->
                    effect.enabled = true
                    echoCanceler = effect
                    aecOn = effect.enabled
                }
            }
        }
        if (nsAvail) {
            runCatching {
                NoiseSuppressor.create(audioSessionId)?.let { effect ->
                    effect.enabled = true
                    noiseSuppressor = effect
                    nsOn = effect.enabled
                }
            }
        }
        if (agcAvail) {
            runCatching {
                AutomaticGainControl.create(audioSessionId)?.let { effect ->
                    effect.enabled = true
                    gainControl = effect
                    agcOn = effect.enabled
                }
            }
        }

        _dspCapabilities.value = AudioDspCapabilities(
            aecAvailable = aecAvail,
            aecActive = aecOn,
            nsAvailable = nsAvail,
            nsActive = nsOn,
            agcAvailable = agcAvail,
            agcActive = agcOn,
        )
    }

    private fun cleanupAudioResources() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null

        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching { gainControl?.release() }
        gainControl = null

        runCatching {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        }
        audioRecord = null

        _dspCapabilities.value = _dspCapabilities.value.copy(
            aecActive = false,
            nsActive = false,
            agcActive = false,
        )
    }

    companion object {
        fun probeHardwareAvailability(): AudioDspCapabilities {
            return AudioDspCapabilities(
                aecAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
                nsAvailable = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
                agcAvailable = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false),
            )
        }
    }
}
