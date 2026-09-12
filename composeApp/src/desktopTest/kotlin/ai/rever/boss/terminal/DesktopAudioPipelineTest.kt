package ai.rever.boss.terminal

import ai.rever.boss.platform.DesktopAudioPipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopAudioPipelineTest {
    @Test
    fun `calculateRmsEnergy correctly calculates silence energy`() {
        val pipeline = DesktopAudioPipeline(sampleRate = 24000)
        val silence = ByteArray(960) // 960 bytes = 480 samples of zero
        val energy = pipeline.calculateRmsEnergy(silence, silence.size)
        assertEquals(0f, energy, 0.0001f)
    }

    @Test
    fun `calculateRmsEnergy correctly calculates sine wave energy`() {
        val pipeline = DesktopAudioPipeline(sampleRate = 24000)
        val sampleCount = 480
        val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Generate full-scale sine wave
        for (i in 0 until sampleCount) {
            val sinVal = Math.sin(2.0 * Math.PI * i / 32.0)
            val sample = (sinVal * 32767.0).toInt().toShort()
            buffer.putShort(sample)
        }

        val bytes = buffer.array()
        val energy = pipeline.calculateRmsEnergy(bytes, bytes.size)
        // RMS of full scale sine wave is ~1/sqrt(2) ≈ 0.707
        assertTrue(energy in 0.65f..0.75f, "Expected energy near 0.707, got $energy")
    }

    @Test
    fun `drainAndFlush clears playback queue immediately`() {
        val pipeline = DesktopAudioPipeline(sampleRate = 24000)
        val dummyAudio = ByteArray(1920) { 1 }

        pipeline.enqueuePlayback(dummyAudio)
        pipeline.enqueuePlayback(dummyAudio)

        // Drain & flush for barge-in
        pipeline.drainAndFlush()

        assertEquals(0f, pipeline.outputLevel.value, "Output level should reset to 0 after drain")
        pipeline.stop()
    }
}
