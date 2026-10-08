package dev.sk2andy.voiceisolation.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechActivityDetectorTest {
    private val dry = FloatArray(256) { 0.2f }
    private val speech = FloatArray(256) { 0.1f }

    @Test
    fun `speech needs two frames and retained energy holds through brief pauses`() {
        val detector = SpeechActivityDetector()
        assertFalse(detector.update(dry, speech))
        assertTrue(detector.update(dry, speech))
        repeat(SpeechActivityDetector.HANGOVER_FRAMES) {
            assertTrue(detector.update(dry, FloatArray(256)))
        }
        assertFalse(detector.update(dry, FloatArray(256)))
    }

    @Test
    fun `hysteresis retains quieter speech without triggering from background`() {
        val detector = SpeechActivityDetector()
        val borderlineSpeech = FloatArray(256) { 0.075f }
        repeat(3) { assertFalse(detector.update(dry, borderlineSpeech)) }
        detector.update(dry, speech)
        assertTrue(detector.update(dry, speech))
        repeat(SpeechActivityDetector.HANGOVER_FRAMES + 2) {
            assertTrue(detector.update(dry, borderlineSpeech))
        }
    }

    @Test
    fun `silence and single frame spikes do not activate speech`() {
        val detector = SpeechActivityDetector()
        assertFalse(detector.update(FloatArray(256), speech))
        assertFalse(detector.update(dry, speech))
        assertFalse(detector.update(dry, FloatArray(256)))
        repeat(3) {
            assertFalse(detector.update(FloatArray(256) { 0.004f }, FloatArray(256) { 0.004f }))
        }
    }
}
