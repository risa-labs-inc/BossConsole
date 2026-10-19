package ai.rever.boss.ipc

import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import java.util.concurrent.TimeUnit

/**
 * Transport-level abuse bounds for the microkernel IPC channel.
 *
 * WHY: the IPC endpoint is reachable by every process on the machine. The pinned TLS key proves
 * the endpoint and the per-call token proves identity, but neither limits how much a peer may
 * demand from the transport itself. gRPC happens to ship defaults for the message and metadata
 * caps, but nothing in this repository pinned them, and two dimensions were left completely
 * unbounded: one connection could hold unlimited concurrent streams (per-stream handler work
 * and memory, library default Integer.MAX_VALUE), and a connection with no RPCs in flight was
 * never closed (idle eviction is disabled by default), so a peer that connects and then goes
 * silent keeps its slot forever.
 *
 * All bounds use refuse semantics. Oversize requests and responses fail with RESOURCE_EXHAUSTED
 * before any service handler runs. Excess concurrent streams are refused; well-behaved clients
 * additionally see the cap advertised in HTTP/2 SETTINGS and queue locally instead of flooding.
 * A connection idle for [maxConnectionIdleMillis] is closed with a graceful GO_AWAY that
 * clients survive by reconnecting on demand.
 *
 * Deliberately not bounded here: total connection age, because PluginUI and state watch
 * streams are designed to stay open indefinitely and active RPCs already exempt a connection
 * from the idle cap; and keepalive ping rates, which the library already polices. A peer that
 * connects but never sends a byte holds only a socket descriptor, since without a TLS handshake
 * no handler state or worker involvement exists, and Netty's handshake timeout rejects peers
 * that start a handshake and then stall.
 */
class IpcTransportLimits(
    /** Cap on one request's serialized size. Larger frames are refused with RESOURCE_EXHAUSTED. */
    val maxInboundMessageBytes: Int = DEFAULT_MAX_INBOUND_MESSAGE_BYTES,
    /** Cap on one request's headers. The process token is a few hundred bytes. */
    val maxInboundMetadataBytes: Int = DEFAULT_MAX_INBOUND_METADATA_BYTES,
    /** Cap on streams a single connection may run concurrently. Excess streams are refused. */
    val maxConcurrentCallsPerConnection: Int = DEFAULT_MAX_CONCURRENT_CALLS_PER_CONNECTION,
    /** A connection with no RPCs in flight for this long is closed with a graceful GO_AWAY. */
    val maxConnectionIdleMillis: Long = DEFAULT_MAX_CONNECTION_IDLE_MILLIS,
) {
    init {
        require(maxInboundMessageBytes > 0) { "maxInboundMessageBytes must be positive" }
        require(maxInboundMetadataBytes > 0) { "maxInboundMetadataBytes must be positive" }
        require(maxConcurrentCallsPerConnection > 0) { "maxConcurrentCallsPerConnection must be positive" }
        // NettyServerBuilder.maxConnectionIdle clamps any window shorter than one second up to
        // one second, so a value below MIN_CONNECTION_IDLE_MILLIS would not fail here yet would
        // silently widen on the transport.
        require(maxConnectionIdleMillis >= MIN_CONNECTION_IDLE_MILLIS) {
            "maxConnectionIdleMillis must be at least $MIN_CONNECTION_IDLE_MILLIS; " +
                "grpc-netty clamps shorter windows up to one second"
        }
    }

    /** The reader bounds, applied to the kernel side of the socket. */
    internal fun applyToServer(builder: NettyServerBuilder): NettyServerBuilder =
        builder
            .maxInboundMessageSize(maxInboundMessageBytes)
            .maxInboundMetadataSize(maxInboundMetadataBytes)
            .maxConcurrentCallsPerConnection(maxConcurrentCallsPerConnection)
            .maxConnectionIdle(maxConnectionIdleMillis, TimeUnit.MILLISECONDS)

    /** Response messages and metadata must obey the same bounds as requests. */
    internal fun applyToChannel(builder: NettyChannelBuilder): NettyChannelBuilder =
        builder
            .maxInboundMessageSize(maxInboundMessageBytes)
            .maxInboundMetadataSize(maxInboundMetadataBytes)

    companion object {
        /** Matches io.grpc's wire default, so pinning it changes nothing for well-formed traffic. */
        const val DEFAULT_MAX_INBOUND_MESSAGE_BYTES: Int = 4 * 1024 * 1024

        /** Matches io.grpc's default header budget. */
        const val DEFAULT_MAX_INBOUND_METADATA_BYTES: Int = 8 * 1024

        /** Generous for legitimate children, which hold a handful of streams each. */
        const val DEFAULT_MAX_CONCURRENT_CALLS_PER_CONNECTION: Int = 128

        /** Two minutes without any RPC in flight closes the connection; live RPCs keep it open. */
        const val DEFAULT_MAX_CONNECTION_IDLE_MILLIS: Long = 120_000

        /**
         * The effective floor of the idle window: NettyServerBuilder.maxConnectionIdle clamps any
         * shorter duration up to one second, so anything below this is not a valid pin.
         */
        const val MIN_CONNECTION_IDLE_MILLIS: Long = 1_000
    }
}
