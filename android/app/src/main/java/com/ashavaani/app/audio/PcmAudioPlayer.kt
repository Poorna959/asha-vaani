package com.ashavaani.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Pure-Kotlin outdoor-audible gain stage for 16-bit signed LE PCM TTS streams.
 * Prevents quiet conversational TTS from being drowned out in rural street/household noise
 * while clamping safely to Int16 bounds so audio never wraps or distorts.
 */
object PcmGainBooster {
    const val DEFAULT_OUTDOOR_GAIN: Float = 1.85f

    fun boost16BitLe(pcmBytes: ByteArray, gain: Float = DEFAULT_OUTDOOR_GAIN): ByteArray {
        if (pcmBytes.size < 2 || gain <= 1.0f) return pcmBytes
        val out = ByteArray(pcmBytes.size)
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val boosted = (sample * gain)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (boosted and 0xFF).toByte()
            out[i + 1] = ((boosted shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}

/**
 * Low-latency streaming 16kHz 16-bit mono PCM AudioTrack player for Gnani Timbre v3.0 TTS chunks.
 *
 * Routes playback explicitly to the device's main loudspeaker (`USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`
 * + `AudioManager` speakerphone pinning when no external headset is attached) so ASHA workers can
 * hear read-backs, danger alerts, and compliance refusals clearly outdoors without holding the
 * phone to their ear, while preserving sub-150ms barge-in via [flushImmediately].
 */
class PcmAudioPlayer(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val outputGain: Float = PcmGainBooster.DEFAULT_OUTDOOR_GAIN,
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoudspeakerActive = MutableStateFlow(true)
    val isLoudspeakerActive: StateFlow<Boolean> = _isLoudspeakerActive.asStateFlow()

    private val _lastBargeInFlushMs = MutableStateFlow<Float?>(null)
    val lastBargeInFlushMs: StateFlow<Float?> = _lastBargeInFlushMs.asStateFlow()

    /** Monotonic timestamp (`SystemClock.elapsedRealtime()`) when natural TTS playback ended; 0L after explicit barge-in flush. */
    @Volatile
    var lastPlaybackEndedAtMs: Long = 0L
        private set

    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private val queueController = PlaybackQueueController()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var writerJob: Job? = null
    private var idleResetJob: Job? = null
    private var previousAudioMode: Int? = null

    private val audioManager: AudioManager? by lazy {
        context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Route audio to the built-in bottom loudspeaker unless a wired or Bluetooth headset is active.
     */
    private fun configureLoudspeaker(track: AudioTrack) {
        val am = audioManager ?: return
        runCatching {
            val outputDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasExternalHeadset = outputDevices.any { dev ->
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            if (hasExternalHeadset) {
                _isLoudspeakerActive.value = false
                return
            }

            if (previousAudioMode == null) {
                previousAudioMode = am.mode
            }
            // Keep MODE_NORMAL so USAGE_MEDIA plays at full bottom-speaker power instead of earpiece cap
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL) {
                am.mode = AudioManager.MODE_NORMAL
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commSpeaker = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (commSpeaker != null) {
                    am.setCommunicationDevice(commSpeaker)
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }

            val builtinSpeaker = outputDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (builtinSpeaker != null) {
                track.setPreferredDevice(builtinSpeaker)
            }
            _isLoudspeakerActive.value = true
        }
    }

    private fun restoreAudioRouting() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            previousAudioMode?.let { prev ->
                am.mode = prev
                previousAudioMode = null
            }
        }
    }

    /**
     * Initialize AudioTrack and start background streaming writer loop. Safe to call repeatedly.
     */
    fun ensureStarted(): Boolean {
        synchronized(trackLock) {
            if (audioTrack != null) return true

            val minBuf = AudioTrack.getMinBufferSize(
                PcmAudioConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                return false
            }

            val bufferSize = max(minBuf * 2, PcmAudioConfig.FRAME_BYTES * 8)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmAudioConfig.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return false
            }

            configureLoudspeaker(track)
            runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
            track.play()
            audioTrack = track
        }

        if (writerJob == null || writerJob?.isActive != true) {
            writerJob = scope.launch {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                drainLoop()
            }
        }
        return true
    }

    /**
     * Enqueue a binary 16kHz 16-bit LE mono PCM TTS chunk for immediate streaming playback.
     */
    fun enqueuePcm(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        if (!ensureStarted()) return
        queueController.enqueue(pcmBytes)
        wakeSignal.trySend(Unit)
    }

    /**
     * Immediately silence TTS playback on barge-in (`barge_in_clear` or local user interruption).
     * Purges queued PCM chunks, pauses/flushes/re-arms AudioTrack, and records exact cutoff ms.
     */
    fun flushImmediately(): Float {
        val startNs = System.nanoTime()
        idleResetJob?.cancel()
        queueController.flushAll()

        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                    track.play()
                }
            }
        }

        _isPlaying.value = false
        // Explicit user barge-in: clear echo holdoff so the ASHA worker's speech captures immediately.
        lastPlaybackEndedAtMs = 0L
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        _lastBargeInFlushMs.value = elapsedMs
        return elapsedMs
    }

    fun release() {
        idleResetJob?.cancel()
        queueController.flushAll()
        writerJob?.cancel()
        writerJob = null
        wakeSignal.close()
        synchronized(trackLock) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null
        }
        restoreAudioRouting()
        _isPlaying.value = false
        scope.cancel()
    }

    private suspend fun drainLoop() {
        for (signal in wakeSignal) {
            if (!scope.isActive) break
            while (scope.isActive) {
                val rawChunk = queueController.pollActiveChunk() ?: break
                val chunk = PcmGainBooster.boost16BitLe(rawChunk, outputGain)
                val genBeforeWrite = queueController.currentGeneration
                _isPlaying.value = true
                idleResetJob?.cancel()

                synchronized(trackLock) {
                    val track = audioTrack
                    if (track != null && genBeforeWrite == queueController.currentGeneration) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            configureLoudspeaker(track)
                            runCatching { track.play() }
                        }
                        runCatching {
                            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                }
            }
            scheduleIdleReset()
        }
    }

    private fun scheduleIdleReset() {
        idleResetJob?.cancel()
        idleResetJob = scope.launch {
            delay(IDLE_TAIL_MS)
            if (queueController.size() == 0) {
                _isPlaying.value = false
                lastPlaybackEndedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    companion object {
        private const val IDLE_TAIL_MS = 280L
    }
}
