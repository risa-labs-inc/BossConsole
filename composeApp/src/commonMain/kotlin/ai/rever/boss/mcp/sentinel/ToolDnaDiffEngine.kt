package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Computes structured semantic and structural diffs between two versions of an MCP tool definition.
 */
object ToolDnaDiffEngine {
    /**
     * Compute a semantic diff between an old definition (or baseline string) and a new definition.
     */
    fun computeDiff(
        oldDescription: String,
        oldSchemaJson: String,
        oldReadOnly: Boolean = false,
        oldRequiresAdmin: Boolean = false,
        newDefinition: McpToolDefinition,
    ): ToolDnaDiffResult {
        val details = mutableListOf<DiffDetail>()
        val categories = mutableSetOf<ChangeCategory>()

        // 1. Description change
        val normOldDesc = ToolDnaFingerprinter.normalizeText(oldDescription)
        val normNewDesc = ToolDnaFingerprinter.normalizeText(newDefinition.description)
        if (normOldDesc != normNewDesc) {
            categories.add(ChangeCategory.DESCRIPTION_CHANGED)
            details.add(
                DiffDetail(
                    category = ChangeCategory.DESCRIPTION_CHANGED,
                    fieldName = "description",
                    oldValue = normOldDesc,
                    newValue = normNewDesc,
                    explanation = "Tool description changed from '$normOldDesc' to '$normNewDesc'",
                ),
            )
        }

        // 2. Metadata changes (readOnly, requiresAdmin)
        if (oldReadOnly != newDefinition.readOnly) {
            categories.add(ChangeCategory.CAPABILITY_EXPANSION)
            details.add(
                DiffDetail(
                    category = ChangeCategory.CAPABILITY_EXPANSION,
                    fieldName = "readOnly",
                    oldValue = oldReadOnly.toString(),
                    newValue = newDefinition.readOnly.toString(),
                    explanation = "Tool readOnly status changed from $oldReadOnly to ${newDefinition.readOnly}",
                ),
            )
        }

        if (oldRequiresAdmin != newDefinition.requiresAdmin) {
            categories.add(ChangeCategory.CAPABILITY_EXPANSION)
            details.add(
                DiffDetail(
                    category = ChangeCategory.CAPABILITY_EXPANSION,
                    fieldName = "requiresAdmin",
                    oldValue = oldRequiresAdmin.toString(),
                    newValue = newDefinition.requiresAdmin.toString(),
                    explanation =
                        "Tool requiresAdmin requirement changed from " +
                            "$oldRequiresAdmin to ${newDefinition.requiresAdmin}",
                ),
            )
        }

        // 3. Input Schema diff
        diffSchemas(
            oldSchemaJson = oldSchemaJson,
            newSchemaJson = newDefinition.inputSchema,
            categories = categories,
            details = details,
        )

        return ToolDnaDiffResult(
            hasChanges = details.isNotEmpty(),
            categories = categories,
            diffDetails = details,
        )
    }

    private fun diffSchemas(
        oldSchemaJson: String,
        newSchemaJson: String,
        categories: MutableSet<ChangeCategory>,
        details: MutableList<DiffDetail>,
    ) {
        val canonicalOld = ToolDnaFingerprinter.canonicalizeJson(oldSchemaJson)
        val canonicalNew = ToolDnaFingerprinter.canonicalizeJson(newSchemaJson)

        if (canonicalOld == canonicalNew) return

        categories.add(ChangeCategory.INPUT_SCHEMA_CHANGED)

        val oldObj = DiffHelpers.parseObjectOrNull(oldSchemaJson)
        val newObj = DiffHelpers.parseObjectOrNull(newSchemaJson)

        if (oldObj == null || newObj == null) {
            details.add(
                DiffDetail(
                    category = ChangeCategory.INPUT_SCHEMA_CHANGED,
                    fieldName = "inputSchema",
                    oldValue = canonicalOld,
                    newValue = canonicalNew,
                    explanation = "Schema structure was modified",
                ),
            )
            return
        }

        val oldProps = DiffHelpers.getPropertiesMap(oldObj)
        val newProps = DiffHelpers.getPropertiesMap(newObj)
        val oldRequired = DiffHelpers.getRequiredList(oldObj)
        val newRequired = DiffHelpers.getRequiredList(newObj)

        diffAddedProperties(oldProps, newProps, newRequired, categories, details)
        diffRemovedProperties(oldProps, newProps, categories, details)
        diffModifiedProperties(oldProps, newProps, categories, details)
        diffRequiredStatus(oldProps.keys, oldRequired, newRequired, categories, details)
    }

    private fun diffAddedProperties(
        oldProps: Map<String, JsonElement>,
        newProps: Map<String, JsonElement>,
        newRequired: Set<String>,
        categories: MutableSet<ChangeCategory>,
        details: MutableList<DiffDetail>,
    ) {
        val addedKeys = newProps.keys - oldProps.keys
        for (key in addedKeys) {
            val propDef = newProps.getValue(key)
            val isRequired = key in newRequired
            val isDestructive = DiffHelpers.isDestructiveParameter(key, propDef)

            val category =
                when {
                    isDestructive -> ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED
                    isRequired -> ChangeCategory.REQUIRED_PARAMETER_ADDED
                    else -> ChangeCategory.OPTIONAL_PARAMETER_ADDED
                }

            categories.add(category)
            if (isDestructive || isRequired) {
                categories.add(ChangeCategory.CAPABILITY_EXPANSION)
            }

            val reqText = if (isRequired) "required" else "optional"
            val destText = if (isDestructive) " (DESTRUCTIVE)" else ""
            val canonProp = ToolDnaFingerprinter.canonicalizeJson(propDef.toString())
            details.add(
                DiffDetail(
                    category = category,
                    fieldName = "inputSchema.properties.$key",
                    oldValue = "<absent>",
                    newValue = canonProp,
                    explanation = "Newly added $reqText parameter '$key'$destText: $canonProp",
                ),
            )
        }
    }

