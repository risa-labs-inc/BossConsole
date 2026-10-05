package ai.rever.boss.cache

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIOEngineConfig
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
    fun `discovery reuses clients for each TLS host across icons and redirects`(): Unit =
        runTest {
            val created = mutableListOf<String>()
            OriginalFaviconHttpSession(
                createClient = { host ->
                    created.add(host)
                    HttpClient(
                        MockEngine { request ->
                            if (request.headers[HttpHeaders.Host] == "example.com") {
                                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.com/icon"))
                            } else {
                                respond("icon")
                            }
                        },
                    ) { configureOriginalFaviconClient() }
                },
                resolveAddresses = { listOf(InetAddress.getByName("8.8.8.8")) },
            ).use { session ->
                assertNotNull(session.fetch("https://example.com/favicon.ico"))
                assertNotNull(session.fetch("https://example.com/second.png"))
                assertEquals(listOf("example.com", "cdn.example.com"), created)
            }
        }

    @Test
    fun `IPv6 routes use a bracketed authority while preserving site Host`(): Unit =
        runTest {
            HttpClient(
                MockEngine { request ->
                    assertEquals("example.com", request.headers[HttpHeaders.Host])
                    assertTrue(request.url.toString().startsWith("https://[2606:4700:4700:"))
                    respond("icon")
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                assertNotNull(
                    fetchOriginalFavicon(
                        client,
                        "https://example.com/favicon.ico",
                        false,
                        resolveAddresses = { listOf(InetAddress.getByName("2606:4700:4700::1111")) },
                    ),
                )
            }
        }

    @Test
    fun `redirects cannot reach a private service`(): Unit =
        runTest {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://127.0.0.1/private"))
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
            "::ffff:127.0.0.1",
            "64:ff9b::a00:1",
            "2002:7f00:1::",
            "2001::1",
            "2001:db8::1",
            "192.0.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
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
                    requests.add(assertNotNull(request.headers[HttpHeaders.Host]))
                    if (request.headers[HttpHeaders.Host] == "example.com") {
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
                        listOf(publicAddress)
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
                        listOf(if (it == "example.com") publicAddress else InetAddress.getByName("127.0.0.1"))
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
    ): ByteArray? = fetchOriginalFavicon(client, url, htmlPrefix, resolveAddresses = { listOf(publicAddress) })

    @Test
    fun `connection uses the vetted IP without resolving the original host again`(): Unit =
        runTest {
            var lookups = 0
            HttpClient(
                MockEngine { request ->
                    assertEquals("8.8.8.8", request.url.host)
                    assertEquals("example.com:8443", request.headers[HttpHeaders.Host])
                    assertEquals("/icon.ico?size=128", request.url.encodedPath + "?" + request.url.encodedQuery)
                    respond("image")
                },
            ) { configureOriginalFaviconClient() }.use { client ->
                val result =
                    fetchOriginalFavicon(client, "https://example.com:8443/icon.ico?size=128", false) {
                        lookups++
                        // A second DNS answer would be unsafe. The transport must use the first result.
                        listOf(if (lookups == 1) publicAddress else InetAddress.getByName("127.0.0.1"))
                    }
                assertEquals("image", assertNotNull(result).toString(Charsets.UTF_8))
                assertEquals(1, lookups)
            }
        }

    @Test
    fun `pinned HTTPS preserves the original certificate name and default trust`() {
        pinnedOriginalFaviconClient("example.com").use { client ->
            val config = client.engine.config as CIOEngineConfig
            assertEquals("example.com", config.https.serverName)
            assertNull(config.https.trustManager)
        }
    }

    private val publicAddress = InetAddress.getByName("8.8.8.8")
}
