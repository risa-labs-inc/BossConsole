package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.UserData
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * IPC proxy implementation of AuthDataProvider.
 *
 * This runs in the kernel process and delegates all calls to the
 * auth service process via gRPC. Drop-in replacement for the in-process
 * AuthDataProviderImpl when running in kernel mode.
 */
class AuthDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : AuthDataProvider {

    private val stub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)

    private val _currentUser = MutableStateFlow<UserData?>(null)
    override val currentUser: StateFlow<UserData?> = _currentUser.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    override val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _userPermissions = MutableStateFlow<Set<String>>(emptySet())
    override val userPermissions: StateFlow<Set<String>> = _userPermissions.asStateFlow()

    init {
        // Start watching auth state from the remote service with reconnection
        scope.launch {
            var delayMs = 1_000L
            while (isActive) {
                try {
                    stub.watchCurrentUser(Empty.getDefaultInstance()).collect { response ->
                        if (response.authenticated && response.hasUser()) {
                            val user = response.user
                            _currentUser.value = UserData(
                                id = user.userId,
                                email = user.email,
                                displayName = user.displayName.takeIf { it.isNotEmpty() },
                                avatarUrl = user.avatarUrl.takeIf { it.isNotEmpty() },
                                roles = user.rolesList,
                                createdAt = user.sessionCreatedAt,
                            )
                            _isAdmin.value = user.isAdmin
                            _userPermissions.value = user.permissionsList.toSet()
                        } else {
                            _currentUser.value = null
                            _isAdmin.value = false
                            _userPermissions.value = emptySet()
                        }
                    }
                    // Stream ended cleanly — reconnect immediately
                    delayMs = 1_000L
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Connection lost — reset state and reconnect with backoff
                    _currentUser.value = null
                    _isAdmin.value = false
                    _userPermissions.value = emptySet()
                    kotlinx.coroutines.delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    // Non-suspend: check cached permissions (updated by background watcher)
    override fun hasPermission(permission: String): Boolean {
        return permission in _userPermissions.value
    }

    override fun hasAnyPermission(vararg permissions: String): Boolean {
        val current = _userPermissions.value
        return permissions.any { it in current }
    }
}