    private fun diffRemovedProperties(
        oldProps: Map<String, JsonElement>,
        newProps: Map<String, JsonElement>,
        categories: MutableSet<ChangeCategory>,
        details: MutableList<DiffDetail>,
    ) {
        val removedKeys = oldProps.keys - newProps.keys
        for (key in removedKeys) {
            categories.add(ChangeCategory.PARAMETER_REMOVED)
            val canonOld = ToolDnaFingerprinter.canonicalizeJson(oldProps.getValue(key).toString())
            details.add(
                DiffDetail(
                    category = ChangeCategory.PARAMETER_REMOVED,
                    fieldName = "inputSchema.properties.$key",
                    oldValue = canonOld,
                    newValue = "<absent>",
                    explanation = "Removed parameter '$key'",
                ),
            )
        }
    }

    private fun diffModifiedProperties(
        oldProps: Map<String, JsonElement>,
        newProps: Map<String, JsonElement>,
        categories: MutableSet<ChangeCategory>,
        details: MutableList<DiffDetail>,
    ) {
        val commonKeys = oldProps.keys intersect newProps.keys
        for (key in commonKeys) {
            val oldProp = oldProps.getValue(key)
            val newProp = newProps.getValue(key)
            val oldType = DiffHelpers.getPropType(oldProp)
            val newType = DiffHelpers.getPropType(newProp)

            if (oldType != newType) {
                categories.add(ChangeCategory.PARAMETER_TYPE_CHANGED)
                details.add(
                    DiffDetail(
                        category = ChangeCategory.PARAMETER_TYPE_CHANGED,
                        fieldName = "inputSchema.properties.$key.type",
                        oldValue = oldType,
                        newValue = newType,
                        explanation = "Parameter '$key' type changed from '$oldType' to '$newType'",
                    ),
                )
            }
        }
    }

    private fun diffRequiredStatus(
        oldKeys: Set<String>,
        oldRequired: Set<String>,
        newRequired: Set<String>,
        categories: MutableSet<ChangeCategory>,
        details: MutableList<DiffDetail>,
    ) {
        val requiredAdded = newRequired - oldRequired
        for (key in requiredAdded) {
            if (key in oldKeys) {
                categories.add(ChangeCategory.REQUIRED_PARAMETER_ADDED)
                details.add(
                    DiffDetail(
                        category = ChangeCategory.REQUIRED_PARAMETER_ADDED,
                        fieldName = "inputSchema.required.$key",
                        oldValue = "optional",
                        newValue = "required",
                        explanation = "Existing parameter '$key' became required",
                    ),
                )
            }
        }
    }
}

private object DiffHelpers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseObjectOrNull(jsonStr: String): JsonObject? =
        try {
            json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Throwable) {
            null
        }

    fun getPropertiesMap(obj: JsonObject): Map<String, JsonElement> {
        val props = obj["properties"] ?: return emptyMap()
        return try {
            props.jsonObject
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun getRequiredList(obj: JsonObject): Set<String> {
        val req = obj["required"] ?: return emptySet()
        return try {
            req.jsonArray
                .mapNotNull {
                    (it as? JsonPrimitive)?.content
                }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun getPropType(element: JsonElement): String {
        val obj = element as? JsonObject ?: return "unknown"
        return (obj["type"] as? JsonPrimitive)?.content ?: "unknown"
    }

    private val TOKEN_DELIMITERS = Regex("""[_\-\s,.:;/\\]+""")
    private val CAMEL_CASE_SPLIT = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")

    private fun extractTokens(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = mutableSetOf<String>()
        val words = text.split(TOKEN_DELIMITERS)
        for (w in words) {
            if (w.isBlank()) continue
            tokens.add(w.lowercase())
            val camelParts = w.split(CAMEL_CASE_SPLIT)
            for (cp in camelParts) {
                if (cp.isNotBlank()) tokens.add(cp.lowercase())
            }
        }
        return tokens
    }

    fun isDestructiveParameter(
        key: String,
        propDef: JsonElement,
    ): Boolean {
        val keyTokens = extractTokens(key)
        val desc = ((propDef as? JsonObject)?.get("description") as? JsonPrimitive)?.content.orEmpty()
        val descTokens = extractTokens(desc)
        val allTokens = keyTokens + descTokens

        if (allTokens.isEmpty()) return false

        val directDestructive =
            setOf(
                "delete",
                "remove",
                "purge",
                "wipe",
                "destroy",
                "drop",
                "truncate",
                "kill",
                "override",
                "overwrite",
                "erase",
            )
        val benignContext =
            setOf(
                "output",
                "refresh",
                "build",
                "code",
                "text",
                "date",
                "file",
                "json",
                "yaml",
                "response",
                "log",
                "input",
                "display",
                "sync",
                "fetch",
                "reload",
                "cache",
                "lint",
            )

        val hasDirect = directDestructive.any { it in allTokens }
        val isBenignContext = allTokens.any { it in benignContext }
        val hasContextual = ("force" in allTokens || "format" in allTokens || "clean" in allTokens) && !isBenignContext

        return hasDirect || hasContextual
    }
}
