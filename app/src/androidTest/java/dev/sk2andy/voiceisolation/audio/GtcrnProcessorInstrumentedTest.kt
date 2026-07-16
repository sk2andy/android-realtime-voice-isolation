package dev.sk2andy.voiceisolation.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class GtcrnProcessorInstrumentedTest {
    @Test
    fun modelLoadsAndProcessesStreamingFrames() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        GtcrnProcessor(context).use { processor ->
            repeat(4) { frameIndex ->
                val input = FloatArray(StftProcessor.HOP_SIZE) { sampleIndex ->
                    val absoluteSample = frameIndex * StftProcessor.HOP_SIZE + sampleIndex
                    (0.2 * sin(2.0 * PI * 440.0 * absoluteSample / GtcrnProcessor.SAMPLE_RATE)).toFloat()
                }

                val output = processor.process(input)

                assertEquals(StftProcessor.HOP_SIZE, output.size)
                assertTrue(output.all(Float::isFinite))
            }
            assertTrue(processor.inferenceMs() > 0f)
        }
    }
}
