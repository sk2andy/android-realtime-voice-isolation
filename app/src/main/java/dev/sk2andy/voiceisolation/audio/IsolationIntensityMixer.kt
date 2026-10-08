package dev.sk2andy.voiceisolation.audio

import kotlin.math.abs
import kotlin.math.tanh

internal class IsolationIntensityMixer(
    frameSize: Int,
) {
    private val delayedDryFrame = FloatArray(frameSize)
    private var currentIntensity = 0f
    private var initialized = false
    private val speechDetector = SpeechActivityDetector()
    private var mixInitialized = false
    private var currentReduction = 0f
    private var currentVoiceGain = 1f

    val speechDetected: Boolean
        get() = speechDetector.speechDetected

    val effectiveReduction: Float
        get() = currentReduction

    fun mix(
        input: FloatArray,
        isolated: FloatArray,
        output: FloatArray,
        intensity: Float,
    ) {
        require(input.size == delayedDryFrame.size)
        require(isolated.size == delayedDryFrame.size)
        require(output.size == delayedDryFrame.size)

        val normalizedIntensity = AudioMixSettings.fromIntensity(intensity).backgroundReduction
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

    fun mix(
        input: FloatArray,
        isolated: FloatArray,
        output: FloatArray,
        settings: AudioMixSettings,
    ) {
        require(input.size == delayedDryFrame.size && input.isNotEmpty())
        require(isolated.size == delayedDryFrame.size)
        require(output.size == delayedDryFrame.size)

        val normalized = settings.normalized()
        val hasSpeech = speechDetector.update(delayedDryFrame, isolated)
        val softenBackground = normalized.autoMode && !hasSpeech
        val targetReduction = if (softenBackground) {
            minOf(normalized.backgroundReduction, AUTO_BACKGROUND_REDUCTION)
        } else {
            normalized.backgroundReduction
        }
        val targetVoiceGain = if (softenBackground) 1f else normalized.voiceGain
        if (!mixInitialized) {
            currentReduction = targetReduction
            currentVoiceGain = targetVoiceGain
            mixInitialized = true
        }

        val rampFraction = if (normalized.autoMode) {
            val rampMs = if (hasSpeech) AUTO_ATTACK_MS else AUTO_RELEASE_MS
            (output.size * 1000f / GtcrnProcessor.SAMPLE_RATE / rampMs).coerceAtMost(1f)
        } else {
            1f
        }
        val nextReduction = currentReduction + (targetReduction - currentReduction) * rampFraction
        val nextVoiceGain = currentVoiceGain + (targetVoiceGain - currentVoiceGain) * rampFraction
        val reductionStep = (nextReduction - currentReduction) / output.size
        val gainStep = (nextVoiceGain - currentVoiceGain) / output.size

        for (index in output.indices) {
            val reduction = currentReduction + reductionStep * (index + 1)
            val voiceGain = currentVoiceGain + gainStep * (index + 1)
            val background = delayedDryFrame[index] - isolated[index]
            output[index] = softLimit(background * (1f - reduction) + isolated[index] * voiceGain)
        }
        currentReduction = nextReduction
        currentVoiceGain = nextVoiceGain
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
        private const val AUTO_BACKGROUND_REDUCTION = 0.2f
        private const val AUTO_ATTACK_MS = 40f
        private const val AUTO_RELEASE_MS = 250f
    }
}
