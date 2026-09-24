package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpFlightPlanInput
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyFault
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.mcpFlightPlan
import ai.rever.boss.mcp.mcpPolicyFaultBlocksInvocation
import ai.rever.boss.plugin.api.RegisteredMcpTool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key

/**
 * Supplies [McpFlightPlanDialog] with a live registry snapshot while it is open.
 *
 * Permission comes from [McpToolRegistryImpl.permittedToolNames] — the registry's
 * permission set, which keeps user-disabled tools — and NOT from the exposed `tools`
 * set, which folds the kill-switch in (review on #1380): a tool the operator switched
 * off but is allowed to run must report the tool-switch checkpoint, not "no permission".
 *
 * The plan is re-derived when policy state moves: `key` over the policy engine's
 * `config`/`sessionTrustedTools` snapshots subscribes this composable to both, and
 * `policyFor` — which resolves against exactly that state — re-answers for every tool.
 * Without it, a plan left open would keep showing the policy that was current when it
 * opened, through any rule edit or session-trust change made while it was on screen.
 */
@Composable
fun McpFlightPlanLauncher(onDismiss: () -> Unit) {
    val allTools by McpToolRegistryImpl.allTools.collectAsState()
    val disabledToolNames by McpToolRegistryImpl.disabledToolNames.collectAsState()
    val permittedToolNames by McpToolRegistryImpl.permittedToolNames.collectAsState()
    val policyFault by McpToolRegistryImpl.policyFault.collectAsState()
    val policyEngine = McpToolRegistryImpl.policyEngine
    val policyConfig by policyEngine.config.collectAsState()
    val sessionTrustedTools by policyEngine.sessionTrustedTools.collectAsState()
    val plans =
        key(policyConfig, sessionTrustedTools) {
            mcpFlightPlanViews(
                allTools = allTools,
                disabledToolNames = disabledToolNames,
                permittedToolNames = permittedToolNames,
                policyFault = policyFault,
                resolvePolicy = { tool ->
                    policyEngine.policyFor(
                        tool.definition.name,
                        tool.providerId,
                        tool.definition.readOnly,
                    )
                },
            )
        }
    McpFlightPlanDialog(plans = plans, onDismiss = onDismiss)
}

/**
 * Derive one tool's [McpFlightPlanInput] from live registry state.
 *
 * [permittedToolNames] is the registry's permission set — user-disabled tools included —
 * so a switched-off tool the operator may run keeps `isPermitted = true` and the plan
 * blames the kill-switch, not RBAC. Deriving from the exposed `tools` set instead
 * conflated the two gates and reported switched-off tools as permission failures
 * (review on #1380).
 *
 * [policy] must come from `McpPolicyEngine.policyFor` with the same provider and
 * `declaredReadOnly` as the real invocation — see [mcpFlightPlan].
 */
internal fun mcpFlightPlanInput(
    tool: RegisteredMcpTool,
    disabledToolNames: Set<String>,
    permittedToolNames: Set<String>,
    policy: McpPolicyAction,
    policyFault: McpPolicyFault?,
): McpFlightPlanInput =
    McpFlightPlanInput(
        toolName = tool.definition.name,
        providerId = tool.providerId,
        declaredReadOnly = tool.definition.readOnly,
        isEnabled = tool.definition.name !in disabledToolNames,
        isPermitted = "${tool.providerId}/${tool.definition.name}" in permittedToolNames,
        policy = policy,
        policyFaulted = mcpPolicyFaultBlocksInvocation(policyFault),
    )

/**
 * One tool's [McpFlightPlanView]: the registry metadata the dialog lists, plus the
 * forecast [mcpFlightPlan] derives from [input]. The input arrives as a parameter so
 * a test can pin exactly what the launcher feeds the forecast.
 */
internal fun mcpFlightPlanView(
    tool: RegisteredMcpTool,
    input: McpFlightPlanInput,
): McpFlightPlanView =
    McpFlightPlanView(
        providerId = tool.providerId,
        toolName = tool.definition.name,
        description = tool.definition.description,
        inputSchema = tool.definition.inputSchema,
        plan = mcpFlightPlan(input),
    )

/**
 * The launcher's whole derivation: every registered tool's view, sorted by name.
 *
 * [resolvePolicy] is the only live consult — the composable supplies the policy
 * engine's `policyFor` — so this stays a pure function of its arguments and cannot
 * become an alternate policy resolver. Tests drive a real `McpToolRegistryCore`
 * through this exact function, so the disabled path they exercise is the one the
 * composable runs (review on #1380).
 */
internal fun mcpFlightPlanViews(
    allTools: List<RegisteredMcpTool>,
    disabledToolNames: Set<String>,
    permittedToolNames: Set<String>,
    policyFault: McpPolicyFault?,
    resolvePolicy: (RegisteredMcpTool) -> McpPolicyAction,
): List<McpFlightPlanView> =
    allTools
        .map { tool ->
            val input =
                mcpFlightPlanInput(
                    tool = tool,
                    disabledToolNames = disabledToolNames,
                    permittedToolNames = permittedToolNames,
                    policy = resolvePolicy(tool),
                    policyFault = policyFault,
                )
            mcpFlightPlanView(tool = tool, input = input)
        }.sortedBy { it.toolName }
