import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.gradle.api.tasks.TaskAction
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gradle task to publish a plugin to the BOSS Plugin Store.
 *
 * Set BOSS_PLUGIN_STORE_TOKEN in the environment before publishing.
 * Usage:
 * ```
 * ./gradlew :plugin-platform:plugin-my-plugin:publishPlugin \
 *   -PauthorName="My Company"
 * ```
 *
 * The task reads metadata from the JAR manifest and publishes to the store.
 */
abstract class PublishPluginTask : DefaultTask() {
    init {
        group = "publishing"
        description = "Publishes the plugin to the BOSS Plugin Store"
        notCompatibleWithConfigurationCache("Publishing reads credentials only during execution")
        doNotTrackState("Publishing changes remote state and must not cache credentials or outcomes")
    }

    /**
     * The plugin JAR file to publish
     */
    @get:InputFile
    abstract val jarFile: RegularFileProperty

    /**
     * Plugin ID (e.g., "ai.rever.boss.plugin.myplugin")
     * If not specified, will be read from JAR manifest
     */
    @get:Input
    @get:Optional
    abstract val pluginId: Property<String>

    /**
     * Human-readable plugin display name
     * If not specified, will be read from JAR manifest
     */
    @get:Input
    @get:Optional
    abstract val displayName: Property<String>

    /**
     * Plugin version (semver format, e.g., "1.0.0")
     * If not specified, will be read from JAR manifest
     */
    @get:Input
    @get:Optional
    abstract val pluginVersion: Property<String>

    /**
     * Author name (optional, defaults to logged-in user)
     */
    @get:Input
    @get:Optional
    abstract val authorName: Property<String>

    /**
     * Plugin description
     */
    @get:Input
    @get:Optional
    abstract val pluginDescription: Property<String>

    /**
     * Changelog for this version
     */
    @get:Input
    @get:Optional
    abstract val changelog: Property<String>

    /**
     * Comma-separated list of tags
     */
    @get:Input
    @get:Optional
    abstract val tags: Property<String>

    /** Required by the store when creating a new plugin. */
    @get:Input
    @get:Optional
    abstract val homepageUrl: Property<String>

    /**
     * Plugin store URL
     * Defaults to production store URL
     */
    @get:Input
    @get:Optional
    abstract val storeUrl: Property<String>

    /**
     * Supabase anonymous key (optional)
     */
    @get:Input
    @get:Optional
    abstract val anonKey: Property<String>

    @TaskAction
    fun publish() {
        try {
            publishArtifact()
        } catch (error: GradleException) {
            throw error
        } catch (error: Exception) {
            // Transport/parser exceptions may embed signed URLs or response bodies.
            throw GradleException("Publishing failed (${error.javaClass.simpleName}); check connectivity and metadata")
        }
    }

