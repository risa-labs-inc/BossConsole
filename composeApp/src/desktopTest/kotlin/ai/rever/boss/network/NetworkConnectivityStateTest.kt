package ai.rever.boss.network

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkConnectivityStateTest {
    @BeforeTest
    fun setUp() {
        NetworkConnectivityMonitor.reset()
    }

    @Test
    fun `formatNetworkStatusLine formats ONLINE status correctly`() {
        assertEquals("Connected to network", formatNetworkStatusLine(NetworkStatus.ONLINE))
    }

    @Test
    fun `formatNetworkStatusLine formats OFFLINE status correctly`() {
        assertEquals("Offline — Check your internet connection", formatNetworkStatusLine(NetworkStatus.OFFLINE))
    }

    @Test
    fun `formatNetworkStatusLine formats RECONNECTING status correctly`() {
        assertEquals("Reconnecting to network...", formatNetworkStatusLine(NetworkStatus.RECONNECTING))
    }

    @Test
    fun `NetworkConnectivityMonitor updates and tracks offline status`() {
        NetworkConnectivityMonitor.updateStatus(NetworkStatus.OFFLINE)

        assertEquals(NetworkStatus.OFFLINE, NetworkConnectivityMonitor.currentStatus)
        assertFalse(NetworkConnectivityMonitor.isConnected())
    }

    @Test
    fun `NetworkConnectivityMonitor tracks online status`() {
        NetworkConnectivityMonitor.updateStatus(NetworkStatus.ONLINE)

        assertEquals(NetworkStatus.ONLINE, NetworkConnectivityMonitor.currentStatus)
        assertTrue(NetworkConnectivityMonitor.isConnected())
    }

    @Test
    fun `reset restores monitor to default ONLINE state`() {
        NetworkConnectivityMonitor.updateStatus(NetworkStatus.OFFLINE)
        NetworkConnectivityMonitor.reset()

        assertEquals(NetworkStatus.ONLINE, NetworkConnectivityMonitor.currentStatus)
        assertTrue(NetworkConnectivityMonitor.isConnected())
    }
}
