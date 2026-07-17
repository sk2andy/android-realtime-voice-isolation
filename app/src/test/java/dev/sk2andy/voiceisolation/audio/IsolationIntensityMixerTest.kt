package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertArrayEquals
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
}
