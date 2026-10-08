package dev.sk2andy.voiceisolation.service

import android.content.Context
import dev.sk2andy.voiceisolation.audio.ProcessingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class IsolationPhase {
    Idle,
    Connecting,
    LoadingModel,
    Running,
    Stopping,
    Error,
}

internal data class IsolationUiState(
    val phase: IsolationPhase = IsolationPhase.Idle,
    val message: String = "",
    val intensity: Float = IsolationSettings.DEFAULT_INTENSITY,
    val customIntensity: Float = IsolationSettings.DEFAULT_INTENSITY,
    val preset: IsolationPreset = IsolationPreset.Film,
    val selectedUserPresetId: String? = null,
    val userPresets: List<UserIsolationPreset> = emptyList(),
    val autoOffMinutes: Int = IsolationSettings.DEFAULT_AUTO_OFF_MINUTES,
    val automationEnabled: Boolean = false,
    val automationToken: String = "",
    val stats: ProcessingStats? = null,
    val voiceGain: Float = IsolationSettings.legacyVoiceGain(intensity),
    val autoMode: Boolean = false,
)

internal object IsolationStateStore {
    private val mutableState = MutableStateFlow(IsolationUiState())
    val state: StateFlow<IsolationUiState> = mutableState.asStateFlow()

    fun updatePhase(phase: IsolationPhase, message: String) {
        mutableState.update { current ->
            current.copy(
                phase = phase,
                message = message,
                stats = if (phase == IsolationPhase.Idle || phase == IsolationPhase.Error) null else current.stats,
            )
        }
    }

    fun updateIntensity(intensity: Float) {
        mutableState.update { it.copy(intensity = IsolationSettings.normalizeIntensity(intensity)) }
    }

    fun updatePreset(preset: IsolationPreset, intensity: Float) {
        mutableState.update {
            val normalizedIntensity = IsolationSettings.normalizeIntensity(intensity)
            it.copy(
                intensity = normalizedIntensity,
                customIntensity = if (preset == IsolationPreset.Custom) {
                    normalizedIntensity
                } else {
                    it.customIntensity
                },
                preset = preset,
                selectedUserPresetId = null,
            )
        }
    }

    fun updateVoiceGain(voiceGain: Float) {
        mutableState.update {
            it.copy(
                voiceGain = IsolationSettings.normalizeVoiceGain(voiceGain),
                customIntensity = it.intensity,
                preset = IsolationPreset.Custom,
                selectedUserPresetId = null,
            )
        }
    }

    fun updateAutoMode(enabled: Boolean) {
        mutableState.update {
            it.copy(
                autoMode = enabled,
                customIntensity = it.intensity,
                preset = IsolationPreset.Custom,
                selectedUserPresetId = null,
            )
        }
    }

    fun updateAutoOffMinutes(minutes: Int) {
        mutableState.update {
            it.copy(autoOffMinutes = IsolationSettings.normalizeAutoOffMinutes(minutes))
        }
    }

    fun updateSettings(settings: IsolationSettingsSnapshot) {
        mutableState.update {
            it.copy(
                intensity = settings.intensity,
                customIntensity = settings.customIntensity,
                preset = settings.preset,
                selectedUserPresetId = settings.selectedUserPresetId,
                userPresets = settings.userPresets,
                autoOffMinutes = settings.autoOffMinutes,
                automationEnabled = settings.automationEnabled,
                automationToken = settings.automationToken,
                voiceGain = settings.voiceGain,
                autoMode = settings.autoMode,
            )
        }
    }

    fun updateStats(stats: ProcessingStats) {
        mutableState.update { it.copy(stats = stats) }
    }
}

internal fun IsolationUiState.presetLabel(context: Context): String =
    selectedUserPresetId
        ?.let { id -> userPresets.firstOrNull { it.id == id }?.name }
        ?: context.getString(preset.labelResource)
