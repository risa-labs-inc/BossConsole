package ai.rever.boss.viewmodels

import ai.rever.boss.services.supabase.CrossDeviceAuthenticationRequired
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial lifecycle tests for the PasskeyAuthViewModel auth state machine.
 * The passkey authentication seam is a non-cancellation-cooperative gate, matching
 * a downstream service that converts CancellationException into Result.failure.
 * Every failure mode is therefore driven deterministically instead of by sleeps:
 * a superseded attempt completing late, local cross-device identity retirement,
 * and an explicit cancel killing the in-flight completion path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyAuthViewModelSessionLifecycleTest {
    private data class PendingAttempt(
        val email: String,
        val credentialId: String?,
        val gate: CompletableDeferred<Result<Unit>>,
    )

    private val pendingAttempts = ArrayDeque<PendingAttempt>()

    private fun newViewModel() =
        PasskeyAuthViewModel { email, credentialId ->
            val gate = CompletableDeferred<Result<Unit>>()
            pendingAttempts.addLast(PendingAttempt(email, credentialId, gate))
            withContext(NonCancellable) { gate.await() }
        }

    private fun crossDeviceRequirement() =
        CrossDeviceAuthenticationRequired(
            qrCodeUrl = "https://auth.example/qr",
            challenge = "challenge-superseded",
            sessionId = "session-superseded",
        )

    @Test
    fun `a superseded attempt cannot fire onSuccess or clear the loading state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                var firstSuccess = 0
                var secondSuccess = 0
                viewModel.authenticateWithEmailAndPasskey("user@example.com") { firstSuccess++ }
                advanceUntilIdle()
                val superseded = pendingAttempts.removeFirst()
                assertEquals("user@example.com", superseded.email)
                assertNull(superseded.credentialId)
                assertTrue(viewModel.isLoading.value)

                viewModel.authenticateWithSpecificPasskey("user@example.com", "cred-1") { secondSuccess++ }
                advanceUntilIdle()
                val currentAttempt = pendingAttempts.removeFirst()
                assertEquals("user@example.com", currentAttempt.email)
                assertEquals("cred-1", currentAttempt.credentialId)

                // The current attempt must still be allowed to complete successfully.
                currentAttempt.gate.complete(Result.success(Unit))
                advanceUntilIdle()

                assertEquals(0, firstSuccess)
                assertEquals(1, secondSuccess)
                assertFalse(viewModel.isLoading.value)

                // The abandoned attempt may complete late, but it must remain inert.
                superseded.gate.complete(Result.success(Unit))
                advanceUntilIdle()

                assertEquals(0, firstSuccess)
                assertEquals(1, secondSuccess)
                assertFalse(viewModel.isLoading.value)
            } finally {
                pendingAttempts.forEach { it.gate.complete(Result.failure(Exception("test cleanup"))) }
                advanceUntilIdle()
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a superseded attempt cannot re-arm the cross-device QR dialog`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                val superseded = pendingAttempts.removeFirst()

                viewModel.authenticateWithSpecificPasskey("user@example.com", "cred-1") {}
                advanceUntilIdle()

                // The abandoned attempt surfaces a cross-device requirement late.
                superseded.gate.complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                pendingAttempts.forEach { it.gate.complete(Result.failure(Exception("test cleanup"))) }
                advanceUntilIdle()
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `dismissing the QR dialog retires the cross-device session identity`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                pendingAttempts.removeFirst().gate.complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()
                assertTrue(viewModel.showCrossDeviceQR.value)
                assertEquals("session-superseded", viewModel.crossDeviceSessionId.value)

                viewModel.dismissCrossDeviceQR()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `cancelling authentication retires the local cross-device identity`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                viewModel.authenticateWithEmailAndPasskey("user@example.com") {}
                advanceUntilIdle()
                pendingAttempts.removeFirst().gate.complete(Result.failure(crossDeviceRequirement()))
                advanceUntilIdle()
                assertTrue(viewModel.showCrossDeviceQR.value)

                viewModel.cancelAuthentication()

                assertFalse(viewModel.showCrossDeviceQR.value)
                assertNull(viewModel.crossDeviceQRUrl.value)
                assertNull(viewModel.crossDeviceChallenge.value)
                assertNull(viewModel.crossDeviceSessionId.value)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `an explicitly cancelled attempt cannot complete the login`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel()
            try {
                var success = 0
                viewModel.authenticateWithEmailAndPasskey("user@example.com") { success++ }
                advanceUntilIdle()
                val gate = pendingAttempts.removeFirst().gate

                viewModel.cancelAuthentication()
                assertFalse(viewModel.isLoading.value)

                // The cancelled attempt completes after the user backed out.
                gate.complete(Result.success(Unit))
                advanceUntilIdle()
                assertEquals(0, success)
            } finally {
                viewModel.dispose()
                Dispatchers.resetMain()
            }
        }
}
