package dev.sk2andy.voiceisolation.service

internal class AutoOffController(
    autoOffMinutes: Int,
    private val silenceThresholdDb: Float = DEFAULT_SILENCE_THRESHOLD_DB,
) {
    private var timeoutMillis = minutesToMillis(autoOffMinutes)
    private var silentSinceMillis: Long? = null
    private var triggered = false

    @Synchronized
    fun updateAutoOffMinutes(minutes: Int) {
        timeoutMillis = minutesToMillis(minutes)
        if (timeoutMillis == 0L) reset()
    }

    @Synchronized
    fun reset() {
        silentSinceMillis = null
        triggered = false
    }

    @Synchronized
    fun onAudioLevel(inputDb: Float, nowMillis: Long): Boolean {
        if (timeoutMillis == 0L) return false

        val silent = inputDb.isFinite() && inputDb <= silenceThresholdDb
        if (!silent) {
            reset()
            return false
        }

        val silentSince = silentSinceMillis
        if (silentSince == null) {
            silentSinceMillis = nowMillis
            return false
        }

        if (!triggered && (nowMillis - silentSince).coerceAtLeast(0L) >= timeoutMillis) {
            triggered = true
            return true
        }
        return false
    }

    private fun minutesToMillis(minutes: Int): Long =
        IsolationSettings.normalizeAutoOffMinutes(minutes) * MILLIS_PER_MINUTE

    companion object {
        internal const val DEFAULT_SILENCE_THRESHOLD_DB = -72f
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

