package ai.rever.boss.terminal

import ai.rever.boss.services.terminal.VoiceCallSessionManager
import ai.rever.boss.services.terminal.VoiceCallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class VoiceCallSessionManagerTest {
    @Test
    fun `startCall transitions to connecting and listening`() {
        val session = VoiceCallSessionManager("win-1", "term-1")
        assertEquals(VoiceCallState.DISCONNECTED, session.state.value)

        session.startCall()
        // Starts connecting and transitions to listening
        assertEquals(VoiceCallState.LISTENING, session.state.value)

        session.endCall()
        assertEquals(VoiceCallState.DISCONNECTED, session.state.value)
        session.dispose()
    }

    @Test
    fun `bargeIn triggers flush and interrupt callbacks when model is speaking`() {
        val session = VoiceCallSessionManager("win-1", "term-1")
        session.startCall()

        val flushed = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)

        session.onFlushAudioOutput = { flushed.set(true) }
        session.onInterruptSignal = { interrupted.set(true) }

        // Model speaks
        session.onModelAudioReceived(ByteArray(100))
        assertEquals(VoiceCallState.SPEAKING, session.state.value)

        // User speaks over the model (barge-in / interruption)
        session.onUserVoiceActivity(isSpeaking = true)

        assertTrue(flushed.get(), "Flush callback should be invoked on barge-in")
        assertTrue(interrupted.get(), "Interrupt signal should be sent to server")
        assertEquals(VoiceCallState.LISTENING, session.state.value, "State should revert to LISTENING")

        session.dispose()
    }

    @Test
    fun `tool execution transitions state to processing and back`() {
        val session = VoiceCallSessionManager("win-1", "term-1")
        session.startCall()

        session.onToolExecutionStarted("run_terminal_command", "git status")
        assertEquals(VoiceCallState.PROCESSING, session.state.value)
        assertEquals("run_terminal_command", session.metrics.value.activeToolName)

        session.onToolExecutionCompleted("run_terminal_command", "On branch main")
        assertEquals(null, session.metrics.value.activeToolName)

        session.dispose()
    }

    @Test
    fun `mute toggles state correctly`() {
        val session = VoiceCallSessionManager("win-1", "term-1")
        assertFalse(session.metrics.value.isMuted)

        val isMuted = session.toggleMute()
        assertTrue(isMuted)
        assertTrue(session.metrics.value.isMuted)

        val isUnmuted = session.toggleMute()
        assertFalse(isUnmuted)
        assertFalse(session.metrics.value.isMuted)

        session.dispose()
    }

    @Test
    fun `companion registry stores and cleans up sessions by window`() {
        VoiceCallSessionManager.getOrCreate("win-A", "term-1")
        VoiceCallSessionManager.getOrCreate("win-A", "term-2")
        VoiceCallSessionManager.getOrCreate("win-B", "term-1")

        assertNotNull(VoiceCallSessionManager.get("win-A", "term-1"))
        assertNotNull(VoiceCallSessionManager.get("win-A", "term-2"))
        assertNotNull(VoiceCallSessionManager.get("win-B", "term-1"))

        val removed = VoiceCallSessionManager.removeAllForWindow("win-A")
        assertEquals(2, removed)

        assertEquals(null, VoiceCallSessionManager.get("win-A", "term-1"))
        assertEquals(null, VoiceCallSessionManager.get("win-A", "term-2"))
        assertNotNull(VoiceCallSessionManager.get("win-B", "term-1"))

        VoiceCallSessionManager.removeAllForWindow("win-B")
    }
}
