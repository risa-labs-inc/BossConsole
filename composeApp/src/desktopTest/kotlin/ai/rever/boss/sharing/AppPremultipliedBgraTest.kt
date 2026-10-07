package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AppPremultipliedBgraTest {
    @Test
    fun `clear holes preserve owner while translucent and opaque runs compose in order`() {
        val owner = pixels(200, 80, 20, 255, 200, 80, 20, 255, 200, 80, 20, 255, 200, 80, 20, 255)
        val overlay = pixels(0, 0, 0, 0, 10, 20, 100, 128, 30, 40, 50, 255, 60, 70, 80, 255)
        blendPremultipliedBgra(AppRawWindowFrame(overlay, 4, 1), owner, 4, 0, 0)
        assertContentEquals(pixels(200, 80, 20, 255, 110, 60, 110, 255, 30, 40, 50, 255, 60, 70, 80, 255), owner)
    }

    @Test
    fun `source over preserves partial destination alpha and saturates bright channels`() {
        val destination = pixels(20, 40, 60, 128, 240, 250, 255, 255)
        val overlay = pixels(10, 20, 30, 128, 240, 250, 255, 128)
        blendPremultipliedBgra(AppRawWindowFrame(overlay, 2, 1), destination, 2, 0, 0)
        assertContentEquals(pixels(20, 40, 60, 192, 255, 255, 255, 255), destination)
    }

    @Test
    fun `placement clips right and bottom edges without copying adjacent source row pixels`() {
        val destination = ByteArray(3 * 2 * 4)
        val overlay = pixels(10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255, 100, 110, 120, 255)
        blendPremultipliedBgra(AppRawWindowFrame(overlay, 2, 2), destination, 3, 2, 1)
        val expected = ByteArray(destination.size)
        pixels(10, 20, 30, 255).copyInto(expected, 5 * 4)
        assertContentEquals(expected, destination)
    }

    @Test
    fun `non BGRA or truncated frames cannot enter the compositor`() {
        assertFailsWith<IllegalArgumentException> {
            blendPremultipliedBgra(AppRawWindowFrame(ByteArray(6), 2, 2, "NV12"), ByteArray(16), 2, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            blendPremultipliedBgra(AppRawWindowFrame(ByteArray(3), 1, 1), ByteArray(4), 1, 0, 0)
        }
    }

    private fun pixels(vararg channels: Int): ByteArray = channels.map { it.toByte() }.toByteArray()
}
