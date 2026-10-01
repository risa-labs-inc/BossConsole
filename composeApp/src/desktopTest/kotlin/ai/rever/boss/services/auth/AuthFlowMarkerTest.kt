package ai.rever.boss.services.auth

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthFlowMarkerTest {
    @TempDir
    lateinit var root: File

    private fun mark(at: Long) {
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText(at.toString())
    }

    @Test
    fun `a recent flow is pending`() {
        mark(1_000L)
        assertEquals(1_000L, AuthFlowMarker.pendingSince(root, now = 1_000L + 60_000L))
    }

    @Test
    fun `a flow past the sign-in time limit is not`() {
        mark(1_000L)
        assertNull(AuthFlowMarker.pendingSince(root, now = 1_000L + AuthFlowMarker.MAX_AGE_MS + 1))
    }

    @Test
    fun `no marker, a garbled one, or one from the future is not pending`() {
        assertNull(AuthFlowMarker.pendingSince(root, now = 1_000L))
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText("soon")
        assertNull(AuthFlowMarker.pendingSince(root, now = 1_000L))
        mark(5_000L)
        assertNull(AuthFlowMarker.pendingSince(root, now = 1_000L))
    }
}
