package ai.rever.boss.plugin

/**
 * Host-side helper for judging whether a plugin version's declared
 * `minIpcVersion` is compatible with this host's IPC contract
 * (`IpcVersion.CURRENT`).
 *
 * The host is the source of truth for `IpcVersion.CURRENT`; the plugin layer
 * (commonMain modules like plugin-updater) can't depend on `:boss-ipc`, so the
 * compatibility decision is computed here and injected where needed.
 *
 * `IpcVersion.CURRENT` is read by reflection because `:boss-ipc` is excluded
 * from the Windows-ARM64 build (no protoc binary — see settings.gradle.kts).
 * When the class is absent, [hostVersion] is null and every status resolves to
 * [Status.UNKNOWN] (nothing to gate — that target has no out-of-process
 * plugins). The status rules mirror `ai.rever.boss.ipc.IpcVersion.isCompatible`:
 * same major required, runtime `minor.patch` must be ≤ the host's.
 */
object IpcCompatibility {

    enum class Status {
        /** Host can load this version. */
        COMPATIBLE,

        /** Same major, but the version needs a newer host minor/patch. */
        REQUIRES_HOST_UPDATE,

        /** Different IPC major — hard incompatible. */
        MAJOR_MISMATCH,

        /** Blank minIpcVersion, or host IPC version unavailable. */
        UNKNOWN
    }

    /**
     * This host's `IpcVersion.CURRENT`, or null when `:boss-ipc` isn't on the
     * classpath (Windows-ARM64 build).
     */
    val hostVersion: String? by lazy {
        try {
            val ipcCls = Class.forName("ai.rever.boss.ipc.IpcVersion")
            ipcCls.getField("CURRENT").get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** Compatibility status of a plugin version declaring [minIpcVersion]. */
    fun status(minIpcVersion: String): Status {
        if (minIpcVersion.isBlank()) return Status.UNKNOWN
        val host = hostVersion ?: return Status.UNKNOWN
        val rt = parse(minIpcVersion) ?: return Status.UNKNOWN
        val h = parse(host) ?: return Status.UNKNOWN
        return when {
            rt.first != h.first -> Status.MAJOR_MISMATCH
            rt.second > h.second -> Status.REQUIRES_HOST_UPDATE
            rt.second == h.second && rt.third > h.third -> Status.REQUIRES_HOST_UPDATE
            else -> Status.COMPATIBLE
        }
    }

    /**
     * True if a plugin declaring [minIpcVersion] can be installed/loaded by
     * this host. Treats UNKNOWN as installable (legacy JARs / no boss-ipc),
     * matching the lenient behaviour of the spawn-time gate.
     */
    fun isInstallable(minIpcVersion: String): Boolean =
        status(minIpcVersion).let { it == Status.COMPATIBLE || it == Status.UNKNOWN }

    private fun parse(version: String): Triple<Int, Int, Int>? {
        val core = version.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }
}
