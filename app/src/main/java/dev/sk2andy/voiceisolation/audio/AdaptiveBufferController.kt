package dev.sk2andy.voiceisolation.audio

internal class AdaptiveBufferController(
    private val initialBufferFrames: Int,
    capacityFrames: Int,
    private val frameSize: Int,
    actualBufferFrames: Int = initialBufferFrames,
) {
    var bufferFrames: Int = actualBufferFrames
        private set

    val stabilityLevel: Int
        get() = when {
            bufferFrames >= initialBufferFrames * 2 -> 2
            bufferFrames > initialBufferFrames -> 1
            else -> 0
        }

    private var previousUnderruns = 0
    private val maximumBufferFrames = capacityFrames
    private var capacityFrames = capacityFrames

    init {
        require(initialBufferFrames > 0 && capacityFrames > 0 && frameSize > 0)
        require(actualBufferFrames > 0)
    }

    fun resizeRequest(underruns: Int): Int? {
        val normalizedUnderruns = underruns.coerceAtLeast(0)
        val newUnderruns = normalizedUnderruns > previousUnderruns
        previousUnderruns = normalizedUnderruns
        if (!newUnderruns || bufferFrames >= capacityFrames) return null

        return (bufferFrames.toLong() + maxOf(frameSize, bufferFrames / 2))
            .coerceAtMost(capacityFrames.toLong()).toInt()
    }

    fun onResizeResult(actualFrames: Int) {
        // AudioTrack may reject resizing or round the request. Trust only valid actual sizes.
        if (actualFrames in bufferFrames..capacityFrames) bufferFrames = actualFrames
    }

    fun synchronize(actualFrames: Int, actualCapacityFrames: Int) {
        // Routing can change the device's size or capacity. Report actual latency and keep
        // future requests bounded by both the allocation budget and current device capacity.
        if (actualFrames > 0) bufferFrames = actualFrames
        if (actualCapacityFrames > 0) capacityFrames = minOf(maximumBufferFrames, actualCapacityFrames)
    }
}
