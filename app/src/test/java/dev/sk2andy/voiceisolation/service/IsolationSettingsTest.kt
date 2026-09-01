package dev.sk2andy.voiceisolation.service

import org.junit.Assert.assertEquals
import org.junit.Test

class IsolationSettingsTest {
    @Test
    fun `preset storage values round trip`() {
        IsolationPreset.entries.forEach { preset ->
            assertEquals(preset, IsolationPreset.fromStorageValue(preset.storageValue))
        }
    }

    @Test
    fun `unknown preset falls back to film`() {
        assertEquals(IsolationPreset.Film, IsolationPreset.fromStorageValue("missing"))
        assertEquals(IsolationPreset.Film, IsolationPreset.fromStorageValue(null))
    }

    @Test
    fun `intensity is finite and clamped`() {
        assertEquals(0f, IsolationSettings.normalizeIntensity(-1f))
        assertEquals(0.75f, IsolationSettings.normalizeIntensity(0.75f))
        assertEquals(1f, IsolationSettings.normalizeIntensity(2f))
        assertEquals(
            IsolationSettings.DEFAULT_INTENSITY,
            IsolationSettings.normalizeIntensity(Float.NaN),
        )
        assertEquals(
            IsolationSettings.DEFAULT_INTENSITY,
            IsolationSettings.normalizeIntensity(Float.POSITIVE_INFINITY),
        )
    }

    @Test
    fun `built in presets keep expected strengths`() {
        assertEquals(0.85f, IsolationPreset.Podcast.intensity)
        assertEquals(0.65f, IsolationPreset.Film.intensity)
        assertEquals(0.50f, IsolationPreset.Gaming.intensity)
        assertEquals(1f, IsolationPreset.Maximum.intensity)
        assertEquals(null, IsolationPreset.Custom.intensity)
    }

    @Test
    fun `user preset encoding round trips unicode and delimiters`() {
        val preset = UserIsolationPreset(
            id = "preset-id",
            name = "Film | Nacht 🎬",
            intensity = 0.72f,
        )

        assertEquals(
            preset,
            IsolationSettings.decodeUserPreset(IsolationSettings.encodeUserPreset(preset)),
        )
    }

    @Test
    fun `invalid user preset encoding is ignored`() {
        assertEquals(null, IsolationSettings.decodeUserPreset("broken"))
        assertEquals(null, IsolationSettings.decodeUserPreset("|bmFtZQ|0.5"))
        assertEquals(null, IsolationSettings.decodeUserPreset("id|not base64!|0.5"))
    }

    @Test
    fun `user preset names are trimmed and bounded`() {
        assertEquals("Film", IsolationSettings.normalizeUserPresetName("  Film  "))
        assertEquals(30, IsolationSettings.normalizeUserPresetName("x".repeat(40)).length)
    }
}
