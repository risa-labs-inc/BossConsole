package ai.rever.boss.plugin.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class OverlayRegistryWriteOnceTest {
    @BeforeEach
    @AfterEach
    fun reset() {
        listOf("useHeavyweightOverlays", "modalRenderer", "popupRenderer", "diagnostics").forEach {
            resetOverlayFieldForTest(it)
        }
        BossOverlayHost::class.java.getDeclaredField("useHeavyweightOverlays").apply {
            isAccessible = true
            setBoolean(null, false)
        }
        BossOverlayHost.openHeavyweightPopups = 0
    }

    @Test
    fun `independent startup fields register and reject later replacement or clearing`() {
        val messages = mutableListOf<String>()
        BossOverlayHost.diagnostics = { messages += it }
        val diagnostic = BossOverlayHost.diagnostics
        BossOverlayHost.useHeavyweightOverlays = false
        BossOverlayHost.modalRenderer = { _, _, _ -> }
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> }
        val modal = BossOverlayHost.modalRenderer
        val popup = BossOverlayHost.popupRenderer
        assertNotNull(modal)
        assertNotNull(popup)

        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = null
        BossOverlayHost.popupRenderer = null
        BossOverlayHost.diagnostics = null

        assertFalse(BossOverlayHost.useHeavyweightOverlays)
        assertSame(modal, BossOverlayHost.modalRenderer)
        assertSame(popup, BossOverlayHost.popupRenderer)
        assertSame(diagnostic, BossOverlayHost.diagnostics)
        assertEquals(4, messages.size)
        BossOverlayHost.openHeavyweightPopups++
        BossOverlayHost.openHeavyweightPopups--
        assertEquals(0, BossOverlayHost.openHeavyweightPopups)
    }
}
