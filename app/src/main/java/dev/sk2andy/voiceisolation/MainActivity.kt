package dev.sk2andy.voiceisolation

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.sk2andy.voiceisolation.service.IsolationPreset
import dev.sk2andy.voiceisolation.service.IsolationSettings
import dev.sk2andy.voiceisolation.service.IsolationSettingsSnapshot
import dev.sk2andy.voiceisolation.service.IsolationStateStore
import dev.sk2andy.voiceisolation.service.IsolationUiState
import dev.sk2andy.voiceisolation.service.UserIsolationPreset
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt
import kotlin.math.log10

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
        IsolationStateStore.updateSettings(IsolationSettings.snapshot(this))
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
                    onIntensityChange = ::setIntensity,
                    onIntensityChangeFinished = ::persistIntensity,
                    onVoiceGainChange = ::setVoiceGain,
                    onAutoModeChange = ::setAutoMode,
                    onPresetSelected = ::setPreset,
                    onUserPresetSelected = ::setUserPreset,
                    onSaveUserPreset = ::saveUserPreset,
                    onDeleteUserPreset = ::deleteUserPreset,
                    onAutoOffChange = ::setAutoOffMinutes,
                    onAutomationEnabledChange = ::setAutomationEnabled,
                    onCopyAutomationToken = ::copyAutomationToken,
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

    private fun setIntensity(intensity: Float) {
        IsolationStateStore.updatePreset(IsolationPreset.Custom, intensity)
        applyMixToActiveService()
    }

    private fun persistIntensity() {
        val state = IsolationStateStore.state.value
        val settings = IsolationSettings.setCustomMix(
            this,
            state.intensity,
            state.voiceGain,
            state.autoMode,
        )
        IsolationStateStore.updateSettings(settings)
    }

    private fun setVoiceGain(gain: Float) {
        IsolationStateStore.updateVoiceGain(gain)
        applyMixToActiveService()
    }

    private fun setAutoMode(enabled: Boolean) {
        IsolationStateStore.updateAutoMode(enabled)
        persistIntensity()
        applyMixToActiveService()
    }

    private fun setPreset(preset: IsolationPreset) {
        applySettings(IsolationSettings.setPreset(this, preset))
    }

    private fun setUserPreset(id: String) {
        applySettings(IsolationSettings.setUserPreset(this, id))
    }

    private fun saveUserPreset(name: String) {
        applySettings(
            IsolationSettings.saveUserPreset(
                context = this,
                name = name,
                intensity = IsolationStateStore.state.value.intensity,
                voiceGain = IsolationStateStore.state.value.voiceGain,
                autoMode = IsolationStateStore.state.value.autoMode,
            ),
        )
    }

    private fun deleteUserPreset(id: String) {
        applySettings(IsolationSettings.deleteUserPreset(this, id))
    }

    private fun applySettings(settings: IsolationSettingsSnapshot) {
        IsolationStateStore.updateSettings(settings)
        applyMixToActiveService()
    }

    private fun applyMixToActiveService() {
        if (
            IsolationStateStore.state.value.phase in setOf(
                IsolationPhase.Connecting,
                IsolationPhase.LoadingModel,
                IsolationPhase.Running,
            )
        ) {
            startService(
                AudioIsolationService.intent(this, AudioIsolationService.ACTION_SET_MIX),
            )
        }
    }

    private fun setAutoOffMinutes(minutes: Int) {
        val normalized = IsolationSettings.setAutoOffMinutes(this, minutes)
        IsolationStateStore.updateAutoOffMinutes(normalized)
        if (
            IsolationStateStore.state.value.phase in setOf(
                IsolationPhase.Connecting,
                IsolationPhase.LoadingModel,
                IsolationPhase.Running,
            )
        ) {
            startService(
                AudioIsolationService.intent(this, AudioIsolationService.ACTION_SET_AUTO_OFF)
                    .putExtra(AudioIsolationService.EXTRA_AUTO_OFF_MINUTES, normalized),
            )
        }
    }

    private fun setAutomationEnabled(enabled: Boolean) {
        IsolationStateStore.updateSettings(IsolationSettings.setAutomationEnabled(this, enabled))
    }

    private fun copyAutomationToken() {
        val token = IsolationStateStore.state.value.automationToken
        if (token.isBlank()) return
        val clip = ClipData.newPlainText(getString(R.string.automation_token), token).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
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
    onIntensityChange: (Float) -> Unit,
    onIntensityChangeFinished: () -> Unit,
    onVoiceGainChange: (Float) -> Unit,
    onAutoModeChange: (Boolean) -> Unit,
    onPresetSelected: (IsolationPreset) -> Unit,
    onUserPresetSelected: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
    onAutoOffChange: (Int) -> Unit,
    onAutomationEnabledChange: (Boolean) -> Unit,
    onCopyAutomationToken: () -> Unit,
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
            .safeDrawingPadding()
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

        PresetsCard(
            selectedPreset = state.preset,
            selectedUserPresetId = state.selectedUserPresetId,
            customIntensity = state.customIntensity,
            userPresets = state.userPresets,
            onPresetSelected = onPresetSelected,
            onUserPresetSelected = onUserPresetSelected,
            onSaveUserPreset = onSaveUserPreset,
            onDeleteUserPreset = onDeleteUserPreset,
        )

        AutoOffCard(
            minutes = state.autoOffMinutes,
            onMinutesChange = onAutoOffChange,
        )

        AudioMixCard(
            state = state,
            onIntensityChange = onIntensityChange,
            onVoiceGainChange = onVoiceGainChange,
            onChangeFinished = onIntensityChangeFinished,
            onAutoModeChange = onAutoModeChange,
        )

        AutomationCard(
            enabled = state.automationEnabled,
            token = state.automationToken,
            onEnabledChange = onAutomationEnabledChange,
            onCopyToken = onCopyAutomationToken,
        )

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
private fun AudioMixCard(
    state: IsolationUiState,
    onIntensityChange: (Float) -> Unit,
    onVoiceGainChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    onAutoModeChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.background_reduction), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.intensity_percent, (state.intensity * 100).roundToInt()), color = Mint)
            }
            Slider(
                value = state.intensity,
                onValueChange = onIntensityChange,
                onValueChangeFinished = onChangeFinished,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.voice_boost), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.voice_boost_db, 20f * log10(state.voiceGain)), color = Mint)
            }
            Slider(
                value = state.voiceGain,
                valueRange = 1f..2f,
                onValueChange = onVoiceGainChange,
                onValueChangeFinished = onChangeFinished,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_mode), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.auto_mode_description), color = Muted, fontSize = 13.sp)
                }
                Switch(checked = state.autoMode, onCheckedChange = onAutoModeChange)
            }
            if (state.phase == IsolationPhase.Running) {
                state.stats?.let { stats ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric(stringResource(R.string.metric_inference), "%.1f ms".format(stats.inferenceMs))
                        Metric(stringResource(R.string.metric_rtf), "%.2f".format(stats.realTimeFactor))
                        Metric(stringResource(R.string.metric_input), "%.0f dB".format(stats.inputDb))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric(stringResource(R.string.metric_underruns), stats.underruns.toString())
                        Metric(stringResource(R.string.metric_buffer), "%.0f ms".format(stats.bufferMs))
                        Metric(
                            stringResource(R.string.metric_stability),
                            stringResource(
                                when (stats.stabilityLevel) {
                                    2 -> R.string.stability_safe
                                    1 -> R.string.stability_buffered
                                    else -> R.string.stability_normal
                                },
                            ),
                        )
                    }
                    if (state.autoMode) {
                        Text(
                            stringResource(
                                if (stats.speechDetected) R.string.auto_speech else R.string.auto_background,
                                (stats.effectiveReduction * 100).roundToInt(),
                            ),
                            color = Mint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetsCard(
    selectedPreset: IsolationPreset,
    selectedUserPresetId: String?,
    customIntensity: Float,
    userPresets: List<UserIsolationPreset>,
    onPresetSelected: (IsolationPreset) -> Unit,
    onUserPresetSelected: (String) -> Unit,
    onSaveUserPreset: (String) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.presets), fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IsolationPreset.entries.forEach { preset ->
                    val label = if (preset == IsolationPreset.Custom) {
                        stringResource(
                            R.string.preset_custom_percent,
                            (customIntensity * 100).roundToInt(),
                        )
                    } else {
                        stringResource(preset.labelResource)
                    }
                    FilterChip(
                        selected = selectedUserPresetId == null && selectedPreset == preset,
                        onClick = { onPresetSelected(preset) },
                        label = { Text(label) },
                    )
                }
                userPresets.forEach { preset ->
                    FilterChip(
                        selected = selectedUserPresetId == preset.id,
                        onClick = { onUserPresetSelected(preset.id) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.preset_name_percent,
                                    preset.name,
                                    (preset.intensity * 100).roundToInt(),
                                ),
                            )
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showSaveDialog = true }) {
                    Text(stringResource(R.string.action_save_preset))
                }
                selectedUserPresetId?.let { id ->
                    TextButton(onClick = { onDeleteUserPreset(id) }) {
                        Text(stringResource(R.string.action_delete_preset))
                    }
                }
            }
            Text(
                stringResource(R.string.presets_description),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_preset_title)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onSaveUserPreset(presetName)
                        presetName = ""
                        showSaveDialog = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AutomationCard(
    enabled: Boolean,
    token: String,
    onEnabledChange: (Boolean) -> Unit,
    onCopyToken: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.automation_api), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.automation_api_description),
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Text(stringResource(R.string.automation_token), color = Muted, fontSize = 12.sp)
                Text(token, color = SoftWhite, fontSize = 12.sp)
                TextButton(onClick = onCopyToken) {
                    Text(stringResource(R.string.action_copy_token))
                }
            }
        }
    }
}

@Composable
private fun AutoOffCard(minutes: Int, onMinutesChange: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.auto_off), fontWeight = FontWeight.Bold)
                Text(
                    if (minutes == 0) {
                        stringResource(R.string.auto_off_disabled)
                    } else {
                        stringResource(R.string.auto_off_minutes, minutes)
                    },
                    color = Mint,
                )
            }
            Slider(
                value = minutes.toFloat(),
                onValueChange = {
                    onMinutesChange(IsolationSettings.normalizeAutoOffMinutes(it.roundToInt()))
                },
                valueRange = 0f..IsolationSettings.MAX_AUTO_OFF_MINUTES.toFloat(),
                steps = 11,
            )
            Text(
                stringResource(R.string.auto_off_description),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
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
