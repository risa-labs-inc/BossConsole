package ai.rever.boss.sharing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real HTTP/socket tests: response cancellation must unblock a real JDK SocketChannel writer. */
class AppRawFrameDeliveryTest {
    @Test
    fun `sixteen readers never occupy HTTP control workers and duplicate or excess admission stays bounded`() {
        val entered = CountDownLatch(16)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        Fixture { exchange, _ ->
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            entered.countDown()
            try {
                release.await()
                exchange.sendResponseHeaders(204, -1)
            } finally {
                active.decrementAndGet()
            }
        }.use { fixture ->
            assertEquals(200, fixture.fast()) // Warm the real HTTP client before timing admission.
            val reads = (0 until 16).map(fixture::read)
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertEquals(409, fixture.read(0).get(750, TimeUnit.MILLISECONDS).statusCode())
                assertEquals(503, fixture.read(16).get(750, TimeUnit.MILLISECONDS).statusCode())
                assertEquals(200, fixture.fast())
                assertEquals(16, maximum.get())
            } finally {
                release.countDown()
            }
            reads.forEach { assertEquals(204, it.get(2, TimeUnit.SECONDS).statusCode()) }
        }
    }

    @Test
    fun `page retirement interrupts an admitted wait and rejects future delivery`() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        Fixture { exchange, _ ->
            entered.countDown()
            try {
                release.await()
                published.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
            } finally {
                exited.countDown()
            }
        }.use { fixture ->
            val response = fixture.read(0)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.pages[0].close()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
            response.handle { _, _ -> true }.get(1, TimeUnit.SECONDS)
            assertEquals(0, published.get())
            assertEquals(410, fixture.read(0).get(1, TimeUnit.SECONDS).statusCode())
            assertEquals(200, fixture.fast())
        }
    }

    @Test
    fun `retirement before worker execution cannot deliver a late response`() {
        val start = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val calls = AtomicInteger()
        val factory =
            ThreadFactory { work ->
                Thread({
                    try {
                        start.await()
                    } catch (_: InterruptedException) {
                        // Executor shutdown still runs the canceled task's cleanup wrapper.
                    }
                    try {
                        work.run()
                    } finally {
                        workerExited.countDown()
                    }
                }, "synthetic-delayed-raw-worker").apply { isDaemon = true }
            }
        val delivery = AppRawFrameDelivery(factory)
        Fixture(delivery) { exchange, _ ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
        }.use { fixture ->
            val response = fixture.read(0)
            assertTrue(fixture.admitted.await(1, TimeUnit.SECONDS))
            fixture.pages[0].close()
            start.countDown()
            response.handle { _, _ -> true }.get(1, TimeUnit.SECONDS)
            delivery.close()
            assertTrue(workerExited.await(1, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
            assertEquals(410, fixture.read(1).get(1, TimeUnit.SECONDS).statusCode())
        }
    }

    @Test
    fun `stalled response writer is interrupted by the total deadline without blocking other HTTP requests`() {
        val writing = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val bytes = ByteArray(16 * 1024 * 1024)
        Fixture { exchange, _ ->
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                writing.countDown()
                exchange.responseBody.write(bytes)
            } finally {
                exited.countDown()
            }
        }.use { fixture ->
            assertEquals(200, fixture.fast())
            Socket().use { socket ->
                socket.receiveBufferSize = 1024
                socket.connect(fixture.address)
                val request =
                    "POST /raw/0 HTTP/1.1\r\nHost: 127.0.0.1:${fixture.address.port}\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                assertTrue(writing.await(1, TimeUnit.SECONDS))
                assertFalse(exited.await(100, TimeUnit.MILLISECONDS), "The real response write must be stalled")
                assertEquals(200, fixture.fast())
                assertTrue(
                    exited.await(3, TimeUnit.SECONDS),
                    "Deadline must unblock the writer before the client closes",
                )
            }
        }
    }

    @Test
    fun `pool retirement cancels every active page while independent HTTP handling remains live`() {
        val entered = CountDownLatch(2)
        val exited = CountDownLatch(2)
        val release = CountDownLatch(1)
        Fixture { _, _ ->
            entered.countDown()
            try {
                release.await()
            } finally {
                exited.countDown()
            }
        }.use { fixture ->
            val reads = listOf(fixture.read(0), fixture.read(1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.delivery.close()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
            reads.forEach { it.handle { _, _ -> true }.get(1, TimeUnit.SECONDS) }
            assertEquals(410, fixture.read(2).get(1, TimeUnit.SECONDS).statusCode())
            assertEquals(200, fixture.fast())
        }
    }

    private class Fixture(
        val delivery: AppRawFrameDelivery = AppRawFrameDelivery(),
        private val action: (HttpExchange, Int) -> Unit,
    ) : AutoCloseable {
        val pages = List(17) { delivery.newPage() }
        val admitted = CountDownLatch(1)
        private val httpWorkers = Executors.newFixedThreadPool(4)
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 32).apply {
                executor = httpWorkers
                createContext("/", ::handle)
                start()
            }
        val address: InetSocketAddress get() = server.address

        private fun handle(exchange: HttpExchange) {
            var transferred = false
            try {
                if (exchange.requestURI.path == "/fast") {
                    reply(exchange, 200)
                } else {
                    check(exchange.requestMethod == "POST")
                    check(exchange.requestHeaders.getFirst("Transfer-Encoding") == null)
                    check(exchange.requestHeaders.getFirst("Content-Length") in listOf(null, "0"))
                    val index =
                        exchange.requestURI.path
                            .substringAfterLast('/')
                            .toInt()
                    val admission = pages[index].submit(exchange) { action(exchange, index) }
                    transferred = admission == AppRawFrameDelivery.Admission.ACCEPTED
                    if (transferred) {
                        admitted.countDown()
                    } else {
                        reply(
                            exchange,
                            when (admission) {
                                AppRawFrameDelivery.Admission.DUPLICATE -> 409
                                AppRawFrameDelivery.Admission.SATURATED -> 503
                                else -> 410
                            },
                        )
                    }
                }
            } finally {
                if (!transferred) exchange.close()
            }
        }

        fun read(index: Int): CompletableFuture<HttpResponse<ByteArray>> =
            client.sendAsync(
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${address.port}/raw/$index"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )

        fun fast(): Int =
            client
                .sendAsync(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:${address.port}/fast")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                ).get(750, TimeUnit.MILLISECONDS)
                .statusCode()

        private fun reply(
            exchange: HttpExchange,
            status: Int,
        ) {
            val bytes = status.toString().toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            delivery.close()
            server.stop(0)
            httpWorkers.shutdownNow()
        }
    }

    companion object {
        private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    }
}
