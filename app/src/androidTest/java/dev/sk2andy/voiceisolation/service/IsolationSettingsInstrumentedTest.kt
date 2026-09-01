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
