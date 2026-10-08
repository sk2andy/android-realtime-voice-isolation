package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolationIntensityMixerTest {
    @Test
    fun `zero intensity returns latency aligned original signal`() {
        val mixer = IsolationIntensityMixer(frameSize = 2)
        val output = FloatArray(2)

        mixer.mix(floatArrayOf(0.25f, -0.5f), floatArrayOf(0.1f, 0.1f), output, intensity = 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), output, 0f)

        mixer.mix(floatArrayOf(0.75f, 0.5f), floatArrayOf(0.2f, 0.2f), output, intensity = 0f)
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), output, 0f)
    }

    @Test
    fun `full intensity compensates isolated voice level`() {
        val mixer = IsolationIntensityMixer(frameSize = 2)
        val output = FloatArray(2)

        mixer.mix(floatArrayOf(0.5f, -0.5f), floatArrayOf(0.2f, -0.2f), output, intensity = 1f)

        assertArrayEquals(floatArrayOf(0.26f, -0.26f), output, 0.0001f)
    }

    @Test
    fun `intensity changes ramp across one frame`() {
        val mixer = IsolationIntensityMixer(frameSize = 4)
        val output = FloatArray(4)

        mixer.mix(FloatArray(4) { 0.4f }, FloatArray(4), output, intensity = 0f)
        mixer.mix(FloatArray(4), FloatArray(4) { 0.2f }, output, intensity = 1f)

        assertArrayEquals(floatArrayOf(0.365f, 0.33f, 0.295f, 0.26f), output, 0.0001f)
    }

    @Test
    fun `mixed signal is soft limited without frame wide gain changes`() {
        val mixer = IsolationIntensityMixer(frameSize = 2)
        val output = FloatArray(2)

        mixer.mix(floatArrayOf(0f, 0f), floatArrayOf(1f, -0.5f), output, intensity = 1f)

        assertArrayEquals(floatArrayOf(0.9999f, -0.65f), output, 0.0001f)
    }

    @Test
    fun `independent voice boost works without background reduction`() {
        val mixer = IsolationIntensityMixer(frameSize = 2)
        val output = FloatArray(2)
        val settings = AudioMixSettings(backgroundReduction = 0f, voiceGain = 1.5f)
        mixer.mix(floatArrayOf(0.4f, -0.4f), FloatArray(2), output, settings)
        mixer.mix(FloatArray(2), floatArrayOf(0.2f, -0.2f), output, settings)

        assertArrayEquals(floatArrayOf(0.5f, -0.5f), output, 0.00001f)
    }

    @Test
    fun `background reduction does not reduce voice with unity gain`() {
        val mixer = IsolationIntensityMixer(frameSize = 2)
        val output = FloatArray(2)
        val settings = AudioMixSettings(backgroundReduction = 0.75f, voiceGain = 1f)
        mixer.mix(floatArrayOf(0.4f, -0.4f), FloatArray(2), output, settings)
        mixer.mix(FloatArray(2), floatArrayOf(0.2f, -0.2f), output, settings)

        assertArrayEquals(floatArrayOf(0.25f, -0.25f), output, 0.00001f)
    }

    @Test
    fun `legacy migration preserves old mix during intensity changes`() {
        val legacy = IsolationIntensityMixer(frameSize = 4)
        val migrated = IsolationIntensityMixer(frameSize = 4)
        val legacyOutput = FloatArray(4)
        val migratedOutput = FloatArray(4)
        for (intensity in floatArrayOf(0.65f, 0f, 0.5f, 1f, 0.3f)) {
            val dry = floatArrayOf(0.5f, -0.2f, 0.1f, -0.4f)
            val isolated = floatArrayOf(0.2f, -0.1f, 0.05f, -0.25f)
            legacy.mix(dry, isolated, legacyOutput, intensity)
            migrated.mix(dry, isolated, migratedOutput, AudioMixSettings.fromIntensity(intensity))
            assertArrayEquals(legacyOutput, migratedOutput, 0.00001f)
        }
    }

    @Test
    fun `independent gains ramp per sample and peaks remain limited`() {
        val mixer = IsolationIntensityMixer(frameSize = 4)
        val output = FloatArray(4)
        mixer.mix(FloatArray(4) { 0.4f }, FloatArray(4), output, AudioMixSettings(0f, 1f))
        mixer.mix(FloatArray(4), FloatArray(4) { 0.2f }, output, AudioMixSettings(1f, 2f))
        assertArrayEquals(FloatArray(4) { 0.4f }, output, 0.00001f)

        mixer.mix(FloatArray(4), floatArrayOf(1f, -1f, 0.1f, -0.1f), output, AudioMixSettings(1f, 2f))
        assertTrue(output[0] < 1f)
        assertTrue(output[1] > -1f)
        assertEquals(0.2f, output[2], 0.00001f)
        assertEquals(-0.2f, output[3], 0.00001f)
    }

    @Test
    fun `automatic mode softens background then attacks and releases gradually`() {
        val mixer = IsolationIntensityMixer(frameSize = 256)
        val dry = FloatArray(256) { 0.4f }
        val output = FloatArray(256)
        val settings = AudioMixSettings(1f, 1.5f, true)
        mixer.mix(dry, FloatArray(256), output, settings)
        mixer.mix(dry, FloatArray(256), output, settings)
        assertFalse(mixer.speechDetected)
        assertEquals(0.2f, mixer.effectiveReduction, 0f)
        assertArrayEquals(FloatArray(256) { 0.32f }, output, 0.00001f)

        mixer.mix(dry, FloatArray(256) { 0.25f }, output, settings)
        assertFalse(mixer.speechDetected)
        mixer.mix(dry, FloatArray(256) { 0.25f }, output, settings)
        assertTrue(mixer.speechDetected)
        assertEquals(0.52f, mixer.effectiveReduction, 0.00001f)
        repeat(8) { mixer.mix(dry, FloatArray(256) { 0.25f }, output, settings) }
        val speechReduction = mixer.effectiveReduction
        repeat(SpeechActivityDetector.HANGOVER_FRAMES) {
            mixer.mix(dry, FloatArray(256), output, settings)
            assertTrue(mixer.speechDetected)
        }
        mixer.mix(dry, FloatArray(256), output, settings)
        assertFalse(mixer.speechDetected)
        assertTrue(mixer.effectiveReduction > 0.2f)
        assertTrue(mixer.effectiveReduction < speechReduction + 0.01f)
        repeat(100) { mixer.mix(dry, FloatArray(256), output, settings) }
        assertEquals(0.2f, mixer.effectiveReduction, 0.002f)
    }
}
