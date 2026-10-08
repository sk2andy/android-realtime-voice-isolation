package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveBufferControllerTest {
    @Test
    fun `new underruns grow active buffer but unchanged totals do not`() {
        val controller = AdaptiveBufferController(1024, 4096, 256)
        assertNull(controller.resizeRequest(0))
        assertEquals(1536, controller.resizeRequest(2))
        controller.onResizeResult(1536)
        assertEquals(1, controller.stabilityLevel)
        assertNull(controller.resizeRequest(2))
        assertEquals(2304, controller.resizeRequest(3))
        controller.onResizeResult(2304)
        assertEquals(2, controller.stabilityLevel)
    }

    @Test
    fun `buffer never exceeds capacity or shrinks`() {
        val controller = AdaptiveBufferController(1024, 4096, 256)
        for (underruns in 1..10) {
            controller.resizeRequest(underruns)?.let(controller::onResizeResult)
        }
        assertEquals(4096, controller.bufferFrames)
        assertNull(controller.resizeRequest(11))
        controller.onResizeResult(1024)
        assertEquals(4096, controller.bufferFrames)
    }

    @Test
    fun `resize errors keep actual size and device rounding is accepted`() {
        val controller = AdaptiveBufferController(1024, 4096, 256)
        assertEquals(1536, controller.resizeRequest(1))
        controller.onResizeResult(-3)
        assertEquals(1024, controller.bufferFrames)
        assertNull(controller.resizeRequest(1))
        assertEquals(1536, controller.resizeRequest(2))
        controller.onResizeResult(1600)
        assertEquals(1600, controller.bufferFrames)
        controller.onResizeResult(5000)
        assertEquals(1600, controller.bufferFrames)
    }

    @Test
    fun `counter reset does not cause a resize until another underrun`() {
        val controller = AdaptiveBufferController(1024, 4096, 256)
        controller.resizeRequest(4)?.let(controller::onResizeResult)
        assertNull(controller.resizeRequest(0))
        assertEquals(2304, controller.resizeRequest(1))
    }

    @Test
    fun `route changes report actual buffer and cap requests by device capacity`() {
        val controller = AdaptiveBufferController(1024, 4096, 256)
        controller.resizeRequest(1)?.let(controller::onResizeResult)
        controller.synchronize(2000, 2400)
        assertEquals(2000, controller.bufferFrames)
        assertNull(controller.resizeRequest(1))
        assertEquals(2400, controller.resizeRequest(2))
        controller.onResizeResult(2400)
        assertNull(controller.resizeRequest(3))

        controller.synchronize(5000, 8000)
        assertEquals(5000, controller.bufferFrames)
        assertNull(controller.resizeRequest(4))
    }
}
