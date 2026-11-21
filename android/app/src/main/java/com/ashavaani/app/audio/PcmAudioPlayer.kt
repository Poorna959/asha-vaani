package com.ashavaani.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
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
 * Low-latency streaming 16kHz 16-bit mono PCM AudioTrack player for Gnani Timbre v3.0 TTS chunks.
 *
 * Uses `USAGE_VOICE_COMMUNICATION` so hardware Acoustic Echo Cancellation (AEC) uses this
 * playback stream as its echo reference, and supports sub-150ms barge-in via [flushImmediately].
 */
class PcmAudioPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _lastBargeInFlushMs = MutableStateFlow<Float?>(null)
    val lastBargeInFlushMs: StateFlow<Float?> = _lastBargeInFlushMs.asStateFlow()

    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private val queueController = PlaybackQueueController()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var writerJob: Job? = null
    private var idleResetJob: Job? = null

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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        _isPlaying.value = false
        scope.cancel()
    }

    private suspend fun drainLoop() {
        for (signal in wakeSignal) {
            if (!scope.isActive) break
            while (scope.isActive) {
                val chunk = queueController.pollActiveChunk() ?: break
                val genBeforeWrite = queueController.currentGeneration
                _isPlaying.value = true
                idleResetJob?.cancel()

                synchronized(trackLock) {
                    val track = audioTrack
                    if (track != null && genBeforeWrite == queueController.currentGeneration) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
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
            }
        }
    }

    companion object {
        private const val IDLE_TAIL_MS = 280L
    }
}
