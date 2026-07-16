package dev.sk2andy.voiceisolation.audio

data class ProcessingStats(
    val inferenceMs: Float,
    val realTimeFactor: Float,
    val inputDb: Float,
    val underruns: Int,
)
