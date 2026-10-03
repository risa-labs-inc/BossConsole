package ai.rever.boss.sharing

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.net.callback.BeforeUrlRequestCallback
import com.teamdev.jxbrowser.view.swing.BrowserView
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color as ComposeColor

/** Explicit consent to capture only this test's synthetic owned ComposeWindow. No account or SFU. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_SHARING_BENCHMARK", matches = "1")
class AppSharingNativeBenchmarkTest {
    @TempDir
    lateinit var temporary: Path

    // One owner scope closes capture, bridge and browser on every exit.
    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    fun `measure native owned window through authenticated video presentation`() {
        val fps = (System.getenv("BOSS_SHARING_BENCHMARK_FPS") ?: "60").toInt().also { require(it == 30 || it == 60) }
        val format =
            (System.getenv("BOSS_SHARING_BENCHMARK_FORMAT") ?: "BGRA").also {
                require(it in setOf("BGRA", "NV12"))
            }
        val markerEvery =
            (System.getenv("BOSS_SHARING_BENCHMARK_MARKER_EVERY") ?: "1").toInt().also {
                require(it in setOf(0, 1, 5, 10, 30))
            }
        val renderer =
            (System.getenv("BOSS_SHARING_BENCHMARK_SOURCE_RENDERER") ?: "swing").also {
                require(it in setOf("swing", "compose"))
            }
        val jitterTarget =
            (System.getenv("BOSS_SHARING_BENCHMARK_JITTER_TARGET_MS") ?: "default").also {
                require(it in setOf("default", "0", "10"))
            }
        val transport =
            (System.getenv("BOSS_SHARING_BENCHMARK_TRANSPORT") ?: "fixture-http").also {
                require(it in setOf("fixture-http", "production-assets-native-client"))
            }
        val productionTransport = transport == "production-assets-native-client"
        val waitForFrame = productionTransport || System.getenv("BOSS_SHARING_BENCHMARK_WAIT_FRAME") == "1"
        val external = System.getenv("BOSS_SHARING_BENCHMARK_EXTERNAL_VIEWER") == "1"
        val placement = if (external) "external-browser" else "embedded-shared-jvm"
        val options =
            buildJsonObject {
                put("format", format)
                put("fps", fps)
                put("markerEvery", markerEvery)
                put("viewerPlacement", placement)
                put("sourceRenderer", renderer)
                put("waitForFrame", waitForFrame)
                if (jitterTarget != "default") put("jitterBufferTargetMs", jitterTarget.toInt())
                put(
                    "nativeSource",
                    buildJsonObject {
                        put("transport", transport)
                        put("rawUrl", "./raw")
                        put("clockUrl", "./clock")
                        put("metricsUrl", "./metrics")
                    },
                )
            }
        val destination =
            Path.of(System.getenv("BOSS_SHARING_BENCHMARK_OUTPUT") ?: "build/reports/app-sharing-benchmark")
        Files.createDirectories(destination)
        val chromium = Path.of(requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")))
        require(Files.isDirectory(chromium) && JxBrowserConfig.licenseKey.isNotBlank())
        ChromiumToolkitPreload.preload(chromium)
        val painted = ConcurrentHashMap<Int, Double>()
        val diagnostics =
            if (System.getenv("BOSS_SHARING_BENCHMARK_CAPTURE_DIAGNOSTICS") == "1") AppCaptureDiagnostics() else null
        val counters = NativeBenchmarkCounters(diagnostics)
        val panel = NativeBenchmarkPattern(painted, counters)
        val window =
            onEdt {
                ComposeWindow().apply {
                    isUndecorated = true
                    title = "Synthetic native sharing benchmark"
                    focusableWindowState = false
                    setSize(1280, 720)
                    setContent {
                        if (renderer == "compose") {
                            NativeComposeBenchmarkPattern(painted, counters, fps)
                        } else {
                            SwingPanel(factory = { panel }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    isVisible = true
                }
            }
        val timer =
            if (renderer == "swing") {
                onEdt { Timer(1000 / fps) { panel.repaint() }.also { it.start() } }
            } else {
                null
            }
        val failure = AtomicReference<String?>()
        try {
            val target = AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window)
            val capture = AppContinuousWindowCapture(diagnostics = diagnostics)
            assertTrue(capture.capability(target).supported, "This OS/session must support exact owned-window capture")
            NativeBenchmarkServer(painted, failure, counters, waitForFrame, productionTransport).use { fixture ->
                capture.start(target, fixture::offer, failure::set, { true }, { fps }, { format }).use {
                    val report =
                        if (external) {
                            externalReport(fixture, options, destination)
                        } else {
                            embeddedReport(fixture, options, chromium)
                        }
                    assertEquals(null, failure.get(), "Native capture stopped during benchmark")
                    val file = destination.resolve("native-${System.currentTimeMillis()}-$format-$fps.json")
                    Files.writeString(file, report.toString() + "\n")
                    println("Native sharing benchmark aggregate: ${file.toAbsolutePath()}")
                    assertTrue(
                        report
                            .getValue("coverage")
                            .jsonObject
                            .getValue("nativeCapture")
                            .jsonPrimitive.boolean,
                    )
                    val actualPlacement = report.getValue("scenario").jsonObject.getValue("viewerPlacement")
                    assertEquals(placement, actualPlacement.jsonPrimitive.content)
                    assertTrue(report.getValue("valid").jsonPrimitive.boolean, "Native benchmark invalid: $report")
                }
            }
        } finally {
            onEdt {
                timer?.stop()
                window.dispose()
            }
        }
    }

    private fun externalReport(
        fixture: NativeBenchmarkServer,
        options: JsonObject,
        destination: Path,
    ): JsonObject {
        val manifest = destination.resolve("external-viewer-${ProcessHandle.current().pid()}.json")
        createOwnerOnlyManifest(manifest)
        try {
            val content =
                buildJsonObject {
                    put("url", fixture.entryUrl)
                    put("bootstrap", fixture.bootstrap)
                    put("options", options)
                }
            Files.writeString(manifest, content.toString() + "\n")
            println("External synthetic benchmark manifest: ${manifest.toAbsolutePath()}")
            return fixture.result.get(180, TimeUnit.SECONDS)
        } finally {
            Files.deleteIfExists(manifest)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Remove an incomplete manifest before rethrowing any permission failure.
    private fun createOwnerOnlyManifest(path: Path) {
        if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(path)
            try {
                val acl = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java))
                acl.acl =
                    listOf(
                        AclEntry
                            .newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(acl.owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                            .build(),
                    )
            } catch (failure: Throwable) {
                Files.deleteIfExists(path)
                throw failure
            }
        }
    }

    @Suppress("LongMethod") // Isolated Chromium profile, network boundary and cleanup remain together.
    private fun embeddedReport(
        fixture: NativeBenchmarkServer,
        options: JsonObject,
        chromium: Path,
    ): JsonObject {
        val engine =
            Engine.newInstance(
                EngineOptions
                    .newBuilder(RenderingMode.OFF_SCREEN)
                    .licenseKey(JxBrowserConfig.licenseKey)
                    .chromiumDir(chromium)
                    .userDataDir(Files.createDirectory(temporary.resolve("profile")))
                    .enableIncognito()
                    .enableAutoplay()
                    .disableDnsOverHttps()
                    .addSwitch("--disable-background-networking")
                    .addSwitch("--disable-component-update")
                    .addSwitch("--disable-sync")
                    .addSwitch("--disable-renderer-backgrounding")
                    .addSwitch("--disable-background-timer-throttling")
                    .build(),
            )
        var browserWindow: JFrame? = null
        try {
            engine.network().set(
                BeforeUrlRequestCallback::class.java,
                BeforeUrlRequestCallback { params ->
                    if (params.urlRequest().url().startsWith(fixture.baseUrl)) {
                        BeforeUrlRequestCallback.Response.proceed()
                    } else {
                        BeforeUrlRequestCallback.Response.cancel()
                    }
                },
            )
            val browser = engine.newBrowser()
            browserWindow =
                onEdt {
                    JFrame("Synthetic encrypted benchmark receiver").apply {
                        iconImages = BossWindowIcon.images
                        focusableWindowState = false
                        contentPane.add(BrowserView.newInstance(browser))
                        setSize(900, 700)
                        isVisible = true
                    }
                }
            browser.navigation().loadUrlAndWait(fixture.entryUrl, Duration.ofSeconds(20))
            val frame = browser.mainFrame().orElseThrow()
            if (fixture.bootstrap.isNotEmpty()) frame.executeJavaScript<Any?>(fixture.bootstrap)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (frame.executeJavaScript<Boolean>("typeof window.runBenchmark==='function'") != true) {
                check(System.nanoTime() < deadline) { "Benchmark module did not initialize" }
                Thread.sleep(50)
            }
            frame.executeJavaScript<Any?>("window.runBenchmark($options);undefined")
            return fixture.result.get(90, TimeUnit.SECONDS)
        } finally {
            onEdt { browserWindow?.dispose() }
            engine.close()
        }
    }
}

private class NativeBenchmarkPattern(
    private val painted: ConcurrentHashMap<Int, Double>,
    private val counters: NativeBenchmarkCounters,
) : JPanel() {
    private var sequence = 0

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val id = ++sequence
        counters.increment("paints")
        painted[id] = System.nanoTime() / 1_000_000.0
        painted.remove(id - 4800)
        graphics.color = Color(22, 27, 34)
        graphics.fillRect(0, 0, width, height)
        graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 18)
        graphics.color = Color.WHITE
        graphics.drawString("Synthetic native full-window sharing benchmark", 16, 100)
        graphics.drawString("const connected = await share.takeControl();", 16, 128)
        graphics.drawString("0123456789 AaBbCcDd [] {} () <> /? +-= _", 16, 156)
        for (row in 0 until 12) {
            graphics.color = Color(119, 189, 251)
            graphics.drawString("row $row terminal output 0123456789", 16, 245 + (row * 31 + id * 4) % 450)
        }
        graphics.color = Color(228, 85, 102)
        graphics.fillRect(id * 7 % 1190, 655, 90, 45)
        val values = intArrayOf(0xd3, id ushr 16, id ushr 8, id, (id ushr 16 xor (id ushr 8) xor id xor 0xa5) and 255)
        for (bit in 0 until 40) {
            graphics.color = if ((values[bit / 8] ushr (7 - bit % 8)) and 1 == 1) Color.WHITE else Color.BLACK
            graphics.fillRect(16 + bit * 12, 16, 12, 32)
        }
    }
}

/** Direct Compose drawing: no Swing Timer, SwingPanel readback, or per-frame text layout. */
@Suppress("LongMethod") // One fixed workload keeps source-paint timing adjacent to all synthetic pixels.
@Composable
private fun NativeComposeBenchmarkPattern(
    painted: ConcurrentHashMap<Int, Double>,
    counters: NativeBenchmarkCounters,
    fps: Int,
) {
    val pulse = remember { mutableLongStateOf(Long.MIN_VALUE) }
    val sequence = remember { AtomicInteger() }
    val measurer = rememberTextMeasurer(cacheSize = 20)
    val text =
        remember(measurer) {
            val labels =
                listOf(
                    "Synthetic native full-window sharing benchmark",
                    "const connected = await share.takeControl();",
                    "0123456789 AaBbCcDd [] {} () <> /? +-= _",
                ) + (0 until 12).map { "row $it terminal output 0123456789" }
            labels.map { label ->
                measurer.measure(
                    AnnotatedString(label),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp),
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
    LaunchedEffect(fps) {
        while (isActive) {
            // Time buckets avoid 30/60 Hz drift when a display's period is slightly off its nominal rate.
            withFrameNanos { pulse.longValue = it / (1_000_000_000L / fps) }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (pulse.longValue == Long.MIN_VALUE) return@Canvas
        val id = sequence.incrementAndGet()
        counters.increment("paints")
        painted[id] = System.nanoTime() / 1_000_000.0
        painted.remove(id - 4800)
        val sx = size.width / 1280f
        val sy = size.height / 720f
        drawRect(ComposeColor(22, 27, 34))
        for (line in 0 until 3) {
            drawText(text[line], ComposeColor.White, Offset(16 * sx, (100 + line * 28) * sy - text[line].firstBaseline))
        }
        for (row in 0 until 12) {
            val layout = text[row + 3]
            val y = (245 + (row * 31 + id * 4) % 450) * sy - layout.firstBaseline
            drawText(layout, ComposeColor(119, 189, 251), Offset(16 * sx, y))
        }
        drawRect(ComposeColor(228, 85, 102), Offset((id * 7 % 1190) * sx, 655 * sy), Size(90 * sx, 45 * sy))
        val values = intArrayOf(0xd3, id ushr 16, id ushr 8, id, (id ushr 16 xor (id ushr 8) xor id xor 0xa5) and 255)
        for (bit in 0 until 40) {
            val color = if ((values[bit / 8] ushr (7 - bit % 8)) and 1 == 1) ComposeColor.White else ComposeColor.Black
            drawRect(color, Offset((16 + bit * 12) * sx, 16 * sy), Size(12 * sx, 32 * sy))
        }
    }
}

private class NativeBenchmarkServer(
    private val painted: ConcurrentHashMap<Int, Double>,
    private val failure: AtomicReference<String?>,
    private val counters: NativeBenchmarkCounters,
    private val waitForFrame: Boolean,
    private val productionTransport: Boolean,
) : AutoCloseable {
    private val mailbox = AppRawFrameMailbox()
    private val rawWaiter = Semaphore(1)
    private var lastCapturedSource = 0
    val result = CompletableFuture<JsonObject>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor =
        Executors.newFixedThreadPool(3) { task ->
            Thread(task, "native-benchmark-http").apply { isDaemon = true }
        }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val prefix = "/${UUID.randomUUID()}/"
    private val productionAssets = if (productionTransport) AppSharingAssets() else null
    private val productionPage = productionAssets?.open(buildJsonObject {}, true, ::benchmarkRpc)
    val baseUrl = productionPage?.url?.substringBeforeLast('/')?.plus("/") ?: (origin + prefix)
    val entryUrl = baseUrl + if (productionTransport) "viewer.css" else "benchmark.html"
    val bootstrap =
        if (productionTransport) "import(new URL('./benchmark.mjs',location.href).href);undefined" else ""
    private val scripts =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("scripts/app-sharing") }
            .first { Files.isDirectory(it) }

    init {
        server.executor = executor
        server.createContext(prefix, ::handle)
        server.start()
    }

    private suspend fun benchmarkRpc(request: JsonObject): JsonObject =
        when (request["action"]?.jsonPrimitive?.content) {
            "benchmarkClock" -> {
                buildJsonObject { put("monotonicMs", System.nanoTime() / 1_000_000.0) }
            }

            "benchmarkMetrics" -> {
                val unavailable =
                    setOf(
                        "mailboxReads",
                        "http200",
                        "http204",
                        "httpUnknownMarkers",
                        "bytesServed",
                        "httpPaintAgeMicros",
                    )
                JsonObject(counters.snapshot().filterKeys { it !in unavailable })
            }

            "benchmarkTimestamps" -> {
                val ids = request.getValue("ids").jsonArray
                require(ids.size <= 2400)
                buildJsonObject {
                    ids.forEach { item ->
                        val id = item.jsonPrimitive.int
                        require(id in 1..0xffffff)
                        painted[id]?.let { put(id.toString(), it) }
                    }
                }
            }

            "benchmarkResult" -> {
                val report = request.getValue("report").jsonObject
                require(report.toString().length <= 100000)
                result.complete(report)
                buildJsonObject {}
            }

            else -> {
                error("Unknown synthetic benchmark action")
            }
        }

    // Observe marker validity and ordering before publishing this exact frame to the mailbox.
    @Suppress("NestedBlockDepth")
    fun offer(frame: AppRawCapturedFrame?) {
        if (frame == null) {
            counters.increment("captureEmpty")
        } else {
            counters.increment("captures")
            val id = frameSequence(frame.pixels)
            val timestamp = id?.let(painted::get)
            if (id == null || timestamp == null) {
                counters.increment("captureUnknownMarkers")
            } else {
                counters.increment("captureAgeSamples")
                counters.add("capturePaintAgeMicros", ((System.nanoTime() / 1_000_000.0 - timestamp) * 1000).toLong())
                if (id > lastCapturedSource) {
                    counters.increment("uniqueCapturedPaints")
                    if (lastCapturedSource > 0) {
                        counters.add("paintsSkippedByCapture", (id - lastCapturedSource - 1).toLong())
                    }
                    lastCapturedSource = id
                } else if (id < lastCapturedSource) {
                    counters.increment("captureSourceReorders")
                }
            }
        }
        if (productionTransport) productionPage?.rawFrames?.offer(frame) else mailbox.offer(frame)
    }

    @Suppress("TooGenericExceptionCaught") // Malformed fixture HTTP requests must return no resource.
    private fun handle(exchange: HttpExchange) {
        try {
            require(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}")
            when (val name = exchange.requestURI.rawPath.removePrefix(prefix)) {
                "raw" -> {
                    raw(exchange)
                }

                "clock" -> {
                    val clock = "{\"monotonicMs\":${System.nanoTime() / 1_000_000.0}}"
                    respond(exchange, "application/json", clock.toByteArray())
                }

                "metrics" -> {
                    require(exchange.requestMethod == "GET")
                    respond(exchange, "application/json", counters.snapshot().toString().toByteArray())
                }

                "result" -> {
                    require(exchange.requestMethod == "POST" && exchange.requestHeaders.getFirst("Origin") == origin)
                    val body = exchange.requestBody.readNBytes(100001)
                    require(body.size <= 100000)
                    result.complete(Json.parseToJsonElement(body.decodeToString()).jsonObject)
                    respond(exchange, "application/json", "{}".toByteArray())
                }

                else -> {
                    asset(exchange, name)
                }
            }
        } catch (_: Exception) {
            runCatching { exchange.sendResponseHeaders(400, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun raw(exchange: HttpExchange) {
        require(exchange.requestMethod == "POST" && exchange.requestHeaders.getFirst("Origin") == origin)
        check(failure.get() == null)
        val suppliedAfter = exchange.requestHeaders.getFirst("X-Benchmark-After")
        val after = suppliedAfter?.toLongOrNull() ?: -1L
        require(after >= -1)
        counters.increment("mailboxReads")
        val requestedWait = waitForFrame && exchange.requestHeaders.getFirst("X-Benchmark-Wait") == "true"
        val waiting = requestedWait && rawWaiter.tryAcquire()
        val snapshot =
            if (waiting) {
                try {
                    mailbox.awaitNext(after, 250)
                } finally {
                    rawWaiter.release()
                }
            } else {
                mailbox.latest()
            }
        exchange.responseHeaders.set("X-Benchmark-Wait-Accepted", waiting.toString())
        exchange.responseHeaders.set("X-Benchmark-Sequence", snapshot.sequence.toString())
        val pixels = snapshot.frame?.pixels
        val id = pixels?.let(::frameSequence)
        val timestamp = id?.let(painted::get)
        if (pixels == null || timestamp == null || after == snapshot.sequence) {
            counters.increment("http204")
            if (pixels != null && timestamp == null) counters.increment("httpUnknownMarkers")
            exchange.sendResponseHeaders(204, -1)
            return
        }
        exchange.responseHeaders.set("X-Benchmark-Frame", id.toString())
        exchange.responseHeaders.set("X-Benchmark-Painted-At", timestamp.toString())
        exchange.responseHeaders.set("X-Boss-App-Width", pixels.width.toString())
        exchange.responseHeaders.set("X-Boss-App-Height", pixels.height.toString())
        exchange.responseHeaders.set("X-Boss-App-Pixel-Format", pixels.format)
        val ageMicros = ((System.nanoTime() / 1_000_000.0 - timestamp) * 1000).toLong()
        respond(exchange, "application/octet-stream", pixels.bgra)
        counters.increment("http200")
        counters.add("bytesServed", pixels.bgra.size.toLong())
        counters.add("httpPaintAgeMicros", ageMicros)
    }

    private fun frameSequence(frame: AppRawWindowFrame): Int? {
        val bytes = IntArray(5)
        for (bit in 0 until 40) {
            val x = ((22 + bit * 12) * frame.width / 1280).coerceIn(0, frame.width - 1)
            val y = (32 * frame.height / 720).coerceIn(0, frame.height - 1)
            val offset = (y * frame.width + x) * if (frame.format == "NV12") 1 else 4
            bytes[bit / 8] = bytes[bit / 8] * 2 + if ((frame.bgra[offset].toInt() and 255) > 128) 1 else 0
        }
        val id = (bytes[1] shl 16) or (bytes[2] shl 8) or bytes[3]
        return id.takeIf { bytes[0] == 0xd3 && bytes[4] == ((bytes[1] xor bytes[2] xor bytes[3] xor 0xa5) and 255) }
    }

    private fun asset(
        exchange: HttpExchange,
        name: String,
    ) {
        require(exchange.requestMethod == "GET")
        val content =
            when (name) {
                "benchmark.html", "benchmark.css", "benchmark.mjs",
                "benchmark-metrics.mjs", "benchmark-production.mjs",
                -> {
                    Files.readAllBytes(scripts.resolve(name))
                }

                "media.mjs", "crypto.mjs", "encoded-worker.mjs", "recovery.mjs" -> {
                    checkNotNull(javaClass.getResourceAsStream("/app-sharing/$name")).use { it.readBytes() }
                }

                else -> {
                    error("Unknown fixture resource")
                }
            }
        val type =
            when {
                name.endsWith(".html") -> "text/html; charset=utf-8"
                name.endsWith(".css") -> "text/css; charset=utf-8"
                else -> "text/javascript; charset=utf-8"
            }
        respond(exchange, type, content)
    }

    private fun respond(
        exchange: HttpExchange,
        type: String,
        bytes: ByteArray,
    ) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; worker-src 'self'; " +
                "connect-src 'self'; media-src blob:; base-uri 'none'; frame-ancestors 'none'",
        )
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        productionPage?.close?.invoke()
        productionAssets?.close()
        mailbox.close()
        server.stop(0)
        executor.shutdownNow()
    }
}

/** Scalar counters only; no source identifiers, raw pixels or collector names enter reports. */
private class NativeBenchmarkCounters(
    private val diagnostics: AppCaptureDiagnostics? = null,
) {
    private val values =
        listOf(
            "paints",
            "captures",
            "captureEmpty",
            "captureUnknownMarkers",
            "captureAgeSamples",
            "capturePaintAgeMicros",
            "uniqueCapturedPaints",
            "paintsSkippedByCapture",
            "captureSourceReorders",
            "mailboxReads",
            "http200",
            "http204",
            "httpUnknownMarkers",
            "bytesServed",
            "httpPaintAgeMicros",
        ).associateWith { AtomicLong() }

    fun increment(name: String) {
        values.getValue(name).incrementAndGet()
    }

    fun add(
        name: String,
        amount: Long,
    ) {
        values.getValue(name).addAndGet(amount)
    }

    fun snapshot(): JsonObject =
        buildJsonObject {
            values.forEach { (name, value) -> put(name, value.get()) }
            diagnostics?.snapshot()?.let { snapshot ->
                put(
                    "captureDiagnostics",
                    buildJsonObject {
                        put("boundsNanos", buildJsonArray { AppCaptureDiagnostics.BOUNDS_NANOS.forEach { add(it) } })
                        put(
                            "counters",
                            buildJsonObject { snapshot.counters.forEach { (key, value) -> put(key, value) } },
                        )
                        put(
                            "timings",
                            buildJsonObject {
                                snapshot.timings.forEach { (name, timing) ->
                                    put(
                                        name,
                                        buildJsonObject {
                                            put("samples", timing.samples)
                                            put("totalNanos", timing.totalNanos)
                                            put("buckets", buildJsonArray { timing.buckets.forEach { add(it) } })
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
            val collectors = ManagementFactory.getGarbageCollectorMXBeans()
            val available = collectors.all { it.collectionCount >= 0 && it.collectionTime >= 0 }
            put("gcAvailable", available)
            if (available) {
                put("gcCollections", collectors.sumOf { it.collectionCount })
                put("gcCollectionTimeMs", collectors.sumOf { it.collectionTime })
            }
            val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            put("heapUsedBytes", heap.used)
            put("heapCommittedBytes", heap.committed)
            put("heapMaxBytes", heap.max)
            put("monotonicMs", System.nanoTime() / 1_000_000.0)
        }
}
