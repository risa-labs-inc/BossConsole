package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The channel speaks newline-delimited frames and the `OPEN` verb is the one
 * place a caller-controlled string — a URL — is written into the stream. These
 * cases pin down that a URL which cannot occupy a single line is refused
 * before it is ever framed, and that a stray second line is never a request.
 */
class SingleInstanceOpenFramingTest {
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

    @Test
    fun `an url that cannot occupy one framed line is never sent`() {
        val token = "c".repeat(TOKEN_HEX_LENGTH)

        // The framing is newline-delimited, so a raw line break in the URL would
        // smuggle a second request into the stream; the formatter must refuse it.
        assertNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, "boss://x\n$VERB_OPEN evil"))
        assertNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, "boss://x\r\n$VERB_PING"))
        assertNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, "boss://x\tevil"))
        assertNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, " "))

        // The URL has its own byte budget, well under the whole-request one.
        val oversizedUrl = "boss://url?url=" + "a".repeat(MAX_FORWARD_URL_BYTES)
        assertNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, oversizedUrl))

        // A percent-encoded newline is ordinary URL content and still frames.
        assertNotNull(formatOpenRequest(token, DeepLinkOrigin.EXTERNAL, "boss://url?url=a%0Ab"))
    }

    @Test
    fun `a control character mid-line never parses into an open request`() {
        val token = "d".repeat(TOKEN_HEX_LENGTH)
        // The URL is the rest of the line, so a line break cannot survive the
        // read — but a hand-rolled sender can still embed a raw CR or other
        // control character mid-line. That is a malformed frame, not a link.
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token $VERB_OPEN EXTERNAL boss://x\revil"))
        assertNull(parseRequestLine("$PROTOCOL_VERSION $token $VERB_OPEN EXTERNAL boss://x\tevil"))
    }

    @Test
    fun `an injected second line is dropped by the framing, never forwarded`() {
        assertTrue(SingleInstanceManager.acquireLock())
        val descriptor = assertNotNull(readPublishedDescriptor())

        // The hostile URL never reaches the channel: sendToExistingInstance
        // refuses it before connecting, and nothing answers a stray second line.
        assertFalse(SingleInstanceManager.sendToExistingInstance("boss://x\n$VERB_OPEN evil"))

        // Even a raw injected second line is not a request: the reader takes one
        // line per connection and the channel stays healthy afterwards.
        val injected =
            "$PROTOCOL_VERSION ${descriptor.token} $VERB_OPEN EXTERNAL boss://split\n" +
                "$PROTOCOL_VERSION ${descriptor.token} $VERB_STATUS"
        assertEquals(RESPONSE_OK, exchange(descriptor, injected))
        assertEquals(RESPONSE_PONG, exchange(descriptor, formatPingRequest(descriptor.token)))
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
}

/**
 * A formatted `OPEN` request line for a URL the test knows is well-formed —
 * [formatOpenRequest] also answers null, which a well-formed fixture never is.
 */
internal fun openRequestLine(
    token: String,
    origin: DeepLinkOrigin,
    url: String,
): String = assertNotNull(formatOpenRequest(token, origin, url))
