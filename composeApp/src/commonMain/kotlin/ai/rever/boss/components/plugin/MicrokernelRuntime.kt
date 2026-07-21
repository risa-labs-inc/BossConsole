package ai.rever.boss.components.plugin

/**
 * Shared coordinates for the microkernel runtime JAR. Referenced by:
 * - [DefaultPlugin] to skip it when scanning ~/.boss/plugins for loadable plugins
 * - `OutOfProcessPluginSpawnerImpl` to locate the runtime JAR on classpath
 * - `PluginStoreSetup` to identify the system plugin that ships it
 *
 * Any rename must be reflected in three places at once — hence the constant.
 */
object MicrokernelRuntime {
    /**
     * File-name prefix used for the runtime fatJar.
     *
     * Source: standalone repo `risa-labs-inc/boss-microkernel-runtime`,
     * `./gradlew fatJar`. Output ends up as
     * `boss-microkernel-runtime-<version>-all.jar`. Distributed via
     * GitHub Releases of the same repo and registered in the BOSS Plugin
     * Store; BossConsole's `PluginStoreSetup.scheduleBackgroundUpdateCheck`
     * keeps `~/.boss/plugins/` up to date.
     */
    const val ARTIFACT_PREFIX: String = "boss-microkernel-runtime"

    /** Plugin ID used by the runtime's `plugin.json` manifest. */
    const val PLUGIN_ID: String = "ai.rever.boss.microkernel.runtime"

    /** GitHub repository that hosts runtime releases. */
    const val GITHUB_REPO: String = "risa-labs-inc/boss-microkernel-runtime"
}
