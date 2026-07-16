package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StftProcessorTest {
    @Test
    fun `analysis and synthesis reconstruct signal after one hop delay`() {
        val processor = StftProcessor()
        val spectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
        val output = FloatArray(StftProcessor.HOP_SIZE)
        val frames = List(12) { frameIndex ->
            FloatArray(StftProcessor.HOP_SIZE) { sampleIndex ->
                val absoluteSample = frameIndex * StftProcessor.HOP_SIZE + sampleIndex
                (0.55 * sin(2.0 * PI * 440.0 * absoluteSample / 16_000.0)).toFloat()
            }
        }

        var maximumError = 0f
        frames.forEachIndexed { index, frame ->
            processor.analyze(frame, spectrum)
            processor.synthesize(spectrum, output)
            if (index > 0) {
                frames[index - 1].indices.forEach { sampleIndex ->
                    maximumError = maxOf(
                        maximumError,
                        abs(frames[index - 1][sampleIndex] - output[sampleIndex]),
                    )
                }
            }
        }

        assertTrue("Maximum reconstruction error was $maximumError", maximumError < 0.0001f)
    }
}
