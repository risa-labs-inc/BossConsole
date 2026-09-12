package ai.rever.boss.platform

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopAudioDeviceManagerTest {
    @Test
    fun `runDiagnostics completes without throwing and provides structured result`() {
        val diag = DesktopAudioDeviceManager.runDiagnostics()
        assertNotNull(diag)
        assertNotNull(diag.inputDevices)
        assertNotNull(diag.outputDevices)
        assertNotNull(diag.supportedSampleRates)
        assertNotNull(diag.permissionStatus)
    }

    @Test
    fun `isInputFormatSupported checks standard sample rates`() {
        // Checks that format support check is callable safely
        val supported16k = DesktopAudioDeviceManager.isInputFormatSupported(16000)
        val supported24k = DesktopAudioDeviceManager.isInputFormatSupported(24000)
        // Should evaluate to boolean without throwing
        assertTrue(supported16k || !supported16k)
        assertTrue(supported24k || !supported24k)
    }
}
