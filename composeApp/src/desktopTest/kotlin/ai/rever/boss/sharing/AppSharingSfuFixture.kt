package ai.rever.boss.sharing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Credentials stay in JVM memory and are never served as browser configuration or logged. */
internal class AppSharingSfuCredentials private constructor(
    val owner: String,
    val viewerBaseUrl: String,
    private val endpoint: String,
    private val accessToken: String,
    private val anonKey: String,
) {
    fun backend(): AppSharingBackend =
        AppSharingBackend(endpoint = { endpoint }, identity = { owner to accessToken }, anonKey = { anonKey })

    companion object {
        fun load(): AppSharingSfuCredentials {
            val path =
                Path.of(
                    requireNotNull(System.getenv("BOSS_TEST_APP_SFU_CREDENTIALS")) {
                        "Set BOSS_TEST_APP_SFU_CREDENTIALS to an owner-only dedicated test account JSON file"
                    },
                )
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Expected a regular credentials file" }
            val privateModes = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            require(Files.getPosixFilePermissions(path).all { it in privateModes }) { "Credentials must be owner-only" }
            val bytes = Files.newInputStream(path).use { it.readNBytes(16385) }
            require(bytes.size <= 16384) { "Credentials file is too large" }
            val value =
                runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
                    .getOrElse { error("Invalid SFU credentials JSON") }

            fun field(name: String): String =
                value
                    .getValue(name)
                    .jsonPrimitive.content
                    .also { require(it.isNotBlank()) }
            val endpoint = runCatching { URI(field("endpoint")) }.getOrElse { error("Invalid SFU endpoint URI") }
            require(
                endpoint.scheme == "https" ||
                    (endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost")),
            )
            require(endpoint.userInfo == null && endpoint.fragment == null && endpoint.rawQuery == null)
            val viewer = runCatching { URI(field("viewerBaseUrl")) }.getOrElse { error("Invalid viewer base URI") }
            require(viewer.scheme == "https" && viewer.userInfo == null && viewer.fragment == null)
            val owner =
                runCatching { UUID.fromString(field("userId")).toString() }.getOrElse { error("Invalid test owner") }
            return AppSharingSfuCredentials(
                owner,
                viewer.toString(),
                endpoint.toString(),
                field("accessToken"),
                field("anonKey"),
            )
        }
    }
}

/** Fixed loopback RPC capability restricted to this newly generated synthetic session. */
internal class AppSharingSfuFixture(
    private val credentials: AppSharingSfuCredentials,
) : AutoCloseable {
    val result = CompletableFuture<JsonObject>()
    private val backend = credentials.backend()
    private val session = UUID.randomUUID().toString()
    private val generation = UUID.randomUUID().toString()
    private val window = "synthetic-${UUID.randomUUID()}"
    private val token = UUID.randomUUID().toString()
    private val prefix = "/${UUID.randomUUID()}/"
    private val executor =
        Executors.newFixedThreadPool(16) { task -> Thread(task, "app-sfu-smoke-http").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 32)
    private val origin = "http://127.0.0.1:${server.address.port}"
    val baseUrl = origin + prefix
    private val testAssets = setOf("sfu-smoke.html", "sfu-smoke.mjs")
    private val productionAssets =
        setOf("bridge.mjs", "media.mjs", "control.mjs", "crypto.mjs", "encoded-worker.mjs", "stats.mjs", "recovery.mjs")
    private val actions =
        setOf(
            "register",
            "heartbeat",
            "stop",
            "preferencesGet",
            "admit",
            "consume",
            "revoke",
            "peerHeartbeat",
            "mediaCreate",
            "mediaPublish",
            "mediaSubscribe",
            "mediaRenegotiate",
            "mediaClose",
            "dataEstablish",
            "dataPublish",
            "dataSubscribe",
            "dataRevoke",
            "controlAcquire",
            "controlRenew",
            "controlRelease",
            "controlPoll",
        )

    init {
        server.executor = executor
        server.createContext(prefix, ::handle)
        server.start()
    }

    /** HTTP/IO boundary: errors are fixed codes, never request bodies or credential-bearing descriptors. */
    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        try {
            require(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}")
            val name = exchange.requestURI.rawPath.removePrefix(prefix)
            if (exchange.requestMethod == "POST") {
                require(exchange.requestHeaders.getFirst("Origin") == origin)
                post(exchange, name)
            } else {
                require(exchange.requestMethod == "GET")
                get(exchange, name)
            }
        } catch (error: AppSharingException) {
            respond(exchange, 409, buildJsonObject { put("error", error.reason) }.toString(), "application/json")
        } catch (_: Exception) {
            runCatching { respond(exchange, 400, "{\"error\":\"fixture_request_refused\"}", "application/json") }
        } finally {
            exchange.close()
        }
    }

    private fun post(
        exchange: HttpExchange,
        name: String,
    ) {
        val body = readBody(exchange)
        if (name == "result") {
            require(body.toString().length <= 16384)
            result.complete(body)
            respond(exchange, 200, "{}", "application/json")
            return
        }
        require(name == "rpc")
        val supplied = exchange.requestHeaders.getFirst("X-Boss-App-Token") ?: ""
        require(MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray()))
        val action = body.getValue("action").jsonPrimitive.content
        require(action in actions)
        val pinned =
            buildJsonObject {
                body.forEach { (key, value) -> put(key, value) }
                put("session_id", session)
                put("generation", generation)
                if (body.containsKey("window_id")) put("window_id", window)
            }
        val response = runBlocking { backend.call(credentials.owner, pinned) }
        respond(exchange, 200, response.toString(), "application/json")
    }

    private fun readBody(exchange: HttpExchange): JsonObject {
        val bytes = exchange.requestBody.use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    private fun get(
        exchange: HttpExchange,
        name: String,
    ) {
        if (name == "fixture") {
            val config =
                buildJsonObject {
                    put("sessionId", session)
                    put("generation", generation)
                    put("windowId", window)
                    put("deviceId", UUID.randomUUID().toString())
                    put("instanceId", UUID.randomUUID().toString())
                    put("rpcUrl", baseUrl + "rpc")
                    put("rpcToken", token)
                    put("viewerBaseUrl", credentials.viewerBaseUrl)
                }
            respond(exchange, 200, config.toString(), "application/json")
            return
        }
        val path =
            when (name) {
                in testAssets -> "/app-sharing-smoke/$name"
                in productionAssets -> "/app-sharing/$name"
                else -> error("Unknown test asset")
            }
        val body = checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        val type = if (name.endsWith(".html")) "text/html; charset=utf-8" else "text/javascript; charset=utf-8"
        respond(exchange, 200, body, type)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        type: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; worker-src 'self'; connect-src 'self'; " +
                "media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'",
        )
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        // A browser failure/timeout still withdraws only this fixture's fresh session.
        runBlocking {
            runCatching {
                backend.call(
                    credentials.owner,
                    buildJsonObject {
                        put("action", "stop")
                        put("session_id", session)
                        put("generation", generation)
                    },
                )
            }
        }
        server.stop(0)
        executor.shutdownNow()
    }
}
