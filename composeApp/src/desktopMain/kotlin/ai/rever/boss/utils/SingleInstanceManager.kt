package ai.rever.boss.utils

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val logger = BossLogger.forComponent("SingleInstanceManager")

/** Descriptor format marker; bumped if the fields below ever change meaning. */
private const val DESCRIPTOR_VERSION = "1"

private const val KEY_VERSION = "version"
private const val KEY_TRANSPORT = "transport"
private const val KEY_ENDPOINT = "endpoint"
private const val KEY_TOKEN = "token"

/** Wire protocol marker, first field of every request line. */
internal const val PROTOCOL_VERSION = "boss-si-1"

/** Asks the running instance to prove it is listening. */
internal const val VERB_PING = "PING"

/** Asks the running instance to process a URL. */
internal const val VERB_OPEN = "OPEN"

/** Asks the signed-in BOSS process for a short-lived RISA LLM credential. */
internal const val VERB_LLM_TOKEN = "LLM_TOKEN"

/** Asks the running instance for status and workspace info. */
internal const val VERB_STATUS = "STATUS"

/** Asks the running instance for registered MCP tools. */
internal const val VERB_MCP_LIST = "MCP_LIST"

/** Asks the running instance to invoke an MCP tool. */
internal const val VERB_MCP_INVOKE = "MCP_INVOKE"

internal const val RESPONSE_OK = "OK"
internal const val RESPONSE_PONG = "PONG"
internal const val RESPONSE_REJECTED = "REJECTED"
private const val RESPONSE_LLM_TOKEN_PREFIX = "LLM_TOKEN "
internal const val RESPONSE_STATUS_PREFIX = "STATUS "
internal const val RESPONSE_MCP_LIST_PREFIX = "MCP_LIST "
internal const val RESPONSE_MCP_INVOKE_PREFIX = "MCP_INVOKE "
private const val RESPONSE_ERROR_PREFIX = "ERROR "

/** 32 random bytes, hex encoded. */
private const val TOKEN_BYTES = 32
internal const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2

private const val IPC_PORT_BASE = 56789
private const val IPC_PORT_RANGE = 10 // Try ports 56789-56798
private const val TCP_BACKLOG = 5

private const val CONNECTION_TIMEOUT_MS = 10000L // 10 seconds - important for auth deep links
private const val LLM_TOKEN_TIMEOUT_MS = 90000L
private const val MCP_INVOKE_TIMEOUT_MS = 60000L

/**
 * Ceiling on a single request. Bounds what one caller can make the app buffer,
 * sized to accommodate Base64-encoded tool arguments and payloads.
 */
internal const val MAX_REQUEST_BYTES = 1024 * 1024

/** A response is one short word; nothing legitimate approaches this. */
private const val MAX_RESPONSE_BYTES = 256

/** Ceiling on data responses (status, MCP tool list, Base64 tool invocation output). */
private const val MAX_DATA_RESPONSE_BYTES = 4 * 1024 * 1024

/**
 * macOS caps a Unix-domain socket path at 104 bytes and Linux at 108. A home
 * directory long enough to breach that falls back to loopback TCP rather than
 * failing to start.
 */
internal const val MAX_UNIX_SOCKET_PATH_LENGTH = 100

private val ownerOnlyDirectoryPermissions =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

private val ownerOnlyFilePermissions =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

/** How the running instance can be reached. */
internal enum class SingleInstanceTransport {
    /** A Unix-domain socket inside the owner-only runtime directory. */
    UNIX,

    /** A loopback TCP port. Used where Unix-domain sockets are unavailable. */
    TCP,
}

/**
 * What the running instance published about itself: where to reach it, and the
 * token a caller must present to be listened to.
 *
 * [toString] omits the token so a descriptor can be logged; nothing else should
 * ever put [token] in a log line.
 */
internal data class InstanceDescriptor(
    val transport: SingleInstanceTransport,
    val endpoint: String,
    val token: String,
) {
    fun encode(): String =
        buildString {
            appendLine("$KEY_VERSION=$DESCRIPTOR_VERSION")
            appendLine("$KEY_TRANSPORT=${transport.name}")
            appendLine("$KEY_ENDPOINT=$endpoint")
            appendLine("$KEY_TOKEN=$token")
        }

    override fun toString(): String = "InstanceDescriptor(transport=$transport, endpoint=$endpoint, token=<redacted>)"
}

/**
 * Parses a descriptor written by [InstanceDescriptor.encode], returning null for
 * anything malformed, truncated or of another version — all of which mean "no
 * usable instance published here", and are handled by reclaiming the file.
 */
internal fun parseInstanceDescriptor(text: String): InstanceDescriptor? {
    val fields =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    if (fields[KEY_VERSION] != DESCRIPTOR_VERSION) return null

    val transport = SingleInstanceTransport.entries.firstOrNull { it.name == fields[KEY_TRANSPORT] }
    val endpoint = fields[KEY_ENDPOINT]?.takeIf { it.isNotBlank() }
    val token = fields[KEY_TOKEN]?.takeIf { it.length >= TOKEN_HEX_LENGTH }

    return if (transport != null && endpoint != null && token != null) {
        InstanceDescriptor(transport, endpoint, token)
    } else {
        null
    }
}

