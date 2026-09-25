package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.ApprovedArtifact
import ai.rever.boss.mcp.McpPreparationResult
import ai.rever.boss.mcp.McpToolPreparer
import ai.rever.boss.mcp.PackPluginDisplay
import ai.rever.boss.mcp.PackRuleDisplay
import ai.rever.boss.mcp.PreparedPackDisplayModel
import ai.rever.boss.mcp.executionObject
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The host's plugin-pack tools, reachable by in-terminal agents over MCP and by scripts through
 * `boss pack` / `boss mcp invoke`.
 *
 * Governance is the registry's, not this class's. `pack_apply` declares `readOnly = false` and its
 * name ends in `_apply`, so under the factory policy every call is held for the operator in the MCP
 * approval dialog, whose argument view is the pack itself; kill-switch, ledger and policy rules
 * apply to it like any other mutating tool. `pack_plan` and `pack_status` only read.
 */
class PluginPackMcpToolProvider(
    private val effects: PluginPackEffects,
    private val jobs: PluginPackJobs,
) : McpToolProvider,
    McpToolPreparer {
    override val providerId: String = PluginPackParser.PACK_PROVIDER_ID

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "pack_plan",
                description =
                    "Show what applying a plugin pack would change, without changing anything: which plugins " +
                        "would be installed, re-versioned or enabled, which are unavailable, and which MCP policy " +
                        "rules would be added or are kept because the operator already set one.",
                inputSchema = PACK_SCHEMA,
                readOnly = true,
                handler = { args -> plan(args) },
            ),
            McpToolDefinition(
                name = "pack_apply",
                description =
                    "Apply a plugin pack: install or enable its plugins from the plugin store and add its MCP " +
                        "policy rules where none exist. Never overrides an existing rule. Returns a job id at once; " +
                        "poll pack_status for progress and the result.",
                inputSchema = PACK_SCHEMA,
                readOnly = false,
                handler = { args -> apply(args) },
            ),
            McpToolDefinition(
                name = "pack_status",
                description = "Progress and result of a pack_apply job. Without a job id, reports the most recent one.",
                inputSchema = """{"type":"object","properties":{"job":{"type":"string"}}}""",
                readOnly = true,
                handler = { args -> status(args) },
            ),
        )

    override suspend fun prepareInvocation(
        toolName: String,
        args: McpToolArgs,
    ): McpPreparationResult? {
        if (toolName != "pack_apply") return null

        val running = jobs.status(null)?.takeIf { it.state == PackJobState.RUNNING }
        if (running != null) {
            return McpPreparationResult.Rejected(
                McpToolResult(
                    "Pack '${running.packId}' is still being applied (job ${running.id}). " +
                        "Wait for it with pack_status, then apply again.",
                    isError = true,
                ),
            )
        }

        val pack =
            PluginPackParser.parse(args.raw).getOrElse {
                return McpPreparationResult.Rejected(invalid(it))
            }

        val snapshot = effects.snapshot(pack)
        val plan = PluginPackPlanner.plan(pack, snapshot)

        // Reject an apply whose dependency closure is unresolved, cyclic, truncated, or too large to display fully.
        for (step in plan.plugins) {
            val closure = step.closure ?: continue
            if (closure.cyclic) {
                return McpPreparationResult.Rejected(
                    McpToolResult(
                        "Cannot apply pack '${pack.id}': dependency cycle detected for plugin '${step.plugin.pluginId}'.",
                        isError = true,
                    ),
                )
            }
            if (closure.truncated) {
                return McpPreparationResult.Rejected(
                    McpToolResult(
                        "Cannot apply pack '${pack.id}': dependency closure for plugin '${step.plugin.pluginId}' was truncated.",
                        isError = true,
                    ),
                )
            }
            if (closure.unresolved.isNotEmpty()) {
                return McpPreparationResult.Rejected(
                    McpToolResult(
                        "Cannot apply pack '${pack.id}': unresolved dependencies for plugin '${step.plugin.pluginId}': ${closure.unresolved.sorted().joinToString(
                            ", ",
                        )}.",
                        isError = true,
                    ),
                )
            }
            if (closure.order.size > MAX_CLOSURE_DISPLAY_SIZE) {
                return McpPreparationResult.Rejected(
                    McpToolResult(
                        "Cannot apply pack '${pack.id}': dependency closure for plugin '${step.plugin.pluginId}' is too large to display (${closure.order.size} plugins).",
                        isError = true,
                    ),
                )
            }
        }

        val pluginsDisplay =
            plan.plugins.map { step ->
                val extraArtifacts =
                    step.closure?.let { closure ->
                        closure.artifacts
                            .filterNot { it.pluginId == step.plugin.pluginId }
                            .ifEmpty {
                                closure.alsoInstalls.map { id ->
                                    val listing = snapshot.store[id] as? StoreListing.Published
                                    val ver = listing?.latest.orEmpty()
                                    val sha = listing?.latestSha256.orEmpty()
                                    ApprovedArtifact(id, ver, sha)
                                }
                            }
                    } ?: emptyList()
                PackPluginDisplay(
                    pluginId = step.plugin.pluginId,
                    action = step.kind.name.lowercase(),
                    targetVersion = step.targetVersion,
                    targetSha256 = step.targetSha256,
                    installedVersion = step.installedVersion,
                    optional = step.plugin.optional,
                    extraDependencies = extraArtifacts,
                )
            }

        val rulesDisplay =
            plan.rules.map { step ->
                PackRuleDisplay(
                    scope =
                        step.rule.scope.name
                            .lowercase(),
                    subject = step.rule.subject,
                    action = step.rule.action.name,
                    outcome = step.kind.name.lowercase(),
                    existing = step.existing?.name,
                )
            }

        val displayModel =
            PreparedPackDisplayModel(
                packId = pack.id,
                plugins = pluginsDisplay,
                rules = rulesDisplay,
            )

        val artifacts = mutableListOf<ApprovedArtifact>()
        for (step in plan.plugins) {
            val closure = step.closure
            if (closure != null && closure.artifacts.isNotEmpty()) {
                artifacts.addAll(closure.artifacts)
            } else if (step.targetVersion != null) {
                artifacts.add(
                    ApprovedArtifact(
                        pluginId = step.plugin.pluginId,
                        version = step.targetVersion,
                        sha256 = step.targetSha256 ?: "",
                    ),
                )
            }
        }

        val prepared =
            PreparedPackApply(
                pack = pack,
                plan = plan,
                snapshot = snapshot,
                stamps = snapshot.stamps,
                artifacts = artifacts,
                displayModel = displayModel,
            )

        return McpPreparationResult.Prepared(
            displayModel = displayModel,
            executionObject = prepared,
            requiresFreshApproval = true,
        )
    }

    private suspend fun plan(args: McpToolArgs): McpToolResult {
        val pack = PluginPackParser.parse(args.raw).getOrElse { return invalid(it) }
        val plan = PluginPackPlanner.plan(pack, effects.snapshot(pack))
        return McpToolResult(PluginPackJson.plan(plan).toString())
    }

    private fun apply(args: McpToolArgs): McpToolResult {
        val prepared = args.executionObject<PreparedPackApply>()
        val start =
            if (prepared != null) {
                jobs.start(prepared)
            } else {
                val pack = PluginPackParser.parse(args.raw).getOrElse { return invalid(it) }
                jobs.start(pack)
            }
        return when (start) {
            is PluginPackJobs.Start.Started -> {
                McpToolResult(PluginPackJson.job(start.job).toString())
            }

            is PluginPackJobs.Start.Busy -> {
                McpToolResult(
                    "Pack '${start.running.packId}' is still being applied (job ${start.running.id}). " +
                        "Wait for it with pack_status, then apply again.",
                    isError = true,
                )
            }
        }
    }

    private fun status(args: McpToolArgs): McpToolResult {
        val id = args.string("job")?.takeIf { it.isNotBlank() }
        val job = jobs.status(id)
        return when {
            job != null -> McpToolResult(PluginPackJson.job(job).toString())
            id == null -> McpToolResult("No pack has been applied yet.", isError = true)
            else -> McpToolResult("No pack job '$id'.", isError = true)
        }
    }

    private fun invalid(error: Throwable): McpToolResult {
        val problems = (error as? InvalidPackException)?.problems ?: listOf(error.message ?: "Invalid pack.")
        return McpToolResult(
            buildJsonObject {
                put("error", "invalid_pack")
                put("problems", buildJsonArray { problems.forEach { add(JsonPrimitive(it)) } })
            }.toString(),
            isError = true,
        )
    }

    private companion object {
        private const val MAX_CLOSURE_DISPLAY_SIZE = 25
        private const val STRING_LIST = """{"type":"array","items":{"type":"string"}}"""
        val PACK_SCHEMA =
            """{"type":"object","required":["pack"],"properties":{""" +
                """"pack":{"type":"string","description":"Pack id: lowercase letters, digits, '.', '_' or '-'."},""" +
                """"plugins":{"type":"array","items":{"type":"string"},""" +
                """"description":"pluginId, pluginId@version, with a trailing ? for an optional plugin."},""" +
                listOf("allow_tools", "ask_tools", "deny_tools", "allow_providers", "ask_providers", "deny_providers")
                    .joinToString(",") { "\"$it\":$STRING_LIST" } +
                "}}"
    }
}

