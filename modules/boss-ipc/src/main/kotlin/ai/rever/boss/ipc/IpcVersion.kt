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
 * Bump this BEFORE merging any change to `boss-ipc/src/main/proto/`.
 */
object IpcVersion {
    /**
     * Current IPC contract version of this host build.
     *
     * History:
     * - 1.0.0 — initial Phase 0 contract. Current. The terminal grid /
     *   cursor / scrollback / shell-event / modifier-aware-input / theme
     *   RPCs are defined in `services/terminal.proto` as reserved
     *   scaffolding but are not implemented by the host. A future minor
     *   bump (1.1.0) is required before any plugin may rely on them; see
     *   issue #743 for the rollback rationale (terminal-tab pivoted to
     *   in-process in PR #742).
     */
    const val CURRENT: String = "1.0.0"

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