/**
 * One request read off the channel.
 *
 * @property token what the caller presented; compared against the live token
 *   before anything is acted on.
 * @property verb [VERB_PING], [VERB_OPEN], [VERB_LLM_TOKEN], [VERB_STATUS], [VERB_MCP_LIST], or [VERB_MCP_INVOKE].
 * @property origin for [VERB_OPEN], what the caller says the URL's provenance is.
 *   Unrecognised labels become [DeepLinkOrigin.EXTERNAL].
 * @property url for [VERB_OPEN], the URL to process; null for other verbs.
 * @property toolName for [VERB_MCP_INVOKE], the tool name to invoke.
 * @property argsJson for [VERB_MCP_INVOKE], the decoded JSON arguments string.
 */
internal data class SingleInstanceRequest(
    val token: String,
    val verb: String,
    val origin: DeepLinkOrigin,
    val url: String?,
    val toolName: String? = null,
    val argsJson: String? = null,
)

/**
 * Parses a request line, returning null for anything that is not a complete,
 * current-version request. A rejected line is never acted on.
 *
 * The line is `<protocol> <token> <verb> ...`
 */
internal fun parseRequestLine(line: String): SingleInstanceRequest? {
    val parts = line.trim().split(' ', limit = 5)
    if (parts.size < 3 || parts[0] != PROTOCOL_VERSION) return null

    val token = parts[1]
    return when (parts[2]) {
        VERB_PING, VERB_LLM_TOKEN, VERB_STATUS, VERB_MCP_LIST -> {
            if (parts.size == 3) {
                SingleInstanceRequest(token, parts[2], DeepLinkOrigin.EXTERNAL, null)
            } else {
                null
            }
        }

        VERB_OPEN -> {
            if (parts.size == 5) {
                SingleInstanceRequest(token, VERB_OPEN, DeepLinkOrigin.fromWireLabel(parts[3]), parts[4])
            } else {
                null
            }
        }

        VERB_MCP_INVOKE -> {
            if (parts.size >= 4) {
                val toolName = parts[3].trim()
                val base64Payload = if (parts.size == 5) parts[4].trim() else ""
                val decodedArgs =
                    try {
                        if (base64Payload.isNotEmpty()) {
                            String(java.util.Base64.getDecoder().decode(base64Payload), StandardCharsets.UTF_8)
                        } else {
                            "{}"
                        }
                    } catch (_: Exception) {
                        "{}"
                    }
                SingleInstanceRequest(token, VERB_MCP_INVOKE, DeepLinkOrigin.OPERATOR_CLI, null, toolName, decodedArgs)
            } else {
                null
            }
        }

        else -> {
            null
        }
    }
}

/** Builds the line [parseRequestLine] reads. Never log the result: it carries the token. */
internal fun formatOpenRequest(
    token: String,
    origin: DeepLinkOrigin,
    url: String,
): String = "$PROTOCOL_VERSION $token $VERB_OPEN ${origin.name} $url"

/** Builds a liveness probe line. Never log the result: it carries the token. */
internal fun formatPingRequest(token: String): String = "$PROTOCOL_VERSION $token $VERB_PING"

/** Builds a credential request. Never log the result: it carries the channel token. */
internal fun formatLlmTokenRequest(token: String): String = "$PROTOCOL_VERSION $token $VERB_LLM_TOKEN"

/** Builds a status query line. Never log the result: it carries the token. */
internal fun formatStatusRequest(token: String): String = "$PROTOCOL_VERSION $token $VERB_STATUS"

/** Builds an MCP tool list request line. Never log the result: it carries the token. */
internal fun formatMcpListRequest(token: String): String = "$PROTOCOL_VERSION $token $VERB_MCP_LIST"

/** Builds an MCP tool invocation line with Base64 payload. Never log the result: it carries the token. */
internal fun formatMcpInvokeRequest(
    token: String,
    toolName: String,
    argsJson: String,
): String {
    val base64Payload = java.util.Base64.getEncoder().encodeToString(argsJson.toByteArray(StandardCharsets.UTF_8))
    return "$PROTOCOL_VERSION $token $VERB_MCP_INVOKE $toolName $base64Payload"
}

private val secureRandom = SecureRandom()

/** Mints the channel token published in the descriptor. */
internal fun newChannelToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return HexFormat.of().formatHex(bytes)
}

/**
 * Compares tokens without leaking how far they matched.
 * [MessageDigest.isEqual] is the JDK's constant-time comparison.
 */
internal fun tokensMatch(
    expected: String,
    provided: String,
): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        provided.toByteArray(StandardCharsets.UTF_8),
    )

/** The schemes the app acts on when one instance forwards a URL to another. */
internal fun isForwardableUrl(url: String?): Boolean =
    url != null &&
        (url.startsWith("boss://") || url.startsWith("http://") || url.startsWith("https://"))

/**
 * Where the running instance keeps its own state: a directory under BOSS's
 * per-user data root, created owner-only.
 *
 * Deliberately not the system temp directory, which is shared between users on
 * Linux — the descriptor holds the channel token, and the endpoint it names is
 * where a forward (including the auth callback) gets delivered.
 */
private object SingleInstanceFiles {
    private const val RUNTIME_DIR_NAME = "run"
    private const val DESCRIPTOR_FILE_NAME = "single-instance"
    private const val SOCKET_FILE_NAME = "single-instance.sock"

    /** Pre-hardening lock file, which lived in the system temp directory. */
    private const val LEGACY_LOCK_FILE_NAME = "boss-instance.lock"

    /** Overridden by tests, so a test run never reads or writes the real `~/.boss`. */
    @Volatile
    var runtimeDirOverride: File? = null

    private val runtimeDir: File
        get() = runtimeDirOverride ?: BossDirectories.resolve(RUNTIME_DIR_NAME)

    val descriptorFile: File
        get() = File(runtimeDir, DESCRIPTOR_FILE_NAME)

    val socketFile: File
        get() = File(runtimeDir, SOCKET_FILE_NAME)

