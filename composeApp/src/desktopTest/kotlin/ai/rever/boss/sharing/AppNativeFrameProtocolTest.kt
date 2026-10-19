package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppNativeFrameProtocolTest {
    @Test
    fun `bounded BGRA frames preserve sequence and pixels`() {
        val pixels = byteArrayOf(3, 7, 11, -1, 19, 23, 29, -1)
        val (sequence, frame) = readAppNativeFrame(packet(payload = pixels), 2, 1, 8)
        assertEquals(9L, sequence)
        assertEquals(2, frame.width)
        assertEquals("BGRA", frame.format)
        assertContentEquals(pixels, frame.bgra)
    }

    @Test
    fun `BSC2 preserves premultiplied alpha while legacy BSC1 requires opaque pixels`() {
        val premultiplied = byteArrayOf(0, 0, 0, 0, 18, 26, 117, -128)
        val (sequence, frame) = readAppNativeFrame(packet(magic = 0x42534332, payload = premultiplied), 2, 1, 8)
        assertEquals(9L, sequence)
        assertEquals("BGRA", frame.format)
        assertContentEquals(premultiplied, frame.bgra)
        assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(payload = premultiplied), 2, 1, 8) }
    }

    @Test
    fun `both versions enforce geometry sizes sequence and complete payloads`() {
        for (magic in listOf(0x42534331, 0x42534332)) {
            assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(magic = magic, width = 3), 2, 1, 0) }
            assertFailsWith<IllegalStateException> {
                readAppNativeFrame(packet(magic = magic, size = Int.MAX_VALUE), 2, 1, 0)
            }
            assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(magic = magic, sequence = 8), 2, 1, 8) }
            assertFailsWith<java.io.EOFException> {
                readAppNativeFrame(packet(magic = magic, payload = byteArrayOf(1)), 2, 1, 0)
            }
        }
    }

    @Test
    fun `unsupported protocol versions are rejected instead of guessing alpha semantics`() {
        for (magic in listOf(0x42534330, 0x42534333, 0x425343FF)) {
            assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(magic = magic), 2, 1, 0) }
        }
    }

    @Test
    fun `reject changed dimensions oversized payload stale sequence and wrong protocol before pixels`() {
        assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(width = 3), 2, 1, 0) }
        assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(size = Int.MAX_VALUE), 2, 1, 0) }
        assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(sequence = 8), 2, 1, 8) }
        assertFailsWith<IllegalStateException> { readAppNativeFrame(packet(magic = 0), 2, 1, 0) }
        assertFailsWith<IllegalArgumentException> { readAppNativeFrame(packet(), Int.MAX_VALUE, 1, 0) }
    }

    @Test
    fun `truncated pixel payload is terminal`() {
        assertFailsWith<java.io.EOFException> { readAppNativeFrame(packet(payload = byteArrayOf(1)), 2, 1, 0) }
    }

    @Test
    fun `helper geometry is source bound and rejects impossible content bounds`() {
        val geometry = WindowCaptureGeometry(42, 320, 240, 320, 240, 0, 0, AppCaptureInsets(38, 38, 59, 38))
        assertEquals(listOf("320", "240", "38", "38", "59", "38"), nativeCaptureGeometryArguments(42, geometry))
        assertEquals(emptyList(), nativeCaptureGeometryArguments(42, null))
        assertFailsWith<IllegalArgumentException> { nativeCaptureGeometryArguments(43, geometry) }
        assertFailsWith<IllegalArgumentException> {
            nativeCaptureGeometryArguments(42, geometry.copy(insets = AppCaptureInsets(left = -1)))
        }
        assertFailsWith<IllegalArgumentException> {
            nativeCaptureGeometryArguments(42, geometry.copy(insets = AppCaptureInsets(top = 240)))
        }
        assertFailsWith<IllegalArgumentException> {
            nativeCaptureGeometryArguments(42, geometry.copy(width = Int.MAX_VALUE))
        }
    }

    @Test
    fun `only clean frame boundary with geometry exit can retry`() {
        val boundary =
            assertFailsWith<AppNativeFrameBoundaryEnd> {
                readAppNativeFrame(DataInputStream(ByteArrayInputStream(byteArrayOf())), 2, 1, 0)
            }
        assertEquals(true, nativeCaptureFailure(75, boundary) is AppNativeGeometryChangedException)
        assertEquals(false, nativeCaptureFailure(1, boundary) is AppNativeGeometryChangedException)
        assertEquals(false, nativeCaptureFailure(null, boundary) is AppNativeGeometryChangedException)
        val truncated =
            assertFailsWith<java.io.EOFException> {
                readAppNativeFrame(packet(payload = byteArrayOf(1)), 2, 1, 0)
            }
        assertEquals(false, nativeCaptureFailure(75, truncated) is AppNativeGeometryChangedException)
        val truncatedHeader =
            assertFailsWith<java.io.EOFException> {
                readAppNativeFrame(DataInputStream(ByteArrayInputStream(byteArrayOf(0x42))), 2, 1, 0)
            }
        assertEquals(false, nativeCaptureFailure(75, truncatedHeader) is AppNativeGeometryChangedException)
        assertEquals(
            false,
            nativeCaptureFailure(75, IllegalStateException("Malformed protocol")) is AppNativeGeometryChangedException,
        )
    }

    private fun packet(
        magic: Int = 0x42534331,
        width: Int = 2,
        size: Int = 8,
        sequence: Long = 9,
        payload: ByteArray = byteArrayOf(0, 0, 0, -1, 0, 0, 0, -1),
    ): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(magic)
            it.writeInt(width)
            it.writeInt(1)
            it.writeInt(size)
            it.writeLong(sequence)
            it.write(payload)
        }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }
}
