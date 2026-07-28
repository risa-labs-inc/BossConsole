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
 * Drop a ```` ``` ````-fenced wrapper if the model added one.
 *
 * The default system prompt forbids fences, but that prompt is operator-editable now, and a
 * replacement that omits the rule shouldn't turn every proposal into a parse failure.
 */
private fun stripCodeFences(text: String): String {
    val trimmed = text.trim()
    return if (!trimmed.startsWith("```")) {
        trimmed
    } else {
        trimmed
            .removePrefix("```")
            .substringAfter('\n', "")
            .substringBeforeLast("```")
            .trim()
    }
}

/**
 * Why the model declined, or null if it didn't.
 *
 * A refusal is a successful HTTP 200 carrying no answer, so it has to be recognised before the
 * response is read as a proposal.
 */
private fun refusalReason(
    json: Json,
    rawResponse: String,
): String? {
    val root = json.parseToJsonElement(rawResponse).jsonObject
    if (root["stop_reason"]?.jsonPrimitive?.contentOrNull != "refusal") return null
    val details = root["stop_details"]?.jsonObject
    val category = details?.get("category")?.jsonPrimitive?.contentOrNull ?: "unspecified"
    val explanation = details?.get("explanation")?.jsonPrimitive?.contentOrNull ?: "no explanation given"
    return "category=$category, $explanation"
}

/**
 * The first `text` block of an Anthropic response.
 *
 * Indexing `content[0]` is wrong here: thinking is on by default on current models, so the answer is
 * preceded by one or more thinking blocks. Refusals are handled before this is reached.
 */
private fun extractAnthropicText(
    json: Json,
    rawResponse: String,
): String? =
    json
        .parseToJsonElement(rawResponse)
        .jsonObject["content"]
        ?.jsonArray
        ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
        ?.jsonObject
        ?.get("text")
        ?.jsonPrimitive
        ?.contentOrNull