    /**
     * Creates the runtime directory owner-only, re-applying those permissions
     * every startup so a directory left behind by an earlier version is tightened
     * rather than trusted as it stands. Also clears the pre-hardening lock file,
     * which nothing reads any more.
     */
    fun prepare() {
        try {
            Files.createDirectories(runtimeDir.toPath())
            restrictToOwner(runtimeDir.toPath(), ownerOnlyDirectoryPermissions)
            Files.deleteIfExists(File(System.getProperty("java.io.tmpdir"), LEGACY_LOCK_FILE_NAME).toPath())
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Could not prepare the single-instance runtime directory", error = e)
        }
    }

    /**
     * Restricts [path] to its owner.
     *
     * On Windows there is no POSIX view; that per-user profile directory is
     * already ACL-restricted to the user, and the File API's owner-only flags are
     * applied as well.
     */
    fun restrictToOwner(
        path: Path,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (e: UnsupportedOperationException) {
            logger.trace(
                LogCategory.SYSTEM,
                "POSIX permissions unavailable, using owner-only File flags",
                mapOf("reason" to (e.message ?: "unsupported")),
            )
            val file = path.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                file.setExecutable(false, false)
                file.setExecutable(true, true)
            }
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Could not restrict permissions", error = e)
        }
    }

    /** Publishes [descriptor] owner-only, replacing whatever was there. */
    fun write(descriptor: InstanceDescriptor): Boolean {
        val path = descriptorFile.toPath()
        val bytes = descriptor.encode().toByteArray(StandardCharsets.UTF_8)
        return try {
            Files.deleteIfExists(path)
            try {
                // Created with the right mode from the start, so the token is
                // never briefly readable by anyone else.
                Files
                    .newByteChannel(
                        path,
                        setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(ownerOnlyFilePermissions),
                    ).use { it.write(ByteBuffer.wrap(bytes)) }
            } catch (e: UnsupportedOperationException) {
                logger.trace(
                    LogCategory.SYSTEM,
                    "POSIX file mode unavailable, writing then restricting",
                    mapOf("reason" to (e.message ?: "unsupported")),
                )
                Files.write(path, bytes)
                restrictToOwner(path, ownerOnlyFilePermissions)
            }
            true
        } catch (e: IOException) {
            logger.error(LogCategory.SYSTEM, "Could not write the single-instance descriptor", error = e)
            false
        }
    }

    fun read(): InstanceDescriptor? {
        val file = descriptorFile
        if (!file.isFile) return null
        return try {
            parseInstanceDescriptor(file.readText())
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Could not read the single-instance descriptor", error = e)
            null
        }
    }

    /**
     * Withdraws the descriptor, then the socket file. That order matters: a
     * descriptor pointing at a removed socket is the state
     * [SingleInstanceManager.acquireLock] reclaims cleanly, while a socket with no
     * descriptor is unreachable and never cleaned up.
     */
    fun withdraw(descriptor: InstanceDescriptor?) {
        try {
            Files.deleteIfExists(descriptorFile.toPath())
            if (descriptor?.transport == SingleInstanceTransport.UNIX) {
                Files.deleteIfExists(File(descriptor.endpoint).toPath())
            }
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Error removing single-instance files", error = e)
        }
    }
}

/**
 * The socket mechanics: binding an endpoint, and one bounded request/response
 * exchange over it.
 *
 * The 10-second budget is enforced by closing the channel underneath a blocked
 * read, because a blocking [SocketChannel] has no read timeout of its own.
 */
