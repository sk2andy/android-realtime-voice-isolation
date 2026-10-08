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
    fun `voice gain is finite and clamped`() {
        assertEquals(1f, IsolationSettings.normalizeVoiceGain(-1f))
        assertEquals(1.7f, IsolationSettings.normalizeVoiceGain(1.7f))
        assertEquals(2f, IsolationSettings.normalizeVoiceGain(3f))
        assertEquals(1f, IsolationSettings.normalizeVoiceGain(Float.NaN))
        assertEquals(1f, IsolationSettings.normalizeVoiceGain(Float.POSITIVE_INFINITY))
        assertEquals(1f, IsolationSettings.normalizeVoiceGain(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `legacy intensity preserves original voice boost`() {
        assertEquals(1f, IsolationSettings.legacyVoiceGain(0f))
        assertEquals(1.195f, IsolationSettings.legacyVoiceGain(0.65f))
        assertEquals(1.3f, IsolationSettings.legacyVoiceGain(1f))
        assertEquals(1f, IsolationSettings.legacyVoiceGain(-1f))
        assertEquals(1.3f, IsolationSettings.legacyVoiceGain(2f))
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
            voiceGain = 1.8f,
            autoMode = true,
        )

        assertEquals(
            preset,
            IsolationSettings.decodeUserPreset(IsolationSettings.encodeUserPreset(preset)),
        )
    }

    @Test
    fun `legacy user presets decode with original voice boost and manual mode`() {
        assertEquals(
            UserIsolationPreset("id", "name", 0.5f, voiceGain = 1.15f, autoMode = false),
            IsolationSettings.decodeUserPreset("id|bmFtZQ|0.5"),
        )
    }

    @Test
    fun `stored user preset controls are normalized`() {
        assertEquals(
            UserIsolationPreset("id", "name", 1f, voiceGain = 2f, autoMode = true),
            IsolationSettings.decodeUserPreset("id|bmFtZQ|2|3|true"),
        )
        assertEquals(
            UserIsolationPreset("id", "name", 0f, voiceGain = 1f, autoMode = false),
            IsolationSettings.decodeUserPreset("id|bmFtZQ|-2|NaN|false"),
        )
    }

    @Test
    fun `invalid user preset encoding is ignored`() {
        assertEquals(null, IsolationSettings.decodeUserPreset("broken"))
        assertEquals(null, IsolationSettings.decodeUserPreset("|bmFtZQ|0.5"))
        assertEquals(null, IsolationSettings.decodeUserPreset("id|not base64!|0.5"))
        assertEquals(null, IsolationSettings.decodeUserPreset("id|bmFtZQ|0.5|1.5"))
        assertEquals(null, IsolationSettings.decodeUserPreset("id|bmFtZQ|0.5|1.5|maybe"))
        assertEquals(null, IsolationSettings.decodeUserPreset("id|bmFtZQ|0.5|1.5|true|extra"))
    }

    @Test
    fun `user preset names are trimmed and bounded`() {
        assertEquals("Film", IsolationSettings.normalizeUserPresetName("  Film  "))
        assertEquals(30, IsolationSettings.normalizeUserPresetName("x".repeat(40)).length)
    }
}
