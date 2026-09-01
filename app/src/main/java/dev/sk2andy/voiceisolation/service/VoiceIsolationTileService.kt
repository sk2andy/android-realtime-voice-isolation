package dev.sk2andy.voiceisolation.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import dev.sk2andy.voiceisolation.MainActivity
import dev.sk2andy.voiceisolation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class VoiceIsolationTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            IsolationStateStore.state.collectLatest(::updateTile)
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (IsolationStateStore.state.value.phase) {
            IsolationPhase.Connecting,
            IsolationPhase.LoadingModel,
            IsolationPhase.Running,
            -> stopIsolation()

            IsolationPhase.Stopping -> Unit
            IsolationPhase.Idle,
            IsolationPhase.Error,
            -> startIsolationOrOpenApp()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startIsolationOrOpenApp() {
        if (!hasShizukuAccess()) {
            openApp()
            return
        }

        IsolationStateStore.updatePhase(
            IsolationPhase.Connecting,
            getString(R.string.state_connecting_shizuku),
        )
        try {
            ContextCompat.startForegroundService(
                this,
                AudioIsolationService.intent(this, AudioIsolationService.ACTION_START),
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start isolation from Quick Settings", error)
            IsolationStateStore.updatePhase(
                IsolationPhase.Error,
                getString(R.string.error_shizuku_connection_failed),
            )
            openApp()
        }
    }

    private fun stopIsolation() {
        IsolationStateStore.updatePhase(
            IsolationPhase.Stopping,
            getString(R.string.state_releasing_audio_route),
        )
        startService(AudioIsolationService.intent(this, AudioIsolationService.ACTION_STOP))
    }

    private fun hasShizukuAccess(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.getVersion() >= MINIMUM_SHIZUKU_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: IsolationUiState) {
        val tile = qsTile ?: return
        val subtitle = if (state.phase == IsolationPhase.Running) {
            getString(
                R.string.tile_running_preset,
                state.presetLabel(this),
            )
        } else {
            getString(state.phase.subtitleResource())
        }
        tile.label = getString(R.string.app_name)
        tile.subtitle = subtitle
        tile.contentDescription = "${tile.label}, $subtitle"
        tile.stateDescription = subtitle
        tile.state = when (state.phase.quickTileVisualState()) {
            QuickTileVisualState.Inactive -> Tile.STATE_INACTIVE
            QuickTileVisualState.Active -> Tile.STATE_ACTIVE
            QuickTileVisualState.Unavailable -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    private fun IsolationPhase.subtitleResource(): Int = when (this) {
        IsolationPhase.Idle -> R.string.phase_idle
        IsolationPhase.Connecting -> R.string.phase_connecting
        IsolationPhase.LoadingModel -> R.string.phase_loading_model
        IsolationPhase.Running -> R.string.phase_running
        IsolationPhase.Stopping -> R.string.phase_stopping
        IsolationPhase.Error -> R.string.phase_error
    }

    companion object {
        private const val MINIMUM_SHIZUKU_VERSION = 13
        private const val OPEN_APP_REQUEST_CODE = 4201
        private const val TAG = "VoiceIsolationTile"
    }
}

internal enum class QuickTileVisualState {
    Inactive,
    Active,
    Unavailable,
}

internal fun IsolationPhase.quickTileVisualState(): QuickTileVisualState = when (this) {
    IsolationPhase.Idle,
    IsolationPhase.Error,
    -> QuickTileVisualState.Inactive

    IsolationPhase.Connecting,
    IsolationPhase.LoadingModel,
    IsolationPhase.Running,
    -> QuickTileVisualState.Active

    IsolationPhase.Stopping -> QuickTileVisualState.Unavailable
}