private object SingleInstanceWire {
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable -> Thread(runnable, "BOSS-IPC-Watchdog").apply { isDaemon = true } },
        )

    /**
     * Binds the Unix-domain socket, or returns null when this platform or path
     * cannot have one (a JDK without AF_UNIX, an over-long socket path).
     */
    fun openUnixServer(
        socketFile: File,
        token: String,
    ): Pair<ServerSocketChannel, InstanceDescriptor>? {
        val path = socketFile.toPath()
        if (path.toString().length > MAX_UNIX_SOCKET_PATH_LENGTH) {
            logger.debug(LogCategory.SYSTEM, "Socket path too long for a Unix-domain channel, using loopback TCP")
            return null
        }

        return try {
            // A socket file left by a crashed instance would make bind fail with
            // "address already in use". The caller has already established that
            // nothing answers there, so removing it is safe.
            Files.deleteIfExists(path)

            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(UnixDomainSocketAddress.of(path))
            SingleInstanceFiles.restrictToOwner(path, ownerOnlyFilePermissions)
            channel to InstanceDescriptor(SingleInstanceTransport.UNIX, path.toString(), token)
        } catch (e: UnsupportedOperationException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Unix-domain sockets unavailable, using loopback TCP",
                mapOf("reason" to (e.message ?: "unsupported")),
            )
            null
        } catch (e: IOException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not bind the Unix-domain channel, using loopback TCP",
                mapOf("reason" to (e.message ?: "io error")),
            )
            null
        }
    }

    /** Binds the first free loopback port in the configured range, or null if all are taken. */
    fun openTcpServer(token: String): Pair<ServerSocketChannel, InstanceDescriptor>? {
        for (port in IPC_PORT_BASE until (IPC_PORT_BASE + IPC_PORT_RANGE)) {
            try {
                val channel = ServerSocketChannel.open()
                channel.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TCP_BACKLOG)
                return channel to InstanceDescriptor(SingleInstanceTransport.TCP, port.toString(), token)
            } catch (e: IOException) {
                logger.trace(
                    LogCategory.SYSTEM,
                    "Loopback port unavailable",
                    mapOf("port" to port, "reason" to (e.message ?: "io error")),
                )
            }
        }
        return null
    }

    /** True when something on [descriptor]'s endpoint answers a probe with the published token. */
    fun respondsToPing(descriptor: InstanceDescriptor): Boolean {
        val response = exchange(descriptor, formatPingRequest(descriptor.token))
        return response == RESPONSE_PONG
    }

    /** Sends one line and reads one bounded line back, within the connection budget. */
    fun exchange(
        descriptor: InstanceDescriptor,
        request: String,
        timeoutMs: Long = CONNECTION_TIMEOUT_MS,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): String? {
        val channel = connect(descriptor) ?: return null
        val budget = closeAfterBudget(channel, timeoutMs)
        return try {
            channel.use {
                writeLine(it, request)
                readBoundedLine(BufferedInputStream(Channels.newInputStream(it)), maxResponseBytes)
            }
        } catch (e: IOException) {
            logger.debug(
                LogCategory.SYSTEM,
                "Single-instance exchange failed",
                mapOf("reason" to (e.message ?: "io error")),
            )
            null
        } finally {
            budget.cancel(false)
        }
    }

    private fun connect(descriptor: InstanceDescriptor): SocketChannel? =
        try {
            when (descriptor.transport) {
                SingleInstanceTransport.UNIX -> {
                    SocketChannel.open(UnixDomainSocketAddress.of(descriptor.endpoint))
                }

                SingleInstanceTransport.TCP -> {
                    SocketChannel.open(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), descriptor.endpoint.toInt()),
                    )
                }
            }
        } catch (e: IOException) {
            // Nothing listening is the ordinary case for a stale descriptor.
            logger.debug(
                LogCategory.SYSTEM,
                "Nothing reachable on the single-instance endpoint",
                mapOf("transport" to descriptor.transport.name, "reason" to (e.message ?: "io error")),
            )
            null
        } catch (e: NumberFormatException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Malformed endpoint in the single-instance descriptor",
                mapOf("reason" to (e.message ?: "not a port")),
            )
            null
        }

    /**
     * Reads one line, giving up once [maxBytes] have arrived without one, so a
     * caller cannot make the app buffer without limit.
     */
    fun readBoundedLine(
        input: InputStream,
        maxBytes: Int,
    ): String? {
        return ByteArrayOutputStream().use { buffer ->
            var overBudget = false
            var next = input.read()
            while (next != -1 && next != '\n'.code) {
                if (buffer.size() >= maxBytes) {
                    overBudget = true
                    break
                }
                buffer.write(next)
                next = input.read()
            }

            if (overBudget) {
                logger.warn(LogCategory.SYSTEM, "Single-instance request exceeded its size budget, dropping")
                null
            } else {
                buffer.toString(StandardCharsets.UTF_8).trimEnd('\r', '\n').takeIf { it.isNotEmpty() }
            }
        }
    }

    fun writeLine(
        channel: SocketChannel,
        line: String,
    ) {
        val output = Channels.newOutputStream(channel)
        output.write("$line\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun closeAfterBudget(
        channel: SocketChannel,
        timeoutMs: Long = CONNECTION_TIMEOUT_MS,
    ): ScheduledFuture<*> =
        watchdog.schedule(
            Runnable {
                if (channel.isOpen) {
                    logger.warn(LogCategory.SYSTEM, "Closing a single-instance connection that overran its budget")
                    closeQuietly(channel)
                }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )

    private fun closeQuietly(channel: SocketChannel) {
        try {
            channel.close()
        } catch (e: IOException) {
            logger.trace(LogCategory.SYSTEM, "Error closing channel", mapOf("reason" to (e.message ?: "io error")))
        }
    }
}

private fun buildLlmTokenResponse(providerOverride: (() -> Result<String>)?): String {
    val result =
        providerOverride?.invoke()
            ?: runCatching {
                kotlinx.coroutines.runBlocking {
                    ai.rever.boss.llm.RisaLlmTokenCommand
                        .fetchTokenForRunningBoss()
                }
            }
    return result.fold(
        onSuccess = { token ->
            if (isSingleLineCredential(token)) {
                RESPONSE_LLM_TOKEN_PREFIX + token
            } else {
                RESPONSE_ERROR_PREFIX + "The RISA LLM gateway returned an invalid credential."
            }
        },
        onFailure = { error ->
            val safeMessage =
                (error.message ?: "Could not obtain a RISA LLM credential.")
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                    .take(180)
            RESPONSE_ERROR_PREFIX + safeMessage
        },
    )
}

private fun buildStatusResponse(statusProviderOverride: (() -> String)?): String {
    val rawJson =
        if (statusProviderOverride != null) {
            statusProviderOverride.invoke()
        } else {
            try {
                val runtime = Runtime.getRuntime()
                val totalMem = runtime.totalMemory() / (1024 * 1024)
                val freeMem = runtime.freeMemory() / (1024 * 1024)
                val maxMem = runtime.maxMemory() / (1024 * 1024)
                val usedMem = totalMem - freeMem
                val heapPercent = if (maxMem > 0) ((usedMem.toDouble() / maxMem.toDouble()) * 100.0) else 0.0
                val os = System.getProperty("os.name") ?: "Unknown"
                val arch = System.getProperty("os.arch") ?: "Unknown"
                val version = ai.rever.boss.utils.AppVersion.currentVersionString()
                val projectPath = ai.rever.boss.git.GitService.getCurrentProjectPath() ?: ""

                """{"running":true,"version":"$version","os":"$os","arch":"$arch","activeProject":"$projectPath","memory":{"usedMb":$usedMem,"maxMb":$maxMem,"heapPercent":${String.format(java.util.Locale.US, "%.1f", heapPercent)}}}"""
            } catch (e: Exception) {
                return RESPONSE_ERROR_PREFIX + (e.message ?: "Failed to query status")
            }
        }
    val base64 = java.util.Base64.getEncoder().encodeToString(rawJson.toByteArray(StandardCharsets.UTF_8))
    return RESPONSE_STATUS_PREFIX + base64
}

private fun buildMcpListResponse(listProviderOverride: (() -> String)?): String {
    val rawJson =
        if (listProviderOverride != null) {
            listProviderOverride.invoke()
        } else {
            try {
                val tools = ai.rever.boss.mcp.McpToolRegistryImpl.tools.value
                buildString {
                    append("[")
                    tools.forEachIndexed { index, registeredTool ->
                        val def = registeredTool.definition
                        if (index > 0) append(",")
                        append("{")
                        append("\"name\":").append(kotlinx.serialization.json.Json.encodeToString(def.name)).append(",")
                        append("\"description\":").append(kotlinx.serialization.json.Json.encodeToString(def.description)).append(",")
                        append("\"pluginId\":").append(kotlinx.serialization.json.Json.encodeToString(registeredTool.providerId)).append(",")
                        append("\"requiresAdmin\":").append(def.requiresAdmin).append(",")
                        append("\"requiredPermissions\":").append(kotlinx.serialization.json.Json.encodeToString(def.requiredPermissions))
                        append("}")
                    }
                    append("]")
                }
            } catch (e: Exception) {
                return RESPONSE_ERROR_PREFIX + (e.message ?: "Failed to list MCP tools")
            }
        }
    val base64 = java.util.Base64.getEncoder().encodeToString(rawJson.toByteArray(StandardCharsets.UTF_8))
    return RESPONSE_MCP_LIST_PREFIX + base64
}

private fun buildMcpInvokeResponse(
    toolName: String,
    argsJson: String,
    invokeHandlerOverride: (suspend (String, String) -> McpToolResult)?,
): String {
    if (toolName.isBlank()) {
        return RESPONSE_ERROR_PREFIX + "Tool name must not be blank"
    }

    if (argsJson.isNotBlank() && argsJson != "{}") {
        try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            if (parsed !is kotlinx.serialization.json.JsonObject) {
                val errPayload = """{"success":false,"isError":true,"tool":"$toolName","content":"Arguments must be a valid JSON object"}"""
                val base64 = java.util.Base64.getEncoder().encodeToString(errPayload.toByteArray(StandardCharsets.UTF_8))
                return RESPONSE_MCP_INVOKE_PREFIX + base64
            }
        } catch (e: Exception) {
            val safeError = "Malformed JSON arguments: ${e.message ?: "Invalid JSON syntax"}".replace('\n', ' ').replace('\r', ' ')
            val encodedError = kotlinx.serialization.json.Json.encodeToString<String>(safeError)
            val errPayload = """{"success":false,"isError":true,"tool":"$toolName","content":$encodedError}"""
            val base64 = java.util.Base64.getEncoder().encodeToString(errPayload.toByteArray(StandardCharsets.UTF_8))
            return RESPONSE_MCP_INVOKE_PREFIX + base64
        }
    }

    return try {
        val result: McpToolResult =
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    if (invokeHandlerOverride != null) {
                        invokeHandlerOverride.invoke(toolName, argsJson)
                    } else {
                        ai.rever.boss.mcp.McpToolRegistryImpl.invoke(toolName, argsJson)
                    }
                } ?: McpToolResult(
                    text = "Tool '$toolName' timed out after 30s",
                    isError = true,
                )
            }
        val encodedContent = kotlinx.serialization.json.Json.encodeToString<String>(result.text)
        val rawJson =
            """{"success":${!result.isError},"isError":${result.isError},"tool":"$toolName","content":$encodedContent}"""
        val base64 = java.util.Base64.getEncoder().encodeToString(rawJson.toByteArray(StandardCharsets.UTF_8))
        RESPONSE_MCP_INVOKE_PREFIX + base64
    } catch (e: Exception) {
        val safeMessage = (e.message ?: "Failed to invoke tool $toolName").replace('\n', ' ').replace('\r', ' ')
        RESPONSE_ERROR_PREFIX + safeMessage
    }
}

