package ai.rever.boss.ipc

/**
 * Version of the inter-process contract that this BossConsole build speaks.
 *
 * This is the single source of truth for proto/gRPC compatibility between
 * the kernel (host) and microkernel runtime child JVMs. It is consumed in
 * two places today:
 *
 * 1. `PluginManifest.minIpcVersion` on the microkernel runtime — the runtime
 *    advertises the minimum host IPC version it is compatible with.
 * 2. Host-side spawn/install paths (`OutOfProcessPluginSpawnerImpl`,
 *    `PluginStoreSetup`) — refuse to launch or adopt a runtime JAR whose
 *    `minIpcVersion` is incompatible with the running host.
 *
 * ### Bump policy
 *
 * - **Patch** (`1.0.0` → `1.0.1`): code-only fix inside generated classes,
 *   no observable wire change. Runtimes compiled against any previous patch
 *   remain compatible.
 * - **Minor** (`1.0.0` → `1.1.0`): additive only — new RPCs, new optional
 *   fields with fresh field numbers, new enum values. Runtimes compiled
 *   against an older minor stay compatible with a newer host (forward-compat),
 *   and vice-versa (backward-compat via proto3 unknown-field preservation).
 * - **Major** (`1.0.0` → `2.0.0`): breaking change to the wire format —
 *   renumbered fields, removed RPCs, changed types. Runtime and host must
 *   share the same major version.
 *
 * Bump this before publishing changes to the proto or public JVM API.
 * A transport transition also requires its explicit security marker; a numeric
 * minimum alone must not admit a plaintext runtime into an authenticated host.
 */
object IpcVersion {
    /**
     * Current IPC contract version of this host build.
     *
     * History:
     * - 1.4.0 - additive `TerminalService.CloseInput` RPC delivers stdin EOF to a session
     *   without terminating it, so a caller can let a stdin-consuming one-shot command (sort,
     *   grep, cat with no args, ...) exit on its own. Like 1.0.0 below, this bump is not a
     *   capability signal: an older host is still compatible and answers `CloseInput` with
     *   UNIMPLEMENTED, which callers must treat as "not supported" rather than a session fault.
     * - 1.3.0 - MasteryService surfaces guarded edges that fired (the target node
     *   was never invoked) through additive MasteryProgress oneof field 9
     *   NodeSkipped. Old runtimes receive a Progress with an unset oneof - the
     *   default unknown case, which the executor maps to a no-op.
     * - 1.2.0 - remote UI diffs distinguish removed properties from explicit
     *   empty-string values through additive NodeUpdated field 4.
     * - 1.1.0 - authenticated transport and credential-required JVM APIs.
     *   Published boss-ipc artifacts now use a distinct version; the security marker
     *   remains mandatory because version ordering alone does not prove transport compatibility.
     * - 1.0.0 - initial Phase 0 contract. The terminal grid /
     *   cursor / scrollback / shell-event / modifier-aware-input / theme
     *   RPCs are defined in `services/terminal.proto` as reserved
     *   scaffolding but are not implemented by the host. A global IPC version
     *   bump is not a capability signal for this reserved surface; plugins may
     *   rely on it only after a concrete host implementation advertises a
     *   terminal-specific capability. See
     *   issue #743 for the rollback rationale (terminal-tab pivoted to
     *   in-process in PR #742).
     */
    const val CURRENT: String = "1.4.0"

    /**
     * Parse a semver string into (major, minor, patch). Trailing
     * pre-release / build metadata is tolerated and ignored.
     * Returns null if the string is not a recognisable semver.
     */
    fun parse(version: String): Triple<Int, Int, Int>? {
        if (version.isBlank()) return null
        val core = version.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    /**
     * Check whether a runtime whose `minIpcVersion` is [runtimeMinIpcVersion]
     * can talk to a host at [hostIpcVersion] (defaults to [CURRENT]).
     *
     * Rules:
     * - Blank `runtimeMinIpcVersion` → accept (legacy runtimes built before
     *   Phase 0 — treated as "unknown, probably compatible"). Caller may
     *   want to surface a WARN.
     * - Major mismatch → reject.
     * - Runtime's `minIpcVersion` higher than host's version (within the
     *   same major) → reject (host is too old for this runtime).
     * - Otherwise → accept.
     */
    fun isCompatible(
        runtimeMinIpcVersion: String,
        hostIpcVersion: String = CURRENT,
    ): CompatResult {
        if (runtimeMinIpcVersion.isBlank()) return CompatResult.UnknownRuntime
        val rt =
            parse(runtimeMinIpcVersion)
                ?: return CompatResult.Incompatible(
                    "Runtime declares an unparseable minIpcVersion: '$runtimeMinIpcVersion'",
                )
        val host =
            parse(hostIpcVersion)
                ?: return CompatResult.Incompatible(
                    "Host IPC version is unparseable: '$hostIpcVersion'",
                )

        if (rt.first != host.first) {
            return CompatResult.Incompatible(
                "Runtime requires IPC major v${rt.first}.x but host speaks v${host.first}.x " +
                    "(runtime=$runtimeMinIpcVersion, host=$hostIpcVersion). " +
                    if (rt.first > host.first) "Update BossConsole." else "Update the runtime JAR.",
            )
        }
        val rtMM = rt.second * 1_000_000 + rt.third
        val hostMM = host.second * 1_000_000 + host.third
        if (rtMM > hostMM) {
            return CompatResult.Incompatible(
                "Runtime requires IPC ≥$runtimeMinIpcVersion but host is $hostIpcVersion. " +
                    "Update BossConsole.",
            )
        }
        return CompatResult.Compatible
    }

    sealed interface CompatResult {
        data object Compatible : CompatResult

        /** Manifest has no `minIpcVersion` — legacy JAR from before Phase 0. */
        data object UnknownRuntime : CompatResult

        data class Incompatible(
            val reason: String,
        ) : CompatResult
    }
}
