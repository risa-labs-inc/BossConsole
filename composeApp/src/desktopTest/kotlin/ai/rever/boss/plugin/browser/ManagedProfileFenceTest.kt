package ai.rever.boss.plugin.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedProfileFenceTest {
    @Test
    fun `busy profile acquisition fails on deadline without unlocking its existing owner`() =
        runTest {
            val mutex = Mutex(locked = true)
            val failure = assertFailsWith<IllegalStateException> { acquireManagedProfileLock(mutex, 50) }
            assertTrue(failure.message.orEmpty().contains("still in use"))
            assertTrue(mutex.isLocked)
            mutex.unlock()
        }

    @Test
    fun `released profile can be acquired and the new caller owns its fence`() =
        runTest {
            val mutex = Mutex(locked = true)
            val acquisition = async { acquireManagedProfileLock(mutex, 100) }
            runCurrent()
            mutex.unlock()
            acquisition.await()
            assertTrue(mutex.isLocked)
            mutex.unlock()
        }

    @Test
    fun `cancelled acquisition does not unlock a potentially live profile`() =
        runTest {
            val mutex = Mutex(locked = true)
            val acquisition = launch { acquireManagedProfileLock(mutex, 100) }
            runCurrent()
            acquisition.cancelAndJoin()
            assertTrue(mutex.isLocked)
            mutex.unlock()
        }
}
