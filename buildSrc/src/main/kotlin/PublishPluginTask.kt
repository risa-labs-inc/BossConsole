import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gradle task to publish a plugin to the BOSS Plugin Store.
 *
 * Usage:
 * ```
 * ./gradlew :plugin-platform:plugin-my-plugin:publishPlugin \
 *   -PpluginStoreToken=eyJ... \
 *   -PauthorName="My Company"
 * ```
 *
 * The task reads metadata from the JAR manifest and publishes to the store.
 */
abstract class PublishPluginTask : DefaultTask() {

    init {
        group = "publishing"
        description = "Publishes the plugin to the BOSS Plugin Store"
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

    /**
     * Authentication token for the plugin store
     * Can also be provided via BOSS_PLUGIN_STORE_TOKEN environment variable
     */
    @get:Input
    abstract val authToken: Property<String>

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
        val jar = jarFile.get().asFile
        if (!jar.exists()) {
            throw GradleException("JAR file not found: ${jar.absolutePath}")
        }

        logger.lifecycle("Publishing plugin to BOSS Plugin Store")
        logger.lifecycle("============================================")

        // Read JAR metadata
        val jarBytes = jar.readBytes()
        val jarSize = jarBytes.size.toLong()
        val sha256 = calculateSha256(jarBytes)

        // Extract metadata from manifest if not provided
        val manifest = readManifest(jar)
        
        val actualPluginId = pluginId.orNull 
            ?: manifest["Plugin-Id"] 
            ?: throw GradleException("Plugin ID not found. Provide via pluginId property or Plugin-Id manifest entry.")
        
        val actualDisplayName = displayName.orNull 
            ?: manifest["Plugin-Name"] 
            ?: actualPluginId
        
        val actualVersion = pluginVersion.orNull 
            ?: manifest["Plugin-Version"] 
            ?: throw GradleException("Version not found. Provide via pluginVersion property or Plugin-Version manifest entry.")

        logger.lifecycle("  Plugin ID:    $actualPluginId")
        logger.lifecycle("  Display Name: $actualDisplayName")
        logger.lifecycle("  Version:      $actualVersion")
        logger.lifecycle("  JAR Size:     $jarSize bytes")
        logger.lifecycle("  SHA256:       ${sha256.take(16)}...")

        val baseUrl = storeUrl.orNull 
            ?: System.getenv("BOSS_PLUGIN_STORE_URL") 
            ?: "https://api.risaboss.com/functions/v1/plugin-store"
        
        val token = authToken.orNull
            ?: System.getenv("BOSS_PLUGIN_STORE_TOKEN")
            ?: throw GradleException("Authentication token required. Set via authToken property or BOSS_PLUGIN_STORE_TOKEN environment variable.")

        val apiKey = anonKey.orNull ?: System.getenv("SUPABASE_ANON_KEY") ?: ""

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
                tags = tags.orNull?.split(",")?.map { it.trim() } ?: emptyList(),
                token = token,
                apiKey = apiKey
            )
            logger.lifecycle("  Plugin entry created")
        } else {
            logger.lifecycle("  Plugin exists, publishing new version")
        }

        // Step 3: Create version and get upload URL
        logger.lifecycle("Creating version entry...")
        val (versionId, uploadUrl) = createVersion(
            baseUrl = baseUrl,
            pluginId = actualPluginId,
            version = actualVersion,
            changelog = changelog.orNull ?: "",
            token = token,
            apiKey = apiKey
        )
        logger.lifecycle("  Version created: $versionId")

        // Step 4: Upload JAR
        logger.lifecycle("Uploading JAR file...")
        uploadJar(uploadUrl, jarBytes)
        logger.lifecycle("  JAR uploaded successfully")

        // Step 5: Finalize version
        logger.lifecycle("Finalizing version...")
        finalizeVersion(
            baseUrl = baseUrl,
            versionId = versionId,
            sha256 = sha256,
            jarSize = jarSize,
            token = token,
            apiKey = apiKey
        )
        logger.lifecycle("  Version finalized")

        logger.lifecycle("")
        logger.lifecycle("============================================")
        logger.lifecycle("Plugin published successfully!")
        logger.lifecycle("  Plugin ID: $actualPluginId")
        logger.lifecycle("  Version:   $actualVersion")
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
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

    private fun checkPluginExists(baseUrl: String, pluginId: String, token: String, apiKey: String): Boolean {
        return try {
            val response = httpGet("$baseUrl/$pluginId", token, apiKey)
            response.statusCode == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun createPlugin(
        baseUrl: String,
        pluginId: String,
        displayName: String,
        description: String,
        authorName: String?,
        tags: List<String>,
        token: String,
        apiKey: String
    ) {
        val tagsJson = tags.joinToString(",") { "\"$it\"" }
        val authorJson = if (authorName != null) "\"$authorName\"" else "null"
        
        val body = """
        {
            "pluginId": "$pluginId",
            "displayName": "$displayName",
            "description": "${escapeJson(description)}",
            "authorName": $authorJson,
            "tags": [$tagsJson]
        }
        """.trimIndent()

        val response = httpPost("$baseUrl/publish", body, token, apiKey)
        if (response.statusCode !in 200..201) {
            val error = extractJsonValue(response.body, "error") ?: response.body
            throw GradleException("Failed to create plugin: $error")
        }
    }

    private fun createVersion(
        baseUrl: String,
        pluginId: String,
        version: String,
        changelog: String,
        token: String,
        apiKey: String
    ): Pair<String, String> {
        val body = """
        {
            "version": "$version",
            "changelog": "${escapeJson(changelog)}",
            "minBossVersion": "1.0.0"
        }
        """.trimIndent()

        val response = httpPost("$baseUrl/$pluginId/version", body, token, apiKey)
        if (response.statusCode !in 200..201) {
            val error = extractJsonValue(response.body, "error") ?: response.body
            throw GradleException("Failed to create version: $error")
        }

        val versionId = extractJsonValue(response.body, "versionId")
            ?: throw GradleException("No versionId in response")
        val uploadUrl = extractJsonValue(response.body, "uploadUrl")
            ?: throw GradleException("No uploadUrl in response")

        return versionId to uploadUrl
    }

    private fun uploadJar(uploadUrl: String, jarBytes: ByteArray) {
        val url = URL(uploadUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("Content-Length", jarBytes.size.toString())

        connection.outputStream.use { it.write(jarBytes) }

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
        apiKey: String
    ) {
        val body = """
        {
            "versionId": "$versionId",
            "sha256": "$sha256",
            "jarSize": $jarSize
        }
        """.trimIndent()

        val response = httpPost("$baseUrl/version/finalize", body, token, apiKey)
        if (response.statusCode != 200) {
            val error = extractJsonValue(response.body, "error") ?: response.body
            throw GradleException("Failed to finalize version: $error")
        }
    }

    private fun httpGet(url: String, token: String, apiKey: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (apiKey.isNotEmpty()) {
            connection.setRequestProperty("apikey", apiKey)
        }

        return readResponse(connection)
    }

    private fun httpPost(url: String, body: String, token: String, apiKey: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (apiKey.isNotEmpty()) {
            connection.setRequestProperty("apikey", apiKey)
        }

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(body)
        }

        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val statusCode = connection.responseCode
        val inputStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val body = inputStream?.use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }
        } ?: ""

        return HttpResponse(statusCode, body)
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]+)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private data class HttpResponse(val statusCode: Int, val body: String)
}
