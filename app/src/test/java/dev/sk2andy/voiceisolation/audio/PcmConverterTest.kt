package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmConverterTest {
    @Test
    fun `short to float maps full range`() {
        val output = FloatArray(3)

        PcmConverter.toFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE), output)

        assertEquals(-1f, output[0], 0f)
        assertEquals(0f, output[1], 0f)
        assertEquals(Short.MAX_VALUE / 32768f, output[2], 0f)
    }

    @Test
    fun `float to short applies gain and clips safely`() {
        val output = ShortArray(5)

        PcmConverter.toShort(floatArrayOf(-2f, -0.5f, 0f, 0.5f, 2f), output, gain = 0.5f)

        assertArrayEquals(
            shortArrayOf(Short.MIN_VALUE, -8192, 0, 8192, Short.MAX_VALUE),
            output,
        )
    }
}
