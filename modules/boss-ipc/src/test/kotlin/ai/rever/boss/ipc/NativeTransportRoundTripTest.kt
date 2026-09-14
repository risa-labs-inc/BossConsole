@file:Suppress("DEPRECATION")

package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.services.EventBusServiceImpl
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.MetadataUtils
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Loads the platform's native artifact and exercises gRPC's HTTP/2 stack over a real Unix socket. */
class NativeTransportRoundTripTest {
    @Test
    fun `native transport carries a grpc round trip`() =
        runBlocking {
            val os = System.getProperty("os.name").lowercase()
            val mac = os.contains("mac")
            assumeTrue("Unix transport is used on macOS and Linux", mac || os.contains("linux"))
            // Keep below macOS's socket-path limit even when java.io.tmpdir is a long runner path.
            val directory = Files.createTempDirectory(Path.of("/tmp"), "boss-uds-")
            val socket = directory.resolve("rpc.sock")
            var group: EventLoopGroup? = null
            var server: Server? = null
            var channel: ManagedChannel? = null
            try {
                // Do not skip when a native library fails to load: that is the regression this test guards.
                group = if (mac) KQueueEventLoopGroup(1) else EpollEventLoopGroup(1)
                val address = DomainSocketAddress(socket.toFile())
                server =
                    NettyServerBuilder
                        .forAddress(address)
                        .channelType(
                            if (mac) {
                                KQueueServerDomainSocketChannel::class.java
                            } else {
                                EpollServerDomainSocketChannel::class.java
                            },
                        ).bossEventLoopGroup(group)
                        .workerEventLoopGroup(group)
                        .addService(EventBusServiceImpl())
                        .build()
                        .start()
                channel =
                    NettyChannelBuilder
                        .forAddress(address)
                        .channelType(
                            if (mac) KQueueDomainSocketChannel::class.java else EpollDomainSocketChannel::class.java,
                        ).eventLoopGroup(group)
                        .usePlaintext()
                        .build()
                val metadata = Metadata()
                metadata.put(Metadata.Key.of("boss-process-token", Metadata.ASCII_STRING_MARSHALLER), "a".repeat(64))
                val response =
                    EventBusServiceGrpcKt
                        .EventBusServiceCoroutineStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .publish(EventEnvelope.newBuilder().setEventType("NativeTransportProbe").build())
                assertTrue(response.success)
            } finally {
                channel?.shutdownNow()?.awaitTermination(5, TimeUnit.SECONDS)
                server?.shutdownNow()?.awaitTermination(5, TimeUnit.SECONDS)
                group?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
                Files.deleteIfExists(socket)
                Files.deleteIfExists(directory)
            }
        }
}
