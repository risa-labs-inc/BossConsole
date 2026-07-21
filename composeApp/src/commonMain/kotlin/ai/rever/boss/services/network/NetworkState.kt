package ai.rever.boss.services.network

/**
 * Network connectivity states
 */
sealed class NetworkState {
    object Checking : NetworkState()
    object Connected : NetworkState()
    data class Disconnected(
        val lastCheckTime: Long = System.currentTimeMillis(),
        val retryAttempt: Int = 0
    ) : NetworkState()
}
