package ai.rever.boss.plugin.sandbox.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [PluginToastState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginToastStateTest {

    private lateinit var testScope: TestScope
    private lateinit var toastState: PluginToastState

    @BeforeEach
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        toastState = PluginToastState(testScope, maxToasts = 3)
    }

    @Nested
    inner class ShowTests {

        @Test
        fun `show adds toast to list`() = testScope.runTest {
            val message = ToastMessage(
                type = ToastType.INFO,
                title = "Test",
                message = "Test message"
            )

            toastState.show(message)

            assertEquals(1, toastState.toasts.value.size)
            assertEquals("Test", toastState.toasts.value[0].title)
        }

        @Test
        fun `show respects maxToasts limit`() = testScope.runTest {
            repeat(5) { i ->
                toastState.show(
                    ToastMessage(
                        type = ToastType.INFO,
                        title = "Toast $i",
                        message = "Message $i"
                    )
                )
            }

            assertEquals(3, toastState.toasts.value.size)
            // Should keep the last 3 toasts
            assertEquals("Toast 2", toastState.toasts.value[0].title)
            assertEquals("Toast 3", toastState.toasts.value[1].title)
            assertEquals("Toast 4", toastState.toasts.value[2].title)
        }

        @Test
        fun `SHORT duration auto-dismisses after 3 seconds`() = testScope.runTest {
            toastState.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = "Test",
                    message = "Test",
                    duration = ToastDuration.SHORT
                )
            )

            assertEquals(1, toastState.toasts.value.size)

            advanceTimeBy(3001)

            assertEquals(0, toastState.toasts.value.size)
        }

        @Test
        fun `LONG duration auto-dismisses after 6 seconds`() = testScope.runTest {
            toastState.show(
                ToastMessage(
                    type = ToastType.WARNING,
                    title = "Test",
                    message = "Test",
                    duration = ToastDuration.LONG
                )
            )

            advanceTimeBy(3001)
            assertEquals(1, toastState.toasts.value.size) // Still there after 3s

            advanceTimeBy(3001)
            assertEquals(0, toastState.toasts.value.size) // Gone after 6s
        }

        @Test
        fun `INDEFINITE duration does not auto-dismiss`() = testScope.runTest {
            toastState.show(
                ToastMessage(
                    type = ToastType.ERROR,
                    title = "Test",
                    message = "Test",
                    duration = ToastDuration.INDEFINITE
                )
            )

            advanceTimeBy(60_000) // 1 minute

            assertEquals(1, toastState.toasts.value.size)
        }
    }

    @Nested
    inner class DismissTests {

        @Test
        fun `dismiss removes specific toast by id`() = testScope.runTest {
            val message1 = ToastMessage(
                id = "toast-1",
                type = ToastType.INFO,
                title = "Toast 1",
                message = "Message 1",
                duration = ToastDuration.INDEFINITE
            )
            val message2 = ToastMessage(
                id = "toast-2",
                type = ToastType.INFO,
                title = "Toast 2",
                message = "Message 2",
                duration = ToastDuration.INDEFINITE
            )

            toastState.show(message1)
            toastState.show(message2)
            assertEquals(2, toastState.toasts.value.size)

            toastState.dismiss("toast-1")

            assertEquals(1, toastState.toasts.value.size)
            assertEquals("toast-2", toastState.toasts.value[0].id)
        }

        @Test
        fun `dismiss is safe for unknown id`() = testScope.runTest {
            toastState.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = "Test",
                    message = "Test"
                )
            )

            // Should not throw
            toastState.dismiss("unknown-id")

            assertEquals(1, toastState.toasts.value.size)
        }

        @Test
        fun `dismiss cancels auto-dismiss timer`() = testScope.runTest {
            val message = ToastMessage(
                id = "toast-1",
                type = ToastType.INFO,
                title = "Test",
                message = "Test",
                duration = ToastDuration.SHORT
            )

            toastState.show(message)
            toastState.dismiss("toast-1")

            // Advance past the auto-dismiss time
            advanceTimeBy(4000)

            // Should still be empty (no error from trying to dismiss twice)
            assertEquals(0, toastState.toasts.value.size)
        }

        @Test
        fun `dismissAll clears all toasts`() = testScope.runTest {
            repeat(3) { i ->
                toastState.show(
                    ToastMessage(
                        type = ToastType.INFO,
                        title = "Toast $i",
                        message = "Message $i",
                        duration = ToastDuration.INDEFINITE
                    )
                )
            }
            assertEquals(3, toastState.toasts.value.size)

            toastState.dismissAll()

            assertEquals(0, toastState.toasts.value.size)
        }
    }

    @Nested
    inner class UtilityTests {

        @Test
        fun `hasToasts returns true when toasts exist`() = testScope.runTest {
            assertFalse(toastState.hasToasts())

            toastState.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = "Test",
                    message = "Test",
                    duration = ToastDuration.INDEFINITE
                )
            )

            assertTrue(toastState.hasToasts())
        }

        @Test
        fun `toastCount returns correct count`() = testScope.runTest {
            assertEquals(0, toastState.toastCount())

            toastState.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = "Test 1",
                    message = "Test",
                    duration = ToastDuration.INDEFINITE
                )
            )
            assertEquals(1, toastState.toastCount())

            toastState.show(
                ToastMessage(
                    type = ToastType.INFO,
                    title = "Test 2",
                    message = "Test",
                    duration = ToastDuration.INDEFINITE
                )
            )
            assertEquals(2, toastState.toastCount())
        }
    }

    @Nested
    inner class ToastMessageTests {

        @Test
        fun `ToastMessage has default id`() {
            val message = ToastMessage(
                type = ToastType.INFO,
                title = "Test",
                message = "Test"
            )

            assertTrue(message.id.isNotEmpty())
        }

        @Test
        fun `ToastAction stores label and callback`() {
            var clicked = false
            val action = ToastAction("Click me") { clicked = true }

            assertEquals("Click me", action.label)

            action.onClick()

            assertTrue(clicked)
        }
    }
}