/** The JSON the pack tools return. Field names are the contract; message text may be reworded. */
internal object PluginPackJson {
    fun plan(plan: PackPlan): JsonObject =
        buildJsonObject {
            put("pack", plan.pack.id)
            put("satisfied", plan.satisfied)
            put("requiredBlocked", plan.requiredBlocked.size)
            put(
                "plugins",
                buildJsonArray {
                    plan.plugins.forEach { step ->
                        add(
                            buildJsonObject {
                                put("pluginId", step.plugin.pluginId)
                                step.plugin.version?.let { put("requested", it) }
                                put("optional", step.plugin.optional)
                                put("action", step.kind.name.lowercase())
                                step.installedVersion?.let { put("installed", it) }
                                step.targetVersion?.let { put("target", it) }
                                put("detail", step.detail)
                                step.closure?.let { putClosure(it) }
                            },
                        )
                    }
                },
            )
            put(
                "rules",
                buildJsonArray {
                    plan.rules.forEach { step ->
                        add(
                            buildJsonObject {
                                put(
                                    "scope",
                                    step.rule.scope.name
                                        .lowercase(),
                                )
                                put("subject", step.rule.subject)
                                put("action", step.rule.action.name)
                                put("outcome", step.kind.name.lowercase())
                                step.existing?.let { put("existing", it.name) }
                            },
                        )
                    }
                },
            )
        }

