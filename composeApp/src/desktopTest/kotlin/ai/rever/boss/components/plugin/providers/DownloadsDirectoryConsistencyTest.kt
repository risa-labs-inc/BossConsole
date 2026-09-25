package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.ipc.FileSystemDataProviderProxy
import io.grpc.ManagedChannelBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The out-of-process proxy answers getDownloadsDirectory() locally instead of asking
 * the host, so a plugin is told the same folder only if both sides compute it the
 * same way. A plugin must not see a different Downloads folder depending on whether
 * it was loaded in-process or out-of-process.
 */
class DownloadsDirectoryConsistencyTest {
    @Test
    fun `in-process and out-of-process plugins are told the same downloads directory`() {
        // Never connected: getDownloadsDirectory() is a local query on the proxy.
        val channel = ManagedChannelBuilder.forAddress("localhost", 1).usePlaintext().build()
        try {
            val inProcess = FileSystemDataProviderImpl().getDownloadsDirectory()
            val outOfProcess = FileSystemDataProviderProxy(channel, channel).getDownloadsDirectory()

            assertEquals(inProcess, outOfProcess)
        } finally {
            channel.shutdownNow()
        }
    }
}
