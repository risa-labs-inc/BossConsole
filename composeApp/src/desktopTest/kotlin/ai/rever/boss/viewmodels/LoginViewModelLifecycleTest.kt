package ai.rever.boss.viewmodels

import ai.rever.boss.viewmodels.auth.AuthOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelLifecycleTest {
    @Test
    fun `dispose cancels child view model work`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val viewModel = LoginViewModel()
                var result: AuthOptions? = null

                viewModel.dispose()
                viewModel.checkUserExists("disposed@example.com") { result = it }
                advanceUntilIdle()

                assertNull(result)
            } finally {
                Dispatchers.resetMain()
            }
        }
}
