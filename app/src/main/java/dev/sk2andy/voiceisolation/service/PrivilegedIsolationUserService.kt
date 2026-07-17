package dev.sk2andy.voiceisolation.service

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import dev.sk2andy.voiceisolation.R
import dev.sk2andy.voiceisolation.audio.PrivilegedAudioPipeline
import dev.sk2andy.voiceisolation.audio.UserVisibleAudioException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.InvocationTargetException

@Keep
class PrivilegedIsolationUserService @Keep constructor(
    private val context: Context,
) : IIsolationUserService.Stub() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var running = false

    private var callback: IIsolationCallback? = null
    private var pipeline: PrivilegedAudioPipeline? = null

    override fun start(intensity: Float, callback: IIsolationCallback) {
        this.callback = callback
        scope.launch {
            mutex.withLock {
                if (pipeline != null) {
                    notifyState(IsolationProtocol.RUNNING, context.getString(R.string.state_isolation_running))
                    return@withLock
                }

                notifyState(
                    IsolationProtocol.LOADING_MODEL,
                    context.getString(R.string.state_loading_gtcrn, Process.myUid()),
                )
                val createdPipeline = PrivilegedAudioPipeline(
                    context = context,
                    onStats = { stats ->
                        runCatching {
                            this@PrivilegedIsolationUserService.callback?.onStats(
                                stats.inferenceMs,
                                stats.realTimeFactor,
                                stats.inputDb,
                                stats.underruns,
                            )
                        }
                    },
                    onFatalError = { error ->
                        scope.launch { handleFatalError(error) }
                    },
                )

                try {
                    createdPipeline.start(intensity)
                    pipeline = createdPipeline
                    running = true
                    notifyState(IsolationProtocol.RUNNING, context.getString(R.string.state_media_isolated))
                } catch (error: Throwable) {
                    createdPipeline.close()
                    running = false
                    notifyState(IsolationProtocol.ERROR, error.userMessage())
                }
            }
        }
    }

    override fun stop() {
        runBlocking {
            mutex.withLock {
                if (pipeline == null && !running) return@withLock
                notifyState(IsolationProtocol.STOPPING, context.getString(R.string.state_releasing_audio_route))
                pipeline?.stop()
                pipeline = null
                running = false
                notifyState(IsolationProtocol.IDLE, context.getString(R.string.state_ready))
            }
        }
    }

    override fun setIntensity(intensity: Float) {
        pipeline?.setIntensity(intensity)
    }

    override fun isRunning(): Boolean = running

    override fun destroy() {
        runCatching { stop() }
        scope.cancel()
        Log.i(TAG, "Privileged UserService destroyed")
        System.exit(0)
    }

    private suspend fun handleFatalError(error: Throwable) {
        mutex.withLock {
            runCatching { pipeline?.stop() }
            pipeline = null
            running = false
            notifyState(IsolationProtocol.ERROR, error.userMessage())
        }
    }

    private fun notifyState(state: Int, message: String) {
        runCatching { callback?.onStateChanged(state, message) }
        Log.i(TAG, "state=$state")
    }

    private fun Throwable.userMessage(): String {
        val root = generateSequence(this) { error ->
            when (error) {
                is InvocationTargetException -> error.targetException
                else -> error.cause
            }
        }.last()
        Log.e(TAG, "Privileged audio failure", root)
        return when (root) {
            is UserVisibleAudioException -> context.getString(
                root.userMessageResource,
                *root.formatArguments,
            )
            else -> context.getString(R.string.error_unknown)
        }
    }

    companion object {
        private const val TAG = "IsolationUserService"
    }
}
