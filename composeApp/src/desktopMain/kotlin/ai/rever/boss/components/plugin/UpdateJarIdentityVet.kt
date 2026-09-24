package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Identity vet for the host plugin Update path (BossConsole#927).
 *
 * Both store installers refuse a downloaded jar unless it declares the row's plugin id AND
 * that id is not `PluginDependencyResolution.NOT_USER_INSTALLABLE`
 * (`StoreVersionInstaller.activate`, `StoreMissingDependencyInstaller.vetAndLoad`), because
 * nothing binds a store row to the plugin id its jar declares, and because a one-click path
 * must never install the api or microkernel layer: `installPlugin` routes an api-declaring
 * jar into `DynamicPluginManager.hotSwapApiLayer`, a process-wide unload/swap/reload. The
 * host's own Update button feeds the same `installPlugin`, and its swap force-unloads the
 * running plugin FIRST - so unvetted, an identity-mismatched jar uninstalled the real
 * plugin and registered whatever its own manifest declared.
 *
 * The gate here enforces the installers' exact two conditions, not the equality half
 * alone: the declared id must equal the plugin being updated, and it must not be
 * protected - a protected id is refused under EVERY row, its own included. The first cut
 * of this gate let the api id pass under its own row as the "designed" runtime api-swap
 * route; that route is now closed to match the installers, whose rule is that
 * NOT_USER_INSTALLABLE reaches no one-click path at all.
 *
 * A refused jar is discarded here together with its `.sig` sidecar ([discardRefused]), so
 * the refusal does not depend on the caller's failure path for cleanup: the update path
 * streams onto a scannable `<pluginId>-<version>.jar`, and bytes left behind are a file
 * the next launch's directory scan would try to load.
 */
internal object UpdateJarIdentityVet {
    private val logger = BossLogger.forComponent("UpdateJarIdentityVet")

    /**
     * Accept [jarPath] only when its manifest declares exactly [pluginId] and that id is
     * user-installable.
     *
     * Fails closed on an unreadable manifest too: a jar whose declared identity cannot be
     * established gives the update nothing to verify, and the caller keeps the running
     * plugin installed rather than swapping in bytes it cannot name. The manifest read
     * error threads into the warn log (`error=`) and rides along as the refusal's cause,
     * so a corrupt zip and an IO failure stay distinguishable from the logs alone.
     */
    fun vet(
        pluginId: String,
        jarPath: String,
    ): Result<Unit> {
        val read = runCatching { PluginManifestReader.readFromJar(jarPath) }
        val declaredId = read.getOrNull()?.pluginId
        val protectedIds = PluginDependencyResolution.NOT_USER_INSTALLABLE
        if (declaredId != pluginId || declaredId in protectedIds) {
            val cause = read.exceptionOrNull()
            logger.warn(
                LogCategory.SYSTEM,
                "Refusing an update jar that does not declare the plugin it updates",
                mapOf(
                    "expected" to pluginId,
                    "declared" to (declaredId ?: "unreadable"),
                    "jarPath" to jarPath,
                ),
                error = cause,
            )
            discardRefused(jarPath)
            return Result.failure(
                IllegalStateException(
                    "The update for $pluginId did not install as $pluginId: " +
                        refusalReason(declaredId, protectedIds) + ". The store entry may be wrong; the " +
                        "running version was kept.",
                    cause,
                ),
            )
        }
        return Result.success(Unit)
    }

    private fun refusalReason(
        declaredId: String?,
        protectedIds: Set<String>,
    ): String =
        when {
            declaredId == null -> {
                "its manifest could not be read"
            }

            declaredId in protectedIds -> {
                "it declares the protected id \"$declaredId\""
            }

            else -> {
                "it declares \"$declaredId\""
            }
        }

    /**
     * Delete a jar this vet refused, and its signature sidecar with it.
     *
     * Mirrors the store installers' refusal branches, which discard the refused bytes
     * before returning, and `PluginUpdateBridge.discardPartialDownload`, which owns the
     * same pair for a failed download. The sidecar must go with the jar: a `.sig` that
     * outlives its jar meets the next download's fresh bytes and hard-fails its load,
     * which is worse than being unsigned. The bridge's own failure-path delete still runs
     * afterwards and becomes a no-op backstop, not the guarantee.
     *
     * Absence is the postcondition, not a delete that returned true: `delete()` returns
     * false rather than throwing when a Windows lock holds the file, so an already-absent
     * jar is success here, and a jar that cannot be removed is reported rather than raised
     * - the refusal is what the caller is waiting on. Best-effort for the sidecar for the
     * same reason.
     */
    private fun discardRefused(jarPath: String) {
        val jar = File(jarPath)
        val gone = runCatching { !jar.exists() || jar.delete() }.getOrDefault(false)
        if (!gone) {
            logger.warn(
                LogCategory.SYSTEM,
                "A refused update jar could not be removed; the next launch would try to load it",
                mapOf("jarPath" to jarPath),
            )
        }
        runCatching { PluginSignatureSidecar.delete(jarPath) }
    }
}
