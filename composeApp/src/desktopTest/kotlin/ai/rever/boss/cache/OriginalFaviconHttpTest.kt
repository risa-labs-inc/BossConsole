package ai.rever.boss.cache

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginalFaviconHttpTest {
    @Test
    fun `redirects cannot reach a private service`(): Unit =
        runTest {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1/private"))
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                assertNull(fetch(client, "https://example.com/favicon.ico", false))
                assertEquals(1, requests)
            }
        }

    @Test
    fun `image bodies exceeding the cap are rejected even without Content Length`(): Unit =
        runTest {
            val oversized = ByteArray(MAX_ORIGINAL_FAVICON_BYTES + 1)
            HttpClient(MockEngine { respond(ByteReadChannel(oversized)) }) {
                configureOriginalFaviconClient()
            }.use { client ->
                assertNull(fetch(client, "https://example.com/favicon.ico", false))
            }
        }

    @Test
    fun `an oversized homepage keeps its bounded prefix and early icon declarations`(): Unit =
        runTest {
            val html = "<link rel='icon' href='/logo.svg'>" + " ".repeat(MAX_ORIGINAL_FAVICON_BYTES)
            HttpClient(
                MockEngine {
                    respond(
                        ByteReadChannel(html.toByteArray()),
                        headers = headersOf(HttpHeaders.ContentLength, html.length.toString()),
                    )
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                val prefix = assertNotNull(fetch(client, "https://example.com/", true))
                assertEquals(MAX_ORIGINAL_FAVICON_BYTES, prefix.size)
                assertEquals(
                    listOf("https://example.com/logo.svg"),
                    OriginalFaviconSource.iconLinks("https://example.com/", prefix.toString(Charsets.UTF_8)),
                )
            }
        }

    @Test
    fun `non-success status and excessive declared image length are rejected`(): Unit =
        runTest {
            HttpClient(MockEngine { respond("missing", HttpStatusCode.NotFound) }) {
                configureOriginalFaviconClient()
            }.use { client -> assertNull(fetch(client, "https://example.com/favicon.ico", false)) }
            HttpClient(
                MockEngine {
                    respond("image", headers = headersOf(HttpHeaders.ContentLength, "999999"))
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                assertNull(fetch(client, "https://example.com/favicon.ico", false))
            }
        }

    @Test
    fun `public DNS answers cannot route original discovery to private or local addresses`() {
        for (ip in listOf(
            "127.0.0.1",
            "10.0.0.1",
            "192.168.1.1",
            "172.16.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
        )) {
            assertFalse(isPublicFaviconAddress(InetAddress.getByName(ip)), ip)
        }
        assertTrue(isPublicFaviconAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicFaviconAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun `a legitimate public CDN redirect is checked and followed`(): Unit =
        runTest {
            val requests = mutableListOf<String>()
            val checkedHosts = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requests.add(request.url.host)
                    if (request.url.host == "example.com") {
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://cdn.example.com/icon.ico"),
                        )
                    } else {
                        respond("image")
                    }
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                val result =
                    fetchOriginalFavicon(client, "https://example.com/favicon.ico", false) { host ->
                        checkedHosts.add(host)
                        true
                    }
                assertEquals("image", assertNotNull(result).toString(Charsets.UTF_8))
                assertEquals(listOf("example.com", "cdn.example.com"), requests)
                assertEquals(requests, checkedHosts)
            }
        }

    @Test
    fun `a redirect to a public hostname with private DNS answers is rejected before requesting it`(): Unit =
        runTest {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://cdn.example.com/icon.ico"),
                    )
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                assertNull(
                    fetchOriginalFavicon(client, "https://example.com/favicon.ico", false) {
                        it == "example.com"
                    },
                )
                assertEquals(1, requests)
            }
        }

    @Test
    fun `redirect loops are bounded`(): Unit =
        runTest {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/again.ico"))
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                assertNull(fetch(client, "https://example.com/favicon.ico", false))
                assertEquals(4, requests)
            }
        }

    // Keep HTTP-engine tests hermetic; address policy itself is exercised with literal addresses.
    private suspend fun fetch(
        client: HttpClient,
        url: String,
        htmlPrefix: Boolean,
    ): ByteArray? = fetchOriginalFavicon(client, url, htmlPrefix, addressAllowed = { true })
}
