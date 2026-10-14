package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalOutputBufferTest {
    @Test
    fun `slow reader gets an explicit gap instead of silently losing output`() =
        runBlocking {
            val output = TerminalOutputBuffer()
            val release = CompletableDeferred<Unit>()
            output.append(chunk())
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<StatusRuntimeException> {
                        output.stream().collect { release.await() }
                    }
                }
            repeat(300) { output.append(chunk()) }
            output.append(chunk().toBuilder().setIsExit(true).build())
            release.complete(Unit)
            assertEquals(Status.Code.OUT_OF_RANGE, received.await().status.code)
        }

    private fun chunk() = TerminalOutputChunk.newBuilder().setData(ByteString.copyFromUtf8("data")).build()
}
