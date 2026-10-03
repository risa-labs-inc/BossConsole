package ai.rever.boss.sharing

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppRawFrameWaitTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun `negotiated reader receives fresh binary pixels and empty sequence while legacy polling stays supported`() {
        AppSharingAssets().use { server ->
            val page = server.open(buildJsonObject {}, true) { buildJsonObject {} }
            val waiting = client.sendAsync(request(page, "0"), HttpResponse.BodyHandlers.ofByteArray())
            awaitAdmission(page)
            val bytes = byteArrayOf(30, 20, 10, -1)
            page.rawFrames.offer(frame(bytes))
            val fresh = waiting.get(2, TimeUnit.SECONDS)
            assertEquals(200, fresh.statusCode())
            assertTrue(bytes.contentEquals(fresh.body()))
            assertEquals("true", fresh.headers().firstValue("X-Boss-App-Wait").orElseThrow())
            assertEquals("1", fresh.headers().firstValue("X-Boss-App-Sequence").orElseThrow())
            val idle = client.send(request(page, "1"), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(204, idle.statusCode())
            assertEquals("1", idle.headers().firstValue("X-Boss-App-Sequence").orElseThrow())
            assertEquals("false", idle.headers().firstValue("X-Boss-App-Empty").orElseThrow())
            val legacy = client.send(request(page, "-1", wait = false), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, legacy.statusCode())
            assertFalse(legacy.headers().firstValue("X-Boss-App-Wait").isPresent)
            page.rawFrames.offer(null)
            val empty = client.send(request(page, "1"), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(204, empty.statusCode())
            assertEquals("2", empty.headers().firstValue("X-Boss-App-Sequence").orElseThrow())
            assertEquals("true", empty.headers().firstValue("X-Boss-App-Empty").orElseThrow())
        }
    }

    @Test
    fun `negotiation authenticates and rejects malformed sequences and request bodies before admission`() {
        AppSharingAssets().use { server ->
            val page = server.open(buildJsonObject {}, true) { buildJsonObject {} }
            listOf("", "not-a-number", "-2", "1", "9007199254740992").forEach { after ->
                assertEquals(400, status(request(page, after)))
            }
            assertEquals(400, status(request(page, "0", body = "payload")))
            assertEquals(403, status(request(page, "0", token = "invalid")))
            assertEquals(30, page.rawFrames.frameRate, "Rejected requests must not alter capture preferences")
            val viewer = server.open(buildJsonObject {}, false) { buildJsonObject {} }
            assertEquals(403, status(request(viewer, "-1")))
            assertEquals(204, status(request(page, "-1")))
        }
    }

    @Test
    fun `retiring a waiting page releases delivery and prevents late captured pixels from returning`() {
        AppSharingAssets().use { server ->
            val page = server.open(buildJsonObject {}, true) { buildJsonObject {} }
            val waiting = client.sendAsync(request(page, "0"), HttpResponse.BodyHandlers.ofByteArray())
            awaitAdmission(page)
            page.close()
            waiting
                .handle { response, _ ->
                    // A clear may win the close race; any successful response must contain no pixels.
                    if (response != null) assertEquals(204, response.statusCode())
                }.get(2, TimeUnit.SECONDS)
            page.rawFrames.offer(frame(byteArrayOf(30, 20, 10, -1)))
            assertEquals(null, page.rawFrames.latest().frame)
            assertEquals(404, status(request(page, "-1")))
            val replacement = server.open(buildJsonObject {}, true) { buildJsonObject {} }
            assertEquals(204, status(request(replacement, "-1")))
        }
    }

    private fun status(request: HttpRequest) = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun awaitAdmission(page: AppViewerPage) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (page.rawFrames.frameRate != 60 && System.nanoTime() < until) Thread.sleep(1)
        assertEquals(60, page.rawFrames.frameRate)
    }

    private fun frame(bytes: ByteArray) =
        AppRawCapturedFrame(
            AppRawWindowFrame(bytes, 1, 1),
            1,
            AppSurfaceSnapshot(emptyList(), 0, 0, 1, 1, 1, 1),
        )

    private fun request(
        page: AppViewerPage,
        after: String,
        wait: Boolean = true,
        body: String = "",
        token: String? = null,
    ): HttpRequest {
        val uri = URI(page.url)
        return HttpRequest
            .newBuilder(uri.resolve("raw-frame"))
            .header("Origin", "${uri.scheme}://${uri.authority}")
            .header("X-Boss-App-Token", token ?: uri.path.split('/')[1])
            .header("X-Boss-App-After", after)
            .header("X-Boss-App-Frame-Rate", "60")
            .apply { if (wait) header("X-Boss-App-Wait", "true") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(3))
            .build()
    }
}
