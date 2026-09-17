package ai.rever.boss.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the current network connectivity status of the desktop application.
 */
enum class NetworkStatus {
    ONLINE,
    OFFLINE,
    RECONNECTING,
}

/**
 * Pure helper function formatting a network status into human-readable banner text.
 */
fun formatNetworkStatusLine(status: NetworkStatus): String =
    when (status) {
        NetworkStatus.ONLINE -> "Connected to network"
        NetworkStatus.OFFLINE -> "Offline — Check your internet connection"
        NetworkStatus.RECONNECTING -> "Reconnecting to network..."
    }

/**
 * Thread-safe monitor tracking application network connectivity state.
 */
object NetworkConnectivityMonitor {
    private val lock = Any()

    private val _statusFlow = MutableStateFlow(NetworkStatus.ONLINE)
    val statusFlow: StateFlow<NetworkStatus> = _statusFlow.asStateFlow()

    /**
     * Current network status value.
     */
    val currentStatus: NetworkStatus
        get() = _statusFlow.value

    /**
     * Updates the current network status.
     */
    fun updateStatus(newStatus: NetworkStatus) {
        synchronized(lock) {
            _statusFlow.value = newStatus
        }
    }

    /**
     * Returns whether the network is currently available (ONLINE).
     */
    fun isConnected(): Boolean = currentStatus == NetworkStatus.ONLINE

    /**
     * Resets status to default ONLINE state.
     */
    fun reset() {
        synchronized(lock) {
            _statusFlow.value = NetworkStatus.ONLINE
        }
    }
}