/** The assistant message out of an OpenAI-compatible chat-completions response. */
private fun extractOpenAiText(
    json: Json,
    rawResponse: String,
): String? =
    json
        .parseToJsonElement(rawResponse)
        .jsonObject["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull

/**
 * One proposed patch, resolved against the file it applies to.
 *
 * The model is asked for a snippet swap; falling back to the whole patched body, and then to the
 * original, keeps a partial answer from being reported as a rewrite of the file to nothing.
 */
private fun filePatch(
    obj: JsonObject,
    sourceFiles: Map<String, String>,
): FilePatch? {
    val filePath = obj["filePath"]?.jsonPrimitive?.contentOrNull ?: return null
    val originalSnippet = obj["originalSnippet"]?.jsonPrimitive?.contentOrNull ?: ""
    val patchedSnippet = obj["patchedSnippet"]?.jsonPrimitive?.contentOrNull ?: ""
    val originalContent = sourceFiles[filePath] ?: ""
    val patchedContent =
        when {
            originalSnippet.isNotBlank() && originalContent.contains(originalSnippet) -> {
                originalContent.replace(originalSnippet, patchedSnippet)
            }

            patchedSnippet.isNotBlank() -> {
                patchedSnippet
            }

            else -> {
                originalContent
            }
        }
    return FilePatch(
        filePath = filePath,
        originalContent = originalContent,
        patchedContent = patchedContent,
        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}

/**
 * Calls the operator's chosen model to generate repair proposals.
 *
 * Two wire shapes are supported — Anthropic's Messages API and the OpenAI chat-completions shape
 * that OpenAI, Together and most gateways implement — because provider, model and system prompt are
 * an operator choice (Settings → Advanced → Self-Healing), forwarded here as the environment
 * variables [aiRepairConfigFromEnvironment] reads.
 *
 * Uses [java.net.HttpURLConnection] — no additional runtime dependencies required, which matters for
 * a service that ships as a fat JAR spawned by the kernel.
 */
class HttpAiRepairClient(
    private val config: AiRepairConfig = aiRepairConfigFromEnvironment(),
) : AiRepairClient {
    private val logger = LoggerFactory.getLogger(HttpAiRepairClient::class.java)

    private val apiKey = config.apiKey

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

        val sourceContext =
            sourceFiles.entries
                .joinToString("\n\n") { (path, content) ->
                    "=== $path ===\n$content"
                }.take(12_000) // Stay within typical context window limits

        val prompt =
            buildString {
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
                        """{"explanation":"<why this fix works>","patches":[{"filePath":"<path>","description":"<what changed>","originalSnippet":"<exact lines to replace>","patchedSnippet":"<replacement lines>"}]}""",
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

        val prompt =
            buildString {
                appendLine("You are an AI configuration repair assistant. Suggest configuration changes for the failing process.")
                appendLine()
                appendLine("Process ID: $processId")
                appendLine("Root Cause: $rootCause")
                appendLine("Error Message: $errorMessage")
                suggestedFix?.let { appendLine("Initial Suggestion: $it") }
                appendLine()
                appendLine(
                    "Respond with ONLY a valid JSON object — no markdown:" +
                        """{"explanation":"<reasoning>","configChanges":{"KEY":"value"}}""",
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

    private suspend fun callApi(userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = buildRequestBody(userPrompt)

            val url = URL(config.endpoint)
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    applyAuth(this)
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
                    val error =
                        connection.errorStream?.reader(StandardCharsets.UTF_8)?.readText()
                            ?: "no error body"
                    throw IOException("AI API returned HTTP $statusCode: $error")
                }

                connection.inputStream.reader(StandardCharsets.UTF_8).readText()
            } finally {
                connection.disconnect()
            }
        }

    /**
     * The request body for the configured wire.
     *
     * Anthropic takes the system prompt as a top-level field rather than a message, and rejects
     * `temperature` outright on current models — so the two shapes differ by more than field names
     * and are built separately instead of patched from a common object.
     */
    private fun buildRequestBody(userPrompt: String): String =
        when (config.wire) {
            AiRepairWire.ANTHROPIC -> {
                buildJsonObject {
                    put("model", config.model)
                    put("max_tokens", config.wire.maxTokens)
                    put("system", config.systemPrompt)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        }
                    }
                }.toString()
            }

            AiRepairWire.OPENAI -> {
                buildJsonObject {
                    put("model", config.model)
                    put("max_tokens", config.wire.maxTokens)
                    put("temperature", 0.2)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", config.systemPrompt)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", userPrompt)
                        }
                    }
                }.toString()
            }
        }

    /** Anthropic authenticates with `x-api-key` plus a version header; everyone else with a bearer token. */
    private fun applyAuth(connection: HttpURLConnection) {
        when (config.wire) {
            AiRepairWire.ANTHROPIC -> {
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            }

            AiRepairWire.OPENAI -> {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }
    }

    // ---- Response parsers ----

    /**
     * The proposal object out of a response, or null when there isn't one.
     *
     * "Parsed some JSON" is not "got a proposal": a refusal, an error body or a bare envelope all
     * parse fine and carry none of [keys], and reporting one as a repair hands the operator an
     * empty diff described as a successful fix.
     */
    private fun proposalRoot(
        rawResponse: String,
        vararg keys: String,
    ): JsonObject? {
        val root = extractContent(rawResponse)?.let(::parseObject)
        return when {
            root == null -> {
                null
            }

            keys.any { root[it] != null } -> {
                root
            }

            else -> {
                logger.warn("AI response carried no proposal (looked for {})", keys.joinToString())
                null
            }
        }
    }

    private fun parseObject(content: String): JsonObject? =
        try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            logger.error("Failed to parse AI response: {}", e.message)
            null
        }

    private fun parseSourceFixResponse(
        rawResponse: String,
        sourceFiles: Map<String, String>,
    ): SourceFixProposal? {
        val root = proposalRoot(rawResponse, "explanation", "patches") ?: return null
        return SourceFixProposal(
            explanation = root["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
            patches =
                root["patches"]
                    ?.jsonArray
                    ?.mapNotNull { filePatch(it.jsonObject, sourceFiles) }
                    ?: emptyList(),
        )
    }

    private fun parseConfigFixResponse(rawResponse: String): ConfigFixProposal? {
        val root = proposalRoot(rawResponse, "explanation", "configChanges") ?: return null
        return ConfigFixProposal(
            explanation = root["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
            configChanges =
                (root["configChanges"] as? JsonObject)
                    ?.entries
                    ?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
                    ?: emptyMap(),
        )
    }

    /**
     * The assistant's text out of a response in the configured wire's shape, or null when the model
     * did not give one.
     *
     * Null is load-bearing. A refusal must NOT fall through to the raw envelope the way an
     * unrecognised shape does: that envelope is itself valid JSON, so the parsers would read it as
     * a proposal with no explanation and no patches — and an empty proposal reaches the operator as
     * a *successful* repair carrying an empty diff, which is worse than no answer.
     */
    private fun extractContent(rawResponse: String): String? =
        try {
            val refusal = if (config.wire == AiRepairWire.ANTHROPIC) refusalReason(json, rawResponse) else null
            if (refusal != null) {
                logger.warn("Model declined the repair request: {}", refusal)
                null
            } else {
                val text =
                    when (config.wire) {
                        AiRepairWire.ANTHROPIC -> extractAnthropicText(json, rawResponse)
                        AiRepairWire.OPENAI -> extractOpenAiText(json, rawResponse)
                    }
                // Falling back to the raw string covers gateways that return the payload directly.
                stripCodeFences(text ?: rawResponse)
            }
        } catch (_: Exception) {
            rawResponse
        }

    private companion object {
        /** Pinned per Anthropic's API contract; unrelated to the model in [AiRepairConfig]. */
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
