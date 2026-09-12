import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishPluginTaskTest {
    private data class Request(val method: String, val path: String, val authorization: String?, val body: ByteArray)

    @Test
    fun `real task serializes publishing values and keeps credentials out of output`() {
        val directory = Files.createTempDirectory("publish-task-test").toFile()
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val token = "synthetic-gradle-publish-token"
        val special = "Quotes \"' \\ & | < > ^ ! % ( ) 雪\nsecond line"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        var existenceStatus = 404
        server.createContext("/") { exchange ->
            val request = Request(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.use { it.readAllBytes() },
            )
            requests.add(request)
            val status = if (request.method == "GET") existenceStatus else 200
            val payload = if (request.path.endsWith("/version")) {
                mapOf("versionId" to special, "uploadUrl" to "$base/upload?signature=$token")
            } else {
                mapOf("message" to token)
            }
            val body = JsonOutput.toJson(payload).toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val jar = directory.resolve("plugin.jar")
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Plugin-Id", "plugin/a?b#c %雪")
                mainAttributes.putValue("Plugin-Version", "1.2.3")
            }
            JarOutputStream(jar.outputStream(), manifest).use {
                it.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
                it.write("""{"minBossVersion":"0.7.8"}""".toByteArray())
                it.closeEntry()
            }
            directory.resolve("settings.gradle").writeText("rootProject.name = 'publisher-test'")
            directory.resolve("value.txt").writeText(special)
            val classDirectory = PublishPluginTask::class.java.protectionDomain.codeSource.location.toURI().path
            directory.resolve("build.gradle").writeText(
                """
                buildscript { dependencies { classpath files(${JsonOutput.toJson(classDirectory)}) } }
                tasks.register('publishTest', PublishPluginTask) {
                    jarFile.set(file('plugin.jar'))
                    storeUrl.set(${JsonOutput.toJson(base)})
                    displayName.set(file('value.txt').text)
                    pluginDescription.set(file('value.txt').text)
                    changelog.set(file('value.txt').text)
                    tags.set('alpha,"quoted",雪')
                    homepageUrl.set('https://example.invalid/plugin')
                }
                """.trimIndent(),
            )
            val runner = GradleRunner.create()
                .withProjectDir(directory)
                .withArguments("publishTest", "--configuration-cache", "--stacktrace")
                .withEnvironment(System.getenv() + ("BOSS_PLUGIN_STORE_TOKEN" to token))
            val result = runner.build()
            assertFalse(result.output.contains(token))
            assertEquals(listOf("GET", "POST", "POST", "PUT", "POST"), requests.map { it.method })
            assertEquals("/plugin%2Fa%3Fb%23c%20%25%E9%9B%AA", requests[0].path)
            val creation = JsonSlurper().parse(requests[1].body) as Map<*, *>
            assertEquals(special, creation["displayName"])
            assertEquals(special, creation["description"])
            assertEquals(listOf("alpha", "\"quoted\"", "雪"), creation["tags"])
            val version = JsonSlurper().parse(requests[2].body) as Map<*, *>
            assertEquals("0.7.8", version["minBossVersion"])
            assertEquals(special, version["changelog"])
            assertTrue(jar.readBytes().contentEquals(requests[3].body))
            assertEquals(null, requests[3].authorization)
            requests.filter { it.method != "PUT" }.forEach { assertEquals("Bearer $token", it.authorization) }
            assertTrue(result.output.contains("Configuration cache entry discarded"))

            requests.clear()
            existenceStatus = 403
            val refused = runner.buildAndFail()
            assertFalse(refused.output.contains(token))
            assertTrue(refused.output.contains("HTTP 403"))
            assertEquals(1, requests.size)
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
