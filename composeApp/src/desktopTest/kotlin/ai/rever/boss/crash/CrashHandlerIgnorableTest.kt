package ai.rever.boss.crash

import io.github.jan.supabase.auth.exception.TokenExpiredException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [CrashHandler.isIgnorable] — recoverable background failures
 * must not pop the crash dialog (dismissing it exits the app).
 */
class CrashHandlerIgnorableTest {
    @Test
    fun `supabase token expiry is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(TokenExpiredException()))
    }

    @Test
    fun `token expiry nested in cause chain is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(RuntimeException("request failed", TokenExpiredException())))
    }

    @Test
    fun `coroutine cancellation is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(kotlinx.coroutines.CancellationException("cancelled")))
    }

    @Test
    fun `broken pipe io exception is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(java.io.IOException("Broken pipe")))
    }

    @Test
    fun `generic runtime exception is not ignorable`() {
        assertFalse(CrashHandler.isIgnorable(RuntimeException("actual crash")))
    }
}