    private fun publishArtifact() {
        val jar = jarFile.get().asFile
        if (!jar.exists()) {
            throw GradleException("JAR file not found: ${jar.absolutePath}")
        }

        logger.lifecycle("Publishing plugin to BOSS Plugin Store")
        logger.lifecycle("============================================")

        // Read JAR metadata
        val jarSize = jar.length()
        val sha256 = calculateSha256(jar)

        // Extract metadata from manifest if not provided
        val manifest = readManifest(jar)
        val pluginMetadata = java.util.jar.JarFile(jar).use { archive ->
            archive.getJarEntry("META-INF/boss-plugin/plugin.json")?.let { entry ->
                archive.getInputStream(entry).bufferedReader().use { JsonSlurper().parseText(it.readText()) }
            }
        }
        val metadata = if (pluginMetadata is Map<*, *>) pluginMetadata else emptyMap<String, String>()
        val minimumVersion = metadata["minBossVersion"]?.let {
            require(it is String && it.isNotBlank()) { "Invalid minBossVersion in plugin metadata" }
            it
        } ?: "1.0.0"

        val actualPluginId =
            pluginId.orNull
                ?: manifest["Plugin-Id"]
                ?: throw GradleException("Plugin ID not found. Provide via pluginId property or Plugin-Id manifest entry.")

        val actualDisplayName =
            displayName.orNull
                ?: manifest["Plugin-Name"]
                ?: actualPluginId

        val actualVersion =
            pluginVersion.orNull
                ?: manifest["Plugin-Version"]
                ?: throw GradleException("Version not found. Provide via pluginVersion property or Plugin-Version manifest entry.")

        logger.lifecycle("  Plugin ID:    $actualPluginId")
        logger.lifecycle("  Display Name: $actualDisplayName")
        logger.lifecycle("  Version:      $actualVersion")
        logger.lifecycle("  JAR Size:     $jarSize bytes")
        logger.lifecycle("  SHA256:       ${sha256.take(16)}...")

        val baseUrl =
            storeUrl.orNull
                ?: System.getenv("BOSS_PLUGIN_STORE_URL")
                ?: "https://api.risaboss.com/functions/v1/plugin-store"

        val token =
            System.getenv("BOSS_PLUGIN_STORE_TOKEN")
                ?: throw GradleException(
                    "Set BOSS_PLUGIN_STORE_TOKEN in the environment before publishing.",
                )

        val apiKey = anonKey.orNull ?: System.getenv("SUPABASE_ANON_KEY") ?: ""
        require(token.isNotBlank() && listOf(token, apiKey).none { '\r' in it || '\n' in it }) {
            "Invalid publishing credentials"
        }

        // Step 1: Check if plugin exists
        logger.lifecycle("Checking plugin existence...")
        val pluginExists = checkPluginExists(baseUrl, actualPluginId, token, apiKey)

        // Step 2: Create plugin if it doesn't exist
        if (!pluginExists) {
            logger.lifecycle("Creating plugin entry...")
            createPlugin(
                baseUrl = baseUrl,
                pluginId = actualPluginId,
                displayName = actualDisplayName,
                description = pluginDescription.orNull ?: "",
                authorName = authorName.orNull,
                homepageUrl = homepageUrl.orNull ?: (metadata["homepageUrl"] as? String) ?: manifest["Plugin-Url"]
                    ?: throw GradleException("Set homepageUrl when creating a new plugin"),
                tags = tags.orNull?.split(",")?.map { it.trim() } ?: emptyList(),
                token = token,
                apiKey = apiKey,
            )
            logger.lifecycle("  Plugin entry created")
        } else {
            logger.lifecycle("  Plugin exists, publishing new version")
        }

        // Step 3: Create version and get upload URL
        logger.lifecycle("Creating version entry...")
        val (versionId, uploadUrl) =
            createVersion(
                baseUrl = baseUrl,
                pluginId = actualPluginId,
                version = actualVersion,
                changelog = changelog.orNull ?: "",
                minBossVersion = minimumVersion,
                token = token,
                apiKey = apiKey,
            )
        logger.lifecycle("  Version entry created")

        // Step 4: Upload JAR
        logger.lifecycle("Uploading JAR file...")
        uploadJar(uploadUrl, jar)
        logger.lifecycle("  JAR uploaded successfully")

        // Step 5: Finalize version
        logger.lifecycle("Finalizing version...")
        finalizeVersion(
            baseUrl = baseUrl,
            versionId = versionId,
            sha256 = sha256,
            jarSize = jarSize,
            token = token,
            apiKey = apiKey,
        )
        logger.lifecycle("  Version finalized")

        logger.lifecycle("")
        logger.lifecycle("============================================")
        logger.lifecycle("Plugin published successfully!")
        logger.lifecycle("  Plugin ID: $actualPluginId")
        logger.lifecycle("  Version:   $actualVersion")
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun readManifest(jarFile: java.io.File): Map<String, String> {
        val manifest = mutableMapOf<String, String>()
        try {
            java.util.jar.JarFile(jarFile).use { jar ->
                val mf = jar.manifest
                mf?.mainAttributes?.forEach { key, value ->
                    manifest[key.toString()] = value.toString()
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not read JAR manifest: ${e.message}")
        }
        return manifest
    }

    private fun checkPluginExists(
        baseUrl: String,
        pluginId: String,
        token: String,
        apiKey: String,
    ): Boolean {
        val response = httpGet("$baseUrl/${pathSegment(pluginId)}", token, apiKey)
        return when (response.statusCode) {
            200 -> true
            404 -> false
            else -> throw GradleException("Plugin existence check failed: HTTP ${response.statusCode}")
        }
    }

    private fun createPlugin(
        baseUrl: String,
        pluginId: String,
        displayName: String,
        description: String,
        authorName: String?,
        homepageUrl: String,
        tags: List<String>,
        token: String,
        apiKey: String,
    ) {
        val body = JsonOutput.toJson(mapOf(
            "pluginId" to pluginId,
            "displayName" to displayName,
            "description" to description,
            "authorName" to authorName,
            "homepageUrl" to homepageUrl,
            "tags" to tags,
        ))

        val response = httpPost("$baseUrl/publish", body, token, apiKey)
        if (response.statusCode !in 200..201) {
            throw GradleException("Failed to create plugin: HTTP ${response.statusCode}")
        }
    }

    private fun createVersion(
        baseUrl: String,
        pluginId: String,
        version: String,
        changelog: String,
        minBossVersion: String,
        token: String,
        apiKey: String,
    ): Pair<String, String> {
        val body = JsonOutput.toJson(mapOf(
            "version" to version,
            "changelog" to changelog,
            "minBossVersion" to minBossVersion,
        ))

        val response = httpPost("$baseUrl/${pathSegment(pluginId)}/version", body, token, apiKey)
        if (response.statusCode !in 200..201) {
            throw GradleException("Failed to create version: HTTP ${response.statusCode}")
        }

        val versionId =
            extractJsonValue(response.body, "versionId")
                ?: throw GradleException("No versionId in response")
        val uploadUrl =
            extractJsonValue(response.body, "uploadUrl")
                ?: throw GradleException("No uploadUrl in response")

        return versionId to uploadUrl
    }

    private fun uploadJar(
        uploadUrl: String,
        jar: File,
    ) {
        val connection = publishingConnection(uploadUrl)
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(jar.length())
        jar.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }

        val responseCode = connection.responseCode
        if (responseCode !in 200..201) {
            throw GradleException("Failed to upload JAR: HTTP $responseCode")
        }
    }

    private fun finalizeVersion(
        baseUrl: String,
        versionId: String,
        sha256: String,
        jarSize: Long,
        token: String,
        apiKey: String,
    ) {
        val body = JsonOutput.toJson(mapOf(
            "versionId" to versionId,
            "sha256" to sha256,
            "jarSize" to jarSize,
        ))

        val response = httpPost("$baseUrl/version/finalize", body, token, apiKey)
        if (response.statusCode != 200) {
            throw GradleException("Failed to finalize version: HTTP ${response.statusCode}")
        }
    }

    private fun httpGet(
        url: String,
        token: String,
        apiKey: String,
    ): HttpResponse {
        val connection = publishingConnection(url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (apiKey.isNotEmpty()) {
            connection.setRequestProperty("apikey", apiKey)
        }

        return readResponse(connection)
    }

    private fun httpPost(
        url: String,
        body: String,
        token: String,
        apiKey: String,
    ): HttpResponse {
        val connection = publishingConnection(url)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (apiKey.isNotEmpty()) {
            connection.setRequestProperty("apikey", apiKey)
        }

        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(body)
        }

        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val statusCode = connection.responseCode
        val inputStream =
            if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val body =
            inputStream?.use { stream ->
                val bytes = stream.readNBytes(1024 * 1024 + 1)
                require(bytes.size <= 1024 * 1024) { "Publishing response exceeds the size limit" }
                bytes.toString(StandardCharsets.UTF_8)
            } ?: ""

        return HttpResponse(statusCode, body)
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val value = JsonSlurper().parseText(json)
        if (value !is Map<*, *>) return null
        val field = value[key]
        return if (field is String) field else null
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").let {
            if (it == "." || it == "..") it.replace(".", "%2E") else it
        }

    private fun publishingConnection(rawUrl: String): HttpURLConnection {
        val url = URL(rawUrl)
        val local = url.host in setOf("localhost", "127.0.0.1", "[::1]")
        require(url.protocol == "https" || (url.protocol == "http" && local)) {
            "Publishing requires HTTPS except for local development"
        }
        require(url.userInfo == null && url.ref == null) { "Invalid publishing URL" }
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 60_000
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )
}
