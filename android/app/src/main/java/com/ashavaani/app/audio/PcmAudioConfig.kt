package com.ashavaani.app.audio

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared 16kHz 16-bit signed little-endian mono PCM audio parameters
 * aligned with Phase P1 FastAPI `/ws/session` (Prisma v3.0 STT & Timbre v3.0 TTS).
 */
object PcmAudioConfig {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1
    const val BYTES_PER_SAMPLE: Int = 2
    const val FRAME_DURATION_MS: Int = 20
    const val FRAME_SAMPLES: Int = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000 // 320 samples
    const val FRAME_BYTES: Int = FRAME_SAMPLES * BYTES_PER_SAMPLE // 640 bytes per 20ms frame
    const val BYTES_PER_SECOND: Int = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE // 32,000 B/s
}

/**
 * Runtime hardware DSP attachment status surfaced in the Settings & Judge Telemetry sheet.
 */
data class AudioDspCapabilities(
    val aecAvailable: Boolean = false,
    val aecActive: Boolean = false,
    val nsAvailable: Boolean = false,
    val nsActive: Boolean = false,
    val agcAvailable: Boolean = false,
    val agcActive: Boolean = false,
)

/**
 * Pure-Kotlin PCM metering and 20ms frame-alignment utilities (JVM unit-testable without hardware).
 */
object PcmFrameProcessor {
    private const val MAX_INT16 = 32768.0
    private const val MIN_DB = -50.0
    private const val MAX_DB = -6.0

    /**
     * Compute linear RMS in [0.0, 1.0] from 16-bit signed little-endian PCM bytes.
     */
    fun computeLinearRms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Double {
        val usableBytes = min(length, pcmBytes.size)
        val sampleCount = usableBytes / 2
        if (sampleCount <= 0) return 0.0

        var sumSquares = 0.0
        var i = 0
        while (i + 1 < sampleCount * 2) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low // signed 16-bit little-endian
            val normalized = sample / MAX_INT16
            sumSquares += normalized * normalized
            i += 2
        }
        return sqrt(sumSquares / sampleCount)
    }

    /**
     * Convert raw 16-bit LE PCM bytes into a perceptually scaled [0f, 1f] level for UI RMS rings.
     * Maps speech energy roughly from -50 dBFS (silence) to -6 dBFS (loud speech).
     */
    fun computeNormalizedRms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Float {
        val linear = computeLinearRms(pcmBytes, length)
        if (linear <= 1e-6) return 0f
        val db = 20.0 * (ln(linear) / ln(10.0))
        val scaled = (db - MIN_DB) / (MAX_DB - MIN_DB)
        return scaled.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Asymmetric exponential smoothing (fast attack, smooth release) for fluid 60fps RMS pulse rings.
     */
    fun smoothRms(
        previous: Float,
        current: Float,
        attackAlpha: Float = 0.55f,
        releaseAlpha: Float = 0.22f,
    ): Float {
        val clampedPrev = previous.coerceIn(0f, 1f)
        val clampedCur = current.coerceIn(0f, 1f)
        val alpha = if (clampedCur >= clampedPrev) attackAlpha else releaseAlpha
        return (clampedPrev + alpha * (clampedCur - clampedPrev)).coerceIn(0f, 1f)
    }
}

/**
 * Re-chunks arbitrary AudioRecord read sizes into exact fixed-size frames (default 640 bytes / 20ms)
 * without dropping partial remainder bytes across read boundaries.
 */
