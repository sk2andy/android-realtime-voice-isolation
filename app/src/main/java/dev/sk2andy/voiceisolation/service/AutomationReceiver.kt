package dev.sk2andy.voiceisolation.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.sk2andy.voiceisolation.R

/**
 * Opt-in automation entry point for Tasker and similar tools.
 *
 * Receiver intentionally has no manifest intent filter. Callers must target component explicitly
 * and provide per-install token shown in app. Token never appears in logs.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.className != javaClass.name) {
            reject("Automation broadcast must be explicit")
            return
        }
        if (!IsolationSettings.isAutomationAuthorized(context, intent.getStringExtra(EXTRA_TOKEN))) {
            reject("Automation API disabled or token invalid")
            return
        }

        runCatching {
            when (intent.action) {
                ACTION_START -> start(context)
                ACTION_STOP -> stop(context)
                ACTION_SET_INTENSITY -> setIntensity(context, intent)
                ACTION_SET_PRESET -> setPreset(context, intent)
                else -> error("Unsupported automation action")
            }
        }.onSuccess {
            resultCode = Activity.RESULT_OK
        }.onFailure { error ->
            Log.e(TAG, "Automation command failed", error)
            resultCode = Activity.RESULT_CANCELED
        }
    }

    private fun start(context: Context) {
        if (
            IsolationStateStore.state.value.phase in setOf(
                IsolationPhase.Connecting,
                IsolationPhase.LoadingModel,
                IsolationPhase.Running,
            )
        ) {
            return
        }
        IsolationStateStore.updateSettings(IsolationSettings.snapshot(context))
        ContextCompat.startForegroundService(
            context,
            AudioIsolationService.intent(context, AudioIsolationService.ACTION_START),
        )
        IsolationStateStore.updatePhase(
            IsolationPhase.Connecting,
            context.getString(R.string.state_connecting_shizuku),
        )
    }

    private fun stop(context: Context) {
        if (IsolationStateStore.state.value.phase in setOf(IsolationPhase.Idle, IsolationPhase.Error)) return
        context.startService(
            AudioIsolationService.intent(context, AudioIsolationService.ACTION_STOP),
        )
    }

    private fun setIntensity(context: Context, intent: Intent) {
        check(intent.hasExtra(EXTRA_INTENSITY)) { "Missing intensity" }
        val intensity = readIntensity(intent)
        check(intensity.isFinite() && intensity in 0f..1f) { "Intensity must be between 0 and 1" }
        val settings = IsolationSettings.setCustomIntensity(context, intensity)
        IsolationStateStore.updateSettings(settings)
        applyIntensityToActiveService(context, settings.intensity)
    }

    private fun setPreset(context: Context, intent: Intent) {
        val requestedPreset = intent.getStringExtra(EXTRA_PRESET)
        val preset = IsolationPreset.entries.firstOrNull { it.storageValue == requestedPreset }
        val userPreset = IsolationSettings.findUserPreset(context, requestedPreset)
        check(preset != null || userPreset != null) { "Unknown preset" }
        val settings = if (preset != null) {
            IsolationSettings.setPreset(context, preset)
        } else {
            IsolationSettings.setUserPreset(context, checkNotNull(userPreset).id)
        }
        IsolationStateStore.updateSettings(settings)
        applyIntensityToActiveService(context, settings.intensity)
    }

    private fun applyIntensityToActiveService(context: Context, intensity: Float) {
        if (
            IsolationStateStore.state.value.phase !in setOf(
                IsolationPhase.Connecting,
                IsolationPhase.LoadingModel,
                IsolationPhase.Running,
            )
        ) {
            return
        }
        context.startService(
            AudioIsolationService.intent(context, AudioIsolationService.ACTION_SET_INTENSITY)
                .putExtra(AudioIsolationService.EXTRA_INTENSITY, intensity)
                .putExtra(AudioIsolationService.EXTRA_PERSIST, false),
        )
    }

    private fun reject(reason: String) {
        Log.w(TAG, reason)
        resultCode = Activity.RESULT_CANCELED
    }

    @Suppress("DEPRECATION")
    private fun readIntensity(intent: Intent): Float {
        val value = intent.extras?.get(EXTRA_INTENSITY)
        return when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        } ?: Float.NaN
    }

    companion object {
        const val ACTION_START = "dev.sk2andy.voiceisolation.automation.START"
        const val ACTION_STOP = "dev.sk2andy.voiceisolation.automation.STOP"
        const val ACTION_SET_INTENSITY = "dev.sk2andy.voiceisolation.automation.SET_INTENSITY"
        const val ACTION_SET_PRESET = "dev.sk2andy.voiceisolation.automation.SET_PRESET"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_PRESET = "preset"

        private const val TAG = "AutomationReceiver"
    }
}
