package dev.sk2andy.voiceisolation.service

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

data class IsolationUiState(
    val phase: IsolationPhase = IsolationPhase.Idle,
    val message: String = "",
    val volume: Float = 0.65f,
    val stats: ProcessingStats? = null,
)

object IsolationStateStore {
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

    fun updateVolume(volume: Float) {
        mutableState.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    fun updateStats(stats: ProcessingStats) {
        mutableState.update { it.copy(stats = stats) }
    }
}
