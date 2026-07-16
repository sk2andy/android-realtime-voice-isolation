package dev.sk2andy.voiceisolation.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class StftProcessor {
    private val window = FloatArray(FFT_SIZE) { index ->
        sqrt(0.5f - 0.5f * cos(2.0 * PI * index / FFT_SIZE).toFloat())
    }
    private val analysisBuffer = FloatArray(FFT_SIZE)
    private val overlapBuffer = FloatArray(FFT_SIZE)
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)

    fun analyze(input: FloatArray, spectrum: FloatArray) {
        require(input.size == HOP_SIZE)
        require(spectrum.size == SPECTRUM_SIZE)

        analysisBuffer.copyInto(
            destination = analysisBuffer,
            destinationOffset = 0,
            startIndex = HOP_SIZE,
            endIndex = FFT_SIZE,
        )
        input.copyInto(analysisBuffer, destinationOffset = FFT_SIZE - HOP_SIZE)

        for (index in 0 until FFT_SIZE) {
            real[index] = analysisBuffer[index] * window[index]
            imaginary[index] = 0f
        }
        fft(inverse = false)

        for (bin in 0 until BIN_COUNT) {
            spectrum[bin] = real[bin]
            spectrum[bin + BIN_COUNT] = imaginary[bin]
        }
    }

    fun synthesize(spectrum: FloatArray, output: FloatArray) {
        require(spectrum.size == SPECTRUM_SIZE)
        require(output.size == HOP_SIZE)

        for (bin in 0 until BIN_COUNT) {
            real[bin] = spectrum[bin]
            imaginary[bin] = spectrum[bin + BIN_COUNT]
        }
        for (bin in 1 until BIN_COUNT - 1) {
            real[FFT_SIZE - bin] = real[bin]
            imaginary[FFT_SIZE - bin] = -imaginary[bin]
        }
        fft(inverse = true)

        for (index in 0 until FFT_SIZE) {
            overlapBuffer[index] += real[index] * window[index]
        }
        overlapBuffer.copyInto(output, endIndex = HOP_SIZE)
        overlapBuffer.copyInto(
            destination = overlapBuffer,
            destinationOffset = 0,
            startIndex = HOP_SIZE,
            endIndex = FFT_SIZE,
        )
        overlapBuffer.fill(0f, fromIndex = FFT_SIZE - HOP_SIZE)
    }

    fun reset() {
        analysisBuffer.fill(0f)
        overlapBuffer.fill(0f)
        real.fill(0f)
        imaginary.fill(0f)
    }

    private fun fft(inverse: Boolean) {
        var reversed = 0
        for (current in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (current < reversed) {
                val realValue = real[current]
                real[current] = real[reversed]
                real[reversed] = realValue

                val imaginaryValue = imaginary[current]
                imaginary[current] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()

            var offset = 0
            while (offset < FFT_SIZE) {
                var weightReal = 1f
                var weightImaginary = 0f
                for (index in 0 until length / 2) {
                    val oddIndex = offset + index + length / 2
                    val oddReal = weightReal * real[oddIndex] - weightImaginary * imaginary[oddIndex]
                    val oddImaginary = weightReal * imaginary[oddIndex] + weightImaginary * real[oddIndex]
                    val evenIndex = offset + index
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]

                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary

                    val nextWeightReal = weightReal * stepReal - weightImaginary * stepImaginary
                    weightImaginary = weightReal * stepImaginary + weightImaginary * stepReal
                    weightReal = nextWeightReal
                }
                offset += length
            }
            length = length shl 1
        }

        if (inverse) {
            for (index in 0 until FFT_SIZE) {
                real[index] = real[index] / FFT_SIZE.toFloat()
                imaginary[index] = imaginary[index] / FFT_SIZE.toFloat()
            }
        }
    }

    companion object {
        const val FFT_SIZE = 512
        const val HOP_SIZE = 256
        const val BIN_COUNT = FFT_SIZE / 2 + 1
        const val SPECTRUM_SIZE = BIN_COUNT * 2
    }
}
