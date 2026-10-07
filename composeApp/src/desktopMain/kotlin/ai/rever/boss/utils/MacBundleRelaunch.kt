package ai.rever.boss.utils

/** How [ApplicationRestarter] reopens a packaged macOS `BOSS.app` once this process is gone. */
internal object MacBundleRelaunch {
    private val FORWARDED_ENV_NAME = Regex("BOSS_[A-Z0-9_]+")
    private val CREDENTIAL_ENV_NAME = Regex("KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL")

    /**
     * False for a bundle running under App Translocation (`/private/var/folders/.../AppTranslocation/
     * <uuid>/d/BOSS.app`), where macOS runs a quarantined app from a read-only mount it may tear
     * down once the process exits, so `open` on that path after we are gone can find nothing.
     */
    fun isRelaunchable(appPath: String): Boolean = !appPath.contains("/AppTranslocation/")

    /**
     * `--env` flags carrying this process's `BOSS_*` overrides into the relaunched app: `open`
     * starts it through LaunchServices with launchd's environment, which would silently drop a
     * `BOSS_DEV_MODE` (a different data root) or a `BOSS_TOOLKIT_PRELOAD` off switch. Values are
     * expanded by the relauncher's shell from the environment it inherits, so they never appear in
     * the command line; names that look like credentials are not forwarded at all.
     */
    fun forwardedEnvFlags(names: Collection<String>): String =
        names
            .filter { FORWARDED_ENV_NAME.matches(it) && !CREDENTIAL_ENV_NAME.containsMatchIn(it) }
            .sorted()
            .joinToString("") { "--env \"$it=${'$'}$it\" " }

    /**
     * The `sh` command that opens [quotedApp] (already shell-quoted). An `open` too old for `--env`
     * fails outright, so that form falls back to opening without the overrides.
     */
    fun openCommand(
        quotedApp: String,
        envNames: Collection<String> = System.getenv().keys,
    ): String {
        val env = forwardedEnvFlags(envNames)
        return if (env.isEmpty()) "open $quotedApp" else "open $env$quotedApp || open $quotedApp"
    }
}
