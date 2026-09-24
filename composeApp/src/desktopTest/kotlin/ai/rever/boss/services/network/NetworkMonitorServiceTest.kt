package ai.rever.boss.services.network

import ai.rever.boss.services.network.NetworkMonitorService.networkState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkMonitorServiceTest {
    // The service is a process-wide singleton, so every test starts from a
    // clean slate. Cleaning at the start (not in a fixture) also recovers from
    // an earlier test that failed halfway through.
    private fun resetServiceState() {
        NetworkMonitorService.stopAutoRetry()
        NetworkMonitorService.probeOverride = null
        NetworkMonitorService.reset()
    }

    private fun disconnected(): NetworkState.Disconnected = networkState.value as NetworkState.Disconnected

    @Test
    fun `consecutive failed checks advance the retry attempt counter`() =
        runTest {
            resetServiceState()
            NetworkMonitorService.probeOverride = { false }

            NetworkMonitorService.checkConnectivity()
            assertEquals(1, disconnected().retryAttempt)

            NetworkMonitorService.checkConnectivity()
            assertEquals(2, disconnected().retryAttempt)

            NetworkMonitorService.checkConnectivity()
            assertEquals(3, disconnected().retryAttempt)
        }

    @Test
    fun `successful check resets the retry attempt counter`() =
        runTest {
            resetServiceState()
            NetworkMonitorService.probeOverride = { false }
            NetworkMonitorService.checkConnectivity()
            assertEquals(1, disconnected().retryAttempt)

            NetworkMonitorService.probeOverride = { true }
            assertTrue(NetworkMonitorService.checkConnectivity())
            assertTrue(NetworkMonitorService.networkState.value is NetworkState.Connected)

            NetworkMonitorService.probeOverride = { false }
            NetworkMonitorService.checkConnectivity()
            assertEquals(1, disconnected().retryAttempt)
        }

    @Test
    fun `startAutoRetry does not leave the retry flag stuck when already connected`() =
        runTest {
            resetServiceState()
            NetworkMonitorService.probeOverride = { true }
            assertTrue(NetworkMonitorService.checkConnectivity())
            assertTrue(NetworkMonitorService.networkState.value is NetworkState.Connected)

            var onConnectedCalls = 0
            NetworkMonitorService.startAutoRetry { onConnectedCalls++ }

            // The loop body never runs (already Connected), so the finally must
            // clear the flag; with the sticky-flag bug this first() never
            // resumes and the test times out instead.
            NetworkMonitorService.isAutoRetrying.first { !it }
            assertFalse(NetworkMonitorService.isAutoRetrying.value)
            assertEquals(0, onConnectedCalls)
        }
}
