package dev.sk2andy.voiceisolation.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolationSettingsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearSettingsBeforeTest() = clearSettings()

    @After
    fun clearSettingsAfterTest() = clearSettings()

    @Test
    fun customIntensityAndLastPresetReloadFromStorage() {
        IsolationSettings.setCustomIntensity(context, 0.77f)

        val reloaded = IsolationSettings.snapshot(context)

        assertEquals(IsolationPreset.Custom, reloaded.preset)
        assertNull(reloaded.selectedUserPresetId)
        assertEquals(0.77f, reloaded.intensity)
        assertEquals(0.77f, reloaded.customIntensity)
    }

    @Test
    fun independentControlsPersistWithoutChangingOtherSettings() {
        IsolationSettings.setCustomMix(context, 0.77f, 1.7f, true)
        IsolationSettings.setCustomIntensity(context, 0.3f)
        val changedIntensity = IsolationSettings.snapshot(context)
        assertEquals(0.3f, changedIntensity.intensity)
        assertEquals(1.7f, changedIntensity.voiceGain)
        assertEquals(true, changedIntensity.autoMode)

        IsolationSettings.setVoiceGain(context, 1.9f)
        IsolationSettings.setAutoMode(context, false)
        val reloaded = IsolationSettings.snapshot(context)
        assertEquals(IsolationPreset.Custom, reloaded.preset)
        assertEquals(0.3f, reloaded.intensity)
        assertEquals(1.9f, reloaded.voiceGain)
        assertEquals(false, reloaded.autoMode)
    }

    @Test
    fun legacyCustomIntensityMigratesWithoutChangingSound() {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit()
            .putString("preset", "custom")
            .putFloat("custom_intensity", 0.8f)
            .commit()

        val reloaded = IsolationSettings.snapshot(context)
        assertEquals(0.8f, reloaded.intensity)
        assertEquals(IsolationSettings.legacyVoiceGain(0.8f), reloaded.voiceGain)
        assertEquals(false, reloaded.autoMode)
    }

    @Test
    fun builtInPresetRestoresOriginalSoundAndCustomKeepsIndependentControls() {
        IsolationSettings.setCustomMix(context, 0.3f, 1.9f, true)
        val builtIn = IsolationSettings.setPreset(context, IsolationPreset.Podcast)
        assertEquals(0.85f, builtIn.intensity)
        assertEquals(IsolationSettings.legacyVoiceGain(0.85f), builtIn.voiceGain)
        assertEquals(false, builtIn.autoMode)

        val custom = IsolationSettings.setPreset(context, IsolationPreset.Custom)
        assertEquals(0.3f, custom.intensity)
        assertEquals(1.9f, custom.voiceGain)
        assertEquals(true, custom.autoMode)
    }

    @Test
    fun userPresetRoundTripAndDeletionPreserveFullTuning() {
        val saved = IsolationSettings.saveUserPreset(context, "Night", 0.4f, 1.8f, true)
        val id = checkNotNull(saved.selectedUserPresetId)
        IsolationSettings.setPreset(context, IsolationPreset.Film)

        val selected = IsolationSettings.setUserPreset(context, id)
        assertEquals(0.4f, selected.intensity)
        assertEquals(1.8f, selected.voiceGain)
        assertEquals(true, selected.autoMode)

        val deleted = IsolationSettings.deleteUserPreset(context, id)
        assertEquals(IsolationPreset.Custom, deleted.preset)
        assertNull(deleted.selectedUserPresetId)
        val reloaded = IsolationSettings.snapshot(context)
        assertEquals(0.4f, reloaded.intensity)
        assertEquals(1.8f, reloaded.voiceGain)
        assertEquals(true, reloaded.autoMode)
    }

    @Test
    fun changingOneControlDetachesPresetButPreservesOtherTuning() {
        val saved = IsolationSettings.saveUserPreset(context, "Night", 0.4f, 1.8f, true)
        val id = checkNotNull(saved.selectedUserPresetId)
        val changedIntensity = IsolationSettings.setCustomIntensity(context, 0.2f)
        assertNull(changedIntensity.selectedUserPresetId)
        assertEquals(1.8f, changedIntensity.voiceGain)
        assertEquals(true, changedIntensity.autoMode)

        IsolationSettings.setUserPreset(context, id)
        val changedGain = IsolationSettings.setVoiceGain(context, 1.5f)
        assertNull(changedGain.selectedUserPresetId)
        assertEquals(0.4f, changedGain.intensity)
        assertEquals(true, changedGain.autoMode)

        IsolationSettings.setUserPreset(context, id)
        val changedMode = IsolationSettings.setAutoMode(context, false)
        assertNull(changedMode.selectedUserPresetId)
        assertEquals(0.4f, changedMode.intensity)
        assertEquals(1.8f, changedMode.voiceGain)
    }

    @Test
    fun optimisticStateChangesDetachPresetAndPreserveIndependentControls() {
        val saved = IsolationSettings.saveUserPreset(context, "Night", 0.4f, 1.8f, true)
        IsolationStateStore.updateSettings(saved)
        IsolationStateStore.updatePreset(IsolationPreset.Custom, 0.2f)
        val intensityState = IsolationStateStore.state.value
        assertNull(intensityState.selectedUserPresetId)
        assertEquals(1.8f, intensityState.voiceGain)
        assertEquals(true, intensityState.autoMode)

        IsolationStateStore.updateSettings(saved)
        IsolationStateStore.updateVoiceGain(1.5f)
        val gainState = IsolationStateStore.state.value
        assertNull(gainState.selectedUserPresetId)
        assertEquals(0.4f, gainState.intensity)
        assertEquals(0.4f, gainState.customIntensity)
        assertEquals(true, gainState.autoMode)

        IsolationStateStore.updateSettings(saved)
        IsolationStateStore.updateAutoMode(false)
        val modeState = IsolationStateStore.state.value
        assertNull(modeState.selectedUserPresetId)
        assertEquals(0.4f, modeState.intensity)
        assertEquals(1.8f, modeState.voiceGain)
    }

    @Test
    fun sameUserPresetNameOverwritesCaseInsensitively() {
        val first = IsolationSettings.saveUserPreset(context, "Cinema", 0.4f)
        val firstId = first.selectedUserPresetId

        val overwritten = IsolationSettings.saveUserPreset(context, "cinema", 0.8f)

        assertEquals(1, overwritten.userPresets.size)
        assertEquals(firstId, overwritten.selectedUserPresetId)
        assertEquals(0.8f, overwritten.intensity)
        assertEquals("cinema", overwritten.userPresets.single().name)
    }

    @Test
    fun selectAndDeletePresetsKeepConsistentFallback() {
        val first = IsolationSettings.saveUserPreset(context, "Quiet", 0.4f)
        val firstId = checkNotNull(first.selectedUserPresetId)
        val second = IsolationSettings.saveUserPreset(context, "Strong", 0.8f)
        val secondId = checkNotNull(second.selectedUserPresetId)

        val selected = IsolationSettings.setUserPreset(context, firstId)
        assertEquals(firstId, selected.selectedUserPresetId)
        assertEquals(0.4f, selected.intensity)

        val afterSelectedDelete = IsolationSettings.deleteUserPreset(context, firstId)
        assertEquals(IsolationPreset.Custom, afterSelectedDelete.preset)
        assertNull(afterSelectedDelete.selectedUserPresetId)
        assertEquals(0.4f, afterSelectedDelete.intensity)
        assertEquals(listOf(secondId), afterSelectedDelete.userPresets.map { it.id })

        val afterUnselectedDelete = IsolationSettings.deleteUserPreset(context, secondId)
        assertEquals(IsolationPreset.Custom, afterUnselectedDelete.preset)
        assertEquals(0.4f, afterUnselectedDelete.intensity)
        assertEquals(emptyList<UserIsolationPreset>(), afterUnselectedDelete.userPresets)
    }

    private fun clearSettings() {
        context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    companion object {
        private const val SETTINGS_NAME = "voice_isolation_settings"
    }
}
