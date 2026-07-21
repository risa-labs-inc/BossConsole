package ai.rever.boss.orchestrator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Calls any OpenAI-compatible chat-completions API to generate repair proposals.
 *
 * Configuration via environment variables:
 *   AI_REPAIR_API_URL  — defaults to https://api.openai.com/v1/chat/completions
 *   AI_REPAIR_API_KEY  — required; falls back to OPENAI_API_KEY if not set
 *   AI_REPAIR_MODEL    — defaults to gpt-5.4
 *
 * Uses [java.net.HttpURLConnection] — no additional runtime dependencies required.
 */
class HttpAiRepairClient : AiRepairClient {

    private val logger = LoggerFactory.getLogger(HttpAiRepairClient::class.java)

    private val apiUrl = System.getenv("AI_REPAIR_API_URL")
        ?: "https://api.openai.com/v1/chat/completions"
    private val apiKey = System.getenv("AI_REPAIR_API_KEY")
        ?: System.getenv("OPENAI_API_KEY")
        ?: ""
    private val model = System.getenv("AI_REPAIR_MODEL") ?: "gpt-5.4"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun proposeSourceFix(
        rootCause: String,
        sourceFiles: Map<String, String>,
        stackTrace: String,
        errorMessage: String,
    ): SourceFixProposal? {
        if (apiKey.isBlank()) {
            logger.warn("AI_REPAIR_API_KEY not set — skipping AI source-fix proposal")
            return null
        }

        val sourceContext = sourceFiles.entries.joinToString("\n\n") { (path, content) ->
            "=== $path ===\n$content"
        }.take(12_000) // Stay within typical context window limits

        val prompt = buildString {
            appendLine("You are an AI code repair assistant. Analyze the process failure below and propose a minimal surgical fix.")
            appendLine()
            appendLine("Root Cause: $rootCause")
            appendLine("Error Message: $errorMessage")
            if (stackTrace.isNotBlank()) {
                appendLine("Stack Trace (truncated to 2000 chars):")
                appendLine(stackTrace.take(2000))
            }
            if (sourceContext.isNotBlank()) {
                appendLine()
                appendLine("Relevant Source Files:")
                appendLine(sourceContext)
            }
            appendLine()
            appendLine(
                "Respond with ONLY a valid JSON object — no markdown, no explanation outside JSON:" +
                """{"explanation":"<why this fix works>","patches":[{"filePath":"<path>","description":"<what changed>","originalSnippet":"<exact lines to replace>","patchedSnippet":"<replacement lines>"}]}"""
            )
            appendLine("Return an empty patches array if no code change is needed.")
        }

        return try {
            val response = callApi(prompt)
            parseSourceFixResponse(response, sourceFiles)
        } catch (e: Exception) {
            logger.error("AI source-fix request failed", e)
            null
        }
    }

    override suspend fun proposeConfigFix(
        processId: String,
        rootCause: String,
        suggestedFix: String?,
        errorMessage: String,
    ): ConfigFixProposal? {
        if (apiKey.isBlank()) {
            logger.warn("AI_REPAIR_API_KEY not set — skipping AI config-fix proposal")
            return null
        }

        val prompt = buildString {
            appendLine("You are an AI configuration repair assistant. Suggest configuration changes for the failing process.")
            appendLine()
            appendLine("Process ID: $processId")
            appendLine("Root Cause: $rootCause")
            appendLine("Error Message: $errorMessage")
            suggestedFix?.let { appendLine("Initial Suggestion: $it") }
            appendLine()
            appendLine(
                "Respond with ONLY a valid JSON object — no markdown:" +
                """{"explanation":"<reasoning>","configChanges":{"KEY":"value"}}"""
            )
            appendLine("Return an empty configChanges object if no configuration change is needed.")
        }

        return try {
            val response = callApi(prompt)
            parseConfigFixResponse(response)
        } catch (e: Exception) {
            logger.error("AI config-fix request failed", e)
            null
        }
    }

    // ---- HTTP ----

    private suspend fun callApi(userPrompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("temperature", 0.2)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "You are a precise code repair assistant. Always respond with valid JSON only. Do not include markdown code fences.")
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                }
            }
        }.toString()

        val url = URL(apiUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
        }

        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(StandardCharsets.UTF_8))
            }

            val statusCode = connection.responseCode
            if (statusCode != 200) {
                val error = connection.errorStream?.reader(StandardCharsets.UTF_8)?.readText()
                    ?: "no error body"
                throw IOException("AI API returned HTTP $statusCode: $error")
            }

            connection.inputStream.reader(StandardCharsets.UTF_8).readText()
        } finally {
            connection.disconnect()
        }
    }

    // ---- Response parsers ----

    private fun parseSourceFixResponse(
        rawResponse: String,
        sourceFiles: Map<String, String>,
    ): SourceFixProposal? {
        return try {
            val root = json.parseToJsonElement(extractContent(rawResponse)).jsonObject
            val explanation = root["explanation"]?.jsonPrimitive?.contentOrNull ?: ""
            val patches = root["patches"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val filePath = obj["filePath"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val originalSnippet = obj["originalSnippet"]?.jsonPrimitive?.contentOrNull ?: ""
                val patchedSnippet = obj["patchedSnippet"]?.jsonPrimitive?.contentOrNull ?: ""
                val originalContent = sourceFiles[filePath] ?: ""
                val patchedContent = when {
                    originalSnippet.isNotBlank() && originalContent.contains(originalSnippet) ->
                        originalContent.replace(originalSnippet, patchedSnippet)
                    patchedSnippet.isNotBlank() -> patchedSnippet
                    else -> originalContent
                }
                FilePatch(
                    filePath = filePath,
                    originalContent = originalContent,
                    patchedContent = patchedContent,
                    description = description,
                )
            } ?: emptyList()
            SourceFixProposal(explanation = explanation, patches = patches)
        } catch (e: Exception) {
            logger.error("Failed to parse AI source-fix response: {}", e.message)
            null
        }
    }

    private fun parseConfigFixResponse(rawResponse: String): ConfigFixProposal? {
        return try {
            val root = json.parseToJsonElement(extractContent(rawResponse)).jsonObject
            val explanation = root["explanation"]?.jsonPrimitive?.contentOrNull ?: ""
            val configChanges = root["configChanges"]
                ?.let { it as? JsonObject }
                ?.entries
                ?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
                ?: emptyMap()
            ConfigFixProposal(explanation = explanation, configChanges = configChanges)
        } catch (e: Exception) {
            logger.error("Failed to parse AI config-fix response: {}", e.message)
            null
        }
    }

    /**
     * Extracts the assistant message content from an OpenAI-compatible response JSON.
     * Falls back to returning the raw string if parsing fails (e.g. some providers
     * return the JSON payload directly).
     */
    private fun extractContent(rawResponse: String): String {
        return try {
            json.parseToJsonElement(rawResponse)
                .jsonObject["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: rawResponse
        } catch (_: Exception) {
            rawResponse
        }
    }
}