/**
 * Whether [token] can be sent as one response line.
 *
 * The wire format is line-based, so a credential carrying CR or LF would let a
 * gateway response inject a second line. Deliberately does *not* check a vendor
 * prefix: pinning `sk-` here turns a LiteLLM key-format change into "the gateway
 * returned an invalid credential" with nothing to diagnose from, and the caller
 * that actually understands the credential is the gateway.
 */
internal val isSingleLineCredential: (String) -> Boolean =
    { token -> token.isNotBlank() && token.none { it == '\n' || it == '\r' || it == ' ' } }

/**
 * Waits for the next connection, or returns null once the channel is gone —
 * which is what [SingleInstanceManager.release] closing it looks like from here.
 */
private fun acceptNextClient(
    serverChannel: ServerSocketChannel?,
    isListening: () -> Boolean,
): SocketChannel? =
    try {
        serverChannel?.accept()
    } catch (error: IOException) {
        if (isListening()) {
            logger.warn(LogCategory.SYSTEM, "Error in IPC listener", error = error)
        }
        null
    }

/**
 * Manages single-instance application behavior.
 *
 * The running instance publishes a descriptor and listens on a local channel, so
 * a second launch can hand over its URL and exit instead of opening a second app.
 *
 * Architecture:
 * - The descriptor lives in BOSS's own per-user data root (`~/.boss/run`, created
 *   owner-only) and records the channel endpoint plus a token minted fresh at
 *   startup. It records no pid.
 * - The channel is a Unix-domain socket inside that directory where the JDK
 *   supports one (macOS, Linux), and a loopback TCP port otherwise (Windows).
 *   Either way the token is what establishes that a caller may be listened to:
 *   every request must present it, and one that does not is refused before it is
 *   read for meaning.
 * - "Is another instance running" is answered by whether something replies on the
 *   channel, not by whether some pid exists — a pid is trivially some other
 *   program — and a descriptor nobody answers on is reclaimed, so a file left
 *   behind by a crash can never stop the app from starting.
 *
 * What the token establishes, and what it does not: holding it proves the caller
 * could read a file in this user's own data root, so a caller is this user, or
 * something already running with this user's access. It says nothing about *which*
 * program is calling. That is why a forwarded URL carries the origin the forwarder
 * determined, rather than being trusted on the strength of the token — see
 * [DeepLinkOrigin].
 *
 * Usage:
 * ```
 * if (!SingleInstanceManager.acquireLock()) {
 *     // Another instance is running
 *     SingleInstanceManager.sendToExistingInstance("boss://auth/verify?token=...")
 *     exitProcess(0)
 * }
 * ```
 */
