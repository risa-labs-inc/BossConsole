package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Computes structured semantic and structural diffs between two versions of an MCP tool definition.
 */
object ToolDnaDiffEngine {
    private val json = Json { ignoreUnknownKeys = true }

    private val DESTRUCTIVE_KEYWORDS = setOf(
        "delete", "remove", "purge", "wipe", "destroy", "drop", "truncate",
        "force", "kill", "override", "overwrite", "erase", "format", "clean",
    )

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
                )
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
                )
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
                    explanation = "Tool requiresAdmin requirement changed from $oldRequiresAdmin to ${newDefinition.requiresAdmin}",
                )
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

        val oldObj = parseObjectOrNull(oldSchemaJson)
        val newObj = parseObjectOrNull(newSchemaJson)

        if (oldObj == null || newObj == null) {
            details.add(
                DiffDetail(
                    category = ChangeCategory.INPUT_SCHEMA_CHANGED,
                    fieldName = "inputSchema",
                    oldValue = canonicalOld,
                    newValue = canonicalNew,
                    explanation = "Schema structure was modified",
                )
            )
            return
        }

        val oldProps = getPropertiesMap(oldObj)
        val newProps = getPropertiesMap(newObj)

        val oldRequired = getRequiredList(oldObj)
        val newRequired = getRequiredList(newObj)

        // Find added properties
        val addedKeys = newProps.keys - oldProps.keys
        for (key in addedKeys) {
            val propDef = newProps.getValue(key)
            val isRequired = key in newRequired
            val isDestructive = isDestructiveParameter(key, propDef)

            val category = when {
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
            details.add(
                DiffDetail(
                    category = category,
                    fieldName = "inputSchema.properties.$key",
                    oldValue = "<absent>",
                    newValue = ToolDnaFingerprinter.canonicalizeJson(propDef.toString()),
                    explanation = "Newly added $reqText parameter '$key'$destText: ${ToolDnaFingerprinter.canonicalizeJson(propDef.toString())}",
                )
            )
        }

        // Find removed properties
        val removedKeys = oldProps.keys - newProps.keys
        for (key in removedKeys) {
            categories.add(ChangeCategory.PARAMETER_REMOVED)
            details.add(
                DiffDetail(
                    category = ChangeCategory.PARAMETER_REMOVED,
                    fieldName = "inputSchema.properties.$key",
                    oldValue = ToolDnaFingerprinter.canonicalizeJson(oldProps.getValue(key).toString()),
                    newValue = "<absent>",
                    explanation = "Removed parameter '$key'",
                )
            )
        }

        // Find modified properties
        val commonKeys = oldProps.keys intersect newProps.keys
        for (key in commonKeys) {
            val oldProp = oldProps.getValue(key)
            val newProp = newProps.getValue(key)
            val oldType = getPropType(oldProp)
            val newType = getPropType(newProp)

            if (oldType != newType) {
                categories.add(ChangeCategory.PARAMETER_TYPE_CHANGED)
                details.add(
                    DiffDetail(
                        category = ChangeCategory.PARAMETER_TYPE_CHANGED,
                        fieldName = "inputSchema.properties.$key.type",
                        oldValue = oldType,
                        newValue = newType,
                        explanation = "Parameter '$key' type changed from '$oldType' to '$newType'",
                    )
                )
            }
        }

        // Find required status changes
        val requiredAdded = newRequired - oldRequired
        for (key in requiredAdded) {
            if (key !in addedKeys) {
                categories.add(ChangeCategory.REQUIRED_PARAMETER_ADDED)
                details.add(
                    DiffDetail(
                        category = ChangeCategory.REQUIRED_PARAMETER_ADDED,
                        fieldName = "inputSchema.required.$key",
                        oldValue = "optional",
                        newValue = "required",
                        explanation = "Existing parameter '$key' became required",
                    )
                )
            }
        }
    }

    private fun parseObjectOrNull(jsonStr: String): JsonObject? {
        return try {
            json.parseToJsonElement(jsonStr).jsonObject
        } catch (_: Throwable) {
            null
        }
    }

    private fun getPropertiesMap(obj: JsonObject): Map<String, JsonElement> {
        val props = obj["properties"] ?: return emptyMap()
        return try {
            props.jsonObject
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun getRequiredList(obj: JsonObject): Set<String> {
        val req = obj["required"] ?: return emptySet()
        return try {
            req.jsonArray.mapNotNull {
                (it as? JsonPrimitive)?.content
            }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun getPropType(element: JsonElement): String {
        val obj = element as? JsonObject ?: return "unknown"
        return (obj["type"] as? JsonPrimitive)?.content ?: "unknown"
    }

    private fun isDestructiveParameter(key: String, propDef: JsonElement): Boolean {
        val lowerKey = key.lowercase()
        if (DESTRUCTIVE_KEYWORDS.any { lowerKey.contains(it) }) return true

        val desc = ((propDef as? JsonObject)?.get("description") as? JsonPrimitive)?.content.orEmpty().lowercase()
        return DESTRUCTIVE_KEYWORDS.any { desc.contains(it) }
    }
}
