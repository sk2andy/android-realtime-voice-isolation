package dev.sk2andy.voiceisolation.audio

internal object PcmConverter {
    private const val SHORT_SCALE = 32768f

    fun toFloat(input: ShortArray, output: FloatArray, count: Int = input.size) {
        require(count <= input.size && count <= output.size)
        for (index in 0 until count) {
            output[index] = input[index] / SHORT_SCALE
        }
    }

    fun toShort(input: FloatArray, output: ShortArray, gain: Float = 1f) {
        require(input.size <= output.size)
        for (index in input.indices) {
            val sample = (input[index] * gain).coerceIn(-1f, 0.9999695f)
            output[index] = (sample * SHORT_SCALE).toInt().toShort()
        }
    }
}
