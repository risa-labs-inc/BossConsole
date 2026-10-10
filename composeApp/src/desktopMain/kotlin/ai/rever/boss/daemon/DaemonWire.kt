package ai.rever.boss.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal val daemonJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
internal const val DAEMON_PROTOCOL = 2
internal const val MAX_MESSAGE_BYTES = 1024 * 1024

@Serializable
internal data class DaemonEndpoint(
    val port: Int,
    val secret: String,
    val protocol: Int = DAEMON_PROTOCOL,
)

@Serializable
internal data class DaemonRequest(
    val secret: String,
    val operation: String,
    val pluginId: String = "",
    val serviceId: String = "",
    val jar: String = "",
    val sha256: String = "",
    val entryPoint: String = "",
    val configuration: Map<String, String> = emptyMap(),
    val method: String = "",
    val payload: String = "",
    val instanceId: String = "",
)

@Serializable
internal data class DaemonResponse(
    val error: String? = null,
    val endpoints: Map<String, String> = emptyMap(),
    val payload: String = "",
)

internal fun ownerDirectory(dir: File): File {
    dir.mkdirs()
    runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    return dir
}

internal fun ownerFile(file: File) {
    if (!file.exists()) {
        runCatching {
            val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            Files.createFile(file.toPath(), permissions)
        }.getOrElse { file.createNewFile() }
    }
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
}

internal fun readMessage(input: java.io.InputStream): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next == -1 || next == 10) break
        require(bytes.size() < MAX_MESSAGE_BYTES) { "Daemon message exceeds limit" }
        bytes.write(next)
    }
    return bytes.toString(Charsets.UTF_8)
}

internal fun daemonRequest(
    endpoint: DaemonEndpoint,
    request: DaemonRequest,
): DaemonResponse =
    Socket().use { socket ->
        require(endpoint.protocol == DAEMON_PROTOCOL) { "Incompatible BOSS daemon" }
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), 1500)
        socket.soTimeout = 30_000
        val bytes =
            daemonJson
                .encodeToString(
                    DaemonRequest.serializer(),
                    request.copy(secret = endpoint.secret),
                ).toByteArray()
        require(bytes.size <= MAX_MESSAGE_BYTES) { "Daemon message exceeds limit" }
        socket.getOutputStream().apply {
            write(bytes)
            write(10)
            flush()
        }
        daemonJson.decodeFromString(DaemonResponse.serializer(), readMessage(socket.getInputStream())).also {
            check(it.error == null) { it.error ?: "Daemon request failed" }
        }
    }
