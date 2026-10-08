package dev.sk2andy.voiceisolation.audio

internal data class AudioMixSettings(
    val backgroundReduction: Float = 0.65f,
    val voiceGain: Float = 1.3f,
    val autoMode: Boolean = false,
) {
    fun normalized(): AudioMixSettings {
        val reduction = if (backgroundReduction.isFinite()) {
            backgroundReduction.coerceIn(0f, 1f)
        } else {
            0.65f
        }
        val gain = if (voiceGain.isFinite()) voiceGain.coerceIn(1f, 2f) else 1f
        return if (reduction == backgroundReduction && gain == voiceGain) {
            this
        } else {
            copy(backgroundReduction = reduction, voiceGain = gain)
        }
    }

    companion object {
        fun fromIntensity(intensity: Float): AudioMixSettings {
            val reduction = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 0.65f
            return AudioMixSettings(reduction, 1f + reduction * 0.3f)
        }
    }
}
