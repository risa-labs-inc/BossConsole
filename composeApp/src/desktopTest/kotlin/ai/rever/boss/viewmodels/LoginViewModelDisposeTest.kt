package ai.rever.boss.viewmodels

import ai.rever.boss.viewmodels.auth.AuthOptions
import ai.rever.boss.viewmodels.auth.AuthOptionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auth view-models own coroutine scopes that must not outlive the auth screen. Each dispose()
 * cancels its owned job without cancelling a shared parent scope, and AuthScreenContainer calls
 * LoginViewModel.dispose() from onDispose so an in-flight sign-in cannot run its onSuccess or
 * mutate state after the screen is gone.
 */
class LoginViewModelDisposeTest {
    @Test
    fun `CoreLoginViewModel dispose cancels its scope without cancelling caller parent scope`() {
        val parentScope = CoroutineScope(SupervisorJob())
        val vm = CoreLoginViewModel(parentScope)
        assertTrue(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)

        vm.dispose()

        assertFalse(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)
    }

    @Test
    fun `AuthOptionsManager dispose cancels its scope without cancelling caller parent scope`() {
        val parentScope = CoroutineScope(SupervisorJob())
        val vm = AuthOptionsManager(parentScope)
        assertTrue(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)

        vm.dispose()

        assertFalse(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)
    }

    @Test
    fun `PasskeyAuthViewModel dispose cancels its scope without cancelling caller parent scope`() {
        val parentScope = CoroutineScope(SupervisorJob())
        val vm = PasskeyAuthViewModel(parentScope)
        assertTrue(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)

        vm.dispose()

        assertFalse(vm.viewModelScope.isActive)
        assertTrue(parentScope.isActive)
    }

    @Test
    fun `LoginViewModel dispose cancels facade scope and cascades to all child view-model scopes`() {
        val parentScope = CoroutineScope(SupervisorJob())
        val vm = LoginViewModel(parentScope)
        assertTrue(vm.viewModelScope.isActive)
        assertTrue(vm.coreLoginViewModel.viewModelScope.isActive)
        assertTrue(vm.passkeyAuthViewModel.viewModelScope.isActive)
        assertTrue(vm.authOptionsManager.viewModelScope.isActive)
        assertTrue(parentScope.isActive)

        vm.dispose()

        assertFalse(vm.viewModelScope.isActive)
        assertFalse(vm.coreLoginViewModel.viewModelScope.isActive)
        assertFalse(vm.passkeyAuthViewModel.viewModelScope.isActive)
        assertFalse(vm.authOptionsManager.viewModelScope.isActive)
        assertTrue(parentScope.isActive)
    }

    @Test
    fun `LoginViewModel child dispose fan-out invokes dispose on each child independently`() {
        val parentScope = CoroutineScope(SupervisorJob())
        val coreVm = CoreLoginViewModel(parentScope)
        val passkeyVm = PasskeyAuthViewModel(parentScope)
        val authOptionsManager = AuthOptionsManager(parentScope)
        val vm = LoginViewModel(parentScope, coreVm, passkeyVm, authOptionsManager)

        assertTrue(coreVm.viewModelScope.isActive)
        assertTrue(passkeyVm.viewModelScope.isActive)
        assertTrue(authOptionsManager.viewModelScope.isActive)

        vm.dispose()

        assertFalse(coreVm.viewModelScope.isActive)
        assertFalse(passkeyVm.viewModelScope.isActive)
        assertFalse(authOptionsManager.viewModelScope.isActive)
    }

    @Test
    fun `dispose is safe to call more than once and maintains cancelled state`() {
        val vm = LoginViewModel()
        assertTrue(vm.viewModelScope.isActive)

        vm.dispose()
        assertFalse(vm.viewModelScope.isActive)

        vm.dispose()
        assertFalse(vm.viewModelScope.isActive)
    }

    @Test
    fun `scopes can be initialized with a CoroutineScope that has no Job without throwing`() {
        val noJobScope = CoroutineScope(EmptyCoroutineContext)
        val coreVm = CoreLoginViewModel(noJobScope)
        val authMgr = AuthOptionsManager(noJobScope)
        val passkeyVm = PasskeyAuthViewModel(noJobScope)
        val loginVm = LoginViewModel(noJobScope)

        assertTrue(coreVm.viewModelScope.isActive)
        assertTrue(authMgr.viewModelScope.isActive)
        assertTrue(passkeyVm.viewModelScope.isActive)
        assertTrue(loginVm.viewModelScope.isActive)

        coreVm.dispose()
        authMgr.dispose()
        passkeyVm.dispose()
        loginVm.dispose()

        assertFalse(coreVm.viewModelScope.isActive)
        assertFalse(authMgr.viewModelScope.isActive)
        assertFalse(passkeyVm.viewModelScope.isActive)
        assertFalse(loginVm.viewModelScope.isActive)
    }

    @Test
    fun `post-dispose operations do not leave loading spinners active or launch work`() {
        val coreVm = CoreLoginViewModel()
        coreVm.dispose()
        coreVm.sendMagicLink("test@example.com") { }
        assertFalse(coreVm.isLoading.value)

        val passkeyVm = PasskeyAuthViewModel()
        passkeyVm.dispose()
        passkeyVm.authenticateWithEmailAndPasskey("test@example.com") { }
        assertFalse(passkeyVm.isLoading.value)

        val authMgr = AuthOptionsManager()
        authMgr.dispose()
        var reportedOptions: AuthOptions? = null
        authMgr.checkUserExists("test@example.com") { reportedOptions = it }
        assertFalse(authMgr.isLoading.value)
        assertTrue(reportedOptions is AuthOptions.Invalid)
    }
}
