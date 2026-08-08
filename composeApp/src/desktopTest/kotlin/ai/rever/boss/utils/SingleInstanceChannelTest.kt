package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the single-instance channel: the descriptor it publishes, the wire
 * format, and the live behaviour a second launch depends on.
 *
 * The channel is what a second launch hands its URL to — including the auth
 * callback — so the cases here are the ones whose regression would be quiet: a
 * caller that presents no token being listened to, a descriptor nothing answers
 * on stopping the app from starting, and a token reaching a log line.
 */
class SingleInstanceChannelTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun useTempRuntimeDir() {
        SingleInstanceManager.runtimeDirOverride = File(tempDir.toFile(), "run")
        SingleInstanceManager.llmTokenProviderOverride = null
    }

    @AfterEach
    fun releaseChannel() {
        SingleInstanceManager.release()
        SingleInstanceManager.llmTokenProviderOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    // ==================== Descriptor ====================

    @Test
    fun `a descriptor round-trips through its file format`() {
        val descriptor =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "56789",
                token = "a".repeat(TOKEN_HEX_LENGTH),
            )
        assertEquals(descriptor, parseInstanceDescriptor(descriptor.encode()))
    }

    @Test
    fun `a descriptor never renders its token`() {
        val token = "0123456789abcdef".repeat(4)
        val rendered = InstanceDescriptor(SingleInstanceTransport.UNIX, "/tmp/x.sock", token).toString()
        assertFalse(rendered.contains(token), "toString must not carry the token: $rendered")
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun `an unusable descriptor is not parsed into one`() {
        val token = "b".repeat(TOKEN_HEX_LENGTH)
        assertNull(parseInstanceDescriptor(""))
        assertNull(parseInstanceDescriptor("version=2\ntransport=TCP\nendpoint=1\ntoken=$token"))
        assertNull(parseInstanceDescriptor("version=1\ntransport=SMOKE\nendpoint=1\ntoken=$token"))
        assertNull(parseInstanceDescriptor("version=1\ntransport=TCP\nendpoint=\ntoken=$token"))
        // A short token would be a guessable one.
        assertNull(parseInstanceDescriptor("version=1\ntransport=TCP\nendpoint=1\ntoken=short"))
        assertNull(parseInstanceDescriptor("version=1\ntransport=TCP\nendpoint=1"))
    }

    // ==================== Wire format ====================

    @Test
    fun `a request line round-trips, keeping the url whole`() {
        val token = "c".repeat(TOKEN_HEX_LENGTH)
        val url = "boss://auth/verify#access_token=abc&type=recovery"

        val open = assertNotNull(parseRequestLine(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, url)))
        assertEquals(token, open.token)
        assertEquals(VERB_OPEN, open.verb)
        assertEquals(DeepLinkOrigin.EXTERNAL, open.origin)
        assertEquals(url, open.url)

        val ping = assertNotNull(parseRequestLine(formatPingRequest(token)))
        assertEquals(VERB_PING, ping.verb)
        assertNull(ping.url)

        val llmToken = assertNotNull(parseRequestLine(formatLlmTokenRequest(token)))
        assertEquals(VERB_LLM_TOKEN, llmToken.verb)
        assertNull(llmToken.url)

        // A URL with a space in it must survive rather than being cut short.
        val spacedUrl = "boss://url?url=a b"
        val spaced = assertNotNull(parseRequestLine(formatOpenRequest(token, DeepLinkOrigin.OPERATOR_CLI, spacedUrl)))
        assertEquals(spacedUrl, spaced.url)
        assertEquals(DeepLinkOrigin.OPERATOR_CLI, spaced.origin)
    }

    @Test
    fun `a malformed or foreign request line is not parsed into one`() {
        val token = "d".repeat(TOKEN_HEX_LENGTH)
        assertNull(parseRequestLine(""))
        assertNull(parseRequestLine("boss://terminal?command=id"))
        assertNull(parseRequestLine("other-protocol $token $VERB_PING"))
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token SHUTDOWN"))
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token $VERB_OPEN"))
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token $VERB_PING extra"))
    }

    @Test
    fun `an unstated or unknown origin on the wire is external`() {
        assertEquals(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.fromWireLabel(null))
        assertEquals(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.fromWireLabel(""))
        assertEquals(DeepLinkOrigin.EXTERNAL, DeepLinkOrigin.fromWireLabel("TRUSTED"))
        assertEquals(DeepLinkOrigin.OPERATOR_CLI, DeepLinkOrigin.fromWireLabel("operator_cli"))
    }

    // ==================== Live channel ====================

    @Test
    fun `acquiring publishes an owner-only descriptor and answers on the channel`() {
        assertTrue(SingleInstanceManager.acquireLock())

        val descriptor = assertNotNull(readPublishedDescriptor())
        assertEquals(TOKEN_HEX_LENGTH, descriptor.token.length)
        assertOwnerOnly(descriptorPath())
        assertOwnerOnly(runtimeDirPath())

        // Where the platform has Unix-domain sockets and the path fits, that is
        // the transport: no port is published and the filesystem confines the
        // endpoint. Loopback TCP is the fallback, and the token carries the trust
        // either way.
        val socketPath = runtimeDirPath().resolve("single-instance.sock")
        val fitsUnixPath = socketPath.toString().length <= MAX_UNIX_SOCKET_PATH_LENGTH
        if (hasPosixPermissions(socketPath) && fitsUnixPath) {
            assertEquals(SingleInstanceTransport.UNIX, descriptor.transport)
            assertOwnerOnly(socketPath)
        }

        // Answering the probe is what "another instance is running" means now.
        assertTrue(SingleInstanceManager.isAnotherInstanceRunning())
    }

    @Test
    fun `a caller presenting the token is acted on, one presenting none is refused`() {
        assertTrue(SingleInstanceManager.acquireLock())
        val descriptor = assertNotNull(readPublishedDescriptor())

        assertEquals(RESPONSE_PONG, exchange(descriptor, formatPingRequest(descriptor.token)))

        // Everything a caller without the token might try: a token of its own,
        // a near-miss of the real one, no protocol at all, and a forward that
        // claims the operator's own origin.
        val wrongToken = "e".repeat(TOKEN_HEX_LENGTH)
        val nearMiss = descriptor.token.dropLast(1) + if (descriptor.token.last() == '0') '1' else '0'
        assertEquals(RESPONSE_REJECTED, exchange(descriptor, formatPingRequest(wrongToken)))
        assertEquals(RESPONSE_REJECTED, exchange(descriptor, formatPingRequest(nearMiss)))
        assertEquals(RESPONSE_REJECTED, exchange(descriptor, "boss://terminal?command=id"))
        assertEquals(
            RESPONSE_REJECTED,
            exchange(descriptor, formatOpenRequest(wrongToken, DeepLinkOrigin.OPERATOR_CLI, "boss://split")),
        )
    }

    @Test
    fun `a caller cannot make the channel buffer without limit`() {
        assertTrue(SingleInstanceManager.acquireLock())
        val descriptor = assertNotNull(readPublishedDescriptor())

        // A request well past the read budget. The read gives up rather than
        // growing, so the request is never acted on — the connection either
        // comes back refused or is simply dropped.
        val padding = "a".repeat(20 * 1024)
        val oversized = formatOpenRequest(descriptor.token, DeepLinkOrigin.EXTERNAL, "boss://url?url=$padding")
        assertNotEquals(RESPONSE_OK, exchangeTolerantly(descriptor, oversized))

        // The channel is still serving afterwards.
        assertEquals(RESPONSE_PONG, exchange(descriptor, formatPingRequest(descriptor.token)))
    }

    @Test
    fun `a second launch cannot take over and forwards instead`() {
        assertTrue(SingleInstanceManager.acquireLock())
        val firstDescriptor = assertNotNull(readPublishedDescriptor())

        // The channel is live, so a second attempt must decline rather than
        // rebind and orphan the first instance's endpoint.
        assertFalse(SingleInstanceManager.acquireLock())
        assertEquals(firstDescriptor, readPublishedDescriptor())

        // Forwarding is the path a second launch takes instead, and the one the
        // auth callback depends on.
        assertTrue(SingleInstanceManager.sendToExistingInstance("boss://auth/verify#access_token=abc"))
        assertFalse(SingleInstanceManager.sendToExistingInstance("  "))
        assertFalse(SingleInstanceManager.sendToExistingInstance("ftp://example.com"))
    }

    @Test
    fun `a credential helper receives a token from the signed-in running instance`() {
        SingleInstanceManager.llmTokenProviderOverride = { Result.success("sk-short-lived-pilot") }
        assertTrue(SingleInstanceManager.acquireLock())

        val token = SingleInstanceManager.requestLlmToken().getOrThrow()

        assertEquals("sk-short-lived-pilot", token)
    }

    @Test
    fun `a credential helper gets a safe error without a running instance`() {
        val error = assertNotNull(SingleInstanceManager.requestLlmToken().exceptionOrNull())

        assertTrue(error.message.orEmpty().contains("Open BOSS"))
    }

    @Test
    fun `a descriptor nothing answers on is reclaimed instead of blocking startup`() {
        // A crashed instance leaves a descriptor behind. It names a live-looking
        // endpoint, but nothing is listening there.
        Files.createDirectories(runtimeDirPath())
        val stale =
            InstanceDescriptor(
                transport = SingleInstanceTransport.TCP,
                endpoint = "56798",
                token = "9".repeat(TOKEN_HEX_LENGTH),
            )
        Files.writeString(descriptorPath(), stale.encode())

        assertFalse(SingleInstanceManager.isAnotherInstanceRunning())
        assertTrue(SingleInstanceManager.acquireLock(), "a stale descriptor must not stop the app from starting")

        val published = assertNotNull(readPublishedDescriptor())
        assertNotEquals(stale.token, published.token, "the reclaimed channel must mint its own token")
    }

    @Test
    fun `releasing withdraws the descriptor so the next start is clean`() {
        assertTrue(SingleInstanceManager.acquireLock())
        assertTrue(Files.exists(descriptorPath()))

        SingleInstanceManager.release()

        assertFalse(Files.exists(descriptorPath()))
        assertFalse(SingleInstanceManager.isAnotherInstanceRunning())
        assertTrue(SingleInstanceManager.acquireLock())
    }

    // ==================== Helpers ====================

    private fun runtimeDirPath(): Path = File(tempDir.toFile(), "run").toPath()

    private fun descriptorPath(): Path = runtimeDirPath().resolve("single-instance")

    private fun readPublishedDescriptor(): InstanceDescriptor? =
        if (Files.exists(descriptorPath())) parseInstanceDescriptor(Files.readString(descriptorPath())) else null

    /**
     * Sends one raw line to the published endpoint and reads the reply, the way
     * any other local program would have to.
     */
    private fun exchange(
        descriptor: InstanceDescriptor,
        line: String,
    ): String? {
        val address =
            when (descriptor.transport) {
                SingleInstanceTransport.UNIX -> {
                    UnixDomainSocketAddress.of(descriptor.endpoint)
                }

                SingleInstanceTransport.TCP -> {
                    InetSocketAddress(InetAddress.getLoopbackAddress(), descriptor.endpoint.toInt())
                }
            }
        return SocketChannel.open(address).use { channel ->
            val output = Channels.newOutputStream(channel)
            output.write("$line\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            BufferedReader(InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8)).readLine()
        }
    }

    /**
     * Like [exchange], but describes a dropped connection instead of throwing: an
     * over-budget request may be answered with a refusal or simply cut off, and
     * either way it must not be acknowledged.
     */
    private fun exchangeTolerantly(
        descriptor: InstanceDescriptor,
        line: String,
    ): String? =
        try {
            exchange(descriptor, line)
        } catch (e: IOException) {
            "connection dropped: ${e.message}"
        }

    /**
     * Asserts nobody but the owner can reach [path]. Skipped where the filesystem
     * has no POSIX view (Windows), which relies on the profile directory's ACL.
     */
    private fun assertOwnerOnly(path: Path) {
        if (!hasPosixPermissions(path)) return

        val ownerOnly =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        val forOthers = Files.getPosixFilePermissions(path) - ownerOnly
        assertTrue(forOthers.isEmpty(), "$path grants $forOthers beyond its owner")
    }

    private fun hasPosixPermissions(path: Path) = path.fileSystem.supportedFileAttributeViews().contains("posix")
}
