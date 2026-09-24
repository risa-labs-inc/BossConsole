package ai.rever.boss.viewmodels

import ai.rever.boss.viewmodels.auth.AuthOptionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auth view-models own coroutine scopes that must not outlive the auth screen. Each dispose()
 * cancels its scope, and AuthScreenContainer calls LoginViewModel.dispose() from onDispose so an
 * in-flight sign-in cannot run its onSuccess or mutate state after the screen is gone.
 */
class LoginViewModelDisposeTest {
    @Test
    fun `CoreLoginViewModel dispose cancels its scope`() {
        val scope = CoroutineScope(SupervisorJob())
        val vm = CoreLoginViewModel(scope)
        assertTrue(scope.isActive)
        vm.dispose()
        assertFalse(scope.isActive)
    }

    @Test
    fun `AuthOptionsManager dispose cancels its scope`() {
        val scope = CoroutineScope(SupervisorJob())
        val vm = AuthOptionsManager(scope)
        assertTrue(scope.isActive)
        vm.dispose()
        assertFalse(scope.isActive)
    }

    @Test
    fun `PasskeyAuthViewModel dispose cancels its scope`() {
        val scope = CoroutineScope(SupervisorJob())
        val vm = PasskeyAuthViewModel(scope)
        assertTrue(scope.isActive)
        vm.dispose()
        assertFalse(scope.isActive)
    }

    @Test
    fun `LoginViewModel dispose cancels its scope`() {
        val scope = CoroutineScope(SupervisorJob())
        val vm = LoginViewModel(scope)
        assertTrue(scope.isActive)
        vm.dispose()
        assertFalse(scope.isActive)
    }

    @Test
    fun `dispose is safe to call more than once`() {
        val vm = LoginViewModel(CoroutineScope(SupervisorJob()))
        vm.dispose()
        vm.dispose()
    }
}
