package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Shared, runtime-updatable classloader over the newest installed
 * boss-plugin-api jar. Installed as the parent of every [PluginClassLoader]
 * (host app classloader above it), it makes the API layer updatable without
 * a BossConsole release:
 *
 * - A type compiled into the HOST resolves parent-first from the host —
 *   identity with host-side implementations is preserved.
 * - A type only present in a NEWER api jar is served from here, once, with a
 *   single Class identity shared by all plugins — so cross-plugin
 *   `getPluginAPI` interfaces added to the SDK work on this host without a
 *   host rebuild.
 *
 * Member additions to host-compiled types are still shadowed by the host's
 * copy (parent-first); those remain host-contract changes gated by
 * minBossVersion. Only brand-new types ship via the jar (minApiVersion).
 *
 * Trust: [fromPluginDir] installs only a jar somebody vouched for — a store
 * sidecar that verifies over the jar's own claimed identity, or a
 * bundled-trust marker matching its bytes. An api jar is classloaded as the
 * PARENT of every plugin, so it gets no rollout warn-path: unverifiable
 * jars degrade the API layer to host-compiled classes instead (BossConsole#851).
 *
 * Lifecycle: created at startup and HOT-SWAPPABLE at runtime. When a newer
 * api jar is installed, DynamicPluginManager.hotSwapApiLayer unloads every
 * plugin, closes this loader (releasing its jar handle), resolves a fresh
 * one over the new jar, and reloads all plugins against it — no app restart.
 * Outside that orchestrated swap the loader is never closed; it holds no
 * references to plugin classloaders, so ordinary plugin unload/GC is
 * unaffected. The updater installs newer api jars under NEW filenames (never
 * in-place), so the open jar handle never blocks the incoming jar.
 */
class ApiClassLoader(
    apiJarUrl: URL?,
    parent: ClassLoader,
) : URLClassLoader(listOfNotNull(apiJarUrl).toTypedArray(), parent) {
    /**
     * Version of the api jar this loader serves (jar manifest
     * Implementation-Version, falling back to plugin.json), or null when no
     * api jar was found (first run offline) — behavior is then identical to
     * the pre-ApiClassLoader host.
     */
    val apiVersion: String? = apiJarUrl?.let { readVersion(File(it.toURI())) }

    /**
     * Absolute path of the api jar, or null when none was found. Published so
     * the out-of-process spawner can append it to child-JVM classpaths (after
     * the runtime + plugin jars, so it only fills in missing types — the flat
     * classpath analogue of this loader's parent-first position).
     */
    val apiJarPath: String? = apiJarUrl?.let { File(it.toURI()).absolutePath }

    /**
     * An api-claiming jar candidate for the newest-selection: the jar, its
     * parsed version (semver comparison key) and the manifest the trust
     * anchor is derived from.
     */
    private data class ApiJarCandidate(
        val jar: File,
        val version: Version,
        val manifest: PluginManifest,
    )

    companion object {
        private val logger = BossLogger.forComponent("ApiClassLoader")

        /** Plugin id of the boss-plugin-api system plugin. */
        const val API_PLUGIN_ID = "ai.rever.boss.plugin.api"

        /**
         * Store-signature verifier used when [fromPluginDir] is called
         * without an injected one (the production paths); the pinned public
         * key is parsed once per process.
         */
        private val storeVerifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS)

        /**
         * Build an ApiClassLoader over the newest VERIFIED boss-plugin-api
         * jar in [pluginDir] (typically ~/.boss/plugins after bundled-copy
         * and reconciliation). Returns an empty loader when none is found —
         * or when none is verifiable: every candidate must pass the
         * fail-closed trust gate ([apiJarRejectionReason], BossConsole#851)
         * before it may become the shared API layer, and a newer
         * unverifiable jar neither wins nor shadows an older verified one.
         *
         * [signatureVerifier] verifies store sidecars and is injectable for
         * tests; production callers use the pinned store key.
         */
        fun fromPluginDir(
            pluginDir: File,
            parent: ClassLoader,
            signatureVerifier: PluginSignatureVerifier = storeVerifier,
        ): ApiClassLoader {
            val newest =
                apiJarCandidates(pluginDir)
                    .sortedByDescending { it.version }
                    .firstNotNullOfOrNull { candidate ->
                        val rejection = apiJarRejectionReason(candidate, signatureVerifier)
                        if (rejection == null) {
                            candidate
                        } else {
                            logger.warn(
                                LogCategory.SYSTEM,
                                "Skipping api-claiming jar without a valid trust proof",
                                mapOf(
                                    "jar" to candidate.jar.name,
                                    "claimedVersion" to candidate.manifest.version,
                                    "reason" to rejection,
                                ),
                            )
                            null
                        }
                    }

            if (newest == null) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "No verifiable boss-plugin-api jar found; API layer limited to host-compiled classes",
                    mapOf(
                        "pluginDir" to pluginDir.absolutePath,
                    ),
                )
                return ApiClassLoader(null, parent)
            }

            val loader = ApiClassLoader(newest.jar.toURI().toURL(), parent)
            logger.info(
                LogCategory.SYSTEM,
                "API layer resolved",
                mapOf(
                    "jar" to newest.jar.name,
                    "apiVersion" to (loader.apiVersion ?: "unknown"),
                ),
            )
            return loader
        }

        /**
         * Scan [pluginDir] for jars whose manifest claims to be the api
         * plugin. Manifest read/validation failures and unparseable version
         * claims drop the jar: a claim that cannot even be parsed never
         * becomes a candidate, let alone the API layer.
         */
        private fun apiJarCandidates(pluginDir: File): List<ApiJarCandidate> =
            pluginDir
                .listFiles { file ->
                    file.isFile && file.extension == "jar"
                }.orEmpty()
                .mapNotNull { jar ->
                    val manifest =
                        try {
                            PluginManifestReader.readFromJar(jar.absolutePath)
                        } catch (e: Exception) {
                            // not a BOSS plugin jar
                            logger.debug(
                                LogCategory.SYSTEM,
                                "Skipping jar without readable plugin manifest",
                                mapOf("jar" to jar.name, "error" to e.toString()),
                            )
                            null
                        }
                    if (manifest?.pluginId == API_PLUGIN_ID) {
                        val version = Version.parse(manifest.version)
                        if (version != null) ApiJarCandidate(jar, version, manifest) else null
                    } else {
                        null
                    }
                }

        /**
         * Fail-closed trust gate a candidate must pass before it is
         * classloaded as the process-wide API layer (BossConsole#851).
         *
         * The api jar becomes the PARENT classloader of every plugin — its
         * classes initialize on first resolution and outrank any single
         * plugin — so unlike plugin loads (see DynamicPluginLoaderImpl's
         * verifySignatureOrThrow) there is NO rollout warn-path for a
         * missing signature: the #102 warn-and-allow window exists so users'
         * plugin installs keep working while signatures are backfilled, but
         * tolerating an unverified jar HERE is arbitrary code execution with
         * maximal reach (exactly the hole #851 reports: an unsigned
         * GitHub-fallback or nulled-signature store jar becoming the shared
         * API layer, surviving the enforcement flip).
         *
         * Accepts exactly the proofs the platform already produces:
         *
         * - a store sidecar whose signature verifies over the canonical
         *   anchor `pluginId|version|sha256` built from the jar's OWN
         *   manifest identity — so a sidecar signed for different bytes or a
         *   different version claim fails (substitution, not just tampering).
         *   Present-but-invalid always rejects, bundled-trust or not,
         *   mirroring plugin loads.
         * - a [PluginBundledTrust] marker matching the jar's CURRENT bytes
         *   (the host's bundled-copy install path, BossConsole#102: bundled
         *   jars ship inside the signed app image and never carry a store
         *   signature).
         * - dev mode (`boss.dev.mode=true`), the same carve-out plugin loads
         *   use so locally built api jars keep loading in development.
         *
         * Returns null to install, or a human-readable reason to reject.
         * Any read/hash error also rejects — a jar that cannot be examined
         * is unverifiable, never "probably fine". The jar is hashed here and
         * re-read by the classloader below; as with plugin loads, that
         * TOCTOU gap is outside the threat model (a local filesystem
         * attacker can already tamper with the plugin dir directly).
         */
        private fun apiJarRejectionReason(
            candidate: ApiJarCandidate,
            signatureVerifier: PluginSignatureVerifier,
        ): String? =
            try {
                val jarPath = candidate.jar.absolutePath
                val signature = PluginSignatureSidecar.read(jarPath)
                if (signature == null) {
                    if (PluginBundledTrust.isTrusted(jarPath)) {
                        // Trusted bundled copy written by the host itself (#102).
                        null
                    } else if (System.getProperty("boss.dev.mode")?.toBoolean() == true) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Api jar has no store signature - installing only because dev mode is on",
                            mapOf(
                                "jar" to candidate.jar.name,
                                "claimedVersion" to candidate.manifest.version,
                            ),
                        )
                        null
                    } else {
                        "no store signature and not a trusted bundled artifact"
                    }
                } else {
                    val sha256 = FileHashing.sha256(candidate.jar)
                    val anchor =
                        PluginStoreTrust.versionAnchor(
                            candidate.manifest.pluginId,
                            candidate.manifest.version,
                            sha256,
                        )
                    val result = signatureVerifier.verifySignedMessage(anchor, signature)
                    if (result.isVerified) {
                        null
                    } else {
                        val reason = (result as? SignatureVerificationResult.Failed)?.reason ?: "unknown"
                        "store signature does not verify: $reason"
                    }
                }
            } catch (e: Exception) {
                // Fail closed: an unreadable or unhashable candidate is
                // unverifiable, never "probably fine".
                "verification error (${e.javaClass.simpleName})"
            }

        private fun readVersion(jar: File): String? {
            val fromManifest =
                try {
                    JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
                } catch (e: Exception) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Could not read Implementation-Version from jar manifest",
                        mapOf("jar" to jar.name, "error" to e.toString()),
                    )
                    null
                }
            if (!fromManifest.isNullOrBlank()) return fromManifest

            return try {
                PluginManifestReader.readFromJar(jar.absolutePath).version
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Could not read version from plugin manifest",
                    mapOf("jar" to jar.name, "error" to e.toString()),
                )
                null
            }
        }
    }

    override fun toString(): String = "ApiClassLoader(apiVersion=$apiVersion, urls=${getURLs().size})"
}
