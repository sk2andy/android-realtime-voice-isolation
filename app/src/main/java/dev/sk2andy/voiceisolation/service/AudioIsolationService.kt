package dev.sk2andy.voiceisolation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.sk2andy.voiceisolation.BuildConfig
import dev.sk2andy.voiceisolation.MainActivity
import dev.sk2andy.voiceisolation.R
import dev.sk2andy.voiceisolation.audio.ProcessingStats
import rikka.shizuku.Shizuku

class AudioIsolationService : Service() {
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PrivilegedIsolationUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("isolation")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val callback = object : IIsolationCallback.Stub() {
        override fun onStateChanged(state: Int, message: String) {
            if (preserveError && state != IsolationProtocol.ERROR) return
            val phase = when (state) {
                IsolationProtocol.IDLE -> IsolationPhase.Idle
                IsolationProtocol.CONNECTING -> IsolationPhase.Connecting
                IsolationProtocol.LOADING_MODEL -> IsolationPhase.LoadingModel
                IsolationProtocol.RUNNING -> IsolationPhase.Running
                IsolationProtocol.STOPPING -> IsolationPhase.Stopping
                else -> IsolationPhase.Error
            }
            IsolationStateStore.updatePhase(phase, message)
            updateNotification()

            if (phase == IsolationPhase.Error) {
                preserveError = true
                stopSelf()
            }
        }

        override fun onStats(
            inferenceMs: Float,
            realTimeFactor: Float,
            inputDb: Float,
            underruns: Int,
        ) {
            IsolationStateStore.updateStats(
                ProcessingStats(inferenceMs, realTimeFactor, inputDb, underruns),
            )
            updateNotification()
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            userServiceBound = true
            bindingRequested = false
            val connectedService = IIsolationUserService.Stub.asInterface(binder)
            if (isStopping) {
                runCatching { connectedService.stop() }
                unbindUserService()
                return
            }
            userService = connectedService
            try {
                connectedService.start(IsolationStateStore.state.value.intensity, callback)
            } catch (error: Throwable) {
                Log.e(TAG, "Shizuku UserService start failed", error)
                fail(
                    getString(
                        R.string.error_shizuku_service_start,
                        getString(R.string.error_unknown),
                    ),
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            userServiceBound = false
            bindingRequested = false
            if (!isStopping) {
                Log.e(TAG, "Shizuku UserService disconnected")
                fail(getString(R.string.error_shizuku_disconnected))
            }
        }
    }

    private var userService: IIsolationUserService? = null
    private var userServiceBound = false
    private var bindingRequested = false
    private var isStopping = false
    private var preserveError = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopIsolation()
            ACTION_INTENSITY_UP -> changeIntensity(INTENSITY_STEP)
            ACTION_INTENSITY_DOWN -> changeIntensity(-INTENSITY_STEP)
            ACTION_SET_INTENSITY -> setIntensity(
                intent.getFloatExtra(EXTRA_INTENSITY, DEFAULT_INTENSITY),
            )
            ACTION_START -> startIsolation()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopping = true
        runCatching { userService?.stop() }
        userService = null
        unbindUserService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!preserveError) {
            IsolationStateStore.updatePhase(IsolationPhase.Idle, getString(R.string.state_ready))
        }
        super.onDestroy()
    }

    private fun startIsolation() {
        if (userService != null || userServiceBound || bindingRequested) return
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        IsolationStateStore.updatePhase(
            IsolationPhase.Connecting,
            getString(R.string.state_starting_privileged_service),
        )

        try {
            when {
                !Shizuku.pingBinder() -> {
                    Log.e(TAG, "Shizuku is not running")
                    fail(getString(R.string.error_shizuku_not_running))
                    return
                }
                Shizuku.getVersion() < MINIMUM_SHIZUKU_VERSION -> {
                    Log.e(TAG, "Shizuku API $MINIMUM_SHIZUKU_VERSION or newer is required")
                    fail(getString(R.string.error_shizuku_minimum_version, MINIMUM_SHIZUKU_VERSION))
                    return
                }
                Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                    Log.e(TAG, "Shizuku permission is missing")
                    fail(getString(R.string.error_shizuku_permission_missing))
                    return
                }
            }
            bindingRequested = true
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (error: Throwable) {
            Log.e(TAG, "Shizuku UserService binding failed", error)
            unbindUserService()
            fail(getString(R.string.error_shizuku_connection_failed))
        }
    }

    private fun stopIsolation() {
        if (isStopping) return
        isStopping = true
        IsolationStateStore.updatePhase(IsolationPhase.Stopping, getString(R.string.state_releasing_audio_route))
        runCatching { userService?.stop() }
        stopSelf()
    }

    private fun changeIntensity(delta: Float) {
        setIntensity(IsolationStateStore.state.value.intensity + delta)
    }

    private fun setIntensity(intensity: Float) {
        val normalized = intensity.coerceIn(0f, 1f)
        IsolationStateStore.updateIntensity(normalized)
        runCatching { userService?.setIntensity(normalized) }
        updateNotification()
    }

    private fun fail(message: String) {
        preserveError = true
        IsolationStateStore.updatePhase(IsolationPhase.Error, message)
        updateNotification()
        stopSelf()
    }

    private fun unbindUserService() {
        if (!bindingRequested && !userServiceBound) return
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        bindingRequested = false
        userServiceBound = false
        userService = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val state = IsolationStateStore.state.value
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = actionPendingIntent(ACTION_STOP, 1)
        val intensityDown = actionPendingIntent(ACTION_INTENSITY_DOWN, 2)
        val intensityUp = actionPendingIntent(ACTION_INTENSITY_UP, 3)
        val detail = if (state.phase == IsolationPhase.Running) {
            val rtf = state.stats?.realTimeFactor?.let { " · RTF %.2f".format(it) }.orEmpty()
            getString(R.string.notification_intensity, (state.intensity * 100).toInt()) + rtf
        } else {
            state.message
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_notification, "−", intensityDown)
            .addAction(R.drawable.ic_notification, "+", intensityUp)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioIsolationService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification())
    }

    companion object {
        const val ACTION_START = "dev.sk2andy.voiceisolation.action.START"
        const val ACTION_STOP = "dev.sk2andy.voiceisolation.action.STOP"
        const val ACTION_INTENSITY_UP = "dev.sk2andy.voiceisolation.action.INTENSITY_UP"
        const val ACTION_INTENSITY_DOWN = "dev.sk2andy.voiceisolation.action.INTENSITY_DOWN"
        const val ACTION_SET_INTENSITY = "dev.sk2andy.voiceisolation.action.SET_INTENSITY"
        const val EXTRA_INTENSITY = "intensity"

        private const val TAG = "AudioIsolationService"
        private const val CHANNEL_ID = "voice_isolation_active"
        private const val NOTIFICATION_ID = 4101
        private const val MINIMUM_SHIZUKU_VERSION = 13
        private const val INTENSITY_STEP = 0.1f
        private const val DEFAULT_INTENSITY = 0.65f

        fun intent(context: Context, action: String): Intent =
            Intent(context, AudioIsolationService::class.java).setAction(action)
    }
}