class PcmFrameChunker(
    val frameBytes: Int = PcmAudioConfig.FRAME_BYTES,
) {
    init {
        require(frameBytes > 0 && frameBytes % 2 == 0) {
            "frameBytes must be a positive even number (16-bit PCM)"
        }
    }

    private val accumulator = ByteArray(frameBytes)
    private var filled = 0

    val bufferedBytes: Int get() = filled

    /**
     * Push raw PCM bytes and invoke [onFrame] for each complete [frameBytes] slice produced.
     */
    fun push(input: ByteArray, length: Int = input.size, onFrame: (ByteArray) -> Unit) {
        val total = min(length, input.size)
        var offset = 0
        while (offset < total) {
            val needed = frameBytes - filled
            val toCopy = min(needed, total - offset)
            System.arraycopy(input, offset, accumulator, filled, toCopy)
            filled += toCopy
            offset += toCopy

            if (filled == frameBytes) {
                onFrame(accumulator.copyOf())
                filled = 0
            }
        }
    }

    /**
     * Flush any trailing partial frame zero-padded to [frameBytes] (used only on clean stop if needed).
     */
    fun flushZeroPadded(onFrame: (ByteArray) -> Unit) {
        if (filled <= 0) return
        val padded = ByteArray(frameBytes)
        System.arraycopy(accumulator, 0, padded, 0, filled)
        filled = 0
        onFrame(padded)
    }

    fun reset() {
        filled = 0
    }
}

/**
 * Generation-stamped PCM queue controller enabling sub-millisecond barge-in eviction of stale
 * pre-interruption TTS chunks. Unit-tested directly on JVM.
 */
class PlaybackQueueController {
    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var pendingChunks: Int = 0

    private val lock = Any()
    private val queue = ArrayDeque<Pair<Long, ByteArray>>()

    val currentGeneration: Long get() = generation

    fun enqueue(pcmBytes: ByteArray): Long {
        if (pcmBytes.isEmpty()) return generation
        synchronized(lock) {
            val gen = generation
            queue.addLast(gen to pcmBytes)
            pendingChunks = queue.size
            return gen
        }
    }

    /**
     * Pop the next chunk matching the active generation, automatically discarding stale pre-barge-in items.
     */
    fun pollActiveChunk(): ByteArray? {
        synchronized(lock) {
            while (queue.isNotEmpty()) {
                val (gen, data) = queue.removeFirst()
                pendingChunks = queue.size
                if (gen == generation) {
                    return data
                }
            }
            pendingChunks = 0
            return null
        }
    }

    /**
     * Advance generation and immediately purge all buffered audio chunks for instant barge-in cutoff.
     */
    fun flushAll(): Int {
        synchronized(lock) {
            val dropped = queue.size
            generation += 1L
            queue.clear()
            pendingChunks = 0
            return dropped
        }
    }

    fun size(): Int = max(0, pendingChunks)
}

/**
 * Pure-Kotlin loudspeaker echo gate (JVM unit-testable without hardware).
 *
 * Because `PcmAudioPlayer` routes read-backs to the loud bottom speaker via `USAGE_MEDIA` + 1.85x
 * outdoor gain (outside Android's `VOICE_COMMUNICATION` AEC reference on many OEM devices), raw
 * speaker output can otherwise loop back into the mic and cause phantom transcripts or false
 * self-barge-in. This gate suppresses upstream mic frames ONLY while the built-in loudspeaker is
 * actively playing TTS plus a brief room-reverb tail ([holdoffMs]). External headsets remain
 * 100% full-duplex, and explicit tap-to-interrupt clears [lastPlaybackEndedAtMs] to 0L for
 * immediate 0ms capture resumption.
 */
class AcousticEchoGate(
    val holdoffMs: Long = DEFAULT_HOLDOFF_MS,
) {
    fun shouldSuppressMicFrame(
        isAgentSpeaking: Boolean,
        isLoudspeakerActive: Boolean,
        lastPlaybackEndedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isLoudspeakerActive) return false
        if (isAgentSpeaking) return true
        if (lastPlaybackEndedAtMs <= 0L) return false
        val elapsedSinceEnd = nowMs - lastPlaybackEndedAtMs
        return elapsedSinceEnd in 0L until holdoffMs
    }

    companion object {
        const val DEFAULT_HOLDOFF_MS: Long = 400L
    }
}
