package dev.sk2andy.voiceisolation.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceIsolationTileServiceTest {
    @Test
    fun `idle and error phases render inactive`() {
        assertEquals(QuickTileVisualState.Inactive, IsolationPhase.Idle.quickTileVisualState())
        assertEquals(QuickTileVisualState.Inactive, IsolationPhase.Error.quickTileVisualState())
    }

    @Test
    fun `starting and running phases render active`() {
        assertEquals(QuickTileVisualState.Active, IsolationPhase.Connecting.quickTileVisualState())
        assertEquals(QuickTileVisualState.Active, IsolationPhase.LoadingModel.quickTileVisualState())
        assertEquals(QuickTileVisualState.Active, IsolationPhase.Running.quickTileVisualState())
    }

    @Test
    fun `stopping phase renders unavailable`() {
        assertEquals(QuickTileVisualState.Unavailable, IsolationPhase.Stopping.quickTileVisualState())
    }
}
