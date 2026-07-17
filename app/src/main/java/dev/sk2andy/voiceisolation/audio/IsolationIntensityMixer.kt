package dev.sk2andy.voiceisolation.audio

import kotlin.math.abs
import kotlin.math.tanh

internal class IsolationIntensityMixer(
    frameSize: Int,
) {
    private val delayedDryFrame = FloatArray(frameSize)
    private var currentIntensity = 0f
    private var initialized = false

    fun mix(
        input: FloatArray,
        isolated: FloatArray,
        output: FloatArray,
        intensity: Float,
    ) {
        require(input.size == delayedDryFrame.size)
        require(isolated.size == delayedDryFrame.size)
        require(output.size == delayedDryFrame.size)

        val normalizedIntensity = intensity.coerceIn(0f, 1f)
        if (!initialized) {
            currentIntensity = normalizedIntensity
            initialized = true
        }
        val intensityStep = (normalizedIntensity - currentIntensity) / output.size

        for (index in output.indices) {
            val sampleIntensity = currentIntensity + intensityStep * (index + 1)
            val dryGain = 1f - sampleIntensity
            val isolatedGain = sampleIntensity * ISOLATED_VOICE_COMPENSATION_GAIN
            output[index] = softLimit(
                delayedDryFrame[index] * dryGain + isolated[index] * isolatedGain,
            )
        }

        currentIntensity = normalizedIntensity
        input.copyInto(delayedDryFrame)
    }

    private fun softLimit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= SOFT_LIMIT_START) return sample

        val limitedMagnitude = SOFT_LIMIT_START + SOFT_LIMIT_RANGE * tanh(
            ((magnitude - SOFT_LIMIT_START) / SOFT_LIMIT_RANGE).toDouble(),
        ).toFloat()
        return if (sample < 0f) -limitedMagnitude else limitedMagnitude
    }

    companion object {
        // Pinned GTCRN validation measured -2.30 dB speech gain at full isolation.
        private const val ISOLATED_VOICE_COMPENSATION_GAIN = 1.3f
        private const val MAX_PEAK = 0.9999695f
        private const val SOFT_LIMIT_START = 0.9f
        private const val SOFT_LIMIT_RANGE = MAX_PEAK - SOFT_LIMIT_START
    }
}
