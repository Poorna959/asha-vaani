package com.ashavaani.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmAudioEngineTest {

    private fun generateSinePcm16Le(sampleCount: Int, amplitude: Int, freqHz: Double = 440.0): ByteArray {
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val angle = 2.0 * PI * freqHz * i / PcmAudioConfig.SAMPLE_RATE_HZ
            val sample = (sin(angle) * amplitude).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `pcm audio config matches 16kHz 20ms 640-byte contract`() {
        assertEquals(16_000, PcmAudioConfig.SAMPLE_RATE_HZ)
        assertEquals(320, PcmAudioConfig.FRAME_SAMPLES)
        assertEquals(640, PcmAudioConfig.FRAME_BYTES)
        assertEquals(32_000, PcmAudioConfig.BYTES_PER_SECOND)
    }

    @Test
    fun `rms metering distinguishes silence, soft speech, and loud speech`() {
        val silence = ByteArray(PcmAudioConfig.FRAME_BYTES)
        val softSpeech = generateSinePcm16Le(PcmAudioConfig.FRAME_SAMPLES, amplitude = 1_500)
        val loudSpeech = generateSinePcm16Le(PcmAudioConfig.FRAME_SAMPLES, amplitude = 18_000)

        val rmsSilence = PcmFrameProcessor.computeNormalizedRms(silence)
        val rmsSoft = PcmFrameProcessor.computeNormalizedRms(softSpeech)
        val rmsLoud = PcmFrameProcessor.computeNormalizedRms(loudSpeech)

        assertEquals(0f, rmsSilence, 0.0001f)
        assertTrue("Expected soft speech RMS in (0.2, 0.7), got $rmsSoft", rmsSoft in 0.2f..0.7f)
        assertTrue("Expected loud speech RMS > soft speech ($rmsLoud vs $rmsSoft)", rmsLoud > rmsSoft)
        assertTrue("Expected loud speech RMS near ceiling, got $rmsLoud", rmsLoud > 0.85f)
    }

    @Test
    fun `rms smoothing attacks faster than it releases`() {
        val attacked = PcmFrameProcessor.smoothRms(previous = 0f, current = 1f)
        val released = PcmFrameProcessor.smoothRms(previous = 1f, current = 0f)

        assertEquals(0.55f, attacked, 0.001f)
        assertEquals(0.78f, released, 0.001f)
        assertTrue(attacked > (1f - released))
    }

    @Test
    fun `frame chunker aligns arbitrary read sizes into exact 640-byte frames without byte loss`() {
        val chunker = PcmFrameChunker(PcmAudioConfig.FRAME_BYTES)
        val emitted = mutableListOf<ByteArray>()

        // Push 1000 bytes (1 full 640-byte frame + 360 remainder)
        val firstInput = ByteArray(1000) { (it % 127).toByte() }
        chunker.push(firstInput) { emitted.add(it) }
        assertEquals(1, emitted.size)
        assertEquals(640, emitted[0].size)
        assertEquals(360, chunker.bufferedBytes)

        // Push 280 bytes (completes 2nd 640-byte frame cleanly)
        val secondInput = ByteArray(280) { ((it + 1000) % 127).toByte() }
        chunker.push(secondInput) { emitted.add(it) }
        assertEquals(2, emitted.size)
        assertEquals(0, chunker.bufferedBytes)

        // Verify byte-for-byte continuity across the split boundary
        val expectedSecond = ByteArray(640) { ((it + 640) % 127).toByte() }
        assertArrayEquals(expectedSecond, emitted[1])
    }

    @Test
    fun `frame chunker flushZeroPadded pads trailing partial frame cleanly`() {
        val chunker = PcmFrameChunker(640)
        val emitted = mutableListOf<ByteArray>()
        chunker.push(ByteArray(100) { 42 }) { emitted.add(it) }
        assertEquals(0, emitted.size)

        chunker.flushZeroPadded { emitted.add(it) }
        assertEquals(1, emitted.size)
        assertEquals(640, emitted[0].size)
        assertEquals(42.toByte(), emitted[0][99])
        assertEquals(0.toByte(), emitted[0][100])
        assertEquals(0, chunker.bufferedBytes)
    }

    @Test
    fun `playback queue controller evicts all stale chunks instantly on barge-in flush`() {
        val controller = PlaybackQueueController()
        val gen0 = controller.enqueue(byteArrayOf(1, 2, 3, 4))
        controller.enqueue(byteArrayOf(5, 6, 7, 8))
        assertEquals(0L, gen0)
        assertEquals(2, controller.size())

        val dropped = controller.flushAll()
        assertEquals(2, dropped)
        assertEquals(0, controller.size())
        assertEquals(1L, controller.currentGeneration)
        assertNull(controller.pollActiveChunk())

        // New post-barge-in audio plays normally on the new generation
        val gen1 = controller.enqueue(byteArrayOf(9, 10, 11, 12))
        assertEquals(1L, gen1)
        val next = controller.pollActiveChunk()
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), next)
        assertNull(controller.pollActiveChunk())
    }

    @Test
    fun `P3-06 outdoor pcm gain booster amplifies speech safely without int16 wraparound`() {
        val silence = ByteArray(PcmAudioConfig.FRAME_BYTES)
        assertArrayEquals(silence, PcmGainBooster.boost16BitLe(silence, gain = 1.85f))

        // Mid-level positive & negative samples scale cleanly by ~1.85x
        val midSamples = byteArrayOf(
            (1000 and 0xFF).toByte(),
            ((1000 shr 8) and 0xFF).toByte(),
            ((-1000) and 0xFF).toByte(),
            (((-1000) shr 8) and 0xFF).toByte(),
            // Peak sample near Short.MAX_VALUE must clamp cleanly to 32767 (no negative overflow!)
            (25000 and 0xFF).toByte(),
            ((25000 shr 8) and 0xFF).toByte(),
            ((-25000) and 0xFF).toByte(),
            (((-25000) shr 8) and 0xFF).toByte(),
        )
        val boosted = PcmGainBooster.boost16BitLe(midSamples, gain = 1.85f)
        fun readShort(buf: ByteArray, idx: Int): Int =
            (buf[idx * 2 + 1].toInt() shl 8) or (buf[idx * 2].toInt() and 0xFF)

        assertEquals(1850, readShort(boosted, 0))
        assertEquals(-1850, readShort(boosted, 1))
        assertEquals(Short.MAX_VALUE.toInt(), readShort(boosted, 2))
        assertEquals(Short.MIN_VALUE.toInt(), readShort(boosted, 3))
    }

    @Test
    fun `P3-06 explicit English language pill maps en-IN cleanly`() {
        val en = com.ashavaani.app.model.SupportedLanguage.fromCode("en-IN")
        assertEquals(com.ashavaani.app.model.SupportedLanguage.ENGLISH, en)
        assertEquals("English", en.nativeLabel)
        assertEquals("en-IN", en.code)
    }
}
