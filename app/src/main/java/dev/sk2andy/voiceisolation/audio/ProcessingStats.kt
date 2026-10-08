package dev.sk2andy.voiceisolation.audio

data class ProcessingStats(
    val inferenceMs: Float,
    val realTimeFactor: Float,
    val inputDb: Float,
    val underruns: Int,
    val speechDetected: Boolean = false,
    val effectiveReduction: Float = 0f,
    val bufferMs: Float = 0f,
    val stabilityLevel: Int = 0,
)
