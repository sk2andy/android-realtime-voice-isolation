package dev.sk2andy.voiceisolation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoOffControllerTest {
    @Test
    fun `continuous silence triggers at configured timeout once`() {
        val controller = AutoOffController(autoOffMinutes = 5)

        assertFalse(controller.onAudioLevel(-90f, nowMillis = 1_000L))
        assertFalse(controller.onAudioLevel(-90f, nowMillis = 300_999L))
        assertTrue(controller.onAudioLevel(-90f, nowMillis = 301_000L))
        assertFalse(controller.onAudioLevel(-90f, nowMillis = 302_000L))
    }

    @Test
    fun `audible input resets silence timer`() {
        val controller = AutoOffController(autoOffMinutes = 5)

        assertFalse(controller.onAudioLevel(-90f, nowMillis = 1_000L))
        assertFalse(controller.onAudioLevel(-30f, nowMillis = 299_000L))
        assertFalse(controller.onAudioLevel(-90f, nowMillis = 300_000L))
        assertFalse(controller.onAudioLevel(-90f, nowMillis = 599_999L))
        assertTrue(controller.onAudioLevel(-90f, nowMillis = 600_000L))
    }

    @Test
    fun `disabled auto off never triggers`() {
        val controller = AutoOffController(autoOffMinutes = 0)

        assertFalse(controller.onAudioLevel(-120f, nowMillis = 0L))
        assertFalse(controller.onAudioLevel(-120f, nowMillis = Long.MAX_VALUE))
    }

    @Test
    fun `changing timeout keeps current silence interval`() {
        val controller = AutoOffController(autoOffMinutes = 15)

        assertFalse(controller.onAudioLevel(-90f, nowMillis = 0L))
        controller.updateAutoOffMinutes(5)

        assertTrue(controller.onAudioLevel(-90f, nowMillis = 300_000L))
    }

    @Test
    fun `invalid audio level is treated as activity`() {
        val controller = AutoOffController(autoOffMinutes = 5)

        assertFalse(controller.onAudioLevel(-90f, nowMillis = 0L))
        assertFalse(controller.onAudioLevel(Float.NaN, nowMillis = 300_000L))
        assertFalse(controller.onAudioLevel(-90f, nowMillis = 301_000L))
    }

    @Test
    fun `settings normalize to off or five minute steps`() {
        assertEquals(0, IsolationSettings.normalizeAutoOffMinutes(-1))
        assertEquals(0, IsolationSettings.normalizeAutoOffMinutes(0))
        assertEquals(5, IsolationSettings.normalizeAutoOffMinutes(1))
        assertEquals(5, IsolationSettings.normalizeAutoOffMinutes(7))
        assertEquals(10, IsolationSettings.normalizeAutoOffMinutes(8))
        assertEquals(60, IsolationSettings.normalizeAutoOffMinutes(99))
    }
}
