package ai.rever.boss.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginViewModelScopeLeakTest {

    @BeforeEach
    fun setup() {
        // Dispatchers.Main must be set for view model scopes using Dispatchers.Main
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `dispose cancels all child component scopes and the parent scope`() {
        // 1. Instantiate the facade view model
        val viewModel = LoginViewModel()

        // 2. Verify everything is active initially
        assertTrue(viewModel.scopeForTesting.isActive, "Parent scope should be active")
        assertTrue(viewModel.coreLoginViewModel.scopeForTesting.isActive, "Core scope should be active")
        assertTrue(viewModel.passkeyAuthViewModel.scopeForTesting.isActive, "Passkey scope should be active")
        assertTrue(viewModel.authOptionsManager.scopeForTesting.isActive, "AuthOptions scope should be active")

        // 3. Trigger the disposal lifecycle (as done by AuthScreenContainer)
        viewModel.dispose()

        // 4. Assert everything is cancelled, preventing scope leaks on sign-in
        assertFalse(viewModel.scopeForTesting.isActive, "Parent scope should be cancelled")
        assertFalse(viewModel.coreLoginViewModel.scopeForTesting.isActive, "Core scope should be cancelled")
        assertFalse(viewModel.passkeyAuthViewModel.scopeForTesting.isActive, "Passkey scope should be cancelled")
        assertFalse(viewModel.authOptionsManager.scopeForTesting.isActive, "AuthOptions scope should be cancelled")
    }
}
