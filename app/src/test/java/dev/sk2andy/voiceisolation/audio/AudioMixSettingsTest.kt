package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixSettingsTest {
    @Test
    fun `invalid controls use finite defaults and valid controls are bounded`() {
        val invalid = AudioMixSettings(Float.NaN, Float.POSITIVE_INFINITY, true).normalized()
        assertEquals(0.65f, invalid.backgroundReduction, 0f)
        assertEquals(1f, invalid.voiceGain, 0f)
        assertEquals(true, invalid.autoMode)

        val bounded = AudioMixSettings(-1f, 5f).normalized()
        assertEquals(0f, bounded.backgroundReduction, 0f)
        assertEquals(2f, bounded.voiceGain, 0f)
    }

    @Test
    fun `legacy intensity maps to original compensation level`() {
        val settings = AudioMixSettings.fromIntensity(0.65f)
        assertEquals(0.65f, settings.backgroundReduction, 0f)
        assertEquals(1.195f, settings.voiceGain, 0.00001f)
    }
}
