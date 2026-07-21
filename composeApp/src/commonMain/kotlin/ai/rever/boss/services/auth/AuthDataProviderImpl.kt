package ai.rever.boss.services.auth

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.UserData
import ai.rever.boss.services.supabase.models.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of AuthDataProvider that wraps AuthStateManager.
 *
 * This adapter allows auth-dependent panels to be extracted to separate
 * plugin modules while keeping the actual auth management in composeApp.
 */
class AuthDataProviderImpl : AuthDataProvider {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _currentUser = MutableStateFlow<UserData?>(null)
    private val _isAdmin = MutableStateFlow(false)
    private val _userPermissions = MutableStateFlow<Set<String>>(emptySet())

    init {
        // Observe AuthStateManager and map to plugin types
        scope.launch {
            AuthStateManager.currentUser.collect { userInfo ->
                _currentUser.value = userInfo?.toPluginData()
                _isAdmin.value = userInfo?.isAdmin == true
                // Effective permissions from the JWT (own + inherited via the role
                // hierarchy) — NOT the user's role names.
                _userPermissions.value = userInfo?.permissions?.toSet() ?: emptySet()
            }
        }
    }

    override val currentUser: StateFlow<UserData?>
        get() = _currentUser.asStateFlow()

    override val isAdmin: StateFlow<Boolean>
        get() = _isAdmin.asStateFlow()

    override val userPermissions: StateFlow<Set<String>>
        get() = _userPermissions.asStateFlow()

    override fun hasPermission(permission: String): Boolean {
        val user = AuthStateManager.currentUser.value ?: return false
        // Check the user's effective permissions (admins implicitly hold all).
        return user.hasPermission(permission)
    }

    override fun hasAnyPermission(vararg permissions: String): Boolean {
        return permissions.any { hasPermission(it) }
    }

    private fun UserInfo.toPluginData(): UserData {
        return UserData(
            id = id,
            email = email,
            displayName = null, // UserInfo doesn't have displayName
            avatarUrl = null, // UserInfo doesn't have avatarUrl
            roles = roles,
            createdAt = 0L // UserInfo has string date, we'll skip parsing for now
        )
    }
}
