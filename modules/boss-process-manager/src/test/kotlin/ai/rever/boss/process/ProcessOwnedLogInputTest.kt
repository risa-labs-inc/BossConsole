package ai.rever.boss.process

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessOwnedLogInputTest {
    @Test
    fun `final write before observed exit is included in the snapshot`() {
        val process = FinalWriteProcess()
        ProcessOwnedLogInput(process.inputStream, process).use { input ->
            assertEquals("final diagnostic", input.readBytes().decodeToString())
        }
    }

    private class FinalWriteProcess : Process() {
        private var bytes = ByteArrayInputStream(byteArrayOf())
        private var exited = false
        private val input =
            object : InputStream() {
                override fun available() = bytes.available()

                override fun read() = bytes.read()
            }

        override fun getInputStream() = input

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun isAlive(): Boolean {
            // Model the child completing its last write immediately before exit is observed.
            if (!exited) {
                bytes = ByteArrayInputStream("final diagnostic".encodeToByteArray())
                exited = true
            }
            return false
        }

        override fun waitFor() = 0

        override fun exitValue() = 0

        override fun destroy() = Unit
    }
}