object SingleInstanceManager {
    const val MAX_REQUEST_BYTES: Int = 1024 * 1024
    private var serverChannel: ServerSocketChannel? = null
    private var listenerThread: Thread? = null

    /** Test seam; production serves credentials from the running BOSS session. */
    internal var llmTokenProviderOverride: (() -> Result<String>)? = null

    /** Test seam / host hook for status response. */
    internal var statusProviderOverride: (() -> String)? = null

    /** Test seam / host hook for MCP tool list response. */
    internal var mcpListProviderOverride: (() -> String)? = null

    /** Test seam / host hook for MCP tool invocation response. */
    internal var mcpInvokeHandlerOverride: (suspend (String, String) -> McpToolResult)? = null

    @Volatile
    private var isListening: Boolean = false

    /** The descriptor this process published, or null when it is not the owner. */
    @Volatile
    private var published: InstanceDescriptor? = null

    /** Runtime directory override for tests; see [SingleInstanceFiles.runtimeDirOverride]. */
    internal var runtimeDirOverride: File?
        get() = SingleInstanceFiles.runtimeDirOverride
        set(value) {
            SingleInstanceFiles.runtimeDirOverride = value
        }

    /**
     * Check whether another instance of BOSS is already running, by asking it.
     * Does not take ownership - use [acquireLock] for that.
     */
    fun isAnotherInstanceRunning(): Boolean {
        val descriptor = SingleInstanceFiles.read() ?: return false
        return SingleInstanceWire.respondsToPing(descriptor)
    }

    /**
     * Try to become the single instance.
     *
     * Returns true if we are the one, false if another instance is answering on
     * the channel. On success the channel is listening and the descriptor is
     * published.
     */
    fun acquireLock(): Boolean {
        SingleInstanceFiles.prepare()

        val existing = SingleInstanceFiles.read()
        if (existing != null && SingleInstanceWire.respondsToPing(existing)) {
            logger.info(LogCategory.SYSTEM, "Another instance is answering on the single-instance channel")
            return false
        }
        if (existing != null) {
            // Nothing answers, so this descriptor outlived its process.
            logger.debug(LogCategory.SYSTEM, "Reclaiming a single-instance descriptor nothing answers on")
        }

        return startServer()
    }

    /**
     * Bind the channel, publish the descriptor and start accepting.
     * Returns false when there is nothing a second launch could reach.
     */
    private fun startServer(): Boolean {
        val token = newChannelToken()
        val bound =
            SingleInstanceWire.openUnixServer(SingleInstanceFiles.socketFile, token)
                ?: SingleInstanceWire.openTcpServer(token)
        serverChannel = bound?.first

        // Without a published descriptor no second launch could reach us, and the
        // token would be unknowable, so failing to publish is failing to start
        // rather than listening on something nobody can address.
        val descriptor = bound?.second?.takeIf { SingleInstanceFiles.write(it) }
        if (descriptor == null) {
            logger.error(
                LogCategory.SYSTEM,
                if (bound == null) {
                    "Failed to bind the single-instance channel on any endpoint"
                } else {
                    "Failed to publish the single-instance descriptor"
                },
            )
            release()
            return false
        }

        published = descriptor
        logger.info(
            LogCategory.SYSTEM,
            "Single-instance channel listening",
            mapOf("transport" to descriptor.transport.name, "endpoint" to descriptor.endpoint),
        )

        startAcceptLoop()
        return true
    }

    private fun startAcceptLoop() {
        isListening = true
        listenerThread =
            thread(isDaemon = true, name = "BOSS-IPC-Listener") {
                logger.trace(LogCategory.SYSTEM, "IPC listener thread started")

                while (isListening && !Thread.currentThread().isInterrupted) {
                    val client = acceptNextClient(serverChannel) { isListening } ?: break
                    handleClient(client)
                }

                logger.trace(LogCategory.SYSTEM, "IPC listener thread stopped")
            }
    }

