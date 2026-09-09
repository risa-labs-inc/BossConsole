package ai.rever.boss.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCapturePermissionsTest {
    private class Binding(
        val preflight: () -> Boolean = { true },
        val request: () -> Boolean = { true },
    ) : CoreGraphics {
        override fun CGPreflightScreenCaptureAccess(): Boolean = preflight()

        override fun CGRequestScreenCaptureAccess(): Boolean = request()
    }

    @Test
    fun `non mac platforms never load native binding`() {
        val permissions = ScreenCapturePermissions(false) { error("Must not load CoreGraphics") }
        assertTrue(permissions.hasPermission())
        assertTrue(permissions.requestPermission())
    }

    @Test
    fun `missing binding fails closed`() {
        val permissions = ScreenCapturePermissions(true) { null }
        assertFalse(permissions.hasPermission())
        assertFalse(permissions.requestPermission())
    }

    @Test
    fun `failed native initialization is lazy cached and fails closed`() {
        for (failure in listOf(UnsatisfiedLinkError("missing library"), NoClassDefFoundError("missing JNA"))) {
            var attempts = 0
            val permissions =
                ScreenCapturePermissions(true) {
                    attempts++
                    throw failure
                }
            assertEquals(0, attempts)
            repeat(2) {
                assertFalse(permissions.hasPermission())
                assertFalse(permissions.requestPermission())
            }
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `invocation failures return false for both permission operations`() {
        for (failure in listOf(UnsatisfiedLinkError("missing symbol"), IllegalStateException("native failure"))) {
            val permissions = ScreenCapturePermissions(true) { Binding({ throw failure }, { throw failure }) }
            assertFalse(permissions.hasPermission())
            assertFalse(permissions.requestPermission())
        }
    }

    @Test
    fun `preflight never requests permission and observes changed permission state`() {
        var granted = false
        var requests = 0
        val permissions =
            ScreenCapturePermissions(true) {
                Binding(
                    preflight = { granted },
                    request = {
                        requests++
                        granted
                    },
                )
            }
        assertFalse(permissions.hasPermission())
        assertEquals(0, requests)
        assertFalse(permissions.requestPermission())
        granted = true
        assertTrue(permissions.hasPermission())
        assertTrue(permissions.requestPermission())
        assertEquals(2, requests)
    }
}