    fun job(job: PackJob): JsonObject =
        buildJsonObject {
            put("job", job.id)
            put("pack", job.packId)
            put("state", job.state.name.lowercase())
            put("done", job.done)
            put("total", job.total)
            if (job.current.isNotEmpty()) put("current", job.current)
            job.error?.let { put("error", it) }
            job.result?.let { result ->
                put("status", result.status.name.lowercase())
                put(
                    "plugins",
                    buildJsonArray {
                        result.plugins.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("pluginId", row.step.plugin.pluginId)
                                    put("optional", row.step.plugin.optional)
                                    put("result", row.kind.name.lowercase())
                                    put("message", row.message)
                                },
                            )
                        }
                    },
                )
                put(
                    "rules",
                    buildJsonArray {
                        result.rules.forEach { row ->
                            add(
                                buildJsonObject {
                                    put(
                                        "scope",
                                        row.step.rule.scope.name
                                            .lowercase(),
                                    )
                                    put("subject", row.step.rule.subject)
                                    put("action", row.step.rule.action.name)
                                    put("result", row.kind.name.lowercase())
                                    row.step.existing?.let { put("existing", it.name) }
                                },
                            )
                        }
                    },
                )
            }
        }
}

/**
 * Adds the dependency closure a plugin row would install.
 *
 * Consent has to name every id that would arrive, not just the one the pack asked for, and has to
 * say when the walk could not see the whole closure - an empty `alsoInstalls` on a row whose walk
 * failed would otherwise read as "nothing else will be installed".
 *
 * File level so `plan` stays inside the host's method length and complexity limits.
 */
private fun JsonObjectBuilder.putClosure(closure: InstallClosure) {
    if (closure.alsoInstalls.isNotEmpty()) {
        put("alsoInstalls", buildJsonArray { closure.alsoInstalls.forEach { add(JsonPrimitive(it)) } })
    }
    if (!closure.partial) return
    put("closureComplete", false)
    if (closure.unresolved.isNotEmpty()) {
        put("unresolved", buildJsonArray { closure.unresolved.forEach { add(JsonPrimitive(it)) } })
    }
    if (closure.cyclic) put("cyclic", true)
    if (closure.truncated) put("truncated", true)
}