    /**
     * Handle one connection.
     *
     * The token is checked before the request means anything, so a caller that
     * cannot present it gets a refusal and nothing else.
     */
    private fun handleClient(client: SocketChannel) {
        thread(isDaemon = true, name = "BOSS-IPC-Client-Handler") {
            var budget = SingleInstanceWire.closeAfterBudget(client)
            try {
                client.use { channel ->
                    val line =
                        SingleInstanceWire.readBoundedLine(
                            BufferedInputStream(Channels.newInputStream(channel)),
                            MAX_REQUEST_BYTES,
                        )
                    val request = line?.let { parseRequestLine(it) }
                    // Only a caller that presented the live token gets the longer
                    // budget: minting a credential is a round trip to the gateway,
                    // and nothing an unauthenticated caller sends should change what
                    // this process is willing to spend on it.
                    if (request != null && (request.verb == VERB_LLM_TOKEN || request.verb == VERB_MCP_INVOKE) && presentsLiveToken(request)) {
                        budget.cancel(false)
                        val timeout = if (request.verb == VERB_LLM_TOKEN) LLM_TOKEN_TIMEOUT_MS else MCP_INVOKE_TIMEOUT_MS
                        budget = SingleInstanceWire.closeAfterBudget(channel, timeout)
                    }
                    SingleInstanceWire.writeLine(channel, responseFor(request))
                }
            } catch (e: IOException) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Single-instance connection ended early",
                    mapOf("reason" to (e.message ?: "io error")),
                )
            } finally {
                budget.cancel(false)
            }
        }
    }

    /**
     * Whether a request presented the token this process published. Reads as a
     * property rather than a function so the gate can be consulted from more than
     * one place without tripping the object's function-count ceiling.
     */
    private val presentsLiveToken: (SingleInstanceRequest) -> Boolean
        get() = { request ->
            val expected = published?.token
            expected != null && tokensMatch(expected, request.token)
        }

    /**
     * Checks the token, then acts on the request, returning the response to send
     * back. A request that does not present the live token is refused here,
     * before its contents mean anything.
     */
    private fun responseFor(request: SingleInstanceRequest?): String {
        if (request == null || !presentsLiveToken(request)) {
            logger.warn(LogCategory.SYSTEM, "Refused a single-instance request that did not present the channel token")
            return RESPONSE_REJECTED
        }

        return when {
            request.verb == VERB_PING -> {
                RESPONSE_PONG
            }

            request.verb == VERB_OPEN && isForwardableUrl(request.url) -> {
                logger.info(
                    LogCategory.SYSTEM,
                    "Received URL from new instance",
                    mapOf("origin" to request.origin.name),
                )
                // The forwarding instance states the URL's provenance; it is not
                // inferred from the caller having held the token, which says
                // nothing about where the URL came from.
                DeepLinkHandler.processDeepLink(requireNotNull(request.url), request.origin)
                RESPONSE_OK
            }

            request.verb == VERB_LLM_TOKEN -> {
                buildLlmTokenResponse(llmTokenProviderOverride)
            }

            request.verb == VERB_STATUS -> {
                buildStatusResponse(statusProviderOverride)
            }

            request.verb == VERB_MCP_LIST -> {
                buildMcpListResponse(mcpListProviderOverride)
            }

            request.verb == VERB_MCP_INVOKE -> {
                val toolName = request.toolName.orEmpty()
                val argsJson = request.argsJson.orEmpty()
                buildMcpInvokeResponse(toolName, argsJson, mcpInvokeHandlerOverride)
            }

            else -> {
                logger.warn(LogCategory.SYSTEM, "Refused a single-instance request BOSS does not serve")
                RESPONSE_REJECTED
            }
        }
    }

    /**
     * Requests a credential from the already-running, signed-in BOSS process.
     * The credential is never written to disk or included in a log line.
     */
    fun requestLlmToken(): Result<String> {
        val target = SingleInstanceFiles.read()
        return if (target == null) {
            Result.failure(
                IllegalStateException("Open BOSS, sign in with your RISA account, and retry."),
            )
        } else {
            val response =
                SingleInstanceWire.exchange(
                    target,
                    formatLlmTokenRequest(target.token),
                    LLM_TOKEN_TIMEOUT_MS,
                )
            if (response == null) {
                Result.failure(
                    IllegalStateException("BOSS is not responding. Open BOSS and retry."),
                )
            } else {
                when {
                    response.startsWith(RESPONSE_LLM_TOKEN_PREFIX) -> {
                        Result.success(response.removePrefix(RESPONSE_LLM_TOKEN_PREFIX))
                    }

                    response.startsWith(RESPONSE_ERROR_PREFIX) -> {
                        Result.failure(IllegalStateException(response.removePrefix(RESPONSE_ERROR_PREFIX)))
                    }

                    else -> {
                        Result.failure(IllegalStateException("BOSS rejected the RISA LLM credential request."))
                    }
                }
            }
        }
    }

    /**
     * Queries status from the running BOSS process.
     */
    fun queryStatus(): Result<String> {
        val target = SingleInstanceFiles.read()
            ?: return Result.failure(IllegalStateException("BOSS is not running. Launch BOSS to view status."))
        val response =
            SingleInstanceWire.exchange(
                target,
                formatStatusRequest(target.token),
                timeoutMs = CONNECTION_TIMEOUT_MS,
                maxResponseBytes = MAX_DATA_RESPONSE_BYTES,
            ) ?: return Result.failure(IllegalStateException("BOSS is not running or not responding on the single-instance channel."))

        return when {
            response.startsWith(RESPONSE_STATUS_PREFIX) -> {
                val base64 = response.removePrefix(RESPONSE_STATUS_PREFIX).trim()
                try {
                    Result.success(String(java.util.Base64.getDecoder().decode(base64), StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Malformed status response from BOSS: ${e.message}"))
                }
            }
            response.startsWith(RESPONSE_ERROR_PREFIX) -> {
                Result.failure(IllegalStateException(response.removePrefix(RESPONSE_ERROR_PREFIX)))
            }
            else -> {
                Result.failure(IllegalStateException("BOSS rejected the status query ($response)."))
            }
        }
    }

    /**
     * Queries active MCP tools from the running BOSS process.
     */
    fun queryMcpList(): Result<String> {
        val target = SingleInstanceFiles.read()
            ?: return Result.failure(IllegalStateException("BOSS is not running. Launch BOSS to list MCP tools."))
        val response =
            SingleInstanceWire.exchange(
                target,
                formatMcpListRequest(target.token),
                timeoutMs = CONNECTION_TIMEOUT_MS,
                maxResponseBytes = MAX_DATA_RESPONSE_BYTES,
            ) ?: return Result.failure(IllegalStateException("BOSS is not running or not responding on the single-instance channel."))

        return when {
            response.startsWith(RESPONSE_MCP_LIST_PREFIX) -> {
                val base64 = response.removePrefix(RESPONSE_MCP_LIST_PREFIX).trim()
                try {
                    Result.success(String(java.util.Base64.getDecoder().decode(base64), StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Malformed MCP list response from BOSS: ${e.message}"))
                }
            }
            response.startsWith(RESPONSE_ERROR_PREFIX) -> {
                Result.failure(IllegalStateException(response.removePrefix(RESPONSE_ERROR_PREFIX)))
            }
            else -> {
                Result.failure(IllegalStateException("BOSS rejected the MCP tool list request ($response)."))
            }
        }
    }

    /**
     * Invokes an MCP tool in the running BOSS process.
     */
    fun invokeMcpTool(
        toolName: String,
        argsJson: String = "{}",
        timeoutMs: Long = MCP_INVOKE_TIMEOUT_MS,
    ): Result<String> {
        val target = SingleInstanceFiles.read()
            ?: return Result.failure(IllegalStateException("BOSS is not running. Launch BOSS to invoke MCP tools."))
        val response =
            SingleInstanceWire.exchange(
                target,
                formatMcpInvokeRequest(target.token, toolName, argsJson),
                timeoutMs = timeoutMs,
                maxResponseBytes = MAX_DATA_RESPONSE_BYTES,
            ) ?: return Result.failure(IllegalStateException("BOSS is not running or not responding on the single-instance channel."))

        return when {
            response.startsWith(RESPONSE_MCP_INVOKE_PREFIX) -> {
                val base64 = response.removePrefix(RESPONSE_MCP_INVOKE_PREFIX).trim()
                try {
                    Result.success(String(java.util.Base64.getDecoder().decode(base64), StandardCharsets.UTF_8))
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Malformed MCP invoke response from BOSS: ${e.message}"))
                }
            }
            response.startsWith(RESPONSE_ERROR_PREFIX) -> {
                Result.failure(IllegalStateException(response.removePrefix(RESPONSE_ERROR_PREFIX)))
            }
            else -> {
                Result.failure(IllegalStateException("BOSS rejected the MCP invoke request ($response)."))
            }
        }
    }

    /**
     * Send a URL to the existing instance.
     *
     * @param origin what the caller knows about where [url] came from. Defaults to
     *   [DeepLinkOrigin.EXTERNAL], because a forwarded URL normally reached this
     *   process from the OS.
     * @return true if the running instance acknowledged it.
     */
    fun sendToExistingInstance(
        url: String,
        origin: DeepLinkOrigin = DeepLinkOrigin.EXTERNAL,
    ): Boolean {
        if (url.isBlank()) {
            logger.warn(LogCategory.SYSTEM, "Cannot send empty URL to existing instance")
            return false
        }

        val response =
            SingleInstanceFiles.read()?.let { target ->
                logger.debug(
                    LogCategory.SYSTEM,
                    "Attempting to connect to existing instance",
                    mapOf("transport" to target.transport.name, "endpoint" to target.endpoint),
                )
                SingleInstanceWire.exchange(target, formatOpenRequest(target.token, origin, url))
            }

        if (response == RESPONSE_OK) {
            logger.info(LogCategory.SYSTEM, "Existing instance acknowledged URL")
        } else {
            logger.warn(
                LogCategory.SYSTEM,
                "Existing instance did not accept the URL",
                mapOf("response" to (response ?: "none")),
            )
        }
        return response == RESPONSE_OK
    }

    /**
     * Start listening for URLs from new instances.
     *
     * Note: the channel is already listening once [acquireLock] succeeds; this
     * exists for API completeness.
     */
    fun startListening(onUrlReceived: (String) -> Unit) {
        logger.debug(LogCategory.SYSTEM, "Already listening for URLs via the single-instance channel")
    }

    /**
     * Stop the channel and withdraw the descriptor.
     * Should be called on application shutdown.
     */
    fun release() {
        logger.info(LogCategory.SYSTEM, "Releasing the single-instance channel...")

        isListening = false
        val descriptor = published
        published = null
        llmTokenProviderOverride = null
        statusProviderOverride = null
        mcpListProviderOverride = null
        mcpInvokeHandlerOverride = null

        try {
            serverChannel?.close()
        } catch (e: IOException) {
            logger.warn(LogCategory.SYSTEM, "Error closing the server channel", error = e)
        }
        serverChannel = null

        try {
            listenerThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(LogCategory.SYSTEM, "Interrupted waiting for the listener thread", error = e)
        }
        listenerThread = null

        SingleInstanceFiles.withdraw(descriptor)

        logger.info(LogCategory.SYSTEM, "Single-instance channel released")
    }
}
