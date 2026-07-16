package dev.sk2andy.voiceisolation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sk2andy.voiceisolation.service.AudioIsolationService
import dev.sk2andy.voiceisolation.service.IsolationPhase
import dev.sk2andy.voiceisolation.service.IsolationStateStore
import dev.sk2andy.voiceisolation.service.IsolationUiState
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var shizukuReady by mutableStateOf(false)
    private var pendingStart = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuState() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            shizukuReady = false
            val phase = IsolationStateStore.state.value.phase
            if (phase != IsolationPhase.Idle && phase != IsolationPhase.Error) {
                IsolationStateStore.updatePhase(IsolationPhase.Error, getString(R.string.error_shizuku_stopped))
            }
        }
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startForegroundIsolation()
            } else {
                IsolationStateStore.updatePhase(
                    IsolationPhase.Error,
                    getString(R.string.error_shizuku_permission_denied),
                )
            }
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart) {
            pendingStart = false
            connectAndStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshShizukuState()

        setContent {
            VoiceIsolationTheme {
                val state by IsolationStateStore.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    shizukuReady = shizukuReady,
                    onStart = ::requestNotificationThenStart,
                    onStop = ::stopIsolation,
                    onOpenShizuku = ::openShizuku,
                    onVolumeChange = ::setOutputVolume,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun requestNotificationThenStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connectAndStart()
        }
    }

    private fun connectAndStart() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuReady = false
                IsolationStateStore.updatePhase(
                    IsolationPhase.Error,
                    getString(R.string.error_shizuku_not_running_instructions),
                )
                return
            }
            shizukuReady = true
            if (Shizuku.getVersion() < MINIMUM_SHIZUKU_VERSION) {
                IsolationStateStore.updatePhase(
                    IsolationPhase.Error,
                    getString(R.string.error_shizuku_minimum_version, MINIMUM_SHIZUKU_VERSION),
                )
                return
            }
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    startForegroundIsolation()
                }
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    IsolationStateStore.updatePhase(
                        IsolationPhase.Error,
                        getString(R.string.error_shizuku_enable_permission),
                    )
                }
                else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Shizuku connection failed", error)
            IsolationStateStore.updatePhase(
                IsolationPhase.Error,
                getString(
                    R.string.error_shizuku,
                    getString(R.string.error_unknown),
                ),
            )
        }
    }

    private fun startForegroundIsolation() {
        IsolationStateStore.updatePhase(IsolationPhase.Connecting, getString(R.string.state_connecting_shizuku))
        ContextCompat.startForegroundService(
            this,
            AudioIsolationService.intent(this, AudioIsolationService.ACTION_START),
        )
    }

    private fun stopIsolation() {
        startService(AudioIsolationService.intent(this, AudioIsolationService.ACTION_STOP))
    }

    private fun setOutputVolume(volume: Float) {
        IsolationStateStore.updateVolume(volume)
        if (IsolationStateStore.state.value.phase == IsolationPhase.Running) {
            startService(
                AudioIsolationService.intent(this, AudioIsolationService.ACTION_SET_VOLUME)
                    .putExtra(AudioIsolationService.EXTRA_VOLUME, volume),
            )
        }
    }

    private fun refreshShizukuState() {
        shizukuReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://shizuku.rikka.app/download/"),
                ),
            )
        }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 6101
        private const val MINIMUM_SHIZUKU_VERSION = 13
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val TAG = "MainActivity"
    }
}

private val Ink = Color(0xFF061416)
private val Surface = Color(0xFF0C2326)
private val SurfaceLight = Color(0xFF123236)
private val Mint = Color(0xFF43E6C4)
private val SoftWhite = Color(0xFFE8F5F2)
private val Muted = Color(0xFF9DB8B3)
private val Error = Color(0xFFFF806E)

@Composable
private fun VoiceIsolationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Mint,
            background = Ink,
            surface = Surface,
            onPrimary = Ink,
            onBackground = SoftWhite,
            onSurface = SoftWhite,
            error = Error,
        ),
        content = content,
    )
}

@Composable
private fun MainScreen(
    state: IsolationUiState,
    shizukuReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenShizuku: () -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    val active = state.phase in setOf(
        IsolationPhase.Connecting,
        IsolationPhase.LoadingModel,
        IsolationPhase.Running,
        IsolationPhase.Stopping,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.brand_voice), color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.brand_isolation),
            color = SoftWhite,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            stringResource(R.string.tagline),
            color = Muted,
            fontSize = 16.sp,
            lineHeight = 23.sp,
        )

        StatusCard(state = state, shizukuReady = shizukuReady)

        if (state.phase == IsolationPhase.Running) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.output), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.volume_percent, (state.volume * 100).toInt()),
                            color = Mint,
                        )
                    }
                    Slider(value = state.volume, onValueChange = onVolumeChange)
                    state.stats?.let { stats ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric(stringResource(R.string.metric_inference), "%.1f ms".format(stats.inferenceMs))
                            Metric(stringResource(R.string.metric_rtf), "%.2f".format(stats.realTimeFactor))
                            Metric(stringResource(R.string.metric_input), "%.0f dB".format(stats.inputDb))
                        }
                    }
                }
            }
        }

        Button(
            onClick = if (active) onStop else onStart,
            enabled = state.phase != IsolationPhase.Stopping,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) Error else Mint,
                contentColor = Ink,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                stringResource(if (active) R.string.action_stop else R.string.action_start),
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
        }

        if (!shizukuReady) {
            Button(
                onClick = onOpenShizuku,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceLight,
                    contentColor = SoftWhite,
                ),
            ) {
                Text(stringResource(R.string.action_open_shizuku))
            }
        }

        SetupCard()
    }
}

@Composable
private fun StatusCard(state: IsolationUiState, shizukuReady: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(
                            if (state.phase == IsolationPhase.Error) Error else Mint,
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        stringResource(state.phase.labelResource),
                        color = Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        if (shizukuReady) R.string.shizuku_connected else R.string.shizuku_offline,
                    ),
                    color = if (shizukuReady) Mint else Muted,
                    fontSize = 13.sp,
                )
            }
            Text(
                state.message.ifBlank { stringResource(R.string.state_ready) },
                color = SoftWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Mint, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SetupCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.requirements_title), color = SoftWhite, fontWeight = FontWeight.Black)
            SetupLine("1", stringResource(R.string.setup_install_shizuku))
            SetupLine("2", stringResource(R.string.setup_grant_permission))
            SetupLine("3", stringResource(R.string.setup_start_media))
            Text(
                stringResource(R.string.capture_limitations),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

private val IsolationPhase.labelResource: Int
    get() = when (this) {
        IsolationPhase.Idle -> R.string.phase_idle
        IsolationPhase.Connecting -> R.string.phase_connecting
        IsolationPhase.LoadingModel -> R.string.phase_loading_model
        IsolationPhase.Running -> R.string.phase_running
        IsolationPhase.Stopping -> R.string.phase_stopping
        IsolationPhase.Error -> R.string.phase_error
    }

@Composable
private fun SetupLine(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = Mint, fontWeight = FontWeight.Black)
        Text(text, color = SoftWhite, modifier = Modifier.weight(1f), lineHeight = 20.sp)
    }
}
